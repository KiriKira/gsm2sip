#!/usr/bin/env python3
"""Derive a probe-only SDK from hash-pinned inputs; preserve all native libraries."""
import copy
import hashlib
import io
import json
from pathlib import Path
import struct
import subprocess
import zipfile
from validate_sdk_patch import validate_patch_offsets, validate_external_layout


ROOT = Path(__file__).resolve().parents[1]


def digest(data):
    return hashlib.sha256(data).hexdigest()


def download(url, expected, output):
    output.parent.mkdir(parents=True, exist_ok=True)
    if not output.exists():
        temporary = output.with_suffix(output.suffix + ".part")
        try:
            subprocess.run(["curl", "--fail", "--location", "--silent", "--show-error",
                            "--max-time", "180", "--output", str(temporary), url], check=True)
            if digest(temporary.read_bytes()) != expected:
                raise RuntimeError(f"Checksum mismatch: {output.name}")
            temporary.replace(output)
        finally:
            temporary.unlink(missing_ok=True)
    data = output.read_bytes()
    if digest(data) != expected:
        raise RuntimeError(f"Checksum mismatch in cached input: {output.name}")
    return data


def load_alignments(data):
    if data[:4] != b"\x7fELF" or data[4:6] != b"\x02\x01":
        raise RuntimeError("Probe requires a little-endian 64-bit ELF library")
    phoff = struct.unpack_from("<Q", data, 32)[0]
    size, count = struct.unpack_from("<HH", data, 54)
    return [struct.unpack_from("<Q", data, phoff + i * size + 48)[0]
            for i in range(count) if struct.unpack_from("<I", data, phoff + i * size)[0] == 1]


def main():
    validate_patch_offsets((ROOT / "sdk/external-playout.patch").read_text())
    pin = json.loads((ROOT / "sdk/pin.json").read_text())
    out = ROOT / "build/webrtc-sdk"
    cache = ROOT / "build/sdk-downloads"
    source = out / "source/org/webrtc/audio"
    source.mkdir(parents=True, exist_ok=True)
    aar = download(pin["aar_url"], pin["aar_sha256"], cache / "upstream.aar")
    notices = download("https://raw.githubusercontent.com/webrtc-sdk/android/" +
                       pin["publisher_commit"] + "/Licenses/WEBRTC.md",
                       pin["publisher_notices_sha256"], cache / "THIRD_PARTY_WEBRTC.md")
    (out / "assets/licenses").mkdir(parents=True, exist_ok=True)
    (out / "assets/licenses/THIRD_PARTY_WEBRTC.md").write_bytes(notices)
    for path, expected in pin["source_files"].items():
        url = ("https://raw.githubusercontent.com/webrtc-sdk/webrtc/"
               + pin["declared_source_commit"] + "/" + path)
        content = download(url, expected, cache / Path(path).name)
        target = source / Path(path).name if path.endswith(".java") else out / "assets/licenses" / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(content)
    subprocess.run(["patch", "--batch", "--fuzz=0", "-p1", "-i",
                    str(ROOT / "sdk/external-playout.patch"), "-d", str(out / "source")], check=True)
    validate_external_layout((source / "WebRtcAudioTrack.java").read_text())

    removed = []
    native = {}
    # Remove only the Java class rebuilt from the pinned source plus our patch.
    # The native JNI entry points and every native library remain byte-for-byte upstream.
    with zipfile.ZipFile(io.BytesIO(aar)) as original:
        classes = io.BytesIO()
        with zipfile.ZipFile(io.BytesIO(original.read("classes.jar"))) as old, \
                zipfile.ZipFile(classes, "w", compression=zipfile.ZIP_DEFLATED) as new:
            for item in old.infolist():
                name = item.filename
                if name == "org/webrtc/audio/WebRtcAudioTrack.class" or name.startswith(
                        "org/webrtc/audio/WebRtcAudioTrack$"):
                    removed.append(name)
                else:
                    new.writestr(copy.copy(item), old.read(name))
        if not removed:
            raise RuntimeError("The expected upstream Java playout class was not found")
        result = out / "external-playout.aar"
        with zipfile.ZipFile(result, "w", compression=zipfile.ZIP_DEFLATED) as derived:
            for item in original.infolist():
                data = original.read(item.filename)
                if item.filename.endswith(".so"):
                    abi = item.filename.split("/")[1]
                    if abi not in ("arm64-v8a", "x86_64"):
                        continue
                    alignments = load_alignments(data)
                    if not alignments or min(alignments) < 16384:
                        raise RuntimeError(f"Native library is not 16 KB aligned: {abi}")
                    native[abi] = {"sha256": digest(data), "load_alignments": alignments}
                derived.writestr(copy.copy(item), classes.getvalue() if item.filename == "classes.jar" else data)
        if set(native) != {"arm64-v8a", "x86_64"}:
            raise RuntimeError("Missing a required probe ABI")
        with zipfile.ZipFile(result) as derived:
            for abi in native:
                name = f"jni/{abi}/libjingle_peerconnection_so.so"
                if derived.read(name) != original.read(name):
                    raise RuntimeError("Unexpected modification to a native library")

    report = {"coordinate": pin["coordinate"], "upstream_sha256": digest(aar),
              "declared_source_commit": pin["declared_source_commit"],
              "patch_sha256": digest((ROOT / "sdk/external-playout.patch").read_bytes()),
              "removed_classes": removed, "native": native,
              "derived_aar_sha256": digest(result.read_bytes()),
              "native_runtime_validated": False, "cellular_audio_validated": False}
    (out / "provenance.json").write_text(json.dumps(report, indent=2) + "\n")
    print("Verified pinned SDK/source inputs, rebuilt Java playout source and unchanged 16 KB native libraries.")


if __name__ == "__main__":
    main()
