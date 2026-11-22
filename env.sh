# Source this file (". ./env.sh" or "source env.sh") to put the project's JDK and
# Android platform-tools on PATH for the current shell. Safe to source repeatedly.

# Both live in user space under ~/Android (see "Toolchain setup" in the README). Override either
# by exporting PROSEPLOW_JAVA_HOME or PROSEPLOW_ANDROID_HOME before sourcing this file.
export JAVA_HOME="${PROSEPLOW_JAVA_HOME:-${HOME}/Android/jdk}"
export ANDROID_HOME="${PROSEPLOW_ANDROID_HOME:-${HOME}/Android/Sdk}"

case ":${PATH}:" in
    *":${ANDROID_HOME}/platform-tools:"*) ;;
    *) PATH="${ANDROID_HOME}/platform-tools:${PATH}" ;;
esac
case ":${PATH}:" in
    *":${JAVA_HOME}/bin:"*) ;;
    *) PATH="${JAVA_HOME}/bin:${PATH}" ;;
esac
export PATH
