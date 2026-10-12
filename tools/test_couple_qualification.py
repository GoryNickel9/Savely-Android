"""Offline proof fixtures. Never open the QA vault, device, CLI or HTTP transport."""
import copy
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location("couple_qualification", Path(__file__).with_name("run-couple-qualification.py"))
runner = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(runner)
RUN = "11111111-1111-4111-8111-111111111111"
REQUEST = "22222222-2222-4222-8222-222222222222"
TX = "33333333-3333-4333-8333-333333333333"


def ledger():
    return {"version": 1, "run_id": RUN, "project_host": runner.HOST,
            "user_a": runner.A, "user_b": runner.B, "marker": "android-couple-" + RUN,
            "session_a": "44444444-4444-4444-8444-444444444444",
            "session_b": "55555555-5555-4555-8555-555555555555",
            "started_at": "2026-10-12T00:00:00Z", "operations": {
                "invitation": {"table": "couple_connection_requests", "method": "POST", "actor": runner.A,
                               "id": REQUEST, "expected": {"id": REQUEST, "sender_id": runner.A, "receiver_id": runner.B}},
                "equal_tx": {"table": "transactions", "method": "POST", "actor": runner.A, "id": TX,
                             "expected": {"id": TX, "user_id": runner.A, "type": "expense", "amount": "10.01", "currency": "EUR",
                                          "exchange_rate_eur": "1", "category_id": None,
                                          "description": "android-couple-" + RUN + "-equal_tx", "date": "2026-10-12"}},
            }}


def legacy_case():
    state = ledger()
    state["run_id"] = runner.LEGACY_CAS_RUN
    state["marker"] = "android-couple-" + runner.LEGACY_CAS_RUN
    state["operations"]["equal_tx"]["expected"]["description"] = state["marker"] + "-equal_tx"
    expected = {"id": runner.LEGACY_CAS_KEY.split(":", 1)[1], "created_by": runner.A,
                "connection_id": REQUEST, "original_tx_id": TX, "couple_category_name": state["marker"],
                "split_mode": "equal", "partner_amount": None}
    return state, expected, runner.LEGACY_CAS_KEY


class CoupleQualificationGuardsTest(unittest.TestCase):
    def test_valid_registry_is_bound_to_both_qa_actors_and_run(self):
        self.assertEqual(RUN, runner.validate_ledger(ledger(), RUN)["run_id"])

    def test_wrong_run_or_owner_never_reaches_cleanup(self):
        for field, value in (("run_id", REQUEST), ("user_a", TX), ("project_host", "other.supabase.co")):
            changed = ledger(); changed[field] = value
            with self.assertRaises(RuntimeError):
                runner.validate_ledger(changed, RUN)

    def test_changed_transaction_marker_amount_currency_or_extra_field_is_rejected(self):
        for field, value in (("description", "preexisting"), ("amount", "20"), ("currency", "USD"), ("user_id", runner.B), ("notes", "extra")):
            changed = ledger(); changed["operations"]["equal_tx"]["expected"][field] = value
            with self.assertRaises(RuntimeError):
                runner.validate_ledger(changed, RUN)

    def test_acceptance_must_target_the_reserved_request(self):
        changed = ledger()
        changed["operations"]["acceptance"] = {"table": "rpc/accept_couple_request", "method": "POST", "actor": runner.B,
                                                "id": "", "expected": {"p_request_id": TX}}
        with self.assertRaisesRegex(RuntimeError, "ACCEPTANCE_PAYLOAD"):
            runner.validate_ledger(changed, RUN)

    def test_cas_preserves_boolean_null_and_escaped_string_types(self):
        values = runner.cas({"id": TX, "deleted_at": None, "is_active": False, "description": 'a,"b"\\c'})
        self.assertEqual("is.null", values["deleted_at"])
        self.assertEqual("eq.false", values["is_active"])
        self.assertEqual('eq.a,"b"\\c', values["description"])
        self.assertEqual("eq." + TX, values["id"])

    def test_standalone_eq_keeps_signed_strings_uuid_and_timestamp_without_adding_quotes(self):
        from urllib.parse import parse_qs, urlsplit
        row = {"id": TX, "created_at": "2026-10-12T07:25:13.132029+00:00", "description": "a,+:(x).\"\\&id=eq.foreign", "amount": "-0.01"}
        encoded = runner.query("transactions", **runner.cas(row))
        decoded = parse_qs(urlsplit(encoded).query)
        self.assertEqual({key: ["eq." + value] for key, value in row.items()}, decoded)
        self.assertEqual(set(row), set(decoded))

    def test_recovery_requires_legacy_http_400_exact_row_and_corrected_get_before_distinct_reservation(self):
        with tempfile.TemporaryDirectory() as path:
            state, expected, key = legacy_case()
            cleanup = runner.Cleanup(Path(path), "public-fixture", "admin-fixture", state, key)
            cleanup.state["attempts"][key] = copy.deepcopy(expected)
            def reads(table, **where):
                if where.get("id") == 'eq."' + expected["id"] + '"':
                    raise runner.HttpOutcomeError("HTTP_400_BODY_WITHHELD", 400, "22P02")
                return [copy.deepcopy(expected)]
            cleanup.get = reads
            def uncertain(*args, **kwargs):
                state = json.loads(cleanup.path.read_text())
                self.assertEqual(expected, state["attempts"][key])
                self.assertEqual(expected, state["recovery_attempts"][key]["expected"])
                self.assertTrue(state["recovery_attempts"][key]["corrected_get_exact"])
                raise runner.HttpOutcomeError("TRANSPORT_OR_JSON_UNCERTAIN")
            with patch.object(runner, "http", side_effect=uncertain) as transport:
                with self.assertRaises(runner.HttpOutcomeError):
                    cleanup.remove("shared_expenses", expected)
                with self.assertRaisesRegex(RuntimeError, "RECOVERY_DELETE_ALREADY_ATTEMPTED"):
                    cleanup.remove("shared_expenses", expected)
                self.assertEqual(1, transport.call_count)
            self.assertFalse(cleanup.state["recovery_outcomes"][key]["definitive_rejection"])

    def test_recovery_refuses_unknown_error_zero_match_or_changed_payload_without_delete(self):
        for status, server_code, fixed_match in ((500, "22P02", True), (400, "OTHER", True), (400, "22P02", False)):
            with tempfile.TemporaryDirectory() as path:
                state, expected, key = legacy_case()
                cleanup = runner.Cleanup(Path(path), "public-fixture", "admin-fixture", state, key)
                cleanup.state["attempts"][key] = copy.deepcopy(expected)
                def reads(table, **where):
                    if where.get("id") == 'eq."' + expected["id"] + '"':
                        raise runner.HttpOutcomeError("HTTP_ERROR_BODY_WITHHELD", status, server_code)
                    if len(where) > 1 and not fixed_match:
                        return []
                    return [copy.deepcopy(expected)]
                cleanup.get = reads
                with patch.object(runner, "http", side_effect=AssertionError("No DELETE allowed")):
                    with self.assertRaises(RuntimeError):
                        cleanup.remove("shared_expenses", expected)
                self.assertEqual({}, cleanup.state.get("recovery_attempts", {}))

    def test_future_run_other_uuid_or_corrected_serializer_never_gets_legacy_recovery(self):
        for change in ("new_run", "new_uuid", "corrected_serializer"):
            with tempfile.TemporaryDirectory() as path:
                state, expected, key = legacy_case()
                if change == "new_run":
                    state["run_id"] = RUN
                if change == "new_uuid":
                    expected["id"] = TX
                    key = "shared_expenses:" + TX
                cleanup = runner.Cleanup(Path(path), "public-fixture", "admin-fixture", state, key)
                cleanup.state["attempts"][key] = copy.deepcopy(expected)
                if change == "corrected_serializer":
                    cleanup.state["serialization_versions"] = {key: "raw_eq_v1"}
                cleanup.get = lambda *args, **kwargs: self.fail("Out-of-scope recovery must stop before GET")
                with patch.object(runner, "http", side_effect=AssertionError("No transport allowed")):
                    with self.assertRaisesRegex(RuntimeError, "LEGACY_RECOVERY_SCOPE"):
                        cleanup.remove("shared_expenses", expected)
                self.assertEqual({}, cleanup.state.get("recovery_attempts", {}))

    def test_http_delete_rejection_is_durable_and_does_not_clear_original_reservation(self):
        with tempfile.TemporaryDirectory() as path:
            expected = ledger()["operations"]["equal_tx"]["expected"]
            key = "transactions:" + TX
            cleanup = runner.Cleanup(Path(path), "public-fixture", "admin-fixture", ledger())
            cleanup.get = lambda table, **where: [copy.deepcopy(expected)]
            with patch.object(runner, "http", side_effect=runner.HttpOutcomeError("HTTP_400_BODY_WITHHELD", 400, "22P02")):
                with self.assertRaises(runner.HttpOutcomeError):
                    cleanup.remove("transactions", expected)
            saved = json.loads(cleanup.path.read_text())
            self.assertEqual(expected, saved["attempts"][key])
            self.assertEqual(400, saved["delete_outcomes"][key]["status"])
            self.assertEqual("22P02", saved["delete_outcomes"][key]["server_code"])
            self.assertTrue(saved["delete_outcomes"][key]["definitive_rejection"])

    def test_foreign_or_changed_row_is_preserved_before_delete(self):
        with tempfile.TemporaryDirectory() as path:
            cleanup = runner.Cleanup(Path(path), "public-fixture", "admin-fixture", ledger())
            expected = ledger()["operations"]["equal_tx"]["expected"]
            cleanup.get = lambda table, **where: [{**expected, "user_id": runner.B}]
            with patch.object(runner, "http", side_effect=AssertionError("No transport allowed")):
                with self.assertRaisesRegex(RuntimeError, "CLEANUP_ROW_CHANGED"):
                    cleanup.remove("transactions", expected)
            self.assertEqual({}, cleanup.state["attempts"])

    def test_reservation_is_durable_before_delete_and_lost_response_cannot_repeat(self):
        with tempfile.TemporaryDirectory() as path:
            cleanup = runner.Cleanup(Path(path), "public-fixture", "admin-fixture", ledger())
            expected = ledger()["operations"]["equal_tx"]["expected"]
            cleanup.get = lambda table, **where: [copy.deepcopy(expected)]
            def lost(*args, **kwargs):
                state = json.loads(cleanup.path.read_text())
                self.assertEqual(expected, state["attempts"]["transactions:" + TX])
                raise RuntimeError("Synthetic uncertain DELETE")
            with patch.object(runner, "http", side_effect=lost) as transport:
                with self.assertRaises(RuntimeError):
                    cleanup.remove("transactions", expected)
                with self.assertRaisesRegex(RuntimeError, "DELETE_ALREADY_ATTEMPTED"):
                    cleanup.remove("transactions", expected)
                self.assertEqual(1, transport.call_count)

    def test_absence_after_lost_delete_confirms_cleanup_without_a_second_write(self):
        with tempfile.TemporaryDirectory() as path:
            cleanup = runner.Cleanup(Path(path), "public-fixture", "admin-fixture", ledger())
            expected = ledger()["operations"]["equal_tx"]["expected"]
            key = "transactions:" + TX
            cleanup.state["attempts"][key] = expected
            cleanup.get = lambda table, **where: []
            with patch.object(runner, "http", side_effect=AssertionError("No transport allowed")):
                cleanup.remove("transactions", expected)
            self.assertEqual([key], cleanup.state["removed"])

    def test_reports_refuse_secrets_at_any_depth(self):
        with self.assertRaises(RuntimeError):
            runner.safe({"operations": [{"access_token": "never-output"}]})

    def test_already_completed_local_result_can_be_confirmed_without_replaying_cleanup(self):
        state = ledger()
        flags = {key: state[key] for key in ("run_id", "project_host", "session_a", "session_b")}
        flags.update(records_absent=True, logout_a=True, logout_b=True)
        result = {**state, "stage": "host_finalized", "cleanup_complete": True, "cleanup_pending": False}
        self.assertIs(result, runner.completed_local_result(result, state, flags))

    def test_pending_or_foreign_local_result_cannot_claim_completed_cleanup(self):
        state = ledger()
        flags = {key: state[key] for key in ("run_id", "project_host", "session_a", "session_b")}
        flags.update(records_absent=True, logout_a=True, logout_b=True)
        result = {**state, "stage": "host_finalized", "cleanup_complete": True, "cleanup_pending": False}
        for field, value in (("session_a", REQUEST), ("cleanup_complete", "true"), ("cleanup_pending", True), ("stage", "local_finalizing")):
            changed = {**result, field: value}
            with self.assertRaises(RuntimeError):
                runner.completed_local_result(changed, state, flags)
        with self.assertRaises(RuntimeError):
            runner.completed_local_result(result, state, {**flags, "logout_b": False})
        with self.assertRaises(RuntimeError):
            runner.completed_local_result(result, state, {**flags, "run_id": REQUEST})

    def test_secret_transport_cannot_follow_a_redirect(self):
        self.assertIsNone(runner.NoCredentialRedirect().redirect_request(None, None, 302, "Found", {}, "https://other.invalid"))

    def test_unsupported_json_values_are_rejected_instead_of_silently_becoming_null(self):
        with self.assertRaises(TypeError):
            runner.dump({"value": object()})


if __name__ == "__main__":
    unittest.main()
