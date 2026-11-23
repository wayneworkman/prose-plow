#!/usr/bin/env bash
# Signs an unsigned release APK with the key made by tools/signing-key.sh and verifies the result.
#
#   tools/sign-release.sh [unsigned.apk] [signed.apk]
#
# Defaults: app/build/outputs/apk/release/app-release-unsigned.apk in, and
# prose-plow-<versionName>.apk in the repository's root out (APKs are git-ignored).
#
# The APK gets an APK Signature Scheme v3 signature and nothing older: Android 9, the oldest
# version the app runs on, checks v3, so apksigner writes no v2 signature, and the v1 (JAR)
# signature is switched off explicitly because it is what stops tools that copy signatures from
# handling an APK of more than 2 GiB. Alignment is kept as the build made it, because the models
# inside are mapped in place and must stay on their page boundaries.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root="$(cd "$here/.." && pwd)"
# shellcheck source=../env.sh
source "$root/env.sh" >/dev/null

keystore="${PROSEPLOW_KEYSTORE:-$HOME/.config/proseplow/proseplow-release.p12}"
build_tools="${ANDROID_HOME:?env.sh must set ANDROID_HOME}/build-tools/37.0.0"
version="$(sed -n 's/^ *versionName = "\(.*\)"$/\1/p' "$root/app/build.gradle.kts" | head -n 1)"
unsigned="${1:-$root/app/build/outputs/apk/release/app-release-unsigned.apk}"
signed="${2:-$root/prose-plow-${version:?no versionName in app/build.gradle.kts}.apk}"

[[ -f "$unsigned" ]] || { echo "No unsigned APK at $unsigned: build one with ./gradlew :app:assembleRelease" >&2; exit 1; }
[[ -f "$keystore" ]] || { echo "No keystore at $keystore: run tools/signing-key.sh once" >&2; exit 1; }

PROSEPLOW_KEYSTORE_PASSWORD="$("$here/keyring.py" get)"
export PROSEPLOW_KEYSTORE_PASSWORD

"$build_tools/apksigner" sign --ks "$keystore" --ks-type PKCS12 --ks-key-alias proseplow \
    --ks-pass env:PROSEPLOW_KEYSTORE_PASSWORD \
    --v1-signing-enabled false \
    --alignment-preserved true \
    --out "$signed" "$unsigned"
rm -f "$signed.idsig"

"$build_tools/apksigner" verify --verbose --print-certs "$signed" \
    | grep -E '^Verifies|Verified using v[123] scheme|certificate DN|certificate SHA-256'
"$build_tools/zipalign" -c -P 16 4 "$signed"
echo "Signed: $signed"
