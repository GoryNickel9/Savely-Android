"""Exact-run host ledger for signed release Couple forms. All product mutations remain UI-only."""
import argparse
import copy
import datetime as dt
from decimal import Decimal
import json
from pathlib import Path

from couple_invitation_evidence import (A, B, QA, QA_SQL, QA_USERS, QA_RUN, PROJECT, Management,
    EvidenceError, need, canonical, instant, literal, exclusive, SNAPSHOT_SQL as BASE_SQL,
    REQUEST_FIELDS, AUDIT_FIELDS)

ROOT = Path(__file__).resolve().parent.parent / "artifacts/couple-form-ui"
OPS = ("invite", "accept", "equal-tx", "equal-share", "custom-tx", "custom-share", "budget",
    "budget-update", "remove-equal", "remove-custom", "reshare-custom", "remove-custom-partner",
    "reshare-equal", "remove-equal-creator", "final-custom", "revoke")
ACTORS = (A, B, A, A, B, B, A, B, B, B, B, A, A, A, B, A)
TABLES = ("couple_connection_requests", "couple_connections", "transactions", "shared_expenses", "couple_budgets", "couple_audit_log")
SCOPES = {
    "couple_connection_requests": f"sender_id in ({QA_SQL}) or receiver_id in ({QA_SQL})",
    "couple_connections": f"user_a in ({QA_SQL}) or user_b in ({QA_SQL})",
    "transactions": f"user_id in ({QA_SQL})",
    "shared_expenses": f"created_by in ({QA_SQL}) or connection_id in (select id from public.couple_connections where user_a in ({QA_SQL}) or user_b in ({QA_SQL}))",
    "couple_budgets": f"created_by in ({QA_SQL}) or connection_id in (select id from public.couple_connections where user_a in ({QA_SQL}) or user_b in ({QA_SQL}))",
    "couple_audit_log": f"actor_id in ({QA_SQL}) or connection_id in (select id from public.couple_connections where user_a in ({QA_SQL}) or user_b in ({QA_SQL}))",
}
FIELDS = {
    "couple_connection_requests": REQUEST_FIELDS,
    "couple_audit_log": AUDIT_FIELDS,
    "couple_connections": {"id", "user_a", "user_b", "created_at", "updated_at", "revoked_at"},
    "transactions": {"id", "user_id", "category_id", "type", "amount", "currency", "description", "date", "created_at", "updated_at", "deleted_at", "exchange_rate_eur"},
    "shared_expenses": {"id", "connection_id", "original_tx_id", "created_by", "split_percentage", "couple_category_name", "created_at", "updated_at", "split_mode", "partner_amount"},
    "couple_budgets": {"id", "connection_id", "couple_category_name", "amount", "currency", "month", "year", "created_by", "created_at", "updated_at"},
}
RECORD_SQL = ",".join(f"'{table}',(select coalesce(jsonb_agg(to_jsonb(t) order by id),'[]'::jsonb) from "
    f"(select * from public.{table} where {SCOPES[table]} order by id limit 101) t)" for table in TABLES)
COUNT_SQL = ",".join(f"'{table}',(select count(*) from public.{table} where {SCOPES[table]})" for table in TABLES)
SNAPSHOT_SQL = f"with base as ({BASE_SQL}) select snapshot || jsonb_build_object('day',(now() at time zone 'Europe/Rome')::date," \
    f"'records',jsonb_build_object({RECORD_SQL}),'counts',jsonb_build_object({COUNT_SQL})) as snapshot from base"


def number(value, expected):
    try:
        return not isinstance(value, bool) and Decimal(str(value)) == Decimal(str(expected))
    except Exception:
        return False


def payload(row, expected):
    for key, value in expected.items():
        actual = row.get(key)
        if key in ("amount", "exchange_rate_eur", "partner_amount", "split_percentage") and value is not None:
            need(number(actual, value), "PAYLOAD_NUMBER_CHANGED")
        else:
            need(key in row and actual == value, "PAYLOAD_CHANGED")
            if type(value) is int:
                need(type(actual) is int, "PAYLOAD_INTEGER_TYPE_INVALID")


def normalized(records):
    return {table: sorted(rows, key=lambda r: r["id"]) for table, rows in records.items()}


class FormEvidence:
    def __init__(self, run, executor, root=ROOT):
        self.run = canonical(run)
        self.directory = root / run / "evidence"
        self.execute = executor
        self.marker = "android-couple-form-" + run

    def path(self, name):
        need(name in ("preflight", "cleanup-reservation", "cleanup-complete") or
            name in {op + suffix for op in OPS for suffix in ("-reservation", "-observed")}, "EVIDENCE_NAME_INVALID")
        return self.directory / (name + ".json")

    def save(self, name, value):
        exclusive(self.path(name), {"version": 1, "run_id": self.run, "project": PROJECT, "qa_run": QA_RUN,
            "user_a": A, "user_b": B, "marker": self.marker, **value})

    def read(self, name):
        try:
            with self.path(name).open("rb") as source:
                raw = source.read(262145)
            need(len(raw) <= 262144, "EVIDENCE_TOO_LARGE")
            value = json.loads(raw)
            need(all(value.get(k) == v for k, v in {"version": 1, "run_id": self.run, "project": PROJECT,
                "qa_run": QA_RUN, "user_a": A, "user_b": B, "marker": self.marker}.items()), "EVIDENCE_SCOPE_INVALID")
            return value
        except EvidenceError:
            raise
        except Exception:
            raise EvidenceError("EVIDENCE_INVALID_OR_MISSING") from None

    def validate_snapshot(self, value):
        instant(value["now"])
        need(isinstance(value.get("day"), str) and dt.date.fromisoformat(value["day"]).isoformat() == value["day"], "DATABASE_DAY_INVALID")
        need({u.get("email"): u.get("id") for u in value["users"]} == QA_USERS and len(value["users"]) == 3 and
            all(u.get("confirmed") is True and u.get("qa_run") == QA_RUN for u in value["users"]), "QA_IDENTITIES_INVALID")
        profiles = value["profiles"]
        need(len(profiles) == 2 and {p["user_id"] for p in profiles} == {A, B}, "QA_PROFILES_INVALID")
        for profile in profiles:
            code = profile.get("couple_code")
            need(isinstance(code, str) and len(code) == 8 and all(c in "ABCDEFGHJKMNPQRSTUVWXYZ23456789" for c in code), "COUPLE_CODE_INVALID")
            need(profile["permissions"].get("couple_expenses") is True and profile["permissions"].get("admin") is not True, "COUPLE_PERMISSION_INVALID")
        need(profiles[0]["couple_code"] != profiles[1]["couple_code"], "COUPLE_CODE_COLLISION")
        need(value["factors"] == 0 and value["registrations"] == "false" and type(value["sessions"]) is int and value["sessions"] >= 0, "QA_CONFIG_OR_FACTORS_CHANGED")
        need(set(value["records"]) == set(TABLES) and set(value["counts"]) == set(TABLES), "RECORD_TABLES_INVALID")
        ids = set()
        for table in TABLES:
            rows = value["records"][table]
            need(isinstance(rows, list) and type(value["counts"][table]) is int and len(rows) == value["counts"][table] <= 30, "RECORD_LIMIT_OR_TRUNCATION")
            for row in rows:
                need(set(row) == FIELDS[table], "ROW_SCHEMA_CHANGED")
                identifier = canonical(row["id"])
                need(identifier not in ids, "ROW_ID_COLLISION")
                ids.add(identifier)
        return value

    def snapshot(self):
        rows = self.execute(SNAPSHOT_SQL, read_only=True)
        need(isinstance(rows, list) and len(rows) == 1 and isinstance(rows[0].get("snapshot"), dict), "SNAPSHOT_INVALID")
        return self.validate_snapshot(rows[0]["snapshot"])

    def same_identity(self, before, after):
        need(before["users"] == after["users"] and before["profiles"] == after["profiles"] and
            before["day"] == after["day"] and instant(before["now"]) <= instant(after["now"]), "IDENTITY_OR_DAY_OR_CLOCK_CHANGED")

    def preflight(self):
        need(not self.directory.exists(), "RUN_ALREADY_EXISTS")
        value = self.snapshot()
        need(value["sessions"] == 0 and all(not rows for rows in value["records"].values()), "QA_NOT_EMPTY")
        self.directory.mkdir(parents=True, exist_ok=False)
        self.save("preflight", {"snapshot": value})
        return value

    def history(self):
        state = self.validate_snapshot(self.read("preflight")["snapshot"])
        need(state["sessions"] == 0 and all(not rows for rows in state["records"].values()), "PREFLIGHT_CHANGED")
        bindings, seen, completed = {}, set(), []
        for op in OPS:
            if not self.path(op + "-observed").exists():
                break
            reservation = self.read(op + "-reservation")
            before = self.validate_snapshot(reservation["snapshot"])
            self.same_identity(state, before)
            need(normalized(state["records"]) == normalized(before["records"]), "RESERVATION_BASELINE_CHANGED")
            need(reservation.get("actor") == ACTORS[OPS.index(op)], "RESERVATION_ACTOR_CHANGED")
            after = self.validate_snapshot(self.read(op + "-observed")["snapshot"])
            bindings, seen = self.transition(op, before, after, bindings, seen)
            state = after
            completed.append(op)
        # A later observation cannot silently be skipped after a missing earlier one.
        need(not any(self.path(op + "-observed").exists() for op in OPS[len(completed):]), "OBSERVATION_ORDER_INVALID")
        return state, bindings, seen, completed

    def reserve(self, op):
        need(op in OPS, "OPERATION_INVALID")
        need(not self.path(op + "-reservation").exists(), "SECOND_ACTION_ATTEMPT_BLOCKED")
        state, _, _, completed = self.history()
        need(len(completed) < len(OPS) and OPS[len(completed)] == op, "OPERATION_ORDER_INVALID")
        current = self.snapshot()
        self.same_identity(state, current)
        need(normalized(current["records"]) == normalized(state["records"]), "UNKNOWN_OR_CHANGED_RECORDS")
        self.save(op + "-reservation", {"actor": ACTORS[OPS.index(op)], "snapshot": current})
        return current

    def observe(self, op):
        need(op in OPS, "OPERATION_INVALID")
        need(not self.path(op + "-observed").exists(), "ACTION_ALREADY_OBSERVED")
        state, bindings, seen, completed = self.history()
        need(len(completed) < len(OPS) and OPS[len(completed)] == op, "OPERATION_ORDER_INVALID")
        reservation = self.read(op + "-reservation")
        before = self.validate_snapshot(reservation["snapshot"])
        self.same_identity(state, before)
        need(normalized(before["records"]) == normalized(state["records"]) and
            reservation.get("actor") == ACTORS[OPS.index(op)], "RESERVATION_CHANGED")
        after = self.snapshot()
        self.transition(op, before, after, bindings, seen)
        self.save(op + "-observed", {"snapshot": after})
        return after

    def transition(self, op, before, after, prior_bindings, prior_seen):
        self.same_identity(before, after)
        bindings, seen = copy.deepcopy(prior_bindings), set(prior_seen)
        expected = copy.deepcopy(before["records"])
        actor = ACTORS[OPS.index(op)]
        start, end = instant(before["now"]), instant(after["now"])

        def add(table, label, body):
            old = {r["id"] for r in before["records"][table]}
            added = [r for r in after["records"][table] if r["id"] not in old]
            # Every supported operation adds at most one row to each table.
            need(len(added) == 1, "NEW_ROW_NOT_UNIQUE")
            row = added[0]
            need(row["id"] not in seen, "HISTORIC_ID_REUSED")
            payload(row, body)
            need(start <= instant(row["created_at"]) <= end, "CREATED_OUTSIDE_RESERVATION")
            if "updated_at" in row:
                need(instant(row["created_at"]) <= instant(row["updated_at"]) <= end, "NEW_ROW_UPDATE_TIME_INVALID")
            seen.add(row["id"]); bindings[label] = copy.deepcopy(row)
            expected[table].append(row)
            return row

        def audit(action, target, connection, metadata):
            return add("couple_audit_log", op + "-audit", {"actor_id": actor, "action": action,
                "target_id": target, "connection_id": connection, "metadata": metadata})

        def change(table, label, updates):
            old = bindings[label]
            rows = [r for r in after["records"][table] if r["id"] == old["id"]]
            need(len(rows) == 1, "CHANGED_ROW_NOT_FOUND")
            row = rows[0]
            payload(row, updates)
            untouched = set(old) - set(updates) - {"updated_at"}
            need(all(row[k] == old[k] for k in untouched), "UNEXPECTED_FIELD_CHANGED")
            need(start <= instant(row["updated_at"]) <= end, "UPDATED_OUTSIDE_RESERVATION")
            expected[table] = [row if r["id"] == old["id"] else r for r in expected[table]]
            bindings[label] = copy.deepcopy(row)
            return row

        def remove(table, label):
            row = bindings[label]
            expected[table] = [r for r in expected[table] if r["id"] != row["id"]]
            return row

        pair = {"sender_id": A, "receiver_id": B}
        if op == "invite":
            row = add("couple_connection_requests", "request", {**pair, "status": "pending"})
            audit("request_sent", row["id"], None, pair)
        elif op == "accept":
            request = change("couple_connection_requests", "request", {"status": "accepted"})
            connection = add("couple_connections", "connection", {"user_a": A, "user_b": B, "revoked_at": None})
            audit("request_accepted", request["id"], connection["id"], pair)
        elif op in ("equal-tx", "custom-tx"):
            equal = op == "equal-tx"
            add("transactions", op, {"user_id": actor, "type": "expense", "amount": "10.01" if equal else "7.00",
                "currency": "EUR" if equal else "USD", "exchange_rate_eur": "1", "category_id": None,
                "description": self.marker + ("-eur" if equal else "-usd"), "date": before["day"], "deleted_at": None})
        elif op in ("equal-share", "custom-share", "reshare-equal", "reshare-custom", "final-custom"):
            equal = op in ("equal-share", "reshare-equal")
            tx = bindings["equal-tx" if equal else "custom-tx"]
            row = add("shared_expenses", op, {"connection_id": bindings["connection"]["id"], "original_tx_id": tx["id"],
                "created_by": actor, "couple_category_name": self.marker, "split_mode": "equal" if equal else "custom",
                "partner_amount": None if equal else "2.01", "split_percentage": "50" if equal else "28.71"})
            audit("shared_expense_created", row["id"], row["connection_id"], {"original_tx_id": tx["id"], "couple_category_name": self.marker})
        elif op == "budget":
            add("couple_budgets", "budget", {"connection_id": bindings["connection"]["id"], "couple_category_name": self.marker,
                "amount": "3", "currency": "EUR", "month": 1, "year": 2000, "created_by": A})
        elif op == "budget-update":
            change("couple_budgets", "budget", {"amount": "4"})
        elif op == "remove-equal":
            row = remove("shared_expenses", "equal-share")
            audit("shared_expense_deleted", row["id"], row["connection_id"], {})
        elif op == "remove-custom":
            remove("shared_expenses", "custom-share")  # Direct creator DELETE has no audit trigger.
        elif op == "remove-custom-partner":
            row = remove("shared_expenses", "reshare-custom")
            audit("shared_expense_deleted", row["id"], row["connection_id"], {})
        elif op == "remove-equal-creator":
            remove("shared_expenses", "reshare-equal")
        elif op == "revoke":
            rows = after["records"]["couple_connections"]
            need(len(rows) == 1 and rows[0]["revoked_at"] is not None, "REVOCATION_NOT_FOUND")
            instant(rows[0]["revoked_at"])  # Client clock is not used as ownership proof; updated_at and audit are DB timestamps.
            row = change("couple_connections", "connection", {"revoked_at": rows[0]["revoked_at"]})
            audit("connection_revoked", row["id"], row["id"], {"user_a": A, "user_b": B})
        need(normalized(expected) == normalized(after["records"]), "UNKNOWN_OR_CHANGED_RECORDS")
        return bindings, seen

    def cleanup(self):
        state, bindings, _, completed = self.history()
        need(tuple(completed) == OPS and bindings["connection"]["revoked_at"] is not None, "REVOCATION_PROOF_REQUIRED")
        current = self.snapshot()
        self.same_identity(state, current)
        need(current["sessions"] == 0, "QA_SESSIONS_NOT_LOGGED_OUT")
        if self.path("cleanup-reservation").exists():
            saved = self.read("cleanup-reservation")
            need(saved["snapshot"] == state, "CLEANUP_RESERVATION_CHANGED")
            need(all(not rows for rows in current["records"].values()), "CLEANUP_UNCERTAIN_NO_REPLAY")
        else:
            need(normalized(current["records"]) == normalized(state["records"]), "UNKNOWN_OR_CHANGED_RECORDS")
            # Parent deletion must never cascade an unregistered row. All rows were validated above.
            self.save("cleanup-reservation", {"snapshot": state, "reserved_at": current["now"]})
            order = ("shared_expenses", "couple_audit_log", "couple_budgets", "transactions", "couple_connections", "couple_connection_requests")
            for table in order:
                for row in state["records"][table]:
                    body = literal(json.dumps(row, sort_keys=True, separators=(",", ":")))
                    # Require the known children already absent before deleting their parent.
                    children = ""
                    if table == "couple_connections":
                        children = " and not exists(select 1 from public.shared_expenses where connection_id=t.id)" \
                            " and not exists(select 1 from public.couple_budgets where connection_id=t.id)" \
                            " and not exists(select 1 from public.couple_audit_log where connection_id=t.id)"
                    elif table == "transactions":
                        children = " and not exists(select 1 from public.shared_expenses where original_tx_id=t.id)"
                    sql = f"delete from public.{table} t where t.id={literal(row['id'])}::uuid and to_jsonb(t)={body}::jsonb{children} returning id"
                    need(self.execute(sql, read_only=False) == [{"id": row["id"]}], "DELETE_RESPONSE_UNCERTAIN")
                    need(self.execute(f"select id from public.{table} where id={literal(row['id'])}::uuid", read_only=True) == [], "DELETE_ABSENCE_UNCONFIRMED")
            current = self.snapshot()
            self.same_identity(state, current)
            need(current["sessions"] == 0 and all(not rows for rows in current["records"].values()), "FINAL_CLEANUP_PENDING")
        if not self.path("cleanup-complete").exists():
            self.save("cleanup-complete", {"observed_at": current["now"], "cleanup_pending": False})
        return current


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("preflight", "reserve", "observe", "adopt", "cleanup", "selfcheck", "test"))
    parser.add_argument("run", nargs="?")
    parser.add_argument("operation", nargs="?", choices=OPS)
    args = parser.parse_args()
    if args.command in ("selfcheck", "test"):
        import unittest
        suite = unittest.defaultTestLoader.discover(str(Path(__file__).parent), pattern="test_couple_form_evidence.py")
        raise SystemExit(0 if unittest.TextTestRunner().run(suite).wasSuccessful() else 1)
    canonical(args.run)
    need((args.operation is not None) == (args.command in ("reserve", "observe", "adopt")), "COMMAND_OPERATION_INVALID")
    from native_auth_config import management_token
    proof = FormEvidence(args.run, Management(management_token()))
    if args.command == "preflight":
        value = proof.preflight()
    elif args.command == "cleanup":
        value = proof.cleanup()
    elif args.command == "reserve":
        value = proof.reserve(args.operation)
    else:
        value = proof.observe(args.operation)
    print(json.dumps({"run_id": proof.run, "command": args.command, "operation": args.operation,
        "database_time": value["now"], "day": value["day"], "sessions": value["sessions"], "counts": value["counts"]}))


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print(str(error) if isinstance(error, EvidenceError) else "FAILURE_DETAILS_WITHHELD")
        raise SystemExit(1)
