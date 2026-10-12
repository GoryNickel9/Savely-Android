"""Offline fake SQL executor only. No vault, management transport, APK or device access."""
import copy
import json
from pathlib import Path
import re
import tempfile
import unittest

import couple_invitation_evidence as evidence

RUN = "11111111-1111-4111-8111-111111111111"
FIRST = "22222222-2222-4222-8222-222222222222"
REVERSE = "33333333-3333-4333-8333-333333333333"
SENT_FIRST = "44444444-4444-4444-8444-444444444444"
END_FIRST = "55555555-5555-4555-8555-555555555555"
SENT_REVERSE = "66666666-6666-4666-8666-666666666666"
END_REVERSE = "77777777-7777-4777-8777-777777777777"


def time(second):
    return f"2026-10-12T12:00:{second:02d}+00:00"


def snapshot():
    return {"now": time(0), "users": sorted([{"id": owner, "email": email, "confirmed": True,
        "qa_run": evidence.QA_RUN} for email, owner in evidence.QA_USERS.items()], key=lambda u: u["id"]),
        "profiles": sorted([{"user_id": evidence.A, "couple_code": "ABCDEFGH", "permissions": {"couple_expenses": True}},
            {"user_id": evidence.B, "couple_code": "ABCDEFGJ", "permissions": {"couple_expenses": True}}], key=lambda p: p["user_id"]),
        "sessions": 0, "factors": 0, "registrations": "false", "transactions": 0, "connections": 0,
        "requests": [], "request_count": 0, "audits": [], "audit_count": 0}


class FakeSql:
    def __init__(self):
        self.value = snapshot()
        self.calls = []
        self.lost_delete = False
        self.before_write = None

    def __call__(self, sql, read_only=True):
        self.calls.append((sql, read_only))
        if sql == evidence.SNAPSHOT_SQL:
            return [{"snapshot": copy.deepcopy(self.value)}]
        match = re.search(r"public\.(couple_audit_log|couple_connection_requests).*?id='([a-f0-9-]+)'::uuid", sql)
        if not match:
            raise AssertionError("Unexpected fixture SQL")
        table, identifier = match.groups()
        key = "audits" if table == "couple_audit_log" else "requests"
        rows = self.value[key]
        row = next((r for r in rows if r["id"] == identifier), None)
        if read_only:
            return [] if row is None else [{"id": identifier}]
        if self.before_write:
            self.before_write()
        if row is None:
            return []
        literal = evidence.literal(json.dumps(row, sort_keys=True, separators=(",", ":")))
        if "and to_jsonb(t)=" + literal + "::jsonb returning id" not in sql:
            raise AssertionError("DELETE did not freeze the full row")
        rows.remove(row)
        self.value["audit_count" if key == "audits" else "request_count"] = len(rows)
        if self.lost_delete:
            raise evidence.EvidenceError("MANAGEMENT_OUTCOME_UNCERTAIN")
        return [{"id": identifier}]

    def pending(self, leg):
        sender, receiver, _, _ = evidence.PAIR[leg]
        identifier, audit_id, second = (FIRST, SENT_FIRST, 2) if leg == "first" else (REVERSE, SENT_REVERSE, 5)
        self.value["requests"].append({"id": identifier, "sender_id": sender, "receiver_id": receiver,
            "status": "pending", "created_at": time(second), "updated_at": time(second)})
        self.value["audits"].append({"id": audit_id, "connection_id": None, "actor_id": sender,
            "action": "request_sent", "target_id": identifier, "metadata": {"sender_id": sender, "receiver_id": receiver},
            "created_at": time(second)})
        self.value.update(now=time(second), request_count=len(self.value["requests"]), audit_count=len(self.value["audits"]))

    def terminal(self, leg):
        sender, receiver, status, actor = evidence.PAIR[leg]
        identifier, audit_id, second = (FIRST, END_FIRST, 3) if leg == "first" else (REVERSE, END_REVERSE, 6)
        row = next(r for r in self.value["requests"] if r["id"] == identifier)
        row.update(status=status, updated_at=time(second))
        self.value["audits"].append({"id": audit_id, "connection_id": None, "actor_id": actor,
            "action": "request_" + status, "target_id": identifier, "metadata": {"sender_id": sender, "receiver_id": receiver},
            "created_at": time(second)})
        self.value.update(now=time(second), audit_count=len(self.value["audits"]))


class CoupleInvitationEvidenceTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.sql = FakeSql()
        self.proof = evidence.Evidence(RUN, self.sql, Path(self.temp.name))

    def preflight(self):
        self.proof.preflight()
        self.sql.value["now"] = time(1)

    def first_pending(self):
        self.preflight()
        self.proof.reserve("first")
        self.sql.pending("first")
        self.proof.observe("first")

    def first_terminal(self):
        self.first_pending()
        self.sql.terminal("first")
        self.proof.observe("first")
        self.sql.value["now"] = time(4)

    def complete(self):
        self.first_terminal()
        self.proof.reserve("reverse")
        self.sql.pending("reverse")
        self.proof.observe("reverse")
        self.sql.terminal("reverse")
        self.proof.observe("reverse")

    def writes(self):
        return [sql for sql, read in self.sql.calls if not read]

    def test_two_directions_four_audits_and_full_cas_cleanup(self):
        self.complete()
        self.assertEqual(2, self.sql.value["request_count"])
        self.assertEqual(4, self.sql.value["audit_count"])
        self.sql.before_write = lambda: self.assertTrue(self.proof.path("cleanup-reservation").is_file())
        final = self.proof.cleanup()
        self.assertEqual(6, len(self.writes()))
        self.assertEqual([], final["requests"])
        self.assertEqual([], final["audits"])
        self.assertFalse(self.proof.read("cleanup-complete")["cleanup_pending"])

    def test_preflight_refuses_nonempty_counts_and_wrong_qa_metadata(self):
        baseline = copy.deepcopy(self.sql.value)
        mutations = [(key, 1) for key in ("sessions", "factors", "transactions", "connections")]
        mutations += [("registrations", "true"), ("request_count", 1), ("audit_count", 1)]
        for key, value in mutations:
            with self.subTest(key=key):
                self.sql.value = copy.deepcopy(baseline)
                self.sql.value[key] = value
                with self.assertRaises(evidence.EvidenceError):
                    self.proof.preflight()
                self.assertFalse(self.proof.directory.exists())
        self.sql.value = copy.deepcopy(baseline)
        self.sql.value["users"][0]["qa_run"] = RUN
        with self.assertRaisesRegex(evidence.EvidenceError, "QA_IDENTITY_INVALID"):
            self.proof.preflight()

    def test_preflight_rejects_changed_identity_permissions_and_code(self):
        baseline = copy.deepcopy(self.sql.value)
        for field, value in (("user_id", RUN), ("couple_code", "OOOOOOOO"), ("permissions", {"couple_expenses": False}),
            ("permissions", {"couple_expenses": True, "admin": True})):
            with self.subTest(field=field, value=value):
                self.sql.value = copy.deepcopy(baseline)
                self.sql.value["profiles"][0][field] = value
                with self.assertRaises(evidence.EvidenceError):
                    self.proof.preflight()

    def test_reservation_is_exclusive_before_any_ui_or_transport_mutation(self):
        self.preflight()
        self.proof.reserve("first")
        with self.assertRaisesRegex(evidence.EvidenceError, "SECOND_INVITATION_ATTEMPT_BLOCKED"):
            self.proof.reserve("first")
        self.assertEqual([], self.writes())

    def test_reverse_requires_first_terminal_rejection(self):
        self.first_pending()
        with self.assertRaisesRegex(evidence.EvidenceError, "LEG_ORDER_INVALID"):
            self.proof.reserve("reverse")

    def test_new_request_requires_correct_direction_pending_and_known_fields(self):
        self.preflight()
        self.proof.reserve("first")
        self.sql.pending("first")
        baseline = copy.deepcopy(self.sql.value)
        for key, value in (("sender_id", evidence.B), ("receiver_id", RUN), ("status", "accepted"), ("unexpected", "x")):
            with self.subTest(key=key):
                self.sql.value = copy.deepcopy(baseline)
                self.sql.value["requests"][0][key] = value
                with self.assertRaises(evidence.EvidenceError):
                    self.proof.observe("first")
                self.assertFalse(self.proof.path("first-pending").exists())

    def test_clock_request_and_audit_bounds_refuse_past_future_and_naive_times(self):
        self.preflight()
        self.proof.reserve("first")
        self.sql.pending("first")
        baseline = copy.deepcopy(self.sql.value)
        for table, key, value in (("requests", "created_at", time(0)), ("requests", "updated_at", time(10)),
            ("audits", "created_at", time(0)), ("audits", "created_at", time(10)),
            ("requests", "created_at", "2026-10-12T12:00:02")):
            with self.subTest(table=table, key=key, value=value):
                self.sql.value = copy.deepcopy(baseline)
                self.sql.value[table][0][key] = value
                with self.assertRaises(evidence.EvidenceError):
                    self.proof.observe("first")

    def test_send_audit_requires_exact_actor_metadata_target_null_connection(self):
        self.preflight()
        self.proof.reserve("first")
        self.sql.pending("first")
        baseline = copy.deepcopy(self.sql.value)
        for key, value in (("actor_id", evidence.B), ("target_id", REVERSE), ("connection_id", RUN),
            ("action", "request_accepted"), ("metadata", {"sender_id": evidence.A, "receiver_id": evidence.B, "extra": True})):
            with self.subTest(key=key):
                self.sql.value = copy.deepcopy(baseline)
                self.sql.value["audits"][0][key] = value
                with self.assertRaises(evidence.EvidenceError):
                    self.proof.observe("first")

    def test_unknown_request_or_audit_blocks_adoption(self):
        self.preflight()
        self.proof.reserve("first")
        self.sql.pending("first")
        baseline = copy.deepcopy(self.sql.value)
        for table, count in (("requests", "request_count"), ("audits", "audit_count")):
            with self.subTest(table=table):
                self.sql.value = copy.deepcopy(baseline)
                extra = copy.deepcopy(self.sql.value[table][0]); extra["id"] = REVERSE
                if table == "audits":
                    extra["target_id"] = RUN
                self.sql.value[table].append(extra); self.sql.value[count] += 1
                with self.assertRaises(evidence.EvidenceError):
                    self.proof.observe("first")

    def test_terminal_requires_pending_adoption_and_exact_state(self):
        self.preflight()
        self.proof.reserve("first")
        self.sql.pending("first")
        self.sql.terminal("first")
        with self.assertRaisesRegex(evidence.EvidenceError, "REQUEST_STATUS_INVALID"):
            self.proof.observe("first")

    def test_modified_send_audit_or_wrong_terminal_actor_is_rejected(self):
        self.first_pending()
        self.sql.terminal("first")
        baseline = copy.deepcopy(self.sql.value)
        for index, field, value in ((0, "id", REVERSE), (1, "actor_id", evidence.A), (1, "action", "request_cancelled")):
            with self.subTest(index=index, field=field):
                self.sql.value = copy.deepcopy(baseline)
                self.sql.value["audits"][index][field] = value
                with self.assertRaises(evidence.EvidenceError):
                    self.proof.observe("first")

    def test_cleanup_requires_terminal_proofs_and_zero_sessions(self):
        self.first_terminal()
        with self.assertRaisesRegex(evidence.EvidenceError, "TERMINAL_PROOF_REQUIRED"):
            self.proof.cleanup()
        self.proof.reserve("reverse"); self.sql.pending("reverse"); self.proof.observe("reverse")
        self.sql.terminal("reverse"); self.proof.observe("reverse")
        self.sql.value["sessions"] = 1
        with self.assertRaisesRegex(evidence.EvidenceError, "QA_SESSIONS_NOT_LOGGED_OUT"):
            self.proof.cleanup()
        self.assertEqual([], self.writes())

    def test_cleanup_changed_full_payload_and_unknown_audit_never_write(self):
        self.complete()
        baseline = copy.deepcopy(self.sql.value)
        for table, field, value in (("requests", "updated_at", time(7)), ("requests", "receiver_id", RUN),
            ("audits", "metadata", {}), ("audits", "actor_id", evidence.B)):
            with self.subTest(table=table, field=field):
                self.sql.value = copy.deepcopy(baseline)
                self.sql.value[table][0][field] = value
                with self.assertRaises(evidence.EvidenceError):
                    self.proof.cleanup()
                self.assertEqual([], self.writes())

    def test_saved_scope_and_payload_tampering_is_rejected(self):
        self.complete()
        path = self.proof.path("first-terminal")
        original = json.loads(path.read_text())
        for change in ("run_id", "project", "actor", "status", "metadata"):
            value = copy.deepcopy(original)
            if change in ("run_id", "project"):
                value[change] = REVERSE
            elif change == "actor":
                value["audits"][0]["actor_id"] = evidence.A
            elif change == "status":
                value["request"]["status"] = "cancelled"
            else:
                value["audits"][0]["metadata"] = {}
            path.write_text(json.dumps(value))
            with self.subTest(change=change), self.assertRaises(evidence.EvidenceError):
                self.proof.cleanup()
            self.assertEqual([], self.writes())
        path.write_text(json.dumps(original))

    def test_lost_delete_response_is_reserved_and_never_replayed(self):
        self.complete()
        self.sql.lost_delete = True
        with self.assertRaisesRegex(evidence.EvidenceError, "MANAGEMENT_OUTCOME_UNCERTAIN"):
            self.proof.cleanup()
        self.assertTrue(self.proof.path("cleanup-reservation").exists())
        self.assertEqual(1, len(self.writes()))
        self.sql.lost_delete = False
        with self.assertRaisesRegex(evidence.EvidenceError, "CLEANUP_UNCERTAIN_NO_REPLAY"):
            self.proof.cleanup()
        self.assertEqual(1, len(self.writes()))

    def test_complete_cleanup_reconciliation_never_repeats_delete(self):
        self.complete()
        self.proof.cleanup()
        self.proof.cleanup()
        self.assertEqual(6, len(self.writes()))

    def test_partial_exclusive_reservation_and_foreign_run_block(self):
        self.preflight()
        self.proof.path("first-reservation").write_text('{"run_id":')
        with self.assertRaisesRegex(evidence.EvidenceError, "SECOND_INVITATION_ATTEMPT_BLOCKED"):
            self.proof.reserve("first")
        with self.assertRaises(evidence.EvidenceError):
            self.proof.observe("first")
        self.assertEqual([], self.writes())

    def test_sql_snapshot_and_public_summary_do_not_return_secrets_or_general_data(self):
        self.assertNotIn("encrypted_password", evidence.SNAPSHOT_SQL)
        self.assertNotIn("refresh_token", evidence.SNAPSHOT_SQL)
        self.assertNotIn("select * from auth.users", evidence.SNAPSHOT_SQL)
        self.preflight()
        report = evidence.summary(self.sql.value)
        self.assertNotIn("users", report)
        self.assertNotIn("profiles", report)
        self.assertEqual(0, report["transactions"])


if __name__ == "__main__":
    unittest.main()
