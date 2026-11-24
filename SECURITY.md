# Security policy

## Reporting a vulnerability

Please report a vulnerability privately, through GitHub's private vulnerability reporting on this
repository: the "Report a vulnerability" button under the repository's Security tab. Do not open
a public issue for it.

Say what is affected, how to reproduce it, and which commit you looked at. A reply is given on a
best-effort basis; there is no fixed response time and no bug bounty.

## Supported versions

Only `main` is supported. There are no binary releases: everyone builds the app from source, so
a fix reaches users when they build a commit that contains it.

## Scope

In scope:

- The lock and the handling of keys: the passphrase, the fingerprint unlock, the key derivation,
  the Android Keystore keys and when key material is kept in memory or erased.
- The encrypted stores and their formats: the chats, drafts, settings and the saved model
  memory as they are written to the phone.
- The permissions in the merged manifest, and anything that lets the app reach the network or
  another app reach the app's data.
- The native code that reads model (GGUF) files and saved state files.

Out of scope: what the README lists under
[What this does not protect against](README.md#what-this-does-not-protect-against), such as
someone who knows the passphrase, whoever holds the phone while the app is unlocked, or a
compromised phone.

## What has been reviewed

The code has had only the project's own review. There has been no independent audit.
