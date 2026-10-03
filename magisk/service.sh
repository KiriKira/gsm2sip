#!/system/bin/sh
# service.sh — runs late in boot (after data is decrypted & mounted)
#
# Keeps the priv-app APK in sync when the user updates via 'adb install -r'.
# The updated APK goes to /data/app/ but the priv-app base in the Magisk
# overlay becomes stale.  This script copies the latest APK so the overlay
# is correct on the NEXT reboot.
#
# Also logs CAPTURE_AUDIO_OUTPUT grant status for debugging.

MODDIR="${0%/*}"
TAG="GatewayMagisk"

MOD_VER=$(grep '^version=' "$MODDIR/module.prop" 2>/dev/null | cut -d= -f2)
log -t "$TAG" "SIP-GSM Gateway Magisk Module ${MOD_VER:-unknown} — service.sh running"

PRIV_DIR="$MODDIR/system/priv-app/Gateway"
PRIV_APK="$PRIV_DIR/Gateway.apk"

# ── Sync APK ──────────────────────────────────────────
# pm path returns the currently-active APK (may be /data/app/ update)
APK_PATH=$(pm path com.callagent.gateway 2>/dev/null | head -1 | sed 's/^package://')

if [ -n "$APK_PATH" ] && [ -f "$APK_PATH" ]; then
    if [ ! -f "$PRIV_APK" ]; then
        # No priv-app APK yet — copy it
        mkdir -p "$PRIV_DIR"
        cp "$APK_PATH" "$PRIV_APK"
        chmod 644 "$PRIV_APK"
        log -t "$TAG" "Created priv-app APK from $APK_PATH (reboot needed)"
    elif ! cmp -s "$APK_PATH" "$PRIV_APK" 2>/dev/null; then
        # APK was updated via adb install — sync it
        cp "$APK_PATH" "$PRIV_APK"
        chmod 644 "$PRIV_APK"
        log -t "$TAG" "Synced updated APK from $APK_PATH (reboot needed for priv-app refresh)"
    else
        log -t "$TAG" "Priv-app APK is up to date"
    fi
else
    log -t "$TAG" "Gateway app not installed — nothing to sync"
fi

# ── Wait for PackageManager ───────────────────────────
# service.sh runs in late_start, which is still early enough that `pm` is not
# answering yet: every grant below silently did nothing on a cold boot, which
# is how RECEIVE_SMS came to be ungranted while the log claimed otherwise.
# Backgrounded so the wait does not hold up Magisk's service stage.
wait_for_pm() {
    i=0
    while [ "$(getprop sys.boot_completed)" != "1" ] && [ $i -lt 150 ]; do
        sleep 2
        i=$((i + 1))
    done
    i=0
    while ! pm path android >/dev/null 2>&1 && [ $i -lt 30 ]; do
        sleep 2
        i=$((i + 1))
    done
}

# ── Grant runtime permissions automatically ───────────
# These normally require user approval via UI prompts.
# Granting them here avoids manual setup on a headless gateway.
PKG="com.callagent.gateway"
(
wait_for_pm
for PERM in \
    android.permission.RECORD_AUDIO \
    android.permission.READ_PHONE_STATE \
    android.permission.READ_PHONE_NUMBERS \
    android.permission.READ_CALL_LOG \
    android.permission.RECEIVE_SMS \
    android.permission.SEND_SMS \
    android.permission.ACCESS_FINE_LOCATION \
    android.permission.ACCESS_COARSE_LOCATION \
    android.permission.CALL_PHONE \
    android.permission.ANSWER_PHONE_CALLS \
    android.permission.POST_NOTIFICATIONS \
; do
    pm grant "$PKG" "$PERM" 2>/dev/null && \
        log -t "$TAG" "Granted: $PERM" || \
        log -t "$TAG" "Skip (already granted or N/A): $PERM"
done
) &

# ── Ensure tinymix is available ────────────────────────
# tinymix is needed to control ABOX/ALSA mixer for incall_music injection.
# /system/bin/tinymix via Magisk overlay can hit SELinux "Permission denied"
# on some devices, so we install to /data/local/tmp/ which has a permissive
# context.  The app prefers /data/local/tmp/ in its discovery order.
# tinymix is bundled once per ABI: the ALSA control ioctls encode the size of
# structs holding `long`, so an ARM64 build and an armeabi-v7a build speak
# different ioctl ABIs and neither works on the other's kernel.
DEVICE_ABI=$(getprop ro.product.cpu.abi 2>/dev/null)
case "$DEVICE_ABI" in
    arm64*|aarch64*) TINYMIX_SRC="$MODDIR/tinymix" ;;
    arm*)            TINYMIX_SRC="$MODDIR/tinymix32" ;;
    *)               TINYMIX_SRC="" ;;
esac
if [ -n "$TINYMIX_SRC" ] && [ -f "$TINYMIX_SRC" ]; then
    cp "$TINYMIX_SRC" /data/local/tmp/tinymix
    chmod 755 /data/local/tmp/tinymix
    chown root:root /data/local/tmp/tinymix
    log -t "$TAG" "tinymix: installed $DEVICE_ABI binary to /data/local/tmp/tinymix"
else
    log -t "$TAG" "tinymix: no bundled build for ABI=$DEVICE_ABI"
fi
TINYMIX_FOUND=false
for TPATH in /data/local/tmp/tinymix /vendor/bin/tinymix /system/bin/tinymix /system/xbin/tinymix; do
    if [ -x "$TPATH" ]; then
        TINYMIX_FOUND=true
        log -t "$TAG" "tinymix: using $TPATH"
        break
    fi
done
if [ "$TINYMIX_FOUND" = "false" ]; then
    log -t "$TAG" "tinymix: NOT FOUND — ABOX mixer controls will not work"
fi

# ── Ensure tinycap is available ───────────────────────
# tinycap is needed to probe ALSA capture PCMs for modem downlink audio.
# Same deployment strategy as tinymix: /data/local/tmp/ for SELinux compat.
# The bundled tinycap is an ARM64 C build with no source in this tree, so on a
# 32-bit device fall back to the ROM's own — LineageOS ships tinyplay/tinycap/
# tinypcminfo on the msm8960 devices, and the app only ever looks for tinycap
# at the /data/local/tmp path.
TINYCAP_SRC=""
case "$DEVICE_ABI" in
    arm64*|aarch64*) [ -f "$MODDIR/tinycap" ] && TINYCAP_SRC="$MODDIR/tinycap" ;;
    *)               [ -x /system/bin/tinycap ] && TINYCAP_SRC=/system/bin/tinycap ;;
esac
if [ -n "$TINYCAP_SRC" ]; then
    cp "$TINYCAP_SRC" /data/local/tmp/tinycap
    chmod 755 /data/local/tmp/tinycap
    chown root:root /data/local/tmp/tinycap
    log -t "$TAG" "tinycap: installed $TINYCAP_SRC to /data/local/tmp/tinycap"
else
    log -t "$TAG" "tinycap: none available for ABI=$DEVICE_ABI — PCM probe will be skipped"
fi

# ── Log ALSA card info for diagnostics ────────────────
ALSA_CARDS=$(cat /proc/asound/cards 2>/dev/null)
if [ -n "$ALSA_CARDS" ]; then
    log -t "$TAG" "ALSA cards: $ALSA_CARDS"
fi

# ── Log privileged permission status ──────────────────
PERM_DUMP=$(dumpsys package "$PKG" 2>/dev/null)
for PERM in CAPTURE_AUDIO_OUTPUT MODIFY_PHONE_STATE READ_PRIVILEGED_PHONE_STATE CALL_PRIVILEGED; do
    if echo "$PERM_DUMP" | grep -q "$PERM.*granted=true"; then
        log -t "$TAG" "$PERM: GRANTED"
    else
        log -t "$TAG" "$PERM: NOT GRANTED — check priv-app install, reboot may be needed"
    fi
done

# Log install location for debugging
log -t "$TAG" "APK path: $APK_PATH"
log -t "$TAG" "Priv-app: $(ls -la $PRIV_APK 2>/dev/null || echo 'missing')"
