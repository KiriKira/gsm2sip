#!/usr/bin/env python3
"""Run the separate synthetic PCM instrumentation; never infer success from adb's exit code."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time
import uuid

ROOT = Path(__file__).resolve().parents[1]
GATEWAY = ROOT.parents[1]
PACKAGE = "com.callagent.gateway.webrtcprobe"


def controlled_failure(output):
    reports = re.findall(r"^INSTRUMENTATION_RESULT: probe_result=(.+)$", output, re.M)
    if not reports:
        return None
    return controlled_failure_report(json.loads(reports[-1]))


def controlled_failure_report(report):
    if not isinstance(report, dict) or report.get("status") != "FAIL":
        return None
    reasons = {"native_audio_or_ice_failed", "default_audio_device_active", "pcm_timeout", "ice_timeout", "opus_unavailable",
               "sdp_create_failed", "sdp_set_failed", "audio_scheduler_shutdown_failed",
               "ice_relay_shutdown_failed", "operation_timeout", "unexpected_exception"}
    failure_code = report.get("failure_code")
    result = {"status": "FAIL", "failure_code": failure_code
              if isinstance(failure_code, str) and failure_code in reasons else "unexpected_exception"}
    diagnostics = report.get("diagnostics", {})
    if not isinstance(diagnostics, dict):
        return result
    safe = {}
    for key, allowed in {
        "stage": {"initializing", "endpoint_a", "endpoint_b", "create_offer", "set_local_offer", "set_remote_offer",
                  "create_answer", "set_local_answer", "set_remote_answer", "wait_for_ice", "wait_for_pcm"},
        "cleanup_stage": {"not_started", "ice_relays", "peer_resources", "complete", "failed"},
    }.items():
        if isinstance(diagnostics.get(key), str) and diagnostics[key] in allowed:
            safe[key] = diagnostics[key]
    states = {"NEW", "CONNECTING", "CONNECTED", "DISCONNECTED", "FAILED", "CLOSED", "CHECKING", "COMPLETED", "GATHERING", "COMPLETE"}
    for side in ("a", "b"):
        for suffix in ("capture_frames", "playout_frames", "remote_tone_frames", "ice_received", "ice_delivered", "ice_callbacks", "ice_queued"):
            key = f"{side}_{suffix}"
            value = diagnostics.get(key)
            if type(value) is int and 0 <= value <= 1_000_000_000:
                safe[key] = value
        for suffix in ("connection_state", "ice_state", "ice_gathering_state"):
            key = f"{side}_{suffix}"
            if isinstance(diagnostics.get(key), str) and diagnostics[key] in states:
                safe[key] = diagnostics[key]
        rms_key = f"{side}_last_rms"
        rms = diagnostics.get(rms_key)
        if type(rms) in (int, float) and 0 <= rms <= 32768:
            safe[rms_key] = rms
    errors = {"capture_init", "capture_start", "capture", "playout_init", "playout_start", "playout", "ice",
              "candidate_rejected", "candidate_budget", "candidate_delivery_failed"}
    if isinstance(diagnostics.get("errors"), list):
        safe["errors"] = sorted({item for item in diagnostics["errors"] if isinstance(item, str) and item in errors})
    result["diagnostics"] = safe
    return result


def evaluate_instrumentation(output):
    codes = re.findall(r"^INSTRUMENTATION_CODE:\s*(-?\d+)\s*$", output, re.M)
    reports = re.findall(r"^INSTRUMENTATION_RESULT: probe_result=(.+)$", output, re.M)
    if not codes or codes[-1] != "-1" or not reports:
        raise RuntimeError("instrumentation_failed_or_missing_result")
    return evaluate_native_result(json.loads(reports[-1]))


def evaluate_native_result(result):
    if result.get("status") != "PASS" or result.get("scope") != "synthetic_webrtc_pcm":
        raise RuntimeError("native_probe_did_not_pass")
    observed = result.get("observed", {})
    for name in ("a_capture_frames", "b_capture_frames", "a_remote_tone_frames", "b_remote_tone_frames"):
        if type(observed.get(name)) is not int or observed[name] < 30:
            raise RuntimeError("missing_bidirectional_pcm_evidence")
    if (observed.get("opus_only") is not True or observed.get("both_peers_connected") is not True
            or observed.get("default_microphone") != "DISABLED"
            or observed.get("default_audio_track") != "BYPASSED"):
        raise RuntimeError("unexpected_media_path")
    if result.get("cellular_audio") != "NOT_RUN" or result.get("turn") != "NOT_RUN":
        raise RuntimeError("probe_scope_mismatch")
    return result


def evaluate_activity_result(result, expected_run_id):
    if result.get("run_id") != expected_run_id or result.get("activity_result_code") != -1:
        raise RuntimeError("stale_or_failed_activity_result")
    return evaluate_native_result(result)


def run(command, timeout=30):
    return subprocess.run(command, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                          check=True, timeout=timeout).stdout


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--serial", default=os.environ.get("ANDROID_SERIAL"))
    parser.add_argument("--output", type=Path, default=ROOT / "build/probe-result.json")
    parser.add_argument("--instrumentation-timeout", type=int, default=90,
                        help="Outer adb timeout; does not change native ICE/PCM deadlines")
    parser.add_argument("--driver", choices=("instrumentation", "activity"), default="instrumentation")
    parser.add_argument("--reuse-identical-apk", action="store_true",
                        help="Reuse an installed APK only after its SHA-256 matches the fresh build")
    args = parser.parse_args()
    if not 1 <= args.instrumentation_timeout <= 600:
        parser.error("--instrumentation-timeout must be between 1 and 600 seconds")
    result = {"status": "FAIL", "scope": "synthetic_webrtc_pcm", "cellular_audio": "NOT_RUN", "turn": "NOT_RUN"}
    result["instrumentation_timeout_seconds"] = args.instrumentation_timeout
    def stage(name):
        result["runner_stage"] = name
        print("PROBE_STAGE: " + name, flush=True)
    exit_code = 1
    try:
        stage("device_selection")
        listing = run(["adb", "devices"])
        devices = [line.split()[0] for line in listing.splitlines()[1:]
                   if len(line.split()) == 2 and line.split()[1] == "device"]
        if not devices or (args.serial and args.serial not in devices):
            result.update(status="BLOCKED", reason="no_selected_android_device")
            exit_code = 2
            return exit_code
        if not args.serial and len(devices) != 1:
            result.update(status="BLOCKED", reason="select_one_device_with_serial")
            exit_code = 2
            return exit_code
        serial = args.serial or devices[0]
        adb = ["adb", "-s", serial]
        stage("device_metadata")
        sdk = int(run(adb + ["shell", "getprop", "ro.build.version.sdk"]).strip())
        abi = run(adb + ["shell", "getprop", "ro.product.cpu.abi"]).strip()
        if sdk < 26 or abi not in ("arm64-v8a", "x86_64"):
            result.update(status="BLOCKED", reason="unsupported_probe_api_or_abi", sdk_int=sdk, abi=abi)
            exit_code = 2
            return exit_code
        result.update(device_serial=serial, sdk_int=sdk, abi=abi)
        # Always build from the current checked-out inputs; a stale APK cannot satisfy this runner.
        stage("build")
        run(["bash", str(GATEWAY / "gradlew"), "-p", str(ROOT), "assembleDebug",
             "--no-daemon", "--max-workers=1",
             "-Dorg.gradle.jvmargs=-Xmx1536m -XX:MaxMetaspaceSize=512m -Dfile.encoding=UTF-8",
             "-Pkotlin.compiler.execution.strategy=in-process"], timeout=300)
        apk = ROOT / "build/outputs/apk/debug/Gsm2SipWebRtcProbe-debug.apk"
        result["apk_sha256"] = hashlib.sha256(apk.read_bytes()).hexdigest()
        result["sdk_provenance"] = json.loads((ROOT / "build/webrtc-sdk/provenance.json").read_text())
        reused = False
        if args.reuse_identical_apk:
            stage("installed_apk_verification")
            try:
                locations = run(adb + ["shell", "pm", "path", PACKAGE]).splitlines()
                paths = [line.removeprefix("package:").strip() for line in locations if line.startswith("package:")]
                if len(paths) == 1 and re.fullmatch(r"/data/app/[A-Za-z0-9_~=/.-]+/base\.apk", paths[0]):
                    installed_hash = run(adb + ["shell", "sha256sum", paths[0]]).split()[0]
                    if installed_hash == result["apk_sha256"]:
                        result["installed_apk_sha256"] = installed_hash
                        reused = True
            except (OSError, subprocess.SubprocessError, IndexError):
                pass  # A missing or unverifiable installation must be replaced.
        result["apk_installation"] = "REUSED_IDENTICAL" if reused else "INSTALLED"
        if reused and args.driver == "instrumentation":
            stage("fresh_process")
            run(adb + ["shell", "am", "force-stop", PACKAGE])
        elif not reused:
            stage("install")
            run(adb + ["install", "--no-streaming", "-r", str(apk)], timeout=60)
        if args.driver == "activity":
            stage("activity")
            nonce = str(uuid.uuid4())
            run(adb + ["shell", "am", "start", "-W", "-n",
                       PACKAGE + "/com.callagent.webrtcprobe.WebRtcProbeActivity", "--es", "run_id", nonce], timeout=45)
            deadline = time.monotonic() + args.instrumentation_timeout
            native = None
            while time.monotonic() < deadline:
                try:
                    candidate = json.loads(run(adb + ["exec-out", "run-as", PACKAGE, "cat",
                        "files/webrtc-probe-activity-result.json"], timeout=10))
                    if candidate.get("run_id") == nonce:
                        native = candidate
                        break
                except (ValueError, subprocess.SubprocessError):
                    pass
                time.sleep(1)
            if native is None:
                raise RuntimeError("activity_result_timeout")
            failure_report = controlled_failure_report(native)
            if failure_report is not None:
                result["native_failure"] = failure_report
            result.update(evaluate_activity_result(native, nonce))
            result["native_driver"] = "debug_activity"
        else:
            stage("instrumentation")
            output = run(adb + ["shell", "am", "instrument", "-w", "-r",
                        PACKAGE + "/com.callagent.webrtcprobe.WebRtcProbeInstrumentation"],
                         timeout=args.instrumentation_timeout)
            failure_report = controlled_failure(output)
            if failure_report is not None:
                result["native_failure"] = failure_report
            result.update(evaluate_instrumentation(output))
            result["native_driver"] = "instrumentation"
        stage("complete")
        exit_code = 0
        return exit_code
    except (RuntimeError, ValueError, OSError, subprocess.SubprocessError) as failure:
        # Native output can contain signaling details. Store only controlled runner errors.
        result["reason"] = str(failure) if type(failure) is RuntimeError else type(failure).__name__
        return exit_code
    finally:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(result, indent=2) + "\n")
        print(json.dumps(result, indent=2))


if __name__ == "__main__":
    sys.exit(main())
