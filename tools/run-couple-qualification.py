"""Opt-in native pair qualification; exact-run host cleanup. No schema or account changes."""
import argparse
import base64
import datetime as dt
from decimal import Decimal
import json
import os
from pathlib import Path
import re
import subprocess
import uuid
from urllib.parse import urlencode
from urllib.request import Request, build_opener, HTTPRedirectHandler
from urllib.error import HTTPError

from qa_credentials import load_credentials, PROJECT, QA_USERS

ROOT = Path(__file__).resolve().parent.parent
BASE = f"https://{PROJECT}.supabase.co"
HOST = f"{PROJECT}.supabase.co"
A_EMAIL = "android-qa-5ef2838c-a@example.invalid"
B_EMAIL = "android-qa-5ef2838c-b@example.invalid"
A, B = QA_USERS[A_EMAIL], QA_USERS[B_EMAIL]
PACKAGE = "cc.savely.android"
CLASS = PACKAGE + ".data.RealCoupleQualificationTest"
INPUT = "couple-qualification-session.json"
LEGACY_CAS_RUN = "75789397-93d6-4774-9db2-8a75f262de73"
LEGACY_CAS_KEY = "shared_expenses:7b27145f-51e9-433f-8523-f1a2c99996c6"


class QualificationError(RuntimeError):
    def __init__(self, code):
        self.code = code if isinstance(code, str) and re.fullmatch(r"[A-Z][A-Z0-9_]{1,100}", code) else "INTERNAL_CODE_WITHHELD"
        super().__init__(self.code)


class HttpOutcomeError(QualificationError):
    def __init__(self, code, status=None, server_code=None):
        super().__init__(code)
        self.status = status
        self.server_code = server_code if isinstance(server_code, str) and re.fullmatch(r"(?:[A-Z0-9]{5}|PGRST[0-9]{3})", server_code) else None


def require(ok, code):
    if not ok:
        raise QualificationError(code)


def uid(value):
    require(isinstance(value, str) and str(uuid.UUID(value)) == value, "UUID_INVALID")
    return value


def dump(value):
    def decimal_only(item):
        if isinstance(item, Decimal):
            return str(item)
        raise TypeError("UNSUPPORTED_JSON_VALUE")
    return json.dumps(value, default=decimal_only,
                      separators=(",", ":"), ensure_ascii=False)


def atomic(path, value):
    temporary = path.with_suffix(path.suffix + ".tmp")
    with temporary.open("w", encoding="utf-8") as output:
        output.write(dump(value) + "\n")
        output.flush()
        os.fsync(output.fileno())
    temporary.replace(path)


def session_id(token):
    return uid(json.loads(base64.urlsafe_b64decode(token.split(".")[1] + "==="))["session_id"])


def identity(user, owner, email):
    require(user.get("id") == owner and user.get("email") == email and user.get("email_confirmed_at"), "QA_IDENTITY")
    require(not user.get("factors"), "EXISTING_MFA_PRESERVED")


class NoCredentialRedirect(HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, message, headers, new_url):
        return None


def http(path, public, token, method="GET", body=None, prefer=None, with_status=False):
    require(path.startswith(("/rest/v1/", "/auth/v1/")), "ENDPOINT_NOT_ALLOWED")
    headers = {"apikey": public, "Authorization": "Bearer " + token, "Content-Type": "application/json"}
    if prefer:
        headers["Prefer"] = prefer
    request = Request(BASE + path, method=method, headers=headers,
                      data=None if body is None else dump(body).encode())
    try:
        with build_opener(NoCredentialRedirect()).open(request, timeout=30) as response:
            raw = response.read(2_097_153)
            status = response.status
        require(len(raw) <= 2_097_152, "RESPONSE_TOO_LARGE")
        try:
            value = json.loads(raw, parse_float=Decimal) if raw else None
            return (value, status) if with_status else value
        except (UnicodeError, ValueError):
            raise HttpOutcomeError("SUCCESS_RESPONSE_JSON_UNCERTAIN", status) from None
    except HTTPError as error:
        server_code = None
        try:
            body = error.read(8193)
            if len(body) <= 8192:
                server_code = json.loads(body).get("code")
        except Exception:
            pass
        raise HttpOutcomeError(f"HTTP_{error.code}_BODY_WITHHELD", error.code, server_code) from None
    except (OSError, ValueError):
        raise HttpOutcomeError("TRANSPORT_OR_JSON_UNCERTAIN") from None


def query(table, **values):
    require(re.fullmatch(r"[a-z_]+", table), "TABLE_INVALID")
    return "/rest/v1/" + table + "?" + urlencode(values)


def equal(left, right):
    if isinstance(left, dict) and isinstance(right, dict):
        return left.keys() == right.keys() and all(equal(left[k], right[k]) for k in left)
    if isinstance(left, list) and isinstance(right, list):
        return len(left) == len(right) and all(equal(a, b) for a, b in zip(left, right))
    if left is None or right is None or isinstance(left, bool) or isinstance(right, bool):
        return type(left) is type(right) and left == right
    try:
        return Decimal(str(left)) == Decimal(str(right))
    except Exception:
        return type(left) is type(right) and left == right


def matches(row, expected):
    return all(key in row and equal(row[key], value) for key, value in expected.items())


def validate_ledger(value, run):
    uid(run)
    require(value.get("version") == 1 and value.get("run_id") == run and value.get("project_host") == HOST and
            value.get("user_a") == A and value.get("user_b") == B and value.get("marker") == "android-couple-" + run, "LEDGER_SCOPE")
    uid(value["session_a"]); uid(value["session_b"])
    require(value["session_a"] != value["session_b"], "SESSION_PAIR_INVALID")
    start = dt.datetime.fromisoformat(value["started_at"].replace("Z", "+00:00"))
    require(start.tzinfo is not None, "LEDGER_TIMEZONE")
    allowed = {
        "invitation": ("couple_connection_requests", "POST", A), "acceptance": ("rpc/accept_couple_request", "POST", B),
        "equal_tx": ("transactions", "POST", A), "custom_tx": ("transactions", "POST", B),
        "equal_share": ("shared_expenses", "POST", A), "custom_share": ("shared_expenses", "POST", B),
        "budget": ("couple_budgets", "POST", A), "budget_change": ("couple_budgets", "PATCH", B),
        "denied_private_update": ("transactions", "PATCH", B), "revocation": ("couple_connections", "PATCH", A),
    }
    require(isinstance(value.get("operations"), dict), "OPERATIONS_INVALID")
    ids = set()
    for label, operation in value["operations"].items():
        require(label in allowed and (operation["table"], operation["method"], operation["actor"]) == allowed[label], "OPERATION_SCOPE")
        require(isinstance(operation["expected"], dict), "OPERATION_BODY")
        if operation["method"] == "POST" and not operation["table"].startswith("rpc/"):
            uid(operation["id"])
            require(operation["id"] not in ids and operation["expected"].get("id") == operation["id"], "RECORD_ID_SCOPE")
            ids.add(operation["id"])
    marker = value["marker"]
    operations = value["operations"]
    if "invitation" in operations:
        expected = operations["invitation"]["expected"]
        require(expected == {"id": operations["invitation"]["id"], "sender_id": A, "receiver_id": B}, "INVITATION_PAYLOAD")
    if "acceptance" in operations:
        require("invitation" in operations and operations["acceptance"]["expected"] ==
                {"p_request_id": operations["invitation"]["id"]}, "ACCEPTANCE_PAYLOAD")
    if "budget_change" in operations:
        require("budget" in operations and operations["budget_change"]["expected"] == {"amount": "4.00"}, "BUDGET_CHANGE_PAYLOAD")
    if "denied_private_update" in operations:
        require("equal_tx" in operations and operations["denied_private_update"]["expected"] ==
                {"description": marker + "-denied"}, "DENIAL_PAYLOAD")
    if "revocation" in operations:
        require(value.get("connection_id") and operations["revocation"]["expected"] ==
                {"revoked_at": value.get("planned_revoked_at")}, "REVOCATION_PAYLOAD")
    for label, actor, currency, amount in (("equal_tx", A, "EUR", "10.01"), ("custom_tx", B, "USD", "7.00")):
        if label in operations:
            row = operations[label]["expected"]
            require(set(row) == {"id", "user_id", "type", "amount", "currency", "exchange_rate_eur", "category_id", "description", "date"}, "TRANSACTION_FIELDS")
            require(row["user_id"] == actor and row["type"] == "expense" and row["currency"] == currency and
                    equal(row["amount"], amount) and equal(row["exchange_rate_eur"], "1") and row["category_id"] is None and
                    row["description"] == marker + "-" + label, "TRANSACTION_PAYLOAD")
            dt.date.fromisoformat(row["date"])
    for label, actor, tx, mode, amount in (("equal_share", A, "equal_tx", "equal", None), ("custom_share", B, "custom_tx", "custom", "2.01")):
        if label in operations:
            row = operations[label]["expected"]
            require(set(row) == {"id", "connection_id", "original_tx_id", "created_by", "couple_category_name", "split_mode", "partner_amount"}, "SHARE_FIELDS")
            require(row["connection_id"] == value.get("connection_id") and row["created_by"] == actor and
                    row["original_tx_id"] == operations[tx]["id"] and row["couple_category_name"] == marker and
                    row["split_mode"] == mode and equal(row["partner_amount"], amount), "SHARE_PAYLOAD")
    if "budget" in operations:
        row = operations["budget"]["expected"]
        require(set(row) == {"id", "connection_id", "couple_category_name", "amount", "currency", "month", "year", "created_by"}, "BUDGET_FIELDS")
        require(row["connection_id"] == value.get("connection_id") and row["created_by"] == A and row["couple_category_name"] == marker and
                row["currency"] == "EUR" and row["month"] == 1 and row["year"] == 2000 and
                equal(row["amount"], "3"), "BUDGET_PAYLOAD")
    return value


def cas(row, quoted_legacy=False):
    result = {}
    for key, value in row.items():
        require(re.fullmatch(r"[a-z_]+", key), "CAS_FIELD")
        if isinstance(value, (dict, list)):
            # Immutable audit metadata is validated before deletion; actor/action/target/time stay in CAS.
            require(key == "metadata", "UNEXPECTED_STRUCTURED_FIELD")
            continue
        if value is None:
            result[key] = "is.null"
        elif isinstance(value, bool):
            result[key] = "eq." + str(value).lower()
        elif isinstance(value, (int, Decimal)):
            result[key] = "eq." + str(value)
        else:
            # Standalone eq takes a raw value; urlencode encodes its reserved characters.
            # Quotes only have syntactic meaning inside in/and/or (PostgREST issue #1943).
            result[key] = ('eq."' + str(value).replace("\\", "\\\\").replace('"', '\\"') + '"') if quoted_legacy else "eq." + str(value)
    require("id" in result, "CAS_ID_REQUIRED")
    return result


def safe(value):
    if isinstance(value, dict):
        require(not any(any(s in key.lower() for s in ("token", "password", "secret")) for key in value), "SECRET_REPORT_REJECTED")
        for child in value.values():
            safe(child)
    elif isinstance(value, list):
        for child in value:
            safe(child)
    return value


def completed_local_result(result, ledger, flags):
    """Accept an already completed exact-run result after an uncertain instrumentation reply."""
    validate_ledger(result, ledger["run_id"])
    require(flags.get("run_id") == ledger["run_id"], "FINAL_RESULT_SCOPE")
    require(all(result.get(key) == ledger[key] == flags.get(key) for key in ("project_host", "session_a", "session_b")), "FINAL_RESULT_SCOPE")
    require(all(flags.get(key) is True for key in ("records_absent", "logout_a", "logout_b")), "HOST_FINALIZATION_NOT_CONFIRMED")
    require(result.get("stage") == "host_finalized" and result.get("cleanup_complete") is True and
            result.get("cleanup_pending") is False, "FINAL_RESULT_NOT_COMPLETE")
    return result


class Cleanup:
    def __init__(self, directory, public, admin, ledger, rejected_cas_key=None):
        self.directory, self.public, self.admin, self.ledger = directory, public, admin, ledger
        self.path = directory / "cleanup-journal.json"
        self.state = json.loads(self.path.read_text()) if self.path.exists() else {"run_id": ledger["run_id"], "attempts": {}, "removed": []}
        require(self.state.get("run_id") == ledger["run_id"], "HOST_JOURNAL_SCOPE")
        self.rejected_cas_key = rejected_cas_key

    def outcome(self, key, recovery, **fields):
        field = "recovery_outcomes" if recovery else "delete_outcomes"
        self.state.setdefault(field, {})[key] = fields
        atomic(self.path, self.state)

    def get(self, table, **where):
        values = http(query(table, select="*", limit="100", **where), self.admin, self.admin)
        require(isinstance(values, list) and len(values) < 100, "CLEANUP_READ_NOT_BOUNDED")
        return values

    def remove(self, table, expected):
        key = table + ":" + uid(expected["id"])
        recovery = key in self.state["attempts"]
        if recovery and self.rejected_cas_key == key:
            # Only this historic request was independently proven to use the broken quoted serializer.
            # A legacy GET failing today says nothing about any future raw-eq DELETE having committed.
            require(self.ledger["run_id"] == LEGACY_CAS_RUN and key == LEGACY_CAS_KEY and
                    key not in self.state.get("serialization_versions", {}), "LEGACY_RECOVERY_SCOPE")
        current = self.get(table, id="eq." + expected["id"])
        if not current:
            if key not in self.state["removed"]:
                self.state["removed"].append(key); atomic(self.path, self.state)
            return
        require(len(current) == 1 and equal(current[0], expected), "CLEANUP_ROW_CHANGED")
        if recovery:
            require(self.rejected_cas_key == key and equal(self.state["attempts"][key], expected), "DELETE_ALREADY_ATTEMPTED_ROW_STILL_PRESENT")
            require(key not in self.state.get("recovery_attempts", {}), "RECOVERY_DELETE_ALREADY_ATTEMPTED")
            # A distinct explicit recovery may only repair the proven legacy serialization rejection.
            # Neither a zero-row GET nor an uncertain original response is sufficient authorization.
            rejected = None
            try:
                self.get(table, **cas(expected, quoted_legacy=True))
            except HttpOutcomeError as error:
                rejected = error
            require(rejected is not None and rejected.status == 400 and rejected.server_code == "22P02", "LEGACY_CAS_REJECTION_NOT_PROVEN")
            matching = self.get(table, **cas(expected))
            require(len(matching) == 1 and equal(matching[0], expected), "CORRECTED_CAS_READ_NOT_EXACT")
            self.state.setdefault("recovery_attempts", {})[key] = {
                "expected": expected, "legacy_get_status": 400, "legacy_server_code": "22P02", "corrected_get_exact": True,
            }
        else:
            self.state["attempts"][key] = expected
            self.state.setdefault("serialization_versions", {})[key] = "raw_eq_v1"
        atomic(self.path, self.state)  # durable before DELETE; no blind retry after uncertainty
        try:
            response, response_status = http(query(table, **cas(expected)), self.admin, self.admin, "DELETE", prefer="return=representation", with_status=True)
        except HttpOutcomeError as error:
            self.outcome(key, recovery, status=error.status, server_code=error.server_code, code=error.code,
                         definitive_rejection=error.status is not None and 400 <= error.status < 500 and error.status != 408)
            raise
        except Exception:
            self.outcome(key, recovery, status=None, server_code=None, code="DELETE_OUTCOME_UNCERTAIN", definitive_rejection=False)
            raise
        self.outcome(key, recovery, status=response_status, server_code=None, code="DELETE_RESPONSE_RECEIVED", definitive_rejection=False)
        require(isinstance(response, list) and len(response) == 1 and equal(response[0], expected), "DELETE_RESPONSE_UNCERTAIN")
        require(not self.get(table, id="eq." + expected["id"]), "DELETE_ABSENCE_UNCONFIRMED")
        self.state["removed"].append(key); atomic(self.path, self.state)

    def discover_connection(self):
        l = self.ledger; operations = l["operations"]
        request = operations.get("invitation")
        if not request:
            require(not self.get("couple_connections", user_a="eq." + A, user_b="eq." + B), "UNREGISTERED_CONNECTION")
            return None
        rows = self.get("couple_connection_requests", id="eq." + request["id"])
        require(len(rows) <= 1 and (not rows or rows[0]["sender_id"] == A and rows[0]["receiver_id"] == B), "REQUEST_SCOPE")
        connections = self.get("couple_connections", user_a="eq." + A, user_b="eq." + B)
        if not connections:
            if l.get("connection_id"):
                key = "couple_connections:" + uid(l["connection_id"])
                require(key in self.state["attempts"], "ACCEPTED_CONNECTION_MISSING")
                previous = self.state["attempts"][key]
                require(previous["id"] == l["connection_id"] and previous["user_a"] == A and previous["user_b"] == B and previous.get("revoked_at"), "REMOVED_CONNECTION_SCOPE")
                return previous
            require(not rows or rows[0]["status"] != "accepted", "ACCEPTED_CONNECTION_MISSING")
            return None
        require(len(connections) == 1 and l.get("empty_pair_baseline") and "acceptance" in operations and rows and rows[0]["status"] == "accepted", "CONNECTION_NOT_BOUND_TO_REQUEST")
        connection = connections[0]; uid(connection["id"])
        require(not l.get("connection_id") or l["connection_id"] == connection["id"], "CONNECTION_ID_CHANGED")
        start = dt.datetime.fromisoformat(l["started_at"].replace("Z", "+00:00"))
        created = dt.datetime.fromisoformat(connection["created_at"].replace("Z", "+00:00"))
        require(start - dt.timedelta(seconds=60) <= created <= dt.datetime.now(dt.timezone.utc) + dt.timedelta(seconds=60), "CONNECTION_OUTSIDE_RUN_WINDOW")
        return connection

    def records(self, connection):
        records = []
        ops = self.ledger["operations"]
        for label in ("equal_share", "custom_share", "budget", "equal_tx", "custom_tx", "invitation"):
            if label not in ops:
                continue
            op = ops[label]
            rows = self.get(op["table"], id="eq." + op["id"])
            if not rows:
                continue
            require(len(rows) == 1, "UUID_NOT_UNIQUE")
            row = rows[0]; candidates = [op["expected"]]
            if label == "budget" and "budget_change" in ops:
                candidates += [{**op["expected"], "amount": "4.00"}, {**op["expected"], "amount": "3.00"}]
            if label == "equal_tx" and "denied_private_update" in ops:
                candidates += [{**op["expected"], "description": self.ledger["marker"] + "-denied"}]
            if label == "invitation":
                require(row.get("status") in ("pending", "accepted"), "REQUEST_STATUS_CHANGED")
            require(any(matches(row, body) for body in candidates), "REGISTERED_PAYLOAD_CHANGED")
            records.append((op["table"], row))
        if connection:
            require(connection.get("revoked_at") is not None, "REVOCATION_REQUIRED_BEFORE_CLEANUP")
            known_shares = {ops[k]["id"] for k in ("equal_share", "custom_share") if k in ops}
            known_budgets = {ops["budget"]["id"]} if "budget" in ops else set()
            require({r["id"] for r in self.get("shared_expenses", connection_id="eq." + connection["id"])} <= known_shares, "UNREGISTERED_SHARE_PRESERVED")
            require({r["id"] for r in self.get("couple_budgets", connection_id="eq." + connection["id"])} <= known_budgets, "UNREGISTERED_BUDGET_PRESERVED")
        return records

    def audits(self, connection):
        ops = self.ledger["operations"]
        request = ops.get("invitation")
        by_id = {}
        if request:
            for row in self.get("couple_audit_log", target_id="eq." + request["id"]):
                by_id[uid(row["id"])] = row
        if connection:
            for row in self.get("couple_audit_log", connection_id="eq." + connection["id"]):
                by_id[uid(row["id"])] = row
        start = dt.datetime.fromisoformat(self.ledger["started_at"].replace("Z", "+00:00"))
        for row in by_id.values():
            instant = dt.datetime.fromisoformat(row["created_at"].replace("Z", "+00:00"))
            require(start - dt.timedelta(seconds=60) <= instant <= dt.datetime.now(dt.timezone.utc) + dt.timedelta(seconds=60), "AUDIT_OUTSIDE_RUN_WINDOW")
            action = row["action"]
            expected = None
            if request and action == "request_sent":
                expected = (A, request["id"], None, {"sender_id": A, "receiver_id": B})
            elif request and connection and action == "request_accepted":
                expected = (B, request["id"], connection["id"], {"sender_id": A, "receiver_id": B})
            elif connection and action == "connection_revoked":
                expected = (A, connection["id"], connection["id"], {"user_a": A, "user_b": B})
            elif connection and action == "shared_expense_created":
                op = next((ops[k] for k in ("equal_share", "custom_share") if k in ops and ops[k]["id"] == row["target_id"]), None)
                if op:
                    expected = (op["actor"], op["id"], connection["id"], {"original_tx_id": op["expected"]["original_tx_id"], "couple_category_name": self.ledger["marker"]})
            require(expected is not None and (row["actor_id"], row["target_id"], row["connection_id"]) == expected[:3] and equal(row["metadata"], expected[3]), "UNREGISTERED_AUDIT_PRESERVED")
        return list(by_id.values())


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--execute", action="store_true", help="Explicit opt-in; default has no external actions")
    parser.add_argument("--recover", action="store_true")
    parser.add_argument("--run-id")
    parser.add_argument("--recover-rejected-cas", help="Explicit exact table:UUID repair after legacy CAS rejection is proven by readonly GETs")
    parser.add_argument("--device", choices=("emulator-5558",), default="emulator-5558")
    parser.add_argument("--avd", choices=("SavelyAndroid12", "SavelyTest"), default="SavelyAndroid12")
    args = parser.parse_args()
    require(args.execute, "EXPLICIT_EXECUTE_REQUIRED")
    run = uid(args.run_id) if args.run_id else str(uuid.uuid4())
    require(not args.recover or args.run_id, "RECOVERY_EXACT_RUN_REQUIRED")
    rejected_key = args.recover_rejected_cas
    if rejected_key:
        require(args.recover and ":" in rejected_key, "REJECTED_CAS_RECOVERY_OPT_IN")
        table, identifier = rejected_key.split(":", 1)
        require(table in ("shared_expenses", "couple_budgets", "transactions", "couple_audit_log", "couple_connections", "couple_connection_requests"), "REJECTED_CAS_TABLE_SCOPE")
        uid(identifier)
        require(run == LEGACY_CAS_RUN and rejected_key == LEGACY_CAS_KEY, "LEGACY_RECOVERY_SCOPE")
    out = ROOT / "artifacts/couple-qualification" / run
    if args.recover:
        require(out.is_dir(), "RECOVERY_EVIDENCE_REQUIRED")
    else:
        out.mkdir(parents=True, exist_ok=False)
    adb = Path(os.environ["LOCALAPPDATA"]) / "Android/Sdk/platform-tools/adb.exe"

    def device(*parts, data=None, timeout=90):
        response = subprocess.run([str(adb), "-s", args.device, *parts], input=data, capture_output=True, text=True, timeout=timeout)
        require(response.returncode == 0, "ADB_FAILED_OUTPUT_WITHHELD")
        return response.stdout

    def private(name):
        return json.loads(device("shell", "run-as", PACKAGE, "cat", "files/" + name), parse_float=Decimal)

    def transfer(name, value):
        device("shell", "run-as", PACKAGE, "sh", "-c", f"'set -C; cat > files/{name}'", data=dump(value))

    def instrumentation(method, flag, timeout=240):
        raw = device("shell", "am", "instrument", "-w", "-r", "-e", "class", CLASS + "#" + method,
                     "-e", flag, "true", "-e", "coupleRunId", run, PACKAGE + ".test/androidx.test.runner.AndroidJUnitRunner", timeout=timeout)
        log = out / (method + ".txt")
        if log.exists():
            log = out / (method + "-" + str(uuid.uuid4()) + ".txt")
        with log.open("x", encoding="utf-8") as output:
            output.write(raw)
        return "OK (1 test)" in raw

    bundle = None
    admin = None
    complete = False
    native_started = False
    transferred = False

    def local_finalize(ledger, flags):
        name = "couple-qualification-host-" + run + ".json"
        final_name = "couple-qualification-final-" + run + ".json"
        final_present = device("shell", "run-as", PACKAGE, "sh", "-c", f"'test -f files/{final_name} && echo present || true'")
        previous = validate_ledger(private(final_name), run) if "present" in final_present else None
        if previous is None or previous.get("cleanup_complete") is not True:
            present = device("shell", "run-as", PACKAGE, "sh", "-c", f"'test -f files/{name} && echo present || true'")
            if "present" in present:
                require(equal(private(name), flags), "EXISTING_ATTESTATION_PRESERVED")
            else:
                transfer(name, flags)
            require(instrumentation("clearExactPrivateRunAfterHostCleanup", "coupleLocalFinalize"), "LOCAL_CLEANUP_PENDING")
            previous = private(final_name)
        result = completed_local_result(previous, ledger, flags)
        leftovers = device("shell", "run-as", PACKAGE, "sh", "-c", "'for f in files/couple-qualification-session.json " +
                           f"files/couple-qualification-checkpoint-{run}.json files/couple-qualification-checkpoint-{run}.json.bak " +
                           f"files/couple-qualification-checkpoint-{run}.json.new files/{name}; do test ! -e \"$f\" || echo pending; done'")
        require("pending" not in leftovers, "LOCAL_FILES_RETAINED")
        atomic(out / "result.json", safe(result))
        return result

    try:
        require(device("emu", "avd", "name").splitlines()[0].strip() == args.avd, "WRONG_DEDICATED_AVD")
        installed = device("shell", "dumpsys", "package", PACKAGE)
        require("versionCode=10" in installed and "DEBUGGABLE" in installed, "DEBUG_CODE10_REQUIRED")
        settings = dict(line.split("=", 1) for line in (ROOT / "savely.properties").read_text().splitlines() if "=" in line)
        require(settings["SUPABASE_URL"].rstrip("/") == BASE, "PROJECT_CONFIGURATION")
        public = settings["SUPABASE_ANON_KEY"]
        final_path = out / "finalization.json"
        if args.recover and final_path.exists():
            flags = json.loads(final_path.read_text())
            if flags.get("records_absent") and flags.get("logout_a") and flags.get("logout_b"):
                native_started = True  # No bootstrap/session HTTP required for purely local finalization.
                ledger = validate_ledger(json.loads((out / "native-ledger.json").read_text()), run)
                require(flags.get("run_id") == run and flags.get("project_host") == HOST and
                        flags.get("session_a") == ledger["session_a"] and flags.get("session_b") == ledger["session_b"], "FINALIZATION_SCOPE")
                result = local_finalize(ledger, flags)
                complete = True
                require(ledger.get("revocation_privacy_verified") and not ledger.get("failures"), "NATIVE_QUALIFICATION_FAILED_CLEANUP_COMPLETE")
                print("Couple exact-run local finalization complete: " + str(out / "result.json"))
                return
        if args.recover:
            native_started = True  # Never generic-reset a recovering run that may already have written.
            bundle = private(INPUT)
            require(bundle["run_id"] == run and bundle["project_host"] == HOST, "BOOTSTRAP_RUN_SCOPE")
        else:
            require(instrumentation("preflightWithoutCredentials", "couplePreflight"), "PREFLIGHT_REFUSED_NO_LOGIN")
            credentials = load_credentials()
            accounts = {a["email"]: a for a in credentials["accounts"]}
            bundle = {"run_id": run, "project_host": HOST, "date": dt.date.today().isoformat()}
            for label, owner, email in (("a", A, A_EMAIL), ("b", B, B_EMAIL)):
                session = http("/auth/v1/token?grant_type=password", public, public, "POST", {"email": email, "password": accounts[email]["password"]})
                # Keep a returned exact-QA temporary session available for local logout even if later preflight fails.
                require(session.get("user", {}).get("id") == owner and session["user"].get("email") == email, "QA_IDENTITY")
                bundle[label] = session
                identity(session["user"], owner, email)
                session_id(session["access_token"])
            credentials = accounts = None
            transferred = True  # Transfer may have succeeded even if the ADB response is lost.
            transfer(INPUT, bundle)
            native_started = True
            instrumentation("qualifyNativePairAndRevocation", "realCoupleQualification", timeout=600)
        ledger = validate_ledger(private("couple-qualification-checkpoint-" + run + ".json"), run)
        require(bundle["run_id"] == run and session_id(bundle["a"]["access_token"]) == ledger["session_a"] and
                session_id(bundle["b"]["access_token"]) == ledger["session_b"], "SESSION_CHECKPOINT_SCOPE")
        atomic(out / "native-ledger.json", safe(ledger))
        # Admin secret is host RAM only. No account, password, permission, signup or schema endpoint is called.
        process = subprocess.run(["supabase", "projects", "api-keys", "--project-ref", PROJECT, "--reveal", "--output", "json"],
                                 capture_output=True, text=True, check=True)
        keys = json.loads(process.stdout)
        admin = next(k["api_key"] for k in keys if k["name"] == "service_role")
        process = keys = None
        cleanup = Cleanup(out, public, admin, ledger, rejected_key)
        if rejected_key:
            require(rejected_key in cleanup.state["attempts"] and rejected_key not in cleanup.state.get("removed", []), "REJECTED_CAS_RESERVED_ID_REQUIRED")
        connection = cleanup.discover_connection()
        if connection and connection.get("revoked_at") is None and args.recover:
            require(instrumentation("recoverExactRunRevocation", "coupleRecovery"), "EXACT_REVOCATION_RECOVERY_PENDING")
            ledger = validate_ledger(private("couple-qualification-checkpoint-" + run + ".json"), run)
            atomic(out / "native-ledger.json", safe(ledger))
            cleanup.ledger = ledger
            connection = cleanup.discover_connection()
        if connection:
            require(connection.get("revoked_at") is not None, "ACTIVE_CONNECTION_RETAINED_NO_BLIND_REVOKE_RETRY")
            for label, owner, email in (("a", A, A_EMAIL), ("b", B, B_EMAIL)):
                token = bundle[label]["access_token"]
                identity(http("/auth/v1/user", public, token), owner, email)
                for table in ("shared_expenses", "shared_expenses_view", "couple_budgets", "couple_audit_log"):
                    require(http(query(table, connection_id="eq." + connection["id"]), public, token) == [], "REVOKED_PRIVACY_FAILED")
        records = cleanup.records(connection)
        audits = cleanup.audits(connection)
        # Validate the entire deletion set before the first DELETE, then CAS each unchanged exact UUID.
        for table, record in records:
            if table in ("shared_expenses", "couple_budgets"):
                cleanup.remove(table, record)
        for record in audits:
            cleanup.remove("couple_audit_log", record)
        for table, record in records:
            if table == "transactions":
                cleanup.remove(table, record)
        if connection:
            cleanup.remove("couple_connections", connection)
        for table, record in records:
            if table == "couple_connection_requests":
                cleanup.remove(table, record)
        require(not cleanup.records(None) and not cleanup.audits(None), "RECORD_CLEANUP_PENDING")
        for key in cleanup.state["removed"]:
            table, identifier = key.split(":", 1)
            require(not cleanup.get(table, id="eq." + identifier), "ABSENCE_NOT_CONFIRMED")
        if connection:
            require(not cleanup.get("couple_connections", id="eq." + connection["id"]), "CONNECTION_CLEANUP_PENDING")
        flags = {"run_id": run, "project_host": HOST, "session_a": ledger["session_a"], "session_b": ledger["session_b"], "records_absent": True}
        flag_path = out / "finalization.json"
        if flag_path.exists():
            previous = json.loads(flag_path.read_text())
            require(all(previous.get(k) == v for k, v in flags.items()), "FINALIZATION_SCOPE")
            flags = previous
        atomic(flag_path, flags)
        for label in ("a", "b"):
            key = "logout_" + label
            if flags.get(key):
                continue
            require(not flags.get(key + "_attempted"), "LOGOUT_UNCERTAIN_DO_NOT_RETRY")
            flags[key + "_attempted"] = True; atomic(flag_path, flags)
            http("/auth/v1/logout?scope=local", public, bundle[label]["access_token"], "POST", {})
            flags[key] = True; atomic(flag_path, flags)
        local_finalize(ledger, flags)
        complete = True
        require(ledger.get("revocation_privacy_verified") and not ledger.get("failures"), "NATIVE_QUALIFICATION_FAILED_CLEANUP_COMPLETE")
        print("Couple qualification passed; exact-run cleanup verified: " + str(out / "result.json"))
    finally:
        if bundle and not native_started:
            # No native finance operation was dispatched. Revoke only tokens actually returned by this login.
            preliminary = {}
            for label in ("a", "b"):
                if label in bundle:
                    try:
                        http("/auth/v1/logout?scope=local", public, bundle[label]["access_token"], "POST", {})
                        preliminary[label] = True
                    except Exception:
                        preliminary[label] = False
            if preliminary:
                atomic(out / "pre_native_logout.json", preliminary)
            if transferred and preliminary and all(preliminary.values()):
                try:
                    saved = private(INPUT)
                    require(equal(saved, bundle), "PRIVATE_INPUT_PRESERVED")
                    device("shell", "run-as", PACKAGE, "rm", "files/" + INPUT)
                except Exception:
                    pass  # Report pending; never claim removal or clear another session's private input.
        atomic(out / "host.json", {"run_id": run, "project_host": HOST, "device": args.device,
                                   "cleanup_complete": complete, "cleanup_pending": not complete})
        bundle = admin = None


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        # Never print exception details, HTTP response bodies or captured command output.
        code = error.code if isinstance(error, QualificationError) else type(error).__name__
        print("Couple qualification stopped (" + code + "); inspect exact-run evidence. Secrets withheld.")
        raise SystemExit(1)
