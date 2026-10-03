#!/bin/bash
# Read-only Magisk capability probe; never select a model/preset or send a call/SMS.
set -eu
if ! command -v adb >/dev/null 2>&1; then
    echo "adb is required; install Android platform-tools." >&2
    exit 2
fi
SERIAL="${1:-}"
ADB=(adb)
[ -z "$SERIAL" ] || ADB=(adb -s "$SERIAL")
if ! "${ADB[@]}" get-state >/dev/null 2>&1; then
    echo "Select one authorized ADB device by its serial." >&2
    exit 2
fi
ROOT_UID=$("${ADB[@]}" shell "su -c 'id -u'" 2>/dev/null | tr -d '\r')
if [ "$ROOT_UID" != "0" ]; then
    echo "Magisk root access is unavailable. Grant the selected ADB shell root access." >&2
    exit 1
fi
CTL=/data/adb/modules/sip-gsm-gateway/bin/gsm2sipctl
if ! "${ADB[@]}" shell "su -c 'test -f $CTL'" >/dev/null 2>&1; then
    echo "Install the generated gateway-magisk.zip and reboot first." >&2
    exit 1
fi
echo "=== Magisk runtime capabilities ==="
"${ADB[@]}" shell "su -c '$CTL probe'" | tr -d '\r'
echo "This probe reports interfaces, never end-to-end calling acceptance."
echo "PhoneAccount mapping and audio are selected by actual capabilities, not the phone model."
