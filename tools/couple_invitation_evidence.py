"""Opt-in host evidence for release UI invitations. No login, email or configuration writes."""
import argparse
import datetime as dt
import json
import os
from pathlib import Path
import re
import uuid
from urllib.error import HTTPError
from urllib.request import HTTPRedirectHandler, Request, build_opener

from qa_credentials import PROJECT, QA_RUN, QA_USERS

A_EMAIL = "android-qa-5ef2838c-a@example.invalid"
B_EMAIL = "android-qa-5ef2838c-b@example.invalid"
A, B = QA_USERS[A_EMAIL], QA_USERS[B_EMAIL]
QA = tuple(QA_USERS.values())
ROOT = Path(__file__).resolve().parent.parent / "artifacts/couple-invitation-ui"
REQUEST_FIELDS = {"id", "sender_id", "receiver_id", "status", "created_at", "updated_at"}
AUDIT_FIELDS = {"id", "connection_id", "actor_id", "action", "target_id", "metadata", "created_at"}
PAIR = {"first": (A, B, "rejected", B), "reverse": (B, A, "cancelled", B)}


class EvidenceError(RuntimeError):
    def __init__(self, code):
        super().__init__(code if re.fullmatch(r"[A-Z][A-Z0-9_]{1,80}", code) else "FAILURE_WITHHELD")


def need(ok, code):
    if not ok:
        raise EvidenceError(code)


def canonical(value):
    try:
        need(isinstance(value, str) and str(uuid.UUID(value)) == value, "UUID_INVALID")
        return value
    except (ValueError, TypeError, AttributeError):
        raise EvidenceError("UUID_INVALID") from None


def instant(value):
    try:
        result = dt.datetime.fromisoformat(value.replace("Z", "+00:00"))
        need(result.tzinfo is not None, "TIMEZONE_REQUIRED")
        return result
    except (ValueError, TypeError, AttributeError):
        raise EvidenceError("TIMESTAMP_INVALID") from None


def literal(value):
    return "'" + value.replace("'", "''") + "'"


QA_SQL = ",".join(literal(value) + "::uuid" for value in QA)
# Fixed SELECT; auth credentials/metadata outside the three approved IDs are never returned.
SNAPSHOT_SQL = f"""select jsonb_build_object(
 'now',now(),
 'users',(select coalesce(jsonb_agg(jsonb_build_object('id',id,'email',email,
   'confirmed',email_confirmed_at is not null,'qa_run',raw_user_meta_data->>'savely_android_qa_run') order by id),'[]'::jsonb)
   from auth.users where id in ({QA_SQL})),
 'profiles',(select coalesce(jsonb_agg(jsonb_build_object('user_id',user_id,'couple_code',couple_code,
   'permissions',permissions) order by user_id),'[]'::jsonb) from public.profiles where user_id in ('{A}'::uuid,'{B}'::uuid)),
 'sessions',(select count(*) from auth.sessions where user_id in ({QA_SQL}) and (not_after is null or not_after>now())),
 'factors',(select count(*) from auth.mfa_factors where user_id in ({QA_SQL})),
 'registrations',(select value from public.app_config where key='registrations_enabled'),
 'transactions',(select count(*) from public.transactions where user_id in ({QA_SQL})),
 'connections',(select count(*) from public.couple_connections where user_a in ({QA_SQL}) or user_b in ({QA_SQL})),
 'request_count',(select count(*) from public.couple_connection_requests where sender_id in ({QA_SQL}) or receiver_id in ({QA_SQL})),
 'requests',(select coalesce(jsonb_agg(to_jsonb(t) order by id),'[]'::jsonb) from
   (select * from public.couple_connection_requests where sender_id in ({QA_SQL}) or receiver_id in ({QA_SQL}) order by id limit 101) t),
 'audit_count',(select count(*) from public.couple_audit_log where actor_id in ({QA_SQL})),
 'audits',(select coalesce(jsonb_agg(to_jsonb(t) order by id),'[]'::jsonb) from
   (select * from public.couple_audit_log where actor_id in ({QA_SQL}) order by id limit 101) t)
) as snapshot"""


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, message, headers, url):
        return None


class Management:
    def __init__(self, token):
        self.token = token

    def __call__(self, sql, read_only=True):
        request = Request(f"https://api.supabase.com/v1/projects/{PROJECT}/database/query",
            data=json.dumps({"query": sql, "read_only": read_only}).encode(), method="POST",
            headers={"Authorization": "Bearer " + self.token, "Content-Type": "application/json"})
        try:
            with build_opener(NoRedirect()).open(request, timeout=30) as response:
                need(response.status in (200, 201), "MANAGEMENT_STATUS_INVALID")
                raw = response.read(262145)
            need(len(raw) <= 262144, "MANAGEMENT_RESPONSE_TOO_LARGE")
            rows = json.loads(raw)
            need(isinstance(rows, list), "MANAGEMENT_RESPONSE_INVALID")
            return rows
        except HTTPError as error:
            raise EvidenceError("MANAGEMENT_HTTP_" + str(error.code)) from None
        except EvidenceError:
            raise
        except Exception:
            raise EvidenceError("MANAGEMENT_OUTCOME_UNCERTAIN") from None


def exclusive(path, value):
    # An incomplete reservation is retained and blocks replay, including after host interruption.
    need(not path.exists(), "EVIDENCE_ALREADY_EXISTS")
    with path.open("x", encoding="utf-8") as output:
        output.write(json.dumps(value, sort_keys=True, separators=(",", ":")) + "\n")
        output.flush()
        os.fsync(output.fileno())


class Evidence:
    def __init__(self, run, executor, root=ROOT):
        self.run = canonical(run)
        self.directory = root / run / "evidence"
        self.execute = executor

    def path(self, name):
        need(re.fullmatch(r"[a-z-]+", name) is not None, "EVIDENCE_NAME_INVALID")
        return self.directory / (name + ".json")

    def save(self, name, value):
        exclusive(self.path(name), {"version": 1, "run_id": self.run, "project": PROJECT,
            "qa_run": QA_RUN, "user_a": A, "user_b": B, **value})

    def read(self, name):
        try:
            with self.path(name).open("rb") as source:
                raw = source.read(262145)
            need(len(raw) <= 262144, "EVIDENCE_TOO_LARGE")
            value = json.loads(raw)
            need(all(value.get(k) == v for k, v in {"version": 1, "run_id": self.run,
                "project": PROJECT, "qa_run": QA_RUN, "user_a": A, "user_b": B}.items()), "EVIDENCE_SCOPE_INVALID")
            return value
        except EvidenceError:
            raise
        except Exception:
            raise EvidenceError("EVIDENCE_INVALID_OR_MISSING") from None

    def snapshot(self):
        rows = self.execute(SNAPSHOT_SQL, read_only=True)
        need(len(rows) == 1 and isinstance(rows[0].get("snapshot"), dict), "SNAPSHOT_INVALID")
        snapshot = rows[0]["snapshot"]
        instant(snapshot["now"])
        need(isinstance(snapshot.get("users"), list) and len(snapshot["users"]) == 3, "QA_IDENTITY_COUNT")
        need({u.get("email"): u.get("id") for u in snapshot["users"]} == QA_USERS and
            all(u.get("confirmed") is True and u.get("qa_run") == QA_RUN for u in snapshot["users"]), "QA_IDENTITY_INVALID")
        profiles = snapshot.get("profiles")
        need(isinstance(profiles, list) and len(profiles) == 2 and {p.get("user_id") for p in profiles} == {A, B}, "PROFILE_SCOPE_INVALID")
        for profile in profiles:
            code, permissions = profile.get("couple_code"), profile.get("permissions")
            need(isinstance(code, str) and len(code) == 8 and all(c in "ABCDEFGHJKMNPQRSTUVWXYZ23456789" for c in code), "COUPLE_CODE_INVALID")
            need(isinstance(permissions, dict) and permissions.get("couple_expenses") is True and permissions.get("admin") is not True, "COUPLE_PERMISSION_INVALID")
        need(profiles[0]["couple_code"] != profiles[1]["couple_code"], "COUPLE_CODE_COLLISION")
        need(snapshot.get("registrations") == "false" and snapshot.get("factors") == 0 and
            snapshot.get("transactions") == 0 and snapshot.get("connections") == 0, "QA_BASELINE_CHANGED")
        need(type(snapshot.get("sessions")) is int and snapshot["sessions"] >= 0, "SESSION_COUNT_INVALID")
        for key, count in (("requests", "request_count"), ("audits", "audit_count")):
            need(isinstance(snapshot.get(key), list) and type(snapshot.get(count)) is int and
                snapshot[count] == len(snapshot[key]) and snapshot[count] <= 4, "UNEXPECTED_ROW_COUNT")
        return snapshot

    def unchanged_profiles(self, snapshot):
        base = self.read("preflight")["snapshot"]
        need(snapshot["profiles"] == base["profiles"] and snapshot["users"] == base["users"], "QA_PROFILE_CHANGED")
        need(instant(snapshot["now"]) >= instant(base["now"]), "DATABASE_CLOCK_REVERSED")

    def known(self):
        result = {}
        for leg in PAIR:
            terminal = self.path(leg + "-terminal")
            pending = self.path(leg + "-pending")
            if terminal.exists() or pending.exists():
                reservation = self.read(leg + "-reservation")
                before = self.read(leg + "-pending")
                self.validate_saved(leg, reservation, before, terminal=False)
                item = self.read(leg + "-terminal") if terminal.exists() else before
                if terminal.exists():
                    self.validate_saved(leg, reservation, item, terminal=True)
                    need(all(before["request"][key] == item["request"][key] for key in
                        ("id", "sender_id", "receiver_id", "created_at")), "SAVED_REQUEST_CHANGED")
                    need(next(a for a in item["audits"] if a["action"] == "request_sent") == before["audits"][0], "SAVED_SEND_AUDIT_CHANGED")
                    changed = next(a for a in item["audits"] if a["action"] != "request_sent")
                    need(instant(item["request"]["updated_at"]) >= instant(before["observed_at"]) and
                        instant(changed["created_at"]) >= instant(before["observed_at"]), "SAVED_TERMINAL_TIME_INVALID")
                prior = {key: value for key, value in result.items()}
                expected_requests = sorted([v["request"] for v in prior.values()], key=lambda r: r["id"])
                expected_audits = sorted([a for v in prior.values() for a in v["audits"]], key=lambda a: a["id"])
                need(sorted(reservation["baseline_requests"], key=lambda r: r["id"]) == expected_requests and
                    sorted(reservation["baseline_audits"], key=lambda r: r["id"]) == expected_audits, "SAVED_BASELINE_INVALID")
                if leg == "reverse":
                    need("first" in result and result["first"]["request"]["status"] == "rejected" and
                        instant(reservation["reserved_at"]) >= instant(result["first"]["observed_at"]), "SAVED_LEG_ORDER_INVALID")
                result[leg] = item
        return result

    def validate_saved(self, leg, reservation, proof, terminal):
        sender, receiver, status, actor = PAIR[leg]
        need((reservation.get("sender"), reservation.get("receiver"), reservation.get("actor")) ==
            (sender, receiver, sender), "SAVED_RESERVATION_INVALID")
        start, end = instant(reservation["reserved_at"]), instant(proof["observed_at"])
        row, audits = proof["request"], proof["audits"]
        need(set(row) == REQUEST_FIELDS and (row["sender_id"], row["receiver_id"], row["status"]) ==
            (sender, receiver, status if terminal else "pending"), "SAVED_REQUEST_INVALID")
        identifier = canonical(row["id"])
        need(start <= instant(row["created_at"]) <= instant(row["updated_at"]) <= end, "SAVED_REQUEST_TIME_INVALID")
        expected = {"request_sent": sender}
        if terminal:
            expected["request_" + status] = actor
        need(len(audits) == len(expected) and {a.get("action") for a in audits} == set(expected), "SAVED_AUDITS_INVALID")
        ids = {identifier}
        for audit in audits:
            need(set(audit) == AUDIT_FIELDS and audit["target_id"] == identifier and audit["connection_id"] is None and
                audit["actor_id"] == expected[audit["action"]] and audit["metadata"] ==
                {"sender_id": sender, "receiver_id": receiver}, "SAVED_AUDIT_SCOPE_INVALID")
            need(canonical(audit["id"]) not in ids, "SAVED_ID_COLLISION")
            ids.add(audit["id"])
            need(instant(row["created_at"]) <= instant(audit["created_at"]) <= end, "SAVED_AUDIT_TIME_INVALID")

    def exact_existing(self, snapshot, known):
        requests = [v["request"] for v in known.values()]
        audits = [row for v in known.values() for row in v["audits"]]
        need(sorted(snapshot["requests"], key=lambda r: r["id"]) == sorted(requests, key=lambda r: r["id"]), "UNKNOWN_OR_CHANGED_REQUEST")
        need(sorted(snapshot["audits"], key=lambda r: r["id"]) == sorted(audits, key=lambda r: r["id"]), "UNKNOWN_OR_CHANGED_AUDIT")

    def preflight(self):
        need(not self.directory.exists(), "RUN_ALREADY_EXISTS")
        snapshot = self.snapshot()
        need(snapshot["sessions"] == 0 and not snapshot["requests"] and not snapshot["audits"], "QA_NOT_EMPTY")
        self.directory.mkdir(parents=True, exist_ok=False)
        self.save("preflight", {"snapshot": snapshot})
        return snapshot

    def reserve(self, leg):
        need(leg in PAIR, "LEG_INVALID")
        need(not self.path(leg + "-reservation").exists(), "SECOND_INVITATION_ATTEMPT_BLOCKED")
        snapshot = self.snapshot()
        self.unchanged_profiles(snapshot)
        known = self.known()
        need(not known if leg == "first" else set(known) == {"first"} and
            known["first"]["request"]["status"] == "rejected", "LEG_ORDER_INVALID")
        self.exact_existing(snapshot, known)
        need(all(instant(snapshot["now"]) >= instant(v["observed_at"]) for v in known.values()), "DATABASE_CLOCK_REVERSED")
        sender, receiver, _, _ = PAIR[leg]
        self.save(leg + "-reservation", {"sender": sender, "receiver": receiver, "actor": sender,
            "reserved_at": snapshot["now"], "baseline_requests": snapshot["requests"], "baseline_audits": snapshot["audits"]})
        return snapshot

    def observe(self, leg):
        need(leg in PAIR, "LEG_INVALID")
        terminal = self.path(leg + "-terminal")
        need(not terminal.exists(), "TERMINAL_ALREADY_OBSERVED")
        reservation = self.read(leg + "-reservation")
        sender, receiver, final_status, actor = PAIR[leg]
        need((reservation.get("sender"), reservation.get("receiver"), reservation.get("actor")) == (sender, receiver, sender), "RESERVATION_DIRECTION_INVALID")
        pending = self.path(leg + "-pending").exists()
        snapshot = self.snapshot()
        self.unchanged_profiles(snapshot)
        start, end = instant(reservation["reserved_at"]), instant(snapshot["now"])
        need(start <= end, "RESERVATION_TIME_INVALID")
        known = self.known()
        previous = known.pop(leg, None)
        rows = [r for r in snapshot["requests"] if r.get("sender_id") == sender and r.get("receiver_id") == receiver]
        need(len(rows) == 1, "NEW_REQUEST_NOT_UNIQUE")
        row = rows[0]
        need(set(row) == REQUEST_FIELDS, "REQUEST_SCHEMA_INVALID")
        canonical(row["id"])
        need(row["id"] not in {r["id"] for r in reservation["baseline_requests"]}, "REQUEST_NOT_NEW")
        need(start <= instant(row["created_at"]) <= instant(row["updated_at"]) <= end, "REQUEST_TIME_INVALID")
        need(row["status"] == (final_status if pending else "pending"), "REQUEST_STATUS_INVALID")
        if pending:
            need(previous is not None and previous["request"]["id"] == row["id"] and
                all(previous["request"][k] == row[k] for k in ("sender_id", "receiver_id", "created_at")), "ADOPTED_REQUEST_CHANGED")
        audits = [a for a in snapshot["audits"] if a.get("target_id") == row["id"]]
        expected_actions = {"request_sent": sender}
        if pending:
            expected_actions["request_" + final_status] = actor
        need(len(audits) == len(expected_actions) and {a.get("action") for a in audits} == set(expected_actions), "AUDIT_ACTIONS_INVALID")
        ids = {row["id"]}
        for audit in audits:
            need(set(audit) == AUDIT_FIELDS, "AUDIT_SCHEMA_INVALID")
            identifier = canonical(audit["id"])
            need(identifier not in ids, "AUDIT_ID_COLLISION")
            ids.add(identifier)
            need(audit["connection_id"] is None and audit["actor_id"] == expected_actions[audit["action"]] and
                audit["metadata"] == {"sender_id": sender, "receiver_id": receiver}, "AUDIT_SCOPE_INVALID")
            need(start <= instant(row["created_at"]) <= instant(audit["created_at"]) <= end, "AUDIT_TIME_INVALID")
        if pending:
            sent = next(a for a in audits if a["action"] == "request_sent")
            need(sent == previous["audits"][0], "SEND_AUDIT_CHANGED")
            changed = next(a for a in audits if a["action"] != "request_sent")
            need(instant(changed["created_at"]) >= instant(previous["observed_at"]) and
                instant(row["updated_at"]) >= instant(previous["observed_at"]), "TERMINAL_BEFORE_ADOPTION")
        candidate = {"request": row, "audits": sorted(audits, key=lambda a: a["action"]), "observed_at": snapshot["now"]}
        known[leg] = candidate
        self.exact_existing(snapshot, known)
        self.save(leg + ("-terminal" if pending else "-pending"), candidate)
        return snapshot

    def cleanup(self):
        known = self.known()
        need(set(known) == set(PAIR) and all(self.path(leg + "-terminal").exists() for leg in PAIR), "TERMINAL_PROOF_REQUIRED")
        snapshot = self.snapshot()
        self.unchanged_profiles(snapshot)
        need(snapshot["sessions"] == 0, "QA_SESSIONS_NOT_LOGGED_OUT")
        need(all(instant(snapshot["now"]) >= instant(v["observed_at"]) for v in known.values()), "DATABASE_CLOCK_REVERSED")
        if self.path("cleanup-reservation").exists():
            reservation = self.read("cleanup-reservation")
            expected = [("couple_audit_log", a) for v in known.values() for a in v["audits"]] + [
                ("couple_connection_requests", v["request"]) for v in known.values()]
            need(reservation.get("rows") == [[table, row] for table, row in expected], "CLEANUP_RESERVATION_CHANGED")
            need(not snapshot["requests"] and not snapshot["audits"], "CLEANUP_UNCERTAIN_NO_REPLAY")
            if not self.path("cleanup-complete").exists():
                self.save("cleanup-complete", {"observed_at": snapshot["now"], "reconciled": True, "cleanup_pending": False})
            return snapshot
        self.exact_existing(snapshot, known)
        for leg, value in known.items():
            sender, receiver, status, _ = PAIR[leg]
            need((value["request"]["sender_id"], value["request"]["receiver_id"], value["request"]["status"]) ==
                (sender, receiver, status), "CLEANUP_STATUS_INVALID")
        plan = [("couple_audit_log", a) for v in known.values() for a in v["audits"]] + [
            ("couple_connection_requests", v["request"]) for v in known.values()]
        need(len(plan) == 6 and len({r["id"] for _, r in plan}) == 6, "CLEANUP_ID_COLLISION")
        self.save("cleanup-reservation", {"reserved_at": snapshot["now"], "rows": plan})
        for table, row in plan:
            # Table comes from the two fixed plan literals. Full JSONB equality freezes every column, including metadata.
            body = literal(json.dumps(row, sort_keys=True, separators=(",", ":")))
            sql = f"delete from public.{table} t where t.id={literal(row['id'])}::uuid and to_jsonb(t)={body}::jsonb returning id"
            result = self.execute(sql, read_only=False)
            need(result == [{"id": row["id"]}], "DELETE_RESPONSE_UNCERTAIN")
            absent = self.execute(f"select id from public.{table} where id={literal(row['id'])}::uuid", read_only=True)
            need(absent == [], "DELETE_ABSENCE_UNCONFIRMED")
        after = self.snapshot()
        self.unchanged_profiles(after)
        need(after["sessions"] == 0 and not after["requests"] and not after["audits"], "FINAL_CLEANUP_PENDING")
        self.save("cleanup-complete", {"observed_at": after["now"], "reconciled": False, "cleanup_pending": False})
        return after


def summary(snapshot):
    return {"database_time": snapshot["now"], "qa_identities": len(snapshot["users"]),
        "sessions": snapshot["sessions"], "requests": snapshot["request_count"], "audits": snapshot["audit_count"],
        "transactions": snapshot["transactions"], "factors": snapshot["factors"], "connections": snapshot["connections"],
        "registrations_enabled": False}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("preflight", "reserve", "adopt", "observe", "cleanup", "selfcheck", "test"))
    parser.add_argument("run", nargs="?")
    parser.add_argument("leg", nargs="?", choices=tuple(PAIR))
    args = parser.parse_args()
    if args.command in ("selfcheck", "test"):
        import unittest
        suite = unittest.defaultTestLoader.discover(str(Path(__file__).parent), pattern="test_couple_invitation_evidence.py")
        raise SystemExit(0 if unittest.TextTestRunner().run(suite).wasSuccessful() else 1)
    canonical(args.run)
    need((args.leg is not None) == (args.command in ("reserve", "adopt", "observe")), "COMMAND_LEG_INVALID")
    from native_auth_config import management_token
    evidence = Evidence(args.run, Management(management_token()))
    if args.command == "preflight":
        snapshot = evidence.preflight()
    elif args.command == "cleanup":
        snapshot = evidence.cleanup()
    elif args.command == "reserve":
        snapshot = evidence.reserve(args.leg)
    else:
        if args.command == "adopt":
            need(not evidence.path(args.leg + "-pending").exists(), "ALREADY_ADOPTED")
        snapshot = evidence.observe(args.leg)
    print(json.dumps({"run_id": evidence.run, "command": args.command, "leg": args.leg, **summary(snapshot)}))


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print(str(error) if isinstance(error, EvidenceError) else "FAILURE_DETAILS_WITHHELD")
        raise SystemExit(1)
