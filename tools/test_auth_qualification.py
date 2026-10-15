"""Offline fixtures only: no vault, subprocess, device or backend access."""
import importlib.util
from pathlib import Path
import unittest
import uuid

spec = importlib.util.spec_from_file_location('auth_qualification', Path(__file__).with_name('auth-qualification.py'))
qualification = importlib.util.module_from_spec(spec)
spec.loader.exec_module(qualification)


class AuthQualificationGuardsTest(unittest.TestCase):
    def setUp(self):
        self.run = str(uuid.uuid4())

    def result(self):
        return {'run_id': self.run, 'user_id': qualification.OWNER, 'email': qualification.EMAIL,
                'project': qualification.PROJECT, 'provisioning_run': qualification.PROVISIONING_RUN,
                'primary_session_id': str(uuid.uuid4()), 'secondary_session_id': str(uuid.uuid4()),
                'factor_id': str(uuid.uuid4()), 'stage': 'passed', 'cleanup_pending': False,
                **{key: True for key in ('password_login', 'totp_verified', 'others_confirmed', 'secondary_revoked',
                                        'current_refreshed', 'factor_removed', 'logout_primary', 'cleanup_local', 'cleanup_secondary')}}

    def test_exact_result_requires_actual_revocation_and_current_refresh(self):
        result = self.result()
        self.assertTrue(qualification.result_passed(result, self.run))
        for key in ('password_login', 'totp_verified', 'others_confirmed', 'secondary_revoked', 'current_refreshed',
                    'factor_removed', 'logout_primary', 'cleanup_local', 'cleanup_secondary'):
            for invalid in (False, None, 'true', 1):
                with self.subTest(key=key, invalid=invalid):
                    changed = dict(result, **{key: invalid})
                    self.assertFalse(qualification.result_passed(changed, self.run))

    def test_changed_owner_project_run_or_session_never_passes(self):
        result = self.result()
        for key in ('run_id', 'user_id', 'email', 'project', 'provisioning_run'):
            with self.subTest(key=key):
                self.assertFalse(qualification.result_passed(dict(result, **{key: 'foreign'}), self.run))
        self.assertFalse(qualification.result_passed(dict(result, secondary_session_id=result['primary_session_id']), self.run))
        for key in ('primary_session_id', 'secondary_session_id', 'factor_id'):
            self.assertFalse(qualification.result_passed(dict(result, **{key: 'not-a-uuid'}), self.run))

    def test_failed_flow_or_pending_cleanup_never_passes(self):
        result = self.result()
        self.assertFalse(qualification.result_passed(dict(result, stage='failed'), self.run))
        self.assertFalse(qualification.result_passed(dict(result, cleanup_pending=True), self.run))
        self.assertFalse(qualification.result_passed(dict(result, cleanup_pending=0), self.run))

    def test_only_a_password_is_selected_without_mutating_vault(self):
        value = {'version': 1, 'project': qualification.PROJECT, 'runId': qualification.PROVISIONING_RUN,
                 'accounts': [{'id': qualification.OWNER, 'email': qualification.EMAIL, 'password': 'synthetic-A-' + 'x' * 40},
                              {'id': 'foreign', 'email': 'other@example.invalid', 'password': 'synthetic-B-' + 'y' * 40}]}
        payload = qualification.credential_input(value, self.run)
        self.assertEqual(payload['password'], value['accounts'][0]['password'])
        self.assertNotIn('accounts', payload)
        self.assertEqual(len(value['accounts']), 2)
        self.assertEqual(payload['user_id'], qualification.OWNER)

    def test_foreign_or_duplicate_credential_binding_is_refused(self):
        value = {'version': 1, 'project': qualification.PROJECT, 'runId': qualification.PROVISIONING_RUN,
                 'accounts': [{'id': qualification.OWNER, 'email': qualification.EMAIL, 'password': 'synthetic-' + 'x' * 40}]}
        for key in ('project', 'runId'):
            with self.subTest(key=key), self.assertRaises(RuntimeError):
                qualification.credential_input(dict(value, **{key: 'foreign'}), self.run)
        with self.assertRaises(RuntimeError):
            qualification.credential_input(dict(value, accounts=value['accounts'] * 2), self.run)

    def test_invalid_run_is_refused_without_uncontrolled_exception(self):
        for value in (None, '', 'invalid', '00000000000000000000000000000000'):
            self.assertFalse(qualification.canonical(value))


if __name__ == '__main__':
    unittest.main()
