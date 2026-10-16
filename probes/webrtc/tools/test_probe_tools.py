import copy
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from prepare_sdk import download
from run_probe import evaluate_instrumentation


class EvidenceTests(unittest.TestCase):
    def report(self):
        return {"status": "PASS", "scope": "synthetic_webrtc_pcm", "cellular_audio": "NOT_RUN", "turn": "NOT_RUN",
                "observed": {"a_capture_frames": 50, "b_capture_frames": 50, "a_remote_tone_frames": 35,
                             "b_remote_tone_frames": 35, "opus_only": True, "both_peers_connected": True,
                             "default_microphone": "DISABLED", "default_audio_track": "BYPASSED"}}

    def output(self, report, code=-1):
        return "INSTRUMENTATION_RESULT: probe_result=" + json.dumps(report) + f"\nINSTRUMENTATION_CODE: {code}\n"

    def test_complete_native_evidence_is_accepted(self):
        self.assertEqual(self.report(), evaluate_instrumentation(self.output(self.report())))

    def test_successful_adb_exit_or_result_text_cannot_hide_instrumentation_failure(self):
        for output in ["INSTRUMENTATION_CODE: -1\n", self.output(self.report(), code=0),
                       self.output(dict(self.report(), status="FAIL"))]:
            with self.subTest(output=output), self.assertRaises(RuntimeError):
                evaluate_instrumentation(output)

    def test_silence_or_one_direction_cannot_pass(self):
        for name in ("a_capture_frames", "b_capture_frames", "a_remote_tone_frames", "b_remote_tone_frames"):
            report = copy.deepcopy(self.report())
            report["observed"][name] = 0
            with self.subTest(field=name), self.assertRaises(RuntimeError):
                evaluate_instrumentation(self.output(report))

    def test_default_hardware_or_false_cellular_claim_is_rejected(self):
        report = self.report()
        report["observed"]["default_audio_track"] = "ACTIVE"
        with self.assertRaises(RuntimeError):
            evaluate_instrumentation(self.output(report))
        report = self.report()
        report["cellular_audio"] = "PASS"
        with self.assertRaises(RuntimeError):
            evaluate_instrumentation(self.output(report))

    def test_modified_cached_sdk_is_not_used_or_silently_replaced(self):
        with tempfile.TemporaryDirectory() as directory:
            cached = Path(directory) / "sdk.aar"
            cached.write_bytes(b"modified artifact")
            with patch("prepare_sdk.subprocess.run") as request, self.assertRaises(RuntimeError):
                download("https://example.invalid/sdk.aar", "0" * 64, cached)
            request.assert_not_called()
            self.assertEqual(b"modified artifact", cached.read_bytes())


if __name__ == "__main__":
    unittest.main()
