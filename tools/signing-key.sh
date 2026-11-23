#!/usr/bin/env bash
# Creates the APK signing key, once, and afterwards only shows its fingerprint.
#
# The key is a PKCS12 keystore outside the repository (mode 600), and its random password is kept
# in the login keyring (tools/keyring.py), so neither is ever typed, committed or left readable.
# tools/sign-release.sh finds both by itself.
#
# Keep a backup of the keystore AND its password (tools/keyring.py get): an installed app only
# accepts updates signed with the same key. Without it the app has to be uninstalled, which
# deletes its chats, before a build signed with a new key can be installed.
#
# PROSEPLOW_KEYSTORE names another place for the keystore.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=../env.sh
source "$here/../env.sh" >/dev/null

keystore="${PROSEPLOW_KEYSTORE:-$HOME/.config/proseplow/proseplow-release.p12}"
alias_name=proseplow

if [[ -e "$keystore" ]]; then
    echo "The signing key already exists: $keystore"
    PROSEPLOW_KEYSTORE_PASSWORD="$("$here/keyring.py" get)" \
        keytool -list -v -keystore "$keystore" -storetype PKCS12 -storepass:env PROSEPLOW_KEYSTORE_PASSWORD -alias "$alias_name" \
        | grep -E 'Owner:|Valid from|SHA256:'
    exit 0
fi

if "$here/keyring.py" get >/dev/null 2>&1; then
    echo "The login keyring already holds a Prose Plow signing password, but $keystore is missing." >&2
    echo "Restore the keystore from its backup; this script will not replace the password." >&2
    exit 1
fi

umask 077
mkdir -p "$(dirname "$keystore")"
PROSEPLOW_KEYSTORE_PASSWORD="$(head -c 32 /dev/urandom | base64 | tr -d '\n')"
export PROSEPLOW_KEYSTORE_PASSWORD

# The password goes into the keyring first and is read back, so a key is never made that the
# keyring cannot open again.
printf '%s\n' "$PROSEPLOW_KEYSTORE_PASSWORD" | "$here/keyring.py" set
[[ "$("$here/keyring.py" get)" == "$PROSEPLOW_KEYSTORE_PASSWORD" ]]

keytool -genkeypair -keystore "$keystore" -storetype PKCS12 -storepass:env PROSEPLOW_KEYSTORE_PASSWORD \
    -alias "$alias_name" -keyalg RSA -keysize 4096 -sigalg SHA256withRSA -validity 36500 -dname "CN=Prose Plow"
chmod 600 "$keystore"

echo "Created $keystore (password in the login keyring)."
keytool -list -v -keystore "$keystore" -storetype PKCS12 -storepass:env PROSEPLOW_KEYSTORE_PASSWORD -alias "$alias_name" \
    | grep -E 'Owner:|Valid from|SHA256:'
echo "Back up the keystore and its password (tools/keyring.py get) somewhere safe."
