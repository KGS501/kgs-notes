#!/usr/bin/env bash
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-/home/agent/Projects/kgs-calendar/.android-sdk}"
ANDROID_AVD_HOME="${ANDROID_AVD_HOME:-${ANDROID_SDK_ROOT}/avd}"
export ANDROID_SDK_ROOT ANDROID_AVD_HOME
AVD_NAME="${KGS_NOTES_AVD_NAME:-kgs_notes_phone_api36}"
UNIT_NAME="kgs-notes-emulator.service"
ADB="${ANDROID_SDK_ROOT}/platform-tools/adb"
EMULATOR="${ANDROID_SDK_ROOT}/emulator/emulator"
AVDMANAGER="${ANDROID_SDK_ROOT}/cmdline-tools/latest/bin/avdmanager"
SYSTEM_IMAGE="system-images;android-36;google_apis;x86_64"
PACKAGE_NAME="com.kgs501.kgsnotes"

usage() {
    echo "Usage: tools/android-emulator.sh create|start|wait|status|stop|install <apk>|launch|screenshot <png>|ui [xml]|logs"
}

require_tooling() {
    test -x "$ADB" || { echo "adb not found at $ADB" >&2; exit 1; }
    test -x "$EMULATOR" || { echo "emulator not found at $EMULATOR" >&2; exit 1; }
}

device_ready() {
    "$ADB" get-state >/dev/null 2>&1 &&
        test "$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" &&
        timeout 8 "$ADB" shell pm path android >/dev/null 2>&1
}

create_avd() {
    require_tooling
    test -x "$AVDMANAGER" || { echo "avdmanager not found at $AVDMANAGER" >&2; exit 1; }
    if "$EMULATOR" -list-avds | grep -Fxq "$AVD_NAME"; then
        echo "AVD $AVD_NAME already exists"
        return
    fi
    mkdir -p "$ANDROID_AVD_HOME"
    printf 'no\n' | ANDROID_AVD_HOME="$ANDROID_AVD_HOME" "$AVDMANAGER" create avd \
        --name "$AVD_NAME" \
        --package "$SYSTEM_IMAGE" \
        --device pixel_6
    echo "Created $AVD_NAME"
}

start_emulator() {
    require_tooling
    if device_ready; then
        echo "Emulator is already ready"
        return
    fi
    if ! "$EMULATOR" -list-avds | grep -Fxq "$AVD_NAME"; then
        create_avd
    fi
    local available_kib
    available_kib="$(awk '/MemAvailable/ { print $2 }' /proc/meminfo)"
    if (( available_kib < 5242880 )); then
        echo "Warning: less than 5 GiB host memory is available; stop idle Gradle daemons if the emulator becomes sluggish." >&2
    fi
    systemctl --user reset-failed "$UNIT_NAME" >/dev/null 2>&1 || true
    if ! systemctl --user is-active --quiet "$UNIT_NAME"; then
        systemd-run --user \
            --unit="${UNIT_NAME%.service}" \
            --collect \
            --property="WorkingDirectory=$PROJECT_ROOT" \
            --setenv="ANDROID_SDK_ROOT=$ANDROID_SDK_ROOT" \
            --setenv="ANDROID_AVD_HOME=$ANDROID_AVD_HOME" \
            "$EMULATOR" \
            -avd "$AVD_NAME" \
            -no-window \
            -gpu swiftshader_indirect \
            -no-audio \
            -no-boot-anim \
            -no-snapshot-load \
            -no-snapshot-save
    fi
    wait_for_device
}

wait_for_device() {
    require_tooling
    local deadline=$((SECONDS + 240))
    while (( SECONDS < deadline )); do
        if device_ready; then
            "$ADB" shell settings put global window_animation_scale 1
            "$ADB" shell settings put global transition_animation_scale 1
            "$ADB" shell settings put global animator_duration_scale 1
            for package_name in \
                com.google.android.apps.wellbeing \
                com.google.android.as \
                com.google.android.dialer \
                com.google.android.apps.messaging \
                com.android.stk; do
                "$ADB" shell pm disable-user --user 0 "$package_name" >/dev/null 2>&1 || true
            done
            echo "Emulator $AVD_NAME is ready"
            return
        fi
        sleep 2
    done
    echo "Emulator did not become ready within 240 seconds" >&2
    systemctl --user status "$UNIT_NAME" --no-pager >&2 || true
    exit 1
}

status() {
    systemctl --user is-active "$UNIT_NAME" || true
    "$ADB" devices -l
    if device_ready; then
        echo "boot_completed=1 package_manager=ready"
    else
        echo "device_not_ready"
    fi
}

stop_emulator() {
    systemctl --user stop "$UNIT_NAME" >/dev/null 2>&1 || true
    echo "Stopped $UNIT_NAME"
}

install_apk() {
    local apk="${1:-}"
    test -n "$apk" || { usage; exit 2; }
    test -f "$apk" || { echo "APK not found: $apk" >&2; exit 1; }
    wait_for_device
    timeout 120 "$ADB" install -r -t "$apk"
}

launch_app() {
    wait_for_device
    "$ADB" shell pm path "$PACKAGE_NAME" >/dev/null 2>&1 || {
        echo "$PACKAGE_NAME is not installed; run the install command first" >&2
        exit 1
    }
    "$ADB" shell am force-stop "$PACKAGE_NAME"
    "$ADB" shell monkey -p "$PACKAGE_NAME" -c android.intent.category.LAUNCHER 1 >/dev/null
    echo "Launched $PACKAGE_NAME"
}

take_screenshot() {
    local destination="${1:-}"
    test -n "$destination" || { usage; exit 2; }
    wait_for_device
    mkdir -p "$(dirname "$destination")"
    timeout 30 "$ADB" exec-out screencap -p > "$destination"
    echo "$destination"
}

dump_ui() {
    local destination="${1:-}"
    wait_for_device
    "$ADB" shell uiautomator dump /sdcard/kgs-notes-window.xml >/dev/null
    if test -n "$destination"; then
        mkdir -p "$(dirname "$destination")"
        "$ADB" exec-out cat /sdcard/kgs-notes-window.xml > "$destination"
        echo "$destination"
    else
        "$ADB" exec-out cat /sdcard/kgs-notes-window.xml
    fi
}

app_logs() {
    local app_pid
    app_pid="$("$ADB" shell pidof "$PACKAGE_NAME" 2>/dev/null | tr -d '\r')"
    if test -n "$app_pid"; then
        "$ADB" logcat -d --pid="$app_pid"
    else
        echo "$PACKAGE_NAME is not running" >&2
        exit 1
    fi
}

case "${1:-}" in
    create) create_avd ;;
    start) start_emulator ;;
    wait) wait_for_device ;;
    status) status ;;
    stop) stop_emulator ;;
    install) install_apk "${2:-}" ;;
    launch) launch_app ;;
    screenshot) take_screenshot "${2:-}" ;;
    ui) dump_ui "${2:-}" ;;
    logs) app_logs ;;
    *) usage; exit 2 ;;
esac
