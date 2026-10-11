import base64
import importlib.util
import json
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('roadmap_smoke', Path(__file__).with_name('run-roadmap-smoke.py'))
smoke = importlib.util.module_from_spec(spec)
spec.loader.exec_module(smoke)


class RoadmapSmokeGuardsTest(unittest.TestCase):
    def test_only_confirmed_documented_account_without_existing_mfa(self):
        user = {'id': smoke.QA_ID, 'email': smoke.QA_EMAIL, 'email_confirmed_at': '2026-01-01'}
        smoke.qa_identity(user)
        for changed in ({'id': 'other'}, {'email': 'other@example.com'}, {'email_confirmed_at': None},
                        {'factors': [{'status': 'verified'}]}, {'factors': [{'status': 'unverified'}]}):
            with self.assertRaises(RuntimeError):
                smoke.qa_identity(user | changed)

    def test_session_identity_requires_canonical_uuid(self):
        session = 'aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee'
        def token(value):
            encoded = base64.urlsafe_b64encode(json.dumps(value).encode()).decode().rstrip('=')
            return 'header.' + encoded + '.signature'
        self.assertEqual(session, smoke.jwt_session_id(token({'session_id': session})))
        for value in ({}, {'session_id': session.upper()}, {'session_id': 'invalid'}):
            with self.assertRaises(RuntimeError):
                smoke.jwt_session_id(token(value))

    def test_reports_reject_foreign_identity_and_nested_sensitive_fields(self):
        report = {'run_id': 'run', 'session_id': 'session', 'user_id': smoke.QA_ID,
                  'project_host': smoke.PROJECT + '.supabase.co', 'contracts': [{'status': 'passed'}]}
        self.assertEqual(report, smoke.sanitized_report(report, 'run', 'session'))
        for changed in ({'user_id': 'other'}, {'project_host': 'other.supabase.co'},
                        {'contracts': [{'refresh_token': 'withheld'}]}, {'payload': {}},
                        {'contracts': [{'response_body': 'withheld'}]}):
            with self.assertRaises(RuntimeError):
                smoke.sanitized_report(report | changed, 'run', 'session')


if __name__ == '__main__':
    unittest.main()
