import copy
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from prepare_sdk import download
from run_probe import controlled_failure, evaluate_activity_result, evaluate_instrumentation


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

    def test_activity_rejects_stale_nonce_and_failed_result_code(self):
        report = dict(self.report(), run_id="fresh-run", activity_result_code=-1)
        self.assertEqual(report, evaluate_activity_result(report, "fresh-run"))
        for invalid in (dict(report, run_id="old-run"), dict(report, activity_result_code=0)):
            with self.assertRaises(RuntimeError):
                evaluate_activity_result(invalid, "fresh-run")

    def test_activity_keeps_the_same_bidirectional_pcm_and_audio_path_requirements(self):
        for field, value in (("b_remote_tone_frames", 0), ("default_audio_track", "ACTIVE")):
            report = dict(self.report(), run_id="fresh-run", activity_result_code=-1)
            report["observed"] = dict(report["observed"], **{field: value})
            with self.assertRaises(RuntimeError):
                evaluate_activity_result(report, "fresh-run")

    def test_failure_keeps_stage_and_counters_without_native_payloads(self):
        report = {"status": "FAIL", "failure_code": "pcm_timeout", "message": "secret SDP",
                  "diagnostics": {"stage": "wait_for_pcm", "cleanup_stage": "complete", "a_capture_frames": 123,
                                  "b_remote_tone_frames": 0, "a_connection_state": "CONNECTED",
                                  "errors": ["ice", "secret candidate"], "sdp": "secret SDP"}}
        result = controlled_failure(self.output(report, code=0))
        self.assertEqual("pcm_timeout", result["failure_code"])
        self.assertEqual(123, result["diagnostics"]["a_capture_frames"])
        self.assertEqual(["ice"], result["diagnostics"]["errors"])
        self.assertNotIn("secret", json.dumps(result))
        with self.assertRaises(RuntimeError):
            evaluate_instrumentation(self.output(report, code=0))

    def test_failure_extractor_does_not_accept_a_pass_claim_or_unknown_text(self):
        self.assertIsNone(controlled_failure(self.output(self.report(), code=0)))
        report = {"status": "FAIL", "failure_code": "secret SDP",
                  "diagnostics": {"stage": "secret SDP", "a_capture_frames": True,
                                  "a_connection_state": "secret SDP", "errors": [{"sdp": "secret"}]}}
        self.assertEqual({"status": "FAIL", "failure_code": "unexpected_exception", "diagnostics": {"errors": []}},
                         controlled_failure(self.output(report, code=0)))

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
