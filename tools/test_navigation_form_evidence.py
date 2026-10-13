"""Offline V02 tests; no vault, HTTP, device or live Management construction."""
import copy
import json
from pathlib import Path
import tempfile
import unittest

import admin_form_evidence as admin
import navigation_form_evidence as nav
from test_admin_form_evidence import FakeExecutor as AdminFake
from test_couple_invitation_evidence import time

RUN = "11111111-1111-4111-8111-111111111111"
NATIVE_ADMIN = "22222222-2222-4222-8222-222222222222"
NATIVE_A = "33333333-3333-4333-8333-333333333333"
NATIVE_B = "44444444-4444-4444-8444-444444444444"
HOST_ADMIN = "55555555-5555-4555-8555-555555555555"
UNKNOWN = "66666666-6666-4666-8666-666666666666"


class FakeExecutor(AdminFake):
    def stage(self, stage):
        self.value = copy.deepcopy(self.base)
        # Literal expected state, independent of transition()/EXPECTED_SESSIONS.
        fire, identifiers = {
            "admin_ready": (True, [NATIVE_ADMIN]), "admin_routes": (True, [NATIVE_ADMIN]),
            "admin_logged_out": (True, []), "a_ready": (True, [NATIVE_A]),
            "a_routes": (True, [NATIVE_A]), "a_logged_out": (True, []),
            "b_denied_ready": (False, [NATIVE_B, HOST_ADMIN]),
            "b_denied_routes": (False, [NATIVE_B, HOST_ADMIN]),
            "b_granted": (True, [NATIVE_B, HOST_ADMIN]), "b_revoked": (False, [NATIVE_B, HOST_ADMIN]),
            "b_revocation_denied": (False, [NATIVE_B, HOST_ADMIN]),
            "b_restored": (True, [NATIVE_B, HOST_ADMIN]),
            "b_restoration_visible": (True, [NATIVE_B, HOST_ADMIN]),
            "b_logged_out": (True, [HOST_ADMIN]), "completed": (True, []),
        }[stage]
        next(row for row in self.value["qa_profile_rows"] if row["user_id"] == nav.B)["permissions"]["fire"] = fire
        self.project()
        now = time(nav.STAGES.index(stage) + 1)
        owners = {NATIVE_ADMIN: nav.ADMIN, NATIVE_A: nav.A, NATIVE_B: nav.B, HOST_ADMIN: nav.ADMIN}
        created = {NATIVE_ADMIN: time(1), NATIVE_A: time(4), NATIVE_B: time(7), HOST_ADMIN: time(7)}
        self.value["now"] = now
        self.value["active_session_rows"] = [{"id": identifier, "user_id": owners[identifier], "aal": "aal1",
            "created_at": created[identifier], "updated_at": now, "not_after": None} for identifier in identifiers]
        self.value["sessions"] = len(identifiers)


class NavigationFormEvidenceTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.executor = FakeExecutor()
        self.ledger = nav.NavigationFormEvidence(RUN, self.executor, root=Path(self.temp.name))

    def ready(self, stage):
        actor = nav.ADMIN if stage in ("admin_ready", "admin_routes", "admin_logged_out") else nav.A if stage in (
            "a_ready", "a_routes", "a_logged_out") else nav.B
        return {"run_id": RUN, "user_id": actor, "stage": stage, "status": "ready"}

    def advance(self, through):
        if not self.ledger.path("preflight-observed").exists():
            self.ledger.preflight()
        for stage in nav.STAGES:
            self.executor.stage(stage)
            self.ledger.observe(stage, self.ready(stage))
            if stage == through:
                break

    def reject(self, code, action):
        with self.assertRaisesRegex(nav.EvidenceError, "^" + code + "$"):
            action()

    def persisted(self):
        return "\n".join(path.read_text(encoding="utf-8") for path in self.ledger.directory.glob("*.json"))

    def test_fifteen_checkpoints_bind_four_distinct_sessions_and_restore_full_baseline(self):
        self.advance("completed")
        base, final, bindings, stages = self.ledger.history()
        self.assertEqual(list(nav.STAGES), stages)
        self.assertEqual(base["qa_profile_rows"], final["qa_profile_rows"])
        self.assertEqual(base["profiles"], final["profiles"])
        self.assertEqual([NATIVE_ADMIN, NATIVE_A, NATIVE_B, HOST_ADMIN], [bindings[key]["id"] for key in
            ("native_admin", "native_a", "native_b", "host_admin")])
        self.assertEqual((0, 0), (final["sessions"], final["factors"]))
        self.assertEqual(16, len(self.executor.calls))
        self.assertTrue(all(sql == nav.SNAPSHOT_SQL and readonly is True for sql, readonly in self.executor.calls))
        self.assertEqual(32, len(list(self.ledger.directory.glob("*.json"))))
        self.assertEqual(nav.B, self.ledger.read("completed-reservation")["native_report"]["user_id"])

    def test_d01_default_still_rejects_a_and_opt_in_preserves_two_unique_owner_limit(self):
        self.executor.stage("a_ready")
        self.reject("FOREIGN_QA_AUTH_STATE", lambda: admin.validate_snapshot(self.executor.value))
        self.assertEqual(1, nav.validate_snapshot(self.executor.value)["sessions"])
        self.reject("AUTH_SCOPE_OPTION_INVALID", lambda: admin.validate_snapshot(self.executor.value, allow_user_a="false"))
        value = copy.deepcopy(self.executor.value)
        value["active_session_rows"].append({**value["active_session_rows"][0], "id": UNKNOWN})
        value["sessions"] = 2
        self.reject("SESSION_OWNER_DUPLICATED", lambda: nav.validate_snapshot(value))
        value["active_session_rows"].append({**value["active_session_rows"][0], "id": HOST_ADMIN, "user_id": nav.ADMIN})
        value["sessions"] = 3
        self.reject("AUTH_ROWS_INVALID", lambda: nav.validate_snapshot(value))

    def test_stage_order_replay_and_unknown_stage_reject_before_read(self):
        self.ledger.preflight()
        self.reject("CHECKPOINT_ORDER_INVALID", lambda: self.ledger.observe("a_ready", self.ready("a_ready")))
        self.reject("CHECKPOINT_INVALID", lambda: self.ledger.observe("unknown", self.ready("admin_ready")))
        self.executor.stage("admin_ready")
        self.ledger.observe("admin_ready", self.ready("admin_ready"))
        self.reject("CHECKPOINT_ORDER_INVALID", lambda: self.ledger.observe("admin_ready", self.ready("admin_ready")))
        self.assertEqual(2, len(self.executor.calls))

    def test_dirty_preflight_refuses_new_read_and_duplicate_reservation(self):
        self.executor.stage("b_denied_ready")
        self.reject("PREFLIGHT_AUTH_NOT_EMPTY", self.ledger.preflight)
        self.reject("PREFLIGHT_NOT_COMPLETE", lambda: self.ledger.observe("admin_ready", self.ready("admin_ready")))
        self.reject("EVIDENCE_ALREADY_EXISTS", self.ledger.preflight)
        self.assertEqual(1, len(self.executor.calls))

    def test_uncertain_read_sanitizes_exception_and_blocks_new_instance_replay(self):
        self.ledger.preflight()
        self.executor.error = nav.EvidenceError("PRIVATE_TRANSPORT_DATA")
        self.reject("READ_OUTCOME_UNCERTAIN", lambda: self.ledger.observe("admin_ready", self.ready("admin_ready")))
        self.assertNotIn("PRIVATE_TRANSPORT_DATA", self.persisted())
        fresh = nav.NavigationFormEvidence(RUN, self.executor, root=Path(self.temp.name))
        self.reject("CHECKPOINT_UNCERTAIN_NO_REPLAY", lambda: fresh.observe("admin_ready", self.ready("admin_ready")))
        self.assertEqual(2, len(self.executor.calls))

    def test_partial_reservation_blocks_read_and_replay(self):
        self.ledger.preflight()
        self.ledger.save("admin_ready-reservation", {"status": "reserved", "native_report": self.ready("admin_ready")})
        self.reject("CHECKPOINT_UNCERTAIN_NO_REPLAY", lambda: self.ledger.observe("admin_ready", self.ready("admin_ready")))
        self.assertEqual(1, len(self.executor.calls))

    def test_actor_scope_report_fields_and_status_reject_before_read(self):
        self.ledger.preflight()
        for change, code in (({"user_id": nav.A}, "NATIVE_REPORT_SCOPE_INVALID"),
                             ({"stage": "a_ready"}, "NATIVE_REPORT_SCOPE_INVALID"),
                             ({"run_id": UNKNOWN}, "NATIVE_REPORT_SCOPE_INVALID"),
                             ({"status": "passed"}, "NATIVE_REPORT_SCOPE_INVALID"),
                             ({"token": "PRIVATE_DATA"}, "NATIVE_REPORT_FIELDS_INVALID")):
            self.reject(code, lambda: self.ledger.observe("admin_ready", {**self.ready("admin_ready"), **change}))
        self.assertEqual(1, len(self.executor.calls))
        self.assertNotIn("PRIVATE_DATA", self.persisted())

    def test_only_b_fire_can_change_and_nonboolean_permissions_are_rejected(self):
        fake = FakeExecutor()
        fake.stage("b_denied_ready")
        value = copy.deepcopy(fake.value)
        value["qa_profile_rows"][0]["permissions"]["fire"] = 1
        self.reject("PERMISSIONS_INVALID", lambda: nav.validate_snapshot(value))
        self.advance("a_logged_out")
        self.executor.stage("b_denied_ready")
        next(row for row in self.executor.value["qa_profile_rows"] if row["user_id"] == nav.B)["permissions"]["poker"] = False
        self.executor.project()
        self.reject("QA_PERMISSIONS_OR_PROFILE_CHANGED", lambda: self.ledger.observe("b_denied_ready", self.ready("b_denied_ready")))

    def test_revoked_and_restored_permissions_require_exact_current_state(self):
        self.advance("b_granted")
        self.executor.stage("b_revoked")
        next(row for row in self.executor.value["qa_profile_rows"] if row["user_id"] == nav.B)["permissions"]["fire"] = True
        self.executor.project()
        self.reject("QA_PERMISSIONS_OR_PROFILE_CHANGED", lambda: self.ledger.observe("b_revoked", self.ready("b_revoked")))
        with tempfile.TemporaryDirectory() as root:
            fake = FakeExecutor()
            ledger = nav.NavigationFormEvidence(RUN, fake, root=Path(root))
            ledger.preflight()
            for stage in nav.STAGES[:11]:
                fake.stage(stage)
                ledger.observe(stage, self.ready(stage))
            fake.stage("b_restored")
            next(row for row in fake.value["qa_profile_rows"] if row["user_id"] == nav.B)["permissions"]["extra_flag"] = True
            fake.project()
            self.reject("QA_PERMISSIONS_OR_PROFILE_CHANGED", lambda: ledger.observe("b_restored", self.ready("b_restored")))

    def test_removed_admin_session_cannot_be_reused_for_a_or_host_admin(self):
        self.advance("admin_logged_out")
        self.executor.stage("a_ready")
        self.executor.value["active_session_rows"][0]["id"] = NATIVE_ADMIN
        self.reject("SESSION_ID_REUSED", lambda: self.ledger.observe("a_ready", self.ready("a_ready")))
        with tempfile.TemporaryDirectory() as root:
            fake = FakeExecutor()
            ledger = nav.NavigationFormEvidence(RUN, fake, root=Path(root))
            ledger.preflight()
            for stage in nav.STAGES[:6]:
                fake.stage(stage)
                ledger.observe(stage, self.ready(stage))
            fake.stage("b_denied_ready")
            next(row for row in fake.value["active_session_rows"] if row["user_id"] == nav.ADMIN)["id"] = NATIVE_ADMIN
            self.reject("SESSION_ID_REUSED", lambda: ledger.observe("b_denied_ready", self.ready("b_denied_ready")))

    def test_unexpected_owner_or_substituted_session_does_not_satisfy_stage(self):
        self.advance("a_logged_out")
        self.executor.stage("b_denied_ready")
        next(row for row in self.executor.value["active_session_rows"] if row["user_id"] == nav.ADMIN)["user_id"] = nav.A
        self.reject("NEW_SESSION_NOT_UNIQUE", lambda: self.ledger.observe("b_denied_ready", self.ready("b_denied_ready")))
        fake = FakeExecutor()
        fake.stage("admin_ready")
        fake.value["active_session_rows"][0]["user_id"] = UNKNOWN
        self.reject("FOREIGN_QA_AUTH_STATE", lambda: nav.validate_snapshot(fake.value))

    def test_fresh_sessions_must_strictly_follow_preflight_and_previous_logout(self):
        self.ledger.preflight()
        self.executor.stage("admin_ready")
        self.executor.value["active_session_rows"][0]["created_at"] = time(0)
        self.reject("SESSION_PREDATES_PREFLIGHT", lambda: self.ledger.observe("admin_ready", self.ready("admin_ready")))
        with tempfile.TemporaryDirectory() as root:
            fake = FakeExecutor()
            ledger = nav.NavigationFormEvidence(RUN, fake, root=Path(root))
            ledger.preflight()
            for stage in nav.STAGES[:3]:
                fake.stage(stage)
                ledger.observe(stage, self.ready(stage))
            fake.stage("a_ready")
            fake.value["active_session_rows"][0]["created_at"] = time(3)
            self.reject("NEW_SESSION_PREDATES_CHECKPOINT", lambda: ledger.observe("a_ready", self.ready("a_ready")))

    def test_identity_profile_registration_factor_and_business_guards(self):
        fake = FakeExecutor()
        for field in ("transactions", "connections", "request_count", "audit_count", "shared_expenses", "couple_budgets"):
            value = copy.deepcopy(fake.base)
            value[field] = 1
            self.reject("QA_BUSINESS_NOT_EMPTY", lambda: nav.validate_snapshot(value))
        for change, code in (({"registrations": "true"}, "REGISTRATIONS_CHANGED"),
                             ({"factors": 1}, "QA_FACTORS_NOT_EMPTY")):
            self.reject(code, lambda: nav.validate_snapshot({**fake.base, **change}))
        value = copy.deepcopy(fake.base)
        value["users"][0]["confirmed"] = False
        self.reject("QA_IDENTITIES_INVALID", lambda: nav.validate_snapshot(value))
        self.advance("admin_ready")
        self.executor.stage("admin_routes")
        self.executor.value["qa_profile_rows"][0]["id"] = UNKNOWN
        self.reject("QA_PERMISSIONS_OR_PROFILE_CHANGED", lambda: self.ledger.observe("admin_routes", self.ready("admin_routes")))

    def test_logout_requires_b_absent_and_only_bound_host_admin_until_completed(self):
        self.advance("b_restoration_visible")
        self.executor.stage("b_logged_out")
        self.executor.value["active_session_rows"][0]["id"] = NATIVE_B
        self.executor.value["active_session_rows"][0]["user_id"] = nav.B
        self.reject("SESSION_BINDING_CHANGED", lambda: self.ledger.observe("b_logged_out", self.ready("b_logged_out")))
        with tempfile.TemporaryDirectory() as root:
            fake = FakeExecutor()
            ledger = nav.NavigationFormEvidence(RUN, fake, root=Path(root))
            ledger.preflight()
            for stage in nav.STAGES[:14]:
                fake.stage(stage)
                ledger.observe(stage, self.ready(stage))
            fake.value["now"] = time(15)
            self.reject("CHECKPOINT_SESSION_COUNT_INVALID", lambda: ledger.observe("completed", self.ready("completed")))

    def test_clock_reversal_aal_change_and_secret_session_fields_reject(self):
        self.advance("a_ready")
        self.executor.stage("a_routes")
        self.executor.value["now"] = time(3)
        self.executor.value["active_session_rows"][0]["created_at"] = time(3)
        self.executor.value["active_session_rows"][0]["updated_at"] = time(3)
        self.reject("DATABASE_CLOCK_REVERSED", lambda: self.ledger.observe("a_routes", self.ready("a_routes")))
        fake = FakeExecutor()
        fake.stage("a_ready")
        value = copy.deepcopy(fake.value)
        value["active_session_rows"][0]["refresh_token"] = "PRIVATE_DATA"
        self.reject("AUTH_ROW_FIELDS_INVALID", lambda: nav.validate_snapshot(value))
        base = nav.validate_snapshot(fake.base)
        current = nav.validate_snapshot(fake.value)
        bindings = nav.transition("a_ready", current, base, base, {})
        fake.stage("a_routes")
        fake.value["active_session_rows"][0]["aal"] = "aal2"
        self.reject("SESSION_BINDING_CHANGED", lambda: nav.transition("a_routes", fake.value, base, current, bindings))

    def test_malformed_snapshot_response_and_corrupt_journal_are_not_replayed(self):
        self.ledger.preflight()
        self.ledger.execute = lambda sql, read_only: [{"snapshot": self.executor.value, "token": "PRIVATE_DATA"}]
        self.reject("SNAPSHOT_RESPONSE_INVALID", lambda: self.ledger.observe("admin_ready", self.ready("admin_ready")))
        self.assertNotIn("PRIVATE_DATA", self.persisted())
        with tempfile.TemporaryDirectory() as root:
            fake = FakeExecutor()
            ledger = nav.NavigationFormEvidence(RUN, fake, root=Path(root))
            ledger.preflight()
            ledger.path("preflight-observed").write_text("{broken-json", encoding="utf-8")
            self.reject("JOURNAL_INVALID_OR_MISSING", lambda: ledger.observe("admin_ready", self.ready("admin_ready")))
            self.assertEqual(1, len(fake.calls))


if __name__ == "__main__":
    unittest.main()
