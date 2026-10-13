"""Offline A05 tests; no credentials, HTTP, device or Management construction."""
import copy
import json
from pathlib import Path
import tempfile
import unittest

import session_form_evidence as sessions
from test_couple_invitation_evidence import snapshot as qa_snapshot, time

RUN = "11111111-1111-4111-8111-111111111111"
PRIMARY = "22222222-2222-4222-8222-222222222222"
SECONDARY = "33333333-3333-4333-8333-333333333333"
SURVIVOR = "44444444-4444-4444-8444-444444444444"
UNKNOWN = "55555555-5555-4555-8555-555555555555"


class FakeExecutor:
    def __init__(self):
        self.value = qa_snapshot()
        self.value.update(mfa_factor_rows=[], active_session_rows=[], shared_expenses=0, couple_budgets=0)
        self.calls = []
        self.error = None

    def __call__(self, sql, read_only=True):
        self.calls.append((sql, read_only))
        if sql != sessions.SNAPSHOT_SQL or read_only is not True:
            raise AssertionError("Unexpected SQL or mutation")
        if self.error:
            raise self.error
        return [{"snapshot": copy.deepcopy(self.value)}]

    def stage(self, stage):
        # Independent literal fixtures, not generated from EXPECTED or transition().
        identifiers = {
            "primary_ready": [PRIMARY],
            "secondary_ready": [PRIMARY, SECONDARY],
            "revocation_cancelled": [PRIMARY, SECONDARY],
            "secondary_revoked": [PRIMARY],
            "secondary_refresh_rejected": [PRIMARY],
            "survivor_ready": [PRIMARY, SURVIVOR],
            "primary_revoked": [SURVIVOR],
            "survivor_refreshed": [SURVIVOR],
            "completed": [],
        }[stage]
        now = time(sessions.STAGES.index(stage) + 1)
        created = {PRIMARY: time(1), SECONDARY: time(2), SURVIVOR: time(6)}
        self.value["now"] = now
        self.value["active_session_rows"] = [{"id": identifier, "user_id": sessions.A,
            "aal": "aal1", "created_at": created[identifier], "updated_at": now,
            "not_after": None} for identifier in identifiers]
        self.value["sessions"] = len(identifiers)


class SessionFormEvidenceTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.executor = FakeExecutor()
        self.ledger = sessions.SessionFormEvidence(RUN, self.executor, root=Path(self.temp.name))

    def ready(self, stage):
        return {"run_id": RUN, "user_id": sessions.A, "stage": stage, "status": "ready"}

    def advance(self, through):
        if not self.ledger.path("preflight-observed").exists():
            self.ledger.preflight()
        for stage in sessions.STAGES:
            self.executor.stage(stage)
            self.ledger.observe(stage, self.ready(stage))
            if stage == through:
                break

    def reject(self, code, action):
        with self.assertRaisesRegex(sessions.EvidenceError, "^" + code + "$"):
            action()

    def persisted(self):
        return "\n".join(p.read_text(encoding="utf-8") for p in self.ledger.directory.glob("*.json"))

    def test_nine_checkpoints_bind_three_sessions_and_only_execute_fixed_reads(self):
        self.advance("completed")
        _, final, bindings, completed = self.ledger.history()
        self.assertEqual(list(sessions.STAGES), completed)
        self.assertEqual([PRIMARY, SECONDARY, SURVIVOR], [bindings[k]["id"] for k in
            ("primary", "secondary", "survivor")])
        self.assertEqual((0, 0), (final["sessions"], final["factors"]))
        self.assertEqual(10, len(self.executor.calls))
        self.assertTrue(all(sql == sessions.SNAPSHOT_SQL and readonly is True
                            for sql, readonly in self.executor.calls))
        self.assertEqual(20, len(list(self.ledger.directory.glob("*.json"))))

    def test_out_of_order_and_replay_reject_before_read(self):
        self.ledger.preflight()
        self.reject("CHECKPOINT_ORDER_INVALID", lambda: self.ledger.observe("secondary_ready", self.ready("secondary_ready")))
        self.executor.stage("primary_ready")
        self.ledger.observe("primary_ready", self.ready("primary_ready"))
        self.reject("CHECKPOINT_ORDER_INVALID", lambda: self.ledger.observe("primary_ready", self.ready("primary_ready")))
        self.assertEqual(2, len(self.executor.calls))

    def test_completed_run_cannot_replay(self):
        self.advance("completed")
        self.reject("CHECKPOINT_ORDER_INVALID", lambda: self.ledger.observe("completed", self.ready("completed")))
        self.assertEqual(10, len(self.executor.calls))

    def test_existing_session_blocks_preflight_and_duplicate_preflight_cannot_read(self):
        self.executor.stage("primary_ready")
        self.reject("PREFLIGHT_AUTH_NOT_EMPTY", self.ledger.preflight)
        self.reject("EVIDENCE_ALREADY_EXISTS", self.ledger.preflight)
        self.reject("PREFLIGHT_NOT_COMPLETE", lambda: self.ledger.observe("primary_ready", self.ready("primary_ready")))
        self.assertEqual(1, len(self.executor.calls))

    def test_factor_presence_is_never_accepted_or_persisted(self):
        self.ledger.preflight()
        self.executor.stage("primary_ready")
        self.executor.value["factors"] = 1
        self.executor.value["mfa_factor_rows"] = [{"id": UNKNOWN, "user_id": sessions.A,
            "status": "unverified", "factor_type": "totp", "friendly_name": "Savely Android",
            "created_at": time(1), "updated_at": time(1)}]
        self.reject("QA_FACTORS_NOT_EMPTY", lambda: self.ledger.observe("primary_ready", self.ready("primary_ready")))
        self.assertFalse(self.ledger.path("primary_ready-observed").exists())

    def test_foreign_qa_session_rejected_before_persistence(self):
        for owner in (sessions.B, next(owner for owner in sessions.QA if owner not in (sessions.A, sessions.B))):
            with self.subTest(owner=owner), tempfile.TemporaryDirectory() as root:
                fake = FakeExecutor()
                ledger = sessions.SessionFormEvidence(RUN, fake, root=Path(root))
                ledger.preflight()
                fake.stage("primary_ready")
                fake.value["active_session_rows"][0]["user_id"] = owner
                self.reject("FOREIGN_QA_AUTH_STATE", lambda: ledger.observe("primary_ready", self.ready("primary_ready")))
                self.assertFalse(ledger.path("primary_ready-observed").exists())

    def test_extra_secret_fields_and_bad_native_report_never_persist(self):
        self.ledger.preflight()
        for change, code in (({"token": "DO_NOT_PERSIST"}, "NATIVE_REPORT_FIELDS_INVALID"),
                             ({"status": "passed"}, "NATIVE_REPORT_SCOPE_INVALID"),
                             ({"user_id": sessions.B}, "NATIVE_REPORT_SCOPE_INVALID"),
                             ({"run_id": UNKNOWN}, "NATIVE_REPORT_SCOPE_INVALID")):
            self.reject(code, lambda: self.ledger.observe("primary_ready", {**self.ready("primary_ready"), **change}))
        self.assertEqual(1, len(self.executor.calls))
        self.executor.stage("primary_ready")
        self.executor.value["active_session_rows"][0]["refresh_token"] = "DO_NOT_PERSIST"
        self.reject("AUTH_ROW_FIELDS_INVALID", lambda: self.ledger.observe("primary_ready", self.ready("primary_ready")))
        self.assertNotIn("DO_NOT_PERSIST", self.persisted())

    def test_failed_read_is_sanitized_and_new_instance_cannot_retry(self):
        self.ledger.preflight()
        self.executor.error = sessions.EvidenceError("PRIVATE_TRANSPORT_DATA")
        self.reject("READ_OUTCOME_UNCERTAIN", lambda: self.ledger.observe("primary_ready", self.ready("primary_ready")))
        self.assertNotIn("PRIVATE_TRANSPORT_DATA", self.persisted())
        fresh = sessions.SessionFormEvidence(RUN, self.executor, root=Path(self.temp.name))
        self.reject("CHECKPOINT_UNCERTAIN_NO_REPLAY", lambda: fresh.observe("primary_ready", self.ready("primary_ready")))
        self.assertEqual(2, len(self.executor.calls))

    def test_interrupted_reservation_blocks_read_and_replay(self):
        self.ledger.preflight()
        self.ledger.save("primary_ready-reservation", {"status": "reserved", "native_report": self.ready("primary_ready")})
        self.reject("CHECKPOINT_UNCERTAIN_NO_REPLAY", lambda: self.ledger.observe("primary_ready", self.ready("primary_ready")))
        self.assertEqual(1, len(self.executor.calls))

    def test_primary_identity_cannot_be_substituted_when_secondary_is_added(self):
        self.advance("primary_ready")
        self.executor.stage("secondary_ready")
        self.executor.value["active_session_rows"][0]["id"] = UNKNOWN
        self.reject("NEW_SESSION_NOT_UNIQUE", lambda: self.ledger.observe("secondary_ready", self.ready("secondary_ready")))

    def test_cancel_cannot_remove_a_session(self):
        self.advance("secondary_ready")
        self.executor.stage("revocation_cancelled")
        self.executor.value["active_session_rows"].pop()
        self.executor.value["sessions"] = 1
        self.reject("CHECKPOINT_SESSION_COUNT_INVALID", lambda: self.ledger.observe("revocation_cancelled", self.ready("revocation_cancelled")))

    def test_revocation_must_remove_secondary_not_primary(self):
        self.advance("revocation_cancelled")
        self.executor.stage("secondary_revoked")
        self.executor.value["active_session_rows"][0]["id"] = SECONDARY
        self.reject("SESSION_BINDING_CHANGED", lambda: self.ledger.observe("secondary_revoked", self.ready("secondary_revoked")))

    def test_rejected_refresh_cannot_resurrect_revoked_session(self):
        self.advance("secondary_revoked")
        self.executor.stage("secondary_refresh_rejected")
        self.executor.value["active_session_rows"][0]["id"] = SECONDARY
        self.reject("SESSION_BINDING_CHANGED", lambda: self.ledger.observe("secondary_refresh_rejected", self.ready("secondary_refresh_rejected")))

    def test_survivor_must_be_distinct_from_both_prior_sessions(self):
        self.advance("secondary_refresh_rejected")
        self.executor.stage("survivor_ready")
        self.executor.value["active_session_rows"][1]["id"] = SECONDARY
        self.reject("NEW_SESSION_NOT_UNIQUE", lambda: self.ledger.observe("survivor_ready", self.ready("survivor_ready")))

    def test_survivor_refresh_keeps_same_id_and_aal1(self):
        self.advance("primary_revoked")
        self.executor.stage("survivor_refreshed")
        self.executor.value["active_session_rows"][0]["id"] = UNKNOWN
        self.reject("SESSION_BINDING_CHANGED", lambda: self.ledger.observe("survivor_refreshed", self.ready("survivor_refreshed")))

    def test_session_creation_must_follow_preflight_and_previous_checkpoint(self):
        self.ledger.preflight()
        self.executor.stage("primary_ready")
        self.executor.value["active_session_rows"][0]["created_at"] = time(0)
        self.reject("SESSION_PREDATES_PREFLIGHT", lambda: self.ledger.observe("primary_ready", self.ready("primary_ready")))
        with tempfile.TemporaryDirectory() as root:
            fake = FakeExecutor()
            ledger = sessions.SessionFormEvidence(RUN, fake, root=Path(root))
            ledger.preflight()
            fake.stage("primary_ready")
            ledger.observe("primary_ready", self.ready("primary_ready"))
            fake.stage("secondary_ready")
            fake.value["active_session_rows"][1]["created_at"] = "2026-10-12T12:00:00.500000+00:00"
            self.reject("NEW_SESSION_PREDATES_CHECKPOINT", lambda: ledger.observe("secondary_ready", self.ready("secondary_ready")))

    def test_projection_registration_business_and_aal_changes_rejected(self):
        fake = FakeExecutor()
        fake.stage("primary_ready")
        for field in ("transactions", "connections", "request_count", "audit_count", "shared_expenses", "couple_budgets"):
            value = copy.deepcopy(fake.value)
            value[field] = 1
            self.reject("QA_BUSINESS_NOT_EMPTY", lambda: sessions.clean_snapshot(value))
        value = copy.deepcopy(fake.value)
        value["registrations"] = "true"
        self.reject("REGISTRATIONS_CHANGED", lambda: sessions.clean_snapshot(value))
        self.ledger.preflight()
        self.executor.stage("primary_ready")
        self.executor.value["profiles"][0]["permissions"]["fire"] = True
        self.reject("QA_PROJECTION_CHANGED", lambda: self.ledger.observe("primary_ready", self.ready("primary_ready")))
        with tempfile.TemporaryDirectory() as root:
            ledger = sessions.SessionFormEvidence(RUN, fake, root=Path(root))
            fake.value = FakeExecutor().value
            ledger.preflight()
            fake.stage("primary_ready")
            fake.value["active_session_rows"][0]["aal"] = "aal2"
            self.reject("CHECKPOINT_AAL_INVALID", lambda: ledger.observe("primary_ready", self.ready("primary_ready")))

    def test_snapshot_clock_cannot_reverse(self):
        self.advance("secondary_ready")
        self.executor.stage("revocation_cancelled")
        self.executor.value["now"] = time(2)
        for row in self.executor.value["active_session_rows"]:
            row["updated_at"] = time(2)
        # Equal database clock is legal; reverse to 1 after removing the second row
        # timestamp inconsistency so the transition, not basic validation, rejects.
        self.executor.value["now"] = time(1)
        for row in self.executor.value["active_session_rows"]:
            row["created_at"] = time(1)
            row["updated_at"] = time(1)
        self.reject("DATABASE_CLOCK_REVERSED", lambda: self.ledger.observe("revocation_cancelled", self.ready("revocation_cancelled")))

    def test_journal_binding_tamper_blocks_fresh_reader_before_http(self):
        self.advance("primary_ready")
        path = self.ledger.path("primary_ready-observed")
        value = json.loads(path.read_text(encoding="utf-8"))
        value["bindings"]["primary"]["id"] = UNKNOWN
        path.write_text(json.dumps(value), encoding="utf-8")
        fresh = sessions.SessionFormEvidence(RUN, self.executor, root=Path(self.temp.name))
        self.reject("JOURNAL_BINDINGS_CHANGED", lambda: fresh.observe("secondary_ready", self.ready("secondary_ready")))
        self.assertEqual(2, len(self.executor.calls))

    def test_completed_requires_no_remaining_session(self):
        self.advance("survivor_refreshed")
        self.executor.value["now"] = time(9)
        self.reject("CHECKPOINT_SESSION_COUNT_INVALID", lambda: self.ledger.observe("completed", self.ready("completed")))


if __name__ == "__main__":
    unittest.main()
