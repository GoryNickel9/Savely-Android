"""A05 read-only ledger: nine native checkpoints, not nine JUnit tests.

No credentials, login, revocation, refresh or cleanup is performed here. The
coordinator supplies the fixed Management reader and acknowledges only after
observe() has durably recorded the validated snapshot.
"""
import copy
import json
from pathlib import Path

from couple_invitation_evidence import (
    A, B, QA, PROJECT, QA_RUN, Management, EvidenceError,
    canonical, instant, exclusive, need,
)
from mfa_form_evidence import SNAPSHOT_SQL, validate_snapshot, ordered

ROOT = Path(__file__).resolve().parent.parent / "artifacts/session-form-ui"
STAGES = (
    "primary_ready", "secondary_ready", "revocation_cancelled", "secondary_revoked",
    "secondary_refresh_rejected", "survivor_ready", "primary_revoked",
    "survivor_refreshed", "completed",
)
EXPECTED = {
    "primary_ready": ("primary",),
    "secondary_ready": ("primary", "secondary"),
    "revocation_cancelled": ("primary", "secondary"),
    "secondary_revoked": ("primary",),
    "secondary_refresh_rejected": ("primary",),
    "survivor_ready": ("primary", "survivor"),
    "primary_revoked": ("survivor",),
    "survivor_refreshed": ("survivor",),
    "completed": (),
}
FRESH = {"primary_ready": "primary", "secondary_ready": "secondary", "survivor_ready": "survivor"}


def clean_snapshot(value):
    value = validate_snapshot(value)
    need(value["factors"] == 0, "QA_FACTORS_NOT_EMPTY")
    return value


def transition(stage, snapshot, base, previous, bindings):
    need(ordered(snapshot["users"]) == ordered(base["users"]) and
         ordered(snapshot["profiles"]) == ordered(base["profiles"]), "QA_PROJECTION_CHANGED")
    need(instant(snapshot["now"]) >= instant(previous["now"]), "DATABASE_CLOCK_REVERSED")
    bindings = copy.deepcopy(bindings)
    rows = snapshot["active_session_rows"]
    expected = EXPECTED[stage]
    need(len(rows) == len(expected), "CHECKPOINT_SESSION_COUNT_INVALID")
    fresh = FRESH.get(stage)
    if fresh:
        need(fresh not in bindings, "SESSION_ALREADY_BOUND")
        known_ids = {row["id"] for row in bindings.values()}
        new_rows = [row for row in rows if row["id"] not in known_ids]
        need(len(new_rows) == 1, "NEW_SESSION_NOT_UNIQUE")
        new = new_rows[0]
        need(instant(new["created_at"]) > instant(base["now"]), "SESSION_PREDATES_PREFLIGHT")
        need(fresh == "primary" or instant(new["created_at"]) >= instant(previous["now"]),
             "NEW_SESSION_PREDATES_CHECKPOINT")
        bindings[fresh] = copy.deepcopy(new)
    need(all(key in bindings for key in expected), "SESSION_BINDING_MISSING")
    need({row["id"] for row in rows} == {bindings[key]["id"] for key in expected},
         "SESSION_BINDING_CHANGED")
    previous_rows = {row["id"]: row for row in previous["active_session_rows"]}
    for row in rows:
        origin = next(bindings[key] for key in expected if bindings[key]["id"] == row["id"])
        need(all(row[field] == origin[field] for field in ("id", "user_id", "created_at")),
             "SESSION_BINDING_CHANGED")
        need(row["aal"] == "aal1", "CHECKPOINT_AAL_INVALID")
        old = previous_rows.get(row["id"])
        need(old is None or instant(row["updated_at"]) >= instant(old["updated_at"]),
             "SESSION_CLOCK_REVERSED")
    return bindings


class SessionFormEvidence:
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
        # Transport errors are withheld even if an executor embeds response data.
        try:
            rows = self.execute(SNAPSHOT_SQL, read_only=True)
        except Exception:
            raise EvidenceError("READ_OUTCOME_UNCERTAIN") from None
        need(isinstance(rows, list) and len(rows) == 1 and isinstance(rows[0], dict) and
             set(rows[0]) == {"snapshot"}, "SNAPSHOT_RESPONSE_INVALID")
        return clean_snapshot(rows[0]["snapshot"])

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
            need(value["sessions"] == 0, "PREFLIGHT_AUTH_NOT_EMPTY")
            self.save("preflight-observed", {"status": "observed", "snapshot": value})
            return value
        except Exception as error:
            self.failure("preflight", error)
            raise EvidenceError(str(error) if isinstance(error, EvidenceError) else "READ_OUTCOME_UNCERTAIN") from None

    def validate_native(self, stage, report):
        need(isinstance(report, dict) and set(report) == {"run_id", "user_id", "stage", "status"},
             "NATIVE_REPORT_FIELDS_INVALID")
        need(report == {"run_id": self.run, "user_id": A, "stage": stage, "status": "ready"},
             "NATIVE_REPORT_SCOPE_INVALID")

    def history(self):
        need(self.path("preflight-observed").exists() and not self.path("preflight-failed").exists(),
             "PREFLIGHT_NOT_COMPLETE")
        need(self.read("preflight-reservation").get("status") == "reserved", "JOURNAL_RESERVATION_INVALID")
        preflight = self.read("preflight-observed")
        need(preflight.get("status") == "observed", "JOURNAL_CHECKPOINT_INVALID")
        base = clean_snapshot(preflight["snapshot"])
        need(base["sessions"] == 0, "PREFLIGHT_AUTH_NOT_EMPTY")
        previous, bindings, completed = base, {}, []
        gap = False
        for stage in STAGES:
            reserved = self.path(stage + "-reservation").exists()
            observed = self.path(stage + "-observed").exists()
            need(not self.path(stage + "-failed").exists() and (not reserved or observed),
                 "CHECKPOINT_UNCERTAIN_NO_REPLAY")
            if not observed:
                gap = True
                continue
            need(reserved and not gap, "CHECKPOINT_ORDER_INVALID")
            reservation = self.read(stage + "-reservation")
            need(reservation.get("status") == "reserved", "JOURNAL_RESERVATION_INVALID")
            self.validate_native(stage, reservation.get("native_report"))
            proof = self.read(stage + "-observed")
            need(proof.get("status") == "observed" and proof.get("stage") == stage,
                 "JOURNAL_CHECKPOINT_INVALID")
            value = clean_snapshot(proof["snapshot"])
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
