"""Offline V06 guards: synthetic rows, no network or credential store."""
import copy
import datetime as dt
import unittest

import portfolio_form_evidence as p
from test_recurring_form_evidence import FakeSql
from test_finance_form_evidence import RUN
from test_couple_invitation_evidence import time

IDS = {"single": "11111111-1111-4111-8111-111111111111", "first": "22222222-2222-4222-8222-222222222222",
       "second": "33333333-3333-4333-8333-333333333333"}


class PortfolioEvidenceTest(unittest.TestCase):
    def setUp(self):
        self.before = {"finance": FakeSql().value, "rows": {table: [] for table in p.TABLES},
                       "counts": {table: 0 for table in p.TABLES}}
        self.before["finance"]["now"] = time(10)
        self.registry = {}

    def update(self, value):
        value["counts"] = {table: len(rows) for table, rows in value["rows"].items()}
        return value

    def created(self, key="single"):
        value = copy.deepcopy(self.before)
        written = (p.instant(self.before["finance"]["now"]) + dt.timedelta(seconds=1)).isoformat()
        value["finance"]["now"] = written
        value["rows"]["portfolio_assets"].append({"id": IDS[key], "user_id": p.A,
            "name": f"android-portfolio-form-{RUN}-{key}", "symbol": "AAPL", "type": "stock",
            "quantity": "3.00000000" if key == "second" else "2.00000000", "purchase_price": "0.01",
            "current_price": "0.02", "currency": "EUR", "purchase_date": value["finance"]["day"],
            "notes": f"android-portfolio-form-{RUN}", "created_at": written, "updated_at": written,
            "sold_at": None, "sold_price": None})
        return self.update(value)

    def adopt(self, key="single"):
        value = self.created(key)
        self.registry = p.transition("create-" + key, self.before, value, self.registry, RUN)
        self.before = value

    def reject(self, code, action):
        with self.assertRaisesRegex(p.finance.EvidenceError, "^" + code + "$"):
            action()

    def test_create_edit_close_delete_preserves_decimal_identity_and_has_no_replay(self):
        self.adopt()
        self.reject("PORTFOLIO_CREATION_REPLAY", lambda: p.transition("create-single", self.before, self.before, self.registry, RUN))
        for op, change in (("edit-single", {"current_price": "0.030"}),
                           ("close-single", {"sold_price": "0.03", "sold_at": self.before["finance"]["day"]})):
            value = copy.deepcopy(self.before)
            value["rows"]["portfolio_assets"][0].update(**change, updated_at=value["finance"]["now"])
            self.registry = p.transition(op, self.before, value, self.registry, RUN)
            self.before = value
        value = copy.deepcopy(self.before)
        value["rows"]["portfolio_assets"] = []
        self.update(value)
        removed = p.transition("delete-single", self.before, value, self.registry, RUN)
        self.assertTrue(removed["single"]["removed"])

    def test_creation_rejects_owner_symbol_notes_quantity_currency_date_or_price_changes(self):
        for key, bad in (("user_id", p.QA[1]), ("symbol", "MSFT"), ("notes", "other"),
                         ("quantity", "3"), ("currency", "USD"), ("purchase_date", "2026-01-01"), ("purchase_price", "0.02")):
            with self.subTest(key=key):
                value = self.created()
                value["rows"]["portfolio_assets"][0][key] = bad
                self.reject("PORTFOLIO_PAYLOAD_CHANGED", lambda: p.transition("create-single", self.before, value, {}, RUN))

    def test_cancel_and_closed_read_reject_every_portfolio_mutation(self):
        for op in p.READS:
            self.assertEqual({}, p.transition(op, self.before, self.before, {}, RUN))
            self.reject("PORTFOLIO_UNRESERVED_CHANGE", lambda: p.transition(op, self.before, self.created(), {}, RUN))

    def test_group_requires_both_adopted_open_preimages_and_exact_sale_price(self):
        self.adopt("first")
        self.adopt("second")
        value = copy.deepcopy(self.before)
        for row in value["rows"]["portfolio_assets"]:
            row.update(sold_at=value["finance"]["day"], sold_price="0.03", updated_at=value["finance"]["now"])
        result = p.transition("close-group", self.before, value, self.registry, RUN)
        self.assertEqual(2, len(result))
        value["rows"]["portfolio_assets"][1]["sold_price"] = "0.04"
        self.reject("PORTFOLIO_PAYLOAD_CHANGED", lambda: p.transition("close-group", self.before, value, self.registry, RUN))

    def test_snapshot_rejects_truncation_foreign_owner_duplicate_id_and_numeric_float(self):
        value = self.created()
        for label, mutate, code in (
            ("count", lambda v: v["counts"].update(portfolio_assets=2), "PORTFOLIO_ROWS_TRUNCATED"),
            ("owner", lambda v: v["rows"]["portfolio_assets"][0].update(user_id="44444444-4444-4444-8444-444444444444"), "PORTFOLIO_OWNER_INVALID"),
            ("number", lambda v: v["rows"]["portfolio_assets"][0].update(quantity=2.0), "DECIMAL_TEXT_REQUIRED"),
        ):
            with self.subTest(label=label):
                changed = copy.deepcopy(value)
                mutate(changed)
                self.reject(code, lambda: p.validate(changed))
        duplicate = copy.deepcopy(value)
        duplicate["rows"]["portfolio_assets"] *= 2
        self.update(duplicate)
        self.reject("PORTFOLIO_OWNER_INVALID", lambda: p.validate(duplicate))

    def test_baseline_preserves_existing_daily_snapshot_but_rejects_existing_assets(self):
        value = copy.deepcopy(self.before)
        value["rows"]["net_worth_snapshots"] = [{"id": IDS["first"], "user_id": p.A, "date": value["finance"]["day"],
            "net_worth": "900719925474099.01", "components": {"cash": 0}, "created_at": time(0)}]
        self.update(value)
        p.baseline(value)
        self.reject("PORTFOLIO_QA_NOT_EMPTY", lambda: p.baseline(self.created()))
        after = copy.deepcopy(value)
        after["rows"]["net_worth_snapshots"][0]["net_worth"] = "0"
        self.reject("PORTFOLIO_UNRESERVED_CHANGE", lambda: p.transition("closed-read", value, after, {}, RUN))

    def test_delete_requires_adopted_uuid_and_preserves_foreign_asset(self):
        self.reject("PORTFOLIO_ADOPTION_MISSING", lambda: p.transition("delete-single", self.before, self.before, {}, RUN))
        self.adopt()
        value = copy.deepcopy(self.before)
        self.reject("PORTFOLIO_UNRESERVED_CHANGE", lambda: p.transition("delete-single", self.before, value, self.registry, RUN))

    def test_partial_group_commit_is_not_reported_as_complete(self):
        self.adopt("first")
        self.adopt("second")
        value = copy.deepcopy(self.before)
        value["rows"]["portfolio_assets"][0].update(sold_at=value["finance"]["day"], sold_price="0.03", updated_at=value["finance"]["now"])
        self.reject("PORTFOLIO_PAYLOAD_CHANGED", lambda: p.transition("close-group", self.before, value, self.registry, RUN))

    def prices(self):
        for key in ("single", "first", "second"):
            self.adopt(key)
        value = copy.deepcopy(self.before)
        now = value["finance"]["now"]
        for index, row in enumerate(value["rows"]["portfolio_assets"]):
            row.update(current_price="250.13", updated_at=now)
            value["rows"]["asset_price_history"].append({"id": f"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa{index}",
                "user_id": p.A, "asset_id": row["id"], "price": "250.125", "recorded_at": value["finance"]["day"], "created_at": now})
        for index, table in enumerate(("manual_price_updates", "price_update_logs")):
            log = {"id": f"bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbb{index}", "user_id": p.A,
                   "updated_at": now, "assets_checked": 3, "assets_updated": 3}
            log.update({"created_at": now} if index == 0 else {"errors": []})
            value["rows"][table].append(log)
        return self.update(value)

    def test_prices_adopts_only_own_three_quotes_and_raw_history_with_matching_counts(self):
        value = self.prices()
        result = p.transition("prices", self.before, value, self.registry, RUN)
        self.assertEqual("250.13", result["single"]["row"]["current_price"])
        self.assertEqual("250.125", value["rows"]["asset_price_history"][0]["price"])

    def test_prices_cannot_change_purchase_data_or_rewrite_daily_snapshots(self):
        value = self.prices()
        value["rows"]["portfolio_assets"][0]["purchase_price"] = "99"
        self.reject("PORTFOLIO_PAYLOAD_CHANGED", lambda: p.transition("prices", self.before, value, self.registry, RUN))

    def test_prices_require_each_log_once_and_history_bound_to_adopted_assets(self):
        value = self.prices()
        for table in ("manual_price_updates", "price_update_logs"):
            changed = copy.deepcopy(value)
            changed["rows"][table] = []
            self.update(changed)
            self.reject("PORTFOLIO_PRICE_LOG_INVALID", lambda: p.transition("prices", self.before, changed, self.registry, RUN))
        value["rows"]["asset_price_history"][0]["asset_id"] = "44444444-4444-4444-8444-444444444444"
        self.reject("PORTFOLIO_HISTORY_INVALID", lambda: p.transition("prices", self.before, value, self.registry, RUN))

    def test_price_count_mismatch_is_never_a_success(self):
        value = self.prices()
        value["rows"]["manual_price_updates"][0]["assets_updated"] = 2
        self.reject("PORTFOLIO_PRICE_COUNTS_CHANGED", lambda: p.transition("prices", self.before, value, self.registry, RUN))

    def test_cleanup_is_limited_to_one_adopted_qa_log_full_preimage(self):
        value = self.prices()
        for table in ("manual_price_updates", "price_update_logs"):
            row = value["rows"][table][0]
            sql = p.cleanup_sql(table, row, self.before, value)
            self.assertIn("delete from public." + table + " t where t.id='" + row["id"] + "'::uuid", sql)
            self.assertIn("to_jsonb(t)-'created_at'-'updated_at'", sql)
            changed = copy.deepcopy(row)
            changed["assets_updated"] = 2
            self.reject("PORTFOLIO_CLEANUP_NOT_ADOPTED", lambda: p.cleanup_sql(table, changed, self.before, value))
            changed["user_id"] = p.QA[1]
            self.reject("PORTFOLIO_CLEANUP_OWNER_INVALID", lambda: p.cleanup_sql(table, changed, self.before, value))
            self.reject("PORTFOLIO_CLEANUP_NOT_ADOPTED", lambda: p.cleanup_sql(table, row, value, value))
        self.reject("PORTFOLIO_CLEANUP_TABLE_INVALID", lambda: p.cleanup_sql("net_worth_snapshots", {}, self.before, value))


if __name__ == "__main__":
    unittest.main()
