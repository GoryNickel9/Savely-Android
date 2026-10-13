import unittest
from urllib.parse import parse_qs, urlsplit

from couple_invitation_evidence import A, B, EvidenceError
from finance_form_cleanup import exact_path, normalize


class FinanceCleanupTest(unittest.TestCase):
    run_id = "11111111-1111-4111-8111-111111111111"
    identifier = "22222222-2222-4222-8222-222222222222"

    def category(self):
        return {"id": self.identifier, "user_id": A, "name": "android-finance-form-" + self.run_id + "-expense",
                "type": "expense", "icon": "📦", "color": "#6b7280", "created_at": "2026-10-13T12:00:00+00:00", "deleted_at": None}

    def test_all_projection_fields_guard_the_delete_including_null(self):
        row = self.category()
        path = exact_path("categories", row, self.run_id, set(), {self.identifier})
        query = parse_qs(urlsplit(path).query)
        self.assertEqual(set(row) | {"select"}, set(query))
        self.assertEqual(["is.null"], query["deleted_at"])
        self.assertEqual(["eq." + A], query["user_id"])

    def test_baseline_foreign_unadopted_wrong_marker_and_extra_fields_are_rejected(self):
        for change, baseline, adopted in (
            ({}, {self.identifier}, {self.identifier}), ({}, set(), set()),
            ({"user_id": B}, set(), {self.identifier}), ({"name": "ordinary-user-data"}, set(), {self.identifier}),
            ({"extra": "unexpected"}, set(), {self.identifier}),
        ):
            with self.subTest(change=change, baseline=baseline, adopted=adopted):
                with self.assertRaises(EvidenceError):
                    exact_path("categories", {**self.category(), **change}, self.run_id, baseline, adopted)

    def test_budget_requires_an_adopted_category_and_numeric_predicate(self):
        cat = "33333333-3333-4333-8333-333333333333"
        row = {"id": self.identifier, "user_id": A, "category_id": cat, "amount": 4.0,
               "currency": "EUR", "month": 1, "year": 2000,
               "created_at": "2026-10-13T12:00:00+00:00", "updated_at": "2026-10-13T12:00:01+00:00"}
        self.assertEqual("4", normalize("budgets", row)["amount"])
        with self.assertRaises(EvidenceError):
            exact_path("budgets", row, self.run_id, set(), {self.identifier})
        path = exact_path("budgets", row, self.run_id, set(), {self.identifier, cat})
        self.assertEqual(["eq.4"], parse_qs(urlsplit(path).query)["amount"])


if __name__ == "__main__":
    unittest.main()
