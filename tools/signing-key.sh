#!/usr/bin/env bash
# Creates your APK signing key, once, and afterwards only shows its fingerprint.
#
# The key is a PKCS12 keystore outside the repository (mode 600), by default
# ~/.config/proseplow/proseplow-release.p12; PROSEPLOW_KEYSTORE names another file, and
# PROSEPLOW_KEY_ALIAS another alias than "proseplow". ./gradlew assembleRelease signs with it.
#
# The password, in this order:
# - PROSEPLOW_KEYSTORE_PASSWORD, when it is set: yours, chosen by you; it is not stored anywhere
#   and is not shown. The release build then needs the same variable.
# - Otherwise, on a desktop with a login keyring (tools/keyring.py): a random password that goes
#   into the keyring, where the release build finds it. It is not shown; tools/keyring.py get
#   prints it for your backup.
# - Otherwise: a random password, shown once, here. There is nowhere safe to keep it on this
#   machine, so it is yours to put in a password manager; the release build takes it from
#   PROSEPLOW_KEYSTORE_PASSWORD. A random password is used rather than asking you for one so
#   that the key is never weaker than 256 random bits, whoever builds.
#
# Back up the keystore AND its password: an installed app only accepts updates signed with the
# same key. Without it the app has to be uninstalled, which deletes its chats, before a build
# signed with a new key can be installed.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=../env.sh
source "$here/../env.sh" >/dev/null

keystore="${PROSEPLOW_KEYSTORE:-$HOME/.config/proseplow/proseplow-release.p12}"
alias_name="${PROSEPLOW_KEY_ALIAS:-proseplow}"

keyring_usable() {
    [[ -x "$here/keyring.py" ]] && timeout 30 "$here/keyring.py" available 2>/dev/null
}

show_key() {
    keytool -list -v -keystore "$keystore" -storetype PKCS12 -storepass:env PROSEPLOW_KEYSTORE_PASSWORD -alias "$alias_name" \
        | grep -E 'Owner:|Valid from|SHA256:'
}

if [[ -e "$keystore" ]]; then
    echo "The signing key already exists: $keystore"
    if [[ -z "${PROSEPLOW_KEYSTORE_PASSWORD:-}" ]] && keyring_usable; then
        PROSEPLOW_KEYSTORE_PASSWORD="$("$here/keyring.py" get 2>/dev/null || true)"
    fi
    if [[ -n "${PROSEPLOW_KEYSTORE_PASSWORD:-}" ]]; then
        export PROSEPLOW_KEYSTORE_PASSWORD
        show_key
    else
        echo "(Set PROSEPLOW_KEYSTORE_PASSWORD to see its fingerprint.)"
    fi
    exit 0
fi

umask 077
mkdir -p "$(dirname "$keystore")"

shown=0
if [[ -n "${PROSEPLOW_KEYSTORE_PASSWORD:-}" ]]; then
    where="the password you gave in PROSEPLOW_KEYSTORE_PASSWORD"
elif keyring_usable; then
    if "$here/keyring.py" get >/dev/null 2>&1; then
        echo "The login keyring already holds a Prose Plow signing password, but $keystore is missing." >&2
        echo "Restore the keystore from its backup; this script will not replace the password." >&2
        exit 1
    fi
    PROSEPLOW_KEYSTORE_PASSWORD="$(head -c 32 /dev/urandom | base64 | tr -d '\n')"
    # The password goes into the keyring first and is read back, so a key is never made that the
    # keyring cannot open again.
    printf '%s\n' "$PROSEPLOW_KEYSTORE_PASSWORD" | "$here/keyring.py" set
    [[ "$("$here/keyring.py" get)" == "$PROSEPLOW_KEYSTORE_PASSWORD" ]]
    where="a random password, kept in the login keyring (tools/keyring.py get prints it)"
else
    PROSEPLOW_KEYSTORE_PASSWORD="$(head -c 32 /dev/urandom | base64 | tr -d '\n')"
    where="the random password shown below"
    shown=1
fi
export PROSEPLOW_KEYSTORE_PASSWORD

keytool -genkeypair -keystore "$keystore" -storetype PKCS12 -storepass:env PROSEPLOW_KEYSTORE_PASSWORD \
    -alias "$alias_name" -keyalg RSA -keysize 4096 -sigalg SHA256withRSA -validity 36500 -dname "CN=Prose Plow"
chmod 600 "$keystore"

echo "Created $keystore, alias $alias_name, with $where."
show_key

if [[ "$shown" -eq 1 ]]; then
    cat <<EOF

There is no login keyring here, so the password of this key is shown now, ONCE, and is stored
nowhere on this machine:

    $PROSEPLOW_KEYSTORE_PASSWORD

Put it in your password manager now. Without it the key is useless and the app you install can
never be updated. For a release build, give it without leaving it in your shell's history:

    read -rs PROSEPLOW_KEYSTORE_PASSWORD && export PROSEPLOW_KEYSTORE_PASSWORD
EOF
fi
echo
echo "Back up $keystore and its password now, both together, somewhere other than this machine."
echo "An installed app accepts updates only when they are signed with this same key."
