#!/usr/bin/env bash
# Signs an unsigned release APK with your key (tools/signing-key.sh) and verifies the result.
#
#   tools/sign-release.sh [unsigned.apk] [signed.apk]
#
# ./gradlew assembleRelease signs by itself, and this script is not needed for that. It is for an
# APK that was built unsigned: with ./gradlew assembleRelease -Pproseplow.sign=false, on another
# machine, or by a build server, signed here afterwards with the same key and in the same way.
#
# Defaults: app/build/outputs/apk/release/app-release-unsigned.apk in (what the unsigned build
# writes), and prose-plow-<versionName>.apk in the repository's root out (APKs are git-ignored).
# The key: PROSEPLOW_KEYSTORE, by default ~/.config/proseplow/proseplow-release.p12, alias
# PROSEPLOW_KEY_ALIAS or "proseplow". The password: PROSEPLOW_KEYSTORE_PASSWORD, or else the login
# keyring (tools/keyring.py), the same order as the release build; it is never printed.
#
# The APK gets an APK Signature Scheme v3 signature and nothing older: Android 9, the oldest
# version the app runs on, checks v3, so apksigner writes no v2 signature, and the v1 (JAR)
# signature is switched off explicitly because it is what stops tools that copy signatures from
# handling an APK of more than 2 GiB. Alignment is kept as the build made it, because the models
# inside are mapped in place and must stay on their page boundaries. These are the settings of
# SignApk in app/build.gradle.kts; change both together.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root="$(cd "$here/.." && pwd)"
# shellcheck source=../env.sh
source "$root/env.sh" >/dev/null

keystore="${PROSEPLOW_KEYSTORE:-$HOME/.config/proseplow/proseplow-release.p12}"
alias_name="${PROSEPLOW_KEY_ALIAS:-proseplow}"
build_tools="${ANDROID_HOME:?env.sh must set ANDROID_HOME}/build-tools/37.0.0"
version="$(sed -n 's/^ *versionName = "\(.*\)"$/\1/p' "$root/app/build.gradle.kts" | head -n 1)"
unsigned="${1:-$root/app/build/outputs/apk/release/app-release-unsigned.apk}"
signed="${2:-$root/prose-plow-${version:?no versionName in app/build.gradle.kts}.apk}"

[[ -f "$unsigned" ]] || { echo "No unsigned APK at $unsigned: build one with ./gradlew assembleRelease -Pproseplow.sign=false" >&2; exit 1; }
[[ -f "$keystore" ]] || { echo "No keystore at $keystore: run tools/signing-key.sh once" >&2; exit 1; }

if [[ -z "${PROSEPLOW_KEYSTORE_PASSWORD:-}" ]]; then
    PROSEPLOW_KEYSTORE_PASSWORD="$("$here/keyring.py" get 2>/dev/null || true)"
fi
if [[ -z "$PROSEPLOW_KEYSTORE_PASSWORD" ]]; then
    echo "No password for $keystore: set PROSEPLOW_KEYSTORE_PASSWORD (read -rs PROSEPLOW_KEYSTORE_PASSWORD && export PROSEPLOW_KEYSTORE_PASSWORD)," >&2
    echo "or keep it in the login keyring (tools/signing-key.sh does that where there is one)." >&2
    exit 1
fi
export PROSEPLOW_KEYSTORE_PASSWORD

"$build_tools/apksigner" sign --ks "$keystore" --ks-type PKCS12 --ks-key-alias "$alias_name" \
    --ks-pass env:PROSEPLOW_KEYSTORE_PASSWORD \
    --v1-signing-enabled false \
    --v4-signing-enabled false \
    --alignment-preserved true \
    --out "$signed" "$unsigned"

"$build_tools/apksigner" verify --verbose --print-certs "$signed" \
    | grep -E '^Verifies|Verified using v[1234] scheme|certificate DN|certificate SHA-256'
"$build_tools/zipalign" -c -P 16 4 "$signed"
echo "Signed: $signed"
