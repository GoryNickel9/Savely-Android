"""Offline CAS predicates for the two exact V04 temporary rows; no credentials or HTTP."""
import copy
from decimal import Decimal, localcontext
import unittest
from urllib.parse import parse_qs, urlsplit

from couple_invitation_evidence import A, B, EvidenceError
from recurring_form_cleanup import FIELDS, exact_path, normalize


class RecurringCleanupTest(unittest.TestCase):
    run_id = "11111111-1111-4111-8111-111111111111"
    recurring_id = "22222222-2222-4222-8222-222222222222"
    transaction_id = "33333333-3333-4333-8333-333333333333"
    unknown_id = "44444444-4444-4444-8444-444444444444"
    day = "2026-10-14"

    def row(self, table):
        common = {"id": self.recurring_id if table == "recurring_expenses" else self.transaction_id,
                  "user_id": A, "category_id": None, "amount": "0.0200", "currency": "EUR",
                  "created_at": "2026-10-14T12:00:00.123456+02:00",
                  "updated_at": "2026-10-14T10:00:01.654321Z", "deleted_at": None}
        marker = "android-recurring-form-" + self.run_id
        if table == "recurring_expenses":
            return {**common, "name": marker, "frequency": "weekly", "week_interval": 1,
                    "next_due_date": "2026-10-21", "is_active": False}
        return {**common, "description": marker, "type": "expense", "date": self.day,
                "exchange_rate_eur": Decimal("0.894370123456789012345678901234567890123456789")}

    def path(self, table, row=None, baseline=(), adopted=None, day=None, run=None):
        return exact_path(table, self.row(table) if row is None else row, self.run_id if run is None else run,
                          baseline, {self.recurring_id, self.transaction_id} if adopted is None else adopted,
                          self.day if day is None else day)

    def test_both_paths_guard_every_preimage_field_and_use_sorted_select(self):
        for table in FIELDS:
            with self.subTest(table=table):
                row = self.row(table)
                path = self.path(table, row)
                parsed = urlsplit(path)
                self.assertEqual("/rest/v1/" + table, parsed.path)
                query = parse_qs(parsed.query, strict_parsing=True)
                self.assertEqual(set(row) | {"select"}, set(query))
                self.assertEqual([",".join(sorted(FIELDS[table]))], query["select"])
                for field, value in normalize(table, row).items():
                    expected = "is.null" if value is None else "eq." + (str(value).lower() if type(value) is bool else str(value))
                    self.assertEqual([expected], query[field])
                self.assertEqual(["eq." + A], query["user_id"])
                self.assertEqual(["eq.0.02"], query["amount"])
                self.assertNotIn('"', path)
        recurring = parse_qs(urlsplit(self.path("recurring_expenses")).query)
        self.assertEqual(["eq.false"], recurring["is_active"])
        self.assertEqual(["eq.1"], recurring["week_interval"])

    def test_decimal_and_timestamp_precision_survives_small_decimal_context(self):
        source = self.row("transactions")
        source["created_at"] = "2026-10-14T12:00:00.123456789+02:00"
        source["updated_at"] = "2026-10-14T10:00:00.123456790Z"
        before = copy.deepcopy(source)
        with localcontext() as context:
            context.prec = 6
            normalized = normalize("transactions", source)
            path = self.path("transactions", source)
        self.assertEqual("0.894370123456789012345678901234567890123456789", normalized["exchange_rate_eur"])
        self.assertEqual("2026-10-14T10:00:00.123456789+00:00", normalized["created_at"])
        self.assertEqual("2026-10-14T10:00:00.12345679+00:00", normalized["updated_at"])
        self.assertEqual(["eq." + normalized["exchange_rate_eur"]], parse_qs(urlsplit(path).query)["exchange_rate_eur"])
        self.assertIn("%2B00%3A00", path)
        self.assertEqual(before, source)

    def test_wrong_owner_baseline_unadopted_uuid_and_marker_never_produce_a_path(self):
        for table in FIELDS:
            row = self.row(table)
            marker_key = "name" if table == "recurring_expenses" else "description"
            cases = [({"user_id": B}, (), None), ({}, (row["id"],), None), ({}, (), set()),
                     ({"id": self.unknown_id}, (), None), ({"id": "AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA"}, (), None),
                     ({marker_key: "ordinary-user-data"}, (), None),
                     ({marker_key: row[marker_key] + "-suffix"}, (), None)]
            for changes, baseline, adopted in cases:
                with self.subTest(table=table, changes=changes, baseline=baseline, adopted=adopted):
                    with self.assertRaises(EvidenceError):
                        self.path(table, {**row, **changes}, baseline, adopted)

    def test_active_soft_deleted_wrong_cadence_and_changed_due_are_rejected(self):
        for changes in ({"is_active": True}, {"is_active": "false"}, {"is_active": 0},
                        {"deleted_at": "2026-10-14T12:00:02+02:00"}, {"frequency": "monthly"},
                        {"week_interval": 2}, {"week_interval": True}, {"week_interval": "1"},
                        {"next_due_date": self.day}, {"next_due_date": "2026-10-22"}):
            with self.subTest(changes=changes), self.assertRaises(EvidenceError):
                self.path("recurring_expenses", {**self.row("recurring_expenses"), **changes})

    def test_transaction_payload_and_positive_finite_exchange_rate_are_required(self):
        changes = ({"type": "income"}, {"date": "2026-10-15"}, {"category_id": self.recurring_id},
                   {"amount": "0.01"}, {"currency": "USD"}, {"deleted_at": "2026-10-14T12:00:02+02:00"})
        for change in changes:
            with self.subTest(change=change), self.assertRaises(EvidenceError):
                self.path("transactions", {**self.row("transactions"), **change})
        for rate in ("NaN", "Infinity", "-Infinity", "0", "-0.01", True, 0.89437, "1e9999999", "0e-9999999"):
            with self.subTest(rate=rate), self.assertRaises(EvidenceError):
                self.path("transactions", {**self.row("transactions"), "exchange_rate_eur": rate})

    def test_extra_missing_fields_unknown_table_and_invalid_registry_are_rejected(self):
        for table in FIELDS:
            row = self.row(table)
            for field in row:
                incomplete = dict(row); incomplete.pop(field)
                with self.subTest(table=table, missing=field), self.assertRaises(EvidenceError):
                    self.path(table, incomplete)
            with self.assertRaises(EvidenceError):
                self.path(table, {**row, "unexpected": "ignored"})
        for table in ("categories", "budgets", "transactions?user_id=eq.foreign", None, []):
            with self.subTest(table=table), self.assertRaises(EvidenceError):
                exact_path(table, self.row("transactions"), self.run_id, (), {self.transaction_id}, self.day)
        for values in (self.transaction_id, {"bad-uuid"}, ["AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA"]):
            with self.subTest(values=values), self.assertRaises(EvidenceError):
                self.path("transactions", adopted=values)

    def test_control_characters_invalid_timestamps_dates_and_run_are_rejected(self):
        for table in FIELDS:
            row = self.row(table)
            for field in row:
                if isinstance(row[field], str):
                    for control in ("\x00", "\n", "\r", "\t", "\x7f", "\x85", "\u2028"):
                        with self.subTest(table=table, field=field, control=repr(control)), self.assertRaises(EvidenceError):
                            self.path(table, {**row, field: row[field] + control})
        for timestamp in ("2026-10-14T10:00:00", "2026-02-30T10:00:00Z", "2026-10-14T10:00:00.1234567890Z",
                          "0001-01-01T00:00:00+01:00", None):
            with self.subTest(timestamp=timestamp), self.assertRaises(EvidenceError):
                self.path("transactions", {**self.row("transactions"), "created_at": timestamp})
        for day in ("20261014", "2026-02-30", "2026-10-15", "9999-12-31", "2026-10-14\n"):
            with self.subTest(day=day), self.assertRaises(EvidenceError):
                self.path("recurring_expenses", day=day)
        with self.assertRaises(EvidenceError):
            self.path("transactions", run="not-a-uuid")

    def test_reversed_timestamps_and_non_exact_amount_are_rejected(self):
        for table in FIELDS:
            with self.subTest(table=table), self.assertRaises(EvidenceError):
                self.path(table, {**self.row(table), "updated_at": "2026-10-14T10:00:00.123455Z"})
            with self.subTest(table=table), self.assertRaises(EvidenceError):
                self.path(table, {**self.row(table), "amount": "0.0200000000000000000000000000000000000001"})


if __name__ == "__main__":
    unittest.main()
