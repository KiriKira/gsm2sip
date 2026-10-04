#!/system/bin/sh
set -eu
export PATH=/debug_ramdisk:/sbin:$PATH

MAGISK_VERSION=30.7
MAGISK_VERSION_CODE=30700
PROBE_ID=magisk-validation-smoke
GATEWAY_ID=sip-gsm-gateway
STATE_DIR=/data/adb/magisk-validation-smoke
PROBE_DIR=/data/adb/modules/$PROBE_ID
GATEWAY_DIR=/data/adb/modules/$GATEWAY_ID
CTL=$GATEWAY_DIR/bin/gsm2sipctl
OFFICIAL_APK=/data/local/tmp/magisk-v30.7.apk
ENV_FIX_DIR=/data/local/tmp/magisk-avd-official-env-fix
ENV_INSTALL_DIR=$ENV_FIX_DIR/install

fail() {
    echo "smoke_error=$1" >&2
    exit 1
}

verify_magisk() {
    [ "$(id -u)" = 0 ] || fail root_uid
    MAGISK_PATH=$(command -v magisk || true)
    if [ -z "$MAGISK_PATH" ]; then
        for candidate in /data/adb/magisk/magisk /sbin/magisk /debug_ramdisk/magisk /system/bin/magisk; do
            if [ -x "$candidate" ]; then MAGISK_PATH=$candidate; break; fi
        done
    fi
    [ -n "$MAGISK_PATH" ] || fail magisk_cli_missing
    MAGISK_VER=$("$MAGISK_PATH" -v 2>/dev/null | sed -n '1p')
    MAGISK_CODE=$("$MAGISK_PATH" -V 2>/dev/null | sed -n '1p')
    case "$MAGISK_VER" in "$MAGISK_VERSION"*) ;; *) fail magisk_version_mismatch ;; esac
    [ "$MAGISK_CODE" = "$MAGISK_VERSION_CODE" ] || fail magisk_version_code_mismatch
    echo "root_uid=0"
    echo "magisk_path=$MAGISK_PATH"
    echo "magisk_version=$MAGISK_VER"
    echo "magisk_version_code=$MAGISK_CODE"
}

verify_shell_policy_is_not_persistent() {
    POLICY_RESULT=$("$MAGISK_PATH" --sqlite 'SELECT COUNT(*) FROM policies WHERE uid=2000;' | sed -n '$p')
    case "$POLICY_RESULT" in
        0|*=0) ;;
        *) fail shell_su_policy_persisted_or_unreadable ;;
    esac
    echo "su_shell_uid=2000"
    echo "su_policy_persisted=false"
}

load_official_app_functions() {
    [ -f "$OFFICIAL_APK" ] || fail official_magisk_apk_missing
    mkdir -p "$ENV_FIX_DIR"
    unzip -o -j "$OFFICIAL_APK" assets/app_functions.sh -d "$ENV_FIX_DIR" >/dev/null 2>&1 || fail official_app_functions_extract_failed
    [ -f "$ENV_FIX_DIR/app_functions.sh" ] || fail official_app_functions_missing
    . "$ENV_FIX_DIR/app_functions.sh"
    MAGISKBIN=/data/adb/magisk
    MAGISKTMP=$("$MAGISK_PATH" --path 2>/dev/null | sed -n '1p')
    [ -n "$MAGISKTMP" ] || fail magisk_tmp_path_missing
    export MAGISKBIN MAGISKTMP
}

verify_magisk_environment() {
    verify_magisk
    load_official_app_functions
    if env_check "$MAGISK_VERSION" "$MAGISK_VERSION_CODE"; then
        echo "magisk_environment=complete"
        echo "magisk_environment_path=$MAGISKBIN"
        echo "magisk_runtime_path=$MAGISKTMP"
    else
        ENV_CHECK_RC=$?
        fail "magisk_environment_incomplete_$ENV_CHECK_RC"
    fi
}

fix_magisk_environment() {
    verify_magisk
    [ -f "$OFFICIAL_APK" ] || fail official_magisk_apk_missing
    ABI=$(getprop ro.product.cpu.abi)
    [ "$ABI" = x86_64 ] || fail "unexpected_abi_for_official_env_fix_$ABI"
    rm -rf "$ENV_INSTALL_DIR"
    mkdir -p "$ENV_INSTALL_DIR/chromeos"

    unzip -o -j "$OFFICIAL_APK" \
        assets/util_functions.sh assets/boot_patch.sh assets/addon.d.sh assets/stub.apk \
        -d "$ENV_INSTALL_DIR" >/dev/null 2>&1 || fail official_magisk_scripts_extract_failed
    unzip -o -j "$OFFICIAL_APK" \
        assets/chromeos/futility assets/chromeos/kernel_data_key.vbprivk assets/chromeos/kernel.keyblock \
        -d "$ENV_INSTALL_DIR/chromeos" >/dev/null 2>&1 || fail official_magisk_chromeos_assets_extract_failed
    unzip -o -j "$OFFICIAL_APK" "lib/$ABI/*.so" \
        -d "$ENV_INSTALL_DIR" >/dev/null 2>&1 || fail official_magisk_x86_64_libraries_extract_failed

    grep -qx "MAGISK_VER='$MAGISK_VERSION'" "$ENV_INSTALL_DIR/util_functions.sh" || fail official_magisk_version_asset_mismatch
    grep -qx "MAGISK_VER_CODE=$MAGISK_VERSION_CODE" "$ENV_INSTALL_DIR/util_functions.sh" || fail official_magisk_version_code_asset_mismatch

    for LIB_FILE in "$ENV_INSTALL_DIR"/lib*.so; do
        [ -f "$LIB_FILE" ] || continue
        LIB_NAME=${LIB_FILE##*/}
        LIB_NAME=${LIB_NAME#lib}
        LIB_NAME=${LIB_NAME%.so}
        mv "$LIB_FILE" "$ENV_INSTALL_DIR/$LIB_NAME"
    done

    ABI32=$(getprop ro.product.cpu.abilist32 | sed 's/,.*//')
    if [ -n "$ABI32" ]; then
        mkdir -p "$ENV_FIX_DIR/abi32"
        unzip -o -j "$OFFICIAL_APK" "lib/$ABI32/libmagisk.so" \
            -d "$ENV_FIX_DIR/abi32" >/dev/null 2>&1 || fail official_magisk_32bit_library_extract_failed
        [ -f "$ENV_FIX_DIR/abi32/libmagisk.so" ] || fail official_magisk_32bit_library_missing
        cp "$ENV_FIX_DIR/abi32/libmagisk.so" "$ENV_INSTALL_DIR/magisk32"
    fi

    for REQUIRED in busybox magisk magiskboot magiskinit magiskpolicy init-ld util_functions.sh boot_patch.sh addon.d.sh stub.apk; do
        [ -f "$ENV_INSTALL_DIR/$REQUIRED" ] || fail "official_magisk_env_file_missing_$REQUIRED"
    done
    for REQUIRED in futility kernel_data_key.vbprivk kernel.keyblock; do
        [ -f "$ENV_INSTALL_DIR/chromeos/$REQUIRED" ] || fail "official_magisk_chromeos_file_missing_$REQUIRED"
    done

    load_official_app_functions
    echo "magisk_environment_before_fix_util_functions=$([ -f "$MAGISKBIN/util_functions.sh" ] && echo present || echo missing)"
    fix_env "$ENV_INSTALL_DIR"
    verify_magisk_environment
    echo "magisk_environment_fix=official_app_functions_fix_env"
}

verify_probe_module() {
    [ -d "$PROBE_DIR" ] || fail probe_module_missing
    [ -f "$PROBE_DIR/module.prop" ] || fail probe_module_prop_missing
    grep -qx "id=$PROBE_ID" "$PROBE_DIR/module.prop" || fail probe_module_id_mismatch
    [ ! -e "$PROBE_DIR/disable" ] || fail probe_module_disabled
    [ ! -e "$PROBE_DIR/remove" ] || fail probe_module_removal_pending
    [ "$(cat /system/etc/magisk-validation-smoke 2>/dev/null)" = systemless-mounted ] || fail systemless_marker_missing

    CURRENT_BOOT=$(cat /proc/sys/kernel/random/boot_id)
    POST_FS_DATA=$(cat "$STATE_DIR/post-fs-data" 2>/dev/null || true)
    SERVICE=$(cat "$STATE_DIR/service" 2>/dev/null || true)
    [ -n "$CURRENT_BOOT" ] || fail current_boot_id_missing
    [ "$POST_FS_DATA" = "$CURRENT_BOOT" ] || fail post_fs_data_marker_mismatch
    [ "$SERVICE" = "$CURRENT_BOOT" ] || fail service_marker_mismatch

    echo "probe_module_id=$PROBE_ID"
    echo "probe_module_enabled=true"
    echo "current_boot_id=$CURRENT_BOOT"
    echo "post_fs_data_boot_id=$POST_FS_DATA"
    echo "service_boot_id=$SERVICE"
    echo "systemless_marker=systemless-mounted"
}

install_probe() {
    verify_magisk_environment
    "$MAGISK_PATH" --install-module /data/local/tmp/magisk-validation-smoke.zip
    echo "probe_module_install=accepted"
    verify_shell_policy_is_not_persistent
}

install_gateway() {
    verify_magisk_environment
    verify_probe_module
    [ ! -e /data/adb/gsm2sip ] || fail project_post_fs_data_path_preexisted
    "$MAGISK_PATH" --install-module /data/local/tmp/gateway-magisk.zip
    echo "gateway_module_install=accepted"
    verify_shell_policy_is_not_persistent
}

probe_gateway() {
    verify_magisk_environment
    verify_probe_module
    verify_shell_policy_is_not_persistent
    [ -d "$GATEWAY_DIR" ] || fail gateway_module_missing
    [ -f "$GATEWAY_DIR/module.prop" ] || fail gateway_module_prop_missing
    grep -qx "id=$GATEWAY_ID" "$GATEWAY_DIR/module.prop" || fail gateway_module_id_mismatch
    [ ! -e "$GATEWAY_DIR/disable" ] || fail gateway_module_disabled
    [ ! -e "$GATEWAY_DIR/remove" ] || fail gateway_module_removal_pending
    [ -f "$GATEWAY_DIR/system/priv-app/Gateway/Gateway.apk" ] || fail gateway_privapp_apk_missing
    [ -f /system/priv-app/Gateway/Gateway.apk ] || fail gateway_system_overlay_missing
    [ -f /system/etc/permissions/privapp-permissions-gateway.xml ] || fail gateway_privapp_allowlist_missing
    [ -x "$CTL" ] || fail gateway_control_tool_missing

    CONFIG_META=$(stat -c '%u:%a' /data/adb/gsm2sip 2>/dev/null || true)
    [ "$CONFIG_META" = 0:700 ] || fail gateway_post_fs_data_config_dir_owner_or_mode
    echo "gateway_post_fs_data_config_dir=$CONFIG_META"

    SERVICE_LOG=$(logcat -d -s GatewayMagisk:I '*:S' 2>/dev/null || true)
    printf '%s\n' "$SERVICE_LOG" | grep -q 'service.sh running' || fail gateway_service_hook_missing
    echo "gateway_service_hook=ran"

    PROBE_OUTPUT=$("$CTL" probe)
    printf '%s\n' "$PROBE_OUTPUT"
    printf '%s\n' "$PROBE_OUTPUT" | grep -qx 'module.id=sip-gsm-gateway' || fail gateway_probe_module_id
    printf '%s\n' "$PROBE_OUTPUT" | grep -qx 'module.state=active' || fail gateway_probe_module_state
    printf '%s\n' "$PROBE_OUTPUT" | grep -qx 'module.enabled=true' || fail gateway_probe_module_enabled
    printf '%s\n' "$PROBE_OUTPUT" | grep -qx 'root.uid=0' || fail gateway_probe_root
    printf '%s\n' "$PROBE_OUTPUT" | grep -qx 'privapp.module_apk.present=true' || fail gateway_probe_module_apk
    printf '%s\n' "$PROBE_OUTPUT" | grep -qx 'package.base_apk.present=true' || fail gateway_probe_package_apk

    set +e
    ACCOUNTS_OUTPUT=$("$CTL" accounts 0 2>/dev/null)
    ACCOUNTS_RC=$?
    set -e
    BROKER_STATUS=$(printf '%s\n' "$ACCOUNTS_OUTPUT" | sed -n 's/^status=//p' | sed -n '1p')
    BROKER_COUNT=$(printf '%s\n' "$ACCOUNTS_OUTPUT" | sed -n 's/^count=//p' | sed -n '1p')
    BROKER_ERROR=$(printf '%s\n' "$ACCOUNTS_OUTPUT" | sed -n 's/^error=//p' | sed -n '1p')
    case "$ACCOUNTS_RC:$BROKER_STATUS" in
        0:ok|10:unavailable) ;;
        *) fail gateway_broker_unexpected_result ;;
    esac
    echo "broker.status=$BROKER_STATUS"
    echo "broker.count=${BROKER_COUNT:-unknown}"
    echo "broker.error=${BROKER_ERROR:-none}"
    echo "voice.call_acceptance=not_tested"
}

case "${1:-}" in
    fix_environment) fix_magisk_environment ;;
    verify_environment) verify_magisk_environment ;;
    install_probe) install_probe ;;
    install_gateway) install_gateway ;;
    probe_gateway) probe_gateway ;;
    *) fail invalid_action ;;
esac
