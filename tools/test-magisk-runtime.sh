#!/bin/sh
set -eu

REPO_DIR=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)
SOURCE="$REPO_DIR/magisk/bin/gsm2sipctl"
TEST_ROOT=$(mktemp -d /tmp/gsm2sip-magisk-test.XXXXXX)
FIXTURE_ROOT="$TEST_ROOT/root"
CONTROL="$TEST_ROOT/gsm2sipctl"
OUT="$TEST_ROOT/stdout"
ERR="$TEST_ROOT/stderr"
PROTOCOL_FIXTURE="$REPO_DIR/tools/fixtures/root-telephony-accounts-v1.txt"
MODULE="$FIXTURE_ROOT/data/adb/modules/sip-gsm-gateway"
export FIXTURE_ROOT
export PROTOCOL_FIXTURE

cleanup() {
    rm -rf "$TEST_ROOT"
}
trap cleanup 0 HUP INT TERM

fail() {
    echo "FAIL: $*" >&2
    exit 1
}

assert_contains() {
    case "$1" in
        *"$2"*) ;;
        *) fail "expected output to contain: $2" ;;
    esac
}

expect_exit() {
    expected=$1
    shift
    set +e
    "$@" >"$OUT" 2>"$ERR"
    actual=$?
    set -e
    [ "$actual" -eq "$expected" ] || fail "expected exit $expected, got $actual: $(cat "$ERR")"
}

mkdir -p "$FIXTURE_ROOT/system/bin" "$FIXTURE_ROOT/system/xbin" "$FIXTURE_ROOT/vendor/bin"
mkdir -p "$FIXTURE_ROOT/data/adb/modules/sip-gsm-gateway/system/priv-app/Gateway"
mkdir -p "$FIXTURE_ROOT/data/adb/gsm2sip" "$FIXTURE_ROOT/data/app/~~hash==/com.callagent.gateway-hash=="
printf '%s\n' 'id=sip-gsm-gateway' 'version=v-test.1' > "$MODULE/module.prop"
: > "$MODULE/system/priv-app/Gateway/Gateway.apk"
: > "$FIXTURE_ROOT/data/app/~~hash==/com.callagent.gateway-hash==/base.apk"
printf '{}' > "$FIXTURE_ROOT/data/adb/gsm2sip/audio-profile.json"

# Relocate compile-time Android paths only in this disposable test copy.
sed \
    -e "s|/system/bin|$FIXTURE_ROOT/system/bin|g" \
    -e "s|/system/xbin|$FIXTURE_ROOT/system/xbin|g" \
    -e "s|/system_ext/priv-app|$FIXTURE_ROOT/system_ext/priv-app|g" \
    -e "s|/system/priv-app|$FIXTURE_ROOT/system/priv-app|g" \
    -e "s|/product/priv-app|$FIXTURE_ROOT/product/priv-app|g" \
    -e "s|/data/local/tmp|$FIXTURE_ROOT/data/local/tmp|g" \
    -e "s|/data/adb|$FIXTURE_ROOT/data/adb|g" \
    -e "s|/data/app|$FIXTURE_ROOT/data/app|g" \
    -e "s|/vendor/bin|$FIXTURE_ROOT/vendor/bin|g" \
    -e "s|^MODULE_APK=.*|MODULE_APK=\"$MODULE/system/priv-app/Gateway/Gateway.apk\"|" \
    -e '1s|^.*$|#!/bin/sh|' \
    "$SOURCE" > "$CONTROL"
chmod 755 "$CONTROL"
sh -n "$CONTROL" || fail "control script syntax"

cat > "$FIXTURE_ROOT/system/bin/id" <<'EOF'
#!/bin/sh
[ "$1" = -u ] || exit 1
printf '%s\n' "${FAKE_UID:-0}"
EOF
cat > "$FIXTURE_ROOT/system/bin/getprop" <<'EOF'
#!/bin/sh
[ "$1" = sys.boot_completed ] || exit 1
printf '%s\n' "${FAKE_BOOT:-1}"
EOF
cat > "$FIXTURE_ROOT/system/bin/pm" <<'EOF'
#!/bin/sh
[ "$1" = check-permission ] || exit 1
case "$2" in
    android.permission.RECORD_AUDIO) printf '%s\n' granted ;;
    android.permission.CAPTURE_AUDIO_OUTPUT) printf '%s\n' denied ;;
    android.permission.MODIFY_AUDIO_SETTINGS) printf '%s\n' granted ;;
    *) printf '%s\n' denied ;;
esac
EOF
cat > "$FIXTURE_ROOT/system/bin/cmd" <<'EOF'
#!/bin/sh
[ "$1" = package ] && [ "$2" = path ] && [ "$3" = com.callagent.gateway ] || exit 1
if [ -n "${FAKE_PACKAGE_PATH:-}" ]; then
    printf 'package:%s\n' "$FAKE_PACKAGE_PATH"
else
    printf 'package:%s/data/app/~~hash==/com.callagent.gateway-hash==/base.apk\n' "$FIXTURE_ROOT"
fi
EOF
cat > "$FIXTURE_ROOT/system/bin/magisk" <<'EOF'
#!/bin/sh
exit 0
EOF
cat > "$FIXTURE_ROOT/system/bin/timeout" <<'EOF'
#!/bin/sh
exec /usr/bin/timeout "$@"
EOF
cat > "$FIXTURE_ROOT/system/bin/head" <<'EOF'
#!/bin/sh
exec /usr/bin/head "$@"
EOF
cat > "$FIXTURE_ROOT/system/bin/su" <<'EOF'
#!/bin/sh
[ "$1" = -s ] || exit 91
[ "$3" = 1000 ] || exit 92
shift 3
[ "$#" -eq 0 ] || exit 93
exec /bin/sh
EOF
cat > "$FIXTURE_ROOT/system/bin/app_process" <<'EOF'
#!/bin/sh
[ "$1" = "$FIXTURE_ROOT/system/bin" ] || exit 81
[ "$2" = com.callagent.gateway.root.RootTelephonyBroker ] || exit 82
[ "$3" = accounts ] && [ "$4" = 0 ] || exit 83
case "${FAKE_BROKER_MODE:-ok}" in
    hang) exec sleep 5 ;;
    large)
        exec /usr/bin/head -c 1048576 /dev/zero | /usr/bin/tr '\000' x
        ;;
    wrong_version)
        printf '%s\n' protocol=1 broker_version=99 app_version=429 broker_uid=1000 source=magisk-system-telephony status=ok user_id=0 count=0
        exit 0
        ;;
esac
cat "$PROTOCOL_FIXTURE"
EOF
chmod 755 "$FIXTURE_ROOT/system/bin/"*

mkdir -p "$FIXTURE_ROOT/data/local/tmp"
cat > "$FIXTURE_ROOT/data/local/tmp/tinymix" <<'EOF'
#!/bin/sh
exit 0
EOF
cat > "$FIXTURE_ROOT/vendor/bin/tinycap" <<'EOF'
#!/bin/sh
exit 0
EOF
chmod 755 "$FIXTURE_ROOT/data/local/tmp/tinymix" "$FIXTURE_ROOT/vendor/bin/tinycap"

output=$("$CONTROL" probe)
assert_contains "$output" 'protocol=1'
assert_contains "$output" 'module.state=active'
assert_contains "$output" 'root.uid=0'
assert_contains "$output" 'boot.completed=true'
assert_contains "$output" 'magisk.present=true'
assert_contains "$output" 'su.present=true'
assert_contains "$output" 'app_process.present=true'
assert_contains "$output" 'privapp.module_apk.present=true'
assert_contains "$output" 'package.base_apk.present=true'
assert_contains "$output" 'audio.permission.record_audio=granted'
assert_contains "$output" 'audio.permission.capture_audio_output=denied'
assert_contains "$output" 'audio-profile.present=true'
assert_contains "$output" 'audio-profile.bytes=2'
assert_contains "$output" 'mixer.tinymix.present=true'
assert_contains "$output" 'mixer.tinycap.present=true'
assert_contains "$output" 'voice.call_acceptance=not_tested'

expect_exit 77 env FAKE_UID=2000 sh "$CONTROL" probe
[ ! -s "$OUT" ] || fail "non-root probe exposed output"
assert_contains "$(cat "$ERR")" root_required
output=$(env FAKE_BOOT=0 sh "$CONTROL" status)
assert_contains "$output" 'boot.completed=false'

touch "$MODULE/disable"
output=$("$CONTROL" status)
assert_contains "$output" 'module.state=disabled'
expect_exit 10 sh "$CONTROL" accounts 0
assert_contains "$(cat "$ERR")" module_not_active
rm "$MODULE/disable"

touch "$MODULE/remove"
output=$("$CONTROL" status)
assert_contains "$output" 'module.state=removed'
expect_exit 10 sh "$CONTROL" accounts 0
assert_contains "$(cat "$ERR")" module_not_active
rm "$MODULE/remove"

output=$(sh "$CONTROL" accounts 0)
expected=$(cat "$PROTOCOL_FIXTURE")
[ "$output" = "$expected" ] || fail "module output differs from shared app parser fixture"
assert_contains "$output" 'protocol=1'
assert_contains "$output" 'broker_uid=1000'
assert_contains "$output" 'source=magisk-system-telephony'
assert_contains "$output" 'status=ok'
assert_contains "$output" 'account.0.sub_id=14'
assert_contains "$output" 'account.0.user_id=0'

expect_exit 64 sh "$CONTROL" accounts '0;id'
assert_contains "$(cat "$ERR")" invalid_user_id
bad_package_path="$FIXTURE_ROOT/data/app/~~hash=\$/com.callagent.gateway-hash==/base.apk"
expect_exit 10 env FAKE_PACKAGE_PATH="$bad_package_path" sh "$CONTROL" accounts 0
assert_contains "$(cat "$ERR")" package_apk_unavailable
bad_package_path="$FIXTURE_ROOT/data/app/~~hash==/com.callagent.gateway-hash==/base.apk'"
expect_exit 10 env FAKE_PACKAGE_PATH="$bad_package_path" sh "$CONTROL" accounts 0
assert_contains "$(cat "$ERR")" package_apk_unavailable
expect_exit 20 env FAKE_BROKER_MODE=wrong_version sh "$CONTROL" accounts 0
assert_contains "$(cat "$ERR")" broker_version_mismatch
expect_exit 20 env FAKE_BROKER_MODE=large sh "$CONTROL" accounts 0
assert_contains "$(cat "$ERR")" broker_output_too_large

# Shorten only this copied control entry point so the timeout negative case is fast.
sed -e 's/KILL 8/KILL 1/g' -e 's/KILL 10/KILL 2/g' "$CONTROL" > "$TEST_ROOT/gsm2sipctl-fast"
chmod 755 "$TEST_ROOT/gsm2sipctl-fast"
expect_exit 20 env FAKE_BROKER_MODE=hang sh "$TEST_ROOT/gsm2sipctl-fast" accounts 0
assert_contains "$(cat "$ERR")" broker_timeout

rm "$FIXTURE_ROOT/system/bin/timeout"
expect_exit 10 sh "$CONTROL" accounts 0
assert_contains "$(cat "$ERR")" timeout_unavailable

echo "Magisk runtime harness: PASS"
