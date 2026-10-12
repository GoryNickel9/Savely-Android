import copy
import unittest
from qa_credentials import PROJECT, QA_RUN, QA_USERS, _validate


def fixture():
    return {"version": 1, "project": PROJECT, "runId": QA_RUN, "accounts": [
        {"email": email, "id": user_id, "password": "offline-fixture-" + str(index) * 40,
         "role": "admin-used-only-against-these-QA-accounts" if "-admin@" in email else "standard"}
        for index, (email, user_id) in enumerate(QA_USERS.items())
    ]}


class QaCredentialsTest(unittest.TestCase):
    def test_exact_reserved_fixture(self):
        value = fixture()
        self.assertIs(_validate(value), value)

    def test_wrong_project_or_run_or_version_rejected(self):
        for key in ("project", "runId", "version"):
            value = fixture()
            value[key] = "foreign"
            with self.assertRaisesRegex(RuntimeError, "secrets withheld"):
                _validate(value)

    def test_identity_and_role_changes_rejected(self):
        for key in ("id", "email", "role"):
            value = fixture()
            value["accounts"][0][key] = "foreign"
            with self.assertRaises(RuntimeError):
                _validate(value)

    def test_duplicate_account_rejected(self):
        value = fixture()
        value["accounts"][1] = copy.deepcopy(value["accounts"][0])
        with self.assertRaises(RuntimeError):
            _validate(value)

    def test_duplicate_or_short_password_rejected(self):
        for password in ("short", fixture()["accounts"][1]["password"]):
            value = fixture()
            value["accounts"][0]["password"] = password
            with self.assertRaises(RuntimeError) as result:
                _validate(value)
            self.assertNotIn(password, str(result.exception))

    def test_malformed_input_rejected(self):
        for value in ({}, None, [], {"version": 1, "project": PROJECT, "runId": QA_RUN, "accounts": None}):
            with self.assertRaises(RuntimeError):
                _validate(value)


if __name__ == "__main__":
    unittest.main()
