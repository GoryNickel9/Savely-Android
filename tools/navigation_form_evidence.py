"""V02 read-only ledger: fifteen native checkpoints, not fifteen JUnit tests.

Only the fixed three-QA snapshot is read. The coordinator owns login, UI,
permission CAS and logout; ACK follows the durable validated observation.
No passwords, tokens, JWTs or owner revisions are stored by this ledger.
"""
import copy
import json
from pathlib import Path

from admin_form_evidence import (
    A, B, ADMIN, PROJECT, QA_RUN, SNAPSHOT_SQL, Management, EvidenceError,
    canonical, instant, exclusive, need, ordered, validate_snapshot as admin_snapshot, baseline,
)

ROOT = Path(__file__).resolve().parent.parent / "artifacts/navigation-form-ui"
STAGES = (
    "admin_ready", "admin_routes", "admin_logged_out", "a_ready", "a_routes", "a_logged_out",
    "b_denied_ready", "b_denied_routes", "b_granted", "b_revoked", "b_revocation_denied",
    "b_restored", "b_restoration_visible", "b_logged_out", "completed",
)
ACTORS = {stage: ADMIN if index < 3 else A if index < 6 else B for index, stage in enumerate(STAGES)}
EXPECTED_SESSIONS = {
    "admin_ready": ("native_admin",), "admin_routes": ("native_admin",), "admin_logged_out": (),
    "a_ready": ("native_a",), "a_routes": ("native_a",), "a_logged_out": (),
    "b_denied_ready": ("native_b", "host_admin"), "b_denied_routes": ("native_b", "host_admin"),
    "b_granted": ("native_b", "host_admin"), "b_revoked": ("native_b", "host_admin"),
    "b_revocation_denied": ("native_b", "host_admin"), "b_restored": ("native_b", "host_admin"),
    "b_restoration_visible": ("native_b", "host_admin"), "b_logged_out": ("host_admin",), "completed": (),
}
FRESH = {"admin_ready": (("native_admin", ADMIN),), "a_ready": (("native_a", A),),
         "b_denied_ready": (("native_b", B), ("host_admin", ADMIN))}
DENIED = {"b_denied_ready", "b_denied_routes", "b_revoked", "b_revocation_denied"}


def validate_snapshot(value):
    return admin_snapshot(value, allow_user_a=True)


def transition(stage, snapshot, base, previous, bindings):
    need(ordered(snapshot["users"]) == ordered(base["users"]), "QA_IDENTITIES_CHANGED")
    need(instant(snapshot["now"]) >= instant(previous["now"]), "DATABASE_CLOCK_REVERSED")
    expected_profiles = copy.deepcopy(base["qa_profile_rows"])
    if stage in DENIED:
        next(row for row in expected_profiles if row["user_id"] == B)["permissions"]["fire"] = False
    need(ordered(snapshot["qa_profile_rows"]) == ordered(expected_profiles), "QA_PERMISSIONS_OR_PROFILE_CHANGED")
    expected_projection = copy.deepcopy(base["profiles"])
    flags = {row["user_id"]: row["permissions"] for row in expected_profiles}
    for row in expected_projection:
        row["permissions"] = flags[row["user_id"]]
    need(ordered(snapshot["profiles"]) == ordered(expected_projection), "QA_PROJECTION_CHANGED")
    bindings = copy.deepcopy(bindings)
    rows = snapshot["active_session_rows"]
    expected = EXPECTED_SESSIONS[stage]
    need(len(rows) == len(expected), "CHECKPOINT_SESSION_COUNT_INVALID")
    for key, owner in FRESH.get(stage, ()):
        need(key not in bindings, "SESSION_ALREADY_BOUND")
        candidates = [row for row in rows if row["user_id"] == owner]
        need(len(candidates) == 1, "NEW_SESSION_NOT_UNIQUE")
        row = candidates[0]
        need(row["id"] not in {item["id"] for item in bindings.values()}, "SESSION_ID_REUSED")
        need(instant(row["created_at"]) > instant(base["now"]), "SESSION_PREDATES_PREFLIGHT")
        # Each fresh batch follows preflight or the previous native logout.
        need(instant(row["created_at"]) > instant(previous["now"]), "NEW_SESSION_PREDATES_CHECKPOINT")
        bindings[key] = copy.deepcopy(row)
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


class NavigationFormEvidence:
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
        need(report == {"run_id": self.run, "user_id": ACTORS[stage], "stage": stage, "status": "ready"},
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
