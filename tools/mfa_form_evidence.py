"""Opt-in A04 read-only ledger. No credentials, QR, login, mutations or cleanup."""
import copy
import json
from pathlib import Path
import re

from couple_invitation_evidence import (
    A, B, QA, QA_SQL, QA_USERS, QA_RUN, PROJECT, EvidenceError, need,
    canonical, instant, exclusive, SNAPSHOT_SQL as BASE_SQL,
)

ROOT = Path(__file__).resolve().parent.parent / "artifacts/mfa-form-ui"
STAGES = (
    "pending_cancel_ready", "pending_cancel_kept", "pending_persisted", "pending_removed",
    "enrollment_ready", "enrollment_wrong", "enrollment_verified", "logged_out",
    "login_gate_wrong", "login_gate_verified", "removal_cancelled", "removal_wrong",
    "removal_completed", "completed",
)
FACTOR_FIELDS = {"id", "user_id", "status", "factor_type", "friendly_name", "created_at", "updated_at"}
SESSION_FIELDS = {"id", "user_id", "aal", "created_at", "updated_at", "not_after"}
BASE_FIELDS = {"now", "users", "profiles", "sessions", "factors", "registrations",
    "transactions", "connections", "request_count", "requests", "audit_count", "audits"}
SNAPSHOT_FIELDS = BASE_FIELDS | {"mfa_factor_rows", "active_session_rows", "shared_expenses", "couple_budgets"}
# Fixed selected fields, three approved IDs only; never SELECT factor secret or session tokens.
# Column availability must be confirmed separately by the coordinator before the first live read.
SNAPSHOT_SQL = f"""with base as ({BASE_SQL}) select snapshot || jsonb_build_object(
 'mfa_factor_rows',(select coalesce(jsonb_agg(to_jsonb(t) order by id),'[]'::jsonb) from
   (select id,user_id,status,factor_type,friendly_name,created_at,updated_at
    from auth.mfa_factors where user_id in ({QA_SQL}) order by id limit 30) t),
 'active_session_rows',(select coalesce(jsonb_agg(to_jsonb(t) order by id),'[]'::jsonb) from
   (select id,user_id,aal,created_at,updated_at,not_after
    from auth.sessions where user_id in ({QA_SQL}) and (not_after is null or not_after>now())
    order by id limit 30) t)
 , 'shared_expenses',(select count(*) from public.shared_expenses where created_by in ({QA_SQL})
    or connection_id in (select id from public.couple_connections where user_a in ({QA_SQL}) or user_b in ({QA_SQL}))),
 'couple_budgets',(select count(*) from public.couple_budgets where created_by in ({QA_SQL})
    or connection_id in (select id from public.couple_connections where user_a in ({QA_SQL}) or user_b in ({QA_SQL})))
) as snapshot from base"""


def count(value, expected, code):
    need(type(value) is int and value == expected, code)


def validate_snapshot(value):
    """Reject unexpected data before any response is persisted."""
    need(isinstance(value, dict) and set(value) == SNAPSHOT_FIELDS, "SNAPSHOT_FIELDS_INVALID")
    now = instant(value["now"])
    users = value["users"]
    need(isinstance(users, list) and len(users) == 3 and
        all(isinstance(row, dict) and set(row) == {"id", "email", "confirmed", "qa_run"} for row in users),
        "QA_IDENTITIES_INVALID")
    need({row["email"]: row["id"] for row in users} == QA_USERS and
        all(row["confirmed"] is True and row["qa_run"] == QA_RUN for row in users), "QA_IDENTITIES_INVALID")
    profiles = value["profiles"]
    need(isinstance(profiles, list) and len(profiles) == 2 and
        all(isinstance(row, dict) and set(row) == {"user_id", "couple_code", "permissions"} for row in profiles),
        "QA_PROFILES_INVALID")
    need({row["user_id"] for row in profiles} == {A, B}, "QA_PROFILES_INVALID")
    for row in profiles:
        permissions = row["permissions"]
        need(isinstance(row["couple_code"], str) and
            re.fullmatch(r"[ABCDEFGHJKMNPQRSTUVWXYZ23456789]{8}", row["couple_code"]) is not None,
            "QA_CODE_INVALID")
        need(isinstance(permissions, dict) and
            all(isinstance(key, str) and re.fullmatch(r"[a-z_]{1,60}", key) and type(item) is bool
                for key, item in permissions.items()) and
            permissions.get("couple_expenses") is True and permissions.get("admin") is not True,
            "QA_PERMISSIONS_INVALID")
    need(profiles[0]["couple_code"] != profiles[1]["couple_code"], "QA_CODE_COLLISION")
    need(value["registrations"] == "false", "REGISTRATIONS_CHANGED")
    for field in ("transactions", "connections", "request_count", "audit_count", "shared_expenses", "couple_budgets"):
        count(value[field], 0, "QA_BUSINESS_NOT_EMPTY")
    need(value["requests"] == [] and value["audits"] == [], "QA_BUSINESS_NOT_EMPTY")
    for field, total, fields in (("mfa_factor_rows", "factors", FACTOR_FIELDS),
                                ("active_session_rows", "sessions", SESSION_FIELDS)):
        rows = value[field]
        need(isinstance(rows, list) and len(rows) <= 30, "AUTH_ROWS_INVALID")
        count(value[total], len(rows), "AUTH_COUNT_MISMATCH")
        seen = set()
        for row in rows:
            need(isinstance(row, dict) and set(row) == fields, "AUTH_ROW_FIELDS_INVALID")
            identifier = canonical(row["id"])
            need(identifier not in seen, "AUTH_ID_DUPLICATED")
            seen.add(identifier)
            need(row["user_id"] == A, "FOREIGN_QA_AUTH_STATE")
            need(instant(row["created_at"]) <= instant(row["updated_at"]) <= now, "AUTH_TIMESTAMP_INVALID")
            if field == "mfa_factor_rows":
                need(row["factor_type"] == "totp" and row["friendly_name"] == "Savely Android" and
                    row["status"] in ("unverified", "verified"), "FACTOR_PAYLOAD_INVALID")
            else:
                need(row["aal"] in ("aal1", "aal2"), "SESSION_AAL_INVALID")
                need(row["not_after"] is None or instant(row["not_after"]) > now, "SESSION_ALREADY_EXPIRED")
    return copy.deepcopy(value)


def ordered(rows):
    return sorted(rows, key=lambda row: row.get("id", row.get("user_id")))


def transition(stage, snapshot, base, previous, bindings):
    need(ordered(snapshot["users"]) == ordered(base["users"]) and
        ordered(snapshot["profiles"]) == ordered(base["profiles"]), "QA_PROJECTION_CHANGED")
    need(instant(snapshot["now"]) >= instant(previous["now"]), "DATABASE_CLOCK_REVERSED")
    bindings = copy.deepcopy(bindings)
    index = STAGES.index(stage)
    factor_key = "first_factor" if index <= 2 else "second_factor" if 4 <= index <= 11 else None
    session_key = "first_session" if index <= 6 else "second_session" if 8 <= index <= 12 else None
    expected_status = "unverified" if index <= 5 else "verified"
    expected_aal = "aal1" if index <= 5 or index == 8 else "aal2"
    for field, key, fresh_stage, prior_key in (
        ("mfa_factor_rows", factor_key, ("pending_cancel_ready", "enrollment_ready"), "first_factor"),
        ("active_session_rows", session_key, ("pending_cancel_ready", "login_gate_wrong"), "first_session"),
    ):
        rows = snapshot[field]
        need(len(rows) == (0 if key is None else 1), "CHECKPOINT_AUTH_COUNT_INVALID")
        if key is None:
            continue
        row = rows[0]
        if key not in bindings:
            need(stage in fresh_stage, "AUTH_BINDING_MISSING")
            need(instant(row["created_at"]) > instant(base["now"]), "AUTH_PREDATES_PREFLIGHT")
            if key.startswith("second_"):
                need(prior_key in bindings and row["id"] != bindings[prior_key]["id"], "AUTH_ID_REUSED")
                need(instant(row["created_at"]) >= instant(previous["now"]), "NEW_AUTH_PREDATES_CHECKPOINT")
            bindings[key] = copy.deepcopy(row)
        origin = bindings[key]
        for name in ("id", "user_id", "created_at"):
            need(row[name] == origin[name], "AUTH_BINDING_CHANGED")
        old_rows = previous[field]
        old_row = next((item for item in old_rows if item["id"] == row["id"]), None)
        need(old_row is None or instant(row["updated_at"]) >= instant(old_row["updated_at"]), "AUTH_CLOCK_REVERSED")
        if field == "mfa_factor_rows":
            need(row["status"] == expected_status, "CHECKPOINT_FACTOR_STATUS_INVALID")
        else:
            allowed = ("aal1", "aal2") if stage == "removal_completed" else (expected_aal,)
            need(row["aal"] in allowed, "CHECKPOINT_AAL_INVALID")
    return bindings


class MfaFormEvidence:
    def __init__(self, run, executor, root=ROOT):
        self.run = canonical(run)
        self.root = Path(root).resolve()
        self.directory = self.root / self.run / "evidence"
        self.execute = executor

    def path(self, name):
        valid = {"preflight-reservation", "preflight-observed", "preflight-failed"} | {
            stage + suffix for stage in STAGES for suffix in ("-reservation", "-observed", "-failed")}
        need(name in valid, "JOURNAL_NAME_INVALID")
        need(self.directory.resolve().is_relative_to(self.root / self.run), "JOURNAL_PATH_ESCAPED")
        return self.directory / (name + ".json")

    def save(self, name, payload):
        exclusive(self.path(name), {"version": 1, "run_id": self.run, "project": PROJECT,
            "qa_run": QA_RUN, **payload})

    def read(self, name):
        try:
            with self.path(name).open("rb") as source:
                raw = source.read(262145)
            need(len(raw) <= 262144, "JOURNAL_TOO_LARGE")
            value = json.loads(raw)
            need(isinstance(value, dict) and type(value.get("version")) is int and all(value.get(key) == expected for key, expected in
                {"version": 1, "run_id": self.run, "project": PROJECT, "qa_run": QA_RUN}.items()),
                "JOURNAL_SCOPE_INVALID")
            return value
        except EvidenceError:
            raise
        except Exception:
            raise EvidenceError("JOURNAL_INVALID_OR_MISSING") from None

    def snapshot(self):
        try:
            rows = self.execute(SNAPSHOT_SQL, read_only=True)
            need(isinstance(rows, list) and len(rows) == 1 and isinstance(rows[0], dict) and
                set(rows[0]) == {"snapshot"}, "SNAPSHOT_RESPONSE_INVALID")
            return validate_snapshot(rows[0]["snapshot"])
        except EvidenceError:
            raise
        except Exception:
            raise EvidenceError("READ_OUTCOME_UNCERTAIN") from None

    def failure(self, name, error):
        code = str(error) if isinstance(error, EvidenceError) else "READ_OUTCOME_UNCERTAIN"
        self.save(name + "-failed", {"status": "failed", "failure_code": code})

    def preflight(self):
        need(self.root.exists() and self.root.is_dir(), "JOURNAL_ROOT_MISSING")
        self.path("preflight-reservation")
        self.directory.mkdir(parents=True, exist_ok=True)
        self.save("preflight-reservation", {"status": "reserved"})
        try:
            value = self.snapshot()
            need(value["factors"] == 0 and value["sessions"] == 0, "PREFLIGHT_AUTH_NOT_EMPTY")
            self.save("preflight-observed", {"status": "observed", "snapshot": value})
            return value
        except Exception as error:
            self.failure("preflight", error)
            raise EvidenceError(str(error) if isinstance(error, EvidenceError) else "READ_OUTCOME_UNCERTAIN") from None

    def history(self):
        need(self.path("preflight-observed").exists() and not self.path("preflight-failed").exists(), "PREFLIGHT_NOT_COMPLETE")
        base = validate_snapshot(self.read("preflight-observed")["snapshot"])
        need(base["factors"] == 0 and base["sessions"] == 0, "PREFLIGHT_AUTH_NOT_EMPTY")
        previous, bindings, completed = base, {}, []
        gap = False
        for stage in STAGES:
            reserved = self.path(stage + "-reservation").exists()
            observed = self.path(stage + "-observed").exists()
            need(not self.path(stage + "-failed").exists() and (not reserved or observed), "CHECKPOINT_UNCERTAIN_NO_REPLAY")
            if not observed:
                gap = True
                continue
            need(reserved and not gap, "CHECKPOINT_ORDER_INVALID")
            reservation = self.read(stage + "-reservation")
            self.validate_native(stage, reservation.get("native_report"))
            proof = self.read(stage + "-observed")
            need(proof.get("status") == "observed" and proof.get("stage") == stage, "JOURNAL_CHECKPOINT_INVALID")
            value = validate_snapshot(proof["snapshot"])
            bindings = transition(stage, value, base, previous, bindings)
            need(proof.get("bindings") == bindings, "JOURNAL_BINDINGS_CHANGED")
            previous = value
            completed.append(stage)
        return base, previous, bindings, completed

    def validate_native(self, stage, report):
        need(isinstance(report, dict) and set(report) == {"run_id", "user_id", "stage", "status"}, "NATIVE_REPORT_FIELDS_INVALID")
        need(report == {"run_id": self.run, "user_id": A, "stage": stage, "status": "ready"}, "NATIVE_REPORT_SCOPE_INVALID")

    def observe(self, stage, native_report):
        need(stage in STAGES, "CHECKPOINT_INVALID")
        self.validate_native(stage, native_report)
        base, previous, bindings, completed = self.history()
        need(len(completed) < len(STAGES) and stage == STAGES[len(completed)], "CHECKPOINT_ORDER_INVALID")
        self.save(stage + "-reservation", {"status": "reserved", "native_report": native_report})
        try:
            value = self.snapshot()
            bindings = transition(stage, value, base, previous, bindings)
            self.save(stage + "-observed", {"status": "observed", "stage": stage, "snapshot": value, "bindings": bindings})
            return value
        except Exception as error:
            self.failure(stage, error)
            raise EvidenceError(str(error) if isinstance(error, EvidenceError) else "READ_OUTCOME_UNCERTAIN") from None
