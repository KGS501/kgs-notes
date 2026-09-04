#!/usr/bin/env bash
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FAKE_SDK="$PROJECT_ROOT/tools/test-fixtures/android-sdk"

actual="$(
    ANDROID_SDK_ROOT="$FAKE_SDK" \
        "$PROJECT_ROOT/tools/android-emulator.sh" serial
)"

if [[ "$actual" != "emulator-5556" ]]; then
    printf 'Expected Notes AVD serial emulator-5556, got: %s\n' "$actual" >&2
    exit 1
fi

adb_output="$(
    ANDROID_SERIAL=emulator-5554 \
        ANDROID_SDK_ROOT="$FAKE_SDK" \
        "$PROJECT_ROOT/tools/android-emulator.sh" adb get-state
)"

if [[ "$adb_output" != "device" ]]; then
    printf 'Expected targeted adb output, got: %s\n' "$adb_output" >&2
    exit 1
fi

set +e
launch_output="$(
    timeout 2 env \
        ANDROID_SERIAL=emulator-5554 \
        ANDROID_SDK_ROOT="$FAKE_SDK" \
        "$PROJECT_ROOT/tools/android-emulator.sh" launch 2>&1
)"
launch_status=$?
set -e

if (( launch_status != 0 )) || [[ "$launch_output" != *"Launched com.kgs501.kgsnotes"* ]]; then
    printf 'Expected a targeted launch (status=%s), got: %s\n' "$launch_status" "$launch_output" >&2
    exit 1
fi

set +e
bounded_wait_output="$(
    timeout 4 env \
        ANDROID_SDK_ROOT="$FAKE_SDK" \
        FAKE_ADB_HANG_DISABLE=1 \
        KGS_ANDROID_SETUP_TIMEOUT_SECONDS=1 \
        "$PROJECT_ROOT/tools/android-emulator.sh" wait 2>&1
)"
bounded_wait_status=$?
set -e

if (( bounded_wait_status != 0 )) || [[ "$bounded_wait_output" != *"Emulator kgs_notes_phone_api36 is ready as emulator-5556"* ]]; then
    printf 'Expected bounded device setup (status=%s), got: %s\n' "$bounded_wait_status" "$bounded_wait_output" >&2
    exit 1
fi

connected_test_output="$(
    ANDROID_SERIAL=emulator-5554 \
        ANDROID_SDK_ROOT="$FAKE_SDK" \
        KGS_NOTES_GRADLEW="$PROJECT_ROOT/tools/test-fixtures/gradlew" \
        "$PROJECT_ROOT/tools/android-emulator.sh" connected-test --example-argument
)"

if [[ "$connected_test_output" != *"targeted-gradle:emulator-5556::app:connectedDebugAndroidTest --example-argument"* ]]; then
    printf 'Expected Gradle to target Notes, got: %s\n' "$connected_test_output" >&2
    exit 1
fi

printf '%s\n' 'android-emulator wrapper tests passed'
