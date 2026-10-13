"""D01 offline fixtures: no credentials, real Management, device or role writes."""
import copy
import json
from pathlib import Path
import tempfile
import unittest

import admin_form_evidence as admin
from test_couple_invitation_evidence import snapshot as qa_snapshot, time

RUN = "11111111-1111-4111-8111-111111111111"
PRIMARY = "22222222-2222-4222-8222-222222222222"
SECONDARY = "33333333-3333-4333-8333-333333333333"
UNKNOWN = "44444444-4444-4444-8444-444444444444"


class FakeExecutor:
    def __init__(self):
        self.value = qa_snapshot()
        self.value.update(mfa_factor_rows=[], active_session_rows=[], shared_expenses=0, couple_budgets=0)
        self.value["qa_profile_rows"] = [{"id": owner, "user_id": owner,
            "permissions": {key: key != "admin" or owner == admin.ADMIN for key in
                ("admin", "poker", "fumo", "fire", "tcg", "libreria", "couple_expenses")}}
            for owner in sorted(admin.QA)]
        self.project()
        self.base = copy.deepcopy(self.value)
        self.calls = []
        self.error = None

    def project(self):
        flags = {row["user_id"]: row["permissions"] for row in self.value["qa_profile_rows"]}
        for row in self.value["profiles"]:
            row["permissions"] = copy.deepcopy(flags[row["user_id"]])

    def __call__(self, sql, read_only=True):
        self.calls.append((sql, read_only))
        if sql != admin.SNAPSHOT_SQL or read_only is not True:
            raise AssertionError("Only the fixed read-only snapshot is permitted")
        if self.error:
            raise self.error
        return [{"snapshot": copy.deepcopy(self.value)}]

    def stage(self, stage):
        self.value = copy.deepcopy(self.base)
        # Explicit fixtures independent of transition(): B.fire/B.admin/Admin.admin.
        fire, secondary_role, primary_role, owners = {
            "admin_ready": (True, False, True, [admin.ADMIN]),
            "permission_cancelled": (True, False, True, [admin.ADMIN]),
            "permission_changed": (False, False, True, [admin.ADMIN]),
            "permission_restored": (True, False, True, [admin.ADMIN]),
            "secondary_admin_ready": (True, True, True, [admin.ADMIN, admin.B]),
            "primary_role_revoked": (True, True, False, [admin.ADMIN, admin.B]),
            "primary_role_denied": (True, True, False, [admin.ADMIN, admin.B]),
            "primary_role_restored": (True, True, True, [admin.ADMIN, admin.B]),
            "primary_reloaded": (True, True, True, [admin.ADMIN, admin.B]),
            "secondary_admin_removed": (True, False, True, [admin.ADMIN, admin.B]),
            "completed": (True, False, True, []),
        }[stage]
        for row in self.value["qa_profile_rows"]:
            if row["user_id"] == admin.B:
                row["permissions"]["fire"] = fire
                row["permissions"]["admin"] = secondary_role
            if row["user_id"] == admin.ADMIN:
                row["permissions"]["admin"] = primary_role
        self.project()
        now = time(admin.STAGES.index(stage) + 1)
        self.value["now"] = now
        self.value["active_session_rows"] = [{"id": PRIMARY if owner == admin.ADMIN else SECONDARY,
            "user_id": owner, "aal": "aal1", "created_at": time(1 if owner == admin.ADMIN else 5),
            "updated_at": now, "not_after": None} for owner in owners]
        self.value["sessions"] = len(owners)


class AdminFormEvidenceTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.executor = FakeExecutor()
        self.ledger = admin.AdminFormEvidence(RUN, self.executor, root=Path(self.temp.name))

    def ready(self, stage):
        return {"run_id": RUN, "user_id": admin.ADMIN, "stage": stage, "status": "ready"}

    def advance(self, through):
        if not self.ledger.path("preflight-observed").exists():
            self.ledger.preflight()
        for stage in admin.STAGES:
            self.executor.stage(stage)
            self.ledger.observe(stage, self.ready(stage))
            if stage == through:
                break

    def reject(self, code, action):
        with self.assertRaisesRegex(admin.EvidenceError, "^" + code + "$"):
            action()

    def persisted(self):
        return "\n".join(path.read_text(encoding="utf-8") for path in self.ledger.directory.glob("*.json"))

    def test_eleven_checkpoints_restore_exact_permissions_and_bind_admin_and_b_sessions(self):
        self.advance("completed")
        base, final, bindings, completed = self.ledger.history()
        self.assertEqual(list(admin.STAGES), completed)
        self.assertEqual(base["qa_profile_rows"], final["qa_profile_rows"])
        self.assertEqual(base["profiles"], final["profiles"])
        self.assertEqual((0, 0), (final["sessions"], final["factors"]))
        self.assertEqual([PRIMARY, SECONDARY], [bindings[key]["id"] for key in ("primary", "secondary")])
        self.assertEqual([admin.ADMIN, admin.B], [bindings[key]["user_id"] for key in ("primary", "secondary")])
        self.assertEqual(12, len(self.executor.calls))
        self.assertTrue(all(sql == admin.SNAPSHOT_SQL and readonly is True for sql, readonly in self.executor.calls))
        self.assertEqual(24, len(list(self.ledger.directory.glob("*.json"))))

    def test_order_and_replay_make_no_read(self):
        self.ledger.preflight()
        self.reject("CHECKPOINT_ORDER_INVALID", lambda: self.ledger.observe("permission_changed", self.ready("permission_changed")))
        self.executor.stage("admin_ready")
        self.ledger.observe("admin_ready", self.ready("admin_ready"))
        self.reject("CHECKPOINT_ORDER_INVALID", lambda: self.ledger.observe("admin_ready", self.ready("admin_ready")))
        self.assertEqual(2, len(self.executor.calls))

    def test_completed_cannot_replay(self):
        self.advance("completed")
        self.reject("CHECKPOINT_ORDER_INVALID", lambda: self.ledger.observe("completed", self.ready("completed")))
        self.assertEqual(12, len(self.executor.calls))

    def test_wrong_baseline_roles_block_preflight_and_later_read(self):
        self.executor.value["qa_profile_rows"][0]["permissions"]["admin"] = False
        self.reject("BASELINE_ROLES_INVALID", self.ledger.preflight)
        self.reject("PREFLIGHT_NOT_COMPLETE", lambda: self.ledger.observe("admin_ready", self.ready("admin_ready")))
        self.assertEqual(1, len(self.executor.calls))

    def test_existing_session_or_factor_blocks_preflight(self):
        self.executor.stage("admin_ready")
        self.reject("PREFLIGHT_AUTH_NOT_EMPTY", self.ledger.preflight)
        value = copy.deepcopy(self.executor.base)
        value["factors"] = 1
        self.reject("QA_FACTORS_NOT_EMPTY", lambda: admin.validate_snapshot(value))

    def test_native_reports_wrong_actor_stage_status_or_secret_rejected_before_read(self):
        self.ledger.preflight()
        for change, code in (({"user_id": admin.B}, "NATIVE_REPORT_SCOPE_INVALID"),
                             ({"stage": "completed"}, "NATIVE_REPORT_SCOPE_INVALID"),
                             ({"status": "passed"}, "NATIVE_REPORT_SCOPE_INVALID"),
                             ({"run_id": UNKNOWN}, "NATIVE_REPORT_SCOPE_INVALID"),
                             ({"token": "PRIVATE_DATA"}, "NATIVE_REPORT_FIELDS_INVALID")):
            self.reject(code, lambda: self.ledger.observe("admin_ready", {**self.ready("admin_ready"), **change}))
        self.assertEqual(1, len(self.executor.calls))
        self.assertNotIn("PRIVATE_DATA", self.persisted())

    def test_uncertain_read_withholds_error_contents_and_refuses_fresh_instance_replay(self):
        self.ledger.preflight()
        self.executor.error = admin.EvidenceError("PRIVATE_TRANSPORT_DATA")
        self.reject("READ_OUTCOME_UNCERTAIN", lambda: self.ledger.observe("admin_ready", self.ready("admin_ready")))
        self.assertNotIn("PRIVATE_TRANSPORT_DATA", self.persisted())
        fresh = admin.AdminFormEvidence(RUN, self.executor, root=Path(self.temp.name))
        self.reject("CHECKPOINT_UNCERTAIN_NO_REPLAY", lambda: fresh.observe("admin_ready", self.ready("admin_ready")))
        self.assertEqual(2, len(self.executor.calls))

    def test_partial_reservation_and_duplicate_preflight_do_not_read(self):
        self.ledger.preflight()
        self.reject("EVIDENCE_ALREADY_EXISTS", self.ledger.preflight)
        self.ledger.save("admin_ready-reservation", {"status": "reserved", "native_report": self.ready("admin_ready")})
        self.reject("CHECKPOINT_UNCERTAIN_NO_REPLAY", lambda: self.ledger.observe("admin_ready", self.ready("admin_ready")))
        self.assertEqual(1, len(self.executor.calls))

    def test_cancel_cannot_change_permission(self):
        self.advance("admin_ready")
        self.executor.stage("permission_cancelled")
        self.executor.value["qa_profile_rows"][1]["permissions"]["fire"] = False
        self.executor.project()
        self.reject("QA_PERMISSIONS_OR_PROFILE_CHANGED", lambda: self.ledger.observe("permission_cancelled", self.ready("permission_cancelled")))

    def test_permission_change_is_exact_b_fire_only_without_new_keys(self):
        self.advance("permission_cancelled")
        self.executor.stage("permission_changed")
        self.executor.value["qa_profile_rows"][2]["permissions"]["extra_flag"] = True
        self.executor.project()
        self.reject("QA_PERMISSIONS_OR_PROFILE_CHANGED", lambda: self.ledger.observe("permission_changed", self.ready("permission_changed")))

    def test_restoration_must_return_full_baseline_not_partial_flags(self):
        self.advance("permission_changed")
        self.executor.stage("permission_restored")
        for row in self.executor.value["qa_profile_rows"]:
            if row["user_id"] == admin.B:
                row["permissions"]["poker"] = False
        self.executor.project()
        self.reject("QA_PERMISSIONS_OR_PROFILE_CHANGED", lambda: self.ledger.observe("permission_restored", self.ready("permission_restored")))

    def test_revoked_primary_role_must_be_false_while_b_stays_admin(self):
        self.advance("secondary_admin_ready")
        self.executor.stage("primary_role_revoked")
        for row in self.executor.value["qa_profile_rows"]:
            if row["user_id"] == admin.ADMIN:
                row["permissions"]["admin"] = True
        self.reject("QA_PERMISSIONS_OR_PROFILE_CHANGED", lambda: self.ledger.observe("primary_role_revoked", self.ready("primary_role_revoked")))

    def test_restored_primary_cannot_remove_secondary_admin_early(self):
        self.advance("primary_role_denied")
        self.executor.stage("primary_role_restored")
        for row in self.executor.value["qa_profile_rows"]:
            if row["user_id"] == admin.B:
                row["permissions"]["admin"] = False
        self.executor.project()
        self.reject("QA_PERMISSIONS_OR_PROFILE_CHANGED", lambda: self.ledger.observe("primary_role_restored", self.ready("primary_role_restored")))

    def test_completed_requires_all_roles_restored_and_zero_sessions(self):
        self.advance("secondary_admin_removed")
        self.executor.stage("completed")
        for row in self.executor.value["qa_profile_rows"]:
            if row["user_id"] == admin.B:
                row["permissions"]["admin"] = True
        self.executor.project()
        self.reject("QA_PERMISSIONS_OR_PROFILE_CHANGED", lambda: self.ledger.observe("completed", self.ready("completed")))

    def test_qa_a_session_and_duplicate_admin_session_never_allowed(self):
        fake = FakeExecutor()
        fake.stage("admin_ready")
        value = copy.deepcopy(fake.value)
        value["active_session_rows"][0]["user_id"] = admin.A
        self.reject("FOREIGN_QA_AUTH_STATE", lambda: admin.validate_snapshot(value))
        value = copy.deepcopy(fake.value)
        value["active_session_rows"].append({**value["active_session_rows"][0], "id": UNKNOWN})
        value["sessions"] = 2
        self.reject("SESSION_OWNER_DUPLICATED", lambda: admin.validate_snapshot(value))

    def test_secondary_session_must_belong_to_b(self):
        self.advance("permission_restored")
        self.executor.stage("secondary_admin_ready")
        self.executor.value["active_session_rows"][1]["user_id"] = admin.A
        self.reject("FOREIGN_QA_AUTH_STATE", lambda: self.ledger.observe("secondary_admin_ready", self.ready("secondary_admin_ready")))

    def test_session_identity_cannot_change_on_role_denial(self):
        self.advance("primary_role_revoked")
        self.executor.stage("primary_role_denied")
        self.executor.value["active_session_rows"][0]["id"] = UNKNOWN
        self.reject("SESSION_BINDING_CHANGED", lambda: self.ledger.observe("primary_role_denied", self.ready("primary_role_denied")))

    def test_session_timestamp_and_count_invalid(self):
        self.ledger.preflight()
        self.executor.stage("admin_ready")
        self.executor.value["active_session_rows"][0]["created_at"] = time(0)
        self.reject("SESSION_PREDATES_PREFLIGHT", lambda: self.ledger.observe("admin_ready", self.ready("admin_ready")))
        fake = FakeExecutor()
        fake.stage("admin_ready")
        value = copy.deepcopy(fake.value)
        value["sessions"] = 2
        self.reject("AUTH_COUNT_MISMATCH", lambda: admin.validate_snapshot(value))
        value = copy.deepcopy(fake.value)
        value["active_session_rows"][0]["not_after"] = time(1)
        self.reject("SESSION_ALREADY_EXPIRED", lambda: admin.validate_snapshot(value))

    def test_secondary_creation_cannot_predate_previous_checkpoint(self):
        self.advance("permission_restored")
        self.executor.stage("secondary_admin_ready")
        self.executor.value["active_session_rows"][1]["created_at"] = time(2)
        self.reject("NEW_SESSION_PREDATES_CHECKPOINT", lambda: self.ledger.observe("secondary_admin_ready", self.ready("secondary_admin_ready")))

    def test_secret_fields_and_nonboolean_permissions_rejected_before_persistence(self):
        fake = FakeExecutor()
        fake.stage("admin_ready")
        value = copy.deepcopy(fake.value)
        value["active_session_rows"][0]["refresh_token"] = "PRIVATE_DATA"
        self.reject("AUTH_ROW_FIELDS_INVALID", lambda: admin.validate_snapshot(value))
        value = copy.deepcopy(fake.value)
        value["qa_profile_rows"][0]["permissions"]["fire"] = 1
        self.reject("PERMISSIONS_INVALID", lambda: admin.validate_snapshot(value))
        value = copy.deepcopy(fake.value)
        value["qa_profile_rows"][0]["email"] = "outside@example.invalid"
        self.reject("QA_PROFILE_ROWS_INVALID", lambda: admin.validate_snapshot(value))

    def test_profiles_projection_must_match_all_qa_permissions(self):
        self.executor.value["profiles"][0]["permissions"]["fire"] = False
        self.reject("QA_PROFILE_PROJECTION_MISMATCH", self.ledger.preflight)

    def test_profile_ids_codes_and_user_projection_cannot_change(self):
        self.advance("admin_ready")
        self.executor.stage("permission_cancelled")
        self.executor.value["qa_profile_rows"][1]["id"] = UNKNOWN
        self.reject("QA_PERMISSIONS_OR_PROFILE_CHANGED", lambda: self.ledger.observe("permission_cancelled", self.ready("permission_cancelled")))
        fake = FakeExecutor()
        for field, code in (("profiles", "QA_PROJECTION_CHANGED"), ("users", "QA_IDENTITIES_CHANGED")):
            with self.subTest(field=field):
                base = admin.validate_snapshot(fake.base)
                fake.stage("admin_ready")
                changed = copy.deepcopy(fake.value)
                if field == "profiles":
                    changed[field][0]["couple_code"] = "ABCDEFGK"
                else:
                    changed[field][0]["confirmed"] = False
                self.reject(code, lambda: admin.transition("admin_ready", changed, base, base, {}))

    def test_business_registration_foreign_profile_and_clock_guards(self):
        fake = FakeExecutor()
        for field in ("transactions", "connections", "request_count", "audit_count", "shared_expenses", "couple_budgets"):
            value = copy.deepcopy(fake.base)
            value[field] = 1
            self.reject("QA_BUSINESS_NOT_EMPTY", lambda: admin.validate_snapshot(value))
        value = copy.deepcopy(fake.base)
        value["registrations"] = "true"
        self.reject("REGISTRATIONS_CHANGED", lambda: admin.validate_snapshot(value))
        value = copy.deepcopy(fake.base)
        value["qa_profile_rows"][0]["user_id"] = UNKNOWN
        self.reject("QA_PROFILE_ROWS_INVALID", lambda: admin.validate_snapshot(value))
        self.advance("permission_cancelled")
        self.executor.stage("permission_changed")
        self.executor.value["now"] = time(1)
        self.executor.value["active_session_rows"][0]["updated_at"] = time(1)
        self.reject("DATABASE_CLOCK_REVERSED", lambda: self.ledger.observe("permission_changed", self.ready("permission_changed")))

    def test_journal_binding_tamper_rejected_without_http(self):
        self.advance("admin_ready")
        path = self.ledger.path("admin_ready-observed")
        value = json.loads(path.read_text(encoding="utf-8"))
        value["bindings"]["primary"]["id"] = UNKNOWN
        path.write_text(json.dumps(value), encoding="utf-8")
        self.reject("JOURNAL_BINDINGS_CHANGED", self.ledger.history)
        self.assertEqual(2, len(self.executor.calls))


if __name__ == "__main__":
    unittest.main()
