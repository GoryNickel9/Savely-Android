"""V04 synthetic offline guards. Never constructs HTTP clients or opens a credential vault."""
import copy
import datetime as dt
import json
from pathlib import Path
import tempfile
import unittest

import recurring_form_evidence as recurring
from test_finance_form_evidence import FakeSql as FinanceFake, RUN, SESSION, UNKNOWN, CATEGORY
from test_couple_invitation_evidence import time

RECURRING = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
TRANSACTION = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
BASE_RECURRING = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"


class FakeSql(FinanceFake):
    def __init__(self):
        super().__init__()
        self.value["records"]["recurring_expenses"] = [{"id": BASE_RECURRING, "user_id": recurring.B,
            "category_id": CATEGORY, "name": "Synthetic baseline recurring", "amount": "900719925474099.01",
            "currency": "USD", "frequency": "monthly", "week_interval": None,
            "next_due_date": self.value["day"], "is_active": True, "created_at": time(0),
            "updated_at": time(0), "deleted_at": None}]
        self.counts()
        self.base = copy.deepcopy(self.value)

    def __call__(self, sql, read_only=True):
        self.calls.append((sql, read_only))
        if sql != recurring.SNAPSHOT_SQL or read_only is not True:
            raise AssertionError("Only the fixed recurring read-only query is permitted")
        if self.error:
            raise self.error
        self.tick += 1
        self.value["now"] = self.clock()
        return [{"snapshot": copy.deepcopy(self.value)}]

    def mutate(self, op, marker):
        self.tick += 1
        now, rows, day = self.clock(), self.value["records"], self.value["day"]
        own = next((row for row in rows["recurring_expenses"] if row["id"] == RECURRING), None)
        if op == "create-paused":
            rows["recurring_expenses"].append({"id": RECURRING, "user_id": recurring.A, "category_id": None,
                "name": marker, "amount": "0.010", "currency": "EUR", "frequency": "weekly", "week_interval": 1,
                "next_due_date": day, "is_active": False, "created_at": now, "updated_at": now, "deleted_at": None})
        elif op == "process-create":
            rows["transactions"].append({"id": TRANSACTION, "user_id": recurring.A, "category_id": None,
                "type": "expense", "amount": "0.020", "currency": "EUR", "description": marker,
                "date": day, "created_at": now, "updated_at": now, "deleted_at": None, "exchange_rate_eur": "1.000000"})
            own.update(next_due_date=(dt.date.fromisoformat(day) + dt.timedelta(days=7)).isoformat(), updated_at=now)
        elif op == "cleanup-transaction":
            rows["transactions"] = [row for row in rows["transactions"] if row["id"] != TRANSACTION]
        elif op == "cleanup-recurring":
            rows["recurring_expenses"] = [row for row in rows["recurring_expenses"] if row["id"] != RECURRING]
        else:
            changes = {"edit-amount": {"amount": "0.02"}, "resume": {"is_active": True},
                "reset-due": {"next_due_date": day}, "process-dedup": {
                    "next_due_date": (dt.date.fromisoformat(day) + dt.timedelta(days=7)).isoformat()},
                "pause": {"is_active": False}}
            if op not in changes:
                raise AssertionError("Unknown synthetic transition")
            own.update(**changes[op], updated_at=now)
        self.counts()

    def logout(self):
        self.tick += 1
        self.value.update(sessions=0, active_session_rows=[])


class RecurringFormEvidenceTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.sql = FakeSql()
        self.ledger = recurring.RecurringFormEvidence(RUN, self.sql, root=Path(self.temp.name))

    def reject(self, code, action):
        with self.assertRaisesRegex(recurring.EvidenceError, "^" + code + "$"):
            action()

    def ready(self):
        self.ledger.preflight()
        self.sql.login()
        self.ledger.checkpoint("ready")

    def operation(self, op):
        self.ledger.reserve(op)
        self.sql.mutate(op, self.ledger.marker)
        return self.ledger.observe(op)

    def through(self, op):
        self.ready()
        for current in recurring.OPS:
            self.operation(current)
            if current == op:
                return

    def edit_journal(self, name, update):
        path = self.ledger.path(name)
        value = json.loads(path.read_text(encoding="utf-8"))
        update(value)
        path.write_text(json.dumps(value), encoding="utf-8")

    def test_fixed_sql_four_tables_three_qa_decimal_text_and_bounded_rows(self):
        value = self.ledger.preflight(day="2026-10-12")
        self.assertEqual(set(recurring.TABLES), set(value["records"]))
        self.assertEqual("900719925474099.01", value["records"]["recurring_expenses"][0]["amount"])
        self.assertEqual([(recurring.SNAPSHOT_SQL, True)], self.sql.calls)
        self.assertEqual(4, recurring.SNAPSHOT_SQL.count("limit 501"))
        self.assertIn("amount::text as amount", recurring.SNAPSHOT_SQL)
        self.assertIn(recurring.QA_SQL, recurring.SNAPSHOT_SQL)

    def test_count_shape_and_truncation_reject_before_snapshot_persistence(self):
        for count in (True, 0, 2, 501):
            value = copy.deepcopy(self.sql.base)
            value["counts"]["recurring_expenses"] = count
            self.reject("RECURRING_ROWS_TRUNCATED", lambda: recurring.validate_snapshot(value))
        value = copy.deepcopy(self.sql.base)
        value["records"]["private_table"] = []
        self.reject("RECURRING_TABLES_INVALID", lambda: recurring.validate_snapshot(value))

    def test_nondecimal_nonfinite_and_secret_fields_never_persist(self):
        for amount, code in ((0.01, "DECIMAL_TEXT_REQUIRED"), ("NaN", "DECIMAL_NOT_FINITE"),
                             ("Infinity", "DECIMAL_NOT_FINITE")):
            value = copy.deepcopy(self.sql.base)
            value["records"]["recurring_expenses"][0]["amount"] = amount
            self.reject(code, lambda: recurring.validate_snapshot(value))
        self.sql.value["records"]["recurring_expenses"][0]["access_token"] = "SYNTHETIC_PRIVATE"
        self.reject("RECURRING_ROW_FIELDS_INVALID", self.ledger.preflight)
        self.assertFalse(self.ledger.path("preflight-observed").exists())
        self.assertNotIn("SYNTHETIC_PRIVATE", self.ledger.path("preflight-failed").read_text())

    def test_recurring_field_types_dates_intervals_uuid_and_future_time(self):
        cases = (("is_active", 1, "RECURRING_FIELDS_INVALID"), ("frequency", "biweekly", "RECURRING_FIELDS_INVALID"),
            ("week_interval", True, "RECURRING_INTERVAL_INVALID"), ("week_interval", 521, "RECURRING_INTERVAL_INVALID"),
            ("next_due_date", "2026-02-30", "RECURRING_DATE_INVALID"),
            ("updated_at", time(1), "RECURRING_TIMESTAMP_INVALID"), ("user_id", UNKNOWN, "RECURRING_ROW_OWNER_INVALID"))
        for field, changed, code in cases:
            with self.subTest(field=field, value=changed):
                value = copy.deepcopy(self.sql.base)
                value["records"]["recurring_expenses"][0][field] = changed
                self.reject(code, lambda: recurring.validate_snapshot(value))

    def test_duplicate_baseline_uuid_and_weekly_null_interval_reject(self):
        value = copy.deepcopy(self.sql.base)
        value["records"]["recurring_expenses"].append(copy.deepcopy(value["records"]["recurring_expenses"][0]))
        value["counts"]["recurring_expenses"] = 2
        self.reject("RECURRING_ROW_OWNER_INVALID", lambda: recurring.validate_snapshot(value))
        value = copy.deepcopy(self.sql.base)
        value["records"]["recurring_expenses"][0]["frequency"] = "weekly"
        self.reject("RECURRING_INTERVAL_INVALID", lambda: recurring.validate_snapshot(value))

    def test_preflight_refuses_existing_active_due_a_but_preserves_b(self):
        self.sql.value["records"]["recurring_expenses"][0]["user_id"] = recurring.A
        self.reject("PREFLIGHT_EXISTING_DUE_RECURRING", self.ledger.preflight)
        self.assertFalse(self.ledger.path("preflight-observed").exists())

    def test_preexisting_paused_or_deleted_or_future_a_is_not_processed(self):
        for changes in ({"is_active": False}, {"deleted_at": time(0)}, {"next_due_date": "2026-10-13"}):
            value = copy.deepcopy(self.sql.base)
            value["records"]["recurring_expenses"][0].update(user_id=recurring.A, **changes)
            recurring._baseline(recurring.validate_snapshot(value), self.ledger.marker)

    def test_preflight_day_and_marker_collision_are_rejected(self):
        self.reject("RUN_DAY_MISMATCH", lambda: self.ledger.preflight(day="2026-10-13"))
        value = copy.deepcopy(self.sql.base)
        value["records"]["transactions"][0]["description"] = self.ledger.marker
        self.reject("PREFLIGHT_MARKER_ALREADY_EXISTS", lambda: recurring._baseline(value, self.ledger.marker))

    def test_dirty_account_projections_registration_and_factors_fail(self):
        for key, changed, code in (("registrations", "true", "REGISTRATIONS_CHANGED"),
                ("factors", 1, "QA_FACTORS_NOT_EMPTY"), ("connections", 1, "QA_BUSINESS_NOT_EMPTY")):
            value = copy.deepcopy(self.sql.base)
            value[key] = changed
            self.reject(code, lambda: recurring.validate_snapshot(value))
        self.ready()
        self.sql.value["profiles"][0]["couple_code"] = "ABCD2345"
        self.reject("QA_ACCOUNT_BASELINE_CHANGED", lambda: self.ledger.reserve("create-paused"))

    def test_preflight_is_exclusive_and_transport_failure_sanitized(self):
        self.sql.error = RuntimeError("SYNTHETIC_PASSWORD_NEVER_LOG")
        self.reject("READ_OUTCOME_UNCERTAIN", self.ledger.preflight)
        self.assertNotIn("SYNTHETIC_PASSWORD", self.ledger.path("preflight-failed").read_text())
        calls = len(self.sql.calls)
        self.reject("EVIDENCE_ALREADY_EXISTS", self.ledger.preflight)
        self.assertEqual(calls, len(self.sql.calls))

    def test_complete_ordered_cycle_preserves_baseline_and_all_no_write_checkpoints(self):
        self.ready()
        for op in recurring.OPS:
            self.operation(op)
            stages = {"edit-amount": ("paused-process",), "resume": ("calendar",),
                "process-create": ("calendar-after-process",), "process-dedup": ("calendar-after-dedup", "empty-process"),
                "pause": ("calendar-paused", "delete-cancel"), "cleanup-recurring": ("completed",)}
            for stage in stages.get(op, ()):
                self.ledger.checkpoint(stage)
            if op == "pause":
                self.sql.logout()
                self.ledger.checkpoint("logged-out")
        result = self.ledger.verify_restored()
        self.assertEqual(self.sql.base["records"], result["records"])
        self.assertEqual(0, result["sessions"])
        self.assertEqual(list(recurring.OPS), self.ledger.history()[3])
        self.assertTrue(self.ledger.read("cleanup-check-observed")["operations_complete"])
        self.assertTrue(all(query == recurring.SNAPSHOT_SQL and readonly is True for query, readonly in self.sql.calls))

    def test_order_invalid_stage_and_cleanup_before_logout_do_no_read(self):
        self.ready()
        calls = len(self.sql.calls)
        self.reject("OPERATION_ORDER_INVALID", lambda: self.ledger.reserve("process-create"))
        self.reject("CHECKPOINT_ORDER_INVALID", lambda: self.ledger.checkpoint("empty-process"))
        self.reject("CHECKPOINT_INVALID", lambda: self.ledger.checkpoint("arbitrary"))
        self.assertEqual(calls, len(self.sql.calls))

    def test_reservation_blocks_second_reserve_and_reconstruction(self):
        self.ready()
        self.ledger.reserve("create-paused")
        calls = len(self.sql.calls)
        self.reject("OPERATION_UNCERTAIN_NO_REPLAY", lambda: self.ledger.reserve("create-paused"))
        reloaded = recurring.RecurringFormEvidence(RUN, self.sql, root=Path(self.temp.name))
        self.reject("OPERATION_UNCERTAIN_NO_REPLAY", reloaded.history)
        self.assertEqual(calls, len(self.sql.calls))

    def test_failed_observation_is_terminal_even_after_snapshot_is_repaired(self):
        self.ready()
        self.ledger.reserve("create-paused")
        self.sql.mutate("create-paused", self.ledger.marker)
        self.sql.value["records"]["recurring_expenses"][-1]["amount"] = "0.02"
        self.reject("OWNED_PAYLOAD_CHANGED", lambda: self.ledger.observe("create-paused"))
        self.sql.value["records"]["recurring_expenses"][-1]["amount"] = "0.01"
        calls = len(self.sql.calls)
        self.reject("OPERATION_UNCERTAIN_NO_REPLAY", lambda: self.ledger.observe("create-paused"))
        self.assertEqual(calls, len(self.sql.calls))

    def test_adoption_requires_exactly_one_new_a_row_and_creation_window(self):
        self.ready()
        self.ledger.reserve("create-paused")
        self.sql.mutate("create-paused", self.ledger.marker)
        duplicate = copy.deepcopy(self.sql.value["records"]["recurring_expenses"][-1])
        duplicate["id"] = UNKNOWN
        self.sql.value["records"]["recurring_expenses"].append(duplicate)
        self.sql.counts()
        self.reject("OWNED_CREATION_NOT_UNIQUE", lambda: self.ledger.observe("create-paused"))

    def test_baseline_mutation_or_new_unreserved_transaction_cannot_be_adopted(self):
        self.ready()
        self.sql.value["records"]["transactions"][0]["amount"] = "0.01"
        self.reject("BASELINE_OR_UNRESERVED_RECORD_CHANGED", lambda: self.ledger.reserve("create-paused"))

    def test_same_uuid_amount_edit_preserves_metadata_and_null_category(self):
        self.through("edit-amount")
        row = self.ledger.history()[2]["recurring"]["row"]
        self.assertEqual(RECURRING, row["id"])
        self.assertEqual("0.02", row["amount"])
        self.assertIsNone(row["category_id"])
        self.assertFalse(row["is_active"])
        self.assertEqual("2026-10-12", row["next_due_date"])

    def test_undeclared_metadata_change_is_rejected_on_amount_edit(self):
        self.through("create-paused")
        self.ledger.reserve("edit-amount")
        self.sql.mutate("edit-amount", self.ledger.marker)
        self.sql.value["records"]["recurring_expenses"][-1]["category_id"] = CATEGORY
        self.reject("OWNED_PAYLOAD_CHANGED", lambda: self.ledger.observe("edit-amount"))

    def test_processing_literal_amount_date_weekly_advance_and_nulls(self):
        self.through("process-create")
        registry = self.ledger.history()[2]
        self.assertEqual("2026-10-19", registry["recurring"]["row"]["next_due_date"])
        self.assertEqual(TRANSACTION, registry["transaction"]["row"]["id"])
        row = registry["transaction"]["row"]
        self.assertEqual("2026-10-12", row["date"])
        self.assertEqual("0.020", row["amount"])
        self.assertEqual("1.000000", row["exchange_rate_eur"])
        self.assertIsNone(row["category_id"])

    def test_dedup_preserves_original_transaction_full_row_and_uuid(self):
        self.through("process-create")
        original = copy.deepcopy(self.ledger.history()[2]["transaction"]["row"])
        self.operation("reset-due")
        self.operation("process-dedup")
        registry = self.ledger.history()[2]
        self.assertEqual(original, registry["transaction"]["row"])
        self.assertEqual("2026-10-19", registry["recurring"]["row"]["next_due_date"])
        self.assertEqual(2, len(self.sql.value["records"]["transactions"]))

    def test_duplicate_dedup_insert_and_transaction_rewrite_are_rejected(self):
        self.through("reset-due")
        self.ledger.reserve("process-dedup")
        self.sql.mutate("process-dedup", self.ledger.marker)
        row = copy.deepcopy(self.sql.value["records"]["transactions"][-1])
        row["id"] = UNKNOWN
        self.sql.value["records"]["transactions"].append(row)
        self.sql.counts()
        self.reject("BASELINE_OR_UNRESERVED_RECORD_CHANGED", lambda: self.ledger.observe("process-dedup"))

    def test_no_write_checkpoint_rejects_unexpected_schedule_advance(self):
        self.through("edit-amount")
        self.sql.value["records"]["recurring_expenses"][-1]["next_due_date"] = "2026-10-19"
        self.reject("BASELINE_OR_UNRESERVED_RECORD_CHANGED", lambda: self.ledger.checkpoint("paused-process"))

    def test_owner_switch_same_uid_new_sid_and_session_expiry_fail(self):
        self.ready()
        self.sql.value["active_session_rows"][0]["id"] = UNKNOWN
        self.reject("NATIVE_SESSION_BINDING_CHANGED", lambda: self.ledger.reserve("create-paused"))
        self.sql.value["active_session_rows"][0]["id"] = SESSION
        self.sql.value["active_session_rows"][0]["user_id"] = recurring.B
        self.reject("NATIVE_SESSION_OWNER_INVALID", lambda: self.ledger.reserve("create-paused"))

    def test_original_session_refresh_updated_timestamp_is_allowed(self):
        self.ready()
        self.sql.tick += 1
        self.sql.value["active_session_rows"][0]["updated_at"] = self.sql.clock()
        self.operation("create-paused")
        self.assertEqual(["create-paused"], self.ledger.history()[3])

    def test_cleanup_requires_observed_logout_and_refuses_other_deleted_row(self):
        self.through("pause")
        self.reject("LOGOUT_NOT_OBSERVED", lambda: self.ledger.reserve("cleanup-transaction"))
        self.sql.logout()
        self.ledger.checkpoint("logged-out")
        self.ledger.reserve("cleanup-transaction")
        self.sql.value["records"]["transactions"].pop(0)
        self.sql.counts()
        self.reject("BASELINE_OR_UNRESERVED_RECORD_CHANGED", lambda: self.ledger.observe("cleanup-transaction"))

    def test_journal_scope_ordinal_and_registry_tampering_fail_closed(self):
        self.through("create-paused")
        self.edit_journal("create-paused-reservation", lambda value: value.update(ordinal=99))
        self.reject("JOURNAL_ORDER_INVALID", self.ledger.history)
        self.edit_journal("create-paused-reservation", lambda value: value.update(ordinal=1, project="other"))
        self.reject("JOURNAL_SCOPE_INVALID", self.ledger.history)

    def test_incomplete_cleanup_proof_never_marks_operations_complete(self):
        self.through("pause")
        candidates = self.ledger.cleanup_candidates()
        self.assertEqual(["transaction", "recurring"], [entry["key"] for entry in candidates])
        self.assertEqual({TRANSACTION, RECURRING}, {entry["row"]["id"] for entry in candidates})
        self.sql.mutate("cleanup-transaction", self.ledger.marker)
        self.sql.mutate("cleanup-recurring", self.ledger.marker)
        self.sql.logout()
        result = self.ledger.verify_restored()
        self.assertEqual(self.sql.base["records"], result["records"])
        proof = self.ledger.read("cleanup-check-observed")
        self.assertFalse(proof["operations_complete"])
        self.assertEqual(list(recurring.OPS[:7]), proof["completed_ops"])

    def test_cleanup_candidates_are_copies_and_reject_current_unreserved_changes(self):
        self.through("create-paused")
        candidate = self.ledger.cleanup_candidates()[0]
        self.assertEqual(RECURRING, candidate["row"]["id"])
        candidate["row"]["amount"] = "999"
        self.assertEqual("0.010", self.ledger.history()[2]["recurring"]["row"]["amount"])
        self.sql.value["records"]["recurring_expenses"][-1]["amount"] = "0.02"
        self.reject("BASELINE_OR_UNRESERVED_RECORD_CHANGED", self.ledger.cleanup_candidates)

    def test_unresolved_reservation_blocks_cleanup_candidates_and_restore_before_read(self):
        self.ready()
        self.ledger.reserve("create-paused")
        calls = len(self.sql.calls)
        self.reject("OPERATION_UNCERTAIN_NO_REPLAY", self.ledger.cleanup_candidates)
        self.reject("OPERATION_UNCERTAIN_NO_REPLAY", self.ledger.verify_restored)
        self.assertEqual(calls, len(self.sql.calls))

    def test_processing_insert_without_schedule_advance_is_not_adopted_as_complete(self):
        self.through("resume")
        self.ledger.reserve("process-create")
        self.sql.mutate("process-create", self.ledger.marker)
        self.sql.value["records"]["recurring_expenses"][-1]["next_due_date"] = "2026-10-12"
        self.reject("OWNED_PAYLOAD_CHANGED", lambda: self.ledger.observe("process-create"))
        self.assertFalse(self.ledger.path("process-create-observed").exists())
        calls = len(self.sql.calls)
        self.reject("OPERATION_UNCERTAIN_NO_REPLAY", self.ledger.cleanup_candidates)
        self.assertEqual(calls, len(self.sql.calls))

    def test_creation_cannot_adopt_a_row_predating_reservation(self):
        self.ready()
        self.ledger.reserve("create-paused")
        self.sql.mutate("create-paused", self.ledger.marker)
        self.sql.value["records"]["recurring_expenses"][-1].update(created_at=time(0), updated_at=time(0))
        self.reject("OWNED_CREATION_WINDOW_INVALID", lambda: self.ledger.observe("create-paused"))

    def test_day_change_after_preflight_and_checkpoint_foreign_session_are_rejected(self):
        self.ready()
        self.sql.value["day"] = "2026-10-13"
        self.reject("RUN_CLOCK_CHANGED", lambda: self.ledger.reserve("create-paused"))
        self.sql.value["day"] = "2026-10-12"
        self.operation("create-paused")
        self.operation("edit-amount")
        self.sql.value["active_session_rows"][0]["user_id"] = recurring.B
        self.reject("NATIVE_SESSION_OWNER_INVALID", lambda: self.ledger.checkpoint("paused-process"))

    def test_invalid_names_and_unknown_operation_never_become_paths_or_queries(self):
        self.reject("JOURNAL_NAME_INVALID", lambda: self.ledger.path("../../secret"))
        self.reject("OPERATION_INVALID", lambda: self.ledger.reserve("delete-baseline"))
        self.assertEqual([], self.sql.calls)

    def test_soft_deleted_recurring_is_never_a_live_cleanup_preimage(self):
        # Owner SELECT hides this row; the guard must reject it before any REST cleanup request.
        row = {"id": RECURRING, "user_id": recurring.A, "is_active": False, "deleted_at": time(0)}
        registry = {"recurring": {"table": "recurring_expenses", "row": row, "removed": False},
                    "transaction": {"table": "transactions", "row": {"id": TRANSACTION}, "removed": True}}
        self.reject("CLEANUP_PREIMAGE_INVALID", lambda: recurring._apply("cleanup-recurring", self.sql.base,
            self.sql.base, registry, self.ledger.marker))
        self.assertNotIn("delete-soft", recurring.OPS)
        self.assertEqual([], self.sql.calls)

    def test_cleanup_candidates_rejects_active_recurring_before_new_read(self):
        self.through("resume")
        calls = len(self.sql.calls)
        self.reject("CLEANUP_PREIMAGE_INVALID", self.ledger.cleanup_candidates)
        self.assertEqual(calls, len(self.sql.calls))


if __name__ == "__main__":
    unittest.main()
