import unittest
from native_auth_config import CALLBACK, merged_redirects


class NativeAuthConfigTest(unittest.TestCase):
    def test_additive_and_idempotent(self):
        original = "https://savely.cc/auth/callback,https://www.savely.cc/reset-password"
        expected = original + "," + CALLBACK
        self.assertEqual(expected, merged_redirects(original))
        self.assertEqual(expected, merged_redirects(expected))
        self.assertEqual(CALLBACK, merged_redirects(""))

    def test_invalid_input_cannot_replace_the_allowlist(self):
        for value in (None, [], {}):
            with self.assertRaises(RuntimeError):
                merged_redirects(value)
