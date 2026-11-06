#!/usr/bin/env bash
# Named Android instrumentation suites shared by CI and local emulator runs.
#
#   tools/instrumentation-suites.sh <suite>          print the Gradle argument
#   tools/instrumentation-suites.sh --list <suite>   print one class per line
#   tools/instrumentation-suites.sh --check          verify every listed class exists
#
# Example:
#   ANDROID_SERIAL=emulator-5556 ./gradlew :app:connectedDebugAndroidTest \
#       $(tools/instrumentation-suites.sh security)
#
# Suites:
#   full      every committed instrumentation test (no filter)
#   core      editing, Rich/Source editor, external-file, and security regressions
#   minimum   regressions that do not need Rich Mode, for the minimum supported
#             API (its stock WebView lacks WebMessageListener, so Rich Mode
#             falls back to Source Mode there)
#   adaptive  locale and tablet/foldable layout coverage
#   security  platform security boundaries only
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TEST_ROOT="$PROJECT_ROOT/app/src/androidTest/kotlin"
PACKAGE="com.kgs.notes"

SECURITY_CLASSES=(
    CredentialStoreSecurityTest
    EditorWebViewSecurityTest
    NextcloudAccountStoreDeviceTest
    PlatformSecurityBoundaryTest
)
CORE_CLASSES=(
    EditorImePlacementTest
    EditorModePositionTest
    ExternalFileFlowTest
    ExternalFileScreenTest
    InteractionRegressionTest
    "${SECURITY_CLASSES[@]}"
)
ADAPTIVE_CLASSES=(
    BottomSheetRegressionTest
    LibraryImprovementsTest
)
MINIMUM_CLASSES=(
    ExternalFileScreenTest
    "${ADAPTIVE_CLASSES[@]}"
    "${SECURITY_CLASSES[@]}"
)

classes_for() {
    case "$1" in
        full) ;;
        core) printf '%s\n' "${CORE_CLASSES[@]}" ;;
        minimum) printf '%s\n' "${MINIMUM_CLASSES[@]}" ;;
        adaptive) printf '%s\n' "${ADAPTIVE_CLASSES[@]}" ;;
        security) printf '%s\n' "${SECURITY_CLASSES[@]}" ;;
        *) echo "Unknown instrumentation suite: $1 (expected full|core|minimum|adaptive|security)" >&2; return 2 ;;
    esac
}

check_classes() {
    local suite class status=0
    for suite in core minimum adaptive security; do
        while read -r class; do
            if ! test -f "$TEST_ROOT/${PACKAGE//./\/}/$class.kt"; then
                echo "Suite $suite lists missing test class $class" >&2
                status=1
            fi
        done < <(classes_for "$suite")
    done
    return "$status"
}

case "${1:-}" in
    --check) check_classes ;;
    --list) classes_for "${2:-}" ;;
    "") echo "Usage: tools/instrumentation-suites.sh [--list] full|core|minimum|adaptive|security | --check" >&2; exit 2 ;;
    *)
        classes="$(classes_for "$1" | sed "s/^/$PACKAGE./" | paste -sd, -)"
        if test -n "$classes"; then
            printf -- '-Pandroid.testInstrumentationRunnerArguments.class=%s\n' "$classes"
        fi
        ;;
esac
