#!/usr/bin/env python3
"""Run a disposable API 34 x86_64 AVD through real Magisk module boots."""

from __future__ import annotations

import argparse
import difflib
import hashlib
import json
import os
import re
import shlex
import shutil
import signal
import subprocess
import sys
import time
import zipfile
import xml.etree.ElementTree as ET
from pathlib import Path
from typing import Any


ROOTAVD_COMMIT = "613caa44371f85e1a461bc030e07ddc2d71afe32"
MAGISK_VERSION = "30.7"
MAGISK_VERSION_CODE = "30700"
MAGISK_APK_SHA256 = "e0d32d2123532860f97123d927b1bb86c4e08e6fd8a48bfc6b5bee0afae9ebd5"
MAGISK_APP_FUNCTIONS_SHA256 = "6c0acfadcfca72dcdc1a6bd37874ef0ef10ff793a19ead34a4b77e5b2ca13f0b"
SYSTEM_IMAGE = "system-images/android-34/google_apis/x86_64"
AVD_NAME = "Gsm2SipMagiskApi34"
MAGISK_ENV_APK_FILES = {
    "assets/app_functions.sh",
    "assets/util_functions.sh",
    "assets/boot_patch.sh",
    "assets/addon.d.sh",
    "assets/stub.apk",
    "assets/chromeos/futility",
    "assets/chromeos/kernel_data_key.vbprivk",
    "assets/chromeos/kernel.keyblock",
    "lib/x86_64/libbusybox.so",
    "lib/x86_64/libinit-ld.so",
    "lib/x86_64/libmagisk.so",
    "lib/x86_64/libmagiskboot.so",
    "lib/x86_64/libmagiskinit.so",
    "lib/x86_64/libmagiskpolicy.so",
}


class SmokeFailure(RuntimeError):
    pass


def safe_name(value: str) -> str:
    return re.sub(r"[^A-Za-z0-9_.-]+", "_", value).strip("_")[:72] or "command"


class MagiskAvdSmoke:
    def __init__(self, args: argparse.Namespace) -> None:
        self.args = args
        self.artifacts = args.artifact_dir.resolve()
        self.artifacts.mkdir(parents=True, exist_ok=True)
        self.rootavd = args.rootavd_dir.resolve()
        self.android_home = args.android_home.resolve()
        self.avd_home = args.avd_home.resolve()
        self.gateway_zip = args.gateway_zip.resolve()
        self.serial = "emulator-5554"
        self.adb = shutil.which("adb") or str(self.android_home / "platform-tools/adb")
        self.emulator = shutil.which("emulator") or str(self.android_home / "emulator/emulator")
        self.avdmanager = shutil.which("avdmanager") or str(self.android_home / "cmdline-tools/latest/bin/avdmanager")
        self.sequence = 0
        self.emulator_process: subprocess.Popen[bytes] | None = None
        self.emulator_log_handle: Any = None
        self.boot_ids: list[str] = []
        self.marker_boot_ids: list[str] = []
        self.official_app_functions_path: Path | None = None
        self.root_access = "unavailable"
        self.checks: list[dict[str, str]] = []
        self.summary: dict[str, Any] = {
            "status": "running",
            "rootavd_commit": ROOTAVD_COMMIT,
            "magisk_version": MAGISK_VERSION,
            "magisk_version_code": int(MAGISK_VERSION_CODE),
            "system_image": SYSTEM_IMAGE,
            "abi": "x86_64",
            "checks": self.checks,
        }

    def record(self, name: str, status: str, detail: str) -> None:
        self.checks.append({"name": name, "status": status, "detail": detail})
        print(f"[{status.upper()}] {name}: {detail}", flush=True)

    def write_summary(self) -> None:
        self.summary["checks"] = self.checks
        self.summary["boot_ids"] = self.boot_ids
        self.summary["probe_marker_boot_ids"] = self.marker_boot_ids
        self.summary["root_access"] = self.root_access
        serialized = json.dumps(self.summary, indent=2, sort_keys=True) + "\n"
        (self.artifacts / "summary.json").write_text(serialized, encoding="utf-8")
        (self.artifacts / "results.json").write_text(serialized, encoding="utf-8")

    def run(
        self,
        command: list[str],
        name: str,
        *,
        timeout: float = 60,
        check: bool = True,
        binary: bool = False,
        log: bool = True,
        input_text: str | None = None,
        cwd: Path | None = None,
    ) -> subprocess.CompletedProcess[Any]:
        self.sequence += 1
        printable = shlex.join(command)
        try:
            result = subprocess.run(
                command,
                input=input_text.encode() if input_text is not None else None,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                timeout=timeout,
                check=False,
                cwd=cwd,
            )
        except (OSError, subprocess.TimeoutExpired) as exc:
            if log:
                (self.artifacts / f"{self.sequence:03d}-{safe_name(name)}.log").write_text(
                    f"$ {printable}\nerror: {exc}\n", encoding="utf-8"
                )
            raise SmokeFailure(f"command unavailable or timed out: {name}: {exc}") from exc
        stdout = result.stdout if binary else result.stdout.decode("utf-8", errors="replace")
        stderr = result.stderr.decode("utf-8", errors="replace")
        if log:
            stdout_log = f"<binary output: {len(stdout)} bytes>\n" if binary else str(stdout)
            (self.artifacts / f"{self.sequence:03d}-{safe_name(name)}.log").write_text(
                f"$ {printable}\nexit={result.returncode}\nstdout:\n{stdout_log}\nstderr:\n{stderr}\n",
                encoding="utf-8",
            )
        result.stdout = stdout
        result.stderr = stderr.encode()
        if check and result.returncode != 0:
            raise SmokeFailure(
                f"command failed ({result.returncode}): {name}: {stderr.strip() or stdout!s}"
            )
        return result

    def adb_run(self, *args: str, name: str, timeout: float = 60, check: bool = True,
                binary: bool = False, log: bool = True) -> subprocess.CompletedProcess[Any]:
        return self.run([self.adb, "-s", self.serial, *args], name, timeout=timeout,
                        check=check, binary=binary, log=log)

    def shell(self, command: str, *, name: str, timeout: float = 60, check: bool = True,
              log: bool = True) -> str:
        result = self.adb_run("shell", command, name=name, timeout=timeout, check=check, log=log)
        return str(result.stdout).strip()

    def patch_rootavd(self) -> None:
        root = self.rootavd / "rootAVD.sh"
        archive_apk = self.rootavd / "Magisk.zip"
        git_head = self.run(["git", "-C", str(self.rootavd), "rev-parse", "HEAD"],
                            "rootavd-revision").stdout.strip()
        if git_head != ROOTAVD_COMMIT:
            raise SmokeFailure(f"unexpected rootAVD revision: {git_head}")
        if not root.is_file() or not archive_apk.is_file():
            raise SmokeFailure("pinned rootAVD checkout is missing rootAVD.sh or Magisk.zip")
        apk_hash = hashlib.sha256(archive_apk.read_bytes()).hexdigest()
        if apk_hash != MAGISK_APK_SHA256:
            raise SmokeFailure(f"Magisk APK SHA-256 mismatch: {apk_hash}")
        with zipfile.ZipFile(archive_apk) as apk:
            apk_files = set(apk.namelist())
            missing = MAGISK_ENV_APK_FILES - apk_files
            if missing:
                raise SmokeFailure(f"official Magisk APK is missing env-fix files: {sorted(missing)}")
            util_functions = apk.read("assets/util_functions.sh").decode("utf-8", errors="replace")
            app_functions_bytes = apk.read("assets/app_functions.sh")
            app_functions = app_functions_bytes.decode("utf-8", errors="replace")
            if f"MAGISK_VER='{MAGISK_VERSION}'" not in util_functions:
                raise SmokeFailure("official Magisk APK util_functions.sh version does not match the pinned release")
            if f"MAGISK_VER_CODE={MAGISK_VERSION_CODE}" not in util_functions:
                raise SmokeFailure("official Magisk APK util_functions.sh version code does not match the pinned release")
            if not re.search(r"(?m)^fix_env\(\) \{", app_functions):
                raise SmokeFailure("official Magisk APK is missing its fix_env function")
            app_functions_hash = hashlib.sha256(app_functions_bytes).hexdigest()
            if app_functions_hash != MAGISK_APP_FUNCTIONS_SHA256:
                raise SmokeFailure(f"official Magisk app_functions.sh hash mismatch: {app_functions_hash}")
            self.official_app_functions_path = self.artifacts / "official-magisk-app_functions.sh"
            self.official_app_functions_path.write_bytes(app_functions_bytes)

        original = root.read_text(encoding="utf-8")
        patched = original
        changes: list[str] = []

        def replace_once(old: str, new: str, label: str) -> None:
            nonlocal patched
            count = patched.count(old)
            if count != 1:
                raise SmokeFailure(f"rootAVD patch anchor {label!r} matched {count} times")
            patched = patched.replace(old, new, 1)
            changes.append(label)

        replace_once(
            "\tadb shell sh $ADBBASEDIR/rootAVD.sh $@",
            "\tadb shell MAGISKVERCHOOSEN=true MAGISK_VER=$MAGISK_VER sh $ADBBASEDIR/rootAVD.sh $@",
            "pass pinned Magisk version to the guest",
        )

        def replace_function(source: str, name: str, body: str) -> str:
            pattern = re.compile(rf"(?ms)^{re.escape(name)}\(\) \{{.*?^\}}")
            matches = list(pattern.finditer(source))
            if len(matches) != 1:
                raise SmokeFailure(f"rootAVD function patch {name!r} matched {len(matches)} times")
            return source[:matches[0].start()] + body + source[matches[0].end():]

        function_changes = {
            "CheckAVDIsOnline": '''CheckAVDIsOnline() {
\tAVDIsOnline=false
\texport AVDIsOnline
\techo "[!] Network checks disabled by Magisk validation smoke"
}''',
            "DownLoadFile": '''DownLoadFile() {
\techo "[!] Dynamic downloads are disabled by Magisk validation smoke" >&2
\treturn 1
}''',
            "GetUSBHPmod": '''GetUSBHPmod() {
\techo "[!] Optional module downloads are disabled by Magisk validation smoke" >&2
\treturn 1
}''',
            "DownloadUptoDateSript": '''DownloadUptoDateSript() {
\techo "[!] rootAVD self-update download is disabled by Magisk validation smoke" >&2
\treturn 1
}''',
        }
        for name, body in function_changes.items():
            before = patched
            patched = replace_function(patched, name, body)
            if patched == before:
                raise SmokeFailure(f"rootAVD patch did not change {name}")
            changes.append(f"disable dynamic network entry point {name}")

        wget_count = patched.count("wget")
        if wget_count < 1:
            raise SmokeFailure("expected rootAVD download call sites were not found")
        patched = patched.replace("wget", "false")
        changes.append(f"replace {wget_count} BusyBox wget applet calls with false")
        if "wget" in patched or re.search(r"(?m)^\s*curl\b", patched):
            raise SmokeFailure("a direct dynamic download command remains in patched rootAVD")

        diff = "".join(difflib.unified_diff(
            original.splitlines(keepends=True), patched.splitlines(keepends=True),
            fromfile=f"rootAVD.sh@{ROOTAVD_COMMIT}", tofile="rootAVD.sh (smoke-patched)",
        ))
        (self.artifacts / "rootavd-patch.diff").write_text(diff, encoding="utf-8")
        (self.artifacts / "rootavd-patch-summary.txt").write_text(
            "\n".join(changes) + "\n", encoding="utf-8"
        )
        root.write_text(patched, encoding="utf-8")
        self.record("pinned_sources", "pass",
                    f"rootAVD={git_head}; Magisk={MAGISK_VERSION}; sha256={apk_hash}")
        self.record("official_magisk_env_fix_sources", "pass",
                    f"validated {len(MAGISK_ENV_APK_FILES)} APK entries; app_functions sha256={app_functions_hash}")
        self.record("rootavd_network_guards", "pass", "; ".join(changes[1:]))

    def prepare_avd(self) -> None:
        if not Path(self.adb).is_file() or not Path(self.emulator).is_file() or not Path(self.avdmanager).is_file():
            raise SmokeFailure("Android SDK adb/emulator/avdmanager tools are unavailable")
        image_dir = self.android_home / SYSTEM_IMAGE
        ramdisk = image_dir / "ramdisk.img"
        if not ramdisk.is_file():
            raise SmokeFailure(f"API 34 Google APIs x86_64 ramdisk is missing: {ramdisk}")
        if not self.gateway_zip.is_file():
            raise SmokeFailure(f"built gateway Magisk module is missing: {self.gateway_zip}")

        self.avd_home.mkdir(parents=True, exist_ok=True)
        avd_dir = self.avd_home / f"{AVD_NAME}.avd"
        config_ini = self.avd_home / f"{AVD_NAME}.ini"
        if avd_dir.exists() or config_ini.exists():
            shutil.rmtree(avd_dir, ignore_errors=True)
            config_ini.unlink(missing_ok=True)
        self.run([
            self.avdmanager, "create", "avd", "--force", "--name", AVD_NAME,
            "--package", "system-images;android-34;google_apis;x86_64",
            "--device", "pixel_2", "--sdcard", "512M",
        ], "create-api34-avd", timeout=120, input_text="no\n")
        self.record("avd_created", "pass", "fresh API 34 google_apis x86_64 AVD")

    def start_emulator(self, *, wipe: bool = False) -> None:
        if self.emulator_process and self.emulator_process.poll() is None:
            raise SmokeFailure("refusing to start while an owned emulator is still running")
        self.run([self.adb, "start-server"], "adb-start-server", timeout=15, check=False)
        log_path = self.artifacts / f"emulator-boot-{len(self.boot_ids) + 1}.log"
        self.emulator_log_handle = log_path.open("wb")
        command = [
            self.emulator, "@" + AVD_NAME, "-port", "5554", "-accel", "on",
            "-no-window", "-gpu", "swiftshader_indirect", "-no-snapshot", "-noaudio",
            "-no-boot-anim", "-cores", "2", "-memory", "4096",
            "-prop", "persist.sys.locale=en-US",
        ]
        if wipe:
            command.append("-wipe-data")
        self.emulator_process = subprocess.Popen(
            command, stdout=self.emulator_log_handle, stderr=subprocess.STDOUT,
            start_new_session=True,
        )
        deadline = time.monotonic() + 360
        device_ready = False
        while time.monotonic() < deadline:
            if self.emulator_process.poll() is not None:
                raise SmokeFailure(f"emulator exited during boot; inspect {log_path.name}")
            state = self.adb_run("get-state", name="adb-get-state", timeout=8,
                                 check=False, log=False)
            if state.returncode == 0 and str(state.stdout).strip() == "device":
                device_ready = True
                break
            time.sleep(2)
        if not device_ready:
            raise SmokeFailure("emulator did not become reachable within 360 seconds")

        boot_deadline = time.monotonic() + 360
        while time.monotonic() < boot_deadline:
            if self.emulator_process.poll() is not None:
                raise SmokeFailure(f"emulator exited before boot completed; inspect {log_path.name}")
            boot = self.shell("getprop sys.boot_completed", name="boot-completed-poll",
                              timeout=8, check=False, log=False)
            if boot == "1":
                break
            time.sleep(3)
        else:
            raise SmokeFailure("Android sys.boot_completed did not reach 1")

        sdk = self.shell("getprop ro.build.version.sdk", name="android-api-level")
        abi = self.shell("getprop ro.product.cpu.abi", name="android-primary-abi")
        if sdk != "34" or not abi.startswith("x86_64"):
            raise SmokeFailure(f"unexpected AVD image: API={sdk}, ABI={abi}")
        boot_id = self.shell("cat /proc/sys/kernel/random/boot_id", name="kernel-boot-id")
        if not boot_id or (self.boot_ids and boot_id == self.boot_ids[-1]):
            raise SmokeFailure("cold boot did not produce a new kernel boot_id")
        self.boot_ids.append(boot_id)
        self.record(f"android_boot_{len(self.boot_ids)}", "pass", f"API={sdk}; ABI={abi}; boot_id={boot_id}")
        time.sleep(12)

    def sync_guest_filesystem(self, name: str) -> None:
        result = self.adb_run("shell", "sync", name=name, timeout=30, check=True)
        if result.returncode != 0:
            raise SmokeFailure(f"guest filesystem sync failed: {name}")
        self.record("guest_filesystem_sync", "pass", name)

    def stop_emulator(self, *, sync_guest: bool = True) -> None:
        process = self.emulator_process
        if process is None:
            return
        sync_failure: Exception | None = None
        if process.poll() is None:
            if sync_guest:
                try:
                    self.sync_guest_filesystem("before-emulator-stop-sync")
                except Exception as exc:
                    sync_failure = exc
            self.run([self.adb, "start-server"], "adb-start-server-before-stop", timeout=15, check=False)
            self.adb_run("emu", "kill", name="stop-emulator", timeout=10, check=False)
            try:
                process.wait(timeout=35)
            except subprocess.TimeoutExpired:
                os.killpg(process.pid, signal.SIGTERM)
                try:
                    process.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    os.killpg(process.pid, signal.SIGKILL)
                    process.wait(timeout=10)
        self.emulator_process = None
        if self.emulator_log_handle:
            self.emulator_log_handle.close()
            self.emulator_log_handle = None
        if sync_failure is not None:
            raise SmokeFailure(f"guest filesystem sync failed before emulator stop: {sync_failure}")

    def create_probe_zip(self) -> Path:
        module = Path(__file__).resolve().parent / "magisk-avd-smoke-module"
        for rel in ("post-fs-data.sh", "service.sh", "META-INF/com/google/android/update-binary"):
            (module / rel).chmod(0o755)
        zip_path = self.artifacts / "magisk-validation-smoke.zip"
        zip_path.unlink(missing_ok=True)
        self.run(["zip", "-qr", "-X", str(zip_path), "."], "package-lifecycle-probe",
                 timeout=60, cwd=module)
        listing = self.run(["unzip", "-Z", "-1", str(zip_path)], "inspect-probe-zip").stdout
        required = {
            "module.prop", "post-fs-data.sh", "service.sh",
            "system/etc/magisk-validation-smoke", "META-INF/com/google/android/update-binary",
        }
        if required - set(str(listing).splitlines()):
            raise SmokeFailure("probe module zip is missing required Magisk module files")
        self.record("probe_module_packaged", "pass", f"{zip_path.name}; {len(required)} required files")
        return zip_path

    def capture_ui(self, label: str) -> ET.Element | None:
        self.adb_run("shell", "uiautomator", "dump", "/sdcard/magisk-avd-smoke-window.xml",
                     name=f"uiautomator-{label}", timeout=20, check=False, log=False)
        xml_result = self.adb_run("exec-out", "cat", "/sdcard/magisk-avd-smoke-window.xml",
                                  name=f"ui-hierarchy-{label}", timeout=15, check=False, log=False)
        raw = str(xml_result.stdout)
        if raw:
            (self.artifacts / f"ui-{safe_name(label)}.xml").write_text(raw, encoding="utf-8")
            try:
                return ET.fromstring(raw)
            except ET.ParseError:
                pass
        return None

    @staticmethod
    def visible_nodes(root: ET.Element | None) -> list[ET.Element]:
        if root is None:
            return []
        return [node for node in root.iter("node") if node.attrib.get("visible-to-user", "true") == "true"]

    @staticmethod
    def node_bounds(node: ET.Element) -> tuple[int, int, int, int] | None:
        match = re.fullmatch(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]", node.attrib.get("bounds", ""))
        if not match:
            return None
        return tuple(int(part) for part in match.groups())  # type: ignore[return-value]

    def tap_node(self, node: ET.Element, label: str) -> None:
        bounds = self.node_bounds(node)
        if bounds is None:
            raise SmokeFailure(f"cannot tap Magisk UI node without bounds: {label}")
        left, top, right, bottom = bounds
        self.adb_run("shell", "input", "tap", str((left + right) // 2), str((top + bottom) // 2),
                     name=f"tap-{label}", timeout=10)

    def capture_screenshot(self, label: str) -> None:
        result = self.adb_run("exec-out", "screencap", "-p", name=f"screenshot-{label}",
                              timeout=20, binary=True)
        data = result.stdout
        if isinstance(data, bytes) and data.startswith(b"\x89PNG"):
            (self.artifacts / f"{safe_name(label)}.png").write_bytes(data)

    def request_one_time_shell_root(self, action: str, name: str, *, timeout: float = 120) -> str | None:
        shell_uid = self.shell("id -u", name="shell-uid", timeout=10)
        if shell_uid != "2000":
            raise SmokeFailure(f"unexpected adb shell uid before Magisk authorization: {shell_uid}")
        inner = f"/system/bin/sh /data/local/tmp/magisk-avd-smoke-root.sh {action}"
        remote = f"PATH=/debug_ramdisk:/sbin:$PATH su -c {shlex.quote(inner)}"
        command = [self.adb, "-s", self.serial, "shell", remote]
        process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        deadline = time.monotonic() + min(60, timeout)
        once_selected = False
        grant_clicked = False
        last_tree: ET.Element | None = None
        while time.monotonic() < deadline and process.poll() is None:
            tree = self.capture_ui("su-request")
            if tree is not None:
                last_tree = tree
                nodes = self.visible_nodes(tree)
                if not once_selected:
                    spinner = next((n for n in nodes if n.attrib.get("resource-id", "").endswith("/timeout")), None)
                    if spinner is not None:
                        self.tap_node(spinner, "timeout-spinner")
                        time.sleep(0.4)
                        popup = self.capture_ui("su-timeout-options")
                        option = next((n for n in self.visible_nodes(popup)
                                       if n.attrib.get("text", "").strip().casefold() in {"once", "one time", "only once"}), None)
                        if option is not None:
                            self.tap_node(option, "once-option")
                            once_selected = True
                            time.sleep(0.3)
                if once_selected:
                    nodes = self.visible_nodes(self.capture_ui("su-request-ready"))
                    grant = next((n for n in nodes
                                  if n.attrib.get("resource-id", "").endswith("/grant_btn")
                                  and n.attrib.get("enabled", "true") == "true"), None)
                    if grant is not None:
                        self.capture_screenshot("magisk-su-request-once")
                        self.tap_node(grant, "grant-once")
                        grant_clicked = True
                        break
            time.sleep(0.5)

        if grant_clicked:
            try:
                stdout, stderr = process.communicate(timeout=timeout)
            except subprocess.TimeoutExpired:
                process.kill()
                stdout, stderr = process.communicate()
            output = stdout.decode("utf-8", errors="replace").strip()
            if process.returncode == 0 and "root_uid=0" in output and not re.search(r"(^|\n)smoke_error=", output):
                self.root_access = "Magisk su: one-time approval for adb shell UID 2000"
                self.record("su_authorization", "pass", self.root_access)
                return output
            (self.artifacts / "su-authorize.stdout.txt").write_text(output + "\n", encoding="utf-8")
            (self.artifacts / "su-authorize.stderr.txt").write_text(stderr.decode("utf-8", errors="replace"), encoding="utf-8")
        else:
            process.terminate()
            try:
                stdout, stderr = process.communicate(timeout=5)
            except subprocess.TimeoutExpired:
                process.kill()
                stdout, stderr = process.communicate()
            (self.artifacts / "su-authorize.stdout.txt").write_text(stdout.decode("utf-8", errors="replace"), encoding="utf-8")
            (self.artifacts / "su-authorize.stderr.txt").write_text(stderr.decode("utf-8", errors="replace"), encoding="utf-8")
        if last_tree is not None:
            self.capture_screenshot("magisk-su-request-unresolved")
        return None

    def try_adbd_root_fallback(self) -> bool:
        result = self.run([self.adb, "-s", self.serial, "root"], "adbd-root-fallback",
                          timeout=30, check=False)
        deadline = time.monotonic() + 30
        while time.monotonic() < deadline:
            state = self.adb_run("get-state", name="adbd-root-state", timeout=8,
                                 check=False, log=False)
            if state.returncode == 0 and self.shell("id -u", name="adbd-root-uid",
                                                   timeout=8, check=False, log=False) == "0":
                self.root_access = "adbd root; privileged actions via Magisk su -c"
                self.record("root_access_fallback", "pass",
                            f"{str(result.stdout).strip()}; privileged actions use Magisk su namespace")
                return True
            time.sleep(1)
        return False

    def run_root_action(self, action: str, name: str, *, timeout: float = 120) -> str:
        if self.root_access.startswith("adbd root"):
            if action not in {"fix_environment", "verify_environment", "install_probe", "install_gateway", "probe_gateway"}:
                raise SmokeFailure(f"unrecognized privileged smoke action: {action}")
            inner = f"/system/bin/sh /data/local/tmp/magisk-avd-smoke-root.sh {action}"
            remote = f"PATH=/debug_ramdisk:/sbin:$PATH su -c {shlex.quote(inner)}"
        else:
            raise SmokeFailure("direct privileged action requires adbd root")
        result = self.adb_run("shell", remote, name=name, timeout=timeout, check=False)
        output = str(result.stdout).strip()
        if result.returncode != 0 or re.search(r"(^|\n)smoke_error=", output):
            stderr = result.stderr.decode("utf-8", errors="replace").strip()
            raise SmokeFailure(
                f"Magisk action failed ({result.returncode}): {name}\n"
                f"stdout:\n{output}\nstderr:\n{stderr}"
            )
        if "root_uid=0" not in output:
            raise SmokeFailure(f"Magisk su action did not confirm UID 0: {name}")
        self.record("magisk_su_action", "pass", f"{action} ran via Magisk su -c")
        return output

    @staticmethod
    def util_functions_snapshot(output: str) -> str:
        wanted = (
            "active_util_functions_metadata=",
            "active_util_functions_sha256=",
            "active_util_functions_version_lines_rc=",
            "active_util_functions_version_match_rc=",
            "active_util_functions_version_code_match_rc=",
            "magisk_diag_grep_path=",
            "magisk_diag_grep_exact_expected_line_rc=",
        )
        lines = [line for line in output.splitlines() if any(line.startswith(key) for key in wanted)]
        return "; ".join(lines) or "active util_functions diagnostics were not returned"

    def perform_root_action(self, action: str, name: str, *, timeout: float = 120) -> str:
        if self.try_adbd_root_fallback():
            result = self.run_root_action(action, name, timeout=timeout)
        else:
            result = self.request_one_time_shell_root(action, name, timeout=timeout)
            if result is None:
                raise SmokeFailure("adbd root is unavailable and a one-time Magisk su approval was not granted")
        if action in {"fix_environment", "install_probe", "install_gateway"}:
            self.sync_guest_filesystem(f"sync-after-{action}")
        return result

    def push(self, local: Path, remote: str, label: str) -> None:
        self.adb_run("push", str(local), remote, name=label, timeout=90)

    def push_root_probe(self, label: str) -> None:
        app_functions = self.official_app_functions_path
        if app_functions is None:
            raise SmokeFailure("official Magisk app_functions.sh was not extracted from the pinned APK")
        root_shell = Path(__file__).resolve().parent / "magisk-avd-smoke-root.sh"
        self.push(root_shell, "/data/local/tmp/magisk-avd-smoke-root.sh", label)
        self.push(app_functions, "/data/local/tmp/magisk-avd-app-functions.sh",
                  f"{label}-official-app-functions")

    def validate_repeat_boot_markers(self, output: str, label: str) -> str:
        values: dict[str, str] = {}
        for line in output.splitlines():
            if "=" in line:
                key, value = line.split("=", 1)
                values[key] = value
        current = values.get("current_boot_id", "")
        if not current or current != self.boot_ids[-1]:
            raise SmokeFailure(f"{label}: probe module current boot_id differs from host cold-boot observation")
        if values.get("post_fs_data_boot_id") != current or values.get("service_boot_id") != current:
            raise SmokeFailure(f"{label}: module hook markers do not match current boot_id")
        self.marker_boot_ids.append(current)
        return current

    def record_magic_version(self) -> None:
        version = self.shell("su -c 'magisk -v'", name="magisk-daemon-version", timeout=20)
        version_code = self.shell("su -c 'magisk -V'", name="magisk-daemon-version-code", timeout=20)
        if not version.startswith(MAGISK_VERSION) or version_code != MAGISK_VERSION_CODE:
            raise SmokeFailure(f"Magisk daemon mismatch: {version}/{version_code}")

    def run_lifecycle(self) -> None:
        self.patch_rootavd()
        self.prepare_avd()
        probe_zip = self.create_probe_zip()

        ramdisk = self.android_home / SYSTEM_IMAGE / "ramdisk.img"
        original_ramdisk_hash = hashlib.sha256(ramdisk.read_bytes()).hexdigest()
        (self.artifacts / "ramdisk-original.sha256").write_text(original_ramdisk_hash + "  ramdisk.img\n", encoding="utf-8")

        self.start_emulator(wipe=True)
        self.record("pre_patch_boot", "pass", "Magisk not yet installed; used only as the running rootAVD target")

        rootavd_log = self.artifacts / "rootavd-run.log"
        env = os.environ.copy()
        env.update({
            "ANDROID_HOME": str(self.android_home), "ANDROID_SDK_ROOT": str(self.android_home),
            "ANDROID_AVD_HOME": str(self.avd_home), "MAGISKVERCHOOSEN": "true",
            "MAGISK_VER": MAGISK_VERSION, "PATH": os.pathsep.join([
                str(self.android_home / "platform-tools"), str(self.android_home / "emulator"),
                str(self.android_home / "cmdline-tools/latest/bin"), env.get("PATH", ""),
            ]),
        })
        rootavd_command = ["bash", str(self.rootavd / "rootAVD.sh"), f"{SYSTEM_IMAGE}/ramdisk.img"]
        try:
            with rootavd_log.open("wb") as output:
                completed = subprocess.run(rootavd_command, cwd=self.rootavd, env=env,
                                           stdout=output, stderr=subprocess.STDOUT,
                                           timeout=1200, check=False)
        except (OSError, subprocess.TimeoutExpired) as exc:
            raise SmokeFailure(f"rootAVD patch failed or timed out: {exc}") from exc
        if completed.returncode != 0:
            raise SmokeFailure(f"pinned rootAVD exited with {completed.returncode}; inspect rootavd-run.log")
        # rootAVD shuts down this disposable guest itself while patching the ramdisk.
        self.stop_emulator(sync_guest=False)
        patched_hash = hashlib.sha256(ramdisk.read_bytes()).hexdigest()
        (self.artifacts / "ramdisk-patched.sha256").write_text(patched_hash + "  ramdisk.img\n", encoding="utf-8")
        if patched_hash == original_ramdisk_hash:
            raise SmokeFailure("rootAVD returned success but did not change ramdisk.img")
        self.record("magisk_ramdisk_patch", "pass", f"ramdisk sha256 {original_ramdisk_hash} -> {patched_hash}")

        self.start_emulator()
        self.push_root_probe("push-root-probe")
        official_apk = self.rootavd / "Magisk.zip"
        env_fix_apk_hash = hashlib.sha256(official_apk.read_bytes()).hexdigest()
        if env_fix_apk_hash != MAGISK_APK_SHA256:
            raise SmokeFailure(f"Magisk APK changed during rootAVD patching: {env_fix_apk_hash}")
        self.record("official_magisk_apk_revalidated", "pass", f"sha256={env_fix_apk_hash}")
        self.push(official_apk, "/data/local/tmp/magisk-v30.7.apk",
                  "push-official-magisk-apk-for-env-fix")
        result = self.perform_root_action("fix_environment", "official-magisk-environment-fix", timeout=180)
        if "magisk_environment=complete" not in result or "magisk_environment_fix=official_app_functions_fix_env" not in result:
            raise SmokeFailure("official Magisk environment fix did not report complete")
        self.record("official_magisk_environment_fix", "pass", result.replace("\n", "; "))
        before_hash = re.search(r"(?m)^active_util_functions_sha256=([a-f0-9]{64})$", result)
        before_size = re.search(r"(?m)^active_util_functions_metadata=[0-9]+:[0-9]+:[0-9]+:([0-9]+)$", result)
        if before_hash is None or before_size is None or int(before_size.group(1)) == 0:
            raise SmokeFailure("official util_functions snapshot is missing or empty before cold boot")
        self.record("util_functions_before_cold_boot", "pass", self.util_functions_snapshot(result))

        self.stop_emulator()
        self.start_emulator()
        self.push_root_probe("refresh-root-probe-after-env-fix")
        try:
            result = self.perform_root_action("verify_environment", "verify-magisk-environment-after-cold-boot", timeout=120)
        except SmokeFailure as exc:
            self.record("util_functions_after_cold_boot", "fail", self.util_functions_snapshot(str(exc)))
            raise
        after_hash = re.search(r"(?m)^active_util_functions_sha256=([a-f0-9]{64})$", result)
        after_size = re.search(r"(?m)^active_util_functions_metadata=[0-9]+:[0-9]+:[0-9]+:([0-9]+)$", result)
        if (after_hash is None or after_size is None or
                after_hash.group(1) != before_hash.group(1) or
                after_size.group(1) != before_size.group(1)):
            raise SmokeFailure("official util_functions content changed or disappeared across cold boot")
        self.record("util_functions_after_cold_boot", "pass", self.util_functions_snapshot(result))
        if "magisk_environment=complete" not in result:
            raise SmokeFailure("Magisk environment fix did not persist across a cold boot")
        self.record("official_magisk_environment_persisted", "pass", result.replace("\n", "; "))

        self.push(probe_zip, "/data/local/tmp/magisk-validation-smoke.zip", "push-probe-zip")
        result = self.perform_root_action("install_probe", "install-lifecycle-probe", timeout=180)
        self.record("probe_module_installed", "pass", result.replace("\n", "; "))

        self.stop_emulator()
        self.start_emulator()
        self.push_root_probe("refresh-root-probe-boot2")
        self.push(self.gateway_zip, "/data/local/tmp/gateway-magisk.zip", "push-gateway-module")
        result = self.perform_root_action("install_gateway", "install-gateway-module", timeout=240)
        first_marker_boot = self.validate_repeat_boot_markers(result, "probe boot 2")
        self.record("gateway_module_installed", "pass", result.replace("\n", "; "))

        self.shell("logcat -c", name="clear-pre-project-module-logs", timeout=15)
        self.stop_emulator()
        self.start_emulator()
        self.push_root_probe("refresh-root-probe-boot3")
        result = self.perform_root_action("probe_gateway", "probe-gateway-module", timeout=180)
        second_marker_boot = self.validate_repeat_boot_markers(result, "probe boot 3")
        if first_marker_boot == second_marker_boot:
            raise SmokeFailure("probe boot markers did not change after the second cold boot")
        (self.artifacts / "gateway-probe.txt").write_text(result + "\n", encoding="utf-8")
        self.record("gateway_control_probe", "pass", "module active; APK overlay visible; read-only control probe returned structured capabilities")
        if "gateway_post_fs_data_config_dir=0:700" not in result or "gateway_service_hook=ran" not in result:
            raise SmokeFailure("project module boot hooks were not evidenced on this cold boot")
        self.record("gateway_module_boot_hooks", "pass", "post-fs-data created root-owned 0700 config dir and service.sh logged on boot")
        if "broker.query_completed=true" not in result or "broker.status=ok" not in result:
            raise SmokeFailure("gateway broker account query did not complete successfully")
        self.record("gateway_broker_probe", "pass", "framework account query returned ok; rows may be empty without SIMs; no identifiers or voice acceptance")

        magisk_log = self.adb_run("logcat", "-d", "-s", "Magisk:D", "GatewayMagisk:I", "*:S",
                                  name="filtered-magisk-boot-logs", timeout=30, check=False)
        (self.artifacts / "filtered-magisk-boot-logs.txt").write_text(str(magisk_log.stdout), encoding="utf-8")
        self.record("repeat_boot_markers", "pass", f"post-fs-data and service markers matched two distinct boot IDs: {first_marker_boot}, {second_marker_boot}")
        self.summary["status"] = "passed"

    def execute(self) -> int:
        try:
            self.run_lifecycle()
        except Exception as exc:
            self.summary["status"] = "failed"
            self.summary["failure"] = str(exc)
            self.record("run", "fail", str(exc))
            print(f"Magisk AVD smoke failed: {exc}", file=sys.stderr, flush=True)
            return_code = 1
        else:
            return_code = 0
        finally:
            try:
                self.stop_emulator()
            except Exception as exc:
                self.summary["cleanup_failure"] = str(exc)
                if self.summary.get("status") == "passed":
                    self.summary["status"] = "failed"
                    self.summary["failure"] = f"emulator cleanup failed: {exc}"
                self.record("emulator_cleanup", "fail", str(exc))
                return_code = 1
            try:
                self.write_summary()
            except Exception as exc:
                print(f"Unable to write smoke summary: {exc}", file=sys.stderr, flush=True)
                return_code = 1
        return return_code


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--artifact-dir", type=Path, required=True)
    parser.add_argument("--rootavd-dir", type=Path, required=True)
    parser.add_argument("--android-home", type=Path, required=True)
    parser.add_argument("--avd-home", type=Path, required=True)
    parser.add_argument("--gateway-zip", type=Path, required=True)
    return parser.parse_args()


if __name__ == "__main__":
    raise SystemExit(MagiskAvdSmoke(parse_args()).execute())
