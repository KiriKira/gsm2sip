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

ROOT = Path(__file__).resolve().parents[1]
GATEWAY = ROOT.parents[1]
PACKAGE = "com.callagent.gateway.webrtcprobe"


def evaluate_instrumentation(output):
    codes = re.findall(r"^INSTRUMENTATION_CODE:\s*(-?\d+)\s*$", output, re.M)
    reports = re.findall(r"^INSTRUMENTATION_RESULT: probe_result=(.+)$", output, re.M)
    if not codes or codes[-1] != "-1" or not reports:
        raise RuntimeError("instrumentation_failed_or_missing_result")
    result = json.loads(reports[-1])
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


def run(command, timeout=30):
    return subprocess.run(command, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                          check=True, timeout=timeout).stdout


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--serial", default=os.environ.get("ANDROID_SERIAL"))
    parser.add_argument("--output", type=Path, default=ROOT / "build/probe-result.json")
    args = parser.parse_args()
    result = {"status": "FAIL", "scope": "synthetic_webrtc_pcm", "cellular_audio": "NOT_RUN", "turn": "NOT_RUN"}
    exit_code = 1
    try:
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
        sdk = int(run(adb + ["shell", "getprop", "ro.build.version.sdk"]).strip())
        abi = run(adb + ["shell", "getprop", "ro.product.cpu.abi"]).strip()
        if sdk < 26 or abi not in ("arm64-v8a", "x86_64"):
            result.update(status="BLOCKED", reason="unsupported_probe_api_or_abi", sdk_int=sdk, abi=abi)
            exit_code = 2
            return exit_code
        result.update(device_serial=serial, sdk_int=sdk, abi=abi)
        # Always build from the current checked-out inputs; a stale APK cannot satisfy this runner.
        run(["bash", str(GATEWAY / "gradlew"), "-p", str(ROOT), "assembleDebug",
             "--no-daemon", "--max-workers=2"], timeout=300)
        apk = ROOT / "build/outputs/apk/debug/Gsm2SipWebRtcProbe-debug.apk"
        result["apk_sha256"] = hashlib.sha256(apk.read_bytes()).hexdigest()
        result["sdk_provenance"] = json.loads((ROOT / "build/webrtc-sdk/provenance.json").read_text())
        run(adb + ["install", "-r", str(apk)], timeout=60)
        output = run(adb + ["shell", "am", "instrument", "-w", "-r",
                            PACKAGE + "/com.callagent.webrtcprobe.WebRtcProbeInstrumentation"], timeout=90)
        result.update(evaluate_instrumentation(output))
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
