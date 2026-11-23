#!/usr/bin/python3
"""Keeps the password of the APK signing keystore in the login keyring.

Uses the desktop's Secret Service (GNOME Keyring on Ubuntu) through libsecret, which the system
Python already has (python3-gi and gir1.2-secret-1). The keyring is unlocked by logging in, so
nothing is typed and nothing is written to disk in the clear.

  keyring.py get      prints the password (exit 1 if there is none)
  keyring.py set      stores the password read from standard input
"""
import sys

import gi

gi.require_version("Secret", "1")
from gi.repository import Secret  # noqa: E402

ATTRIBUTES = {"application": "proseplow", "purpose": "apk-signing"}
SCHEMA = Secret.Schema.new(
    "us.theworkmans.proseplow.Signing",
    Secret.SchemaFlags.NONE,
    {name: Secret.SchemaAttributeType.STRING for name in ATTRIBUTES},
)


def main() -> int:
    command = sys.argv[1] if len(sys.argv) == 2 else ""
    if command == "get":
        password = Secret.password_lookup_sync(SCHEMA, ATTRIBUTES, None)
        if not password:
            print("keyring.py: no Prose Plow signing password in the login keyring", file=sys.stderr)
            return 1
        print(password)
        return 0
    if command == "set":
        password = sys.stdin.readline().rstrip("\n")
        if not password:
            print("keyring.py: empty password", file=sys.stderr)
            return 1
        Secret.password_store_sync(
            SCHEMA, ATTRIBUTES, Secret.COLLECTION_DEFAULT, "Prose Plow APK signing keystore", password, None
        )
        return 0
    print(__doc__, file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main())
