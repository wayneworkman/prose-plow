# Prose Plow

Prose Plow is an Android app that runs small language models entirely on the phone. The models
are packaged inside the APK and run on the phone's processor through
[llama.cpp](https://github.com/ggml-org/llama.cpp), which this project builds from source with
three small patches. Nothing is downloaded while the app runs, and the app declares no network
permission at all. Every chat is stored encrypted behind a lock that cannot be switched off: a
passphrase, or a fingerprint that the passphrase has set up, and never a PIN.

There are no binary releases. You build the app yourself from this repository, and this file
says how: from a bare Ubuntu 24.04 machine to an APK on a phone, with every tool, the version of
it that worked, and every command. The instructions for Windows and macOS are untested best
effort until someone offers corrections by pull request.

| | |
| --- | --- |
| Version | 0.1.0 |
| Runs on | Android 9 (API 28) or later, 64-bit ARM (`arm64-v8a`) only |
| Tested on | One phone: a Samsung Galaxy XCover7 Pro with Android 17 |
| Built on | Ubuntu 24.04 LTS, x86_64, with command-line tools only (no part of Android Studio) |
| Licence | Apache-2.0; see [Licence and third-party notices](#licence-and-third-party-notices) |

## Contents

- [What the app does](#what-the-app-does)
- [The models](#the-models)
- [Security](#security)
- [Build from source on Ubuntu 24.04](#build-from-source-on-ubuntu-2404)
- [Connecting a phone](#connecting-a-phone)
- [Windows (untested)](#windows-untested)
- [macOS (untested)](#macos-untested)
- [Tests](#tests)
- [Adding and removing a model](#adding-and-removing-a-model)
- [Project layout](#project-layout)
- [Contributing](#contributing)
- [Licence and third-party notices](#licence-and-third-party-notices)

## What the app does

Everything in this list is in the code today.

Chats:

- **Several chats.** They are listed in a drawer that slides over the conversation. A chat is
  named after its first message, is stored from its first message or draft on, and can be
  deleted for good.
- **A model for each chat, switchable in the middle of it.** The top bar shows the chat's model;
  tapping it opens the list of the models this build carries. Every answer is labelled with the
  model that wrote it. One model is in memory at a time, and it is loaded when it is needed.
- **Stop, answer again, continue, edit.** A reply can be stopped while it is being written. An
  answer can be asked for again, which replaces it and everything after it. A reply that was
  stopped or reached its length limit can be continued by the model that wrote it. One of your
  own earlier messages can be edited, which removes everything after it and has the model answer
  the new text. A request that failed can be tried again.
- **A system prompt for each model**, in Settings, for the models that were trained with one
  (Qwen3.5 and Boris-2; Peacebell was trained without one and has none).
- **A temperature for each model**, in Settings, from 0 to 2. Left empty, a model uses its own
  reference value; 0 always picks the most likely word. It applies from the next answer and
  switches with the model.
- **Answers as Markdown**, with selectable text, a copy button on every message and on every
  block of code. Images are never loaded: the app shows their alternative text. A link is shown
  in full and is handed to another app only after you have confirmed it.
- **Long conversations.** When a conversation no longer fits the model's context, the oldest
  messages are left out of the latest answer and the app says how many.
- **Drafts.** What you have typed and not sent is kept with its chat.

Privacy:

- **Encrypted storage.** Chats, drafts, the list of chats and the settings are only ever written
  to the phone encrypted (AES-256-GCM).
- **A mandatory lock.** The first start asks for a passphrase of at least 10 characters that is
  not only digits, so a PIN is refused. There is no way to use the app without one and no
  setting that turns the lock off.
- **Passphrase or fingerprint, never a PIN.** Fingerprint unlock is optional, uses strong
  (Class 3) biometrics only, and cannot fall back to the phone's PIN, pattern or password.
- **Locks by itself.** The app is locked at every start and after it has been in the background
  for the chosen time: immediately, 1 minute (the default), 5 or 15 minutes. Settings has "Lock
  now".
- **No recovery.** A forgotten passphrase cannot be recovered. "Reset app data" deletes
  everything after you have typed the word `delete`, and the app starts again with first-time
  set-up.
- **Incognito keyboard.** Every text field asks the keyboard not to learn from what is typed,
  and the app is excluded from autofill.
- **Screenshots are blocked**, and so are screen recordings. "Allow screenshots for 10 minutes"
  in Settings lifts that for a while: the allowance ends after ten minutes, when the phone's
  screen turns off, when you switch it off again, when the app's data is reset or when the app's
  process ends, whichever comes first. Nothing about it is stored, so every start of the app is
  blocked again. The preview in the app switcher stays hidden in any case. On Android 12 and
  older that preview can only be hidden by blocking screenshots altogether, so there the
  allowance is not available and the setting says so.
- **No network permission.** The app's manifest requests no permission, and the build fails if
  a library adds one to the merged manifest that is not on a three-entry allow-list.
- **No backups.** The app's data is excluded from Android's cloud backup and from
  device-to-device transfer.

The About screen lists the models in the build with their sources and licences, and shows the
licence of llama.cpp.

## The models

A model is part of the build: which of them an APK carries is decided when it is built, and the
app offers exactly those. Three are ready to bundle.

| `id` | Name in the app | Source repository on Hugging Face | Licence | Parameters | File in the APK |
| --- | --- | --- | --- | --- | --- |
| `qwen3.5-0.8b` | Qwen3.5 0.8B | `Qwen/Qwen3.5-0.8B` | Apache-2.0 | 0.8 billion in the checkpoint; 752 million in the file, which is text only | 1,516,742,752 bytes |
| `peacebell-v1-291m` | Peacebell v1 291M | `wayneworkman2012/peacebell-v1-291M` | Apache-2.0; the name Peacebell is reserved by the model card | 291 million | 583,680,096 bytes |
| `boris-2-instruct` | Boris-2-Instruct | `opencerebral/Boris-2-Instruct` | Apache-2.0 | 144 million | 291,853,184 bytes |

All three together are 2,392,276,032 bytes (2.39 GB). The exact revision of each repository and
the SHA-256 of every file that is read from it are pinned in `models/manifest.json`, together
with the SHA-256 the converted file must have.

| | Qwen3.5 0.8B | Peacebell v1 291M | Boris-2-Instruct |
| --- | --- | --- | --- |
| What it is | A general assistant in many languages; the default for a new chat | Answers questions about the Second World War from memory; not a general assistant | A very small chat model: fluent, often wrong on facts |
| Architecture in llama.cpp | `qwen35`, as upstream has it | `peacebell`, ported by this project | `boris2`, ported by this project |
| Context on the phone, tokens | 4,096 | 4,096 | 2,048 |
| Longest reply, tokens | 1,024 | 1,024 | 512 |
| Sampling | temperature 1.0, top-k 20, presence penalty 2.0 | greedy | temperature 0.7, top-k 50, top-p 0.9, repetition penalty 1.1 |
| System prompt | yes | no | yes |

The sampling values are each model's own reference settings, taken from its published files.
The temperature can be changed for each model in Settings; the other values are fixed.
The Qwen3.5 file leaves out the vision tower and the multi-token-prediction head of the
checkpoint: the app is text only.

### Precision: 16-bit floats, no quantisation

Every model ships as a GGUF file of 16-bit floats (`F16`). Nothing is quantised to 8, 5 or 4
bits. A checkpoint that is stored as 32-bit floats is cast to 16 bits, and the tests bound what
that changes: against the 32-bit PyTorch reference the 16-bit file must stay within a mean KL
divergence of 1e-5 (5e-5 for Boris-2) and agree on the most likely token at 99.5% of positions or
more. The few tensors that llama.cpp needs as 32-bit floats (norm weights, for example) stay
32-bit.

That costs speed and size: on the test phone an 8-bit quantisation of Qwen3.5-0.8B, measured
with llama.cpp's own benchmark tool, wrote about one and a half times as fast as the 16-bit file
(13.8 against 8.9 tokens per second) and read a prompt about twice as fast, at half the size.
The 4-bit files that are common in on-device apps would be smaller again; they were not
measured.

### Speed on the one phone that was tested

These are measurements on a single device, a Samsung Galaxy XCover7 Pro (SM-G766U1: Snapdragon
7s Gen 3, four Cortex-A720 and four Cortex-A520 cores, 5.5 GB of memory, Android 17). They are
not a promise for any other phone.

| | Qwen3.5 0.8B | Peacebell v1 291M | Boris-2-Instruct |
| --- | --- | --- | --- |
| Reading the conversation (prompt), tokens per second | 89 | 183 | 379 |
| Writing the reply, tokens per second | 9.3 | 24 | 52 |
| Loading the model | 0.45 s | 0.14 s | 0.12 s |
| Memory of the process with the model loaded | 1,621 MiB | 888 MiB | 475 MiB |

The figures come from the tests in `llama/src/androidTest`, not from timing the app's screens:
the 16-bit files, mapped into memory, on the CPU with four threads pinned to the four fast
cores and the test in front. With three unpinned threads, which is what the engine falls back
to when it may not pin, the prompt speeds were 67, 140 and 289 tokens per second; the writing
speed, measured that way for Qwen3.5, did not change. Speeds varied by a few per cent between
runs. `native/README.md` ("On the phone") has the full table and how to repeat it.

Only the CPU is used. The GPU of this phone wrote more slowly than its CPU, and the NPU would
need a proprietary SDK, which this project does not use.

## Security

This section describes what the code does. The classes are in
`app/src/main/java/us/theworkmans/proseplow/security/`, and their unit tests are the more
precise statement of every rule below.

### What is stored, and how

Everything is in the app's private storage.

| File | Content | Protection |
| --- | --- | --- |
| `files/sessions/<id>.enc` | One chat: its messages, its draft, its model and title | Encrypted |
| `files/session-index.enc` | The list of chats | Encrypted |
| `files/settings.enc` | The settings: the system prompt and the temperature of each model | Encrypted |
| `files/kept/*.enc` | Exact copies of files that could not be read completely, kept before they are replaced | Encrypted, as the original was |
| `files/security/lock.json` | The lock configuration: salt, iteration count, the wrapped data key or keys, the lock timeout, the count and time of wrong passphrase attempts | Plain JSON, because it must be readable before the app is unlocked. It holds no secret in the clear |

The models are assets of the APK and are not encrypted: they are public files.

Each encrypted file is one blob:

    "PPE" | version 0x01 | nonce (12 random bytes) | AES-256-GCM ciphertext | tag (16 bytes)

The key is a 256-bit **data key** made from the system's random generator at first-time set-up.
A new random nonce is drawn for every write. The additional authenticated data is the four-byte
header followed by `proseplow:<purpose>`, where the purpose names what the blob is (for example
`session:<id>`), so a file that is renamed, or moved into the place of another, does not
decrypt. Files are replaced atomically (temporary file, `fsync`, rename, `fsync` of the
directory).

### The key hierarchy

The data key exists on disk only wrapped: always by a key derived from the passphrase, and
optionally a second time by a fingerprint key.

    passphrase (Unicode NFC, UTF-8)
      -> PBKDF2-HMAC-SHA256, 600,000 iterations, 16-byte random salt        -> 32 bytes
      -> HMAC-SHA256 with the binding key, over a fixed label and those bytes -> passphrase key
    passphrase key  -- AES-256-GCM --> wraps the data key   (stored in lock.json)
    fingerprint key -- AES-256-GCM --> wraps the data key   (stored in lock.json, optional)
    data key        -- AES-256-GCM --> every stored file

- **The binding key** is an HMAC-SHA256 key that is generated in the Android Keystore, cannot be
  exported and needs no user authentication. Because the passphrase key depends on it, a copy of
  `lock.json` cannot be attacked away from the phone: every guess needs this phone's Keystore.
  A new binding key is made for every new passphrase and the old one is deleted once the new
  configuration is safely on disk, so an older copy of `lock.json` with an older passphrase no
  longer opens. StrongBox is not requested; the key lives in the trusted execution environment
  where the phone has one.
- **PBKDF2** is implemented in the app over `javax.crypto.Mac`, so that the passphrase is turned
  into bytes in an array that is zeroed afterwards; it is checked against the RFC test vectors
  and the JDK's implementation. The iteration count is read from `lock.json`, and a count below
  600,000 is refused as a damaged configuration.
- **The fingerprint key** is an AES-256-GCM key in the Android Keystore that can only be used
  after a strong (Class 3) biometric authentication, once per use, through the system's
  biometric prompt with a crypto object. The phone's PIN, pattern or password cannot authorise
  it. Android invalidates the key when a biometric is enrolled or all are removed; fingerprint
  unlock is then switched off, the passphrase is needed, and the app offers to set it up again.
- Changing the passphrase, and turning fingerprint unlock on or off, need the current
  passphrase, entered within the last two minutes. A fingerprint unlock does not allow them.

### The lock

- Nothing exists before the passphrase does: no data key, no Keystore key and no configuration
  are made until first-time set-up has a passphrase that meets the rules (10 to 1,024
  characters, counted after Unicode normalisation, and not only digits).
- The lock cannot be turned off. The stored configuration has no field for that, and a
  configuration without a passphrase wrap cannot be read.
- The app is locked whenever its process starts, and after it has been in the background for the
  chosen time (immediately, 1 minute by default, 5 or 15 minutes). The time is measured on the
  clock that keeps counting while the phone sleeps, and it is checked again before anything is
  shown when the app comes back.
- When it locks, the screens are torn down at once, a reply that is being written is stopped and
  kept as a stopped message, the writes that are waiting are finished, the inference engine is
  told to forget the conversation, and the data key is overwritten with zeros.
- **Wrong passphrases are slowed down.** The first four wrong attempts cost nothing. The fifth
  costs 30 seconds, and every further one doubles the wait, up to one hour. An attempt is
  counted on disk before it is checked, so killing the app in the middle of a check does not
  avoid the count. Wrong passphrases entered in Settings count as well. A restart of the phone
  during a wait starts the full wait again. There is no wipe after a number of failures.
  Fingerprint attempts are limited by Android itself.

### Reset, and no recovery

There is no recovery key, no reset link and no second way in. A forgotten passphrase leaves one
way on: "Reset app data", which is offered on the lock screen, on the error screens and in
Settings. It asks for no passphrase, because it is the way on when the passphrase is forgotten;
it asks you to type the word `delete`. It then deletes every Keystore key of the app first, from
which moment nothing stored can be decrypted, then every file and the cache, and the lock
configuration last, and the app starts again with first-time set-up. If something could not be
deleted, the app says what.

The app's data is in no backup: `allowBackup` is off and the data extraction rules exclude
everything from cloud backup and from device-to-device transfer. Uninstalling the app, resetting
it or losing the phone loses the chats.

### What stays in memory

- While the app is unlocked, the data key, the decrypted chats and settings, and the model's
  working memory of the conversation are in the app's memory.
- At a lock, the data key's array is zeroed, and the engine clears the model's memory of the
  conversation: the native session overwrites its buffers and the saved checkpoints before it
  frees them.
- Passphrases are handled as character arrays and wiped after use, and so are the keys derived
  from them. A passphrase that contains characters which had to be normalised passes through one
  Java string, which cannot be wiped.
- What cannot be wiped stays until the garbage collector reuses the memory or the process ends:
  the copies of keys inside the Java cryptography classes, and the strings in which the text
  fields, the Markdown renderer and the JSON codec held chat text.
- The loaded model stays in memory across a lock. Its weights are no secret.
- Nothing decrypted is put into the saved instance state, and the main code contains no logging
  call; both are checked by tests that read the sources.

### Other measures

- The window is secure (`FLAG_SECURE`) from its first frame, which keeps it out of screenshots,
  screen recordings and screen sharing, except while a screenshot allowance runs (see "What the
  app does"). The allowance exists in memory only and is timed on the clock that counts from
  the phone's start, so changing the date does not stretch it. The app's own lock does not end
  it: during an allowance the lock screen can be captured as well (its passphrase field shows
  bullets). The preview in the app switcher is switched off separately and always.
- Every text field asks the keyboard not to learn from it
  (`IME_FLAG_NO_PERSONALIZED_LEARNING`). Keyboards may ignore the request.
- The app's windows are excluded from autofill, and a passphrase field gives accessibility
  services bullets instead of the typed text.
- Text copied out of the app is marked as sensitive, which hides it in the clipboard preview of
  Android 13 and later.
- The initialiser through which a library would ask Google Play Services for a downloadable
  emoji font is removed from the manifest, because that request would be made by another
  process on the app's behalf.

### What this does not protect against

- **Anyone who knows the passphrase**, and, while fingerprint unlock is on, anyone whose
  fingerprint is enrolled on the phone.
- **The unlocked app.** Whoever holds the phone while the app is unlocked reads everything, and
  the app stays unlocked in the background for the time you chose.
- **A compromised phone.** Root access, malware with system privileges or an exploited system
  can read the app's memory while it is unlocked, record what is typed and capture the screen
  whatever the window says. The app relies on Android's separation of apps.
- **Guessing on the phone itself by someone with root access.** The count of wrong attempts is
  in a plain file and can be reset, and code that runs as the app can use the binding key. The
  600,000 iterations can then be computed elsewhere in advance, so that a guess costs one
  Keystore operation. In that situation only the strength of the passphrase protects the data.
- **A Keystore that gives up its keys.** If the phone's key store can be made to export the
  binding key, the passphrase key is only as strong as PBKDF2 with 600,000 iterations and your
  passphrase.
- **Traces in memory after a lock**, as described above, and whatever the system moved to
  compressed swap.
- **The keyboard, the clipboard and accessibility services.** A keyboard sees everything typed
  into it. Text you copy leaves the app and can be read by the keyboard and by the app it is
  pasted into. An accessibility service that you have enabled can read what is on the screen,
  chats included.
- **Capture you allowed, and capture from outside.** During a screenshot allowance the screen
  can be captured by anything that can capture a screen. A camera pointed at the phone is never
  prevented.
- **What the files reveal without being decrypted**, to someone who can read the app's private
  storage: that the app is installed and which models it carries, how many chats there are, their
  sizes and the times they were changed, and the content of `lock.json` (whether fingerprint
  unlock is on, how many wrong attempts were made and when).
- **A debug build.** A debug build is debuggable: a computer that is authorised for USB
  debugging can read its private files with `run-as` and attach a debugger to it, and a debug
  build accepts a model file that nothing has verified. Use a release build for chats that
  matter.
- **Loss.** A forgotten passphrase, an uninstalled app or a lost phone loses the chats. "Reset
  app data" needs no passphrase, so anyone who holds the phone can delete everything, though
  not read it.
- **Old ciphertext on the flash storage.** Deleting a chat deletes its file; the phone's storage
  may keep the encrypted bytes until it reuses the space. They stay as unreadable as they were,
  and after a reset the keys that could open them are gone.
- **An old data key.** Changing the passphrase wraps the same data key again; it does not
  encrypt the chats anew. Only a reset replaces the data key.
- **What another app does with a link.** A link you choose to open is handed to another app,
  which uses the network.
- **The sources of the build.** The llama.cpp commit, the Gradle distribution and the model
  files are pinned by hash. Maven and Python packages are pinned by version only, and the
  Android SDK packages by name.

The code has had no independent security review.

## Build from source on Ubuntu 24.04

### What was verified, and how

These instructions were followed literally, on 22 November 2026, on Ubuntu 24.04.5 (x86_64) in a
clean clone, with a freshly downloaded JDK, a freshly installed Android SDK in an empty
directory, a new Python virtualenv and an empty Gradle user home, so that every download in them
was really made. The Gradle steps were last run at commit `3cd0cc7`. The conversions and the
native suite were run in the same clone at `295c975`; nothing under `native/`, `models/`,
`llama/` or `third_party/` differs between the two.

- Run as written: the JDK and command-line tools downloads with their checksums, the
  installation of the five SDK packages, the clone, the virtualenv, the download of the Boris-2
  checkpoint, the conversion of all three models (their hashes matched the manifest), the native
  host suite, `./gradlew check`, debug and release APKs without models and with all three, the
  checks on the APKs, and signing a release APK with a newly made key.
- Not run in that check: `sudo apt install` (the packages were already on the machine; their
  versions are the ones listed), the download commands of the Qwen3.5 and Peacebell checkpoints
  (the same files were taken from a local copy and passed the same hash checks), and everything
  that needs a phone.
- Everything that involves a phone (`adb`, installing, the instrumented tests) was done by the
  project's developers on the one phone named above, not in the clean-machine check.

### Versions

Every tool the build uses, with the version that worked.

| Tool | Version that worked | Where it comes from | What fixes the version |
| --- | --- | --- | --- |
| Ubuntu | 24.04.5 LTS, x86_64 | | |
| git | 2.43.0 | `apt` | Ubuntu 24.04 |
| GCC (`gcc`, `g++`), for the host build and tests | 13.3.0 | `apt`, package `build-essential` | Ubuntu 24.04 |
| Python, with the `venv` module | 3.12.3 | `apt`, packages `python3` and `python3-venv` | Ubuntu 24.04 |
| bash, tar, coreutils, curl, unzip | 5.2.21, 1.35, 9.4, 8.5.0, 6.0 | part of the system, or `apt` | Ubuntu 24.04 |
| JDK | Eclipse Temurin 21.0.13+9 | A tarball from Adoptium's releases, unpacked in user space | The download URL and SHA-256 in this file |
| Android SDK command-line tools (`sdkmanager`) | 22.0, archive build 15859902 | A zip from `dl.google.com` | The download URL and SHA-256 in this file |
| SDK package `platform-tools` (`adb`) | 37.0.1 with the phone; 37.0.3 in the clean-machine check, where `adb` was not run | `sdkmanager` | Nothing: the package has no version in its name, and `sdkmanager` installs the current one |
| SDK package `platforms;android-37.0` | Revision 2 (Android 17) | `sdkmanager` | The package name; `compileSdk = 37` |
| SDK package `build-tools;37.0.0` | 37.0.0 | `sdkmanager` | The package name; `buildToolsVersion` |
| SDK package `ndk;29.0.14206865` | NDK r29 (clang 21.0.0) | `sdkmanager` | The package name; `ndkVersion` in `app/` and `llama/` |
| SDK package `cmake;3.31.6`, for the Android build | CMake 3.31.6 with Ninja 1.12.1 | `sdkmanager` | The package name; `externalNativeBuild.cmake.version` |
| Gradle | 9.7.0 | Downloaded by the wrapper, `./gradlew` | `gradle/wrapper/gradle-wrapper.properties`, with the SHA-256 of the distribution |
| Android Gradle plugin | 9.3.3 | Google's Maven repository | `gradle/libs.versions.toml` |
| Kotlin, with the Compose and serialization plugins | 2.4.21 | Maven Central | `gradle/libs.versions.toml` |
| Compose BOM, and the other libraries | 2026.09.00; see the file | Google's Maven repository and Maven Central | `gradle/libs.versions.toml` |
| llama.cpp | Commit `8d60873dfb8619f2b5ed31adfbab07cccb40db5c` | The git submodule `third_party/llama.cpp` | The submodule and `native/patches/UPSTREAM_COMMIT`, which must agree |
| CMake and Ninja, for the host build | 4.4.5 and 1.13.2 | `pip`, into the virtualenv | `native/requirements.txt` |
| PyTorch (CPU only) | 2.15.0+cpu | `pip`, into the virtualenv | `native/requirements.txt` |
| numpy, transformers, tokenizers, sentencepiece, protobuf, safetensors | 2.5.5, 5.24.0, 0.23.3, 0.2.3, 7.36.2, 0.8.0 | `pip`, into the virtualenv | `native/requirements.txt` |
| huggingface_hub (the `hf` command) | 2.4.0 | `pip`, into the virtualenv | `native/requirements.txt` |

`native/requirements.txt` pins every Python package, including the ones that the packages above
depend on. `gradle.properties` sets `android.builder.sdkDownload=false`: the Android Gradle
plugin never downloads an SDK package by itself, and a build with a missing or different package
fails instead.

### Disk space and time

Measured in the clean-machine check on a 16-thread desktop with an SSD and a fast connection,
with compilation limited to 6 parallel jobs. Plan for about 30 GB of free disk for one debug APK
with all three models, and about 50 GB for everything in this file. The Gradle times are first
runs unless they say otherwise.

| Step | Time | Disk |
| --- | --- | --- |
| JDK: download and unpack | 6 s | 208 MB download, 346 MB unpacked |
| Command-line tools: download and unpack | 3 s | 182 MB download, 175 MB unpacked |
| The five SDK packages | 72 s | 957 MB download, 2.8 GB installed (the NDK is 2.4 GB of it) |
| Clone with the submodule | 22 s | 614 MB, of which 407 MB are llama.cpp's git data |
| Virtualenv with the pinned packages | 59 s | 1.3 GB, and 0.3 GB of pip cache |
| The three checkpoints | 11 s for Boris-2 | 3.4 GB (0.55, 1.1 and 1.7 GB) |
| Converting the three models | 17 s | 2.3 GB |
| Native host suite, `native/tools/host-check.sh` | 8 min 27 s: build 33 s, validation files 13 s, tests 7 min 40 s | 2.0 GB more in the scratch directory, and up to 2.2 GB of temporary files while it runs |
| First Gradle command (`./gradlew :core:test`), which downloads Gradle and the plugins | 1 min 9 s | 0.7 GB in the Gradle user home |
| `./gradlew check` | 1 min 4 s with its downloads; 38 s later, from empty build directories | 1.1 GB in the Gradle user home afterwards |
| `:app:assembleDebug` without models, which includes the native build | 1 min 20 s the first time, 59 s later | 0.7 GB under `app/build` and `llama/build`, 160 MB in `llama/.cxx` |
| `:app:assembleDebug` with all three models | 1 min 5 s after a build without models, 2 min 28 s from empty build directories | 12 GB under `app/build` and `llama/build` |
| `:app:assembleRelease` with all three models | 1 min 33 s after the debug build | 14 GB more under `app/build` and `llama/build`; the Gradle user home had grown to 3.4 GB |
| Signing and verifying the release APK | 19 s and 4 s | 2.4 GB for the signed APK |

The Android Gradle plugin keeps four copies of every bundled model on its way into an APK, which
is where the 12 GB go. `rm -rf app/build llama/build` gives them back.

### Toolchain setup

Everything is installed in user space under `~/Android`, which is where `env.sh` looks for it.
To use another place, install there and export `PROSEPLOW_JAVA_HOME` and
`PROSEPLOW_ANDROID_HOME` before you source `env.sh`. Nothing below edits your shell profile.

#### 1. System packages

The only step that needs `sudo`:

```sh
sudo apt update
sudo apt install git curl unzip build-essential python3 python3-venv
```

#### 2. The JDK

```sh
mkdir -p ~/Android/downloads
cd ~/Android/downloads

curl -fLO 'https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.13%2B9/OpenJDK21U-jdk_x64_linux_hotspot_21.0.13_9.tar.gz'
echo 'd7f5d5cf865a2902b70ff549b97586a7d3f234e016f041ab447ea740c7d66d1c  OpenJDK21U-jdk_x64_linux_hotspot_21.0.13_9.tar.gz' | sha256sum -c

tar -xzf OpenJDK21U-jdk_x64_linux_hotspot_21.0.13_9.tar.gz -C ~/Android    # creates ~/Android/jdk-21.0.13+9
ln -sfn ~/Android/jdk-21.0.13+9 ~/Android/jdk
~/Android/jdk/bin/java -version
```

`sha256sum -c` must answer `OK`, and the last command must print
`OpenJDK Runtime Environment Temurin-21.0.13+9`. The checksum is the one Adoptium publishes next
to the tarball.

#### 3. The Android command-line tools

The zip contains a directory `cmdline-tools`, which has to end up as
`~/Android/Sdk/cmdline-tools/latest`.

```sh
cd ~/Android/downloads

curl -fLO 'https://dl.google.com/android/repository/commandlinetools-linux-15859902_latest.zip'
echo '4e4c464f145a7512b57d088ac6c278c03c9eea610886b35a5e0804e74eedf583  commandlinetools-linux-15859902_latest.zip' | sha256sum -c

mkdir -p ~/Android/Sdk/cmdline-tools
unzip -q commandlinetools-linux-15859902_latest.zip -d ~/Android/downloads
mv ~/Android/downloads/cmdline-tools ~/Android/Sdk/cmdline-tools/latest

JAVA_HOME="$HOME/Android/jdk" ~/Android/Sdk/cmdline-tools/latest/bin/sdkmanager --sdk_root="$HOME/Android/Sdk" --version
```

The checksum is the one on Google's download page for the command-line tools. The last command
prints `22.0`, after a warning that `sdkmanager` is deprecated in favour of a newer "Android
CLI". The warning is expected and appears on every call; these instructions use `sdkmanager`
because that is what was tested.

#### 4. The licence and the SDK packages

```sh
JAVA_HOME="$HOME/Android/jdk" ~/Android/Sdk/cmdline-tools/latest/bin/sdkmanager --sdk_root="$HOME/Android/Sdk" \
    "platform-tools" "platforms;android-37.0" "build-tools;37.0.0" "ndk;29.0.14206865" "cmake;3.31.6"
```

Before it installs anything, `sdkmanager` shows one licence, the Android Software Development
Kit License Agreement, which covers all five packages, and asks `Accept? (y/N)`. **Reading it
and accepting it is your own step.** Nothing in this repository accepts a licence for you, and
the build cannot be made without these packages. No other licence is needed; there is no reason
to run `sdkmanager --licenses`, which offers every licence Google has, previews included.

Four of the five packages carry their version in their name and are installed exactly as
listed. `platform-tools` does not, and you get the current one; only `adb` comes from it, and
the build does not depend on its version. Check what was installed:

```sh
cd ~/Android/Sdk
grep Pkg.Revision platform-tools/source.properties platforms/android-37.0/source.properties \
    build-tools/37.0.0/source.properties ndk/29.0.14206865/source.properties cmake/3.31.6/source.properties
```

### Get the source

```sh
git clone --recurse-submodules https://github.com/wayneworkman/prose-plow.git prose-plow
cd prose-plow
git submodule status
```

The last command must print a line that begins with a blank, not with `-` or `+`, and names the
pinned commit: `8d60873dfb8619f2b5ed31adfbab07cccb40db5c third_party/llama.cpp`, followed by a
description in brackets. After a clone without `--recurse-submodules`, run
`git submodule update --init third_party/llama.cpp`. Every command from here on is run in this
directory.

### Locations and environment

Three locations outside the repository are yours to choose. Nothing is ever written next to the
sources, except by Gradle into its `build` directories.

```sh
export PROSEPLOW_VENV="$HOME/proseplow/venv"                # the Python virtualenv
export PROSEPLOW_BUILD="$HOME/proseplow/build"              # scratch: host build, converted models
export PROSEPLOW_MODEL_SRC="$HOME/proseplow/checkpoints"    # the downloaded checkpoints
export PROSEPLOW_JOBS=6                                     # optional: parallel compile jobs
```

Create the virtualenv and install the pinned packages into it (CMake and Ninja for the host
build, PyTorch and the conversion libraries, the `hf` command):

```sh
python3 -m venv "$PROSEPLOW_VENV"
"$PROSEPLOW_VENV/bin/pip" install -r native/requirements.txt
```

Then, in every new shell, after the four `export` lines:

```sh
. native/tools/env.sh    # the virtualenv's tools on PATH; PROSEPLOW_MODEL_OUT; no GPU for PyTorch
source env.sh            # JAVA_HOME, ANDROID_HOME; java and adb on PATH
```

`native/tools/env.sh` sets `PROSEPLOW_MODEL_OUT`, the directory of the converted models, to
`$PROSEPLOW_BUILD/models`. Gradle finds the Android SDK through `ANDROID_HOME`. A
`local.properties` file (see `local.properties.example`) does the same for a shell in which
`env.sh` was not sourced; it is not needed otherwise. Nothing in the build uses a GPU.

### Get the model checkpoints

Nothing in this repository downloads a model. Fetch the three checkpoints yourself, each at the
revision that `models/manifest.json` pins, into `$PROSEPLOW_MODEL_SRC/<id>`:

```sh
hf download Qwen/Qwen3.5-0.8B --revision 2fc06364715b967f1860aea9cf38778875588b17 \
    --local-dir "$PROSEPLOW_MODEL_SRC/qwen3.5-0.8b"
hf download wayneworkman2012/peacebell-v1-291M --revision a44de191d94e8bf944b31a482fc0f04fa4433eb1 \
    --local-dir "$PROSEPLOW_MODEL_SRC/peacebell-v1-291m"
hf download opencerebral/Boris-2-Instruct --revision 9f60d918ffeeaca15e3caf9d349d8ca34ea5cc7d \
    --local-dir "$PROSEPLOW_MODEL_SRC/boris-2-instruct"
```

No account and no token were needed for the command that was run in the check (Boris-2); `hf`
then warns that the requests are unauthenticated, which is harmless. The files are used under
each model's own licence (see the table above). How the files get there does not matter: a
checkpoint in a Hugging Face hub cache is found as well, and the hashes decide whether the
files are the right ones.

The manifest lists a fourth model, `peacebell-v1-148m`, which is converted and tested but not
bundled. It is optional: without it, six of the native tests report themselves as skipped. To
run them too:

```sh
hf download wayneworkman2012/peacebell-v1-148M --revision 9155a36017601957565a569c73a2b53c9c6a67ec \
    --local-dir "$PROSEPLOW_MODEL_SRC/peacebell-v1-148m"
```

### Convert the models

```sh
native/tools/prepare-llama-src.sh
python3 models/convert_models.py peacebell-v1-291m boris-2-instruct qwen3.5-0.8b
```

The first command writes the patched copy of llama.cpp, which holds the converters, into
`$PROSEPLOW_BUILD/llama-src`. The second verifies every source file against the SHA-256 in the
manifest, converts, and compares the SHA-256 of each result with the one the manifest pins: a
checkpoint that is not byte for byte the pinned one is refused, and so is a conversion that
comes out differently. It prints one line per model:

    peacebell-v1-291m: wrote peacebell-v1-291m-f16.gguf, 583680096 bytes, sha256 df565d191ad1506227f0fb645822ad0224538dcd3a540eb5497ab00ab8dc1868
    boris-2-instruct: wrote boris-2-instruct-f16.gguf, 291853184 bytes, sha256 758d2df848b418064c3e0f7b640c99ff3cad2d8734341d8c4253abc74e6860c6
    qwen3.5-0.8b: wrote qwen3.5-0.8b-f16.gguf, 1516742752 bytes, sha256 91bcb80bb68c2a51e7eb4e28cf088f79678e236f9f16120060b84a57ae7f0978

The files are `$PROSEPLOW_MODEL_OUT/<id>/<id>-f16.gguf`. Name the models as above, or pass
`--available`: without either, the script also wants the optional fourth checkpoint and ends
with an error when it is not there, after converting the others.

### Run the native tests on the host

Optional for building the APK, and the way to know that the native code on your machine
computes what the reference models compute:

```sh
native/tools/host-check.sh
```

It builds llama.cpp and this project's native libraries for the host with GCC, converts the
32-bit validation files, and runs the test suite, which compares the converted files and the
running models with the PyTorch reference. In the check it ended with
`100% tests passed out of 29`: 23 tests ran and the six of the optional fourth model were
skipped. The line `fatal: not a git repository` during the CMake configuration is expected (the
patched copy of llama.cpp is not a git checkout). `native/README.md` describes every test.

### Run the Kotlin tests and the checks

```sh
./gradlew check
```

This runs the unit tests of all three Gradle modules on the JVM, Android lint, and the check of
the merged manifests against the permission allow-list. The first Gradle command downloads
Gradle 9.7.0 and every plugin and library. It must end with `BUILD SUCCESSFUL`. It builds no
native code and needs no model. To leave no Gradle process behind when you are done, run
`./gradlew --stop`; otherwise the idle daemon exits after ten minutes.

### Build the APK

Which models an APK carries is chosen on the command line:

| Gradle property | Environment variable | Meaning |
| --- | --- | --- |
| `-Pproseplow.models=...` | `PROSEPLOW_MODELS` | `none` (the default), `all`, or ids separated by commas |
| `-Pproseplow.modelDir=...` | `PROSEPLOW_MODEL_OUT` | The directory of the converted models; needed unless the selection is `none` |

```sh
# No model: quick to build and install, and the app says that it has none
./gradlew :app:assembleDebug

# All three models
./gradlew :app:assembleDebug -Pproseplow.models=all -Pproseplow.modelDir="$PROSEPLOW_MODEL_OUT"

# Or a selection
./gradlew :app:assembleDebug -Pproseplow.models=boris-2-instruct,peacebell-v1-291m -Pproseplow.modelDir="$PROSEPLOW_MODEL_OUT"
```

The result is `app/build/outputs/apk/debug/app-debug.apk`:

| Models | Debug APK | Release APK, unsigned |
| --- | --- | --- |
| none | 50,176,101 bytes | 41,911,126 bytes |
| all three | 2,442,483,998 bytes | 2,434,219,023 bytes |

The build compiles llama.cpp for arm64 with the NDK (seven variants of the CPU backend, of which
the app loads the best one for the phone's processor), copies each selected model into the APK
and compares its SHA-256 with the manifest once more. A model file that is not the pinned
conversion fails the build. The limit of the APK format is 4 GiB.

**Delete the old APK before you build with another selection of models:**

```sh
rm -f app/build/outputs/apk/debug/app-debug.apk
```

The packaging step of a debug build updates the APK that is already there, and the Android
Gradle plugin never makes that file smaller. In the check, a build without models after one with
all three left a file of 2.4 GB with no model in it, and going back to all three then gave
3.96 GB, of which 1.5 GB was unused space, not far below the limit of the format. Both files were
valid and passed every check; they were only too large. With the old APK deleted first, the
sizes are the ones in the table. `rm -rf app/build llama/build` does the same and frees the disk
space as well. A release build did not show this: its APK came out at the right size after the
selection was changed from all three to none.

### Check the APK

The build checks its own result and fails if a check does not hold; the reports are text files:

```sh
cat app/build/reports/manifest-permissions/debug.txt     # every permission of the merged manifest
cat app/build/reports/native-packaging/packageDebug.txt  # models stored on 16 KiB boundaries, libraries aligned
```

The first must list exactly `android.permission.USE_BIOMETRIC`,
`android.permission.USE_FINGERPRINT` and
`us.theworkmans.proseplow.debug.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`: the two that the
biometric library needs and one that protects the app's own internal broadcasts. There is no
`android.permission.INTERNET`. To look at the APK itself with the SDK's tools:

```sh
BT="$ANDROID_HOME/build-tools/37.0.0"
APK=app/build/outputs/apk/debug/app-debug.apk

"$BT/aapt2" dump permissions "$APK"                # the same three permissions, read from the APK
"$BT/apksigner" verify --verbose --print-certs "$APK"
"$BT/zipalign" -c -P 16 -v 4 "$APK" | tail -n 5    # ends with "Verification successful"
unzip -l "$APK" | grep -E 'assets/models/|lib/arm64-v8a/'

# The model inside the APK is the pinned file: compare with output_sha256 in models/manifest.json
unzip -p "$APK" assets/models/qwen3.5-0.8b.gguf.so | sha256sum
```

Check that the native libraries were stripped of their debug information. The build must not
have printed `Unable to strip the following libraries`, and the `unzip -l` listing must show
these sizes for a build of the pinned sources, in a debug and in a release APK alike:

| Library | Bytes |
| --- | --- |
| `libllama.so` | 4,155,512 |
| `libggml-base.so` | 771,272 |
| `libproseplow_jni.so` | 110,752 |
| `libc++_shared.so` | 1,374,336 |

All thirteen libraries together are 13,588,024 bytes. If `libllama.so` is about 40 MB instead,
the NDK named in `app/build.gradle.kts` is not installed. The native libraries are reproducible:
in the check, the libraries built from a clean clone in another directory with a freshly
installed NDK were byte for byte the ones the developers' own tree had built.

### Install it

With a phone connected (see [Connecting a phone](#connecting-a-phone)):

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

A debug build installs as a separate app, `us.theworkmans.proseplow.debug`, with the name "Prose
Plow (debug)". It has its own data and its own keys and can stay beside a release build. Read
"A debug build" under [What this does not protect against](#what-this-does-not-protect-against)
before you keep chats that matter in it.

### A release build, signed with your own key

The repository contains no signing key and no signing configuration: a release build comes out
unsigned, and you sign it with a key of your own.

```sh
./gradlew :app:assembleRelease -Pproseplow.models=all -Pproseplow.modelDir="$PROSEPLOW_MODEL_OUT"
```

writes `app/build/outputs/apk/release/app-release-unsigned.apk`. Make a key once, in a place of
your choice outside the repository (`keytool` is part of the JDK and asks for a new password):

```sh
mkdir -p ~/keys && chmod 700 ~/keys
keytool -genkeypair -keystore ~/keys/prose-plow.p12 -storetype PKCS12 -alias proseplow \
    -keyalg RSA -keysize 4096 -sigalg SHA256withRSA -validity 36500 -dname "CN=Prose Plow self-built"
chmod 600 ~/keys/prose-plow.p12
```

Sign and verify (`apksigner` asks for the password of the keystore):

```sh
BT="$ANDROID_HOME/build-tools/37.0.0"
"$BT/apksigner" sign --ks ~/keys/prose-plow.p12 --ks-key-alias proseplow \
    --out prose-plow-0.1.0.apk app/build/outputs/apk/release/app-release-unsigned.apk
"$BT/apksigner" verify --verbose --print-certs prose-plow-0.1.0.apk
"$BT/zipalign" -c -P 16 -v 4 prose-plow-0.1.0.apk | tail -n 5
adb install -r prose-plow-0.1.0.apk
```

`apksigner verify` must print `Verifies` and the certificate you just made; the APK carries a
version 3 signature only, which is what Android 9 and later check. `apksigner` also writes
`prose-plow-0.1.0.apk.idsig`, which is not needed for installing and can be deleted. In a script,
`--ks-pass env:NAME` and keytool's `-storepass:env NAME` take the password from an environment
variable instead of asking. The reports of a release build are
`app/build/reports/manifest-permissions/release.txt` and
`app/build/reports/native-packaging/packageRelease.txt`, and the checks of the section above
apply to the signed APK in the same way. In the clean-machine check the signed APK was verified
with these tools; installing it on a phone was not part of that check.

Keep the keystore and its password. Android installs an update only over an app that was signed
with the same key; without the key the only way to a newer build is to uninstall the app, and
that deletes every chat. The release build installs as `us.theworkmans.proseplow`.

## Connecting a phone

The USB route in this section is the one the project's developers used with one phone, a Samsung
Galaxy XCover7 Pro with Android 17. It was not repeated in the clean-machine check, which did
not touch a phone. Where something below was not done by the developers either, it says so.
`adb` is on `PATH` after `source env.sh`.

1. **Developer options.** Open Settings, About phone, and tap "Build number" seven times. On a
   Samsung phone it is under About phone, Software information.
2. **Samsung only: Auto Blocker.** While Auto Blocker is on (Settings, Security and privacy),
   the USB debugging switch is greyed out. Turning Auto Blocker off is not enough: its option
   "Turn on automatically" switches it back on 30 minutes after it was turned off, and USB
   debugging goes off with it, so `adb` loses the phone in the middle of your work without a
   message. **Turn off "Turn on automatically" first**, then Auto Blocker. This cost the project
   hours.
3. **USB debugging.** In Settings, Developer options, turn on "USB debugging".
4. **Connect.** Plug the phone in and accept "Allow USB debugging?" on its screen.

   ```sh
   adb devices
   ```

   must list the phone as `device`, not as `unauthorized`. On the Ubuntu 24.04 desktop that was
   used, no udev rule had to be added for that.
5. **Install.**

   ```sh
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```

   The phone needs free storage for the APK, 2.4 GB with all three models. Over USB a 715 MB APK
   took 20 seconds and a 2.8 GiB test APK 87 seconds. `-r` replaces an installed build of the
   same kind and keeps its data.

**Wireless debugging** is an alternative that needs no cable (Android 11 or later, phone and
computer on the same network): in Developer options open "Wireless debugging", choose "Pair
device with pairing code", and run

```sh
adb pair <address of the phone>:<pairing port>     # asks for the six-digit code
adb connect <address of the phone>:<port>          # the port on the Wireless debugging screen, not the pairing port
```

This is Android's standard procedure, written down for completeness: the developers worked over
USB, and the measurements above are from USB.

**Two builds, two apps.** A debug build is `us.theworkmans.proseplow.debug`, labelled "Prose Plow
(debug)"; a release build is `us.theworkmans.proseplow`. They do not share data or keys and can
be installed side by side. A debug build is signed with a key that the Android Gradle plugin
makes on your machine (`~/.android/debug.keystore`), so a debug build from another machine does
not install over yours.

To remove a build, with all of its data:

```sh
adb uninstall us.theworkmans.proseplow.debug
```

Without `adb`, an APK can also be copied to the phone and opened there, after allowing the file
manager to install apps. Nobody in the project has installed it that way.

## Windows (untested)

> **Untested.** Nobody has built Prose Plow on Windows. What follows is what is known and what
> is not. Corrections by pull request are welcome.

**The route that should work: WSL 2 with Ubuntu 24.04.** Inside it, follow the Ubuntu
instructions above unchanged, with the clone and the three locations on the Linux file system
(under your WSL home, not under `/mnt/c`). It is the same operating system as the tested one, so
the build is expected to behave the same; it has not been tried. You need the disk space listed
above inside the WSL disk image.

Reaching the phone from WSL is the part that differs. Two ways, neither tried:

- **`usbipd-win`** passes the phone's USB device to WSL, after which `adb` inside WSL sees it.
  From an administrator PowerShell: `usbipd list`, then `usbipd bind --busid <id>` once and
  `usbipd attach --wsl --busid <id>` after every reconnection.
- **The Windows `adb`.** Install the `platform-tools` package for Windows, connect the phone to
  Windows as described under [Connecting a phone](#connecting-a-phone), and install the APK that
  was built in WSL from PowerShell, either through its `\\wsl$\...` path or after copying it to
  a Windows directory.

**Building on Windows itself, without WSL.** Known:

- The JDK, the command-line tools (`commandlinetools-win-15859902_latest.zip`, SHA-256
  `90ae805d20434428bffcb699c290860f19bb5f66a67e6b330067e3de801fb04a` on Google's download page)
  and all five SDK packages exist for Windows, and the repository has `gradlew.bat`.
- The Gradle build runs `bash native/tools/prepare-llama-src.sh`, which needs bash 4.4 or newer,
  git and tar on `PATH`. Git for Windows brings a bash; whether the script works in it is
  unknown.
- The patches are applied exactly or not at all. A checkout that converts line endings
  (`core.autocrlf`) would probably break that; clone with `git clone -c core.autocrlf=false`.
- `env.sh` and `native/tools/env.sh` are bash scripts. The converter for Qwen3.5 creates symbolic
  links, which Windows only allows with Developer Mode or administrator rights.
- The host build and the host tests are written for GCC on Linux and use POSIX headers. They are
  not expected to compile with Microsoft's compiler.

Unknown: everything else, including whether the conversions give the pinned hashes on Windows.

## macOS (untested)

> **Untested.** Nobody has built Prose Plow on macOS. What follows is what is known and what is
> not. Corrections by pull request are welcome.

The same tools exist for macOS, and the steps should be the Ubuntu ones with these differences.
None of it has been tried.

- **JDK.** Temurin 21.0.13+9 is published for macOS in the same place:
  `OpenJDK21U-jdk_aarch64_mac_hotspot_21.0.13_9.tar.gz` (Apple silicon, SHA-256
  `bf036b35b071bfb2f54f2c0af8ee8d88afa95ee6eb3dce02c0f94cfc2524d04a`) and
  `OpenJDK21U-jdk_x64_mac_hotspot_21.0.13_9.tar.gz` (Intel, SHA-256
  `9aa1d1c17ae5467a022679e7e0af5c00797d94ac462ed1a46f4db07a8a34a6dd`). The Java home is the
  directory `Contents/Home` inside the unpacked one: point `PROSEPLOW_JAVA_HOME` at it.
- **Command-line tools.** `commandlinetools-mac_arm64-15859902_latest.zip` (SHA-256
  `835b62a26162b229b441d1f6d4680383815a270809eb33522c0d480fa5002c4e`) and
  `commandlinetools-mac_x86_64-15859902_latest.zip` (SHA-256
  `c5a6378ab5cf7e0d5701921405115befff13e9ff7417fb588389338f8bd050f3`), as listed on Google's
  download page. The five SDK packages exist for macOS under the same names.
- **Checksums.** macOS has no `sha256sum`; use `shasum -a 256 -c`.
- **bash.** The scripts need bash 4.4 or newer and macOS ships 3.2. Install a current bash and
  make sure it is the `bash` on `PATH`, because Gradle calls `bash` by that name.
- **Compiler.** The host build expects a C++17 compiler, git and tar; Apple's command-line
  developer tools provide them. Whether the host build and all host tests pass with Apple's
  clang is unknown; nothing in this project has been run on an ARM host.
- **Python packages.** `native/requirements.txt` pins `torch==2.15.0+cpu`. PyTorch's wheels for
  macOS are not published under a `+cpu` version, so this line is expected to need changing to
  `torch==2.15.0`. Whether the conversions then give the pinned hashes is unknown: the build
  refuses a model file with another hash, so you will know.
- **The phone.** `adb` from the macOS `platform-tools` package should reach a phone over USB as
  it does on Linux; not tried.

## Tests

| Suite | What it covers | Command |
| --- | --- | --- |
| `core` (JVM, 274 tests) | The chat model and its stored form, the prompt format of each model against the models' own templates, the model catalogue against the models' files, and the inference engine over a scripted native layer: context budget, memory reuse and checkpoints, stop strings, cancellation | `./gradlew :core:test` |
| `app` (JVM, 356 tests) | The lock and the encryption with software keys and virtual time: the envelope, PBKDF2 against RFC vectors, the back-off, key rotation with a stop at every step, the atomic write, the mandatory lock; the encrypted stores; the chat controller and the view models over a scripted engine; rules about the sources (no logging, nothing decrypted in saved state, package dependencies) | `./gradlew :app:testDebugUnitTest` |
| `llama` (JVM, 11 tests) | The thread plan for a phone's processor, and that the build and the catalogue agree on the names of the model assets | `./gradlew :llama:testDebugUnitTest` |
| All of the above, lint and the permission check | | `./gradlew check` |
| Native host suite (29 tests) | Deterministic conversion; every tensor of the converted files against the checkpoint; tokenizers against the reference libraries; logits and greedy replies against the PyTorch reference, in 32 and in 16 bits; the generation session with every bundled file | `native/tools/host-check.sh` |
| `app`, on a phone | The real Android Keystore behind the lock, the production key derivation, the atomic write on the phone's file system, what a passphrase field gives to accessibility services, the clipboard mark | `./gradlew :app:connectedDebugAndroidTest` |
| `llama`, on a phone | Tokenising and greedy replies equal to the host's and the reference's, memory, cancellation, load time and speed, for each model | `./gradlew :llama:connectedDebugAndroidTest -Pproseplow.models=peacebell-v1-291m,boris-2-instruct -Pproseplow.modelDir="$PROSEPLOW_MODEL_OUT"`; see "On the phone" in `native/README.md` for Qwen3.5 and for running single tests |

The test counts are those of the commit named above. The two suites that need a phone are not
part of `check`. They need a phone that is connected with USB debugging, awake and unlocked.

- The tests of `app` use Keystore keys and files of their own, never the app's. One of them
  needs an enrolled fingerprint and is skipped without one. They run inside the debug build,
  which Gradle installs and **removes again when the tests are done, with the data of a debug
  build that was installed before**.
- The tests of `llama` need the models, either inside the test APK or copied to the phone;
  `native/README.md` has the commands. Building their APK was part of the clean-machine check;
  running them was not.

## Adding and removing a model

A model is a build change, never a download by the app. In outline:

1. Add an entry to `models/manifest.json`: the repository, the revision, the SHA-256 of every
   file the converter reads, the converter and the name of the output.
2. Convert it and record the SHA-256 of the result in the manifest.
3. Add its tests to the native suite. A model of an architecture that llama.cpp does not have
   needs that architecture ported first, with reference data from its PyTorch implementation.
4. Add it to the catalogue in `core` (`ModelCatalog.kt`) with its prompt format, sampling
   settings, context length and licence, and its name to the app's strings.

Removing a model is the reverse. The details are in `models/README.md` (the manifest, getting
checkpoints, converting, "Adding a model", "Removing a model") and in `native/README.md`
("Adding an architecture", "Models in the APK"). With 16-bit files the APK format's limit of
4 GiB is the ceiling for what one APK can carry.

## Project layout

| Path | What it holds |
| --- | --- |
| `app/` | The Android application: the Compose screens, the lock and the encryption (`security/`), the encrypted stores (`data/`), chat (`chat/`) and settings (`settings/`) |
| `core/` | Plain Kotlin without any Android dependency: the chat model, the prompt format of each model, the model catalogue, and the inference engine above the native layer |
| `llama/` | The Android library around the native code: the CMake build for arm64, the JNI binding, the packaging of the models, the checks on the finished APK, and the tests that run on a phone |
| `native/` | Everything below the JVM: the patches to llama.cpp, the two architectures this project adds, the converters, the native library and the generation session, and the host tests. See `native/README.md` |
| `models/` | `manifest.json` with the pinned revisions and hashes, and `convert_models.py`. See `models/README.md` |
| `third_party/llama.cpp` | Upstream llama.cpp as a git submodule at a pinned commit. It is never edited: builds work on a patched copy |
| `gradle/libs.versions.toml` | Every Gradle plugin and library with its version |
| `gradle/wrapper/` | The Gradle wrapper, with the version and SHA-256 of the distribution |
| `env.sh` | Puts the JDK and `adb` on `PATH` for the current shell |
| `local.properties.example` | A template for the optional `local.properties` |
| `LICENSE` | The Apache License 2.0 |

## Contributing

Corrections and improvements are welcome as pull requests, above all for the Windows and macOS
sections, from someone who has actually followed them.

- Run `./gradlew check` before you send a change, and `native/tools/host-check.sh` if it touches
  anything under `native/` or `models/`.
- Tests arrive in the same change as the code they cover.
- Logic that needs no Android belongs in `core`. `app` depends on `core`, never the reverse.
- The app requests no permission. A change that adds one, or a dependency that brings one, fails
  the build and needs a very good reason.
- Model files, signing keys and `local.properties` are never committed.
- When a change makes the build depend on a new tool or another version, record it in the
  versions table of this file.
- Comments and documentation use British spelling. Source files carry no licence header.
- A commit message is one imperative sentence in sentence case, without a prefix tag and without
  a full stop, with a body only where behaviour needs explaining.

Contributions are accepted under the licence of the repository, as section 5 of the Apache
License 2.0 says.

## Licence and third-party notices

Prose Plow is licensed under the Apache License, Version 2.0; the text is in `LICENSE`.

An APK built from this repository also contains the following, each under its own licence.

| Component | Licence | Notice |
| --- | --- | --- |
| llama.cpp and ggml, built from the pinned commit with this project's patches. Only the libraries `libllama` and `ggml` are built; llama.cpp's helper library and its tools are not | MIT | Copyright (c) 2023-2026 The ggml authors. The licence is in `third_party/llama.cpp/LICENSE` and is shown in the app's About screen |
| `libc++_shared.so`, the C++ runtime, taken from the Android NDK | Apache-2.0 with LLVM exceptions | Part of the LLVM project |
| Kotlin standard library, kotlinx.coroutines, kotlinx.serialization | Apache-2.0 | JetBrains s.r.o. and contributors |
| AndroidX libraries: Compose (through its BOM), Material 3, activity, core, lifecycle, navigation, biometric, fragment | Apache-2.0 | The Android Open Source Project |
| `com.mikepenz:multiplatform-markdown-renderer-m3`, and the JetBrains Markdown parser and Compose Multiplatform libraries it uses | Apache-2.0 | Mike Penz; JetBrains s.r.o. |
| Libraries the ones above depend on: AndroidX AppCompat, Guava's ListenableFuture, JSpecify, JetBrains annotations | Apache-2.0 | Their respective authors |
| The models, when they are bundled | See [The models](#the-models): Apache-2.0 each | Qwen3.5-0.8B by the Qwen team of Alibaba Cloud; peacebell-v1-291M, whose model card reserves the name Peacebell for the unmodified weights; Boris-2-Instruct by opencerebral |

The exact versions are in `gradle/libs.versions.toml`. Used for building and testing only, and
not part of an APK: Gradle and the Android Gradle plugin (Apache-2.0), JUnit 4 (Eclipse Public
License 1.0), the AndroidX Test libraries (Apache-2.0), the Python packages of
`native/requirements.txt` under their own licences, and the Android SDK and NDK under the
Android Software Development Kit License Agreement, which you accept yourself when you install
them.
