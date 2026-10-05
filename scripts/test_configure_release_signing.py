"""Exercise real temporary keystores without contacting GitHub or storing repo keys."""
import argparse
import contextlib
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import shutil
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("signing", Path(__file__).with_name("configure-release-signing.py"))
signing = importlib.util.module_from_spec(spec)
spec.loader.exec_module(signing)


@unittest.skipUnless(shutil.which("keytool"), "JDK keytool is required")
class SigningTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory()
        cls.directory = Path(cls.temp.name) / "private-backup"
        cls.args = argparse.Namespace(repo="owner/test-android", keystore=None,
                                      alias=None, output_dir=cls.directory)
        cls.initial = signing.prepare(cls.args, already_has_secrets=False, existing_pin=None)

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def test_resume_preserves_identity_and_credentials(self):
        before = hashlib.sha256(self.initial[1].read_bytes()).hexdigest()
        resumed = signing.prepare(self.args, already_has_secrets=True, existing_pin=self.initial[3])
        self.assertEqual(resumed[2:], self.initial[2:])
        self.assertEqual(hashlib.sha256(resumed[1].read_bytes()).hexdigest(), before)

    def test_backup_is_private(self):
        if os.name == "posix":
            self.assertEqual(self.directory.stat().st_mode & 0o777, 0o700)
            self.assertEqual(self.initial[1].stat().st_mode & 0o777, 0o600)
            self.assertEqual((self.directory / "credentials.json").stat().st_mode & 0o777, 0o600)

    def test_different_pinned_identity_is_rejected(self):
        with self.assertRaisesRegex(RuntimeError, "pinned signer"):
            signing.prepare(self.args, already_has_secrets=True, existing_pin="0" * 64)

    def test_existing_repository_never_gets_a_new_key(self):
        args = argparse.Namespace(repo=self.args.repo, keystore=None, alias=None,
                                  output_dir=Path(self.temp.name) / "other-backup")
        with self.assertRaisesRegex(RuntimeError, "replacement signer"):
            signing.prepare(args, already_has_secrets=True, existing_pin=None)
        self.assertFalse((args.output_dir / "release.keystore").exists())

    def test_import_keeps_original_certificate(self):
        args = argparse.Namespace(repo="owner/imported-android", keystore=self.initial[1],
                                  alias=self.initial[2]["alias"], output_dir=Path(self.temp.name) / "imported")
        password = self.initial[2]["store_password"]
        with patch.dict(os.environ, GSM_RELEASE_STORE_PASSWORD=password, GSM_RELEASE_KEY_PASSWORD=password):
            imported = signing.prepare(args, already_has_secrets=True, existing_pin=self.initial[3])
        self.assertEqual(imported[3], self.initial[3])

    def test_upload_uses_stdin_and_does_not_print_passwords(self):
        calls = []
        real_run = signing.run
        def stub(args, **kwargs):
            if args[0] == "keytool":
                return real_run(args, **kwargs)
            calls.append((args, kwargs))
            return b""
        def api(path, **kwargs):
            if "/variables/" in path:
                return {"value": self.initial[3]}
            return {"secrets": [{"name": name} for name in signing.SECRET_NAMES]}
        output = io.StringIO()
        with patch("sys.argv", ["signing", "--repo", self.args.repo, "--output-dir", str(self.directory)]), \
                patch.object(signing, "api_json", side_effect=api), \
                patch.object(signing, "run", side_effect=stub), contextlib.redirect_stdout(output):
            signing.main()
        self.assertEqual(calls[0][0][1:4], ["variable", "set", signing.PIN_NAME])
        secret_calls = calls[1:]
        self.assertEqual(len(secret_calls), 4)
        for args, kwargs in secret_calls:
            self.assertIn("data", kwargs)
            self.assertNotIn("--body", args)
        self.assertNotIn(self.initial[2]["store_password"], output.getvalue())
        self.assertNotIn(self.initial[2]["store_password"], json.dumps([args for args, _ in calls]))


if __name__ == "__main__":
    unittest.main()
