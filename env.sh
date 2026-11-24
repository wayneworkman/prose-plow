# shellcheck shell=bash
# Source this file (". ./env.sh" or "source env.sh") to put the project's JDK and
# Android platform-tools on PATH for the current shell. Safe to source repeatedly.
#
# Both live in user space under ~/Android (see "Toolchain setup" in the README). Override either
# by exporting PROSEPLOW_JAVA_HOME or PROSEPLOW_ANDROID_HOME before sourcing this file. Where
# neither the override nor the directory under ~/Android exists, a JAVA_HOME or ANDROID_HOME the
# environment already has is kept; where that is missing too, a warning says so.

_proseplow_pick_home() {
    # $1: the variable to set; $2: the override (may be empty); $3: the default directory.
    if [ -n "$2" ]; then
        [ -d "$2" ] || echo "env.sh: $1 is set to $2, which does not exist" >&2
        export "$1=$2"
    elif [ -d "$3" ]; then
        export "$1=$3"
    elif eval "[ -n \"\${$1:-}\" ] && [ -d \"\${$1}\" ]"; then
        export "${1?}"
    else
        echo "env.sh: no $1: neither $3 nor the $1 of the environment exists (see \"Toolchain setup\" in README.md)" >&2
    fi
}
_proseplow_pick_home JAVA_HOME "${PROSEPLOW_JAVA_HOME:-}" "${HOME}/Android/jdk"
_proseplow_pick_home ANDROID_HOME "${PROSEPLOW_ANDROID_HOME:-}" "${HOME}/Android/Sdk"
unset -f _proseplow_pick_home

if [ -n "${ANDROID_HOME:-}" ]; then
    case ":${PATH}:" in
        *":${ANDROID_HOME}/platform-tools:"*) ;;
        *) PATH="${ANDROID_HOME}/platform-tools:${PATH}" ;;
    esac
fi
if [ -n "${JAVA_HOME:-}" ]; then
    case ":${PATH}:" in
        *":${JAVA_HOME}/bin:"*) ;;
        *) PATH="${JAVA_HOME}/bin:${PATH}" ;;
    esac
fi
export PATH
