#!/system/bin/sh
# Remove skip_mount if it exists — this script runs even when skip_mount
# is present, so it guarantees the module's filesystem overlay is active
# on the next boot.
MODDIR="${0%/*}"
rm -f "$MODDIR/skip_mount"

# Keep the optional root-owned audio profile outside the module tree so module
# upgrades cannot replace user configuration. The Android app reads this exact
# file through its bounded RootShell path; network-controlled values never
# become shell commands.
CONFIG_DIR=/data/adb/gsm2sip
if [ -d /data/adb ]; then
    mkdir -p "$CONFIG_DIR"
    chown 0:0 "$CONFIG_DIR" 2>/dev/null
    chmod 0700 "$CONFIG_DIR" 2>/dev/null
    if [ -f "$CONFIG_DIR/audio-profile.json" ]; then
        chown 0:0 "$CONFIG_DIR/audio-profile.json" 2>/dev/null
        chmod 0600 "$CONFIG_DIR/audio-profile.json" 2>/dev/null
    fi
fi
