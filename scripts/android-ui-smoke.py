#!/usr/bin/env python3
"""Portable ADB-only UI smoke for the GSM2SIP Android apps.

Uses Python's standard library and adb. It deliberately never accepts runtime
permissions, chooses a default phone app, pairs an account, or starts SIP/SMS/calls.
"""

from __future__ import annotations

import argparse
import datetime as dt
import json
import os
import re
import shlex
import shutil
import subprocess
import sys
import time
import zipfile
from pathlib import Path
from typing import Any
import xml.etree.ElementTree as ET


ADB_TIMEOUT_SECONDS = 30
UI_WAIT_SECONDS = 45
SYNTHETIC_SERVER = "https://ui-smoke.invalid"
SYNTHETIC_DEVICE = "UI_Smoke_Fold_35"
EXPECTED_CHECKS = (
    "emulator_boot", "emulator_api_level", "apk_install", "apk_native_abi",
    "system_permission_prompts", "app_visible", "settings_navigation", "unpaired_scope",
    "pairing_code_untouched", "synthetic_input", "ime_visible_before_rotation",
    "ime_restored_after_rotation", "rotation_display_change", "rotation_input_restore",
    "fold_device_state", "unfold_device_state", "punch_hole_cutout",
)


def safe_name(value: str) -> str:
    return re.sub(r"[^a-zA-Z0-9_.-]+", "_", value).strip("_")[:80] or "command"


class Smoke:
    def __init__(self, args: argparse.Namespace) -> None:
        self.args = args
        self.artifacts = Path(args.artifact_dir).resolve()
        self.artifacts.mkdir(parents=True, exist_ok=True)
        self.adb = shutil.which("adb") or "adb"
        self.serial = args.serial or os.environ.get("ANDROID_SERIAL") or "emulator-5554"
        self.package = args.package
        self.sequence = 0
        self.results: list[dict[str, str]] = []
        self.last_tree: ET.Element | None = None
        self.last_image_size: tuple[int, int] | None = None
        self.apk_abis: list[str] = []
        self.apk_has_arm64 = False

    def record(self, name: str, status: str, detail: str) -> None:
        item = {"name": name, "status": status, "detail": detail}
        self.results.append(item)
        print(f"[{status.upper()}] {name}: {detail}", flush=True)

    def command(self, args: list[str], *, binary: bool = False, timeout: int = ADB_TIMEOUT_SECONDS,
                check: bool = False, label: str | None = None) -> subprocess.CompletedProcess[Any]:
        self.sequence += 1
        command = [self.adb, "-s", self.serial, *args]
        name = safe_name(label or "_".join(args[:4]))
        try:
            result = subprocess.run(command, capture_output=True, timeout=timeout, check=False)
            out = result.stdout if binary else result.stdout.decode("utf-8", errors="replace")
            err = result.stderr.decode("utf-8", errors="replace")
            log = f"$ {shlex.join(command)}\nexit={result.returncode}\nstdout:\n"
            if binary:
                log += f"<binary output: {len(out)} bytes>\n"
            else:
                log += str(out)
            log += f"\nstderr:\n{err}\n"
            (self.artifacts / f"{self.sequence:03d}-{name}.txt").write_text(log, encoding="utf-8")
            result.stdout = out
            if check and result.returncode != 0:
                raise RuntimeError(f"ADB command failed ({result.returncode}): {shlex.join(args)}: {err.strip()}")
            return result
        except (OSError, subprocess.TimeoutExpired) as exc:
            (self.artifacts / f"{self.sequence:03d}-{name}.txt").write_text(
                f"$ {shlex.join(command)}\nerror: {exc}\n", encoding="utf-8"
            )
            raise RuntimeError(f"ADB command unavailable or timed out: {shlex.join(args)}: {exc}") from exc

    def shell(self, *args: str, check: bool = False, label: str | None = None) -> str:
        result = self.command(["shell", *args], check=check, label=label)
        return str(result.stdout).strip()

    def wait(self, seconds: float = 2.0) -> None:
        time.sleep(seconds)

    def capture(self, name: str) -> ET.Element:
        dump = self.command(["shell", "uiautomator", "dump", "/sdcard/ui-smoke-window.xml"],
                            timeout=ADB_TIMEOUT_SECONDS, label=f"uiautomator_{name}")
        if dump.returncode != 0:
            raise RuntimeError(f"uiautomator dump failed: {dump.stderr.decode(errors='replace')}")
        xml = self.command(["exec-out", "cat", "/sdcard/ui-smoke-window.xml"],
                           timeout=ADB_TIMEOUT_SECONDS, label=f"hierarchy_{name}")
        raw = str(xml.stdout)
        (self.artifacts / f"{safe_name(name)}.xml").write_text(raw, encoding="utf-8")
        try:
            root = ET.fromstring(raw)
        except ET.ParseError as exc:
            raise RuntimeError(f"uiautomator returned invalid XML for {name}: {exc}") from exc
        image = self.command(["exec-out", "screencap", "-p"], binary=True,
                             label=f"screencap_{name}")
        if image.returncode != 0 or not isinstance(image.stdout, bytes) or not image.stdout.startswith(b"\x89PNG"):
            raise RuntimeError(f"screencap failed for {name}")
        (self.artifacts / f"{safe_name(name)}.png").write_bytes(image.stdout)
        if len(image.stdout) >= 24:
            self.last_image_size = (int.from_bytes(image.stdout[16:20], "big"),
                                    int.from_bytes(image.stdout[20:24], "big"))
        self.last_tree = root
        return root

    @staticmethod
    def nodes(root: ET.Element) -> list[ET.Element]:
        return list(root.iter("node"))

    @staticmethod
    def node_value(node: ET.Element) -> str:
        return node.attrib.get("text", "") or node.attrib.get("content-desc", "")

    def find_node(self, root: ET.Element, resource_id: str) -> ET.Element | None:
        for node in self.nodes(root):
            actual = node.attrib.get("resource-id", "")
            if actual == resource_id or actual.endswith("/" + resource_id):
                return node
        return None

    def tap_node(self, node: ET.Element, label: str) -> None:
        bounds = node.attrib.get("bounds", "")
        match = re.fullmatch(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]", bounds)
        if not match:
            raise RuntimeError(f"No usable screen bounds for {label}: {bounds!r}")
        left, top, right, bottom = (int(part) for part in match.groups())
        if right <= left or bottom <= top:
            raise RuntimeError(f"Empty screen bounds for {label}: {bounds!r}")
        self.shell("input", "tap", str((left + right) // 2), str((top + bottom) // 2),
                   check=True, label=f"tap_{label}")

    def tap_id(self, root: ET.Element, resource_id: str) -> None:
        node = self.find_node(root, resource_id)
        if node is None:
            raise RuntimeError(f"UI element is not present: {resource_id}")
        self.tap_node(node, resource_id)

    def node_is_on_screen(self, node: ET.Element) -> bool:
        if node.attrib.get("visible-to-user", "true") != "true":
            return False
        bounds = self.parse_bounds(node.attrib.get("bounds", ""))
        if bounds is None:
            return False
        if self.last_image_size is None:
            return True
        width, height = self.last_image_size
        left, top, right, bottom = bounds
        return left < width and right > 0 and top < height and bottom > 0

    def ensure_node_visible(self, root: ET.Element, resource_id: str) -> ET.Element:
        for attempt in range(5):
            node = self.find_node(root, resource_id)
            if node is not None and self.node_is_on_screen(node):
                return root
            if attempt == 0:
                visible, _ = self._ime_visible()
                if visible:
                    self.shell("input", "keyevent", "KEYCODE_BACK", label="hide_ime_for_scroll")
                    self.wait(1)
                    root = self.capture(f"{resource_id}_ime_hidden")
                    node = self.find_node(root, resource_id)
                    if node is not None and self.node_is_on_screen(node):
                        return root
            width, height = self.last_image_size or (900, 1800)
            later_field = {"pairing_server": "pairing_device_name",
                           "etControlUrl": "etControlDeviceName"}.get(resource_id)
            later_node = self.find_node(root, later_field) if later_field else None
            if later_node is not None and self.node_is_on_screen(later_node):
                # The URL is above the focused device-name field. A downward
                # finger gesture reveals earlier content in a ScrollView.
                start_y, end_y = height // 3, (height * 2) // 3
            else:
                start_y, end_y = max(100, height - 260), max(100, height // 3)
            self.shell("input", "swipe", str(width // 2), str(start_y),
                       str(width // 2), str(end_y), "350",
                       check=True, label=f"scroll_to_{resource_id}_{attempt}")
            self.wait(1)
            root = self.capture(f"scroll_{resource_id}_{attempt}")
        node = self.find_node(root, resource_id)
        if node is None:
            raise RuntimeError(f"UI element is not present after scrolling: {resource_id}")
        if not self.node_is_on_screen(node):
            raise RuntimeError(f"UI element is outside the visible screen after bounded scrolling: {resource_id}")
        return root

    def input_text(self, root: ET.Element, resource_id: str, value: str) -> None:
        root = self.ensure_node_visible(root, resource_id)
        node = self.find_node(root, resource_id)
        if node is None:
            raise RuntimeError(f"Input field is not present: {resource_id}")
        self.tap_node(node, resource_id)
        # Move to the end and erase existing visible text without relying on
        # locale-specific select-all shortcuts or modifying any pairing code.
        self.shell("input", "keyevent", "KEYCODE_MOVE_END", check=True, label=f"end_{resource_id}")
        existing = node.attrib.get("text", "")
        if existing:
            self.shell("input", "keyevent", *(["KEYCODE_DEL"] * min(len(existing), 120)),
                       check=True, label=f"clear_{resource_id}")
        self.shell("input", "text", value, check=True, label=f"type_{resource_id}")
        self.wait(1)

    def field_text(self, root: ET.Element, resource_id: str) -> str | None:
        node = self.find_node(root, resource_id)
        return node.attrib.get("text", "") if node is not None else None

    def wait_for_app_tree(self, predicate: Any, name: str) -> ET.Element:
        deadline = time.monotonic() + UI_WAIT_SECONDS
        last_error = "UI hierarchy did not match"
        while time.monotonic() < deadline:
            root = self.capture(f"{name}_{int(time.time())}")
            if predicate(root):
                return root
            last_error = "expected app elements are missing; a system dialog may still be open"
            self.wait(2)
        raise RuntimeError(f"Timed out waiting for {name}: {last_error}")

    def refuse_system_prompts(self) -> None:
        denied: list[str] = []
        deny_labels = {"don't allow", "dont allow", "deny", "cancel", "not now", "no thanks"}
        for attempt in range(8):
            root = self.capture(f"permission_prompt_{attempt}")
            nodes = self.nodes(root)
            foreign_packages = {n.attrib.get("package", "") for n in nodes
                                if n.attrib.get("package", "") and n.attrib.get("package", "") != self.package}
            promptish = any(
                "permissioncontroller" in p.lower() or "rolecontroller" in p.lower()
                for p in foreign_packages
            )
            if not promptish:
                break
            denial = next((n for n in nodes
                           if self.node_value(n).strip().lower().replace("’", "'") in deny_labels), None)
            if denial is None:
                # A system prompt without an explicit refusal button cannot be
                # safely handled automatically; leave it untouched and report it.
                self.record("system_permission_prompts", "blocked",
                            "system prompt was visible but no explicit deny/cancel control was exposed")
                return
            text = self.node_value(denial).strip()
            self.tap_node(denial, "refuse_system_prompt")
            denied.append(text)
            self.wait(2)
        final = self.capture("after_permission_refusal")
        if any(self.node_value(n).strip().lower() in {"allow", "while using the app", "only this time"}
               for n in self.nodes(final)) and any(
                   "permissioncontroller" in n.attrib.get("package", "").lower() for n in self.nodes(final)
               ):
            self.record("system_permission_prompts", "blocked", "permission controller remains visible")
            return
        self.record("system_permission_prompts", "pass",
                    "explicitly refused: " + ", ".join(denied) if denied
                    else "no permission or default-role prompt was visible; none was accepted")

    def verify_app_foreground(self) -> None:
        resumed = self.shell("dumpsys", "activity", "activities", label="activity_state")
        windows = self.shell("dumpsys", "window", "windows", label="window_state")
        (self.artifacts / "foreground_state.txt").write_text(
            "=== activity ===\n" + resumed + "\n=== windows ===\n" + windows + "\n", encoding="utf-8"
        )
        foreground_lines = [line for line in (resumed + "\n" + windows).splitlines()
                            if re.search(r"topResumedActivity|mResumedActivity|mCurrentFocus", line, re.IGNORECASE)]
        foreground = any(self.package in line for line in foreground_lines)
        root = self.capture("app_foreground")
        tree_has_package = any(n.attrib.get("package", "") == self.package for n in self.nodes(root))
        if foreground and tree_has_package:
            self.record("app_visible", "pass", "target app owns the resumed window and appears in UIAutomator hierarchy")
        else:
            self.record("app_visible", "fail",
                        f"foreground={foreground}, hierarchy_has_package={tree_has_package}; see foreground_state.txt")

    def check_apk_install(self) -> None:
        apk = Path(self.args.apk)
        if not apk.is_file():
            self.record("apk_install", "fail", f"debug APK does not exist: {apk}")
            raise RuntimeError("debug APK is missing")
        with zipfile.ZipFile(apk) as archive:
            self.apk_abis = sorted({
                part.split("/")[1] for part in archive.namelist()
                if part.startswith("lib/") and len(part.split("/")) > 2
            })
        self.apk_has_arm64 = "arm64-v8a" in self.apk_abis
        device_abis = self.shell("getprop", "ro.product.cpu.abilist", label="device_abi_list")
        bridge = self.shell("getprop", "ro.dalvik.vm.native.bridge", label="native_bridge")
        install = self.command(["install", "-r", str(apk)], timeout=ADB_TIMEOUT_SECONDS, label="install_debug_apk")
        install_text = str(install.stdout) + install.stderr.decode("utf-8", errors="replace")
        (self.artifacts / "apk_install.txt").write_text(
            f"APK={apk}\nSHA256 recorded by workflow when available\nAPK native ABIs={self.apk_abis}\n"
            f"device ABIs={device_abis}\nnative bridge={bridge}\n{install_text}\n",
            encoding="utf-8"
        )
        if install.returncode != 0 or "Success" not in install_text:
            self.record("apk_install", "fail", install_text.strip() or f"adb install exited {install.returncode}")
            raise RuntimeError("debug APK could not be installed")
        package_info = self.shell("dumpsys", "package", self.package, label="installed_package_abi")
        match = re.search(r"primaryCpuAbi=([^\s]+)", package_info)
        primary = match.group(1) if match else "not reported"
        supported = {abi.strip() for abi in device_abis.split(",") if abi.strip()}
        translated = "translation" in bridge.lower() or "ndk" in bridge.lower()
        native_ok = not self.apk_abis or bool(supported.intersection(self.apk_abis)) or translated
        (self.artifacts / "apk_install.txt").write_text(
            f"APK={apk}\nAPK native ABIs={self.apk_abis}\nAPK contains arm64-v8a={self.apk_has_arm64}\n"
            f"device ABIs={device_abis}\nnative bridge={bridge}\ninstalled primaryCpuAbi={primary}\n"
            f"install output={install_text.strip()}\n", encoding="utf-8"
        )
        self.record("apk_install", "pass", f"adb installed package; APK ABIs={self.apk_abis or ['none']}; primaryCpuAbi={primary}")
        if native_ok:
            why = "no native libraries" if not self.apk_abis else (
                "native ABI intersects emulator ABIs" if supported.intersection(self.apk_abis)
                else f"native bridge reported {bridge!r}"
            )
            self.record("apk_native_abi", "pass", why)
        else:
            self.record("apk_native_abi", "fail",
                        f"APK ABIs {self.apk_abis} match neither emulator ABIs {sorted(supported)} nor native bridge {bridge!r}")

    def launch(self) -> None:
        self.shell("am", "force-stop", self.package, check=True, label="force_stop")
        result = self.command(["shell", "monkey", "-p", self.package, "1"], timeout=ADB_TIMEOUT_SECONDS,
                              label="launch_launcher_activity")
        if result.returncode != 0:
            raise RuntimeError(f"monkey launch failed: {result.stderr.decode(errors='replace')}")
        self.wait(3)

    def app_specific_fields(self) -> tuple[str, str, str | None]:
        if self.args.scenario == "host":
            return "pairing_server", "pairing_device_name", None
        return "etControlUrl", "etControlDeviceName", "btnHomeMenu"

    def open_settings_if_needed(self, root: ET.Element) -> ET.Element:
        server_id, _, trigger = self.app_specific_fields()
        server_node = self.find_node(root, server_id)
        if self.args.scenario == "gateway":
            if server_node is None or not self.node_is_on_screen(server_node):
                menu = next((node for node in self.nodes(root)
                             if node.attrib.get("resource-id", "").endswith("/btnHomeMenu")), None)
                if menu is None and trigger:
                    menu = next((n for n in self.nodes(root) if n.attrib.get("content-desc", "") == "Menu"), None)
                if menu is None:
                    self.record("settings_navigation", "fail", "gateway home screen Menu control is missing")
                    raise RuntimeError("Gateway home screen Menu control is missing")
                self.tap_node(menu, "gateway_settings_menu")
                root = self.wait_for_app_tree(lambda tree: self.find_node(tree, server_id) is not None,
                                              "gateway_control_pairing_form")
                self.record("settings_navigation", "pass", "opened Control server pairing from the visible gateway UI")
            else:
                self.record("settings_navigation", "pass", "Control server pairing settings are visible")
        else:
            if server_node is None:
                self.record("settings_navigation", "fail", "host pairing form is not visible; expected a fresh unpaired app state")
                raise RuntimeError("Host pairing form is not visible")
            self.record("settings_navigation", "pass", "host pairing form is visible")
        return root

    def verify_and_enter_synthetic_data(self) -> None:
        root = self.capture("before_form_interaction")
        self.verify_app_foreground()
        root = self.open_settings_if_needed(root)
        server_id, name_id, _ = self.app_specific_fields()
        if self.args.scenario == "host":
            if self.find_node(root, server_id) is None:
                self.record("unpaired_scope", "fail", "host pairing form is not present; this smoke requires a fresh unpaired app state")
                raise RuntimeError("host is not on its unpaired pairing form")
        else:
            status_node = self.find_node(root, "tvControlStatus")
            status_text = status_node.attrib.get("text", "").strip() if status_node is not None else ""
            if status_text != "Not paired":
                self.record("unpaired_scope", "fail",
                            "gateway is not in the unpaired state; current control-pairing status was not 'Not paired'")
                raise RuntimeError("gateway is not on its unpaired control-pairing form")
        self.record("unpaired_scope", "pass",
                    "host HTTPS pairing form" if self.args.scenario == "host"
                    else "gateway Control server pairing form reports Not paired")
        code_id = "pairing_code" if self.args.scenario == "host" else "etControlPairingCode"
        code_node = self.find_node(root, code_id)
        code_hint = "一次性配对码" if self.args.scenario == "host" else "Paste code from server CLI"
        if code_node is None:
            self.record("pairing_code_untouched", "blocked", "pairing code field is not exposed in the current UI hierarchy")
        elif (code_node.attrib.get("password", "false") != "true" or
              code_node.attrib.get("text", "").strip() not in ("", code_hint)):
            self.record("pairing_code_untouched", "fail", "pairing code field was unexpectedly non-empty; it was left untouched")
        else:
            self.record("pairing_code_untouched", "pass", "pairing code is empty and was never focused or changed")
        self.input_text(root, server_id, SYNTHETIC_SERVER)
        root = self.capture("server_entered")
        server_at_entry = self.field_text(root, server_id)
        visible, _ = self._ime_visible()
        if visible:
            self.shell("input", "keyevent", "KEYCODE_BACK", label="hide_ime_before_name_field")
            self.wait(1)
            root = self.capture("server_entered_ime_hidden")
        self.input_text(root, name_id, SYNTHETIC_DEVICE)
        root = self.capture("synthetic_fields_and_ime")
        name_at_entry = self.field_text(root, name_id)
        if server_at_entry == SYNTHETIC_SERVER and name_at_entry == SYNTHETIC_DEVICE:
            self.record("synthetic_input", "pass", "synthetic URL was read back after entry and device name after entry")
        else:
            self.record("synthetic_input", "fail",
                        f"server_after_entry={server_at_entry!r}, name_after_entry={name_at_entry!r}")
        self._assert_keyboard("ime_visible_before_rotation")
        self._assert_fields("input_before_rotation")

    def _assert_fields(self, name: str, status_override: str | None = None) -> bool:
        root = self.capture(name)
        server_id, name_id, _ = self.app_specific_fields()
        if self.find_node(root, server_id) is None or self.find_node(root, name_id) is None:
            visible, _ = self._ime_visible()
            if visible:
                self.shell("input", "keyevent", "KEYCODE_BACK", label=f"hide_ime_for_{name}")
                self.wait(1)
                root = self.capture(f"{name}_ime_hidden")
            for field_id in (server_id, name_id):
                if self.find_node(root, field_id) is None:
                    root = self.ensure_node_visible(root, field_id)
                    root = self.capture(f"{name}_{field_id}_visible")
        server = self.field_text(root, server_id)
        device = self.field_text(root, name_id)
        okay = server == SYNTHETIC_SERVER and device == SYNTHETIC_DEVICE
        status = status_override or ("pass" if okay else "fail")
        detail = f"server={server!r}, device={device!r}"
        self.record(name, status, detail)
        return okay

    def _ime_visible(self) -> tuple[bool, str]:
        dump = self.shell("dumpsys", "input_method", label="ime_state")
        (self.artifacts / "ime_state.txt").write_text(dump, encoding="utf-8")
        shown = re.search(r"mInputShown\s*=\s*true", dump, re.IGNORECASE)
        if shown:
            return True, shown.group(0)
        vis = re.search(r"mImeWindowVis\s*=\s*(0x[0-9a-f]+|\d+)", dump, re.IGNORECASE)
        if vis:
            value = int(vis.group(1), 16 if vis.group(1).lower().startswith("0x") else 10)
            if value & 0x2:
                return True, f"mImeWindowVis={vis.group(1)} (IME_VISIBLE bit set)"
        root = self.last_tree
        keyboard_node = next((n for n in self.nodes(root) if root is not None and
                              ("inputmethod" in n.attrib.get("package", "").lower() or
                               "keyboard" in n.attrib.get("package", "").lower())), None) if root is not None else None
        return (keyboard_node is not None, keyboard_node.attrib.get("package", "keyboard node") if keyboard_node is not None else "IME not visible")

    def _assert_keyboard(self, name: str) -> bool:
        deadline = time.monotonic() + 12
        detail = "IME not visible"
        while time.monotonic() < deadline:
            visible, detail = self._ime_visible()
            if visible:
                break
            self.wait(1)
        self.record(name, "pass" if visible else "fail", detail)
        return visible

    def test_rotation_and_ime_restore(self) -> None:
        # Keep the name field focused and the soft keyboard open while forcing
        # a configuration change. Settings are restored even when ADB fails.
        self.shell("settings", "put", "system", "accelerometer_rotation", "0", check=True,
                   label="disable_auto_rotation")
        try:
            portrait_before = self.last_image_size
            self.shell("settings", "put", "system", "user_rotation", "1", check=True,
                       label="rotate_landscape")
            self.wait(5)
            self.capture("landscape_after_rotation")
            ime_ok = self._assert_keyboard("ime_restored_after_rotation")
            rotation_ok = self._assert_fields("after_landscape_rotation")
            landscape_size = self.last_image_size
            self.shell("settings", "put", "system", "user_rotation", "0", check=True,
                       label="rotate_portrait")
            self.wait(5)
            portrait_ok = self._assert_fields("after_portrait_rotation")
            portrait_size = self.last_image_size
            actual_rotation = bool(portrait_before and landscape_size and portrait_size and
                                   portrait_before[0] < portrait_before[1] and
                                   landscape_size[0] > landscape_size[1] and
                                   portrait_size[0] < portrait_size[1])
            self.record("rotation_display_change", "pass" if actual_rotation else "fail",
                        f"actual screenshot dimensions: before={portrait_before}, landscape={landscape_size}, portrait={portrait_size}")
            if rotation_ok and portrait_ok and ime_ok:
                self.record("rotation_input_restore", "pass", "both synthetic fields survived portrait/landscape; IME returned")
            elif rotation_ok and portrait_ok:
                self.record("rotation_input_restore", "fail", "field values survived rotation, but IME did not return")
            else:
                self.record("rotation_input_restore", "fail", "synthetic field values changed or disappeared during rotation")
        finally:
            self.shell("settings", "put", "system", "user_rotation", "0", label="restore_portrait")
            self.shell("settings", "put", "system", "accelerometer_rotation", "1", label="restore_auto_rotation")
        self.shell("input", "keyevent", "KEYCODE_BACK", label="hide_ime")
        self.wait(2)

    @staticmethod
    def extract_current_state(state_dump: str) -> str:
        lines = [line.strip() for line in state_dump.splitlines()
                 if re.search(r"\bm(?:Committed|Base|Override)State\s*=",
                              line, re.IGNORECASE)]
        return " | ".join(lines)

    def device_snapshot(self, name: str) -> dict[str, str]:
        size = self.shell("wm", "size", label=f"{name}_display_size")
        state = self.shell("dumpsys", "device_state", label=f"{name}_device_state")
        cmd_state = self.shell("cmd", "device_state", "print-states", label=f"{name}_device_state_cmd")
        (self.artifacts / f"{name}_device_snapshot.txt").write_text(
            f"=== wm size ===\n{size}\n=== dumpsys device_state ===\n{state}\n"
            f"=== cmd device_state print-states ===\n{cmd_state}\n", encoding="utf-8"
        )
        match = re.search(r"(?:Physical|Override) size:\s*(\d+x\d+)", size, re.IGNORECASE)
        selected_state = self.extract_current_state(state)
        if not selected_state:
            selected_state = self.extract_current_state(cmd_state)
        return {"size": match.group(1) if match else "", "state": selected_state}

    def test_fold_unfold(self) -> None:
        before = self.device_snapshot("fold_before")
        fold = self.command(["emu", "fold"], timeout=ADB_TIMEOUT_SECONDS, label="emulator_fold")
        fold_text = str(fold.stdout) + fold.stderr.decode("utf-8", errors="replace")
        self.wait(6)
        folded = self.device_snapshot("fold_folded")
        self.capture("folded_ui")
        (self.artifacts / "fold_commands.txt").write_text(
            f"fold exit={fold.returncode}\n{fold_text}\nbefore={before}\nfolded={folded}\n", encoding="utf-8"
        )
        fold_changed = bool((before["size"] and folded["size"] and before["size"] != folded["size"]) or
                            (before["state"] and folded["state"] and before["state"] != folded["state"]))
        if fold.returncode != 0:
            self.record("fold_device_state", "blocked", f"emulator fold command unavailable: {fold_text.strip()}")
        elif fold_changed:
            self.record("fold_device_state", "pass",
                        f"observed display/device-state change: {before} -> {folded}")
            self._assert_fields("input_after_fold")
        else:
            self.record("fold_device_state", "blocked",
                        f"fold command returned {fold_text.strip()!r} but no physical display/device-state change was observable")
            self._assert_fields("input_after_fold_unverified", status_override="blocked")

        unfold = self.command(["emu", "unfold"], timeout=ADB_TIMEOUT_SECONDS, label="emulator_unfold")
        unfold_text = str(unfold.stdout) + unfold.stderr.decode("utf-8", errors="replace")
        self.wait(6)
        unfolded = self.device_snapshot("fold_unfolded")
        self.capture("unfolded_ui")
        (self.artifacts / "fold_commands.txt").write_text(
            f"fold exit={fold.returncode}\n{fold_text}\nbefore={before}\nfolded={folded}\n"
            f"unfold exit={unfold.returncode}\n{unfold_text}\nunfolded={unfolded}\n", encoding="utf-8"
        )
        restored = ((before["size"] and unfolded["size"] == before["size"]) or
                    (before["state"] and unfolded["state"] == before["state"]))
        if unfold.returncode != 0:
            self.record("unfold_device_state", "blocked", f"emulator unfold command unavailable: {unfold_text.strip()}")
        elif fold_changed and restored:
            self.record("unfold_device_state", "pass", f"unfold restored observable display/device state: {unfolded}")
            self._assert_fields("input_after_unfold")
        elif not fold_changed:
            self.record("unfold_device_state", "blocked", "fold state was never observably changed")
            self._assert_fields("input_after_unfold_unverified", status_override="blocked")
        else:
            self.record("unfold_device_state", "blocked",
                        f"unfold command completed but original display/device state was not restored: {unfolded}")

    @staticmethod
    def parse_cutout_rects(text: str) -> list[tuple[int, int, int, int]]:
        rectangles: set[tuple[int, int, int, int]] = set()
        for match in re.finditer(
                r"(?i)\b(?:m?boundingRect(?:angles)?s?)\s*[=:]\s*(\{[^}]*\}|\[[^\]]*\]|Rect\([^)]*\)|[^\n,}]+)", text):
            geometry = match.group(1)
            for m in re.finditer(r"Rect\(\s*(-?\d+)\s*,\s*(-?\d+)\s*-\s*(-?\d+)\s*,\s*(-?\d+)\s*\)", geometry):
                rect = tuple(int(part) for part in m.groups())
                if rect[2] > rect[0] and rect[3] > rect[1] and all(v >= 0 for v in rect):
                    rectangles.add(rect)  # type: ignore[arg-type]
            for m in re.finditer(r"\[\s*(\d+)\s*,\s*(\d+)\s*\]\s*\[\s*(\d+)\s*,\s*(\d+)\s*\]", geometry):
                rect = tuple(int(part) for part in m.groups())
                if rect[2] > rect[0] and rect[3] > rect[1]:
                    rectangles.add(rect)  # type: ignore[arg-type]
        return sorted(rectangles)

    @staticmethod
    def parse_bounds(value: str) -> tuple[int, int, int, int] | None:
        match = re.fullmatch(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]", value)
        return tuple(int(part) for part in match.groups()) if match else None  # type: ignore[return-value]

    def test_cutout(self) -> None:
        overlays = self.shell("cmd", "overlay", "list", "--user", "0", label="cutout_overlay_list")
        (self.artifacts / "cutout_overlay_list.txt").write_text(overlays, encoding="utf-8")
        package = next((token for token in re.findall(r"\bcom\.android\.[\w.]+", overlays)
                        if "cutout" in token.lower() and "hole" in token.lower()), None)
        if package is None:
            self.record("punch_hole_cutout", "blocked",
                        "Android image does not expose a hole-style display-cutout overlay")
            return
        enabled = self.command(["shell", "cmd", "overlay", "enable", "--user", "0", package],
                               label="enable_hole_cutout")
        enabled_text = str(enabled.stdout) + enabled.stderr.decode("utf-8", errors="replace")
        self.wait(4)
        root = self.capture("hole_cutout_enabled")
        display = self.shell("dumpsys", "display", label="cutout_display_dump")
        window = self.shell("dumpsys", "window", "windows", label="cutout_window_dump")
        (self.artifacts / "cutout_geometry.txt").write_text(
            f"overlay={package}\nenable_exit={enabled.returncode}\n{enabled_text}\n"
            f"=== display ===\n{display}\n=== windows ===\n{window}\n", encoding="utf-8"
        )
        rects = self.parse_cutout_rects(display + "\n" + window)
        if enabled.returncode != 0 or not rects:
            self.record("punch_hole_cutout", "blocked",
                        f"hole overlay could not be confirmed by live cutout geometry (exit={enabled.returncode}, rects={rects})")
            return
        clickable: list[tuple[str, tuple[int, int, int, int]]] = []
        for node in self.nodes(root):
            if node.attrib.get("package", "") != self.package:
                continue
            if node.attrib.get("visible-to-user", "true") != "true":
                continue
            cls = node.attrib.get("class", "")
            rid = node.attrib.get("resource-id", "")
            if node.attrib.get("clickable") != "true" and "Button" not in cls and "/btn" not in rid:
                continue
            bounds = self.parse_bounds(node.attrib.get("bounds", ""))
            if bounds:
                clickable.append((rid or self.node_value(node) or cls, bounds))
        if not clickable:
            self.record("punch_hole_cutout", "blocked", f"cutout geometry {rects} was visible, but no visible button bounds were exposed")
            return
        collisions = []
        for button, (bl, bt, br, bb) in clickable:
            for rect in rects:
                rl, rt, rr, rb = rect
                if bl < rr and br > rl and bt < rb and bb > rt:
                    collisions.append({"button": button, "button_bounds": [bl, bt, br, bb], "cutout_bounds": list(rect)})
        self.record("punch_hole_cutout", "fail" if collisions else "pass",
                    f"live hole cutout rectangles={rects}; visible button rectangles checked={len(clickable)}; intersections={collisions}")

    def run(self) -> None:
        self.command(["wait-for-device"], timeout=ADB_TIMEOUT_SECONDS, check=True, label="wait_for_device")
        boot = self.shell("getprop", "sys.boot_completed", label="boot_completed")
        if boot != "1":
            deadline = time.monotonic() + 180
            while boot != "1" and time.monotonic() < deadline:
                self.wait(2)
                boot = self.shell("getprop", "sys.boot_completed", label="boot_completed_poll")
        if boot != "1":
            self.record("emulator_boot", "fail", f"sys.boot_completed={boot!r}")
            raise RuntimeError("emulator did not complete boot")
        self.record("emulator_boot", "pass", f"device {self.serial} boot completed")
        device_info = {
            "requested_avd_profile": self.args.device_profile,
            "sdk": self.shell("getprop", "ro.build.version.sdk", label="device_api_level"),
            "release": self.shell("getprop", "ro.build.version.release", label="device_android_release"),
            "model": self.shell("getprop", "ro.product.model", label="device_model"),
            "fingerprint": self.shell("getprop", "ro.build.fingerprint", label="device_fingerprint"),
        }
        (self.artifacts / "device_metadata.json").write_text(json.dumps(device_info, indent=2) + "\n", encoding="utf-8")
        if device_info["sdk"] == "35":
            self.record("emulator_api_level", "pass", f"API {device_info['sdk']} / {device_info['release']}; profile={self.args.device_profile}")
        else:
            self.record("emulator_api_level", "fail", f"expected API 35, observed {device_info['sdk']!r}")
        self.check_apk_install()
        self.launch()
        self.refuse_system_prompts()
        self.verify_and_enter_synthetic_data()
        self.test_rotation_and_ime_restore()
        self.test_fold_unfold()
        self.test_cutout()

    def finish(self, fatal: str | None = None) -> int:
        if fatal:
            self.record("smoke_runner", "fail", fatal)
        reached = {item["name"] for item in self.results}
        for name in EXPECTED_CHECKS:
            if name not in reached:
                self.record(name, "blocked", "check was not reached before smoke execution stopped")
        try:
            logcat = self.command(["logcat", "-d", "-t", "10000"], timeout=ADB_TIMEOUT_SECONDS,
                                  label="final_logcat")
            (self.artifacts / "logcat.txt").write_text(str(logcat.stdout), encoding="utf-8")
        except Exception as exc:  # best-effort diagnostics must not hide test results
            (self.artifacts / "logcat_error.txt").write_text(str(exc), encoding="utf-8")
        fail = any(item["status"] == "fail" for item in self.results)
        blocked = any(item["status"] in {"blocked", "partial"} for item in self.results)
        overall = "fail" if fail else ("partial" if blocked else "pass")
        result = {
            "overall": overall,
            "scenario": self.args.scenario,
            "scope": "unpaired host pairing form" if self.args.scenario == "host"
                     else "unpaired gateway control-pairing settings form",
            "device_profile": self.args.device_profile,
            "package": self.package,
            "serial": self.serial,
            "generated_at_utc": dt.datetime.now(dt.timezone.utc).isoformat(),
            "results": self.results,
        }
        (self.artifacts / "results.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
        summary = [f"# Android UI smoke: {overall.upper()}", "", f"- Scenario: `{self.args.scenario}`",
                   f"- Scope: {result['scope']}", f"- Device profile: `{self.args.device_profile}` (API 35 expected)",
                   "- This covers the unpaired UI path only; it does not establish paired dashboard, SIM, call, or physical foldable-device behavior.",
                   f"- Package: `{self.package}`", f"- Device: `{self.serial}`", "", "| Check | Result | Evidence |",
                   "|---|---|---|"]
        summary.extend(f"| {item['name']} | {item['status']} | {item['detail'].replace('|', '/')} |"
                       for item in self.results)
        summary_text = "\n".join(summary) + "\n"
        (self.artifacts / "summary.md").write_text(summary_text, encoding="utf-8")
        step_summary = os.environ.get("GITHUB_STEP_SUMMARY")
        if step_summary:
            with open(step_summary, "a", encoding="utf-8") as output:
                output.write(summary_text)
        if overall == "partial":
            print("::warning::Android UI smoke completed with blocked checks; see summary.md/results.json")
        return 1 if fail else 0


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", required=True, help="debug APK to install")
    parser.add_argument("--package", required=True, help="Android application ID")
    parser.add_argument("--scenario", required=True, choices=("host", "gateway"))
    parser.add_argument("--device-profile", default="unspecified", help="AVD profile configured by the caller")
    parser.add_argument("--artifact-dir", default="artifacts/android-ui-smoke")
    parser.add_argument("--serial", help="ADB serial; defaults to ANDROID_SERIAL or emulator-5554")
    return parser.parse_args()


def main() -> int:
    smoke = Smoke(parse_args())
    fatal: str | None = None
    try:
        smoke.run()
    except Exception as exc:
        fatal = f"{type(exc).__name__}: {exc}"
        print(f"Smoke execution stopped: {fatal}", file=sys.stderr, flush=True)
    return smoke.finish(fatal)


if __name__ == "__main__":
    raise SystemExit(main())
