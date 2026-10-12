"""Fixed C02 fixture, fake SQL only. No credentials, HTTP, shell or device actions."""
import copy
import json
from pathlib import Path
import re
import tempfile
import unittest
import uuid

import couple_form_evidence as forms
from test_couple_invitation_evidence import snapshot as qa_snapshot

RUN = "11111111-1111-4111-8111-111111111111"


class FakeSql:
    def __init__(self):
        self.value = qa_snapshot()
        self.value.update(day="2026-10-12", records={table: [] for table in forms.TABLES}, counts={table: 0 for table in forms.TABLES})
        self.labels = {}
        self.second = 0
        self.calls = []
        self.lose_delete = False
        self.before_delete = None

    def __call__(self, sql, read_only=True):
        self.calls.append((sql, read_only))
        if sql == forms.SNAPSHOT_SQL:
            return [{"snapshot": copy.deepcopy(self.value)}]
        found = re.search(r"public\.([a-z_]+).*?id='([a-f0-9-]+)'::uuid", sql)
        if not found:
            raise AssertionError("Unexpected fake SQL")
        table, identifier = found.groups()
        rows = self.value["records"][table]
        row = next((r for r in rows if r["id"] == identifier), None)
        if read_only:
            return [] if row is None else [{"id": identifier}]
        if self.before_delete:
            self.before_delete()
        if row is None:
            return []
        frozen = forms.literal(json.dumps(row, sort_keys=True, separators=(",", ":")))
        if "and to_jsonb(t)=" + frozen + "::jsonb" not in sql:
            raise AssertionError("CAS did not freeze every returned field")
        if table == "couple_connections":
            for child in ("shared_expenses", "couple_budgets", "couple_audit_log"):
                if any(r.get("connection_id") == identifier for r in self.value["records"][child]):
                    raise AssertionError("Parent deleted before child")
                if "not exists(select 1 from public." + child not in sql:
                    raise AssertionError("Parent CAS omitted child absence")
        if table == "transactions":
            if any(r["original_tx_id"] == identifier for r in self.value["records"]["shared_expenses"]):
                raise AssertionError("Transaction would cascade a share")
        rows.remove(row); self.sync()
        if self.lose_delete:
            raise forms.EvidenceError("MANAGEMENT_OUTCOME_UNCERTAIN")
        return [{"id": identifier}]

    def sync(self):
        self.value["counts"] = {table: len(rows) for table, rows in self.value["records"].items()}

    def mutate(self, op):
        self.second += 1
        now = f"2026-10-12T12:00:{self.second:02d}+00:00"
        self.value["now"] = now
        actor = forms.ACTORS[forms.OPS.index(op)]
        marker = "android-couple-form-" + RUN
        records = self.value["records"]

        def add(table, label, body):
            row = {field: None for field in forms.FIELDS[table]}
            row.update(id=str(uuid.uuid5(uuid.UUID(RUN), label)), created_at=now)
            if "updated_at" in row:
                row["updated_at"] = now
            row.update(body)
            self.labels[label] = row
            records[table].append(row)
            return row

        def audit(action, target, connection, metadata):
            add("couple_audit_log", op + "-audit", {"actor_id": actor, "action": action, "target_id": target,
                "connection_id": connection, "metadata": metadata})

        pair = {"sender_id": forms.A, "receiver_id": forms.B}
        if op == "invite":
            request = add("couple_connection_requests", "request", {**pair, "status": "pending"})
            audit("request_sent", request["id"], None, pair)
        elif op == "accept":
            self.labels["request"].update(status="accepted", updated_at=now)
            conn = add("couple_connections", "connection", {"user_a": forms.A, "user_b": forms.B})
            audit("request_accepted", self.labels["request"]["id"], conn["id"], pair)
        elif op in ("equal-tx", "custom-tx"):
            equal = op == "equal-tx"
            add("transactions", op, {"user_id": actor, "type": "expense", "amount": 10.01 if equal else 7,
                "currency": "EUR" if equal else "USD", "exchange_rate_eur": 1, "description": marker + ("-eur" if equal else "-usd"),
                "date": self.value["day"]})
        elif op in ("equal-share", "custom-share", "reshare-equal", "reshare-custom", "final-custom"):
            equal = op in ("equal-share", "reshare-equal")
            tx = self.labels["equal-tx" if equal else "custom-tx"]
            share = add("shared_expenses", op, {"connection_id": self.labels["connection"]["id"], "original_tx_id": tx["id"],
                "created_by": actor, "couple_category_name": marker, "split_mode": "equal" if equal else "custom",
                "partner_amount": None if equal else 2.01, "split_percentage": 50 if equal else 28.71})
            audit("shared_expense_created", share["id"], share["connection_id"], {"original_tx_id": tx["id"], "couple_category_name": marker})
        elif op == "budget":
            add("couple_budgets", "budget", {"connection_id": self.labels["connection"]["id"], "couple_category_name": marker,
                "amount": 3, "currency": "EUR", "month": 1, "year": 2000, "created_by": forms.A})
        elif op == "budget-update":
            self.labels["budget"].update(amount=4, updated_at=now)
        elif op in ("remove-equal", "remove-custom", "remove-custom-partner", "remove-equal-creator"):
            label = {"remove-equal": "equal-share", "remove-custom": "custom-share", "remove-custom-partner": "reshare-custom",
                "remove-equal-creator": "reshare-equal"}[op]
            row = self.labels[label]
            records["shared_expenses"].remove(row)
            if op in ("remove-equal", "remove-custom-partner"):
                audit("shared_expense_deleted", row["id"], row["connection_id"], {})
        elif op == "revoke":
            self.labels["connection"].update(revoked_at=now, updated_at=now)
            audit("connection_revoked", self.labels["connection"]["id"], self.labels["connection"]["id"],
                {"user_a": forms.A, "user_b": forms.B})
        else:
            raise AssertionError("Unknown synthetic UI operation")
        self.sync()


class CoupleFormEvidenceTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.sql = FakeSql()
        self.proof = forms.FormEvidence(RUN, self.sql, Path(self.temp.name))

    def begin(self):
        self.proof.preflight()

    def step(self, op):
        self.proof.reserve(op)
        self.sql.mutate(op)
        self.proof.observe(op)

    def through(self, last):
        self.begin()
        for op in forms.OPS[:forms.OPS.index(last) + 1]:
            self.step(op)

    def writes(self):
        return [sql for sql, read in self.sql.calls if not read]

    def test_full_sequence_has_five_share_ids_four_removals_and_only_retained_originals(self):
        self.through("revoke")
        _, bindings, _, completed = self.proof.history()
        shares = [bindings[label]["id"] for label in ("equal-share", "custom-share", "reshare-custom", "reshare-equal", "final-custom")]
        self.assertEqual(5, len(set(shares)))
        self.assertEqual(forms.OPS, tuple(completed))
        self.assertEqual([bindings["final-custom"]["id"]], [r["id"] for r in self.sql.value["records"]["shared_expenses"]])
        self.assertEqual(2, len(self.sql.value["records"]["transactions"]))
        self.assertEqual(2, sum(r["action"] == "shared_expense_deleted" for r in self.sql.value["records"]["couple_audit_log"]))
        self.assertEqual(4, bindings["budget"]["amount"])

    def test_cleanup_full_cas_children_before_parents_and_exclusive_reservation(self):
        self.through("revoke")
        total = sum(self.sql.value["counts"].values())
        self.sql.before_delete = lambda: self.assertTrue(self.proof.path("cleanup-reservation").exists())
        result = self.proof.cleanup()
        self.assertTrue(all(not rows for rows in result["records"].values()))
        self.assertEqual(total, len(self.writes()))
        self.proof.cleanup()
        self.assertEqual(total, len(self.writes()))

    def test_preflight_requires_zero_all_qa_records_and_sessions(self):
        for table in forms.TABLES:
            with self.subTest(table=table):
                self.sql = FakeSql(); self.proof.execute = self.sql
                self.sql.value["records"][table] = [{field: None for field in forms.FIELDS[table]}]
                self.sql.value["records"][table][0]["id"] = RUN
                self.sql.sync()
                with self.assertRaisesRegex(forms.EvidenceError, "QA_NOT_EMPTY"):
                    self.proof.preflight()
        self.sql = FakeSql(); self.proof.execute = self.sql
        self.sql.value["sessions"] = 1
        with self.assertRaisesRegex(forms.EvidenceError, "QA_NOT_EMPTY"):
            self.proof.preflight()

    def test_partial_reservation_and_repeated_tap_plan_cannot_be_replayed(self):
        self.begin()
        self.proof.reserve("invite")
        with self.assertRaisesRegex(forms.EvidenceError, "SECOND_ACTION_ATTEMPT_BLOCKED"):
            self.proof.reserve("invite")
        self.assertEqual([], self.writes())

    def test_observation_requires_prior_reservation_and_order(self):
        self.begin()
        with self.assertRaises(forms.EvidenceError):
            self.proof.observe("invite")
        with self.assertRaisesRegex(forms.EvidenceError, "OPERATION_ORDER_INVALID"):
            self.proof.reserve("accept")

    def test_bad_invite_owner_or_metadata_cannot_be_adopted(self):
        self.begin(); self.proof.reserve("invite"); self.sql.mutate("invite")
        original = copy.deepcopy(self.sql.value)
        for table, key, value in (("couple_connection_requests", "receiver_id", RUN),
            ("couple_audit_log", "actor_id", forms.B), ("couple_audit_log", "metadata", {})):
            self.sql.value = copy.deepcopy(original)
            self.sql.value["records"][table][0][key] = value
            with self.subTest(table=table, key=key), self.assertRaises(forms.EvidenceError):
                self.proof.observe("invite")

    def test_equal_transaction_payload_currency_rate_owner_and_marker_are_frozen(self):
        self.through("accept"); self.proof.reserve("equal-tx"); self.sql.mutate("equal-tx")
        original = copy.deepcopy(self.sql.value)
        for key, value in (("user_id", forms.B), ("amount", 10), ("currency", "USD"), ("exchange_rate_eur", 2),
            ("category_id", RUN), ("description", "unrelated"), ("deleted_at", self.sql.value["now"])):
            self.sql.value = copy.deepcopy(original)
            self.sql.value["records"]["transactions"][0][key] = value
            with self.subTest(key=key), self.assertRaises(forms.EvidenceError):
                self.proof.observe("equal-tx")

    def test_share_requires_exact_connection_original_split_and_audit(self):
        self.through("equal-tx"); self.proof.reserve("equal-share"); self.sql.mutate("equal-share")
        original = copy.deepcopy(self.sql.value)
        for key, value in (("connection_id", RUN), ("original_tx_id", RUN), ("split_percentage", 49),
            ("partner_amount", 2.01), ("split_mode", "custom"), ("created_by", forms.B)):
            self.sql.value = copy.deepcopy(original)
            self.sql.value["records"]["shared_expenses"][0][key] = value
            with self.subTest(key=key), self.assertRaises(forms.EvidenceError):
                self.proof.observe("equal-share")

    def test_budget_change_only_amount_and_server_updated_time(self):
        self.through("budget"); self.proof.reserve("budget-update"); self.sql.mutate("budget-update")
        original = copy.deepcopy(self.sql.value)
        for key, value in (("created_by", forms.B), ("currency", "USD"), ("month", 2), ("amount", 5)):
            self.sql.value = copy.deepcopy(original)
            self.sql.value["records"]["couple_budgets"][0][key] = value
            with self.subTest(key=key), self.assertRaises(forms.EvidenceError):
                self.proof.observe("budget-update")

    def test_recipient_remove_requires_delete_audit_and_original_unchanged(self):
        self.through("budget-update"); self.proof.reserve("remove-equal"); self.sql.mutate("remove-equal")
        original = copy.deepcopy(self.sql.value)
        self.sql.value["records"]["couple_audit_log"].pop(); self.sql.sync()
        with self.assertRaises(forms.EvidenceError):
            self.proof.observe("remove-equal")
        self.sql.value = copy.deepcopy(original)
        self.sql.value["records"]["transactions"][0]["amount"] = 20
        with self.assertRaisesRegex(forms.EvidenceError, "UNKNOWN_OR_CHANGED_RECORDS"):
            self.proof.observe("remove-equal")

    def test_creator_remove_must_not_remove_any_other_share_or_original(self):
        self.through("remove-equal"); self.proof.reserve("remove-custom"); self.sql.mutate("remove-custom")
        self.sql.value["records"]["transactions"].pop(); self.sql.sync()
        with self.assertRaisesRegex(forms.EvidenceError, "UNKNOWN_OR_CHANGED_RECORDS"):
            self.proof.observe("remove-custom")

    def test_reshare_must_have_new_uuid_and_same_original(self):
        self.through("remove-custom"); self.proof.reserve("reshare-custom"); self.sql.mutate("reshare-custom")
        row = self.sql.value["records"]["shared_expenses"][0]
        original = row["id"]
        row["id"] = self.sql.labels["custom-share"]["id"]
        with self.assertRaisesRegex(forms.EvidenceError, "HISTORIC_ID_REUSED"):
            self.proof.observe("reshare-custom")
        row["id"] = original
        row["original_tx_id"] = self.sql.labels["equal-tx"]["id"]
        with self.assertRaises(forms.EvidenceError):
            self.proof.observe("reshare-custom")

    def test_wrong_time_unknown_rows_and_changed_profile_block_observation(self):
        self.begin(); self.proof.reserve("invite"); self.sql.mutate("invite")
        original = copy.deepcopy(self.sql.value)
        self.sql.value["records"]["couple_connection_requests"][0]["created_at"] = "2026-10-11T12:00:00Z"
        with self.assertRaisesRegex(forms.EvidenceError, "CREATED_OUTSIDE_RESERVATION"):
            self.proof.observe("invite")
        self.sql.value = copy.deepcopy(original)
        self.sql.value["profiles"][0]["couple_code"] = "ABCDEFGK"
        with self.assertRaises(forms.EvidenceError):
            self.proof.observe("invite")

    def test_cleanup_requires_revocation_zero_sessions_and_no_unknown_children(self):
        self.through("final-custom")
        with self.assertRaisesRegex(forms.EvidenceError, "REVOCATION_PROOF_REQUIRED"):
            self.proof.cleanup()
        self.step("revoke")
        self.sql.value["sessions"] = 1
        with self.assertRaisesRegex(forms.EvidenceError, "QA_SESSIONS_NOT_LOGGED_OUT"):
            self.proof.cleanup()
        self.sql.value["sessions"] = 0
        extra = copy.deepcopy(self.sql.value["records"]["shared_expenses"][0]); extra["id"] = RUN
        self.sql.value["records"]["shared_expenses"].append(extra); self.sql.sync()
        with self.assertRaisesRegex(forms.EvidenceError, "UNKNOWN_OR_CHANGED_RECORDS"):
            self.proof.cleanup()
        self.assertEqual([], self.writes())

    def test_lost_delete_is_reserved_and_never_replayed(self):
        self.through("revoke")
        self.sql.lose_delete = True
        with self.assertRaisesRegex(forms.EvidenceError, "MANAGEMENT_OUTCOME_UNCERTAIN"):
            self.proof.cleanup()
        self.sql.lose_delete = False
        with self.assertRaisesRegex(forms.EvidenceError, "CLEANUP_UNCERTAIN_NO_REPLAY"):
            self.proof.cleanup()
        self.assertEqual(1, len(self.writes()))

    def test_tampered_saved_actor_or_scope_cannot_authorize_cleanup(self):
        self.through("revoke")
        path = self.proof.path("equal-tx-reservation")
        value = json.loads(path.read_text()); value["actor"] = forms.B
        path.write_text(json.dumps(value))
        with self.assertRaisesRegex(forms.EvidenceError, "RESERVATION_ACTOR_CHANGED"):
            self.proof.cleanup()
        self.assertEqual([], self.writes())


if __name__ == "__main__":
    unittest.main()
