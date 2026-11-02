#!/usr/bin/env bash
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FAKE_SDK="$PROJECT_ROOT/tools/test-fixtures/android-sdk"
# Generous enough for loaded CI hosts, but well below the fake adb's 30 s hang.
COMMAND_BOUND_SECONDS="${KGS_TEST_COMMAND_BOUND_SECONDS:-15}"
SCRATCH="$(mktemp -d)"
trap 'rm -rf "$SCRATCH"' EXIT

fail() {
    printf '%s\n' "$*" >&2
    exit 1
}

# Resolves an environment variable through dev-env.sh in a scrubbed shell, so the
# result does not depend on the developer's own SDK, JDK, or home directory.
resolved() {
    local variable="$1"
    shift
    env -i PATH=/usr/bin:/bin HOME="$SCRATCH/home" "$@" \
        bash -c 'source "$1/tools/dev-env.sh" && printf "%s\n" "${!2}"' _ "$PROJECT_ROOT" "$variable"
}

mkdir -p "$SCRATCH/home" "$SCRATCH/sdk-a" "$SCRATCH/sdk-b"
[[ "$(resolved ANDROID_SDK_ROOT KGS_NOTES_ANDROID_SDK_ROOT="$SCRATCH/sdk-a" ANDROID_HOME="$SCRATCH/sdk-b")" == "$SCRATCH/sdk-a" ]] ||
    fail 'KGS_NOTES_ANDROID_SDK_ROOT must take precedence over ANDROID_HOME'
[[ "$(resolved ANDROID_SDK_ROOT ANDROID_SDK_ROOT="$SCRATCH/sdk-a" ANDROID_HOME="$SCRATCH/sdk-b")" == "$SCRATCH/sdk-a" ]] ||
    fail 'ANDROID_SDK_ROOT must take precedence over ANDROID_HOME'
[[ "$(resolved ANDROID_HOME ANDROID_HOME="$SCRATCH/sdk-b")" == "$SCRATCH/sdk-b" ]] ||
    fail 'ANDROID_HOME must be honoured and exported'
mkdir -p "$SCRATCH/home/Android/Sdk"
[[ "$(resolved ANDROID_SDK_ROOT)" == "$SCRATCH/home/Android/Sdk" ]] ||
    fail 'The conventional ~/Android/Sdk location must be found without configuration'
[[ "$(resolved ANDROID_AVD_HOME KGS_NOTES_ANDROID_USER_HOME="$SCRATCH/user")" == "$SCRATCH/user/avd" ]] ||
    fail 'AVDs must default below the configured Android user home'
[[ "$(resolved JAVA_HOME KGS_NOTES_JAVA_HOME="$SCRATCH/jdk")" == "$SCRATCH/jdk" ]] ||
    fail 'KGS_NOTES_JAVA_HOME must select the JDK'
rmdir "$SCRATCH/home/Android/Sdk"

# A clean checkout without any SDK needs an actionable error, not a missing-file crash.
set +e
missing_sdk_output="$(
    env -i PATH=/usr/bin:/bin HOME="$SCRATCH/home" \
        timeout "$COMMAND_BOUND_SECONDS" "$PROJECT_ROOT/tools/android-emulator.sh" serial 2>&1
)"
missing_sdk_status=$?
set -e
if (( missing_sdk_status != 1 )) || [[ "$missing_sdk_output" != *"Android SDK not found"*"KGS_NOTES_ANDROID_SDK_ROOT"* ]]; then
    fail "Expected an actionable missing-SDK error (status=$missing_sdk_status), got: $missing_sdk_output"
fi

actual="$(
    KGS_NOTES_ANDROID_SDK_ROOT="$FAKE_SDK" \
        "$PROJECT_ROOT/tools/android-emulator.sh" serial
)"

if [[ "$actual" != "emulator-5556" ]]; then
    printf 'Expected Notes AVD serial emulator-5556, got: %s\n' "$actual" >&2
    exit 1
fi

adb_output="$(
    ANDROID_SERIAL=emulator-5554 \
        KGS_NOTES_ANDROID_SDK_ROOT="$FAKE_SDK" \
        "$PROJECT_ROOT/tools/android-emulator.sh" adb get-state
)"

if [[ "$adb_output" != "device" ]]; then
    printf 'Expected targeted adb output, got: %s\n' "$adb_output" >&2
    exit 1
fi

set +e
launch_output="$(
    timeout "$COMMAND_BOUND_SECONDS" env \
        ANDROID_SERIAL=emulator-5554 \
        KGS_NOTES_ANDROID_SDK_ROOT="$FAKE_SDK" \
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
    timeout "$COMMAND_BOUND_SECONDS" env \
        KGS_NOTES_ANDROID_SDK_ROOT="$FAKE_SDK" \
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
        KGS_NOTES_ANDROID_SDK_ROOT="$FAKE_SDK" \
        KGS_NOTES_GRADLEW="$PROJECT_ROOT/tools/test-fixtures/gradlew" \
        "$PROJECT_ROOT/tools/android-emulator.sh" connected-test --example-argument
)"

if [[ "$connected_test_output" != *"targeted-gradle:emulator-5556::app:connectedDebugAndroidTest --example-argument"* ]]; then
    printf 'Expected Gradle to target Notes, got: %s\n' "$connected_test_output" >&2
    exit 1
fi

printf '%s\n' 'android-emulator wrapper tests passed'
