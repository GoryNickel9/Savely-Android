"""Offline A04 ledger fixtures. Fake SELECTs only; never load a vault or transport."""
import copy
import json
from pathlib import Path
import tempfile
import unittest

import mfa_form_evidence as mfa
from test_couple_invitation_evidence import snapshot as qa_snapshot, time

RUN = "11111111-1111-4111-8111-111111111111"
F1 = "22222222-2222-4222-8222-222222222222"
F2 = "33333333-3333-4333-8333-333333333333"
S1 = "44444444-4444-4444-8444-444444444444"
S2 = "55555555-5555-4555-8555-555555555555"


class FakeExecutor:
    def __init__(self):
        self.value = qa_snapshot()
        self.value.update(mfa_factor_rows=[], active_session_rows=[], shared_expenses=0, couple_budgets=0)
        self.calls = []
        self.error = None

    def __call__(self, sql, read_only=True):
        self.calls.append((sql, read_only))
        if sql != mfa.SNAPSHOT_SQL or read_only is not True:
            raise AssertionError("Only the fixed read-only snapshot is allowed")
        if self.error:
            raise self.error
        return [{"snapshot": copy.deepcopy(self.value)}]

    def stage(self, stage, removal_aal="aal2"):
        # Literal checkpoint fixture independent of production's transition function.
        factor, status, session, aal = {
            "pending_cancel_ready": (F1, "unverified", S1, "aal1"),
            "pending_cancel_kept": (F1, "unverified", S1, "aal1"),
            "pending_persisted": (F1, "unverified", S1, "aal1"),
            "pending_removed": (None, None, S1, "aal1"),
            "enrollment_ready": (F2, "unverified", S1, "aal1"),
            "enrollment_wrong": (F2, "unverified", S1, "aal1"),
            "enrollment_verified": (F2, "verified", S1, "aal2"),
            "logged_out": (F2, "verified", None, None),
            "login_gate_wrong": (F2, "verified", S2, "aal1"),
            "login_gate_verified": (F2, "verified", S2, "aal2"),
            "removal_cancelled": (F2, "verified", S2, "aal2"),
            "removal_wrong": (F2, "verified", S2, "aal2"),
            "removal_completed": (None, None, S2, removal_aal),
            "completed": (None, None, None, None),
        }[stage]
        now = time(mfa.STAGES.index(stage) + 1)
        self.value["now"] = now
        self.value["mfa_factor_rows"] = [] if factor is None else [{
            "id": factor, "user_id": mfa.A, "status": status, "factor_type": "totp",
            "friendly_name": "Savely Android", "created_at": time(1 if factor == F1 else 5), "updated_at": now}]
        self.value["active_session_rows"] = [] if session is None else [{
            "id": session, "user_id": mfa.A, "aal": aal,
            "created_at": time(1 if session == S1 else 9), "updated_at": now, "not_after": None}]
        self.value["factors"] = len(self.value["mfa_factor_rows"])
        self.value["sessions"] = len(self.value["active_session_rows"])


class MfaFormEvidenceTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.executor = FakeExecutor()
        self.ledger = mfa.MfaFormEvidence(RUN, self.executor, root=Path(self.temp.name))

    def ready(self, stage):
        return {"run_id": RUN, "user_id": mfa.A, "stage": stage, "status": "ready"}

    def advance(self, through, removal_aal="aal2"):
        if not self.ledger.path("preflight-observed").exists():
            self.ledger.preflight()
        for stage in mfa.STAGES:
            self.executor.stage(stage, removal_aal)
            self.ledger.observe(stage, self.ready(stage))
            if stage == through:
                break

    def persisted_text(self):
        return "\n".join(path.read_text(encoding="utf-8") for path in self.ledger.directory.glob("*.json"))

    def fail(self, code, function):
        with self.assertRaisesRegex(mfa.EvidenceError, "^" + code + "$"):
            function()

    def test_all_fourteen_stages_bind_two_factors_and_sessions_without_writes(self):
        self.advance("completed")
        _, final, bindings, completed = self.ledger.history()
        self.assertEqual(list(mfa.STAGES), completed)
        self.assertEqual([F1, F2, S1, S2], [bindings[k]["id"] for k in
            ("first_factor", "second_factor", "first_session", "second_session")])
        self.assertEqual((0, 0), (final["factors"], final["sessions"]))
        self.assertEqual(15, len(self.executor.calls))
        self.assertTrue(all(sql == mfa.SNAPSHOT_SQL and read_only is True for sql, read_only in self.executor.calls))
        self.assertEqual(30, len(list(self.ledger.directory.glob("*.json"))))

    def test_removal_completed_records_aal1_without_claiming_aal2(self):
        self.advance("completed", removal_aal="aal1")
        proof = self.ledger.read("removal_completed-observed")
        self.assertEqual("aal1", proof["snapshot"]["active_session_rows"][0]["aal"])

    def test_out_of_order_and_completed_replay_make_no_read(self):
        self.ledger.preflight()
        self.fail("CHECKPOINT_ORDER_INVALID", lambda: self.ledger.observe("pending_persisted", self.ready("pending_persisted")))
        self.assertEqual(1, len(self.executor.calls))
        self.executor.stage("pending_cancel_ready")
        self.ledger.observe("pending_cancel_ready", self.ready("pending_cancel_ready"))
        self.fail("CHECKPOINT_ORDER_INVALID", lambda: self.ledger.observe("pending_cancel_ready", self.ready("pending_cancel_ready")))
        self.assertEqual(2, len(self.executor.calls))

    def test_unknown_existing_factor_blocks_preflight_and_new_adoption(self):
        self.executor.stage("pending_cancel_ready")
        self.fail("PREFLIGHT_AUTH_NOT_EMPTY", self.ledger.preflight)
        self.fail("PREFLIGHT_NOT_COMPLETE", lambda: self.ledger.observe("pending_cancel_ready", self.ready("pending_cancel_ready")))
        self.assertEqual(1, len(self.executor.calls))

    def test_other_qa_factor_or_session_is_rejected_and_not_persisted(self):
        for field in ("mfa_factor_rows", "active_session_rows"):
            for owner in (mfa.B, next(user for user in mfa.QA if user not in (mfa.A, mfa.B))):
                with self.subTest(field=field, owner=owner), tempfile.TemporaryDirectory() as root:
                    fake = FakeExecutor()
                    ledger = mfa.MfaFormEvidence(RUN, fake, root=Path(root))
                    ledger.preflight()
                    fake.stage("pending_cancel_ready")
                    fake.value[field][0]["user_id"] = owner
                    self.fail("FOREIGN_QA_AUTH_STATE", lambda: ledger.observe("pending_cancel_ready", self.ready("pending_cancel_ready")))
                    self.assertFalse(ledger.path("pending_cancel_ready-observed").exists())

    def test_extra_secret_row_fields_are_rejected_before_persistence(self):
        self.ledger.preflight()
        self.executor.stage("pending_cancel_ready")
        self.executor.value["mfa_factor_rows"][0]["secret"] = "PRIVATE-DO-NOT-PERSIST"
        self.fail("AUTH_ROW_FIELDS_INVALID", lambda: self.ledger.observe("pending_cancel_ready", self.ready("pending_cancel_ready")))
        self.assertNotIn("PRIVATE-DO-NOT-PERSIST", self.persisted_text())
        self.assertNotIn('"secret"', self.persisted_text())

    def test_report_secret_fields_wrong_owner_status_and_stage_reject_before_read(self):
        self.ledger.preflight()
        for changed, code in (({"token": "PRIVATE-DO-NOT-PERSIST"}, "NATIVE_REPORT_FIELDS_INVALID"),
                              ({"status": "passed"}, "NATIVE_REPORT_SCOPE_INVALID"),
                              ({"user_id": mfa.B}, "NATIVE_REPORT_SCOPE_INVALID"),
                              ({"run_id": F1}, "NATIVE_REPORT_SCOPE_INVALID"),
                              ({"stage": "completed"}, "NATIVE_REPORT_SCOPE_INVALID")):
            with self.subTest(changed=changed):
                report = {**self.ready("pending_cancel_ready"), **changed}
                self.fail(code, lambda: self.ledger.observe("pending_cancel_ready", report))
        self.assertEqual(1, len(self.executor.calls))
        self.assertNotIn("PRIVATE-DO-NOT-PERSIST", self.persisted_text())

    def test_failed_read_is_sanitized_and_refuses_retry_even_with_new_instance(self):
        self.ledger.preflight()
        self.executor.error = RuntimeError("PRIVATE-DO-NOT-PERSIST")
        self.fail("READ_OUTCOME_UNCERTAIN", lambda: self.ledger.observe("pending_cancel_ready", self.ready("pending_cancel_ready")))
        self.assertNotIn("PRIVATE-DO-NOT-PERSIST", self.persisted_text())
        fresh = mfa.MfaFormEvidence(RUN, self.executor, root=Path(self.temp.name))
        self.fail("CHECKPOINT_UNCERTAIN_NO_REPLAY", lambda: fresh.observe("pending_cancel_ready", self.ready("pending_cancel_ready")))
        self.assertEqual(2, len(self.executor.calls))

    def test_interrupted_reservation_alone_blocks_retry_without_read(self):
        self.ledger.preflight()
        self.ledger.save("pending_cancel_ready-reservation", {"status": "reserved", "native_report": self.ready("pending_cancel_ready")})
        self.fail("CHECKPOINT_UNCERTAIN_NO_REPLAY", lambda: self.ledger.observe("pending_cancel_ready", self.ready("pending_cancel_ready")))
        self.assertEqual(1, len(self.executor.calls))

    def test_factor_status_cannot_verify_before_wrong_code_checkpoint(self):
        self.advance("pending_removed")
        self.executor.stage("enrollment_ready")
        self.executor.value["mfa_factor_rows"][0]["status"] = "verified"
        self.fail("CHECKPOINT_FACTOR_STATUS_INVALID", lambda: self.ledger.observe("enrollment_ready", self.ready("enrollment_ready")))

    def test_new_factor_cannot_reuse_removed_id(self):
        self.advance("pending_removed")
        self.executor.stage("enrollment_ready")
        self.executor.value["mfa_factor_rows"][0]["id"] = F1
        self.fail("AUTH_ID_REUSED", lambda: self.ledger.observe("enrollment_ready", self.ready("enrollment_ready")))

    def test_new_login_requires_distinct_session_and_aal1(self):
        self.advance("logged_out")
        self.executor.stage("login_gate_wrong")
        self.executor.value["active_session_rows"][0]["id"] = S1
        self.fail("AUTH_ID_REUSED", lambda: self.ledger.observe("login_gate_wrong", self.ready("login_gate_wrong")))

    def test_same_session_cannot_change_while_pending(self):
        self.advance("pending_cancel_ready")
        self.executor.stage("pending_cancel_kept")
        self.executor.value["active_session_rows"][0]["id"] = S2
        self.fail("AUTH_BINDING_CHANGED", lambda: self.ledger.observe("pending_cancel_kept", self.ready("pending_cancel_kept")))

    def test_database_clock_reversal_blocks_checkpoint(self):
        self.advance("pending_cancel_kept")
        self.executor.stage("pending_persisted")
        self.executor.value["now"] = time(1)
        self.executor.value["mfa_factor_rows"][0]["updated_at"] = time(1)
        self.executor.value["active_session_rows"][0]["updated_at"] = time(1)
        self.fail("DATABASE_CLOCK_REVERSED", lambda: self.ledger.observe("pending_persisted", self.ready("pending_persisted")))

    def test_changed_profile_permission_blocks_even_when_auth_matches(self):
        self.ledger.preflight()
        self.executor.stage("pending_cancel_ready")
        self.executor.value["profiles"][0]["permissions"]["fire"] = True
        self.fail("QA_PROJECTION_CHANGED", lambda: self.ledger.observe("pending_cancel_ready", self.ready("pending_cancel_ready")))

    def test_count_truncation_duplicate_and_invalid_friendly_name_reject(self):
        value = FakeExecutor()
        value.stage("pending_cancel_ready")
        truncated = copy.deepcopy(value.value)
        truncated["factors"] = 31
        self.fail("AUTH_COUNT_MISMATCH", lambda: mfa.validate_snapshot(truncated))
        duplicate = copy.deepcopy(value.value)
        duplicate["mfa_factor_rows"] *= 2
        duplicate["factors"] = 2
        self.fail("AUTH_ID_DUPLICATED", lambda: mfa.validate_snapshot(duplicate))
        renamed = copy.deepcopy(value.value)
        renamed["mfa_factor_rows"][0]["friendly_name"] = "Unknown factor"
        self.fail("FACTOR_PAYLOAD_INVALID", lambda: mfa.validate_snapshot(renamed))

    def test_aal_expiry_and_business_state_do_not_satisfy_checkpoint(self):
        self.ledger.preflight()
        self.executor.stage("pending_cancel_ready")
        for field, wrong, code in (("aal", "aal3", "SESSION_AAL_INVALID"),
                                   ("not_after", time(1), "SESSION_ALREADY_EXPIRED")):
            value = copy.deepcopy(self.executor.value)
            value["active_session_rows"][0][field] = wrong
            self.fail(code, lambda: mfa.validate_snapshot(value))
        for field in ("transactions", "connections", "request_count", "audit_count", "shared_expenses", "couple_budgets"):
            value = copy.deepcopy(self.executor.value)
            value[field] = 1
            self.fail("QA_BUSINESS_NOT_EMPTY", lambda: mfa.validate_snapshot(value))

    def test_auth_created_before_preflight_and_early_aal2_are_rejected(self):
        self.ledger.preflight()
        self.executor.stage("pending_cancel_ready")
        self.executor.value["mfa_factor_rows"][0]["created_at"] = time(0)
        self.fail("AUTH_PREDATES_PREFLIGHT", lambda: self.ledger.observe("pending_cancel_ready", self.ready("pending_cancel_ready")))
        with tempfile.TemporaryDirectory() as root:
            fake = FakeExecutor()
            ledger = mfa.MfaFormEvidence(RUN, fake, root=Path(root))
            ledger.preflight()
            fake.stage("pending_cancel_ready")
            fake.value["active_session_rows"][0]["aal"] = "aal2"
            self.fail("CHECKPOINT_AAL_INVALID", lambda: ledger.observe("pending_cancel_ready", self.ready("pending_cancel_ready")))

    def test_duplicate_preflight_is_exclusive_and_does_not_read_again(self):
        self.ledger.preflight()
        self.fail("EVIDENCE_ALREADY_EXISTS", self.ledger.preflight)
        self.assertEqual(1, len(self.executor.calls))


if __name__ == "__main__":
    unittest.main()
