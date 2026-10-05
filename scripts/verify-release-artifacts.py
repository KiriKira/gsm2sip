#!/usr/bin/env python3
"""Verify a signed gateway release APK and the Magisk ZIP before upload."""

from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import struct
import subprocess
import sys
import zipfile


APPLICATION_ID = "com.callagent.gateway"
EXPECTED_MAGISK_APK = "system/priv-app/Gateway/Gateway.apk"
EXPECTED_ABIS = {
    "tinymix": (2, 183, "arm64-v8a"),
    "tinymix32": (1, 40, "armeabi-v7a"),
    "tinymix-x86_64": (2, 62, "x86_64"),
    "tinymix-x86": (1, 3, "x86"),
}


def fail(message: str) -> "NoReturn":
    print(f"release verification failed: {message}", file=sys.stderr)
    raise SystemExit(1)


def required_env(name: str) -> str:
    value = os.environ.get(name, "")
    if not value:
        fail(f"required environment value {name} is missing")
    return value


def normalize_fingerprint(value: str) -> str:
    normalized = value.replace(":", "").strip().lower()
    if not re.fullmatch(r"[0-9a-f]{64}", normalized):
        fail("GSM_RELEASE_CERT_SHA256 must be a 64-character SHA-256 fingerprint")
    return normalized


def run(args: list[str]) -> str:
    try:
        completed = subprocess.run(args, check=True, text=True, capture_output=True)
    except FileNotFoundError:
        fail(f"required Android release tool was not found: {args[0]}")
    except subprocess.CalledProcessError as error:
        detail = (error.stdout + error.stderr).strip()
        fail(f"command failed ({args[0]}): {detail or error.returncode}")
    return completed.stdout + completed.stderr


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def zip_entry_sha256(archive: zipfile.ZipFile, info: zipfile.ZipInfo) -> str:
    digest = hashlib.sha256()
    with archive.open(info, "r") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def normalized_zip_names(archive: zipfile.ZipFile) -> dict[str, zipfile.ZipInfo]:
    result: dict[str, zipfile.ZipInfo] = {}
    for info in archive.infolist():
        name = info.filename
        while name.startswith("./"):
            name = name[2:]
        result[name] = info
    return result


def verify_elf(path: str, info: zipfile.ZipInfo, archive: zipfile.ZipFile,
               expected_class: int, expected_machine: int, abi: str) -> None:
    with archive.open(info, "r") as source:
        header = source.read(20)
    if len(header) < 20 or header[:4] != b"\x7fELF":
        fail(f"Magisk tool {path} is missing or is not an ELF binary")
    elf_class = header[4]
    data_encoding = header[5]
    if data_encoding not in (1, 2):
        fail(f"Magisk tool {path} has an unsupported ELF byte order")
    byte_order = "<" if data_encoding == 1 else ">"
    machine = struct.unpack_from(byte_order + "H", header, 18)[0]
    if (elf_class, machine) != (expected_class, expected_machine):
        fail(f"Magisk tool {path} has the wrong ABI; expected {abi}")


def main() -> None:
    version_code = int(required_env("GSM_RELEASE_VERSION_CODE"))
    version_name = required_env("GSM_RELEASE_VERSION_NAME")
    expected_fingerprint = normalize_fingerprint(required_env("GSM_RELEASE_CERT_SHA256"))
    source_commit = required_env("GITHUB_SHA")
    if not re.fullmatch(r"[0-9a-fA-F]{40,64}", source_commit):
        fail("GITHUB_SHA is not a full source commit ID")

    android_home = Path(required_env("ANDROID_HOME"))
    build_tools_version = required_env("ANDROID_BUILD_TOOLS_VERSION")
    tools = android_home / "build-tools" / build_tools_version
    apksigner = tools / "apksigner"
    aapt = tools / "aapt"
    zipalign = tools / "zipalign"
    apk = Path("app/build/outputs/apk/release/app-release.apk")
    module = Path("gateway-magisk.zip")
    if not apk.is_file():
        fail(f"signed release APK is missing: {apk}")
    if not module.is_file():
        fail(f"release Magisk ZIP is missing: {module}")

    verification = run([str(apksigner), "verify", "--verbose", "--print-certs", str(apk)])
    fingerprints = re.findall(
        r"^Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F:]+)$",
        verification,
        flags=re.MULTILINE,
    )
    if len(fingerprints) != 1:
        fail("APK must have exactly one pinned release signer")
    actual_fingerprint = normalize_fingerprint(fingerprints[0])
    if actual_fingerprint != expected_fingerprint:
        fail("APK signing certificate does not match GSM_RELEASE_CERT_SHA256")

    badging = run([str(aapt), "dump", "badging", str(apk)])
    package_line = re.search(
        r"^package: name='([^']+)' versionCode='(\d+)' versionName='([^']*)'",
        badging,
        flags=re.MULTILINE,
    )
    if not package_line:
        fail("aapt could not read the APK package metadata")
    package_name, actual_code, actual_name = package_line.groups()
    if package_name != APPLICATION_ID:
        fail(f"unexpected APK package name: {package_name}")
    if int(actual_code) != version_code or actual_name != version_name:
        fail("APK versionCode/versionName does not match release metadata")
    if re.search(r"^application-debuggable$", badging, flags=re.MULTILINE):
        fail("release APK is marked debuggable")

    run([str(zipalign), "-c", "-P", "16", "-v", "4", str(apk)])

    try:
        archive = zipfile.ZipFile(module)
    except (OSError, zipfile.BadZipFile) as error:
        fail(f"Magisk module is not a valid ZIP: {error}")
    with archive:
        corrupt_entry = archive.testzip()
        if corrupt_entry:
            fail(f"Magisk ZIP entry is corrupt: {corrupt_entry}")
        names = normalized_zip_names(archive)
        module_properties = names.get("module.prop")
        if module_properties is None:
            fail("Magisk ZIP is missing module.prop")
        with archive.open(module_properties, "r") as source:
            module_text = source.read(64 * 1024).decode("utf-8")
        module_code = re.findall(r"(?m)^versionCode=(\d+)$", module_text)
        module_version = re.findall(r"(?m)^version=(.*)$", module_text)
        if module_code != [str(version_code)] or module_version != [version_name]:
            fail("Magisk module version metadata does not match the signed APK")
        embedded_apk = names.get(EXPECTED_MAGISK_APK)
        if embedded_apk is None:
            fail(f"Magisk ZIP is missing {EXPECTED_MAGISK_APK}")
        apk_hash = sha256(apk)
        if zip_entry_sha256(archive, embedded_apk) != apk_hash:
            fail("Magisk ZIP does not contain the exact signed release APK bytes")
        for path, (expected_class, expected_machine, abi) in EXPECTED_ABIS.items():
            info = names.get(path)
            if info is None:
                fail(f"Magisk ZIP is missing the {abi} runtime tool ({path})")
            verify_elf(path, info, archive, expected_class, expected_machine, abi)

    output_dir = Path("release-artifact/payload")
    output_dir.mkdir(parents=True, exist_ok=True)
    staged_apk = output_dir / "gateway.apk"
    staged_module = output_dir / "gateway-magisk.zip"
    shutil.copyfile(apk, staged_apk)
    shutil.copyfile(module, staged_module)
    manifest = {
        "source_commit": source_commit.lower(),
        "version": version_name,
        "version_code": version_code,
        "certificate_sha256": expected_fingerprint,
        "artifacts": [
            {"name": staged_apk.name, "sha256": sha256(staged_apk), "size": staged_apk.stat().st_size},
            {"name": staged_module.name, "sha256": sha256(staged_module), "size": staged_module.stat().st_size},
        ],
    }
    manifest_path = output_dir / "release-manifest.json"
    manifest_path.write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    checksummed_files = [staged_apk, staged_module, manifest_path]
    sums = "".join(f"{sha256(path)}  {path.name}\n" for path in checksummed_files)
    (output_dir / "SHA256SUMS.txt").write_text(sums, encoding="utf-8")
    print(f"Verified APK signature, package metadata, 16 KiB alignment, embedded APK and four Magisk ABIs")
    print(f"Release artifacts and manifest staged in {output_dir}")


if __name__ == "__main__":
    main()
