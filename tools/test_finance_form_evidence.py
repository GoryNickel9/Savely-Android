"""V03 offline fixtures only. Never constructs Management or reads a credential vault."""
import copy
import datetime as dt
import json
from pathlib import Path
import tempfile
import unittest

import finance_form_evidence as finance
from test_admin_form_evidence import FakeExecutor as AccountFake
from test_couple_invitation_evidence import time

RUN = "11111111-1111-4111-8111-111111111111"
CATEGORY = "22222222-2222-4222-8222-222222222222"
TRANSACTION = "33333333-3333-4333-8333-333333333333"
BUDGET = "44444444-4444-4444-8444-444444444444"
UNKNOWN = "55555555-5555-4555-8555-555555555555"
SESSION = "66666666-6666-4666-8666-666666666666"
CREATED = {"expense-category": "77777777-7777-4777-8777-777777777777",
           "income-category": "88888888-8888-4888-8888-888888888888",
           "expense": "99999999-9999-4999-8999-999999999999",
           "income": "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
           "budget": "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"}


class FakeSql(AccountFake):
    def __init__(self):
        super().__init__()
        self.value.update(day="2026-10-12", records={
            "categories": [{"id": CATEGORY, "user_id": finance.B, "name": "Synthetic baseline category",
                "type": "expense", "icon": "💰", "color": "#22c55e", "created_at": time(0), "deleted_at": None}],
            "transactions": [{"id": TRANSACTION, "user_id": finance.B, "category_id": CATEGORY,
                "type": "expense", "amount": "9999999999999.99", "currency": "EUR", "description": "Synthetic baseline transaction",
                "date": "2026-10-01", "created_at": time(0), "updated_at": time(0), "deleted_at": None,
                "exchange_rate_eur": "1.000000"}],
            "budgets": [{"id": BUDGET, "user_id": finance.B, "category_id": CATEGORY, "amount": "9.00",
                "currency": "EUR", "month": 1, "year": 2000, "created_at": time(0), "updated_at": time(0)}],
        }, counts={table: 1 for table in finance.TABLES}, transactions=1)
        self.base = copy.deepcopy(self.value)
        self.tick = 0

    def __call__(self, sql, read_only=True):
        self.calls.append((sql, read_only))
        if sql != finance.SNAPSHOT_SQL or read_only is not True:
            raise AssertionError("Only the fixed finance read-only snapshot is allowed")
        if self.error:
            raise self.error
        self.tick += 1
        self.value["now"] = self.clock()
        return [{"snapshot": copy.deepcopy(self.value)}]

    def clock(self):
        return (dt.datetime.fromisoformat(time(0)) + dt.timedelta(seconds=self.tick)).isoformat()

    def login(self):
        self.tick += 1
        self.value["sessions"] = 1
        self.value["active_session_rows"] = [{"id": SESSION, "user_id": finance.A, "aal": "aal1",
            "created_at": self.clock(), "updated_at": self.clock(), "not_after": None}]

    def counts(self):
        self.value["counts"] = {table: len(rows) for table, rows in self.value["records"].items()}
        self.value["transactions"] = self.value["counts"]["transactions"]

    def mutate(self, op, marker):
        self.tick += 1
        now = self.clock()
        rows = self.value["records"]
        def owned(key, table):
            return next(row for row in rows[table] if row["id"] == CREATED[key])
        if op in ("expense-category-create", "income-category-create"):
            kind = op.split("-")[0]
            rows["categories"].append({"id": CREATED[kind + "-category"], "user_id": finance.A,
                "name": marker + "-" + kind, "type": kind, "icon": "📦", "color": "#6b7280",
                "created_at": now, "deleted_at": None})
        elif op in ("expense-create", "income-create"):
            kind = op.split("-")[0]
            rows["transactions"].append({"id": CREATED[kind], "user_id": finance.A, "category_id": CREATED[kind + "-category"],
                "type": kind, "amount": "0.01" if kind == "expense" else "0.05", "currency": "EUR",
                "description": marker + "-" + kind, "date": "2026-10-12", "created_at": now, "updated_at": now,
                "deleted_at": None, "exchange_rate_eur": "1.000000"})
        elif op in ("expense-update", "income-update"):
            kind = op.split("-")[0]
            owned(kind, "transactions").update(amount="0.02" if kind == "expense" else "0.06", updated_at=now)
        elif op == "budget-create":
            rows["budgets"].append({"id": CREATED["budget"], "user_id": finance.A, "category_id": CREATED["expense-category"],
                "amount": "3.00", "currency": "EUR", "month": 1, "year": 2000, "created_at": now, "updated_at": now})
        elif op == "budget-update":
            owned("budget", "budgets").update(amount="4.00", updated_at=now)
        elif op == "expense-category-rename":
            owned("expense-category", "categories")["name"] = marker + "-expense-renamed"
        elif op == "expense-category-soft-delete":
            owned("expense-category", "categories")["deleted_at"] = now
            owned("expense", "transactions").update(category_id=None, updated_at=now)
        elif op in ("expense-delete", "income-delete", "budget-delete"):
            key = op.split("-")[0]
            table = "budgets" if key == "budget" else "transactions"
            rows[table].remove(owned(key, table))
        elif op == "income-category-soft-delete":
            owned("income-category", "categories")["deleted_at"] = now
        elif op in ("cleanup-expense-category", "cleanup-income-category"):
            key = op[len("cleanup-"):]
            rows["categories"].remove(owned(key, "categories"))
        else:
            raise AssertionError("Unknown fixed fixture transition")
        self.counts()


class FinanceFormEvidenceTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.sql = FakeSql()
        self.ledger = finance.FinanceFormEvidence(RUN, self.sql, root=Path(self.temp.name))

    def reject(self, code, action):
        with self.assertRaisesRegex(finance.EvidenceError, "^" + code + "$"):
            action()

    def test_nonzero_baseline_preserves_every_row_and_exact_decimal(self):
        result = self.ledger.preflight()
        self.assertEqual(self.sql.base["records"], result["records"])
        self.assertEqual(result, self.ledger.read("preflight-observed")["snapshot"])
        self.assertEqual("9999999999999.99", result["records"]["transactions"][0]["amount"])
        self.assertEqual([(finance.SNAPSHOT_SQL, True)], self.sql.calls)

    def test_decimal_transport_is_text_and_numeric_float_is_refused(self):
        self.assertIn("amount::text as amount", finance.SNAPSHOT_SQL)
        self.assertIn("exchange_rate_eur::text as exchange_rate_eur", finance.SNAPSHOT_SQL)
        self.sql.value["records"]["transactions"][0]["amount"] = 0.01
        self.reject("DECIMAL_TEXT_REQUIRED", self.ledger.preflight)

    def test_nonfinite_decimal_is_rejected_before_persisting_snapshot(self):
        self.sql.value["records"]["budgets"][0]["amount"] = "NaN"
        self.reject("DECIMAL_NOT_FINITE", self.ledger.preflight)
        self.assertFalse(self.ledger.path("preflight-observed").exists())

    def test_truncated_rows_or_count_mismatch_are_never_accepted(self):
        for count in (2, finance.LIMIT + 1, True):
            with self.subTest(count=count):
                value = copy.deepcopy(self.sql.base)
                value["counts"]["categories"] = count
                self.reject("FINANCE_ROWS_TRUNCATED", lambda: finance.validate_snapshot(value))

    def test_unknown_row_fields_and_foreign_owner_are_rejected(self):
        value = copy.deepcopy(self.sql.base)
        value["records"]["categories"][0]["secret"] = "never persist"
        self.reject("FINANCE_ROW_FIELDS_INVALID", lambda: finance.validate_snapshot(value))
        value = copy.deepcopy(self.sql.base)
        value["records"]["categories"][0]["user_id"] = UNKNOWN
        self.reject("FINANCE_ROW_OWNER_INVALID", lambda: finance.validate_snapshot(value))

    def test_duplicate_ids_and_future_timestamps_are_rejected(self):
        value = copy.deepcopy(self.sql.base)
        value["records"]["categories"].append(copy.deepcopy(value["records"]["categories"][0]))
        value["counts"]["categories"] = 2
        self.reject("FINANCE_ROW_OWNER_INVALID", lambda: finance.validate_snapshot(value))
        value = copy.deepcopy(self.sql.base)
        value["records"]["transactions"][0]["updated_at"] = time(1)
        self.reject("FINANCE_TIMESTAMP_INVALID", lambda: finance.validate_snapshot(value))

    def test_preexisting_run_marker_blocks_adoption(self):
        self.sql.value["records"]["categories"][0]["name"] = self.ledger.marker + "-expense"
        self.reject("PREFLIGHT_MARKER_ALREADY_EXISTS", self.ledger.preflight)

    def test_dirty_account_guards_remain_closed_with_finance_nonzero(self):
        for key, value, code in (("registrations", "true", "REGISTRATIONS_CHANGED"),
                ("factors", 1, "QA_FACTORS_NOT_EMPTY"), ("connections", 1, "QA_BUSINESS_NOT_EMPTY")):
            with self.subTest(key=key):
                altered = copy.deepcopy(self.sql.base)
                altered[key] = value
                self.reject(code, lambda: finance.validate_snapshot(altered))

    def test_preflight_reservation_blocks_same_run_without_another_read(self):
        self.ledger.preflight()
        self.reject("EVIDENCE_ALREADY_EXISTS", self.ledger.preflight)
        self.assertEqual(1, len(self.sql.calls))

    def test_transport_failure_is_sanitized_and_failed_baseline_is_retained(self):
        self.sql.error = RuntimeError("SYNTHETIC_SECRET_MUST_NOT_PERSIST")
        self.reject("READ_OUTCOME_UNCERTAIN", self.ledger.preflight)
        persisted = "\n".join(path.read_text(encoding="utf-8") for path in self.ledger.directory.glob("*.json"))
        self.assertNotIn("SYNTHETIC_SECRET_MUST_NOT_PERSIST", persisted)
        self.assertEqual("READ_OUTCOME_UNCERTAIN", self.ledger.read("preflight-failed")["failure_code"])

    def ready(self):
        self.ledger.preflight()
        self.sql.login()
        self.ledger.checkpoint("ready")

    def advance(self, through):
        self.ready()
        for op in finance.OPS:
            if op.startswith("cleanup-"):
                break
            self.ledger.reserve(op)
            self.sql.mutate(op, self.ledger.marker)
            self.ledger.observe(op)
            if op == through:
                return

    def test_full_sixteen_operations_keep_nonzero_baselines_and_cleanup_only_adopted_categories(self):
        self.advance("income-category-soft-delete")
        clean = self.ledger.cleanup_rows()
        self.assertEqual({CREATED["expense-category"], CREATED["income-category"]}, {row["id"] for row in clean})
        self.assertNotIn(CATEGORY, {row["id"] for row in clean})
        self.sql.value.update(sessions=0, active_session_rows=[])
        self.ledger.checkpoint("logged-out")
        for op in ("cleanup-expense-category", "cleanup-income-category"):
            self.ledger.reserve(op)
            self.sql.mutate(op, self.ledger.marker)
            self.ledger.observe(op)
        final = self.ledger.checkpoint("completed")
        self.assertEqual(self.sql.base["records"], final["records"])
        self.assertEqual([], self.ledger.cleanup_rows())
        base, _, registry, done = self.ledger.history()
        self.assertEqual(list(finance.OPS), done)
        self.assertTrue(all(entry["removed"] for entry in registry.values()))
        self.assertEqual(self.sql.base["records"], base["records"])
        self.assertTrue(all(sql == finance.SNAPSHOT_SQL and readonly is True for sql, readonly in self.sql.calls))

    def test_reservation_is_durable_before_the_only_mutation_and_cannot_be_reused(self):
        self.ready()
        self.ledger.reserve("expense-category-create")
        proof = self.ledger.read("expense-category-create-reservation")
        self.assertEqual("reserved", proof["status"])
        self.assertEqual({}, proof["registry"])
        calls = len(self.sql.calls)
        self.reject("OPERATION_UNCERTAIN_NO_REPLAY", lambda: self.ledger.reserve("expense-category-create"))
        self.assertEqual(calls, len(self.sql.calls))
        self.sql.mutate("expense-category-create", self.ledger.marker)
        self.ledger.observe("expense-category-create")
        self.reject("OPERATION_ORDER_INVALID", lambda: self.ledger.reserve("expense-category-create"))

    def test_two_new_matching_rows_cannot_be_adopted_as_a_single_creation(self):
        self.ready()
        self.ledger.reserve("expense-category-create")
        self.sql.mutate("expense-category-create", self.ledger.marker)
        self.sql.value["records"]["categories"].append({**self.sql.value["records"]["categories"][-1], "id": UNKNOWN})
        self.sql.counts()
        self.reject("OWNED_CREATION_NOT_UNIQUE", lambda: self.ledger.observe("expense-category-create"))
        self.reject("OPERATION_UNCERTAIN_NO_REPLAY", self.ledger.history)

    def test_wrong_owner_marker_or_payload_creation_is_preserved_and_blocks_new_writes(self):
        for field, value in (("user_id", finance.B), ("name", "different-marker"), ("type", "income"), ("icon", "X")):
            with self.subTest(field=field):
                with tempfile.TemporaryDirectory() as directory:
                    sql = FakeSql()
                    ledger = finance.FinanceFormEvidence(RUN, sql, root=Path(directory))
                    ledger.preflight(); sql.login(); ledger.checkpoint("ready")
                    ledger.reserve("expense-category-create"); sql.mutate("expense-category-create", ledger.marker)
                    sql.value["records"]["categories"][-1][field] = value
                    self.reject("OWNED_PAYLOAD_CHANGED", lambda: ledger.observe("expense-category-create"))
                    self.assertEqual(2, len(sql.value["records"]["categories"]))
                    self.reject("OPERATION_UNCERTAIN_NO_REPLAY", lambda: ledger.reserve("income-category-create"))

    def test_baseline_mutation_and_deleted_baseline_record_are_never_authorized(self):
        self.ready()
        self.ledger.reserve("expense-category-create")
        self.sql.mutate("expense-category-create", self.ledger.marker)
        self.sql.value["records"]["transactions"][0]["amount"] = "1.00"
        self.reject("BASELINE_OR_UNRESERVED_RECORD_CHANGED", lambda: self.ledger.observe("expense-category-create"))
        self.assertEqual(1, len(self.sql.value["records"]["transactions"]))
        value = copy.deepcopy(self.sql.base)
        value["records"]["transactions"] = []
        value["counts"]["transactions"] = value["transactions"] = 0
        self.reject("BASELINE_OR_UNRESERVED_RECORD_CHANGED", lambda: finance._unchanged_records(self.sql.base, value, {}))

    def test_category_soft_delete_accepts_only_the_one_adopted_detach_and_keeps_budget_link(self):
        self.advance("expense-category-rename")
        self.ledger.reserve("expense-category-soft-delete")
        self.sql.mutate("expense-category-soft-delete", self.ledger.marker)
        registry = self.ledger.observe("expense-category-soft-delete")
        self.assertIsNone(registry["expense"]["row"]["category_id"])
        self.assertEqual(CREATED["expense-category"], registry["budget"]["row"]["category_id"])
        self.assertEqual(CATEGORY, self.sql.value["records"]["transactions"][0]["category_id"])
        self.assertEqual(self.sql.base["records"]["budgets"][0], self.sql.value["records"]["budgets"][0])

    def test_category_delete_cannot_detach_a_baseline_transaction(self):
        self.advance("expense-category-rename")
        self.ledger.reserve("expense-category-soft-delete")
        self.sql.mutate("expense-category-soft-delete", self.ledger.marker)
        self.sql.value["records"]["transactions"][0]["category_id"] = None
        self.reject("BASELINE_OR_UNRESERVED_RECORD_CHANGED", lambda: self.ledger.observe("expense-category-soft-delete"))

    def test_read_lost_after_creation_never_retries_and_keeps_unknown_outcome_reserved(self):
        self.ready()
        self.ledger.reserve("expense-category-create")
        self.sql.mutate("expense-category-create", self.ledger.marker)
        self.sql.error = RuntimeError("SYNTHETIC_PRIVATE_FAILURE")
        self.reject("READ_OUTCOME_UNCERTAIN", lambda: self.ledger.observe("expense-category-create"))
        calls = len(self.sql.calls)
        self.reject("OPERATION_UNCERTAIN_NO_REPLAY", lambda: self.ledger.observe("expense-category-create"))
        self.assertEqual(calls, len(self.sql.calls))
        self.assertNotIn("SYNTHETIC_PRIVATE_FAILURE", self.ledger.path("expense-category-create-failed").read_text())

    def test_owner_replacement_same_qa_or_other_qa_stops_before_operation_reservation(self):
        self.ready()
        self.sql.value["active_session_rows"][0]["id"] = UNKNOWN
        self.reject("NATIVE_SESSION_BINDING_CHANGED", lambda: self.ledger.reserve("expense-category-create"))
        self.assertFalse(self.ledger.path("expense-category-create-reservation").exists())
        self.sql.value["active_session_rows"][0]["user_id"] = finance.B
        self.reject("NATIVE_SESSION_OWNER_INVALID", lambda: self.ledger.reserve("expense-category-create"))
        self.assertFalse(self.ledger.path("expense-category-create-reservation").exists())

    def test_no_write_checkpoint_rejects_new_records_and_blocks_following_operations(self):
        self.ready()
        self.sql.mutate("expense-category-create", self.ledger.marker)
        self.reject("BASELINE_OR_UNRESERVED_RECORD_CHANGED", lambda: self.ledger.checkpoint("category-create-cancelled"))
        self.reject("CHECKPOINT_UNCERTAIN_NO_REPLAY", lambda: self.ledger.reserve("expense-category-create"))

    def test_operation_order_and_early_cleanup_are_rejected_without_backend_read(self):
        self.ready()
        calls = len(self.sql.calls)
        self.reject("OPERATION_ORDER_INVALID", lambda: self.ledger.reserve("expense-create"))
        self.reject("NATIVE_OPERATIONS_NOT_COMPLETE", self.ledger.cleanup_rows)
        self.reject("OPERATIONS_NOT_COMPLETE", lambda: self.ledger.checkpoint("completed"))
        self.assertEqual(calls, len(self.sql.calls))

    def test_failed_before_save_recovers_exact_preimage_as_cancelled_without_any_mutation(self):
        self.ready()
        self.ledger.reserve("expense-category-create")
        result = self.ledger.recovery_read("expense-category-create")
        self.assertEqual({"status": "cancelled", "registry": {}}, result)
        self.assertEqual([], self.ledger.cleanup_candidates())
        self.reject("RECOVERY_TERMINAL_NO_NEW_WRITES", lambda: self.ledger.reserve("income-category-create"))
        self.sql.value.update(sessions=0, active_session_rows=[])
        self.assertEqual(self.sql.base["records"], self.ledger.verify_restored()["records"])
        self.assertEqual("restored", self.ledger.read("cleanup-check-observed")["status"])

    def test_failed_after_save_adopts_exact_owned_creation_and_preserves_failed_observation(self):
        self.ready()
        self.ledger.reserve("expense-category-create")
        self.sql.mutate("expense-category-create", self.ledger.marker)
        self.sql.error = RuntimeError("SYNTHETIC_FAILURE")
        self.reject("READ_OUTCOME_UNCERTAIN", lambda: self.ledger.observe("expense-category-create"))
        initial_failed = self.ledger.path("expense-category-create-failed").read_bytes()
        self.sql.error = None
        result = self.ledger.recovery_read("expense-category-create")
        self.assertEqual("applied", result["status"])
        self.assertEqual(CREATED["expense-category"], result["registry"]["expense-category"]["row"]["id"])
        self.assertEqual(initial_failed, self.ledger.path("expense-category-create-failed").read_bytes())
        candidates = self.ledger.cleanup_candidates()
        self.assertEqual([("categories", CREATED["expense-category"])], [(row["table"], row["row"]["id"]) for row in candidates])
        self.reject("RECOVERY_TERMINAL_NO_NEW_WRITES", lambda: self.ledger.reserve("income-category-create"))

    def test_uncertain_update_can_reconcile_preimage_or_exact_after_not_a_third_amount(self):
        for changed, wanted in ((False, "cancelled"), (True, "applied")):
            with self.subTest(changed=changed):
                with tempfile.TemporaryDirectory() as directory:
                    sql = FakeSql()
                    ledger = finance.FinanceFormEvidence(RUN, sql, root=Path(directory))
                    ledger.preflight(); sql.login(); ledger.checkpoint("ready")
                    for op in ("expense-category-create", "income-category-create", "expense-create", "income-create"):
                        ledger.reserve(op); sql.mutate(op, ledger.marker); ledger.observe(op)
                    ledger.reserve("expense-update")
                    if changed:
                        sql.mutate("expense-update", ledger.marker)
                    result = ledger.recovery_read("expense-update")
                    self.assertEqual(wanted, result["status"])
                    self.assertEqual("0.02" if changed else "0.01", result["registry"]["expense"]["row"]["amount"])
                    self.assertEqual(4, len(ledger.cleanup_candidates()))

    def test_recovery_refuses_changed_payload_and_never_adopts_or_deletes_it(self):
        self.ready()
        self.ledger.reserve("expense-category-create")
        self.sql.mutate("expense-category-create", self.ledger.marker)
        self.sql.value["records"]["categories"][-1]["name"] = "a-different-payload"
        self.reject("BASELINE_OR_UNRESERVED_RECORD_CHANGED", lambda: self.ledger.recovery_read("expense-category-create"))
        calls = len(self.sql.calls)
        self.reject("RECOVERY_UNCERTAIN_NO_REPLAY", lambda: self.ledger.recovery_read("expense-category-create"))
        self.assertEqual(calls, len(self.sql.calls))
        self.assertEqual(2, len(self.sql.value["records"]["categories"]))

    def test_recovery_reservation_replay_is_blocked_before_another_read(self):
        self.ready()
        self.ledger.reserve("expense-category-create")
        original = self.ledger.read("expense-category-create-reservation")
        self.ledger.save("expense-category-create-recovery-reservation", {
            "status": "reserved", "op": "expense-category-create", "original_reservation": original})
        calls = len(self.sql.calls)
        self.reject("EVIDENCE_ALREADY_EXISTS", lambda: self.ledger.recovery_read("expense-category-create"))
        self.assertEqual(calls, len(self.sql.calls))

    def test_partial_failure_cleanup_candidates_have_only_exact_adopted_rows_of_all_three_tables(self):
        self.advance("budget-create")
        self.ledger.reserve("budget-update")
        recovered = self.ledger.recovery_read("budget-update")
        self.assertEqual("cancelled", recovered["status"])
        candidates = self.ledger.cleanup_candidates()
        self.assertEqual(5, len(candidates))
        self.assertEqual({"categories", "transactions", "budgets"}, {entry["table"] for entry in candidates})
        self.assertTrue(all(entry["row"]["user_id"] == finance.A for entry in candidates))
        self.assertTrue(all(entry["row"]["id"] in CREATED.values() for entry in candidates))
        self.assertTrue(all(entry["row"]["id"] not in (CATEGORY, TRANSACTION, BUDGET) for entry in candidates))
        # Simulate only the coordinator's exact registered cleanup, then prove original baseline remains.
        self.sql.value["records"] = copy.deepcopy(self.sql.base["records"])
        self.sql.counts()
        self.sql.value.update(sessions=0, active_session_rows=[])
        self.assertEqual(self.sql.base["records"], self.ledger.verify_restored()["records"])
        self.assertFalse(self.ledger.path("checkpoint-completed-observed").exists())

    def test_cleanup_snapshot_refuses_concurrent_owned_payload_changes_and_foreign_sessions(self):
        self.advance("expense-category-create")
        self.sql.value["records"]["categories"][-1]["name"] = "changed-after-adoption"
        self.reject("BASELINE_OR_UNRESERVED_RECORD_CHANGED", self.ledger.cleanup_candidates)
        self.sql.value["active_session_rows"][0]["id"] = UNKNOWN
        self.reject("NATIVE_SESSION_BINDING_CHANGED", self.ledger.cleanup_candidates)

    def test_journal_wrong_run_project_owner_and_registry_preimage_are_rejected(self):
        self.ready()
        self.ledger.reserve("expense-category-create")
        path = self.ledger.path("expense-category-create-reservation")
        original = json.loads(path.read_text())
        for key, value in (("run_id", UNKNOWN), ("project", "foreign-project"), ("qa_run", UNKNOWN)):
            with self.subTest(key=key):
                changed = {**original, key: value}
                path.write_text(json.dumps(changed))
                self.reject("JOURNAL_SCOPE_INVALID", lambda: self.ledger.observe("expense-category-create"))
        path.write_text(json.dumps({**original, "registry": {"unknown": {"user_id": finance.B}}}))
        self.reject("JOURNAL_RESERVATION_INVALID", lambda: self.ledger.observe("expense-category-create"))
        self.assertEqual(0, len(self.sql.value["records"]["categories"]) - 1)


if __name__ == "__main__":
    unittest.main()
