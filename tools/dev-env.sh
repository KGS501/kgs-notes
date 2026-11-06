#!/usr/bin/env bash
# Portable development environment resolver for KGS Notes.
#
# Source this file from tools scripts (or an interactive shell) to export the
# Android SDK, AVD, and JDK locations that Gradle and the emulator helper use.
# Nothing here is machine specific; every location can be overridden:
#
#   KGS_NOTES_ANDROID_SDK_ROOT  Android SDK (else ANDROID_SDK_ROOT, ANDROID_HOME,
#                               sdk.dir in local.properties, ./.android-sdk,
#                               ~/Android/Sdk, ~/Library/Android/sdk)
#   KGS_NOTES_ANDROID_USER_HOME Android user home (else ANDROID_USER_HOME,
#                               ./.device-debug/android-home, ~/.android)
#   KGS_NOTES_ANDROID_AVD_HOME  AVD directory (else ANDROID_AVD_HOME,
#                               ./.device-debug/avd, $ANDROID_USER_HOME/avd)
#   KGS_NOTES_JAVA_HOME         JDK used by Gradle (else ./.jdk/17, JAVA_HOME;
#                               the build is verified with JDK 17)
#   KGS_NOTES_GRADLE_USER_HOME  Gradle user home (else GRADLE_USER_HOME,
#                               ./.device-debug/gradle, Gradle's default)
#
# The ./.android-sdk, ./.jdk and ./.device-debug locations are optional,
# git-ignored, project-local installs; they are used only when they exist.
#
# Run it directly (`tools/dev-env.sh`) to print the resolved environment and a
# readiness report. Missing tools are reported by the commands that need them,
# so fixture tests run without an installed SDK.

_kgs_dev_env_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

_kgs_local_properties_sdk() {
    local properties="$_kgs_dev_env_root/local.properties" line value
    test -f "$properties" || return 1
    while IFS= read -r line || test -n "$line"; do
        line="${line%$'\r'}"
        [[ "$line" =~ ^[[:space:]]*sdk\.dir[[:space:]]*[=:][[:space:]]*(.*)$ ]] || continue
        value="${BASH_REMATCH[1]}"
        # Undo the escaping that Android Studio writes for ':' and '\'.
        value="${value//\\:/:}"
        value="${value//\\\\/\\}"
        printf '%s\n' "$value"
        return 0
    done < "$properties"
    return 1
}

_kgs_resolve_sdk() {
    local candidate
    for candidate in \
        "${KGS_NOTES_ANDROID_SDK_ROOT:-}" \
        "${ANDROID_SDK_ROOT:-}" \
        "${ANDROID_HOME:-}" \
        "$(_kgs_local_properties_sdk 2>/dev/null || true)" \
        "$_kgs_dev_env_root/.android-sdk" \
        "${HOME:-}/Android/Sdk" \
        "${HOME:-}/Library/Android/sdk"; do
        if test -n "$candidate" && test -d "$candidate"; then
            printf '%s\n' "$candidate"
            return 0
        fi
    done
    # Keep an explicit but missing override visible in error messages.
    printf '%s\n' "${KGS_NOTES_ANDROID_SDK_ROOT:-${ANDROID_SDK_ROOT:-${ANDROID_HOME:-${HOME:-}/Android/Sdk}}}"
    return 1
}

if ANDROID_SDK_ROOT="$(_kgs_resolve_sdk)"; then
    KGS_NOTES_ANDROID_SDK_FOUND=1
else
    KGS_NOTES_ANDROID_SDK_FOUND=0
fi
ANDROID_HOME="$ANDROID_SDK_ROOT"

_kgs_existing_dir() {
    test -d "$1" && printf '%s\n' "$1"
}

ANDROID_USER_HOME="${KGS_NOTES_ANDROID_USER_HOME:-${ANDROID_USER_HOME:-$(_kgs_existing_dir "$_kgs_dev_env_root/.device-debug/android-home" || printf '%s' "${HOME:-}/.android")}}"
ANDROID_AVD_HOME="${KGS_NOTES_ANDROID_AVD_HOME:-${ANDROID_AVD_HOME:-$(_kgs_existing_dir "$_kgs_dev_env_root/.device-debug/avd" || printf '%s' "$ANDROID_USER_HOME/avd")}}"
export ANDROID_SDK_ROOT ANDROID_HOME ANDROID_USER_HOME ANDROID_AVD_HOME KGS_NOTES_ANDROID_SDK_FOUND

if test -n "${KGS_NOTES_JAVA_HOME:-}"; then
    JAVA_HOME="$KGS_NOTES_JAVA_HOME"
    export JAVA_HOME
elif test -x "$_kgs_dev_env_root/.jdk/17/bin/java"; then
    JAVA_HOME="$_kgs_dev_env_root/.jdk/17"
    export JAVA_HOME
fi
if test -n "${KGS_NOTES_GRADLE_USER_HOME:-}"; then
    GRADLE_USER_HOME="$KGS_NOTES_GRADLE_USER_HOME"
    export GRADLE_USER_HOME
elif test -z "${GRADLE_USER_HOME:-}" && test -d "$_kgs_dev_env_root/.device-debug/gradle"; then
    GRADLE_USER_HOME="$_kgs_dev_env_root/.device-debug/gradle"
    export GRADLE_USER_HOME
fi
if test -n "${JAVA_HOME:-}" && test -d "$JAVA_HOME/bin"; then
    case ":$PATH:" in
        *":$JAVA_HOME/bin:"*) ;;
        *) PATH="$JAVA_HOME/bin:$PATH"; export PATH ;;
    esac
fi

# Fails with an actionable message when a real SDK is required.
kgs_require_android_sdk() {
    if [[ "$KGS_NOTES_ANDROID_SDK_FOUND" != 1 ]]; then
        cat >&2 <<EOF
Android SDK not found (looked at: ${ANDROID_SDK_ROOT:-<unset>}).
Install the SDK or point KGS_NOTES_ANDROID_SDK_ROOT (or ANDROID_SDK_ROOT /
ANDROID_HOME, or sdk.dir in local.properties) at it.
EOF
        return 1
    fi
}

# Prints the feature version of the resolved JDK, e.g. 17.
kgs_java_feature_version() {
    local java="java" version
    test -n "${JAVA_HOME:-}" && java="$JAVA_HOME/bin/java"
    version="$("$java" -XshowSettings:properties -version 2>&1 | awk -F'= ' '/java.specification.version/ { print $2; exit }')"
    test -n "$version" || return 1
    printf '%s\n' "${version#1.}"
}

_kgs_dev_env_report() {
    local status=0 java_version tool
    printf 'PROJECT_ROOT=%s\n' "$_kgs_dev_env_root"
    printf 'ANDROID_SDK_ROOT=%s\n' "$ANDROID_SDK_ROOT"
    printf 'ANDROID_USER_HOME=%s\n' "$ANDROID_USER_HOME"
    printf 'ANDROID_AVD_HOME=%s\n' "$ANDROID_AVD_HOME"
    printf 'JAVA_HOME=%s\n' "${JAVA_HOME:-<unset; java from PATH>}"
    printf 'GRADLE_USER_HOME=%s\n' "${GRADLE_USER_HOME:-<Gradle default>}"
    kgs_require_android_sdk || status=1
    if [[ "$KGS_NOTES_ANDROID_SDK_FOUND" == 1 ]]; then
        for tool in platform-tools/adb emulator/emulator cmdline-tools/latest/bin/avdmanager; do
            if test -x "$ANDROID_SDK_ROOT/$tool"; then
                printf 'ok      %s\n' "$tool"
            else
                printf 'missing %s (needed only for emulator commands)\n' "$tool"
            fi
        done
    fi
    if java_version="$(kgs_java_feature_version 2>/dev/null)"; then
        if [[ "$java_version" == 17 ]]; then
            printf 'ok      JDK %s\n' "$java_version"
        else
            printf 'warning JDK %s found; the Android build is verified with JDK 17 (set KGS_NOTES_JAVA_HOME)\n' "$java_version" >&2
        fi
    else
        printf 'missing JDK; set KGS_NOTES_JAVA_HOME or JAVA_HOME to a JDK 17\n' >&2
        status=1
    fi
    return "$status"
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
    _kgs_dev_env_report
    exit $?
fi
