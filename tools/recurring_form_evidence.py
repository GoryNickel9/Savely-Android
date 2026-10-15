"""V04 fixed-run read-only evidence. Never logs in, processes, updates or cleans backend rows."""
import copy
import datetime as dt
import json
from pathlib import Path

import finance_form_evidence as finance
from couple_invitation_evidence import A, B, QA, QA_SQL, QA_RUN, PROJECT, EvidenceError, need, canonical, instant, exclusive

ROOT = Path(__file__).resolve().parent.parent / "artifacts/recurring-form-ui"
LIMIT = 500
TABLES = (*finance.TABLES, "recurring_expenses")
OPS = ("create-paused", "edit-amount", "resume", "process-create", "reset-due", "process-dedup",
       "pause", "cleanup-transaction", "cleanup-recurring")
# Each checkpoint is a one-shot observation at one of these operation boundaries.
CHECKPOINTS = {
    "ready": (0,), "paused-process": (2,), "calendar": (3,),
    "calendar-after-process": (4,), "calendar-after-dedup": (6,), "empty-process": (6,),
    "calendar-paused": (7,), "delete-cancel": (7,), "logged-out": (7,), "completed": (9,),
}
RECURRING_FIELDS = {"id", "user_id", "category_id", "name", "amount", "currency", "frequency", "week_interval",
                    "next_due_date", "is_active", "created_at", "updated_at", "deleted_at"}
SELECTS = {**finance.SELECTS, "recurring_expenses":
    "id,user_id,category_id,name,amount::text as amount,currency,frequency,week_interval,next_due_date,is_active,created_at,updated_at,deleted_at"}
RECORD_SQL = ",".join(f"'{table}',(select coalesce(jsonb_agg(to_jsonb(t) order by id),'[]'::jsonb) from "
    f"(select {SELECTS[table]} from public.{table} where user_id in ({QA_SQL}) order by id limit {LIMIT + 1}) t)" for table in TABLES)
COUNT_SQL = ",".join(f"'{table}',(select count(*) from public.{table} where user_id in ({QA_SQL}))" for table in TABLES)
SNAPSHOT_SQL = f"with base as ({finance.ACCOUNT_SQL}) select snapshot || jsonb_build_object(" \
    f"'day',(now() at time zone 'Europe/Rome')::date,'records',jsonb_build_object({RECORD_SQL})," \
    f"'counts',jsonb_build_object({COUNT_SQL})) as snapshot from base"


def _date(value):
    try:
        need(isinstance(value, str) and dt.date.fromisoformat(value).isoformat() == value, "RECURRING_DATE_INVALID")
        return dt.date.fromisoformat(value)
    except (ValueError, TypeError):
        raise EvidenceError("RECURRING_DATE_INVALID") from None


def validate_snapshot(value):
    need(isinstance(value, dict) and isinstance(value.get("records"), dict) and
         isinstance(value.get("counts"), dict) and set(value["records"]) == set(TABLES) and
         set(value["counts"]) == set(TABLES), "RECURRING_TABLES_INVALID")
    inherited = copy.deepcopy(value)
    inherited["records"].pop("recurring_expenses")
    inherited["counts"].pop("recurring_expenses")
    finance.validate_snapshot(inherited)
    rows, count = value["records"]["recurring_expenses"], value["counts"]["recurring_expenses"]
    need(isinstance(rows, list) and type(count) is int and 0 <= count <= LIMIT and len(rows) == count,
         "RECURRING_ROWS_TRUNCATED")
    ids, now = set(), instant(value["now"])
    for row in rows:
        need(isinstance(row, dict) and set(row) == RECURRING_FIELDS, "RECURRING_ROW_FIELDS_INVALID")
        identifier = canonical(row["id"])
        need(identifier not in ids and row["user_id"] in QA, "RECURRING_ROW_OWNER_INVALID")
        ids.add(identifier)
        if row["category_id"] is not None:
            canonical(row["category_id"])
        created, updated = instant(row["created_at"]), instant(row["updated_at"])
        need(created <= updated <= now, "RECURRING_TIMESTAMP_INVALID")
        if row["deleted_at"] is not None:
            need(created <= instant(row["deleted_at"]) <= now, "RECURRING_TIMESTAMP_INVALID")
        finance.decimal(row["amount"])
        need(isinstance(row["name"], str) and type(row["is_active"]) is bool and
             row["frequency"] in ("weekly", "monthly", "quarterly", "yearly") and
             (row["currency"] is None or isinstance(row["currency"], str)), "RECURRING_FIELDS_INVALID")
        interval = row["week_interval"]
        need((interval is None and row["frequency"] != "weekly") or
             type(interval) is int and 1 <= interval <= 520, "RECURRING_INTERVAL_INVALID")
        _date(row["next_due_date"])
    return copy.deepcopy(value)


def _baseline(value, marker):
    finance._session(value, absent=True)
    need(all(not str(row.get("name") or row.get("description") or "").startswith(marker)
             for rows in value["records"].values() for row in rows), "PREFLIGHT_MARKER_ALREADY_EXISTS")
    need(not any(row["user_id"] == A and row["is_active"] and row["deleted_at"] is None and
                 row["next_due_date"] <= value["day"] for row in value["records"]["recurring_expenses"]),
         "PREFLIGHT_EXISTING_DUE_RECURRING")


def _apply(op, before, after, registry, marker):
    result = copy.deepcopy(registry)
    start, end = instant(before["now"]), instant(after["now"])
    need(start <= end, "DATABASE_CLOCK_REVERSED")
    due = before["day"]
    next_due = (_date(due) + dt.timedelta(days=7)).isoformat()

    def create(key, table, expected):
        prior = {row["id"] for row in before["records"][table]}
        added = [row for row in after["records"][table] if row["id"] not in prior]
        need(key not in result and len(added) == 1, "OWNED_CREATION_NOT_UNIQUE")
        row = added[0]
        finance._payload(row, expected)
        need(start <= instant(row["created_at"]) <= instant(row["updated_at"]) <= end,
             "OWNED_CREATION_WINDOW_INVALID")
        need(all(row["id"] != entry["row"]["id"] for entry in result.values()), "OWNED_UUID_REUSED")
        result[key] = {"table": table, "row": copy.deepcopy(row), "removed": False}

    if op == "create-paused":
        create("recurring", "recurring_expenses", {"user_id": A, "category_id": None, "name": marker,
            "amount": "0.01", "currency": "EUR", "frequency": "weekly", "week_interval": 1,
            "next_due_date": due, "is_active": False, "deleted_at": None})
        return result
    need("recurring" in result and not result["recurring"]["removed"], "OWNED_RECORD_NOT_AVAILABLE")
    if op == "process-create":
        create("transaction", "transactions", {"user_id": A, "category_id": None, "description": marker,
            "type": "expense", "amount": "0.02", "currency": "EUR", "exchange_rate_eur": "1",
            "date": due, "deleted_at": None})
    if op.startswith("cleanup-"):
        key = "transaction" if op == "cleanup-transaction" else "recurring"
        need(key in result and not result[key]["removed"], "OWNED_RECORD_NOT_AVAILABLE")
        if key == "recurring":
            # Owner SELECT hides soft-deleted rows, so those are never eligible for live REST CAS cleanup.
            need(result[key]["row"]["deleted_at"] is None and result[key]["row"]["is_active"] is False and
                 result["transaction"]["removed"],
                 "CLEANUP_PREIMAGE_INVALID")
        result[key]["removed"] = True
        return result
    updates = {"edit-amount": {"amount": "0.02"}, "resume": {"is_active": True},
        "process-create": {"next_due_date": next_due}, "reset-due": {"next_due_date": due},
        "process-dedup": {"next_due_date": next_due}, "pause": {"is_active": False}}
    old = result["recurring"]["row"]
    row = next((row for row in after["records"]["recurring_expenses"] if row["id"] == old["id"]), None)
    need(row is not None, "OWNED_RECORD_MISSING")
    desired = {**old, "updated_at": row["updated_at"]}
    need(start <= instant(row["updated_at"]) <= end, "OWNED_UPDATE_WINDOW_INVALID")
    need(op in updates, "OPERATION_INVALID")
    desired.update(updates[op])
    finance._payload(row, desired)
    result["recurring"]["row"] = copy.deepcopy(row)
    return result


class RecurringFormEvidence:
    def __init__(self, run, executor, root=ROOT):
        self.run = canonical(run)
        self.root = Path(root).resolve()
        self.directory = self.root / self.run / "evidence"
        self.execute = executor
        self.marker = "android-recurring-form-" + self.run

    def path(self, name):
        prefixes = ("preflight", "cleanup-check", *OPS, *("checkpoint-" + stage for stage in CHECKPOINTS))
        need(isinstance(name, str) and name in {prefix + suffix for prefix in prefixes for suffix in ("-reservation", "-observed", "-failed")},
             "JOURNAL_NAME_INVALID")
        need(self.directory.resolve().is_relative_to(self.root / self.run) and
             (self.root / self.run).resolve().is_relative_to(self.root), "JOURNAL_PATH_ESCAPED")
        return self.directory / (name + ".json")

    def save(self, name, payload):
        exclusive(self.path(name), {"version": 1, "run_id": self.run, "project": PROJECT,
            "qa_run": QA_RUN, "marker": self.marker, **payload})

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

    def _failed(self, prefix, error):
        code = str(error) if isinstance(error, EvidenceError) else "READ_OUTCOME_UNCERTAIN"
        self.save(prefix + "-failed", {"status": "failed", "failure_code": code})
        raise EvidenceError(code) from None

    def preflight(self, day=None):
        need(self.root.is_dir(), "JOURNAL_ROOT_MISSING")
        self.path("preflight-reservation")
        self.directory.mkdir(parents=True, exist_ok=True)
        self.save("preflight-reservation", {"status": "reserved"})
        try:
            value = self.snapshot()
            need(day is None or day == value["day"], "RUN_DAY_MISMATCH")
            _baseline(value, self.marker)
            self.save("preflight-observed", {"status": "observed", "snapshot": value})
            return value
        except Exception as error:
            self._failed("preflight", error)

    def _history(self, pending=None):
        need(self.path("preflight-observed").exists() and not self.path("preflight-failed").exists(),
             "PREFLIGHT_NOT_COMPLETE")
        need(self.read("preflight-reservation").get("status") == "reserved", "JOURNAL_RESERVATION_INVALID")
        proof = self.read("preflight-observed")
        need(proof.get("status") == "observed", "JOURNAL_PROOF_INVALID")
        base = validate_snapshot(proof["snapshot"])
        _baseline(base, self.marker)
        events = []
        for prefix in (*OPS, *("checkpoint-" + stage for stage in CHECKPOINTS)):
            reserved, observed = self.path(prefix + "-reservation").exists(), self.path(prefix + "-observed").exists()
            need(not self.path(prefix + "-failed").exists(), "OPERATION_UNCERTAIN_NO_REPLAY")
            need(not observed or reserved, "JOURNAL_PROOF_INVALID")
            if not reserved:
                continue
            reservation = self.read(prefix + "-reservation")
            need(reservation.get("status") == "reserved" and type(reservation.get("ordinal")) is int and
                 reservation["ordinal"] >= 0, "JOURNAL_RESERVATION_INVALID")
            need(observed or prefix == pending, "OPERATION_UNCERTAIN_NO_REPLAY")
            events.append((reservation["ordinal"], prefix, reservation,
                           self.read(prefix + "-observed") if observed else None))
        registry, completed, previous, binding, checkpoints = {}, [], base, None, set()
        for ordinal, (index, prefix, reservation, proof) in enumerate(sorted(events, key=lambda event: event[0])):
            need(index == ordinal, "JOURNAL_ORDER_INVALID")
            need(proof is not None or ordinal == len(events) - 1, "OPERATION_UNCERTAIN_NO_REPLAY")
            need(reservation.get("registry") == registry and type(reservation.get("after_ops")) is int and
                 reservation["after_ops"] == len(completed),
                 "JOURNAL_REGISTRY_CHANGED")
            if prefix in OPS:
                need(binding is not None and len(completed) < len(OPS) and prefix == OPS[len(completed)] and
                     reservation.get("op") == prefix, "OPERATION_ORDER_INVALID")
                if prefix.startswith("cleanup-"):
                    need("logged-out" in checkpoints, "LOGOUT_NOT_OBSERVED")
                before = validate_snapshot(reservation["snapshot"])
                finance._same_accounts(base, before)
                need(instant(before["now"]) >= instant(previous["now"]), "DATABASE_CLOCK_REVERSED")
                finance._session(before, binding, absent=prefix.startswith("cleanup-"))
                finance._unchanged_records(base, before, registry)
                if proof is None:
                    continue
                need(proof.get("status") == "observed" and proof.get("op") == prefix, "JOURNAL_PROOF_INVALID")
                after = validate_snapshot(proof["snapshot"])
                finance._same_accounts(base, after)
                finance._session(after, binding, absent=prefix.startswith("cleanup-"))
                registry = _apply(prefix, before, after, registry, self.marker)
                finance._unchanged_records(base, after, registry)
                need(proof.get("registry") == registry, "JOURNAL_REGISTRY_CHANGED")
                completed.append(prefix)
            else:
                stage = prefix[len("checkpoint-"):]
                need(reservation.get("stage") == stage and len(completed) in CHECKPOINTS[stage], "CHECKPOINT_ORDER_INVALID")
                need(stage == "ready" or binding is not None, "NATIVE_SESSION_NOT_BOUND")
                if proof is None:
                    continue
                need(proof.get("status") == "observed" and proof.get("stage") == stage, "JOURNAL_PROOF_INVALID")
                after = validate_snapshot(proof["snapshot"])
                finance._same_accounts(base, after)
                finance._unchanged_records(base, after, registry)
                current = finance._session(after, binding, absent=stage in ("logged-out", "completed"))
                if stage == "ready":
                    need(binding is None and instant(current["created_at"]) >= instant(base["now"]), "SESSION_PREDATES_PREFLIGHT")
                    binding = current
                checkpoints.add(stage)
            need(instant(after["now"]) >= instant(previous["now"]), "DATABASE_CLOCK_REVERSED")
            previous = after
        return base, previous, registry, completed, binding, checkpoints, len(events)

    def history(self):
        """Return (baseline, latest observed snapshot, owned registry, completed ordered operations)."""
        return self._history()[:4]

    def reserve(self, op):
        need(op in OPS, "OPERATION_INVALID")
        base, previous, registry, done, binding, checkpoints, ordinal = self._history()
        need(binding is not None and len(done) < len(OPS) and op == OPS[len(done)], "OPERATION_ORDER_INVALID")
        if op.startswith("cleanup-"):
            need("logged-out" in checkpoints, "LOGOUT_NOT_OBSERVED")
        before = self.snapshot()
        finance._same_accounts(base, before)
        need(instant(before["now"]) >= instant(previous["now"]), "DATABASE_CLOCK_REVERSED")
        finance._session(before, binding, absent=op.startswith("cleanup-"))
        finance._unchanged_records(base, before, registry)
        self.save(op + "-reservation", {"status": "reserved", "op": op, "ordinal": ordinal,
            "after_ops": len(done), "snapshot": before, "registry": registry})
        return copy.deepcopy(registry)

    def observe(self, op):
        need(op in OPS, "OPERATION_INVALID")
        base, _, registry, done, binding, _, _ = self._history(pending=op)
        need(len(done) < len(OPS) and op == OPS[len(done)] and self.path(op + "-reservation").exists() and
             not self.path(op + "-observed").exists(), "OPERATION_NOT_RESERVED")
        before = validate_snapshot(self.read(op + "-reservation")["snapshot"])
        try:
            after = self.snapshot()
            finance._same_accounts(base, after)
            finance._session(after, binding, absent=op.startswith("cleanup-"))
            adopted = _apply(op, before, after, registry, self.marker)
            finance._unchanged_records(base, after, adopted)
            self.save(op + "-observed", {"status": "observed", "op": op, "snapshot": after, "registry": adopted})
            return copy.deepcopy(adopted)
        except Exception as error:
            self._failed(op, error)

    def checkpoint(self, stage):
        need(stage in CHECKPOINTS, "CHECKPOINT_INVALID")
        base, previous, registry, done, binding, _, ordinal = self._history()
        need(len(done) in CHECKPOINTS[stage], "CHECKPOINT_ORDER_INVALID")
        need(stage == "ready" and binding is None or stage != "ready" and binding is not None,
             "NATIVE_SESSION_NOT_BOUND")
        prefix = "checkpoint-" + stage
        self.save(prefix + "-reservation", {"status": "reserved", "stage": stage, "ordinal": ordinal,
            "after_ops": len(done), "registry": registry})
        try:
            value = self.snapshot()
            finance._same_accounts(base, value)
            need(instant(value["now"]) >= instant(previous["now"]), "DATABASE_CLOCK_REVERSED")
            finance._unchanged_records(base, value, registry)
            current = finance._session(value, binding, absent=stage in ("logged-out", "completed"))
            if stage == "ready":
                need(instant(current["created_at"]) >= instant(base["now"]), "SESSION_PREDATES_PREFLIGHT")
            self.save(prefix + "-observed", {"status": "observed", "stage": stage, "snapshot": value})
            return value
        except Exception as error:
            self._failed(prefix, error)

    def cleanup_candidates(self):
        """Exact adopted preimages only; unresolved reservations are never guessed or recovered here."""
        base, previous, registry, _, binding, _, _ = self._history()
        recurring = registry.get("recurring")
        if recurring is not None and not recurring["removed"]:
            need(recurring["row"]["deleted_at"] is None and recurring["row"]["is_active"] is False,
                 "CLEANUP_PREIMAGE_INVALID")
        current = self.snapshot()
        finance._same_accounts(base, current)
        need(instant(current["now"]) >= instant(previous["now"]), "DATABASE_CLOCK_REVERSED")
        need(binding is not None or current["sessions"] == 0, "NATIVE_SESSION_NOT_BOUND")
        finance._session(current, binding, absent=current["sessions"] == 0)
        finance._unchanged_records(base, current, registry)
        result = []
        # Dependent transactions precede the source recurring record for explicit host recovery.
        for key in ("transaction", "recurring"):
            entry = registry.get(key)
            if entry is None or entry["removed"]:
                continue
            row, table = entry["row"], entry["table"]
            need(row["user_id"] == A and all(baseline["id"] != row["id"] for baseline in base["records"][table]),
                 "CLEANUP_BASELINE_FORBIDDEN")
            result.append({"key": key, "table": table, "row": copy.deepcopy(row)})
        return result

    def verify_restored(self):
        """Cleanup proof only: an incomplete native sequence remains incomplete even if restored."""
        base, previous, _, done, _, _, _ = self._history()
        self.save("cleanup-check-reservation", {"status": "reserved"})
        try:
            value = self.snapshot()
            finance._same_accounts(base, value)
            need(instant(value["now"]) >= instant(previous["now"]), "DATABASE_CLOCK_REVERSED")
            finance._session(value, absent=True)
            finance._unchanged_records(base, value, {})
            self.save("cleanup-check-observed", {"status": "restored", "snapshot": value,
                "operations_complete": done == list(OPS), "completed_ops": done})
            return value
        except Exception as error:
            self._failed("cleanup-check", error)
