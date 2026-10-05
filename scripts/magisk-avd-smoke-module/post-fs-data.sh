#!/system/bin/sh
set -eu
STATE_DIR=/data/adb/magisk-validation-smoke
mkdir -p "$STATE_DIR"
cat /proc/sys/kernel/random/boot_id > "$STATE_DIR/post-fs-data"
chmod 0600 "$STATE_DIR/post-fs-data"
