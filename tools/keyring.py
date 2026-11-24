#!/usr/bin/python3
"""Keeps the password of the APK signing keystore in the login keyring.

Uses the desktop's Secret Service (GNOME Keyring on Ubuntu) through libsecret, which the system
Python of a desktop already has (python3-gi and gir1.2-secret-1). The keyring is unlocked by
logging in, so nothing is typed and nothing is written to disk in the clear. The release build
(SignApk in app/build.gradle.kts) asks it for the password when PROSEPLOW_KEYSTORE_PASSWORD is not
set, and tools/signing-key.sh stores the password of a new key in it when it can.

  keyring.py available   exit 0 if a keyring can be used here, 1 if not
  keyring.py get         prints the password (exit 1 if there is none)
  keyring.py set         stores the password read from standard input
"""
import sys

try:
    import gi

    gi.require_version("Secret", "1")
    from gi.repository import Secret  # noqa: E402
except (ImportError, ValueError):
    Secret = None

ATTRIBUTES = {"application": "proseplow", "purpose": "apk-signing"}


def schema():
    return Secret.Schema.new(
        "us.theworkmans.proseplow.Signing",
        Secret.SchemaFlags.NONE,
        {name: Secret.SchemaAttributeType.STRING for name in ATTRIBUTES},
    )


def main() -> int:
    command = sys.argv[1] if len(sys.argv) == 2 else ""
    if command not in ("available", "get", "set"):
        print(__doc__, file=sys.stderr)
        return 2
    if Secret is None:
        print("keyring.py: no libsecret bindings for this Python (python3-gi, gir1.2-secret-1)", file=sys.stderr)
        return 1
    try:
        if command == "available":
            Secret.Service.get_sync(Secret.ServiceFlags.OPEN_SESSION, None)
            return 0
        if command == "get":
            password = Secret.password_lookup_sync(schema(), ATTRIBUTES, None)
            if not password:
                print("keyring.py: no Prose Plow signing password in the login keyring", file=sys.stderr)
                return 1
            print(password)
            return 0
        password = sys.stdin.readline().rstrip("\n")
        if not password:
            print("keyring.py: empty password", file=sys.stderr)
            return 1
        Secret.password_store_sync(
            schema(), ATTRIBUTES, Secret.COLLECTION_DEFAULT, "Prose Plow APK signing keystore", password, None
        )
        return 0
    except Exception as e:  # noqa: BLE001 - any failure of the service means "no keyring here"
        print(f"keyring.py: the login keyring cannot be used: {e}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
