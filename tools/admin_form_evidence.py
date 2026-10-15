"""D01 fixed read-only ledger: eleven checkpoints, not eleven JUnit cases.

The coordinator performs native forms and exact QA role CAS outside this ledger.
No credentials, login, permission writes, logout or cleanup are implemented here.
"""
import copy
import json
from pathlib import Path
import re

from couple_invitation_evidence import (
    A, B, QA, QA_SQL, QA_USERS, QA_RUN, PROJECT, Management, EvidenceError,
    canonical, instant, exclusive, need,
)
from mfa_form_evidence import SNAPSHOT_SQL as MFA_SQL, SNAPSHOT_FIELDS, SESSION_FIELDS, count, ordered

ADMIN = QA_USERS["android-qa-5ef2838c-admin@example.invalid"]
ROOT = Path(__file__).resolve().parent.parent / "artifacts/admin-form-ui"
STAGES = (
    "admin_ready", "permission_cancelled", "permission_changed", "permission_restored",
    "secondary_admin_ready", "primary_role_revoked", "primary_role_denied",
    "primary_role_restored", "primary_reloaded", "secondary_admin_removed", "completed",
)
PERMISSIONS = {"admin", "fire", "poker", "fumo", "tcg", "libreria", "couple_expenses"}
SNAPSHOT_SQL = f"""with base as ({MFA_SQL}) select snapshot || jsonb_build_object(
 'qa_profile_rows',(select coalesce(jsonb_agg(to_jsonb(t) order by user_id),'[]'::jsonb) from
   (select id,user_id,permissions from public.profiles where user_id in ({QA_SQL})
    order by user_id limit 4) t)
) as snapshot from base"""


def permissions(value):
    need(isinstance(value, dict) and len(value) <= 50 and
         all(isinstance(key, str) and re.fullmatch(r"[a-z_]{1,50}", key) and type(flag) is bool
             for key, flag in value.items()), "PERMISSIONS_INVALID")


def validate_snapshot(value, *, allow_user_a=False):
    # MFA validation fixes owner A/admin=false and cannot validate these states.
    need(type(allow_user_a) is bool, "AUTH_SCOPE_OPTION_INVALID")
    need(isinstance(value, dict) and set(value) == SNAPSHOT_FIELDS | {"qa_profile_rows"},
         "SNAPSHOT_FIELDS_INVALID")
    now = instant(value["now"])
    users = value["users"]
    need(isinstance(users, list) and len(users) == 3 and
         all(isinstance(row, dict) and set(row) == {"id", "email", "confirmed", "qa_run"}
             for row in users), "QA_IDENTITIES_INVALID")
    need({row["email"]: row["id"] for row in users} == QA_USERS and
         all(row["confirmed"] is True and row["qa_run"] == QA_RUN for row in users), "QA_IDENTITIES_INVALID")
    profiles = value["profiles"]
    need(isinstance(profiles, list) and len(profiles) == 2 and
         all(isinstance(row, dict) and set(row) == {"user_id", "couple_code", "permissions"}
             for row in profiles) and {row["user_id"] for row in profiles} == {A, B}, "QA_PROFILES_INVALID")
    for row in profiles:
        permissions(row["permissions"])
        need(isinstance(row["couple_code"], str) and
             re.fullmatch(r"[ABCDEFGHJKMNPQRSTUVWXYZ23456789]{8}", row["couple_code"]), "QA_CODE_INVALID")
    need(profiles[0]["couple_code"] != profiles[1]["couple_code"], "QA_CODE_COLLISION")
    qa_rows = value["qa_profile_rows"]
    need(isinstance(qa_rows, list) and len(qa_rows) == 3 and
         all(isinstance(row, dict) and set(row) == {"id", "user_id", "permissions"}
             for row in qa_rows) and {row["user_id"] for row in qa_rows} == set(QA), "QA_PROFILE_ROWS_INVALID")
    identifiers = set()
    for row in qa_rows:
        identifier = canonical(row["id"])
        need(identifier not in identifiers, "PROFILE_ID_DUPLICATED")
        identifiers.add(identifier)
        permissions(row["permissions"])
    by_owner = {row["user_id"]: row for row in qa_rows}
    need(all(row["permissions"] == by_owner[row["user_id"]]["permissions"] for row in profiles),
         "QA_PROFILE_PROJECTION_MISMATCH")
    need(value["registrations"] == "false", "REGISTRATIONS_CHANGED")
    for field in ("transactions", "connections", "request_count", "audit_count", "shared_expenses", "couple_budgets"):
        count(value[field], 0, "QA_BUSINESS_NOT_EMPTY")
    need(value["requests"] == [] and value["audits"] == [], "QA_BUSINESS_NOT_EMPTY")
    count(value["factors"], 0, "QA_FACTORS_NOT_EMPTY")
    need(value["mfa_factor_rows"] == [], "QA_FACTORS_NOT_EMPTY")
    rows = value["active_session_rows"]
    need(isinstance(rows, list) and len(rows) <= 2, "AUTH_ROWS_INVALID")
    count(value["sessions"], len(rows), "AUTH_COUNT_MISMATCH")
    ids, owners = set(), set()
    for row in rows:
        need(isinstance(row, dict) and set(row) == SESSION_FIELDS, "AUTH_ROW_FIELDS_INVALID")
        identifier = canonical(row["id"])
        need(identifier not in ids, "AUTH_ID_DUPLICATED")
        ids.add(identifier)
        need(row["user_id"] in ((ADMIN, A, B) if allow_user_a else (ADMIN, B)), "FOREIGN_QA_AUTH_STATE")
        need(row["user_id"] not in owners, "SESSION_OWNER_DUPLICATED")
        owners.add(row["user_id"])
        need(instant(row["created_at"]) <= instant(row["updated_at"]) <= now, "AUTH_TIMESTAMP_INVALID")
        need(row["aal"] in ("aal1", "aal2"), "SESSION_AAL_INVALID")
        need(row["not_after"] is None or instant(row["not_after"]) > now, "SESSION_ALREADY_EXPIRED")
    return copy.deepcopy(value)


def baseline(value):
    need(value["sessions"] == 0, "PREFLIGHT_AUTH_NOT_EMPTY")
    for row in value["qa_profile_rows"]:
        flags = row["permissions"]
        need(set(flags) == PERMISSIONS and flags["fire"] is True and
             flags["admin"] is (row["user_id"] == ADMIN), "BASELINE_ROLES_INVALID")


def transition(stage, snapshot, base, previous, bindings):
    need(ordered(snapshot["users"]) == ordered(base["users"]), "QA_IDENTITIES_CHANGED")
    need(instant(snapshot["now"]) >= instant(previous["now"]), "DATABASE_CLOCK_REVERSED")
    index = STAGES.index(stage)
    expected_profiles = copy.deepcopy(base["qa_profile_rows"])
    for row in expected_profiles:
        if row["user_id"] == B:
            if stage == "permission_changed":
                row["permissions"]["fire"] = False
            if 4 <= index <= 8:
                row["permissions"]["admin"] = True
        if row["user_id"] == ADMIN and stage in ("primary_role_revoked", "primary_role_denied"):
            row["permissions"]["admin"] = False
    need(ordered(snapshot["qa_profile_rows"]) == ordered(expected_profiles), "QA_PERMISSIONS_OR_PROFILE_CHANGED")
    expected_projection = copy.deepcopy(base["profiles"])
    flags = {row["user_id"]: row["permissions"] for row in expected_profiles}
    for row in expected_projection:
        row["permissions"] = flags[row["user_id"]]
    need(ordered(snapshot["profiles"]) == ordered(expected_projection), "QA_PROJECTION_CHANGED")
    bindings = copy.deepcopy(bindings)
    rows = snapshot["active_session_rows"]
    expected = () if stage == "completed" else ("primary", "secondary") if index >= 4 else ("primary",)
    need(len(rows) == len(expected), "CHECKPOINT_SESSION_COUNT_INVALID")
    fresh = "primary" if stage == "admin_ready" else "secondary" if stage == "secondary_admin_ready" else None
    if fresh:
        need(fresh not in bindings, "SESSION_ALREADY_BOUND")
        owner = ADMIN if fresh == "primary" else B
        new_rows = [row for row in rows if row["user_id"] == owner]
        need(len(new_rows) == 1, "NEW_SESSION_NOT_UNIQUE")
        new = new_rows[0]
        need(new["id"] not in {row["id"] for row in bindings.values()}, "SESSION_ID_REUSED")
        need(instant(new["created_at"]) > instant(base["now"]), "SESSION_PREDATES_PREFLIGHT")
        need(fresh == "primary" or instant(new["created_at"]) >= instant(previous["now"]),
             "NEW_SESSION_PREDATES_CHECKPOINT")
        bindings[fresh] = copy.deepcopy(new)
    need(all(key in bindings for key in expected), "SESSION_BINDING_MISSING")
    need({row["id"] for row in rows} == {bindings[key]["id"] for key in expected}, "SESSION_BINDING_CHANGED")
    old_rows = {row["id"]: row for row in previous["active_session_rows"]}
    for row in rows:
        origin = next(bindings[key] for key in expected if bindings[key]["id"] == row["id"])
        need(all(row[field] == origin[field] for field in ("id", "user_id", "created_at", "aal")),
             "SESSION_BINDING_CHANGED")
        old = old_rows.get(row["id"])
        need(old is None or instant(row["updated_at"]) >= instant(old["updated_at"]), "SESSION_CLOCK_REVERSED")
    return bindings


class AdminFormEvidence:
    def __init__(self, run, executor, root=ROOT):
        self.run = canonical(run)
        self.root = Path(root).resolve()
        self.directory = self.root / self.run / "evidence"
        self.execute = executor

    def path(self, name):
        valid = {"preflight-reservation", "preflight-observed", "preflight-failed"} | {
            stage + suffix for stage in STAGES for suffix in ("-reservation", "-observed", "-failed")}
        need(name in valid, "JOURNAL_NAME_INVALID")
        run_root = self.root / self.run
        need(run_root.resolve().is_relative_to(self.root) and
             self.directory.resolve().is_relative_to(run_root), "JOURNAL_PATH_ESCAPED")
        result = self.directory / (name + ".json")
        need(result.resolve().is_relative_to(self.directory.resolve()), "JOURNAL_PATH_ESCAPED")
        return result

    def save(self, name, payload):
        exclusive(self.path(name), {"version": 1, "run_id": self.run,
            "project": PROJECT, "qa_run": QA_RUN, **payload})

    def read(self, name):
        try:
            with self.path(name).open("rb") as source:
                raw = source.read(262145)
            need(len(raw) <= 262144, "JOURNAL_TOO_LARGE")
            value = json.loads(raw)
            need(isinstance(value, dict) and type(value.get("version")) is int and
                 all(value.get(key) == expected for key, expected in
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
        except Exception:
            raise EvidenceError("READ_OUTCOME_UNCERTAIN") from None
        need(isinstance(rows, list) and len(rows) == 1 and isinstance(rows[0], dict) and
             set(rows[0]) == {"snapshot"}, "SNAPSHOT_RESPONSE_INVALID")
        return validate_snapshot(rows[0]["snapshot"])

    def failure(self, name, error):
        self.save(name + "-failed", {"status": "failed", "failure_code":
            str(error) if isinstance(error, EvidenceError) else "READ_OUTCOME_UNCERTAIN"})

    def preflight(self):
        need(self.root.exists() and self.root.is_dir(), "JOURNAL_ROOT_MISSING")
        self.path("preflight-reservation")
        self.directory.mkdir(parents=True, exist_ok=True)
        self.save("preflight-reservation", {"status": "reserved"})
        try:
            value = self.snapshot()
            baseline(value)
            self.save("preflight-observed", {"status": "observed", "snapshot": value})
            return value
        except Exception as error:
            self.failure("preflight", error)
            raise EvidenceError(str(error) if isinstance(error, EvidenceError) else "READ_OUTCOME_UNCERTAIN") from None

    def validate_native(self, stage, report):
        need(isinstance(report, dict) and set(report) == {"run_id", "user_id", "stage", "status"},
             "NATIVE_REPORT_FIELDS_INVALID")
        need(report == {"run_id": self.run, "user_id": ADMIN, "stage": stage, "status": "ready"},
             "NATIVE_REPORT_SCOPE_INVALID")

    def history(self):
        need(self.path("preflight-observed").exists() and not self.path("preflight-failed").exists(), "PREFLIGHT_NOT_COMPLETE")
        need(self.read("preflight-reservation").get("status") == "reserved", "JOURNAL_RESERVATION_INVALID")
        proof = self.read("preflight-observed")
        need(proof.get("status") == "observed", "JOURNAL_CHECKPOINT_INVALID")
        base = validate_snapshot(proof["snapshot"])
        baseline(base)
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
            need(reservation.get("status") == "reserved", "JOURNAL_RESERVATION_INVALID")
            self.validate_native(stage, reservation.get("native_report"))
            proof = self.read(stage + "-observed")
            need(proof.get("status") == "observed" and proof.get("stage") == stage, "JOURNAL_CHECKPOINT_INVALID")
            value = validate_snapshot(proof["snapshot"])
            bindings = transition(stage, value, base, previous, bindings)
            need(proof.get("bindings") == bindings, "JOURNAL_BINDINGS_CHANGED")
            previous = value
            completed.append(stage)
        return base, previous, bindings, completed

    def observe(self, stage, native_report):
        need(isinstance(stage, str) and stage in STAGES, "CHECKPOINT_INVALID")
        self.validate_native(stage, native_report)
        base, previous, bindings, completed = self.history()
        need(len(completed) < len(STAGES) and stage == STAGES[len(completed)], "CHECKPOINT_ORDER_INVALID")
        self.save(stage + "-reservation", {"status": "reserved", "native_report": native_report})
        try:
            value = self.snapshot()
            bindings = transition(stage, value, base, previous, bindings)
            self.save(stage + "-observed", {"status": "observed", "stage": stage,
                "snapshot": value, "bindings": bindings})
            return value
        except Exception as error:
            self.failure(stage, error)
            raise EvidenceError(str(error) if isinstance(error, EvidenceError) else "READ_OUTCOME_UNCERTAIN") from None
