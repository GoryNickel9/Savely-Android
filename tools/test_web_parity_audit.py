import unittest
from web_parity_audit import classify


class ClassificationTest(unittest.TestCase):
    def test_reports_all_affected_modules_and_unknown_paths(self):
        modules = [
            {"id": "auth", "sources": ["api/**", "src/pages/Auth*"]},
            {"id": "shared", "sources": ["api/**"]},
        ]
        self.assertEqual(["auth", "shared"], [m["id"] for m in classify("api/admin.ts", modules)])
        self.assertEqual(["auth"], [m["id"] for m in classify("src/pages/Auth.tsx", modules)])
        self.assertEqual([], classify("src/new-feature.ts", modules))


if __name__ == "__main__":
    unittest.main()
