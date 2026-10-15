"""Offline fakes only: no vault, credentials, HTTP, APK or device access."""
import base64
import copy
from decimal import Decimal
import json
from pathlib import Path
import tempfile
import unittest
from urllib.parse import urlencode, urlparse, parse_qs

import couple_role_observation as subject

RUN = "11111111-1111-4111-8111-111111111111"
CONNECTION = "22222222-2222-4222-8222-222222222222"
TX = "33333333-3333-4333-8333-333333333333"
SHARE = "44444444-4444-4444-8444-444444444444"
SESSION_A = "55555555-5555-4555-8555-555555555555"
SESSION_B = "66666666-6666-4666-8666-666666666666"


def descriptor():
    own = {"id": TX, "user_id": subject.OWNERS["a"], "amount": "10.01", "currency": "EUR"}
    share = {"id": SHARE, "connection_id": CONNECTION, "created_by": subject.OWNERS["a"],
             "original_tx_id": TX, "my_share_amount": "5.01", "currency": "EUR"}
    return {"version": 1, "run_id": RUN,
            "connection": {"id": CONNECTION, "user_a": subject.OWNERS["a"], "user_b": subject.OWNERS["b"]},
            "originals": [{"id": TX, "owner_id": subject.OWNERS["a"]}],
            "queries": [{"label": "private-original", "table": "transactions", "id": TX, "expected": {"a": [own], "b": []}},
                        {"label": "equal-share", "table": "shared_expenses_view", "id": SHARE, "expected": {"a": [share], "b": [copy.deepcopy(share)]}}]}


def credentials():
    return {"accounts": [{"email": email, "id": subject.OWNERS[actor], "password": "fake-password-" + actor}
                         for actor, email in subject.EMAILS.items()]}


class FakeTransport:
    def __init__(self, expected):
        self.expected, self.calls, self.tokens = expected, [], {}
        self.mismatch, self.fail_b_login, self.fail_logout = False, False, False
        self.wrong_jwt_owner = False

    def query(self, table, **values):
        return "/rest/v1/" + table + "?" + urlencode(values)

    def identity(self, user, owner, email):
        subject.need(user["id"] == owner and user["email"] == email, "QA_IDENTITY")

    def session_id(self, token):
        return json.loads(base64.urlsafe_b64decode(token.split(".")[1] + "==="))["session_id"]

    def http(self, path, public, token, method="GET", body=None):
        self.calls.append((path, method))
        if path == "/auth/v1/token?grant_type=password":
            actor = next(actor for actor, email in subject.EMAILS.items() if email == body["email"])
            if actor == "b" and self.fail_b_login:
                raise RuntimeError("fake ambiguous login")
            claims = {"sub": subject.OWNERS[actor], "session_id": SESSION_A if actor == "a" else SESSION_B}
            if self.wrong_jwt_owner:
                claims["sub"] = CONNECTION
            encoded = base64.urlsafe_b64encode(json.dumps(claims).encode()).decode().rstrip("=")
            access = "fake." + encoded + ".fake"
            self.tokens[access] = actor
            return {"user": {"id": subject.OWNERS[actor], "email": subject.EMAILS[actor]}, "access_token": access,
                    "refresh_token": "fake-refresh-never-persisted"}
        actor = self.tokens[token]
        if path == "/auth/v1/user":
            return {"id": subject.OWNERS[actor], "email": subject.EMAILS[actor]}
        if path == "/auth/v1/logout?scope=local":
            if self.fail_logout:
                raise RuntimeError("fake logout failure")
            return None
        if method != "GET" or not path.startswith("/rest/v1/"):
            raise AssertionError("Unexpected operation")
        parsed = urlparse(path)
        table = parsed.path.rsplit("/", 1)[-1]
        query = parse_qs(parsed.query)
        if query.get("limit") != ["2"]:
            raise AssertionError("Unbounded query")
        identifier = query["id"][0].removeprefix("eq.")
        wanted = next(probe for probe in self.expected["queries"] if probe["table"] == table and probe["id"] == identifier)
        rows = copy.deepcopy(wanted["expected"][actor])
        if table == "transactions" and query.get("user_id") != ["eq." + subject.OWNERS["a"]]:
            raise AssertionError("Private ownership filter missing")
        if table == "shared_expenses_view" and query.get("connection_id") != ["eq." + CONNECTION]:
            raise AssertionError("Connection filter missing")
        if rows and "my_share_amount" in rows[0]:
            rows[0]["my_share_amount"] = Decimal("5.010")
        if self.mismatch and rows:
            rows[0]["amount"] = "unexpected-private-value"
        return rows


class RoleObservationTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.directory = Path(self.temporary.name) / RUN
        self.directory.mkdir()

    def tearDown(self):
        self.temporary.cleanup()

    def observe(self, value=None, transport=None, phase="active"):
        value = value or descriptor()
        transport = transport or FakeTransport(value)
        result = subject.observe(self.directory, phase, value, "fake-public", transport, credentials)
        return result, transport

    def test_role_reads_preserve_private_owner_boundary_and_decimal_values(self):
        result, transport = self.observe()
        self.assertEqual("passed", result["status"])
        self.assertEqual([1, 1, 0, 1], [probe["row_count"] for probe in result["probes"]])
        self.assertEqual({"a": SESSION_A, "b": SESSION_B}, result["own_session_ids"])
        self.assertEqual({"a": True, "b": True}, result["own_local_logout"])
        posts = [(path, method) for path, method in transport.calls if method != "GET"]
        self.assertEqual(2, sum(path.endswith("grant_type=password") for path, _ in posts))
        self.assertEqual(2, sum(path == "/auth/v1/logout?scope=local" for path, _ in posts))
        self.assertEqual(4, len(posts))
        for file in self.directory.glob("*.json"):
            text = file.read_text()
            self.assertNotIn("fake-password", text)
            self.assertNotIn("fake-refresh", text)
            self.assertNotIn("fake.", text)

    def test_post_revoke_hidden_share_and_visible_connection_metadata(self):
        value = descriptor()
        value["queries"][1]["expected"] = {"a": [], "b": []}
        row = {**value["connection"], "created_at": "2026-10-12T12:00:00Z", "revoked_at": "2026-10-12T12:01:00Z"}
        value["queries"].append({"label": "archive", "table": "couple_connections", "id": CONNECTION,
                                 "expected": {"a": [row], "b": [row]}})
        result, _ = self.observe(value, phase="post-revoke")
        self.assertEqual("passed", result["status"])
        self.assertEqual([1, 0, 1, 0, 0, 1], [probe["row_count"] for probe in result["probes"]])

    def test_each_auth_post_has_exclusive_reservation_before_dispatch(self):
        value = descriptor()
        transport = FakeTransport(value)
        original_http = transport.http
        def guarded(path, public, token, method="GET", body=None):
            if method == "POST":
                if path.endswith("grant_type=password"):
                    actor = next(actor for actor, email in subject.EMAILS.items() if email == body["email"])
                    operation = "login"
                else:
                    self.assertEqual("/auth/v1/logout?scope=local", path)
                    actor, operation = transport.tokens[token], "logout"
                self.assertTrue((self.directory / f"role-active-{operation}-{actor}-reservation.json").exists())
            return original_http(path, public, token, method, body)
        transport.http = guarded
        result, _ = self.observe(value, transport)
        self.assertEqual("passed", result["status"])

    def test_failed_expectation_does_not_log_unexpected_response_and_logs_out(self):
        value = descriptor()
        transport = FakeTransport(value)
        transport.mismatch = True
        result, _ = self.observe(value, transport)
        self.assertEqual("NUMERIC_INVALID", result["failure_code"])
        self.assertEqual({"a": True}, result["own_local_logout"])
        self.assertFalse(result["cleanup_pending"])
        self.assertNotIn("unexpected-private-value", (self.directory / "role-active-result.json").read_text())

    def test_ambiguous_second_login_retains_cleanup_pending_and_logs_out_first(self):
        value = descriptor()
        transport = FakeTransport(value)
        transport.fail_b_login = True
        result, _ = self.observe(value, transport)
        self.assertEqual("failed", result["status"])
        self.assertTrue(result["cleanup_pending"])
        self.assertEqual({"a": True}, result["own_local_logout"])
        self.assertTrue((self.directory / "role-active-login-b-reservation.json").exists())

    def test_mismatched_jwt_identity_never_logs_out_an_unverified_token(self):
        value = descriptor()
        transport = FakeTransport(value)
        transport.wrong_jwt_owner = True
        result, _ = self.observe(value, transport)
        self.assertEqual("JWT_OWNER_MISMATCH", result["failure_code"])
        self.assertTrue(result["cleanup_pending"])
        self.assertEqual({}, result["own_session_ids"])
        self.assertFalse(any(path.startswith("/rest/v1/") or "logout" in path for path, _ in transport.calls))

    def test_failed_local_logout_is_explicit_without_retry(self):
        value = descriptor()
        transport = FakeTransport(value)
        transport.fail_logout = True
        result, _ = self.observe(value, transport)
        self.assertEqual("failed", result["status"])
        self.assertTrue(result["cleanup_pending"])
        self.assertEqual({"a": False, "b": False}, result["own_local_logout"])
        self.assertEqual(2, sum(path == "/auth/v1/logout?scope=local" for path, _ in transport.calls))

    def test_exclusive_reservation_blocks_replay_before_vault_or_transport(self):
        self.observe()
        def forbidden():
            self.fail("Credentials read during replay")
        transport = FakeTransport(descriptor())
        with self.assertRaises(FileExistsError):
            subject.observe(self.directory, "active", descriptor(), "fake-public", transport, forbidden)
        self.assertEqual([], transport.calls)

    def test_unregistered_original_wrong_pair_arbitrary_query_and_nonqa_owner_rejected(self):
        for modify in (lambda d: d["connection"].update(user_b=TX),
                       lambda d: d["originals"][0].update(owner_id=TX),
                       lambda d: d["queries"][0].update(id=SHARE),
                       lambda d: d["queries"][0].update(query="select=*"),
                       lambda d: d["queries"][0].update(table="profiles")):
            with self.subTest(modify=modify):
                value = descriptor()
                modify(value)
                with self.assertRaises(subject.ObservationError):
                    subject.validate(value, RUN)

    def test_private_original_cannot_be_expected_visible_to_partner(self):
        value = descriptor()
        value["queries"][0]["expected"]["b"] = value["queries"][0]["expected"]["a"]
        with self.assertRaisesRegex(subject.ObservationError, "PRIVATE_TRANSACTION_EXPECTED_FOR_WRONG_ROLE"):
            subject.validate(value, RUN)

    def test_descriptor_path_escape_and_label_injection_are_refused(self):
        root = Path(self.temporary.name)
        (root / "artifacts/couple-form-ui" / RUN).mkdir(parents=True)
        outside = Path(self.temporary.name) / "outside.json"
        outside.write_text("{}")
        with self.assertRaisesRegex(subject.ObservationError, "DESCRIPTOR_OUTSIDE_RUN"):
            subject.paths(RUN, outside, root)
        with self.assertRaises(subject.ObservationError):
            subject.label("../active")

    def test_duplicate_json_keys_and_nonfinite_decimals_are_refused(self):
        source = self.directory / "expected.json"
        source.write_text('{"version":1,"version":2}')
        with self.assertRaisesRegex(subject.ObservationError, "DUPLICATE_JSON_KEY"):
            subject.read_descriptor(source)
        for number in ("NaN", "Infinity", True):
            with self.subTest(number=number), self.assertRaises(subject.ObservationError):
                subject.numeric_equal(number, number)

    def test_timestamp_normalization_and_null_are_strict(self):
        self.assertTrue(subject.equal_field("created_at", "2026-10-12T14:00:00+02:00", "2026-10-12T12:00:00Z"))
        self.assertFalse(subject.equal_field("partner_amount", None, "0"))
        self.assertFalse(subject.equal_field("id", "001", 1))
        self.assertFalse(subject.equal_field("metadata", {"flag": True}, {"flag": 1}))

    def test_metadata_must_not_embed_secrets_in_reserved_descriptor(self):
        value = descriptor()
        value["queries"].append({"label": "audit", "table": "couple_audit_log", "id": SESSION_A,
            "expected": {"a": [{"id": SESSION_A, "connection_id": CONNECTION, "actor_id": subject.OWNERS["a"],
                                    "metadata": {"access_token": "fake-secret"}}], "b": []}})
        with self.assertRaisesRegex(subject.ObservationError, "METADATA_SECRET_FIELD"):
            subject.validate(value, RUN)

    def test_expected_timestamp_without_timezone_is_rejected_before_login(self):
        value = descriptor()
        value["queries"][0]["expected"]["a"][0]["created_at"] = "2026-10-12T12:00:00"
        with self.assertRaisesRegex(subject.ObservationError, "TIMEZONE_REQUIRED"):
            subject.validate(value, RUN)


if __name__ == "__main__":
    unittest.main()
