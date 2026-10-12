"""Opt-in read-only A/B REST observations; only owned login/local logout change Auth state.

Usage: python tools/couple_role_observation.py RUN LABEL EXPECTEDJSONFILE
The descriptor must exist inside artifacts/couple-form-ui/RUN:
 {"version":1,"run_id":"UUID","connection":{"id":"UUID","user_a":"QA A","user_b":"QA B"},
  "originals":[{"id":"UUID","owner_id":"QA A"}],
  "queries":[{"label":"eur-share","table":"shared_expenses_view","id":"UUID",
    "expected":{"a":[{"id":"UUID","connection_id":"UUID","created_by":"QA A",
                         "original_tx_id":"UUID","my_share_amount":"5.01"}],"b":[]}}]}
Request queries additionally require sender_id/receiver_id. Expected rows are [] or
one known field subset; identity/scope fields are mandatory. There are no free SQL,
query strings, mutations, retries, refresh tokens, or management credentials.
"""
import argparse
import base64
import datetime as dt
from decimal import Decimal, InvalidOperation
import importlib.util
import json
import os
from pathlib import Path
import re
import uuid

from qa_credentials import load_credentials, PROJECT, QA_USERS

ROOT = Path(__file__).resolve().parent.parent
EMAILS = {actor: f"android-qa-5ef2838c-{actor}@example.invalid" for actor in ("a", "b")}
OWNERS = {actor: QA_USERS[email] for actor, email in EMAILS.items()}
PAIR = set(OWNERS.values())
FIELDS = {
    "shared_expenses": "id connection_id original_tx_id created_by couple_category_name split_mode partner_amount split_percentage created_at updated_at",
    "shared_expenses_view": "id connection_id original_tx_id created_by couple_category_name split_mode partner_amount split_percentage created_at updated_at total_amount partner_share_amount creator_share_amount my_share_amount currency exchange_rate_eur description date tx_deleted_at",
    "couple_budgets": "id connection_id couple_category_name amount currency month year created_by created_at updated_at",
    "transactions": "id user_id type amount currency exchange_rate_eur category_id description date source notes created_at updated_at deleted_at",
    "couple_connections": "id user_a user_b created_at revoked_at",
    "couple_connection_requests": "id sender_id receiver_id status created_at updated_at",
    "couple_audit_log": "id connection_id actor_id action target_id metadata created_at",
}
FIELDS = {table: set(fields.split()) for table, fields in FIELDS.items()}
NUMERIC = set("amount partner_amount split_percentage total_amount partner_share_amount creator_share_amount my_share_amount exchange_rate_eur month year".split())


class ObservationError(RuntimeError):
    pass


def need(condition, code):
    if not condition:
        raise ObservationError(code)


def uid(value):
    try:
        need(isinstance(value, str) and str(uuid.UUID(value)) == value, "UUID_INVALID")
    except (ValueError, TypeError, AttributeError):
        raise ObservationError("UUID_INVALID") from None
    return value


def label(value):
    need(isinstance(value, str) and re.fullmatch(r"(?:active|post-removal|post-revoke)(?:-[a-z0-9]{1,24})?", value), "LABEL_INVALID")
    return value


def exclusive(path, value):
    # A reservation surviving interruption blocks replay of that observation label.
    with path.open("x", encoding="utf-8") as output:
        output.write(json.dumps(value, ensure_ascii=False, sort_keys=True, default=str) + "\n")
        output.flush()
        os.fsync(output.fileno())


def paths(run, expected_file, root=ROOT):
    uid(run)
    base = (root / "artifacts/couple-form-ui").resolve()
    directory = (base / run).resolve()
    need(directory.parent == base and directory.name == run and directory.is_dir(), "RUN_DIRECTORY_INVALID")
    source = Path(expected_file).resolve()
    need(source.is_relative_to(directory) and source.is_file(), "DESCRIPTOR_OUTSIDE_RUN")
    return directory, source


def unique_object(pairs):
    value = {}
    for key, item in pairs:
        need(key not in value, "DUPLICATE_JSON_KEY")
        value[key] = item
    return value


def read_descriptor(source):
    with source.open("rb") as stream:
        raw = stream.read(262145)
    need(len(raw) <= 262144, "DESCRIPTOR_TOO_LARGE")
    return json.loads(raw.decode("utf-8"), parse_float=Decimal,
                      object_pairs_hook=unique_object,
                      parse_constant=lambda _: (_ for _ in ()).throw(ObservationError("NONFINITE_JSON")))


def nonsecret_metadata(value, depth=0):
    need(depth <= 8, "METADATA_DEPTH")
    if isinstance(value, dict):
        for key, item in value.items():
            need(isinstance(key, str) and not any(word in key.lower() for word in
                 ("password", "token", "secret", "authorization", "private_key")), "METADATA_SECRET_FIELD")
            nonsecret_metadata(item, depth + 1)
    elif isinstance(value, list):
        for item in value:
            nonsecret_metadata(item, depth + 1)


def validate(value, run):
    need(isinstance(value, dict) and set(value) == {"version", "run_id", "connection", "originals", "queries"}, "DESCRIPTOR_FIELDS")
    need(type(value["version"]) is int and value["version"] == 1 and value["run_id"] == uid(run), "DESCRIPTOR_RUN")
    connection = value["connection"]
    need(isinstance(connection, dict) and set(connection) == {"id", "user_a", "user_b"}, "CONNECTION_FIELDS")
    uid(connection["id"])
    need({connection["user_a"], connection["user_b"]} == PAIR, "CONNECTION_NOT_QA_PAIR")
    originals = value["originals"]
    need(isinstance(originals, list) and len(originals) <= 32, "ORIGINALS_LIMIT")
    original_owners = {}
    for row in originals:
        need(isinstance(row, dict) and set(row) == {"id", "owner_id"}, "ORIGINAL_FIELDS")
        key = uid(row["id"])
        need(key not in original_owners and row["owner_id"] in PAIR, "ORIGINAL_OWNER")
        original_owners[key] = row["owner_id"]
    queries = value["queries"]
    need(isinstance(queries, list) and 1 <= len(queries) <= 32, "QUERY_LIMIT")
    labels = set()
    for query in queries:
        need(isinstance(query, dict), "QUERY_INVALID")
        table = query.get("table")
        need(isinstance(table, str) and table in FIELDS, "TABLE_NOT_ALLOWED")
        keys = {"label", "table", "id", "expected"}
        if table == "couple_connection_requests":
            keys |= {"sender_id", "receiver_id"}
        need(set(query) == keys, "QUERY_FIELDS")
        name = query["label"]
        need(isinstance(name, str) and re.fullmatch(r"[a-z][a-z0-9-]{0,47}", name) and name not in labels, "PROBE_LABEL_INVALID")
        labels.add(name)
        uid(query["id"])
        if table == "transactions":
            need(query["id"] in original_owners, "ORIGINAL_NOT_REGISTERED")
        if table == "couple_connections":
            need(query["id"] == connection["id"], "CONNECTION_ID_CHANGED")
        if table == "couple_connection_requests":
            need({query["sender_id"], query["receiver_id"]} == PAIR, "REQUEST_NOT_QA_PAIR")
        expected = query["expected"]
        need(isinstance(expected, dict) and set(expected) == {"a", "b"}, "EXPECTED_ROLES")
        for actor, rows in expected.items():
            need(isinstance(rows, list) and len(rows) <= 1, "EXPECTED_ROW_LIMIT")
            for row in rows:
                need(isinstance(row, dict) and set(row) <= FIELDS[table] and row.get("id") == query["id"], "EXPECTED_FIELDS_OR_ID")
                if "metadata" in row:
                    nonsecret_metadata(row["metadata"])
                if table in {"shared_expenses", "shared_expenses_view", "couple_budgets", "couple_audit_log"}:
                    need(row.get("connection_id") == connection["id"], "EXPECTED_CONNECTION")
                if table in {"shared_expenses", "shared_expenses_view"}:
                    original = row.get("original_tx_id")
                    need(isinstance(original, str) and original in original_owners and row.get("created_by") == original_owners[original], "EXPECTED_SHARE_OWNER")
                if table == "couple_budgets":
                    need(row.get("created_by") in PAIR, "EXPECTED_BUDGET_OWNER")
                if table == "couple_audit_log":
                    need(row.get("actor_id") in PAIR, "EXPECTED_AUDIT_ACTOR")
                if table == "transactions":
                    owner = original_owners[query["id"]]
                    need(owner == OWNERS[actor] and row.get("user_id") == owner, "PRIVATE_TRANSACTION_EXPECTED_FOR_WRONG_ROLE")
                if table == "couple_connections":
                    need(row.get("user_a") == connection["user_a"] and row.get("user_b") == connection["user_b"], "EXPECTED_PARTICIPANTS")
                if table == "couple_connection_requests":
                    need(row.get("sender_id") == query["sender_id"] and row.get("receiver_id") == query["receiver_id"], "EXPECTED_REQUEST_PARTICIPANTS")
                for field in NUMERIC & row.keys():
                    if row[field] is not None:
                        numeric_equal(row[field], row[field])
                for field, item in row.items():
                    if field.endswith("_at") and item is not None:
                        equal_field(field, item, item)
                    if field == "date" and item is not None:
                        need(isinstance(item, str) and dt.date.fromisoformat(item).isoformat() == item, "DATE_INVALID")
    return value


def numeric_equal(left, right):
    need(not isinstance(left, bool) and not isinstance(right, bool), "NUMERIC_BOOLEAN")
    try:
        a, b = Decimal(str(left)), Decimal(str(right))
        need(a.is_finite() and b.is_finite(), "NUMERIC_NONFINITE")
        return a == b
    except InvalidOperation:
        raise ObservationError("NUMERIC_INVALID") from None


def equal_field(field, actual, expected):
    if actual is None or expected is None:
        return actual is None and expected is None
    if field in NUMERIC:
        return numeric_equal(actual, expected)
    if field.endswith("_at"):
        def instant(value):
            need(isinstance(value, str), "TIME_INVALID")
            parsed = dt.datetime.fromisoformat(value.replace("Z", "+00:00"))
            need(parsed.tzinfo is not None, "TIMEZONE_REQUIRED")
            return parsed
        return instant(actual) == instant(expected)
    if isinstance(actual, dict) and isinstance(expected, dict):
        return actual.keys() == expected.keys() and all(equal_field(key, actual[key], expected[key]) for key in actual)
    if isinstance(actual, list) and isinstance(expected, list):
        return len(actual) == len(expected) and all(equal_field("", left, right) for left, right in zip(actual, expected))
    return type(actual) is type(expected) and actual == expected


def query_path(transport, descriptor, query, actor):
    table = query["table"]
    fields = {"id"}
    for rows in query["expected"].values():
        for row in rows:
            fields.update(row)
    where = {"select": ",".join(sorted(fields)), "id": "eq." + query["id"], "limit": "2"}
    if table in {"shared_expenses", "shared_expenses_view", "couple_budgets", "couple_audit_log"}:
        where["connection_id"] = "eq." + descriptor["connection"]["id"]
        owner_field = "actor_id" if table == "couple_audit_log" else "created_by"
        where["or"] = f"({owner_field}.eq.{OWNERS['a']},{owner_field}.eq.{OWNERS['b']})"
    elif table == "transactions":
        owner = next(row["owner_id"] for row in descriptor["originals"] if row["id"] == query["id"])
        where["user_id"] = "eq." + owner
    elif table == "couple_connections":
        where["or"] = f"(user_a.eq.{OWNERS[actor]},user_b.eq.{OWNERS[actor]})"
    elif table == "couple_connection_requests":
        where.update(sender_id="eq." + query["sender_id"], receiver_id="eq." + query["receiver_id"])
    return transport.query(table, **where)


def observe(directory, phase, descriptor, public, transport, credentials=load_credentials):
    phase = label(phase)
    validate(descriptor, directory.name)
    prefix = "role-" + phase
    def record(name, value):
        exclusive(directory / (prefix + "-" + name + ".json"), value)
    record("reservation", {"run_id": descriptor["run_id"], "label": phase,
                           "scope": "two-QA-host-REST-only-login-local-logout", "queries": descriptor["queries"]})
    sessions, accounts, probes, logouts = {}, {}, [], {}
    failure, login_uncertain = None, False
    try:
        accounts = {row["email"]: row for row in credentials()["accounts"]}
        for actor, email in EMAILS.items():
            record("login-" + actor + "-reservation", {"owner": OWNERS[actor], "method": "POST", "path": "/auth/v1/token?grant_type=password"})
            login_uncertain = True
            session = transport.http("/auth/v1/token?grant_type=password", public, public, "POST",
                                     {"email": email, "password": accounts[email]["password"]})
            need(isinstance(session, dict) and isinstance(session.get("user"), dict) and
                 session["user"].get("id") == OWNERS[actor] and session["user"].get("email") == email,
                 "LOGIN_IDENTITY_UNCERTAIN")
            token = session.get("access_token")
            need(isinstance(token, str) and 0 < len(token) <= 16384, "LOGIN_TOKEN_UNCERTAIN")
            session_uuid = uid(transport.session_id(token))
            claims = json.loads(base64.urlsafe_b64decode(token.split(".")[1] + "==="))
            need(claims.get("sub") == OWNERS[actor], "JWT_OWNER_MISMATCH")
            sessions[actor] = {"token": token, "id": session_uuid}
            login_uncertain = False
            user = session["user"]
            session.clear()
            record("session-" + actor, {"owner": OWNERS[actor], "session_id": session_uuid})
            transport.identity(user, OWNERS[actor], email)
            transport.identity(transport.http("/auth/v1/user", public, token), OWNERS[actor], email)
            for query in descriptor["queries"]:
                path = query_path(transport, descriptor, query, actor)
                probe = {"actor": actor, "table": query["table"], "id": query["id"], "label": query["label"], "matched": False}
                probes.append(probe)
                rows = transport.http(path, public, token)
                need(isinstance(rows, list) and len(rows) <= 2, "REST_ROWS_INVALID")
                expected = query["expected"][actor]
                probe["row_count"] = len(rows)
                need(len(rows) == len(expected), "REST_ROW_COUNT_MISMATCH")
                for actual, wanted in zip(rows, expected):
                    need(isinstance(actual, dict) and all(field in actual and equal_field(field, actual[field], value)
                         for field, value in wanted.items()), "REST_EXPECTATION_MISMATCH")
                # Persist only matching explicitly expected fields, never unexpected/private server data.
                probe.update(matched=True, observed=[{key: actual[key] for key in wanted} for actual, wanted in zip(rows, expected)])
                record("probe-" + actor + "-" + query["label"], probe)
    except Exception as error:
        code = getattr(error, "code", None) or (str(error) if isinstance(error, ObservationError) else None)
        failure = code if isinstance(code, str) and re.fullmatch(r"[A-Z][A-Z0-9_]{1,100}", code) else "OBSERVATION_FAILED_DETAILS_WITHHELD"
    finally:
        for actor, session in sessions.items():
            try:
                record("logout-" + actor + "-reservation", {"owner": OWNERS[actor], "session_id": session["id"],
                       "method": "POST", "path": "/auth/v1/logout?scope=local", "scope": "own-returned-login-only"})
                transport.http("/auth/v1/logout?scope=local", public, session["token"], "POST", {})
                logouts[actor] = True
                record("logout-" + actor + "-result", {"owner": OWNERS[actor], "session_id": session["id"], "passed": True})
            except Exception:
                logouts[actor] = False
        cleanup_pending = login_uncertain or any(not logouts.get(actor, False) for actor in sessions)
        passed = failure is None and len(probes) == 2 * len(descriptor["queries"]) and not cleanup_pending
        result = {"run_id": descriptor["run_id"], "label": phase, "status": "passed" if passed else "failed",
                  "failure_code": failure, "probes": probes, "own_session_ids": {actor: value["id"] for actor, value in sessions.items()},
                  "own_local_logout": logouts, "cleanup_pending": cleanup_pending,
                  "scope": "host-REST-QA-role-reads-not-native-UI-or-global-server-session-count"}
        sessions.clear()
        accounts.clear()
        record("result", result)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("run")
    parser.add_argument("label")
    parser.add_argument("expected_json_file")
    args = parser.parse_args()
    try:
        directory, source = paths(args.run, args.expected_json_file)
        label(args.label)
        descriptor = validate(read_descriptor(source), args.run)
        spec = importlib.util.spec_from_file_location("couple_observation_http", ROOT / "tools/run-couple-qualification.py")
        transport = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(transport)
        properties = dict(line.split("=", 1) for line in (ROOT / "savely.properties").read_text().splitlines() if "=" in line)
        need(properties["SUPABASE_URL"].rstrip("/") == transport.BASE, "PROJECT_HOST_MISMATCH")
        public = properties["SUPABASE_ANON_KEY"]
        result = observe(directory, args.label, descriptor, public, transport)
        print("QA role observation " + result["status"] + "; credentials and response errors withheld.")
        return 0 if result["status"] == "passed" else 2
    except Exception:
        print("QA role observation refused or failed; details withheld. Inspect only the reserved nonsecret journal.")
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
