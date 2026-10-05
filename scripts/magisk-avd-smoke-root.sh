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
OFFICIAL_APP_FUNCTIONS=/data/local/tmp/magisk-avd-app-functions.sh
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
    if [ ! -f "$OFFICIAL_APP_FUNCTIONS" ] || [ ! -r "$OFFICIAL_APP_FUNCTIONS" ]; then
        echo "official_app_functions_file=$OFFICIAL_APP_FUNCTIONS" >&2
        ls -ld /data/local/tmp "$OFFICIAL_APP_FUNCTIONS" >&2 2>/dev/null || true
        fail official_app_functions_file_missing_or_unreadable
    fi
    grep -Fqx 'env_check() {' "$OFFICIAL_APP_FUNCTIONS" || fail official_app_functions_env_check_missing
    grep -Fqx 'fix_env() {' "$OFFICIAL_APP_FUNCTIONS" || fail official_app_functions_fix_env_missing
    . "$OFFICIAL_APP_FUNCTIONS" || fail official_app_functions_source_failed
    MAGISKBIN=/data/adb/magisk
    MAGISKTMP=$("$MAGISK_PATH" --path 2>/dev/null | sed -n '1p')
    [ -n "$MAGISKTMP" ] || fail magisk_tmp_path_missing
    export MAGISKBIN MAGISKTMP
    APP_FUNCTIONS_META=$(stat -c '%u:%g:%a:%s' "$OFFICIAL_APP_FUNCTIONS" 2>/dev/null || echo unknown)
    echo "official_app_functions=loaded"
    echo "official_app_functions_metadata=$APP_FUNCTIONS_META"
}

diagnose_util_functions_file() {
    local LABEL=$1
    local FILE=$2
    echo "${LABEL}_path=$FILE"
    if [ ! -f "$FILE" ]; then
        echo "${LABEL}_exists=false"
        return 0
    fi

    echo "${LABEL}_exists=true"
    echo "${LABEL}_metadata=$(stat -c '%u:%g:%a:%s' "$FILE" 2>/dev/null || echo unknown)"
    echo "${LABEL}_selinux_context=$(ls -Zd "$FILE" 2>/dev/null | sed -n '1p' || echo unknown)"
    echo "${LABEL}_sha256=$(sha256sum "$FILE" 2>/dev/null | sed -n '1s/ .*//p' || echo unavailable)"

    set +e
    grep -n -E '^MAGISK_VER(_CODE)?=' "$FILE"
    local LINES_RC=$?
    grep -xqF "MAGISK_VER='$MAGISK_VERSION'" "$FILE"
    local VERSION_RC=$?
    grep -xqF "MAGISK_VER_CODE=$MAGISK_VERSION_CODE" "$FILE"
    local VERSION_CODE_RC=$?
    set -e

    echo "${LABEL}_version_lines_rc=$LINES_RC"
    echo "${LABEL}_version_match_rc=$VERSION_RC"
    echo "${LABEL}_version_code_match_rc=$VERSION_CODE_RC"
}

diagnose_magisk_environment() {
    echo "magisk_diag_expected_version=$MAGISK_VERSION"
    echo "magisk_diag_expected_version_code=$MAGISK_VERSION_CODE"
    echo "magisk_diag_identity=$(id 2>&1 | sed -n '1p')"
    echo "magisk_diag_selinux_identity=$(id -Z 2>&1 | sed -n '1p')"
    echo "magisk_diag_grep_path=$(command -v grep 2>/dev/null || echo missing)"
    echo "magisk_diag_sha256sum_path=$(command -v sha256sum 2>/dev/null || echo missing)"

    diagnose_util_functions_file active_util_functions "$MAGISKBIN/util_functions.sh"
    diagnose_util_functions_file cache_alt_util_functions /cache/data_adb/magisk/util_functions.sh
    diagnose_util_functions_file data_alt_util_functions /data/magisk/util_functions.sh
    diagnose_util_functions_file manager_alt_util_functions /data/user_de/0/com.topjohnwu.magisk/install/util_functions.sh

    set +e
    grep -xqF "MAGISK_VER='$MAGISK_VERSION'" "$MAGISKBIN/util_functions.sh"
    local EXPECTED_GREP_RC=$?
    set -e
    echo "magisk_diag_grep_exact_expected_line_rc=$EXPECTED_GREP_RC"
}

extract_official_apk_files() {
    local LABEL=$1
    local DESTINATION=$2
    shift 2
    local UNZIP_LOG=$ENV_FIX_DIR/${LABEL}.unzip.log
    if unzip -o -j "$OFFICIAL_APK" "$@" -d "$DESTINATION" >"$UNZIP_LOG" 2>&1; then
        echo "official_apk_extract=$LABEL"
    else
        local UNZIP_RC=$?
        echo "official_apk_extract=$LABEL failed exit=$UNZIP_RC log=$UNZIP_LOG" >&2
        cat "$UNZIP_LOG" >&2 2>/dev/null || true
        return 1
    fi
}

verify_magisk_environment() {
    verify_magisk
    load_official_app_functions
    diagnose_magisk_environment
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

    extract_official_apk_files magisk-scripts "$ENV_INSTALL_DIR" \
        assets/util_functions.sh assets/boot_patch.sh assets/addon.d.sh assets/stub.apk || fail official_magisk_scripts_extract_failed
    extract_official_apk_files chromeos-assets "$ENV_INSTALL_DIR/chromeos" \
        assets/chromeos/futility assets/chromeos/kernel_data_key.vbprivk assets/chromeos/kernel.keyblock || fail official_magisk_chromeos_assets_extract_failed
    extract_official_apk_files x86_64-libraries "$ENV_INSTALL_DIR" "lib/$ABI/*.so" || fail official_magisk_x86_64_libraries_extract_failed

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
        extract_official_apk_files x86-32bit-library "$ENV_FIX_DIR/abi32" \
            "lib/$ABI32/libmagisk.so" || fail official_magisk_32bit_library_extract_failed
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

    BROKER_STDERR_FILE=/data/local/tmp/gsm2sip-broker-stderr-$$.log
    rm -f "$BROKER_STDERR_FILE"
    set +e
    ACCOUNTS_OUTPUT=$("$CTL" accounts 0 2>"$BROKER_STDERR_FILE")
    ACCOUNTS_RC=$?
    set -e
    BROKER_STATUS=$(printf '%s\n' "$ACCOUNTS_OUTPUT" | sed -n 's/^status=//p' | sed -n '1p')
    BROKER_COUNT=$(printf '%s\n' "$ACCOUNTS_OUTPUT" | sed -n 's/^count=//p' | sed -n '1p')
    BROKER_ERROR=$(printf '%s\n' "$ACCOUNTS_OUTPUT" | sed -n 's/^error=//p' | sed -n '1p')
    case "$BROKER_STATUS" in ok|unavailable|error) BROKER_STATUS_SAFE=$BROKER_STATUS ;; *) BROKER_STATUS_SAFE=invalid ;; esac
    case "$BROKER_COUNT" in
        ''|*[!0-9]*) BROKER_COUNT_SAFE=unknown ;;
        *)
            if [ "${#BROKER_COUNT}" -le 2 ] && [ "$BROKER_COUNT" -le 32 ]; then
                BROKER_COUNT_SAFE=$BROKER_COUNT
            else
                BROKER_COUNT_SAFE=unknown
            fi
            ;;
    esac
    case "$BROKER_ERROR" in
        '') BROKER_ERROR_SAFE=none ;;
        permission|capability|service|api|context|subscription|telecom|telephony|user|limit|uid|arguments)
            BROKER_ERROR_SAFE=$BROKER_ERROR ;;
        *) BROKER_ERROR_SAFE=invalid ;;
    esac
    BROKER_STDERR_PRESENT=false
    BROKER_STDERR_CLASS=none
    if [ -s "$BROKER_STDERR_FILE" ]; then
        BROKER_STDERR_PRESENT=true
        BROKER_STDERR_LINE=$(sed -n '1p' "$BROKER_STDERR_FILE" | tr -cd 'A-Za-z0-9_:-')
        case "$BROKER_STDERR_LINE" in
            unavailable:permission|unavailable:capability|unavailable:service|unavailable:api|unavailable:context|unavailable:subscription|unavailable:telecom|unavailable:telephony|unavailable:user|unavailable:limit|unavailable:uid|unavailable:arguments|broker_timeout|broker_exit_marker_missing|broker_output_too_large|broker_exit_status_mismatch|broker_invalid_exit_marker)
                BROKER_STDERR_CLASS=$BROKER_STDERR_LINE
                ;;
            *) BROKER_STDERR_CLASS=unclassified ;;
        esac
    fi
    rm -f "$BROKER_STDERR_FILE"
    echo "broker.query_exit_code=$ACCOUNTS_RC"
    echo "broker.query_status=$BROKER_STATUS_SAFE"
    echo "broker.query_count=$BROKER_COUNT_SAFE"
    echo "broker.query_error=$BROKER_ERROR_SAFE"
    echo "broker.stderr_present=$BROKER_STDERR_PRESENT"
    echo "broker.stderr_class=$BROKER_STDERR_CLASS"
    # This API 34 image has framework Telephony/Telecom services. A successful
    # empty query is valid without real SIMs; setup/attribution errors are not.
    [ "$BROKER_COUNT_SAFE" != unknown ] || fail gateway_broker_invalid_count
    case "$ACCOUNTS_RC:$BROKER_STATUS" in
        0:ok) ;;
        10:unavailable) fail "gateway_broker_query_unavailable_$BROKER_ERROR_SAFE" ;;
        *) fail gateway_broker_unexpected_result ;;
    esac
    echo "broker.query_completed=true"
    echo "broker.status=$BROKER_STATUS_SAFE"
    echo "broker.count=$BROKER_COUNT_SAFE"
    echo "broker.error=$BROKER_ERROR_SAFE"
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
