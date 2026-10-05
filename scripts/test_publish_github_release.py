"""Offline tests for immutable publication and recovery; never contact GitHub."""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("publisher", Path(__file__).with_name("publish-github-release.py"))
publisher = importlib.util.module_from_spec(spec)
spec.loader.exec_module(publisher)
COMMIT = "a" * 40


class PublicationTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name)
        (self.directory / "test.apk").write_bytes(b"signed apk fixture")
        data = (self.directory / "test.apk").read_bytes()
        manifest = {"source_commit": COMMIT, "version": "test", "artifacts": [
            {"name": "test.apk", "sha256": hashlib.sha256(data).hexdigest(), "size": len(data)}]}
        (self.directory / "release-manifest.json").write_text(json.dumps(manifest))
        sums = "".join(f"{publisher.digest(self.directory / name)}  {name}\n"
                       for name in ("test.apk", "release-manifest.json"))
        (self.directory / "SHA256SUMS.txt").write_text(sums)
        self.files = publisher.validate_payload(self.directory, COMMIT)
        self.args = argparse.Namespace(directory=self.directory, commit=COMMIT, repo="owner/repo",
                                       tag="build-1-aaaaaaa", name="Build", prerelease=True)
        self.release = {"id": 1, "draft": True, "prerelease": True, "html_url": "https://example.test/release"}
        self.assets = []
        self.calls = []

    def asset(self, name):
        expected = self.files[name]
        return {"id": len(self.assets) + 10, "name": name, "state": "uploaded",
                "size": expected["size"], "digest": "sha256:" + expected["sha256"]}

    def api(self, repo, path, **kwargs):
        self.calls.append((path, kwargs))
        if path.startswith("git/ref/"):
            return {"object": {"type": "commit", "sha": COMMIT}}
        if path.startswith("releases/tags/"):
            return dict(self.release)
        if path == "releases/1/assets?per_page=100&page=1":
            return list(self.assets)
        if path.startswith("releases/assets/"):
            asset_id = int(path.rsplit("/", 1)[1])
            self.assets = [asset for asset in self.assets if asset["id"] != asset_id]
            return None
        if path == "releases/1":
            self.release.update(kwargs["body"])
            return dict(self.release)
        raise AssertionError(f"Unexpected API call: {path}")

    def upload(self, args):
        self.assertEqual(args[:3], ["gh", "release", "upload"])
        self.assertNotIn("--clobber", args)
        self.assets.append(self.asset(Path(args[4]).name))

    def test_tampered_payload_is_rejected_before_network(self):
        (self.directory / "test.apk").write_bytes(b"tampered")
        with patch.object(publisher, "api") as network:
            with self.assertRaises(RuntimeError):
                publisher.publish(self.args)
            network.assert_not_called()

    def test_manifest_source_mismatch(self):
        with self.assertRaisesRegex(RuntimeError, "source commit"):
            publisher.validate_payload(self.directory, "b" * 40)

    def test_draft_partial_upload_recovers(self):
        correct = self.asset("test.apk")
        self.assets = [correct, {"id": 99, "name": "old.apk", "size": 1, "state": "uploaded", "digest": "bad"}]
        with patch.object(publisher, "api", side_effect=self.api), patch.object(publisher, "run", side_effect=self.upload) as upload:
            publisher.publish(self.args)
        self.assertFalse(self.release["draft"])
        self.assertEqual(upload.call_count, len(self.files) - 1)
        self.assertEqual({asset["name"] for asset in self.assets}, set(self.files))

    def test_public_identical_release_is_idempotent(self):
        self.release["draft"] = False
        self.assets = [self.asset(name) for name in self.files]
        with patch.object(publisher, "api", side_effect=self.api), patch.object(publisher, "run") as upload:
            publisher.publish(self.args)
        upload.assert_not_called()
        self.assertTrue(all(call[1].get("method", "GET") == "GET" for call in self.calls))

    def test_public_mismatch_is_never_overwritten(self):
        self.release["draft"] = False
        self.assets = [self.asset(name) for name in self.files]
        self.assets[0]["digest"] = "sha256:" + "b" * 64
        with patch.object(publisher, "api", side_effect=self.api), patch.object(publisher, "run") as upload:
            with self.assertRaisesRegex(RuntimeError, "never overwritten"):
                publisher.publish(self.args)
        upload.assert_not_called()
        self.assertTrue(all(call[1].get("method", "GET") == "GET" for call in self.calls))

    def test_bad_server_digest_keeps_release_draft(self):
        def bad_upload(args):
            self.upload(args)
            self.assets[-1]["digest"] = None
        with patch.object(publisher, "api", side_effect=self.api), patch.object(publisher, "run", side_effect=bad_upload):
            with self.assertRaisesRegex(RuntimeError, "remains a draft"):
                publisher.publish(self.args)
        self.assertTrue(self.release["draft"])

    def test_tag_is_never_retargeted(self):
        with patch.object(publisher, "api", return_value={"object": {"type": "commit", "sha": "b" * 40}}) as network:
            with self.assertRaisesRegex(RuntimeError, "retarget"):
                publisher.ensure_tag("owner/repo", "v1.0.0", COMMIT)
        self.assertEqual(network.call_count, 1)

    def test_symlink_is_rejected(self):
        (self.directory / "test.apk").unlink()
        (self.directory / "test.apk").symlink_to(self.directory / "release-manifest.json")
        with self.assertRaisesRegex(RuntimeError, "Invalid release asset"):
            publisher.validate_payload(self.directory, COMMIT)


if __name__ == "__main__":
    unittest.main()
