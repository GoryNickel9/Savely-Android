"""V03 read-only evidence. Native forms and exact-run cleanup belong to the coordinator."""
import copy
import datetime as dt
from decimal import Decimal, InvalidOperation
import json
from pathlib import Path

from admin_form_evidence import SNAPSHOT_SQL as ACCOUNT_SQL, validate_snapshot as account_snapshot
from couple_invitation_evidence import A, B, QA, QA_SQL, QA_RUN, PROJECT, EvidenceError, need, canonical, instant, exclusive

ROOT = Path(__file__).resolve().parent.parent / "artifacts/finance-form-ui"
TABLES = ("categories", "transactions", "budgets")
OPS = ("expense-category-create", "income-category-create", "expense-create", "income-create",
       "expense-update", "income-update", "budget-create", "budget-update", "expense-category-rename",
       "expense-category-soft-delete", "expense-delete", "income-delete", "budget-delete",
       "income-category-soft-delete", "cleanup-expense-category", "cleanup-income-category")
CHECKPOINTS = ("ready", "category-create-cancelled", "transaction-create-cancelled", "transaction-edit-cancelled",
               "budget-create-cancelled", "budget-edit-cancelled", "category-delete-cancelled",
               "transaction-delete-cancelled", "budget-delete-cancelled", "dashboard-read", "logged-out", "completed")
LIMIT = 500
FIELDS = {
    "categories": {"id", "user_id", "name", "type", "icon", "color", "created_at", "deleted_at"},
    "transactions": {"id", "user_id", "category_id", "type", "amount", "currency", "description", "date",
                     "created_at", "updated_at", "deleted_at", "exchange_rate_eur"},
    "budgets": {"id", "user_id", "category_id", "amount", "currency", "month", "year", "created_at", "updated_at"},
}
SELECTS = {
    "categories": "id,user_id,name,type,icon,color,created_at,deleted_at",
    "transactions": "id,user_id,category_id,type,amount::text as amount,currency,description,date,created_at,updated_at,deleted_at,exchange_rate_eur::text as exchange_rate_eur",
    "budgets": "id,user_id,category_id,amount::text as amount,currency,month,year,created_at,updated_at",
}
# Fixed fields and owners only. Decimal text avoids loss in the Management JSON float parser.
RECORD_SQL = ",".join(f"'{table}',(select coalesce(jsonb_agg(to_jsonb(t) order by id),'[]'::jsonb) from "
    f"(select {SELECTS[table]} from public.{table} where user_id in ({QA_SQL}) order by id limit {LIMIT + 1}) t)" for table in TABLES)
COUNT_SQL = ",".join(f"'{table}',(select count(*) from public.{table} where user_id in ({QA_SQL}))" for table in TABLES)
SNAPSHOT_SQL = f"with base as ({ACCOUNT_SQL}) select snapshot || jsonb_build_object(" \
    f"'day',(now() at time zone 'Europe/Rome')::date,'records',jsonb_build_object({RECORD_SQL})," \
    f"'counts',jsonb_build_object({COUNT_SQL})) as snapshot from base"


def decimal(value):
    try:
        need(isinstance(value, str), "DECIMAL_TEXT_REQUIRED")
        parsed = Decimal(value)
        need(parsed.is_finite(), "DECIMAL_NOT_FINITE")
        return parsed
    except (InvalidOperation, ValueError):
        raise EvidenceError("DECIMAL_INVALID") from None


def validate_snapshot(value):
    need(isinstance(value, dict) and {"day", "records", "counts"} <= set(value), "FINANCE_SNAPSHOT_INVALID")
    account = {key: copy.deepcopy(item) for key, item in value.items() if key not in ("day", "records", "counts")}
    # Account validation intentionally requires zero Couple/MFA data, but existing finance rows are retained.
    transaction_count = account.get("transactions")
    need(type(transaction_count) is int and transaction_count >= 0, "TRANSACTION_COUNT_INVALID")
    account["transactions"] = 0
    account_snapshot(account, allow_user_a=True)
    try:
        need(dt.date.fromisoformat(value["day"]).isoformat() == value["day"], "DAY_INVALID")
    except (ValueError, TypeError):
        raise EvidenceError("DAY_INVALID") from None
    need(isinstance(value["records"], dict) and set(value["records"]) == set(TABLES) and
         isinstance(value["counts"], dict) and set(value["counts"]) == set(TABLES), "FINANCE_TABLES_INVALID")
    now = instant(value["now"])
    identifiers = set()
    for table in TABLES:
        rows, count = value["records"][table], value["counts"][table]
        need(isinstance(rows, list) and type(count) is int and 0 <= count <= LIMIT and len(rows) == count,
             "FINANCE_ROWS_TRUNCATED")
        for row in rows:
            need(isinstance(row, dict) and set(row) == FIELDS[table], "FINANCE_ROW_FIELDS_INVALID")
            identifier = canonical(row["id"])
            need((table, identifier) not in identifiers and row["user_id"] in QA, "FINANCE_ROW_OWNER_INVALID")
            identifiers.add((table, identifier))
            created = instant(row["created_at"])
            need(created <= now, "FINANCE_TIMESTAMP_INVALID")
            if "updated_at" in row:
                need(created <= instant(row["updated_at"]) <= now, "FINANCE_TIMESTAMP_INVALID")
            if "deleted_at" in row and row["deleted_at"] is not None:
                need(created <= instant(row["deleted_at"]) <= now, "FINANCE_TIMESTAMP_INVALID")
            if "category_id" in row and row["category_id"] is not None:
                canonical(row["category_id"])
            if "amount" in row:
                decimal(row["amount"])
            if table == "transactions":
                decimal(row["exchange_rate_eur"])
                try:
                    need(dt.date.fromisoformat(row["date"]).isoformat() == row["date"], "TRANSACTION_DATE_INVALID")
                except (ValueError, TypeError):
                    raise EvidenceError("TRANSACTION_DATE_INVALID") from None
                need(row["type"] in ("income", "expense"), "TRANSACTION_TYPE_INVALID")
            if table == "categories":
                need(row["type"] in ("income", "expense") and isinstance(row["name"], str) and
                     (row["icon"] is None or isinstance(row["icon"], str)) and
                     (row["color"] is None or isinstance(row["color"], str)), "CATEGORY_FIELDS_INVALID")
            if table == "budgets":
                need(row["category_id"] is not None and type(row["month"]) is int and 1 <= row["month"] <= 12 and
                     type(row["year"]) is int, "BUDGET_FIELDS_INVALID")
            if "currency" in row:
                need(row["currency"] is None or isinstance(row["currency"], str), "CURRENCY_INVALID")
    need(transaction_count == value["counts"]["transactions"], "TRANSACTION_COUNT_MISMATCH")
    return copy.deepcopy(value)


def _same_accounts(base, value):
    need(value["day"] == base["day"] and instant(value["now"]) >= instant(base["now"]), "RUN_CLOCK_CHANGED")
    need(all(value[key] == base[key] for key in ("users", "profiles", "qa_profile_rows", "registrations")),
         "QA_ACCOUNT_BASELINE_CHANGED")


def _session(value, origin=None, *, absent=False):
    rows = value["active_session_rows"]
    if absent:
        need(rows == [] and value["sessions"] == 0, "FINAL_AUTH_REMAINS")
        return None
    need(len(rows) == 1 and rows[0]["user_id"] == A and rows[0]["aal"] == "aal1", "NATIVE_SESSION_OWNER_INVALID")
    row = rows[0]
    if origin is not None:
        need(all(row[key] == origin[key] for key in ("id", "user_id", "aal", "created_at", "not_after")) and
             instant(row["updated_at"]) >= instant(origin["updated_at"]), "NATIVE_SESSION_BINDING_CHANGED")
    return copy.deepcopy(row)


def _records(base, registry):
    result = copy.deepcopy(base["records"])
    for entry in registry.values():
        if not entry["removed"]:
            result[entry["table"]].append(copy.deepcopy(entry["row"]))
    return {table: sorted(rows, key=lambda row: row["id"]) for table, rows in result.items()}


def _unchanged_records(base, value, registry):
    need({table: sorted(rows, key=lambda row: row["id"]) for table, rows in value["records"].items()} == _records(base, registry),
         "BASELINE_OR_UNRESERVED_RECORD_CHANGED")


def _payload(row, expected):
    need(all(key in row for key in expected), "OWNED_PAYLOAD_INCOMPLETE")
    for key, wanted in expected.items():
        if key in ("amount", "exchange_rate_eur"):
            need(decimal(row[key]) == Decimal(wanted), "OWNED_PAYLOAD_CHANGED")
        else:
            need(row[key] == wanted, "OWNED_PAYLOAD_CHANGED")


def _detach_prefix(before, after, registry):
    """Only the first confirmed write of expense category removal, never an arbitrary partial payload."""
    result = copy.deepcopy(registry)
    category, tx = result["expense-category"], result["expense"]
    need(not category["removed"] and category["row"]["deleted_at"] is None and not tx["removed"] and
         tx["row"]["category_id"] == category["row"]["id"], "PARTIAL_DETACH_PREIMAGE_INVALID")
    row = next((item for item in after["records"]["transactions"] if item["id"] == tx["row"]["id"]), None)
    need(row is not None, "PARTIAL_DETACH_ROW_MISSING")
    _payload(row, {**tx["row"], "category_id": None, "updated_at": row["updated_at"]})
    need(instant(before["now"]) <= instant(row["updated_at"]) <= instant(after["now"]),
         "PARTIAL_DETACH_WINDOW_INVALID")
    tx["row"] = copy.deepcopy(row)
    return result


def _apply(op, before, after, registry, marker):
    """Accept one fixed transition, including category deletion's one owned TX detach."""
    next_registry = copy.deepcopy(registry)
    start, end = instant(before["now"]), instant(after["now"])
    need(start <= end, "DATABASE_CLOCK_REVERSED")

    def category(kind):
        return next_registry[kind + "-category"]["row"]["id"]

    creates = {
        "expense-category-create": ("expense-category", "categories", {"user_id": A, "name": marker + "-expense",
            "type": "expense", "icon": "📦", "color": "#6b7280", "deleted_at": None}),
        "income-category-create": ("income-category", "categories", {"user_id": A, "name": marker + "-income",
            "type": "income", "icon": "📦", "color": "#6b7280", "deleted_at": None}),
    }
    if op in ("expense-create", "income-create"):
        kind = op.split("-")[0]
        creates[op] = (kind, "transactions", {"user_id": A, "type": kind,
            "description": marker + "-" + kind, "amount": "0.01" if kind == "expense" else "0.05",
            "currency": "EUR", "exchange_rate_eur": "1", "category_id": category(kind),
            "date": before["day"], "deleted_at": None})
    if op == "budget-create":
        creates[op] = ("budget", "budgets", {"user_id": A, "category_id": category("expense"),
            "amount": "3", "currency": "EUR", "month": 1, "year": 2000})
    if op in creates:
        key, table, expected = creates[op]
        prior_ids = {row["id"] for row in before["records"][table]}
        added = [row for row in after["records"][table] if row["id"] not in prior_ids]
        need(key not in next_registry and len(added) == 1, "OWNED_CREATION_NOT_UNIQUE")
        row = added[0]
        _payload(row, expected)
        need(start <= instant(row["created_at"]) <= end, "OWNED_CREATION_WINDOW_INVALID")
        need(all(row["id"] != entry["row"]["id"] for entry in next_registry.values()), "OWNED_UUID_REUSED")
        if "updated_at" in row:
            need(start <= instant(row["updated_at"]) <= end, "OWNED_UPDATE_WINDOW_INVALID")
        next_registry[key] = {"table": table, "row": copy.deepcopy(row), "removed": False}
    else:
        key = ("expense-category" if op.startswith("expense-category") or op == "cleanup-expense-category" else
               "income-category" if op.startswith("income-category") or op == "cleanup-income-category" else
               "expense" if op.startswith("expense-") else "income" if op.startswith("income-") else "budget")
        need(key in next_registry and not next_registry[key]["removed"], "OWNED_RECORD_NOT_AVAILABLE")
        entry = next_registry[key]
        if op.endswith("-delete") and "soft-delete" not in op or op.startswith("cleanup-"):
            if op.startswith("cleanup-"):
                need(entry["table"] == "categories" and entry["row"]["deleted_at"] is not None,
                     "CLEANUP_NOT_SOFT_DELETED")
            entry["removed"] = True
        else:
            row = next((row for row in after["records"][entry["table"]] if row["id"] == entry["row"]["id"]), None)
            need(row is not None, "OWNED_RECORD_MISSING")
            desired = copy.deepcopy(entry["row"])
            if op in ("expense-update", "income-update", "budget-update"):
                desired["amount"] = {"expense-update": "0.02", "income-update": "0.06", "budget-update": "4"}[op]
                desired["updated_at"] = row["updated_at"]
                need(start <= instant(row["updated_at"]) <= end, "OWNED_UPDATE_WINDOW_INVALID")
            elif op == "expense-category-rename":
                desired["name"] = marker + "-expense-renamed"
            elif op.endswith("-soft-delete"):
                desired["deleted_at"] = row["deleted_at"]
                need(row["deleted_at"] is not None and start <= instant(row["deleted_at"]) <= end,
                     "OWNED_DELETE_WINDOW_INVALID")
            else:
                raise EvidenceError("OPERATION_INVALID")
            _payload(row, desired)
            entry["row"] = copy.deepcopy(row)
            if op == "expense-category-soft-delete":
                tx = next_registry["expense"]
                detached = next((row for row in after["records"]["transactions"] if row["id"] == tx["row"]["id"]), None)
                need(detached is not None and not tx["removed"], "OWNED_DETACH_MISSING")
                expected_tx = {**tx["row"], "category_id": None, "updated_at": detached["updated_at"]}
                _payload(detached, expected_tx)
                need(start <= instant(detached["updated_at"]) <= end, "OWNED_DETACH_WINDOW_INVALID")
                tx["row"] = copy.deepcopy(detached)
    return next_registry


class FinanceFormEvidence:
    def __init__(self, run, executor, root=ROOT):
        self.run = canonical(run)
        self.root = Path(root).resolve()
        self.directory = self.root / self.run / "evidence"
        self.execute = executor
        self.marker = "android-finance-form-" + self.run

    def path(self, name):
        allowed = {"preflight-reservation", "preflight-observed", "preflight-failed",
                   "cleanup-check-reservation", "cleanup-check-observed", "cleanup-check-failed"} | {
            op + suffix for op in OPS for suffix in ("-reservation", "-observed", "-failed",
                "-recovery-reservation", "-recovery-observed", "-recovery-failed")} | {
            "checkpoint-" + stage + suffix for stage in CHECKPOINTS for suffix in ("-reservation", "-observed", "-failed")}
        need(isinstance(name, str) and name in allowed,
             "JOURNAL_NAME_INVALID")
        need(self.directory.resolve().is_relative_to(self.root / self.run), "JOURNAL_PATH_ESCAPED")
        return self.directory / (name + ".json")

    def save(self, name, payload):
        exclusive(self.path(name), {"version": 1, "run_id": self.run, "project": PROJECT, "qa_run": QA_RUN,
                                   "marker": self.marker, **payload})

    def read(self, name):
        try:
            with self.path(name).open("rb") as stream:
                raw = stream.read(2_097_153)
            need(len(raw) <= 2_097_152, "JOURNAL_TOO_LARGE")
            value = json.loads(raw)
            need(isinstance(value, dict) and type(value.get("version")) is int and all(value.get(key) == expected
                for key, expected in {"version": 1, "run_id": self.run, "project": PROJECT,
                                     "qa_run": QA_RUN, "marker": self.marker}.items()), "JOURNAL_SCOPE_INVALID")
            return value
        except EvidenceError:
            raise
        except Exception:
            raise EvidenceError("JOURNAL_INVALID_OR_MISSING") from None

    def snapshot(self):
        try:
            result = self.execute(SNAPSHOT_SQL, read_only=True)
        except Exception:
            raise EvidenceError("READ_OUTCOME_UNCERTAIN") from None
        need(isinstance(result, list) and len(result) == 1 and isinstance(result[0], dict) and
             set(result[0]) == {"snapshot"}, "SNAPSHOT_RESPONSE_INVALID")
        return validate_snapshot(result[0]["snapshot"])

    def preflight(self, day=None):
        need(self.root.is_dir(), "JOURNAL_ROOT_MISSING")
        self.path("preflight-reservation")
        self.directory.mkdir(parents=True, exist_ok=True)
        self.save("preflight-reservation", {"status": "reserved"})
        try:
            value = self.snapshot()
            need(day is None or day == value["day"], "RUN_DAY_MISMATCH")
            need(value["sessions"] == 0, "PREFLIGHT_AUTH_NOT_EMPTY")
            need(all(not str(row.get("name") or row.get("description") or "").startswith(self.marker)
                for rows in value["records"].values() for row in rows), "PREFLIGHT_MARKER_ALREADY_EXISTS")
            self.save("preflight-observed", {"status": "observed", "snapshot": value})
            return value
        except Exception as error:
            self.save("preflight-failed", {"status": "failed", "failure_code":
                str(error) if isinstance(error, EvidenceError) else "READ_OUTCOME_UNCERTAIN"})
            raise EvidenceError(str(error) if isinstance(error, EvidenceError) else "READ_OUTCOME_UNCERTAIN") from None

    def _history(self, pending=None, recovery=False):
        need(self.path("preflight-observed").exists() and not self.path("preflight-failed").exists(), "PREFLIGHT_NOT_COMPLETE")
        need(self.read("preflight-reservation").get("status") == "reserved", "JOURNAL_RESERVATION_INVALID")
        preflight = self.read("preflight-observed")
        need(preflight.get("status") == "observed", "JOURNAL_PROOF_INVALID")
        base = validate_snapshot(preflight["snapshot"])
        _session(base, absent=True)
        registry, completed, previous, binding = {}, [], base, None
        ready_name = "checkpoint-ready-observed"
        if self.path(ready_name).exists():
            ready = self.read(ready_name)
            need(self.read("checkpoint-ready-reservation").get("status") == "reserved" and
                 ready.get("status") == "observed", "JOURNAL_PROOF_INVALID")
            previous = validate_snapshot(ready["snapshot"])
            _same_accounts(base, previous)
            _unchanged_records(base, previous, registry)
            binding = _session(previous)
            need(instant(binding["created_at"]) >= instant(base["now"]), "SESSION_PREDATES_PREFLIGHT")
        registries = [copy.deepcopy(registry)]
        gap = False
        for op in OPS:
            reserved, observed = self.path(op + "-reservation").exists(), self.path(op + "-observed").exists()
            recovered = self.path(op + "-recovery-observed").exists()
            recovering = self.path(op + "-recovery-reservation").exists()
            need(not self.path(op + "-recovery-failed").exists() and
                 (not recovering or recovered or op == pending and recovery), "RECOVERY_UNCERTAIN_NO_REPLAY")
            need(not self.path(op + "-failed").exists() or recovered or op == pending and recovery,
                 "OPERATION_UNCERTAIN_NO_REPLAY")
            need(not (observed and recovered), "JOURNAL_PROOF_INVALID")
            if reserved and not observed and not recovered:
                need(op == pending and not gap and op == OPS[len(completed)], "OPERATION_UNCERTAIN_NO_REPLAY")
                gap = True
                continue
            if not observed and not recovered:
                gap = True
                continue
            need(reserved and not gap and binding is not None, "OPERATION_ORDER_INVALID")
            reservation = self.read(op + "-reservation")
            proof = self.read(op + ("-recovery-observed" if recovered else "-observed"))
            need(reservation.get("status") == "reserved" and reservation.get("op") == op and
                 reservation.get("registry") == registry and proof.get("status") in
                 (("applied", "cancelled", "partial") if recovered else ("observed",)) and proof.get("op") == op,
                 "JOURNAL_PROOF_INVALID")
            if recovered:
                recovery_proof = self.read(op + "-recovery-reservation")
                need(recovery_proof.get("status") == "reserved" and recovery_proof.get("op") == op and
                     recovery_proof.get("original_reservation") == reservation, "JOURNAL_PROOF_INVALID")
            before, after = validate_snapshot(reservation["snapshot"]), validate_snapshot(proof["snapshot"])
            _same_accounts(base, before)
            _same_accounts(base, after)
            need(instant(before["now"]) >= instant(previous["now"]), "DATABASE_CLOCK_REVERSED")
            _session(before, binding, absent=op.startswith("cleanup-"))
            _session(after, binding, absent=op.startswith("cleanup-") or recovered and after["sessions"] == 0)
            _unchanged_records(base, before, registry)
            if proof.get("status") == "cancelled":
                need(instant(after["now"]) >= instant(before["now"]), "DATABASE_CLOCK_REVERSED")
                _unchanged_records(base, after, registry)
            elif proof.get("status") == "partial":
                need(op == "expense-category-soft-delete", "PARTIAL_OPERATION_INVALID")
                registry = _detach_prefix(before, after, registry)
            else:
                registry = _apply(op, before, after, registry, self.marker)
            _unchanged_records(base, after, registry)
            need(proof.get("registry") == registry, "JOURNAL_REGISTRY_CHANGED")
            previous = after
            completed.append(op)
            registries.append(copy.deepcopy(registry))
        for stage in CHECKPOINTS:
            reserved = self.path("checkpoint-" + stage + "-reservation").exists()
            observed = self.path("checkpoint-" + stage + "-observed").exists()
            need(not self.path("checkpoint-" + stage + "-failed").exists() and reserved == observed,
                 "CHECKPOINT_UNCERTAIN_NO_REPLAY")
            if not observed:
                continue
            reservation = self.read("checkpoint-" + stage + "-reservation")
            proof = self.read("checkpoint-" + stage + "-observed")
            need(reservation.get("status") == "reserved" and reservation.get("stage") == stage and
                 proof.get("status") == "observed" and proof.get("stage") == stage and
                 type(proof.get("after_ops")) is int and 0 <= proof["after_ops"] <= len(completed) and
                 reservation.get("after_ops") == proof["after_ops"], "JOURNAL_PROOF_INVALID")
            value = validate_snapshot(proof["snapshot"])
            _same_accounts(base, value)
            _unchanged_records(base, value, registries[proof["after_ops"]])
            _session(value, binding, absent=stage in ("logged-out", "completed"))
            if stage == "ready":
                need(proof["after_ops"] == 0, "JOURNAL_PROOF_INVALID")
            if stage == "logged-out":
                need(proof["after_ops"] == 14, "NATIVE_OPERATIONS_NOT_COMPLETE")
            if stage == "completed":
                need(proof["after_ops"] == len(OPS), "OPERATIONS_NOT_COMPLETE")
        return base, previous, registry, completed, binding

    def history(self):
        return self._history()[:4]

    def reserve(self, op):
        need(op in OPS, "OPERATION_INVALID")
        need(not any(self.path(item + "-recovery-reservation").exists() for item in OPS), "RECOVERY_TERMINAL_NO_NEW_WRITES")
        base, previous, registry, done, binding = self._history()
        need(binding is not None and len(done) < len(OPS) and op == OPS[len(done)], "OPERATION_ORDER_INVALID")
        before = self.snapshot()
        _same_accounts(base, before)
        need(instant(before["now"]) >= instant(previous["now"]), "DATABASE_CLOCK_REVERSED")
        _session(before, binding, absent=op.startswith("cleanup-"))
        _unchanged_records(base, before, registry)
        self.save(op + "-reservation", {"status": "reserved", "op": op, "snapshot": before, "registry": registry})
        return copy.deepcopy(registry)

    def observe(self, op):
        need(op in OPS, "OPERATION_INVALID")
        base, _, registry, done, binding = self._history(pending=op)
        need(len(done) < len(OPS) and op == OPS[len(done)] and self.path(op + "-reservation").exists() and
             not self.path(op + "-observed").exists(), "OPERATION_NOT_RESERVED")
        reservation = self.read(op + "-reservation")
        need(reservation.get("status") == "reserved" and reservation.get("op") == op and
             reservation.get("registry") == registry, "JOURNAL_RESERVATION_INVALID")
        before = validate_snapshot(reservation["snapshot"])
        _same_accounts(base, before)
        _unchanged_records(base, before, registry)
        _session(before, binding, absent=op.startswith("cleanup-"))
        try:
            after = self.snapshot()
            _same_accounts(base, after)
            _session(after, binding, absent=op.startswith("cleanup-"))
            adopted = _apply(op, before, after, registry, self.marker)
            _unchanged_records(base, after, adopted)
            self.save(op + "-observed", {"status": "observed", "op": op, "snapshot": after, "registry": adopted})
            return copy.deepcopy(adopted)
        except Exception as error:
            self.save(op + "-failed", {"status": "failed", "failure_code":
                str(error) if isinstance(error, EvidenceError) else "READ_OUTCOME_UNCERTAIN"})
            raise EvidenceError(str(error) if isinstance(error, EvidenceError) else "READ_OUTCOME_UNCERTAIN") from None

    def recovery_read(self, op):
        need(op in OPS, "OPERATION_INVALID")
        base, _, registry, done, binding = self._history(pending=op, recovery=True)
        need(len(done) < len(OPS) and op == OPS[len(done)] and self.path(op + "-reservation").exists() and
             not self.path(op + "-observed").exists() and not self.path(op + "-recovery-observed").exists(),
             "RECOVERY_OPERATION_NOT_PENDING")
        original = self.read(op + "-reservation")
        need(original.get("status") == "reserved" and original.get("op") == op and original.get("registry") == registry,
             "JOURNAL_RESERVATION_INVALID")
        before = validate_snapshot(original["snapshot"])
        _same_accounts(base, before)
        _unchanged_records(base, before, registry)
        _session(before, binding, absent=op.startswith("cleanup-"))
        self.save(op + "-recovery-reservation", {"status": "reserved", "op": op, "original_reservation": original})
        try:
            after = self.snapshot()
            _same_accounts(base, after)
            _session(after, binding, absent=after["sessions"] == 0)
            need(instant(after["now"]) >= instant(before["now"]), "DATABASE_CLOCK_REVERSED")
            try:
                adopted = _apply(op, before, after, registry, self.marker)
                _unchanged_records(base, after, adopted)
                status = "applied"
            except EvidenceError:
                try:
                    _unchanged_records(base, after, registry)
                    adopted, status = copy.deepcopy(registry), "cancelled"
                except EvidenceError:
                    need(op == "expense-category-soft-delete", "BASELINE_OR_UNRESERVED_RECORD_CHANGED")
                    adopted = _detach_prefix(before, after, registry)
                    _unchanged_records(base, after, adopted)
                    status = "partial"
            self.save(op + "-recovery-observed", {"status": status, "op": op, "snapshot": after, "registry": adopted})
            return {"status": status, "registry": copy.deepcopy(adopted)}
        except Exception as error:
            self.save(op + "-recovery-failed", {"status": "failed", "failure_code":
                str(error) if isinstance(error, EvidenceError) else "READ_OUTCOME_UNCERTAIN"})
            raise EvidenceError(str(error) if isinstance(error, EvidenceError) else "READ_OUTCOME_UNCERTAIN") from None

    def checkpoint(self, stage):
        need(stage in CHECKPOINTS, "CHECKPOINT_INVALID")
        base, previous, registry, done, binding = self._history()
        need(stage != "ready" or binding is None and done == [], "READY_ALREADY_BOUND")
        need(stage == "ready" or binding is not None, "NATIVE_SESSION_NOT_BOUND")
        if stage == "completed":
            need(done == list(OPS), "OPERATIONS_NOT_COMPLETE")
        if stage == "logged-out":
            need(done == list(OPS[:14]), "NATIVE_OPERATIONS_NOT_COMPLETE")
        self.save("checkpoint-" + stage + "-reservation", {"status": "reserved", "stage": stage, "after_ops": len(done)})
        try:
            value = self.snapshot()
            _same_accounts(base, value)
            need(instant(value["now"]) >= instant(previous["now"]), "DATABASE_CLOCK_REVERSED")
            _unchanged_records(base, value, registry)
            current = _session(value, binding, absent=stage in ("logged-out", "completed"))
            if stage == "ready":
                need(instant(current["created_at"]) >= instant(base["now"]), "SESSION_PREDATES_PREFLIGHT")
            self.save("checkpoint-" + stage + "-observed", {"status": "observed", "stage": stage, "after_ops": len(done), "snapshot": value})
            return value
        except Exception as error:
            self.save("checkpoint-" + stage + "-failed", {"status": "failed", "failure_code":
                str(error) if isinstance(error, EvidenceError) else "READ_OUTCOME_UNCERTAIN"})
            raise EvidenceError(str(error) if isinstance(error, EvidenceError) else "READ_OUTCOME_UNCERTAIN") from None

    def cleanup_rows(self):
        base, _, registry, done, _ = self._history()
        need(done[:14] == list(OPS[:14]), "NATIVE_OPERATIONS_NOT_COMPLETE")
        rows = []
        for key in ("expense-category", "income-category"):
            entry = registry[key]
            if entry["removed"]:
                continue
            need(entry["table"] == "categories" and entry["row"]["user_id"] == A and entry["row"]["deleted_at"] is not None and
                 all(row["id"] != entry["row"]["id"] for row in base["records"]["categories"]), "CLEANUP_BASELINE_FORBIDDEN")
            rows.append(copy.deepcopy(entry["row"]))
        return rows

    def cleanup_candidates(self):
        base, previous, registry, _, binding = self._history()
        current = self.snapshot()
        _same_accounts(base, current)
        need(instant(current["now"]) >= instant(previous["now"]), "DATABASE_CLOCK_REVERSED")
        _session(current, binding, absent=current["sessions"] == 0)
        _unchanged_records(base, current, registry)
        result = []
        for key, entry in registry.items():
            if entry["removed"]:
                continue
            row, table = entry["row"], entry["table"]
            need(row["user_id"] == A and all(baseline["id"] != row["id"] for baseline in base["records"][table]),
                 "CLEANUP_BASELINE_FORBIDDEN")
            result.append({"key": key, "table": table, "row": copy.deepcopy(row)})
        return result

    def verify_restored(self):
        # Cleanup evidence never upgrades an interrupted native form to a passed form qualification.
        base, _, _, _, _ = self._history()
        self.save("cleanup-check-reservation", {"status": "reserved"})
        try:
            value = self.snapshot()
            _same_accounts(base, value)
            _session(value, absent=True)
            _unchanged_records(base, value, {})
            self.save("cleanup-check-observed", {"status": "restored", "snapshot": value})
            return value
        except Exception as error:
            self.save("cleanup-check-failed", {"status": "failed", "failure_code":
                str(error) if isinstance(error, EvidenceError) else "READ_OUTCOME_UNCERTAIN"})
            raise EvidenceError(str(error) if isinstance(error, EvidenceError) else "READ_OUTCOME_UNCERTAIN") from None
