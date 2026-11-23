# Prose Plow: features and behaviour

This file lists everything the Android app does that a user or a tester can observe, so that a
version for another platform (iOS first) can be held to the same behaviour item by item. It
describes the app as it is built from this repository: every statement was taken from the code,
the tests and the resources, and where a behaviour matters only because of what it protects, the
reason is given with it. One part, [Planned tools](#27-planned-tools-design-considerations-tool),
is about things that are **not** built and is kept apart from everything else.

## How to use this document

- Every item has an identifier (an area prefix and a number, such as `LOCK-07`) and is one
  statement that can be checked, sometimes with sub-points. The identifiers are stable: an item
  that goes away retires its number, and a new item takes the next free one. They can be used as
  the keys of an issue tracker.
- An item states the behaviour in terms that do not assume Android. Where the Android app leans
  on a mechanism of the platform, an **On Android** line names the mechanism and the property it
  provides, so that the equivalent can be looked for. A **Parity note** marks a property that is
  known to be hard, or impossible, to provide elsewhere; it states what has to be preserved and
  does not propose how.
- Text in double quotes is the exact wording the app shows. A name in braces, such as `{model}`,
  stands for a value that is filled in. All wording lives in
  `app/src/main/res/values/strings.xml`; another platform should say the same things.
- A test of the build holds this file to the app (`FeaturesDocumentTest`, beside the rules about
  the sources): it fails when the text of a string in `strings.xml` is not found here word for
  word, when an identifier is defined twice or used without being defined, and when a link
  within this file leads nowhere. A wording that changes in the app is changed here in the same
  commit.
- Numbers (lengths, times, counts) are the values in the code. Where two implementations must
  agree to the byte, the exact form is in
  [Exact formats and rules](#25-exact-formats-and-rules-fmt).
- "The device clock" below always means the clock that counts from the start of the device and
  keeps counting while it sleeps. "The date and time" means the clock a person can set.
- Each section ends its heading with its prefix and begins with a **Source** line naming the
  files that decide its content, so that an item can be kept current. A path is relative to the
  repository. A path that does not begin with a top-level directory is under
  `app/src/main/java/us/theworkmans/proseplow/` (for tests,
  `app/src/test/java/us/theworkmans/proseplow/`), and `core/.../` stands for
  `core/src/main/kotlin/us/theworkmans/proseplow/` (for tests, the same under `core/src/test/`).

## Contents

1. [The app as a whole (APP)](#1-the-app-as-a-whole-app)
2. [First run and set-up (SETUP)](#2-first-run-and-set-up-setup)
3. [The lock (LOCK)](#3-the-lock-lock)
4. [The passphrase (PASS)](#4-the-passphrase-pass)
5. [Fingerprint unlock (BIO)](#5-fingerprint-unlock-bio)
6. [Finishing an answer behind the lock (LEASE)](#6-finishing-an-answer-behind-the-lock-lease)
7. [The end of the process (END)](#7-the-end-of-the-process-end)
8. [Failure screens and reset (RESET)](#8-failure-screens-and-reset-reset)
9. [Keys and encryption (KEY)](#9-keys-and-encryption-key)
10. [Storage (STORE)](#10-storage-store)
11. [Chats (CHAT)](#11-chats-chat)
12. [Messages and replies (MSG)](#12-messages-and-replies-msg)
13. [Markdown, links and copying (MD)](#13-markdown-links-and-copying-md)
14. [Models (MODEL)](#14-models-model)
15. [Settings of each model (MSET)](#15-settings-of-each-model-mset)
16. [Security settings (SECSET)](#16-security-settings-secset)
17. [Screen capture, clipboard, keyboard and overlays (PRIV)](#17-screen-capture-clipboard-keyboard-and-overlays-priv)
18. [Background work and notifications (BG)](#18-background-work-and-notifications-bg)
19. [The inference engine (ENG)](#19-the-inference-engine-eng)
20. [The native session (NAT)](#20-the-native-session-nat)
21. [What stays in memory (MEM)](#21-what-stays-in-memory-mem)
22. [About, licences and notices (ABOUT)](#22-about-licences-and-notices-about)
23. [Accessibility (A11Y)](#23-accessibility-a11y)
24. [Guarantees of the build (BUILD)](#24-guarantees-of-the-build-build)
25. [Exact formats and rules (FMT)](#25-exact-formats-and-rules-fmt)
26. [Security model (THREAT, DEC)](#26-security-model-threat-dec)
27. [Planned tools: design considerations (TOOL)](#27-planned-tools-design-considerations-tool)
28. [What the app does not do (NOT)](#28-what-the-app-does-not-do-not)

## 1. The app as a whole (APP)

**Source:** `app/src/main/AndroidManifest.xml`, `app/build.gradle.kts`,
`app/src/main/java/us/theworkmans/proseplow/MainActivity.kt`, `AppNavigation.kt`,
`ui/ActivityState.kt`, `ui/theme/Theme.kt`.

- **APP-01** The app is named "Prose Plow". Its application identifier is
  `us.theworkmans.proseplow` and is permanent. The version shown in the app is the version name
  of the build; the numeric version code is `major * 10000 + minor * 100 + patch`, and the build
  fails when name and code disagree.
- **APP-02** The app holds conversations with small language models that run on the device's
  own processor. It opens no network connection at any time and has no permission to open one
  (NOT-01).
- **APP-03** The models are part of the installed app. Which of them an installation offers is
  decided when it is built and is asked again at every unlock: the app offers exactly the bundled
  models of its catalogue whose files it carries, down to none at all (MODEL-02, MODEL-21).
- **APP-04** The app is text only (no images, sound or files go into or come out of a model)
  and uses the processor only (no graphics processor, no neural accelerator).
- **APP-05** The app has two groups of screens and only ever builds one of them.
  - Not unlocked: a starting indicator, first-time set-up, the lock screen, and two failure
    screens. Nothing of a chat or a setting exists in the interface while one of these shows.
  - Unlocked: the chat screen, which is the home screen and carries the list of chats as a
    drawer; Settings; a page for each model; About; the third-party notices.
- **APP-06** The app can be opened from the launcher, from the list of recent apps and by
  tapping one of its two notifications (BG-08). All three lead to the lock screen when the app
  is locked. Nothing else opens it: it registers no share target, no link handler, no widget and
  no shortcut.
- **APP-07** Every text the user can see comes from one table of strings; the only exception is
  the technical detail of a failure (PRIV-15), which is an identifier or a few English words
  from the code. The table exists in English only. The names of the models are proper names and
  are not meant to be translated, and neither is the word that confirms a reset (RESET-06).
  Layouts for right-to-left languages are not switched off.
- **APP-08** The appearance follows the system's light or dark setting. There is no setting for
  it in the app. The content is drawn edge to edge, behind the system bars.
  - **On Android:** from Android 12 the colours are taken from the user's wallpaper ("dynamic
    colour"); before that a fixed blue palette is used (primary `#1E5A96` in the light scheme,
    `#A3C9FF` in the dark one). The launcher icon is an adaptive icon with a monochrome layer on
    the background `#1E3A5F`.
- **APP-09** A rotation, or any other change that makes the platform build the screen again,
  loses nothing: the chat on screen, a reply that is being written, the text in the input field,
  a message that is being edited, an open confirmation and the place in Settings all stay. The
  one exception is deliberate: what was typed into a passphrase field, or into the confirmation
  field of a reset, is gone and has to be typed again (PASS-14, RESET-06).
- **APP-10** When the on-screen keyboard opens, the content is resized so that the input field,
  the lock screen's fields and the settings' fields stay visible; nothing is hidden behind the
  keyboard.
- **APP-11** The screen stays on for as long as a reply is being written, on whichever unlocked
  screen is in front, so that a long answer is not cut off by the device going to sleep.
- **APP-12** The back action closes the list of chats when it is open. On Settings, a model's
  page, About and the notices it returns to the screen before. On the chat screen with the
  drawer closed it leaves the app, as the platform defines that.
- **APP-13** A tap that opens another screen is ignored while the screen it was made on is not
  fully in front, so two quick taps never open a screen twice.
- **APP-14** Nothing that was decrypted is ever written to the state the platform saves for an
  app's screens. What is saved is limited to flags, scroll positions and the stack of open
  screens, and it never survives a lock: it is filed under a random key that is replaced at
  every lock, every unlock and every reset, and after the process has ended nothing saved is
  taken back, because the app then starts locked. A reset also deletes the saved state itself.
  - **On Android:** saved instance state is written by the system in the clear. The key is kept
    in a `SavedStateHandle` together with a random token of the process that wrote it.
- **APP-15** Short messages are shown in two ways: as a bar at the bottom of the chat screen
  (for things that concern the chats) and as a brief system pop-up (for things said outside the
  chat screen, such as the results of fingerprint actions).
- **APP-16** A development build is a different app as far as the device is concerned: its own
  identifier (ending in `.debug`), its own data and its own hardware keys, and the name "Prose
  Plow (debug)" wherever the name is shown. Only a development build looks for a model file
  that a developer placed in the app's private storage (MODEL-23). Development and tests can
  therefore never touch a real installation.
- **APP-17** The app runs on 64-bit ARM devices only.
  - **On Android:** Android 9 (API 28) or later, `arm64-v8a` only; the app is built against
    API 37.

## 2. First run and set-up (SETUP)

**Source:** `lock/LockScreens.kt` (`SetupScreen`), `security/AppLock.kt` (`setUp`),
`security/LockManager.kt` (`start`, `setUp`), `security/Passphrase.kt`; tests
`security/LockManagerTest.kt`, `security/AppLockTest.kt`, `security/DurabilityTest.kt`.

- **SETUP-01** When nothing is stored, the app starts at the set-up screen, and there is no way
  past it without creating a passphrase. It shows, in this order:
  - the title "Create your passphrase";
  - "Prose Plow encrypts everything it stores on this phone. Your passphrase unlocks it each
    time you open the app; without it nothing can be read.";
  - in the colour for errors: "A forgotten passphrase cannot be recovered. There is no reset
    link and no recovery key: the only way back in is to reset the app, which deletes all of
    its data.";
  - "Use at least 10 characters, and not only digits.";
  - two passphrase fields, "New passphrase" and "Repeat the passphrase" (PASS-13 says how such a
    field behaves);
  - the button "Create passphrase".
- **SETUP-02** The keyboard's action key moves from the first field to the second and submits
  from the second.
- **SETUP-03** On submitting, the two entries are checked in this order, and the first failure
  is shown under the fields, with both fields marked as wrong:
  1. the two entries differ: "The passphrases do not match.";
  2. fewer than 10 characters: "Use at least 10 characters.";
  3. more than 1,024 characters: "That is longer than any passphrase can be (the limit is 1,024
     characters).";
  4. digits only: "Use more than digits: a PIN is not allowed."

  PASS-01 to PASS-03 say how characters are counted and compared.
- **SETUP-04** Submitting empties both fields whatever the outcome: after a refusal both
  entries are typed again.
- **SETUP-05** While the key is being made, the fields and the button are disabled and a
  spinner shows with "Securing the key…". A second submission during that time is ignored.
- **SETUP-06** Until a passphrase that meets the rules has been submitted, nothing exists: no
  data key, no key in the device's key store, no configuration file, no data file.
- **SETUP-07** On success the data key is made (32 bytes from the system's random generator),
  stored wrapped by the passphrase (KEY-02), and the app is unlocked at once, on a new empty
  chat. Set-up only reports success once the configuration that holds the wrapped key is
  durable on disk (STORE-06), so that nothing can be encrypted with a key whose only wrap a
  power loss could still take away.
- **SETUP-08** If strong biometrics are available at that moment, the app then offers
  fingerprint unlock (BIO-02).
- **SETUP-09** If set-up fails (the key store or the storage reported an error), the screen
  shows "Something went wrong: {detail}", where the detail is the technical name of the failure,
  and set-up can be repeated. Nothing is left behind: the hardware key that was made for the
  attempt is deleted, and a configuration that could not be made durable is removed again.
- **SETUP-10** Set-up is never offered over existing data. If encrypted files exist but the
  lock configuration is gone, the app shows the "cannot be unlocked" screen instead (RESET-02),
  because a new key would make that data unreadable for good. The check is made at start and
  again at the moment the passphrase is submitted.
- **SETUP-11** Hardware keys that an earlier set-up left behind without finishing (or that a
  reset could not delete) are deleted at every start that finds nothing set up; they protect
  nothing.
- **SETUP-12** The same screen follows every complete reset (RESET-08).
- **SETUP-13** The set-up screen can never be captured in a screenshot or a recording, whatever
  screenshot allowance may be running (PRIV-03).

## 3. The lock (LOCK)

**Source:** `security/AppLock.kt`, `security/BackgroundLockPolicy.kt`,
`security/UnlockedSession.kt`, `AppSession.kt`, `lock/LockScreens.kt`, `MainActivity.kt`; tests
`security/AppLockTest.kt`, `security/MandatoryLockTest.kt`, `AppSessionTest.kt`.

- **LOCK-01** The lock is mandatory. There is no setting that turns it off, the stored
  configuration has no field for that, and a configuration without a passphrase cannot be read
  at all.
- **LOCK-02** The app is always in exactly one of six states: starting; set-up required
  (section 2); locked; unlocked; "cannot be unlocked" (RESET-02); "could not be opened"
  (RESET-03).
- **LOCK-03** The app is locked whenever its process starts. Nothing about being unlocked is
  stored anywhere, so a restart of the app or of the device always leads to the lock screen.
  While the stored configuration is being read, only a spinner shows, described to a screen
  reader as "Starting…".
- **LOCK-04** The lock screen shows, in this order: the title "Prose Plow is locked"; one
  passphrase field labelled "Passphrase"; a place for messages; the button "Unlock"; when
  fingerprint unlock is on and its time has not run out (BIO-18), an outlined button "Use
  fingerprint"; and, set apart below them, the action "Reset app data" in the colour for errors
  (RESET-05). When fingerprint unlock is on but its time has run out, the place for messages
  holds the line of BIO-19 instead of the button.
- **LOCK-05** The passphrase is submitted with "Unlock" or with the keyboard's action key.
  Nothing is submitted while the field is empty, while a check is running or while a wait is in
  force (PASS-07). Submitting empties the field.
- **LOCK-06** While the passphrase is being checked, a spinner shows with "Checking the
  passphrase…", and the field, "Unlock" and "Use fingerprint" are disabled.
- **LOCK-07** The outcomes of a check, each shown under the field:
  - wrong: "Wrong passphrase.", with the field marked as wrong;
  - longer than 1,024 characters: "That is longer than any passphrase can be (the limit is 1,024
    characters)."; this is not counted as an attempt;
  - a wait is in force: "Too many wrong attempts. Try again in {time}.", where the time counts
    down as minutes and seconds (with hours when needed), is rounded up to the whole second and
    is refreshed twice a second. "Unlock" is disabled until it reaches zero; the field stays
    usable;
  - an unexpected failure of the key store or the storage: "Something went wrong: {detail}";
  - the hardware key the passphrase depends on is gone: the "cannot be unlocked" screen.
- **LOCK-08** What the lock screen is showing (a check in progress, the last result, a wait)
  survives a rotation. What was typed does not.
- **LOCK-09** A wait that began elsewhere is in force on the lock screen from the moment it
  appears: wrong passphrases entered in Settings before the lock, and a wait that was running
  when the app was last closed.
- **LOCK-10** After a correct passphrase or fingerprint the unlocked session is opened away from
  the interface thread: the settings, the list of chats and the chat that was on screen last are
  read and decrypted (CHAT-02). Only then do the unlocked screens appear. If the stored data
  cannot be looked at, the app shows the "could not be opened" screen instead of an empty app.
  If the session of the unlock before is still closing, its last writes get their bounded time
  (LOCK-17) before the new session reads anything.
- **LOCK-11** The app locks by itself after it has been in the background for the chosen time:
  immediately, 1 minute (the default), 5 minutes or 15 minutes (SECSET-04).
- **LOCK-12** "In the background" begins when the platform reports that the app's screen is no
  longer shown, not when the screen merely loses the focus. The fingerprint prompt, the question
  about notifications and other system dialogs take the focus and must not lock the app.
  Rebuilding the screen for a rotation is not the background either.
  - **On Android:** the activity's `onStop`, which arrives a fraction of a second after the
    screen was left (0.2 to 0.5 seconds after the Home gesture on the phone that was tested).
    Someone who leaves and comes straight back within that moment finds the app as it was, also
    with "Immediately". `onPause` is not used.
- **LOCK-13** The time is measured on the device clock, so it cannot be slept through and is not
  affected by setting the date. A timer does the locking; because a timer may not run while the
  process is frozen or the device sleeps, the elapsed time is looked at again whenever the app
  comes back to the screen, before anything of the session is drawn, and the app locks first if
  the time is up.
- **LOCK-14** With "Immediately", the app locks at the moment LOCK-12 describes.
- **LOCK-15** An unlock that completes after the app has already left the screen (the key
  derivation takes a second or two) counts its timeout from the moment the app left, not from
  the moment the unlock completed. With "Immediately" such a session is locked again before
  anything of it is shown.
- **LOCK-16** "Lock now" locks at once, without a confirmation. It is offered in the menu of the
  chat screen and in the security settings. It is the strongest way to lock: a reply that is
  being written is stopped, and nothing is left alive behind the lock whatever the settings say
  (LEASE-08).
- **LOCK-17** What a lock does, in this order:
  1. The state changes and every unlocked screen is torn down within the same call, not at the
     next frame, so that nothing of a chat or a setting stays referenced by the interface. The
     state of every screen (input text, open dialogs, which message was being edited) is
     dropped.
  2. Changes that were waiting to be written are sent to the disk without further delay.
  3. Away from the interface thread: a reply that is being written is stopped and kept as a
     stopped message (MSG-07); a model load that is under way is abandoned. The lock waits at
     most 10 seconds for the engine to let go.
  4. The engine forgets the conversation (ENG-21); the model itself stays loaded. This too is
     waited for at most 10 seconds; if forgetting fails, the model is unloaded instead.
  5. The writer accepts nothing more and is given at most 10 seconds to finish what it was
     handed.
  6. The data key is overwritten with zeros, whatever became of the steps before. From then on
     every use of the session's cipher fails.
  7. With fingerprint unlock on, the period of BIO-18 starts again from the moment the lock
     was due, and that is stored. For "Lock now" that is the moment of step 1. For a lock by
     itself it is the moment the app left the screen plus the timeout in force, or the moment
     of step 1 if that is earlier: a lock that runs late, because its timer could not run while
     the process was frozen or the device slept (LOCK-13), counts as having happened when it
     was due, whether it is made up for when the app comes back or by the timer itself running
     late. It is stored after step 6, so it never keeps the key in memory, and before the
     process can end (END-01): the process is not ended while the lock's own work is still
     running. When steps 3 to 6 are put off (LEASE-01), this step is not: it is taken at the
     lock, and again when the lease ends, after its own step 6. The end of a lease counts at
     the latest at the end of its 10 minutes, and at the latest 10 minutes after the lock that
     began it was due, however late it is noticed.

  Steps 3 to 6 therefore take at most three waits of 10 seconds each, and they begin once a
  passphrase check that is running at that moment has returned (the two never overlap). LEASE-01
  is the one case in which steps 3 to 6 are put off.
- **LOCK-18** A write that the storage does not complete within its time is not waited for, so
  that it cannot keep the key in memory. Its bytes were encrypted before the key went, so it may
  still reach the disk later. Until it has, an unlock that follows may already be writing the
  same file, and the late write can then replace the newer content; the stores cannot tell the
  two apart. Only a reset waits for such a write (RESET-07).
- **LOCK-19** A session that fails to close still loses its key.
- **LOCK-20** Closing the app's last screen for good (leaving with the back action) drops the
  state of the screens but does not lock: the session stays until the time of LOCK-11 is up.
- **LOCK-21** The lock screen, like every screen on which the app is not unlocked, can never be
  captured in a screenshot or a recording (PRIV-03).
- **LOCK-22** Every time the lock starts and finds a passphrase set up (the start of the
  process, "Try again", the start after a reset), it stores the trusted time (CHAT-24) and then
  deletes what has expired without the key (STORE-22), before a passphrase can be checked: both
  are the lock's own work, which an unlock waits for, so the deletion can never run beside an
  unlock. While a session is alive, being finished behind the lock (LEASE-01) or still closing
  (LOCK-18), nothing is deleted and only the trusted time is stored: the files are that
  session's, and its own expiry covers them. Every unlock and every lock (step 7 of LOCK-17,
  and the end of a lease) store the trusted time as well, and every unlock writes the readable
  copy of the limit of SECSET-13 in the lock configuration again from the setting itself. The
  background maintenance job (BG-12) runs this same clean-up through the lock; it never runs
  it beside the lock's own work.

## 4. The passphrase (PASS)

**Source:** `security/Passphrase.kt`, `security/LockManager.kt`, `security/LockConfig.kt`
(`Backoff`), `lock/PassphraseDialogs.kt`, `lock/SecuritySettings.kt`, `ui/SecureInput.kt`; tests
`security/EnvelopeTest.kt`, `security/LockManagerTest.kt`, `security/LockHardeningTest.kt`,
`lock/SecurityViewModelTest.kt`.

- **PASS-01** A passphrase has at least 10 and at most 1,024 characters and does not consist of
  digits only. A PIN is therefore refused. There is no other rule: no required kinds of
  character, no strength meter, no list of forbidden words, no expiry and no history.
- **PASS-02** Characters are counted as Unicode code points of the text after normalisation
  (PASS-03). "Digits only" means that every character is a decimal digit, of any script.
- **PASS-03** Every passphrase is brought into Unicode Normalisation Form C before it is
  counted, before two entries are compared and before a key is derived from it. The same
  passphrase therefore passes, fails and unlocks alike whichever keyboard composed it (an
  accented letter typed as one code point or as a letter and a combining accent). The key is
  derived from the UTF-8 bytes of that form. This encoding is permanent (FMT-04).
- **PASS-04** Input entered to unlock or to confirm is refused before any check, and without
  counting an attempt, when it is empty or longer than 1,024 characters. The screens do not
  submit an empty field, and an empty entry that reached the lock anyway is ignored without a
  message; a longer one is answered as LOCK-07 says.
- **PASS-05** Every check that is made counts as an attempt before it is checked: the count and
  its time are written to the disk first, and only then is the key derived. Ending the app in
  the middle of a check therefore does not avoid the count. The right passphrase sets the count
  back to zero.
- **PASS-06** Wrong passphrases are slowed down. The count is of attempts since the last
  success:

  | Wrong attempts in a row | Wait before the next attempt |
  | --- | --- |
  | 1 to 4 | none |
  | 5 | 30 seconds |
  | 6 | 1 minute |
  | 7 | 2 minutes |
  | 8 | 4 minutes |
  | 9 | 8 minutes |
  | 10 | 16 minutes |
  | 11 | 32 minutes |
  | 12 and every one after | 1 hour |

- **PASS-07** While a wait is in force, an attempt is refused without being checked and without
  being counted.
- **PASS-08** The wait is timed on the device clock and on nothing else.
  - Within one start of the device it ends exactly one delay after the failure, asleep or awake.
  - If the device was restarted during a wait, the full delay starts again from the first time
    the app looks at it after the restart, and that new start is stored.
  - The date and time are recorded with a failure but never consulted. Setting them forward does
    not end a wait, and setting them back does not prolong one: from whatever moment the app
    looks, a wait never lasts longer than one full delay.
- **PASS-09** There is one count. Wrong passphrases entered in the security settings (PASS-16,
  BIO-03) count exactly like those on the lock screen, the count survives a restart of the app
  and of the device, and a successful fingerprint unlock ends it as the right passphrase does
  (BIO-09).
- **PASS-10** There is no deletion of data after any number of wrong attempts.
- **PASS-11** Deriving a key from a passphrase is meant to take a second or two of one processor
  core. Whenever one is derived (set-up, an unlock, a confirmation in Settings, a change of
  passphrase) a reply that is being written stands aside for exactly that long and then goes
  on unharmed (ENG-22), so that the wait at a passphrase prompt is the same whether or not a
  model is busy.
- **PASS-12** A correct passphrase entry yields a proof that stays valid for 2 minutes. Changing
  the passphrase and turning fingerprint unlock on or off each need a valid proof. A fingerprint
  unlock never yields one, so a fingerprint alone can neither change the passphrase nor change
  how the app is unlocked.
- **PASS-13** A passphrase field, wherever it appears:
  - shows a bullet for every character from the moment it is typed, never the last character
    typed, and has no way to reveal what was typed;
  - asks for the password keyboard, without auto-correction, and asks the keyboard not to learn
    from it (PRIV-10);
  - is kept from autofill (PRIV-11);
  - gives accessibility services one bullet for each character in place of the text, so a screen
    reader still announces a password field and how much is in it, and nothing else can read the
    passphrase out of the field (A11Y-09);
  - is only ever shown where the screen cannot be captured (PRIV-03, PRIV-04);
  - never has its content saved by the platform (APP-14).
- **PASS-14** A passphrase is taken out of its field as an array of characters, never as a
  string, and the field is emptied in the same step. The array and the keys derived from it are
  overwritten after use (MEM-06 says what cannot be overwritten).
- **PASS-15** "Change passphrase" in the security settings is a sequence of two dialogs.
  1. "Enter your passphrase": the line "Enter your current passphrase.", a field "Passphrase",
     and the buttons "Continue" and "Cancel". While it is checked: "Checking the passphrase…".
  2. "New passphrase": the line "Use at least 10 characters, and not only digits. A forgotten
     passphrase cannot be recovered.", the fields "New passphrase" and "Repeat the passphrase",
     and the buttons "Save" and "Cancel". While it is stored: "Securing the key…".

  The new passphrase is checked as in SETUP-03, with the message under the second field. On
  success the dialog closes and the security section says, once: "The passphrase was changed."
- **PASS-16** Messages of the confirmation dialog (also used by BIO-03 and BIO-06), shown under
  its field: "Wrong passphrase."; the too-long message of PASS-04; and, when the key store or the
  storage failed, "That did not work: the phone's key store or storage reported an error." A
  wait after wrong attempts (PASS-06) is shown below the field exactly as on the lock screen
  (LOCK-07): "Too many wrong attempts. Try again in {time}.", counting down from the same value
  the lock screen counts down, whichever of the two screens the attempts were made on, with
  "Continue" disabled until it reaches zero.
- **PASS-17** If more than 2 minutes pass between confirming the current passphrase and saving
  the new one, nothing is changed, the dialog closes and the section says: "Too much time has
  passed. Enter your passphrase again."
- **PASS-18** What a change does: the same data key is wrapped again under the new passphrase
  and a new hardware binding key (KEY-03); the stored data is not encrypted anew. The old
  binding key is deleted only once the new configuration is durable on disk, so a stop at any
  point, a power loss included, leaves either the old or the new passphrase working, never
  neither. Once the old key is gone, an older copy of the configuration, with the older
  passphrase, no longer opens anything.
- **PASS-19** A change is refused, with nothing altered, if the app locked in the meantime.
- **PASS-20** The dialogs in which a passphrase is typed cannot be dismissed while their check
  is running, and can never be captured (PRIV-04).

## 5. Fingerprint unlock (BIO)

**Source:** `security/AppLock.kt`, `security/LockManager.kt`, `security/AndroidKeyVault.kt`,
`security/LockConfig.kt` (`FingerprintPeriod`), `MainActivity.kt` (the prompt),
`lock/LockScreens.kt`, `lock/SecuritySettings.kt`, `ProsePlowApp.kt` (availability, the count
of the device's starts); tests `security/AppLockTest.kt`, `security/LockHardeningTest.kt`,
`security/LockManagerTest.kt`, `security/FingerprintPeriodTest.kt`.

- **BIO-01** Unlocking by fingerprint is optional and off until the user turns it on. It only
  ever accepts the platform's strongest class of biometrics, and it can never fall back to the
  device's PIN, pattern or password. The interface says "fingerprint" throughout, although any
  biometric of that class qualifies.
  - **On Android:** `BiometricPrompt` with a `CryptoObject`, `BIOMETRIC_STRONG` (Class 3) only,
    and a negative button, which is what rules out the device credential. The key is an
    AES-256-GCM key in the Android Keystore that needs a biometric authentication for every
    single use (`setUserAuthenticationParameters(0, AUTH_BIOMETRIC_STRONG)`; on Android 9 and 10
    the older equivalent) and is invalidated when biometrics are enrolled or all removed.
  - **Parity note:** the properties to preserve are that the key which opens the data by
    biometric lives in hardware, can only be used after a biometric match made for that one use,
    becomes unusable when the set of enrolled biometrics changes, and has no route through the
    device passcode.
- **BIO-02** Directly after the passphrase has been created, if a strong biometric is available,
  a dialog offers it: title "Also unlock with fingerprint?"; text "Your fingerprint can unlock
  the app instead of the passphrase. The passphrase always works too, and is still needed after
  the fingerprints on this phone change."; buttons "Use fingerprint" and "Not now". Dismissing
  the dialog is "Not now". Accepting uses the proof of the passphrase that was just created
  (PASS-12), so the passphrase is not asked for again.
- **BIO-03** Turning it on in the security settings asks for the passphrase first (the dialog of
  PASS-15, step 1, with the line "Enter your passphrase to turn on fingerprint unlock."), then
  shows the system's biometric prompt with the title "Turn on fingerprint unlock", the subtitle
  "Confirm with your fingerprint" and the way out "Cancel".
- **BIO-04** After the prompt: on success the app says "Fingerprint unlock is on."; on a failure
  it says "Fingerprint unlock was not turned on."; when the user cancelled the prompt it says
  nothing.
- **BIO-05** What turning it on does: a new hardware key is made, the prompt authorises one use
  of it, and that use wraps the data key; the wrap is stored in the lock configuration (FMT-05,
  FMT-06). A fingerprint unlock that was already set up keeps working until the new one is
  stored, and its key is deleted only after that; cancelling leaves the earlier one untouched.
- **BIO-06** Turning it off asks for the passphrase (with the line "Enter your passphrase to
  turn off fingerprint unlock."), removes the wrap and deletes the hardware key.
- **BIO-07** On the lock screen the biometric prompt appears by itself once for every lock, as
  soon as the lock screen is in front, unless the time of BIO-18 has run out. Its title is "Unlock Prose Plow" and its way out is "Use
  passphrase". A match unlocks without a further confirmation step. If the prompt could not be
  shown, it is tried again the next time the lock screen comes to the front.
- **BIO-08** "Use fingerprint" on the lock screen shows the prompt again. It is disabled while
  the prompt is showing and while a passphrase is being checked, and it is not there while the
  time of BIO-18 has run out.
- **BIO-09** A fingerprint unlock opens the same data key as the passphrase and ends a streak of
  wrong passphrases exactly as the right passphrase does: the count goes back to zero and no
  wait is left. It also starts the period of BIO-18 again. If that cannot be stored, the app is
  not unlocked and the failure is shown.
- **BIO-10** "Use passphrase", or cancelling the prompt, leaves the lock screen as it was and
  says nothing.
- **BIO-11** When a fingerprint unlock does not succeed, the app says one of these, once:
  - too many attempts, as the platform counts them (temporarily or until the device is unlocked
    again): "Too many fingerprint attempts. Use your passphrase.";
  - no biometric is enrolled any more: fingerprint unlock is removed, and "Fingerprint unlock
    was turned off because no fingerprint is enrolled any more. Use your passphrase.";
  - the hardware is missing or busy, or the platform demands a security update: "Fingerprint
    unlock is not available right now. Use your passphrase; fingerprint unlock stays on.";
  - the stored wrap does not open with its own, authorised key: fingerprint unlock is removed,
    and "Fingerprint unlock did not work and was turned off. Use your passphrase.";
  - any other failure after a match: the "not available right now" message; fingerprint unlock
    stays on.
- **BIO-12** When the platform has invalidated the key (a biometric was enrolled, all were
  removed, or the device's secure lock screen was removed), fingerprint unlock is removed
  quietly: no message, the "Use fingerprint" button is gone, and the passphrase is needed. After
  the next unlock by passphrase, if a strong biometric is available, a dialog offers to set it
  up again: title "Turn fingerprint unlock on again?"; text "Fingerprint unlock was turned off
  because the fingerprints on this phone changed, or its key became unusable. Your passphrase
  still works."; buttons "Turn on" and "Not now". "Not now" ends the offer; it is made again
  only after another invalidation (or if declining could not be stored).
- **BIO-13** Only "no strong biometric enrolled" removes fingerprint unlock on the strength of
  what the platform reports about availability. This is looked at when the app starts locked and
  before every prompt. Every other reason for being unavailable (no or busy hardware, a required
  security update, an unknown state) keeps it, and the passphrase is used meanwhile.
- **BIO-14** The wait of PASS-06 does not apply to fingerprint attempts: those are limited by
  the platform. A fingerprint unlock works while a passphrase wait is in force.
- **BIO-15** The switch in the security settings can be used when a strong biometric is
  available or fingerprint unlock is already on; otherwise it is disabled and says "No strong
  fingerprint (or other Class 3 biometric) is set up on this phone." Availability is asked again
  every time the settings come back to the front, so enrolling a fingerprint in the system's
  settings and returning enables the switch.
- **BIO-16** An enrolment whose prompt is answered after the app has locked wraps nothing. A
  lock, and closing the app's last screen while the prompt shows, cancel a pending enrolment and
  delete the key that was made for it. The lock also gives up waiting for that prompt's result,
  so that it cannot keep the locked process alive (END-01); a result that arrives after that is
  ignored and shows no message.
- **BIO-17** A prompt that is showing during a rotation still reports its result. A prompt that
  disappeared without a result is noticed when the app comes back to the front, and "Use
  fingerprint" is enabled again. An unlock prompt still waiting for its result when nothing of
  the app is on screen any more, or on its way there, is given up, so that it cannot keep the
  locked process alive (END-01); a result that arrives after that is ignored.
- **BIO-18** Fingerprint unlock has a time limit, chosen in the security settings (SECSET-12):
  15 minutes, 1 hour (the default), 8 hours or 24 hours; there is no choice without a limit.
  The fingerprint opens the app only while both hold: the device has not been restarted since
  the period last started, and less than the chosen time has passed since then on the device
  clock. Exactly at the end the period has run out. The period starts again at every successful
  unlock, by passphrase (the first, at set-up, included) or by fingerprint, and at every lock
  (LOCK-17, step 7): the timeout, "Lock now", and the end of a lease (LEASE-01). A lock starts
  it at the moment the lock was due, never later: a lock that could only be made up for hours
  later (the device was asleep, the process frozen) does not count as recent use. A correct
  passphrase entered in the security settings starts it too. Otherwise only the passphrase
  opens the app; a correct one starts a new period, and the fingerprint works again.
  - Every case that cannot be decided counts as run out: no start recorded, a count of the
    device's starts that cannot be read, a device clock below the recorded start. After a
    restart the period has run out whatever the device clock shows; unlike the wait of PASS-06,
    nothing is measured against the new clock.
  - The date and time are never consulted.
  - Running out deletes nothing: the hardware key and the wrap stay, fingerprint unlock stays
    on, and it is not marked as invalidated (BIO-12). The limit is a check the app makes when it
    is opened, which is why deleting the key would add nothing against someone who can get past
    that check, and would mean setting fingerprint unlock up again each time.
  - **On Android:** the device clock is `SystemClock.elapsedRealtime()`, and the count of the
    device's starts is `Settings.Global.BOOT_COUNT` (readable without a permission from Android
    7). A start is stored as both values together (FMT-06).
  - **Parity note:** what has to be preserved is that a restart of the device, and the chosen
    time since the last unlock or lock, each end the period on their own, measured on nothing a
    holder of the device can set; where the platform has no count of starts, something that
    tells one start of the device from the next has to stand in for it.
- **BIO-19** While fingerprint unlock is on and its time has run out, the lock screen shows no
  prompt by itself and no "Use fingerprint", and says under the field, in the colour for
  secondary text: "Enter your passphrase this time: the phone was restarted, or the time set
  for fingerprint unlock is up. After that, your fingerprint works again." The time is looked
  at whenever the lock state is read; should it run out while the lock screen is in front, the
  automatic prompt is still not shown, because the fingerprint is refused before a prompt is
  asked for (BIO-20), and the line appears then.
- **BIO-20** The time is checked again at the moment the fingerprint is used. A prompt that
  was showing while the time ran out opens nothing when a finger is then accepted: nothing is
  decrypted, nothing is said to be wrong, fingerprint unlock stays on and is not offered again,
  and the lock screen shows the line of BIO-19.
- **BIO-21** A lock configuration written before the time limit existed has the default time
  (1 hour) and no start of a period: after that update, the passphrase is needed once before
  the fingerprint works again.

## 6. Finishing an answer behind the lock (LEASE)

**Source:** `security/AppLock.kt` (`endSession`, `startLease`, `openOrReattach`),
`security/UnlockedSession.kt`, `AppSession.kt`, `chat/ChatController.kt` (`finishOnly`,
`resume`); tests `security/AppLockTest.kt`, `AppSessionTest.kt`.

- **LEASE-01** When the app locks by itself (LOCK-11 to LOCK-15) while an answer is being
  written, and "Finish the answer after locking" is on (the default, SECSET-05), the screen
  locks exactly as always: every unlocked screen is torn down and the lock screen is all there
  is to see or capture. Behind it the unlocked session is left alive, data key included, until
  that one answer is finished. "Being written" includes an answer that is still waiting for its
  model to load. This happens only while the keep-alive of BG-01 is really running at the moment
  of locking, as the platform says at that moment: if the platform refused it or did not grant
  it (BG-03), if it has not started yet, if the platform no longer has it running, or if the user
  has restricted the app's background use, nothing would keep the process awake to end the
  session on time, and the lock is as LEASE-09 describes.
  - **On Android:** the foreground service must be in the foreground according to the system's
    own record of it (`ActivityManager.getRunningServices`, which returns the app's own
    services), and `ActivityManager.isBackgroundRestricted` must be false. A restricted app's
    service is taken out of the foreground as the app leaves the screen, without being told.
- **LEASE-02** A session left alive in this way starts nothing new: no further reply, and no
  model load that is not for the answer under way, whoever asks. That answer goes on, the
  loading of its model included, and is saved as it goes.
- **LEASE-03** The session ends when the answer has ended, in whichever way, 10 minutes after
  the moment of locking, or the moment the keep-alive of BG-01 ends on the way (the platform
  stopped it, or took it out of the foreground), whichever comes first. At the limit, and when
  the keep-alive is lost, an answer that is still being written is stopped and kept as a
  stopped message. Then the session is closed in the order of
  LOCK-17 (steps 3 to 6) and the key is overwritten. The key is therefore in memory for at most
  10 minutes and then the closing, which is bounded by three waits of up to 10 seconds each
  (LOCK-17).
- **LEASE-04** The limit is counted on the device clock. Because a timer can be late, the
  deadline itself is checked when the app comes to the front and before a session is handed back
  (LEASE-05); a session whose time is up is ended, not resumed.
- **LEASE-05** Unlocking during that time is a complete unlock. The passphrase goes through the
  whole key derivation and the hardware key, or the fingerprint through its prompt and its key,
  with the same count of attempts and the same waits as from a cold lock; that a key happens to
  be in memory shortens nothing. Only a success, and only if the key it produced equals the key
  of the session behind the lock (compared in constant time), puts that same session back on
  screen, so the answer is neither lost nor begun again. The second copy of the key is
  destroyed. A key that does not match ends the session behind the lock and opens a new one.
- **LEASE-06** A wrong passphrase during that time is counted and slowed down as always and
  changes nothing about the answer under way.
- **LEASE-07** The 10 minutes go on counting while the answer stands aside for a passphrase
  check (PASS-11).
- **LEASE-08** "Lock now" never leaves a session alive, and it ends one that is being finished:
  the answer is stopped and the key overwritten at once, whatever the setting. A reset ends it
  too (RESET-07).
- **LEASE-09** With the setting off, with no answer being written, without a running keep-alive,
  or with the app's background use restricted (LEASE-01), the app's own lock stops whatever is
  running and overwrites the key at once, as LOCK-17 describes.
- **LEASE-10** While a session is alive behind the lock its key cannot be wrapped anew: changing
  the passphrase and setting up fingerprint unlock need a session that is on screen.
- **LEASE-11** When the answer is finished behind the lock, it is saved, the engine forgets the
  conversation, the key is overwritten, the notification of BG-06 says that an answer is ready,
  and the process ends (END-01). What keeps the process running in the background (BG-01) is
  held until the key has been overwritten, never released before.
- **LEASE-12** The price is stated to the user (SECSET-05) and is part of the security model
  (THREAT-09): until the session ends, for at most 10 minutes and then the closing of LEASE-03
  (at most three waits of 10 seconds each), the data key and the conversation are in memory as
  if the app were unlocked, although nothing on the screen gives access to them.
  - **Parity note:** the feature needs the process to keep computing for up to 10 minutes while
    the app is out of sight or the screen is off. A platform that suspends background apps
    sooner cannot provide it as it is. What must hold in any case: the screen locks at once; the
    key is not kept longer than the stated limit; a session is left alive only while something
    really keeps the process running to end it on time; unlocking is never shortened; and "Lock
    now" always wins.
- **LEASE-13** The 10 minutes count whatever the model is doing. Reading a conversation again is
  part of the answer (ENG-34), so an answer in a conversation that takes longer than that to read is
  stopped at the limit, possibly before it has written a word, and has to be asked for with the app
  open. The text of MSET-19 says so to the user.

## 7. The end of the process (END)

**Source:** `security/AppLock.kt` (`considerEndingProcess`), `ProcessEnd.kt`; tests
`security/AppLockTest.kt`, `ProcessEndTest.kt`, `SourceRulesTest.kt`.

- **END-01** Whenever all of the following hold, the app ends its own process:
  - no screen of the app is shown, and none is on its way to being shown;
  - no session is open, being finished behind the lock, or still closing. A session only counts
    as closed once its last write has returned, so nothing is lost;
  - no passphrase or fingerprint check, set-up or other work of the lock is running;
  - no run of the background maintenance job holds the process (BG-14). A run that has finished
    holds it until the platform has been told so, and is looked at again only after that;
  - the app is locked. The set-up screen and the two failure screens count as locked only if a
    session was open earlier in the same process, which is the case after a reset.
- **END-02** The conditions are looked at when the app leaves the screen, when a screen of the
  app is destroyed (one that was on its way to being shown may have been all that held the
  process; a rotation is not leaving the screen), and after every piece of the lock's own work,
  the end of a session behind the lock included (LEASE-03).
- **END-03** What follows from END-01:
  - after the app has locked in the background and its session is closed, the process ends;
  - leaving the lock screen without unlocking ends the process;
  - leaving the first-run set-up screen does not (nothing was ever decrypted in that process);
  - the app stays in the list of recent apps, and opening it is a fresh start at the lock
    screen;
  - the model is loaded again at the next unlock;
  - a running screenshot allowance ends with the process (PRIV-06).
- **END-04** The process is never ended while a screen of the app is visible, while an answer is
  being finished behind the lock, before the files are written, or while a write to failing
  storage has not returned.
- **END-05** The reason: a lock overwrites the key, but copies of a conversation remain in
  memory that nothing can overwrite (MEM-04, MEM-05). The end of the process is the only thing
  that releases all of them.
- **END-06** Ending the process loses nothing: by END-01 every write has returned before it
  happens. What keeps the process alive in the background (BG-01) is stopped first.
  - **On Android:** the keep-alive service is stopped and the process is killed
    (`Process.killProcess`). It is killed rather than exited, because an exit runs the
    destructors of the native library, which is not written to be torn down while its threads
    are alive. The task stays in the recent apps. A process that runs instrumented tests never
    ends itself.
  - **Parity note:** a platform may not allow an app to end its own process, or may treat that
    as a crash. The property to preserve is that, once the app is locked and out of sight, no
    readable copy of a conversation remains in its memory. Where the process cannot be ended,
    that property needs another answer, and MEM-04 and MEM-05 list what such an answer has to
    reach.

## 8. Failure screens and reset (RESET)

**Source:** `lock/LockScreens.kt`, `lock/ResetDialog.kt`, `security/AppLock.kt` (`reset`,
`retry`), `security/LockManager.kt` (`start`, `reset`), `security/AppFiles.kt` (`DataReset`);
tests `security/AppLockTest.kt`, `security/AppFilesTest.kt`, `security/LockManagerTest.kt`.

- **RESET-01** There is no recovery of a forgotten passphrase: no recovery key, no reset link,
  no second way in. The only way on is to delete everything.
- **RESET-02** The "cannot be unlocked" screen shows the title "The app's data cannot be
  unlocked", one of three reasons, the line "There is no recovery key. To use the app again,
  reset its data. Try again first if you think the problem is temporary.", and the actions "Try
  again" and "Reset app data". The reasons:
  - the lock configuration exists but cannot be read, is of an unknown version or holds a value
    out of range (FMT-06): "The lock configuration is damaged, so the key that encrypts the
    app's data cannot be recovered.";
  - encrypted files exist but there is no lock configuration: "Encrypted data exists, but its
    lock configuration is gone, so it cannot be decrypted.";
  - the hardware key the configuration names is missing or unusable: "A key in the phone's
    secure key store that the app's data depends on is gone or unusable (for example after the
    key store was cleared)."

  While that key is missing nothing is deleted: the configuration may be an old or foreign copy,
  and the keys of the real one must survive it.
- **RESET-03** The "could not be opened" screen is for failures in which nothing is known to be
  lost: the files or the key store could not be looked at when the app started, or the stored
  data could not be read after an unlock. It shows the title "Couldn't open the app's data", the
  line "Nothing has been deleted. Try again; if it keeps failing, resetting the app's data is
  the only other way on.", the line "Technical detail: {detail}", and the actions "Try again"
  and "Reset app data". A start that cannot look at the files is this failure, never a first
  run.
- **RESET-04** "Try again" on either screen reads the stored configuration afresh, as a start of
  the app does.
- **RESET-05** "Reset app data" is offered on the lock screen (the way on for a forgotten
  passphrase), on both failure screens and at the end of the security settings, where the line
  under it says: "Deletes every chat and setting and your passphrase, then starts again with
  first-time set-up."
- **RESET-06** Every reset goes through one confirmation dialog: title "Reset app data?"; text
  "This deletes everything Prose Plow has stored on this phone: every chat, every setting, your
  passphrase and fingerprint unlock. There is no recovery key and no way to get the data back.
  The app then starts again with first-time set-up."; the line "Type delete to confirm."; a
  field labelled "Confirmation"; the buttons "Delete everything" and "Cancel". "Delete
  everything" is enabled only while the field holds the word `delete`, give or take capitals and
  blanks around it. The field takes one line, without auto-capitalisation and without
  auto-correction, and what was typed is not kept across a rotation. No passphrase is asked for,
  because this is also the way on when the passphrase is forgotten.
- **RESET-07** What a reset does, in this order:
  1. A running screenshot allowance ends.
  2. An unlocked session, and one that is being finished behind the lock, ends: the screens are
     torn down and the starting indicator shows.
  3. The session is closed and its key overwritten (LOCK-17), and then the reset waits, for as
     long as it takes, until nothing of any earlier session can touch a file any more, so that
     no late write can put a file back.
  4. Every hardware key of the app is deleted, also keys under a name this version does not
     know. From this moment nothing stored can be decrypted.
  5. Every file in the app's private files and cache directories is deleted, except the lock
     configuration.
  6. The lock configuration is deleted last.

  Each step is tried even if one before it failed.
- **RESET-08** After a reset the saved state of the screens is deleted and the app starts again
  as from a fresh launch. When everything was deleted, that is first-time set-up.
- **RESET-09** When something could not be deleted, a dialog titled "The reset is not complete"
  lists what, with "OK" to close it:
  - keys: "Some of the app's keys could not be deleted from the phone's key store. The app tries
    again every time it starts." Set-up goes on regardless;
  - files: "Some of the app's files could not be deleted. Reset again to retry." If encrypted
    files are among those left, the app shows the "cannot be unlocked" screen and never offers
    set-up over them;
  - the configuration: "The lock configuration could not be deleted. Reset again to retry." It
    is left with nothing that opens it, and the app shows the "cannot be unlocked" screen.
- **RESET-10** An unlock that completes after a reset has begun is thrown away unseen.
- **RESET-11** A reset needs no passphrase, so anyone who holds the device can delete
  everything, though not read it (THREAT-17).

## 9. Keys and encryption (KEY)

**Source:** `security/Envelope.kt`, `security/KeyVault.kt`, `security/AndroidKeyVault.kt`,
`security/LockManager.kt`, `security/LockConfig.kt`, `security/Passphrase.kt`; tests
`security/EnvelopeTest.kt`, `security/LockManagerTest.kt`, `security/LockHardeningTest.kt`,
`security/DurabilityTest.kt`, `security/MandatoryLockTest.kt`. The exact forms are in section 25.

- **KEY-01** Everything the app stores about the user is encrypted with one 256-bit data key,
  with AES-256-GCM and a new random nonce for every write. Each stored blob is bound to a
  purpose that names what it is (the settings, the list of chats, one chat by its id), so a file
  that is renamed, or put in the place of another, does not decrypt (FMT-01, FMT-02).
- **KEY-02** The data key exists on disk only wrapped: always by a key derived from the
  passphrase and, when fingerprint unlock is on, a second time by a hardware key that needs a
  biometric match. There is no key that opens the data without the user.
- **KEY-03** The passphrase key is derived in two steps: the passphrase is stretched with
  PBKDF2-HMAC-SHA256 (600,000 iterations, a random 16-byte salt), and the result is put through
  an HMAC-SHA256 whose key (the "binding key") lives in the device's hardware key store, cannot
  be exported and needs no user authentication. Because of the second step a copy of the stored
  configuration cannot be attacked away from the device: every guess needs this device's key
  store. A new binding key is made for every new passphrase (FMT-04).
  - **On Android:** an HMAC-SHA256 key in the Android Keystore. StrongBox is not requested; the
    key lives in the trusted execution environment where the device has one.
  - **Parity note:** the property is a keyed function that only this device can compute and
    that cannot be extracted from it. Another platform's hardware key store may not offer an
    HMAC key; whatever stands in for it is a different construction and must be recorded under a
    different name in the configuration's `kdf` field (FMT-06).
- **KEY-04** PBKDF2 is implemented in the app over the platform's HMAC, so that the passphrase is
  turned into bytes in an array the app overwrites afterwards and the result never depends on
  how a library converts characters. It is checked against published test vectors and against
  the platform's own implementation. The iteration count is read from the configuration; a
  count below 600,000 or above 10,000,000 is refused as a damaged configuration, so nothing in
  the file can weaken the derivation or make it hang.
- **KEY-05** Hardware keys are never overwritten. A successor is made under the next generation
  number (a new name), the configuration that refers to it is written and made durable, and only
  then is the old key deleted. A stop at any point, a power loss included, leaves the stored
  configuration referring to a key that exists. Keys that nothing refers to are deleted at
  every start, once the configuration found there is itself durable; while the last write could
  not be made durable, both keys stay. Generation numbers are never reused, and running out of
  them (at 999,999,999) fails cleanly with nothing changed.
- **KEY-06** The data key is held as an array of bytes that is overwritten with zeros when the
  session ends. A key that has been destroyed refuses every further use: it never hands out its
  zeros, so nothing can be encrypted with an all-zero key and an all-zero key can never be
  wrapped as if it were the real one (this is checked before a passphrase change and before a
  biometric authorisation is spent).
- **KEY-07** Changing the passphrase does not change the data key (PASS-18). Only a reset
  replaces it.
- **KEY-08** The encryption proves what a blob is, not that it is the newest version of it. An
  older copy of the same file, put back by someone who can write the app's private storage,
  decrypts and is accepted without notice (THREAT-15).
- **KEY-09** The lock configuration is plain JSON, because it has to be read before the app is
  unlocked. It holds no secret in the clear. Every value in it is checked for range and form
  before anything is derived from it, and the app refuses to write a configuration it would
  refuse to read, so it cannot lock the user out by its own hand. Fields it does not know are
  ignored.

## 10. Storage (STORE)

**Source:** `AppSession.kt` (`DataLayout`), `security/AppFiles.kt`, `security/AtomicFiles.kt`,
`data/SealedFile.kt`, `data/CoalescingWriter.kt`, `data/SessionStore.kt`,
`data/SettingsStore.kt`, `data/ExpiredFiles.kt`, `app/src/main/res/xml/data_extraction_rules.xml`; tests in
`app/src/test/java/us/theworkmans/proseplow/data/`, `security/AppFilesTest.kt`,
`security/DurabilityTest.kt`.

- **STORE-01** Everything is kept in the app's private storage:

  | Path under the private files directory | Content | Form |
  | --- | --- | --- |
  | `sessions/<id>.enc` | One chat: its messages, its unsent draft, its model, its title and the failure of its last request, if any. The file's last-modified time is the date of its newest message (STORE-21) | Encrypted, purpose `session:<id>` |
  | `session-index.enc` | The list of chats (a summary of each) and which chat was on screen last | Encrypted, purpose `session-index` |
  | `settings.enc` | The system prompt of each model; the temperature, the longest reply and the size chosen for the context of each model (MSET-22); whether the question about notifications has been asked; and how long messages are kept (SECSET-13) | Encrypted, purpose `settings` |
  | `kept/*.enc` | Exact copies of files that could not be read completely, made before they were replaced (STORE-09) | Encrypted, as the original was |
  | `security/lock.json` | The lock configuration (FMT-06), with a readable copy of how long messages are kept and the trusted time (CHAT-24) | Plain JSON without a secret in the clear |

  The model files are part of the installed app and are not encrypted: they are public files.
- **STORE-02** Every file that is encrypted with the data key has a name ending in `.enc` and
  lies under the private files directory, outside `security/`. That rule is how the lock knows
  that encrypted data exists (any such file, anywhere under that directory; links are not
  followed; `*.enc.tmp` files of unfinished writes do not count), and it covers stores that may
  be added later.
- **STORE-03** A chat's id is a random UUID. It names the chat's file, is part of what the
  encryption authenticates, and says nothing about order, number or content. Ids are accepted as
  file names only if they consist of 1 to 64 letters, digits, hyphens and underscores.
- **STORE-04** Chats, drafts, the list of chats and the settings are only ever written to the
  disk encrypted. Nothing of them reaches a log, a notification, the state the platform saves
  for the screens, or a backup.
- **STORE-05** A file is replaced atomically: the new content is written to `<name>.tmp`, that
  file is flushed to the disk, renamed over the old one, and the directory is flushed too. A
  stop at any point leaves either the old or the new content, never a mixture.
- **STORE-06** A write counts as durable only once the directory has been flushed as well,
  because a rename that was never flushed can be undone by a power loss. Whatever must not
  happen before the new content is certain (deleting a hardware key that only the old content
  refers to) waits for that. Directories a write creates get their own names flushed as well.
- **STORE-07** All writing happens in the background, one file at a time. Every write says
  everything about its file, so a newer write for a file replaces one that has not started yet.
  A write is either urgent (it runs as soon as the writer is free) or lazy (it waits up to 1
  second for a newer one to replace it):
  - lazy: the text of a reply as it streams, a draft as it is typed, a setting as it is typed;
  - urgent: everything the user did (send, stop, edit, delete, switch model), the end or failure
    of a reply, a change of which chats exist or which is on screen, and the record that the
    question about notifications was asked.

  The stored chat therefore follows a streaming reply by at most a second.
- **STORE-08** When the app leaves the screen, and at a lock, everything lazy is written at
  once, because the process may not live to see the lock.
- **STORE-09** A file is never replaced while it holds something the app has not read
  completely. A file that would not decrypt, that decrypted to something only partly readable,
  that was written by a newer version, or that was never read, is first copied, byte for byte,
  into `kept/`. The copy's name ends in a digest of its bytes, so the same content is kept once
  however often the app starts with it. When the copy cannot be made, the write fails and the
  file stays as it was. Kept copies are not shown anywhere in the app; they go when their chat
  is deleted and at a reset.
- **STORE-10** A chat file that cannot be read at all is never written to. It stays exactly as
  it is until the user deletes that chat (CHAT-16).
- **STORE-11** The list of chats is a convenience; the chat files are the truth. At every unlock
  the list is corrected by the files that are really there: an entry without a file is dropped
  (the chat was deleted); a file without an entry is adopted (its listing did not reach the
  disk) and read for its summary, or listed without a title if it cannot be read; a list that
  is missing or unreadable is rebuilt. To make that sound, a chat is always written to its own
  file before it is listed, and it is deleted by deleting its file.
- **STORE-12** Temporary files that interrupted writes left among the chats are removed at
  every unlock.
- **STORE-13** Deleting a chat deletes its file, what an interrupted write may have left beside
  it and every kept copy of it, and flushes the directories, so that a power loss cannot bring
  it back. No earlier version of a deleted chat stays behind.
- **STORE-14** A write that fails (a full disk, for one) does not stop the app. The chat screen
  says once, in its message bar, "Something could not be saved: {detail}" with the technical
  name of the failure; the other writes go on, and the next change of the same file is a new
  attempt.
- **STORE-15** Settings are stored as they change; there is no Save button and nothing to lose
  by leaving a screen. Without a settings file the defaults are in use and nothing is written
  until something changes.
- **STORE-16** A settings file that cannot be read never stops the app. A card titled "The
  settings could not be read completely" is shown on the chat screen and in Settings until "OK"
  is pressed, with one of:
  - the file could not be read at all: "The stored settings could not be read, so the defaults
    are in use. The old settings file has been kept.";
  - part of it could not be read, or a newer version wrote it: "Some of the stored settings
    could not be read and were left out. The old settings file has been kept."
- **STORE-17** A first start writes nothing but the lock configuration: no list of chats and no
  settings file exist until there is something to store.
- **STORE-18** If the stored data cannot be listed or read from the disk when a session is
  opened, the session does not open and the app shows the "could not be opened" screen
  (RESET-03) rather than an app that looks empty.
- **STORE-19** The app's data is in no backup: it is excluded from the platform's cloud backup
  and from device-to-device transfer. Uninstalling the app, resetting it or losing the device
  loses the chats.
  - **On Android:** `allowBackup="false"`, `fullBackupContent="false"`, and data extraction
    rules that exclude every domain from cloud backup and from device transfer.
- **STORE-20** Nothing in the storage layer is specific to chats: an encrypted file is a blob
  sealed with a namespaced purpose, written atomically and protected by the rule of STORE-09
  (TOOL-07).
- **STORE-21** Every time a chat is written, its file's last-modified time is set to the date of
  its newest message (CHAT-24). When that cannot be done, or no message has a date, the file
  keeps the time it was written, which is later: a deletion by the date (STORE-22) then comes
  later, never earlier. A kept copy (STORE-09) keeps the date of the file it was made from.
  Without the key, the dates are therefore all that shows when each chat last changed.
- **STORE-22** What expires without the key (LOCK-22), by the dates of the files alone and under
  the readable copy of the limit of SECSET-13:
  - a chat file whose date plus the limit lies in the past holds nothing that has not expired,
    and is deleted, with what an interrupted write left beside it and every kept copy of that
    chat, whatever the copies' own dates;
  - a kept copy of a chat, or of the list of chats, whose own date plus the limit lies in the
    past is deleted; kept copies of the settings hold no message and stay;
  - what interrupted writes of chats, of kept copies or of the list of chats left behind
    (`*.enc.tmp`) is deleted once its own date plus the limit lies in the past.

  It runs at every start of the lock, which includes every run of the background maintenance
  job (BG-12) while that is on. With "Never" nothing is deleted. A file whose date cannot be read
  is left alone. The
  directories are flushed after a deletion (STORE-13). The list of chats cannot be changed
  without the key: it names the deleted chats until the next unlock (STORE-23).
- **STORE-23** At every unlock, before any chat is shown and once the list has been corrected by
  the files (STORE-11), the expiry of CHAT-22 is applied to every stored chat whose entry in the
  list does not rule it out: an entry records the date of the chat's oldest message, and a chat
  whose oldest message is within the limit is not read. A chat left without messages is deleted
  with its kept copies; one that keeps some is written again with what remains and its title
  derived again. A chat that holds only a draft, or cannot be read, goes by its file's date as in
  STORE-22. A chat that could only be read in part is deleted only when its file's date is past
  the limit as well, since the messages that could not be read may be newer. A chat that was
  read and kept has the date of its oldest message recorded in its entry, so that later
  unlocks do not read it again until that date is past the limit. Entries of chats that were
  deleted while the app was locked are dropped without a word (unlike CHAT-12); a chat whose
  file is missing although its entry says it changed within the limit was not known to have
  expired, and stays listed, so that opening it says so (CHAT-12). A message stored without a
  date takes its file's date, and one dated in the future takes the present; both are stored
  so.

## 11. Chats (CHAT)

**Source:** `chat/ChatController.kt`, `chat/ChatViewModel.kt`, `chat/ChatScreen.kt`,
`chat/SessionDrawer.kt`, `core/src/main/kotlin/us/theworkmans/proseplow/core/chat/ChatSession.kt`,
`core/.../chat/MessageExpiry.kt`, `core/.../chat/TrustedTime.kt`, `security/TrustedClock.kt`;
tests `chat/ChatControllerTest.kt`, `chat/ChatViewModelTest.kt`, `chat/AiNoticeTest.kt`,
`chat/MessageExpiryChatTest.kt`, `security/TrustedClockTest.kt`,
`core/src/test/kotlin/us/theworkmans/proseplow/core/chat/ChatSessionTest.kt`,
`core/src/test/kotlin/us/theworkmans/proseplow/core/chat/MessageExpiryTest.kt`,
`core/src/test/kotlin/us/theworkmans/proseplow/core/chat/TrustedTimeTest.kt`.

- **CHAT-01** The app keeps several chats. One is on screen at a time. A chat is a single line
  of messages: editing an earlier message or asking for an answer again cuts the line at that
  point, and nothing is kept as an alternative branch.
- **CHAT-02** After an unlock the chat that was on screen last is shown again. If that was a
  chat with nothing in it, or nothing is stored yet, a new empty chat is shown.
- **CHAT-03** A chat with nothing in it is not stored. A new chat is listed and written with its
  first message or its first draft, and a chat that is left empty is forgotten when the user
  moves to another. A chat that only ever held a draft is deleted when the draft has been
  cleared and the user moves on.
- **CHAT-04** A new chat is started from the top bar (a button described as "New chat") or from
  the first entry of the list of chats. It uses the model of the chat that was on screen if this
  build offers that model, otherwise the default model (MODEL-03), otherwise the first model
  the build offers. Asking for a new chat while the chat on screen is already empty does
  nothing.
- **CHAT-05** The list of chats is a drawer that slides over the conversation, opened with the
  menu button of the top bar (described as "Open the list of chats") or with a swipe from the
  edge. Opening it hides the on-screen keyboard. It holds the heading "Chats", the entry "New
  chat", every stored chat, and "Settings" at the bottom. With no stored chat it says: "No chats
  yet. A chat appears here with its first message."
- **CHAT-06** Each chat in the list shows its title on one line, cut with an ellipsis when too
  long, or "Untitled chat" when it has none; under it "{model} · {when}" (only the time for a
  chat whose model is not known); a mark when it is the chat on screen; a small spinner,
  described as "An answer is being written", while a reply is being written in it; and a delete
  button described as "Delete {title}".
- **CHAT-07** "{when}" is "just now" for a change less than a minute ago and otherwise the
  platform's short relative time, to the minute. It is worked out when the row is first shown,
  against the trusted time (CHAT-24) by which the change was dated, not against the device's
  date and time, so a date set back never shows a change in the future.
- **CHAT-08** The list is ordered by when each conversation last changed, the latest first
  (then by creation time, the latest first, then by id). Typing a draft or switching the model
  does not count as a change, so the list does not reshuffle for those.
- **CHAT-09** A chat's title follows its first message from the user: the first line of it that
  has any content, with every run of whitespace turned into one space. A title longer than 48
  characters is cut and ends in an ellipsis ("…"): at the last space that is not beyond the
  48th character, if at least 24 characters stand in front of that space, otherwise after 47
  characters, and never between the two halves of one character. The title follows an edit of
  the first message. A later message does not change it. The user cannot rename a chat.
- **CHAT-10** The top bar of the chat screen shows the chat's title (or "New chat" for a chat
  without one) above the name of the chat's model. The two together are the button that opens
  the model picker (MODEL-05).
- **CHAT-11** Choosing a chat in the list shows it and closes the drawer. A chat that was used
  since the unlock appears at once; another is read from the disk first, and the screen changes
  when that is done. Opening a chat loads no model (MODEL-08).
- **CHAT-12** If the file of a chat has gone by the time it is opened, its entry is dropped from
  the list and the message bar says: "That chat is no longer on this phone."
- **CHAT-13** A chat is deleted from its row in the list or with "Delete chat" in the menu of
  the chat screen (enabled only when the chat on screen is stored, or cannot be read). Either
  way a dialog asks first: title "Delete this chat?"; text "“{title}” will be deleted from this
  phone. This cannot be undone.", or for a chat without a title "This chat will be deleted from
  this phone. This cannot be undone."; buttons "Delete" (in the colour for errors) and "Cancel".
  The dialog survives a rotation.
- **CHAT-14** Deleting is for good (STORE-13); there is no undo. A reply that is being written
  in the chat is abandoned. If the chat was on screen, a new empty chat with the same model
  takes its place. A chat that was still being read from the disk when it was deleted does not
  come back.
- **CHAT-15** What is typed and not sent is the chat's draft. It is stored encrypted with its
  chat as it is typed (STORE-07), comes back after a lock and after a restart, and comes from
  nowhere else. Every chat has its own; switching chats shows the draft of the chat that is now
  on screen. Sending clears it.
- **CHAT-16** A stored chat that cannot be read is still listed, so that it can be seen and
  deleted. Opening it shows, in place of the conversation, "This chat cannot be opened" and "Its
  file is damaged, or was written by a newer version of the app. The file has been left exactly
  as it is, in case a later version can read it. You can also delete the chat.", with the
  buttons "New chat" and "Delete chat". There is no input field and no model in the top bar.
- **CHAT-17** A stored chat of which only a part could be read can be used. A card titled "Part
  of this chat could not be read" says what is missing, followed by "The file as it was has been
  kept.", until "OK" is pressed. It names the first of these that applies:
  - "{n} message could not be read and is not shown." or "{n} messages could not be read and are
    not shown.";
  - "This chat was last saved by a newer version of the app, and some of what that version
    stored may not be shown.";
  - "A stored error message could not be read and is not shown."
- **CHAT-18** A reply keeps being written while the user looks at another chat, at Settings or
  at any other unlocked screen; it belongs to the session, not to a screen.
- **CHAT-19** The menu of the chat screen (a button described as "More options") holds
  "Settings", "Delete chat" and, below a divider, "Lock now".
- **CHAT-20** Directly under the top bar, in every state of the chat screen (an empty chat, one
  that cannot be read, while an answer is written, while a message is edited, with the keyboard
  open), one line says: "This app uses AI, and AI can make mistakes." It never moves and never
  goes away; what comes and goes on the screen does so below it. It has the top bar's own colour
  behind it, small type and little padding, and it wraps rather than being cut at a large font
  size. It is plain text: it cannot be tapped, and a screen reader says it only when the user
  moves to it. The wording is the same in every build and appears on the chat screen only.
- **CHAT-21** A chat without messages shows an invitation in place of the conversation: "Start a
  conversation", then "{model} answers on this phone. Nothing you write leaves it.", then the
  one-sentence description of the model (MODEL-01). When the chat's model cannot answer (it is
  not in this build, the build has no model, or it failed to load) only the first line is shown;
  the card of MODEL-14 to MODEL-16 says why.
- **CHAT-22** Messages expire after the limit chosen in SECSET-13, one limit for every chat. A
  user message and the replies that follow it up to the next user message form a turn, and go
  together; replies before the first user message form a turn of their own. A turn's age is that
  of its newest message. Nothing is removed until some turn is older than the limit; then every
  turn older than the limit less one step is removed, the step being 1 day for the limits of 7
  days and more and 3 hours for 1 day. Messages therefore go up to one step early and never
  late, and a long chat does not lose a turn every few minutes (each removal changes the start
  of the conversation, which the model must then read again). "Older than" is strict: a turn
  exactly at the limit stays. A chat left without messages is deleted whole, with its file and
  its kept copies, without a word; if it was on screen, a new chat with the same model takes its
  place, carrying the draft that was being typed (a draft is not a message and has no date), and
  is stored as any chat with a draft is (CHAT-03). The draft of a chat that was not on screen
  goes with it. A chat that keeps messages takes its title from what is now its
  first user message (CHAT-09), and the failure of a removed turn goes with it.
- **CHAT-23** While the app is unlocked, the expiry of CHAT-22 is applied at the unlock
  (STORE-23), when a stored chat is opened (before it is shown), every hour, and at once when
  the limit is shortened (SECSET-13). A chat whose reply is being written is never changed by
  it: it is pruned as soon as that reply has ended. When the engine holds the conversation of a
  chat that was pruned or deleted this way, it is made to forget it (ENG-21), in turn with the
  replies, so that no expired text stays in the model's memory. A stored chat that has expired
  whole between two passes and is then opened is reported as gone (CHAT-12).
- **CHAT-24** Messages are dated, and their age is measured, by the trusted time: the latest
  time seen, which never goes back. It is the date and time now, or the last trusted time plus
  the time that has passed since it was recorded on the device clock, whichever is later; that
  credit is only given within the same start of the device, known by its count of starts, and
  not at all when the count cannot be read. The trusted time is stored in the lock
  configuration (FMT-06) at every unlock, every lock and every start of the lock (LOCK-22).
  Setting the date back therefore keeps no message alive and dates no message in the past;
  setting it forward is believed, and messages then expire early.

## 12. Messages and replies (MSG)

**Source:** `chat/ChatController.kt`, `chat/ChatViewModel.kt`, `chat/ChatScreen.kt`,
`chat/MessageViews.kt`, `core/.../core/chat/ChatSession.kt`, `core/.../core/chat/ChatMessage.kt`,
`core/.../core/chat/PromptHistory.kt`; tests `chat/ChatControllerTest.kt`,
`chat/ChatViewModelTest.kt`, `core/.../core/chat/ChatSessionTest.kt`,
`core/.../core/chat/PromptHistoryTest.kt`.

- **MSG-01** The field to write in is at the bottom of the chat screen. Empty, it says
  "Message". It grows to at most 6 lines, begins sentences with a capital, and stays usable
  while a reply is being written, so the next message can be typed (and is kept as the draft)
  while an answer arrives.
- **MSG-02** The send button (described as "Send") is enabled when the chat can take a message
  (it is readable and its model is in this build) and the field holds more than blanks. A blank
  message is not sent, and a message that could not be sent stays in the field. "Blanks" has one
  meaning for the button, for sending and for an edit (MSG-13): the characters the prompt trims
  (FMT-09), so the button is never offered for a text that would then not be sent.
- **MSG-03** Sending stores the text without the blanks around it, adds it to the conversation,
  adds the empty reply that is about to be written (stamped with the chat's model at that
  moment), clears the draft and any failure left from the turn before, and scrolls to the end.
- **MSG-04** Only one reply is written at a time, in the whole app. Asking for a reply in one
  chat while another chat is still getting one stops that other reply, which is kept as a
  stopped message, and the message bar says: "The answer in the other chat was stopped."
- **MSG-05** While a reply is being written in the chat on screen, the send button is replaced
  by a stop button (in the colour for errors, described as "Stop"), and the conversation cannot
  be changed: editing, answering again, continuing, trying again and switching the model are
  unavailable until it has ended.
- **MSG-06** Under a reply that is being written, a line with a spinner says what it is waiting
  for, as "{model} · {stage}":
  - "Getting ready…" (the engine is still busy, for example ending the reply before);
  - "Loading the model…", with a bar for the progress of the load;
  - "Reading the conversation…", with a bar for the share of the prompt that is in the model's
    memory (it starts above zero when part of the conversation was still there, ENG-07);
  - "Writing…".
- **MSG-07** "Stop" ends the reply at once. What was written is kept exactly as it is, marked as
  stopped and saved before anything else happens; the engine is told to stop in the background.
- **MSG-08** How a reply ends, and what is kept of it:

  | The reply ended because | Kept as | Its text | Note under it |
  | --- | --- | --- | --- |
  | the model ended its turn, or a stop string was reached | complete | whitespace at its end is removed | none; "The model gave an empty answer" if it has no text |
  | it reached the longest reply set for its model, or the end of the model's context | complete | exactly as written, so that it can be continued | "Reached the length limit" |
  | the user stopped it, another chat asked for a reply, the app locked, or the process ended while it was being written | stopped | exactly as written | "Stopped", or "Stopped before anything was written" if it has no text |
  | the request failed after some text had been written | stopped | exactly as written | "Stopped", with the failure shown below (MSG-14) |
  | the request failed before any text | removed | | the failure is shown where the answer would have been |

- **MSG-09** Under every finished reply a line names the model that wrote it, as "{model}" or
  "{model} · {note}". The model is recorded when the reply is started and never changes
  afterwards, so a chat that switches models keeps a true record. A model this version does not
  know is shown by its id.
- **MSG-10** The actions under a message are small icon buttons with these descriptions. Under
  one of the user's own messages: "Copy message" and "Edit message". Under a reply that has
  text: "Copy message" and "Answer again", and before them the text button "Continue" when
  MSG-12 allows it.
- **MSG-11** "Answer again" removes that answer and everything after it and has the chat's
  current model answer the same question anew. It is available for any answer in the
  conversation, not only the last, and the model that answers may be another than the one that
  wrote the answer being replaced.
- **MSG-12** "Continue" has the model carry on writing the last answer from its last character.
  It is offered only when all of these hold: the message is the last one in the chat; it is a
  reply with some text; it was stopped or reached the length limit; it was written by the chat's
  current model; and nothing is being written. The same message grows; no new message is added.
  Another model may answer again but never continue, because a message names one model.
- **MSG-13** "Edit message" puts the text of one of the user's own messages into the input
  field. While editing:
  - a line above the field says "Editing an earlier message" and "Sending replaces that message
    and deletes everything after it. The model then answers again.", with a close button
    described as "Cancel editing";
  - the message being edited is outlined, the field takes the focus and the keyboard opens;
  - the send button is described as "Send the edited message";
  - the chat's unsent draft is left alone and comes back when the edit is sent or cancelled.

  Sending replaces the text of that message (it keeps its place), removes everything after it
  and starts the reply. A blank edit is not sent. Showing another chat ends an edit, and so does
  the disappearance of the message being edited.
- **MSG-14** When a request fails, a card where the answer would have been says what happened,
  with a line "Technical detail: {detail}" when there is one, and one action. The failure is
  stored with the chat and is still there after a lock. Sending a new message clears it.

  | Failure | Title | Text | Action |
  | --- | --- | --- | --- |
  | No model, or another model, was loaded when the reply was asked for | "The model was not ready" | "No model was loaded when the answer was asked for. Try again." | "Try again" |
  | The model could not be loaded | "The model could not be loaded" | "The model may be missing from this build, or the phone may be short of memory. Try again, or choose another model." | "Try again" |
  | The model could not be loaded because a quarter of the device's memory does not hold even its smallest opening context (ENG-36) | "The model could not be loaded" | "The phone has too little free memory for this model right now. Close other apps and try again, or choose a smaller model." No technical detail is shown with it | "Try again" |
  | The conversation could not be put into the model's prompt format | "The message could not be sent" | "The conversation could not be put into the form this model reads." | "Try again" |
  | The latest message alone leaves no room for a reply (ENG-05) | "The message is too long for this model" | "Tokens it needs: {n}. Tokens the model can read at once: {m}. That leaves no room for an answer; shorten the message and send it again." (without the numbers: "It leaves the model no room for an answer. Shorten the message and send it again.") | "Edit message", which starts MSG-13 for that message |
  | The model's memory ran out during the reply | "The model ran out of room" | "Its memory filled up while it was answering. Try again, or carry on in a new chat." | "Try again" |
  | The native layer reported a failure | "The model stopped with an error" | "The answer could not be finished. Try again." | "Try again" |
  | Anything else, including a kind of failure stored by a newer version | "Something went wrong" | "The answer could not be written. Try again." | "Try again" |

- **MSG-15** "Try again" sends the failed turn again: the failure and everything after that
  turn's message are dropped and a new reply is started, by the chat's current model. The same
  action is offered as a plain row when the last turn has neither an answer nor a failure to
  show: the user's message has no reply at all (the process ended before one began), or the
  reply was stopped before it wrote anything or came out empty.
- **MSG-16** When a conversation no longer fits the model's context at the limit that context may
  grow to (ENG-29), the oldest messages are left out of the latest answer (ENG-04) and a note under
  the conversation says how many: "This conversation is longer than the model can read at once. The
  oldest {n} message was left out of the latest answer.", or with more than one "This conversation
  is longer than the model can read at once. The oldest {n} messages were left out of the latest
  answer." The note belongs to the latest reply in that chat: it is cleared when the next reply is
  asked for, and it is not stored, so it is gone after a lock.
- **MSG-17** A chat opens at its end. While a reply grows the list stays at the end; dragging
  towards older messages stops that, and reaching the end again, or a small button described as
  "Go to the latest message" that appears meanwhile, resumes it. Sending a message resumes it
  too. How far a chat is scrolled, and whether it follows, survive a rotation.
- **MSG-18** The user's messages are shown as bubbles at the end side of the screen with their
  text as typed. Replies take the full width without a bubble and are rendered as Markdown (MD-01),
  without the whitespace in front of them. The text of both can be selected.
- **MSG-19** A change of a model's settings (MSET) applies from the next reply, whether that is
  a new message, an answer asked for again or one that is continued. A reply that is being
  written keeps what it was started with.
- **MSG-20** Every request carries the whole conversation, rendered afresh in the format of the
  model that is to answer, whichever models wrote the earlier replies, together with the system
  prompt set for that model, if it has one. FMT-13 lists exactly which messages are sent.
- **MSG-21** A message sent while its model is still loading waits for the load and then gets
  its reply. A message in a chat whose model is not the one loaded loads that model first. If
  the load fails, the reply fails with that reason and can be tried again.
- **MSG-22** However a generation ends (finished, failed, stopped, cancelled with the session),
  its message is never left marked as being written.
- **MSG-23** When the model's context was made larger for a reply, or could not be made as large as
  the conversation needs (ENG-28 to ENG-33), a note under the conversation says so. It stands above
  the note of MSG-16 and has the same life: it belongs to the latest reply in that chat, is cleared
  when the next reply is asked for, and is not stored. It is made of one or two of these sentences,
  a growth before a limit, with the number of tokens written as the user's locale writes it:
  - the context grew and the model kept what it had read: "This conversation needed more room, so
    the model now reads up to {n} tokens at once.";
  - the context grew and the conversation had to be read again from its start: "This conversation
    needed more room, so the model now reads up to {n} tokens at once. For that it read the whole
    conversation again.";
  - it could not grow as far as needed because the device has too little memory available: "The
    phone has too little free memory to give this conversation more room. For now the model reads up
    to {n} tokens at once.";
  - a larger context could not be created although the memory seemed to be there: "A larger context
    could not be set up on this phone. Until the model is loaded again it reads up to {n} tokens at
    once."

  A reply for which the context grew more than once shows the size it ended with, and says that the
  conversation was read again if any of the steps needed that. The sentence about a limit appears
  with the reply that ran into the limit, not with every reply after it (ENG-33).

## 13. Markdown, links and copying (MD)

**Source:** `chat/MarkdownContent.kt`, `chat/MessageViews.kt`, `chat/ChatScreen.kt`
(`LinkDialog`), `ui/Links.kt`, `ui/UiSupport.kt`, `ui/SelectionMenu.kt`; tests `ui/LinksTest.kt`,
`ui/SelectionMenuTest.kt`, `SourceRulesTest.kt`.

- **MD-01** A reply is rendered as Markdown. The user's own messages are shown as plain text.
- **MD-02** While a reply streams, its Markdown is parsed again in the background as the text
  grows, and the rendering before stays on screen until the next one is ready, so the message
  never collapses or flickers. A finished reply that is shown for the first time is parsed
  before it appears.
- **MD-03** Nothing is ever fetched for a reply. No image loader exists in the app: an image a
  model writes, as a block or in the middle of a line, is shown as its alternative text, in
  italics, as "[image: {alt}]", or as "[image]" when it has none.
- **MD-04** A block of code is shown in a fixed-width font and is never wrapped; it scrolls
  sideways instead. Above it are the language the model named and a button described as "Copy
  code", which copies the code alone, without the fence around it, and confirms with "Code
  copied" (subject to MD-09).
- **MD-05** A link in a reply is never followed directly. Tapping it opens a dialog that shows
  the address in full, in a fixed-width font that can be selected, because the model wrote it
  and what a link says and where it leads can differ.
  - For a link that can be handed on: title "Open this link?"; text "The model wrote this
    address, and it may be wrong. Opening it hands it to another app, such as your browser.";
    buttons "Copy link", "Open" and "Cancel".
  - For any other link: title "Link"; text "This kind of link cannot be opened from here. You
    can copy it."; buttons "Copy link" and "Cancel".
- **MD-06** Only web and mail links can be handed on: the part before the first colon must be
  `http`, `https` or `mailto`, in any mix of capitals. "Open" hands the address to whichever
  other app the device opens such links with; the app itself opens no connection and has no
  browser of its own. If no app takes it, the message bar says: "No app on this phone could open
  that link."
- **MD-07** The link dialog survives a rotation and is remembered only until the user has
  decided.
- **MD-08** The text of every message, of the About screen and of the notices can be selected.
  The menu of a selection offers Cut, Copy, Paste and Select all and nothing else (PRIV-12).
- **MD-09** A copy button copies the message's text: a user's message as stored, a reply without
  the blanks around it, and the reply of a model that continues text exactly as written
  (MODEL-22). The copy is confirmed with "Copied" by the app only where the platform
  does not show a confirmation of its own. A copy that the system refuses (a text too large to
  hand over, for one) is answered with "That could not be copied." instead of a crash.
  - **On Android:** Android 13 and later show their own clipboard confirmation, so the app's
    confirmation is shown on Android 12 and earlier only.
- **MD-10** Everything copied out of the app, by a button or through the selection menu, is
  marked as sensitive (PRIV-09). Copying out of the app always works.

## 14. Models (MODEL)

**Source:** `core/src/main/kotlin/us/theworkmans/proseplow/core/model/` (`ModelCatalog.kt`,
`ModelSpec.kt`, `SamplingParams.kt`, `ReplyLength.kt`), `chat/ModelText.kt`,
`chat/ChatController.kt`, `chat/ChatScreen.kt`, `chat/ChatViewModel.kt`,
`llama/src/main/kotlin/us/theworkmans/proseplow/llama/` (`ModelFiles.kt`, `NativeEngine.kt`),
`models/manifest.json`; tests `core/.../core/model/ModelCatalogTest.kt`,
`chat/ChatControllerTest.kt`, `ModelCatalogManifestTest.kt`,
`llama/src/test/.../BundledModelNamesTest.kt`.

- **MODEL-01** Three models are bundled. Their names are proper names and are shown as they are
  in every language.

  | Id (stored in chats; never reused or changed) | Name shown | Description shown | Source repository on Hugging Face | Licence shown |
  | --- | --- | --- | --- | --- |
  | `qwen3.5-0.8b` | "Qwen3.5 0.8B" | "A general assistant that speaks many languages. The most capable model here." | `Qwen/Qwen3.5-0.8B` | Apache-2.0 |
  | `peacebell-v1-291m` | "Peacebell v1 291M" | "Answers questions about the Second World War from memory. Not a general assistant." | `wayneworkman2012/peacebell-v1-291M` | Apache-2.0 |
  | `boris-2-instruct` | "Boris-2-Instruct" | "A very small chat model for everyday questions. Fluent, but often wrong on facts." | `opencerebral/Boris-2-Instruct` | Apache-2.0 |

- **MODEL-02** The app offers exactly the models of its catalogue that are marked as bundled and
  whose files the installation carries, in the order of the catalogue; both conditions are
  decided in one place. This is asked at every unlock. A model that is not bundled is never
  offered, whatever file of it is present (MODEL-23). Nothing is ever
  discovered, downloaded or imported at run time: adding or removing a model is a change of the
  build.
- **MODEL-03** A new chat starts with Qwen3.5 0.8B when the build offers it (CHAT-04).
- **MODEL-04** What each model is run with. The temperature, the longest reply and how far the
  context may grow can be changed by the user (section 15); everything else is fixed. FMT-14 and
  FMT-15 give the exact rules, and FMT-20 those of the context.

  | | Qwen3.5 0.8B | Peacebell v1 291M | Boris-2-Instruct |
  | --- | --- | --- | --- |
  | Context at the start, tokens (ENG-28) | 4,096 | 4,096 | 2,048 |
  | The most the context can become, tokens: what the model was trained with | 262,144 | 32,768 | 2,048 |
  | Memory for each token of context, bytes | 12,288 | 66,560 | 63,488 |
  | Reading times on the tested phone, assumed until the device the app runs on is measured: seconds to read so many tokens from nothing (ENG-30) | 2,048 in 30.4; 4,096 in 63.5; 6,144 in 99.6; 8,192 in 138.8; 10,240 in 181.9; 12,288 in 227.2; 14,336 in 275.4; 15,872 in 313.5 | 2,048 in 15.5; 4,096 in 35.5; 6,144 in 60.1; 8,192 in 89.2; 10,240 in 125.1; 12,288 in 163.5; 14,336 in 206.4; 15,872 in 241.6 | 512 in 1.3; 1,024 in 2.9; 1,536 in 5.1 |
  | Chunk of a deep read, tokens (ENG-11) | 128 | 256 | 128 |
  | Longest reply, tokens: default; allowed range (MSET-11) | 1,024; 16 to at most 4,096 | 1,024; 16 to at most 4,096 | 512; 16 to 1,024 |
  | Temperature | 1.0 | 0 (always the most likely token) | 0.7 |
  | Top-k; top-p | 20; off | 50; 0.9 (both only matter once the temperature is raised) | 50; 0.9 |
  | Penalties | presence 2.0 | none | repetition 1.1 |
  | A reply ends at | its end-of-turn token or its end-of-text token | its end-of-turn token | the end-of-turn marker written out as text, or its end-of-text token |
  | Tokens never produced | none | token 0 | none |
  | System prompt | yes | no (trained without one) | yes |
  | Prompt format (FMT-09 to FMT-12) | ChatML with control tokens, thinking off | ChatML with injected marker ids | ChatML as plain text |

  The trained context and the bytes for each token are also in `models/manifest.json`
  (`trained_context`, `context_bytes_per_token`), where a test of the native build holds them to the
  model files; a test of the app holds the catalogue to the manifest. The reading times were
  measured on the one phone that was tested, with the test's activity in front and the app's own
  settings (the device run `ReadingDepthDeviceTest`), in each model's chunk of a deep read
  (ENG-11). The phone had been measuring for an hour by then and was warm (battery at 34 degrees
  Celsius); cool, it began about a quarter faster. Boris-2's run leaves room for a short reply, so
  its last point is below its 2,048 tokens and the rest is extrapolated. Shard-2's chunk of a deep
  read is 256.

- **MODEL-05** The model of a chat is chosen by tapping the title of the chat screen (the action
  is described as "Switch model"). A menu lists every offered model with its name and its
  description; the chat's current model is in bold and is marked as selected for accessibility.
  Choosing the current model does nothing.
- **MODEL-06** Every chat has its own model, which is the model of the replies to come. It can be
  switched in the middle of a chat: earlier replies keep the model that wrote them (MSG-09), and
  the whole conversation is shown to the new model in its own format (MSG-20). The choice is
  stored at once. The picker is disabled while a reply is being written in that chat, and says
  so to a screen reader: "Unavailable while an answer is being written".
- **MODEL-07** One model is in memory at a time. Loading another frees the first.
- **MODEL-08** A model is loaded at three moments and at no other: when the app is unlocked (the
  model of the chat on screen, so that the first message does not wait for it); when the user
  picks one, unless a reply is being written somewhere, in which case it is loaded when it is
  first needed; and when a reply needs one. Opening another chat loads nothing, so looking
  through chats that use different models costs nothing.
- **MODEL-09** While a model is being loaded for no reply in particular, a strip under the AI
  notice says "Loading {model}…" with a bar for the progress. A reply that is waiting for the
  load says so itself (MSG-06), and the strip is then not shown.
- **MODEL-10** A model picked while another is still loading takes its place; the other load is
  abandoned. Stopping a reply that is waiting for its model abandons that load too.
- **MODEL-11** The loaded model stays in memory across a lock for as long as the process lives;
  its weights are no secret. Since the process ends once the app is locked and out of sight
  (END-01), the unlock after that loads the model again.
- **MODEL-12** A reply is always written by the model named on it: a request is addressed to the
  model its reply was stamped with, and the engine refuses it if another model has been loaded
  since.
- **MODEL-13** A build may carry any subset of the bundled models, down to none. The app copes
  with each case (MODEL-15, MODEL-16).
- **MODEL-14** When the chat's model failed to load, a card says "{model} could not be loaded" and
  "The model may be missing from this build, or the phone may be short of memory. Try again, or
  choose another model.", with "Technical detail: {detail}" when there is one, and the actions
  "Choose a model" (which opens the picker) and "Try again". When the reason is that the device has
  too little memory for the model (ENG-36), the second sentence is instead "The phone
  has too little free memory for this model right now. Close other apps and try again, or choose a
  smaller model.", and no technical detail is shown. The card is not shown while a reply's own
  failure already says the same (MSG-14).
- **MODEL-15** A chat whose model is not part of this build can be read but not continued until
  another model is chosen. A card says "This chat's model is not in this build" and "{model} is
  not part of this build of the app. Choose another model to carry on with this chat.", with the
  action "Choose a model". A model this version does not know at all is named by its id.
- **MODEL-16** A build without any model keeps its chats readable and asks nothing of the
  engine. A card says "This build has no model" and "No model file is part of this build of the
  app, so messages cannot be answered. Chats that are already here can still be read." The top
  bar shows no model and offers no picker, and nothing can be sent. Settings and About say "This
  build contains no model."
- **MODEL-17** Every model ships as a GGUF file of 16-bit floats. Nothing is quantised to fewer
  bits; a checkpoint stored as 32-bit floats is cast to 16 bits, and the few tensors the runtime
  needs as 32-bit floats stay 32-bit. The Qwen3.5 file is text only: it leaves out the vision
  tower and the multi-token-prediction head of the checkpoint (FMT-18).
- **MODEL-18** A model is never copied on the device, at first start or later. It is handed to
  the native code where it lies and mapped into memory from there, so the weights are in memory
  once, as pages the system can drop and read again when memory is short. If mapping fails, the
  file is read into memory instead.
  - **On Android:** an asset of the APK, stored without compression on a 16 KiB boundary, opened
    as a file descriptor with an offset and a length.
- **MODEL-19** The three bundled models together are 2,392,276,032 bytes, and one installable
  package carries the app and all of them.
  - **On Android:** an APK cannot exceed 4 GiB, which is the ceiling for the set of models.
  - **Parity note:** the largest model needed about 1.6 GiB of process memory on the phone that
    was tested, and the whole package is about 2.4 GB. Whether another platform grants an app
    that much memory, and a package that size, has to be established.
- **MODEL-20** Only the processor is used. On the phone that was tested its graphics processor
  wrote more slowly than the processor, and the neural accelerator would need a proprietary
  kit, which this project does not use.
- **MODEL-21** A fourth model, **Shard-2** (`shard-2`, from `Glint-Research/Shard-2`), is fully
  supported by the code and deliberately not bundled: its repository names a licence only in the
  metadata of its model card, and the app ships a model only when its licence is established.
  No build can package it (the build accepts only models the manifest marks as bundled, and
  fails on any other model file it finds in the package), and no build offers it, not even a
  development build with its file in place (MODEL-02, MODEL-23). The support that exists, and
  that a port must carry if it is to be switched on, is therefore reached today only by the
  tests:
  - It is not a chat model. It is given only the latest message, exactly as typed, and writes on
    from it: earlier turns are not sent, nothing is trimmed, and there is no system prompt.
  - The interface words three lines for it differently: the empty chat says "Start a text" and
    "{model} writes on from what you write, on this phone. Nothing you write leaves it."; the
    empty input field says "Write the beginning of a text".
  - What the user sends is stored exactly as typed, and the model's text is stored exactly as
    written, first character to last, because "Continue" appends to it. Continuing gives the
    model the user's text followed directly by the partial answer.
  - It has one token (id 4096) that takes back the token before it. The app applies that as it
    happens: the words the model retracts disappear from the reply while it is being written,
    never reaching back beyond the start of what this generation wrote (so never into the user's
    text or into the part a continued message already had). The model itself still sees the
    token and what it abandoned; a deletion counts towards the reply's length (ENG-17).
  - It runs with a context of 4,096 tokens, which is what its model card gives as the context it
    was trained with and where it stays: its file would allow 8,192, and the smaller number
    counts (ENG-29). A token of context costs 40,960 bytes. Replies are 200 tokens by default (16
    to 2,048 can be set), with temperature 0.9 and top-k 100. A reply ends at token 1 or token 2.
  - Its name is shown as "Shard-2" and its description as "Continues the text you write. It is
    not a chat model and does not answer questions. It can take back words it has just written.
    Its facts are often invented."
  - A long conversation is never reported as shortened for it, because no history is sent.
- **MODEL-22** For a text-continuing model the chat screen still shows the user's text and the
  model's continuation as two separate messages. It shows the continuation without the
  whitespace in front of it (Markdown would otherwise take indented text for a block of code),
  although it is stored with it, and copies it exactly as written, first character to last,
  since that whitespace is part of the text.
- **MODEL-23** A development build also uses a model file that a developer placed in the app's
  private storage, in preference to a packaged one, so that the weights need not be packaged
  again for every change of the code. Nothing verifies such a file, and the code that looks for
  it is not part of a release build. Such a file is used only for a model the build offers
  anyway (MODEL-02): a file of a model that is not bundled, such as Shard-2, does not make the
  app offer it. The device tests open such a file directly, without the app's list of models.

## 15. Settings of each model (MSET)

**Source:** `settings/SettingsScreen.kt`, `settings/ModelSettingsScreen.kt`,
`settings/SettingsViewModel.kt`, `settings/TemperatureInput.kt`, `settings/ReplyLengthInput.kt`,
`settings/ModelCardLink.kt`, `data/SettingsStore.kt`, `core/.../core/model/ModelCard.kt`,
`core/.../core/model/ReplyLength.kt`, `core/.../core/engine/ContextPolicy.kt`; tests in
`app/src/test/java/us/theworkmans/proseplow/settings/`, `data/SettingsStoreTest.kt`,
`core/.../core/model/ModelCardTest.kt`.

- **MSET-01** Settings are kept for each model, not for each chat: a chat that switches models
  gets the other model's system prompt, temperature, longest reply and context size with it.
  There is no setting that applies to all models.
- **MSET-02** Under the heading "Models", Settings says "Each model has its own settings. Tap a
  model to see and change them." and lists every offered model as one row: its name, and a line
  with the values its next answer would be written with:
  - "Temperature {t} · replies up to {n} tokens · system prompt set";
  - "Temperature {t} · replies up to {n} tokens · no system prompt";
  - for a model that takes no system prompt: "Temperature {t} · replies up to {n} tokens".

  The whole row is one button whose action is described as "open its settings". The numbers are
  written as the user's locale writes them (its decimal mark; its grouping of thousands).
- **MSET-03** A model's page is titled with the model's name and shows, in this order: the
  model's description; the link to its model card (MSET-15); the line "Changes are saved as you
  type and apply from the next answer."; the system prompt, or the reason why there is none;
  the temperature; the longest reply; how far the context may grow (MSET-19); and the way back
  to the model's own values.
- **MSET-04** The system prompt, for the models trained with one (Qwen3.5 and Boris-2): a field
  of 2 to 8 lines labelled "System prompt", which says "None" while empty, with the line "Given
  to the model at the start of every conversation, before your first message." It is stored as
  it is typed. An empty field means none, and a prompt of nothing but whitespace is not sent.
- **MSET-05** A model without one shows a line instead: "{model} was trained without a system
  prompt, so there is none to set." For a model that continues text the line is "{model} is not
  a chat model: it continues the text you write and is given nothing else, so there is no system
  prompt to set."
- **MSET-06** The temperature is a number from 0 to 2, both ends included, in a field labelled
  "Temperature" that asks for the decimal keyboard. Empty, the model's own value applies, and
  the field shows that value as its placeholder. Under it: "0 always picks the most likely
  word; higher values make answers more varied. Default for this model: {default}." For a model
  whose own value is 0 the line ends "Default for this model: {default}, so any value above 0
  turns on sampling."
- **MSET-07** What is read as a temperature: digits with at most one decimal mark, which may
  always be a point and may also be the decimal mark of the user's locale. A number may begin or
  end with the mark (".5", "1."). Digits of other scripts are read as the digits they are.
  Blanks around the entry are ignored. Nothing else is a number: no sign, no exponent, no
  grouping, no blanks inside, and nothing longer than 16 characters. The entry is compared with
  the range as the decimal number that was typed, so 2.0000001 is not rounded into range. A
  stored value is shown without needless zeros, with the locale's decimal mark.
- **MSET-08** Both number fields keep whatever is typed. A valid entry is stored at once. While
  the entry is not valid, the field is marked, the value stored before stays in use, and the
  line under the field says so: for the temperature "Enter a number from 0 to 2. Not saved until
  valid."
- **MSET-09** A non-empty number field has a button at its end that empties it in one tap,
  described as "Use the model's default temperature" or "Use the model's default longest reply".
- **MSET-10** A temperature set by the user replaces the temperature and nothing else: the
  model's other sampling values stay. 0 makes the model always take the most likely token; the
  penalties still apply. For a model whose own temperature is 0 (Peacebell), any value above 0
  turns on sampling with that model's top-k and top-p.
- **MSET-11** The longest reply is a whole number of tokens from 16 to half of what the model's
  context may grow to on this device under the size chosen for it (MSET-19, ENG-29), and never more
  than 4,096, in a field labelled "Longest reply" that asks for the number keyboard. With the
  context left to itself the most is 4,096 for a model whose context may reach 8,192 tokens, and
  1,024 for Boris-2; with a context held at 4,096 tokens it is 2,048. Empty, the model's own value
  applies. Under it: "The most tokens one answer may have before it stops with "Reached the length
  limit"; Continue carries on from there. A token is about three quarters of a word. A longer limit
  leaves less room for the earlier conversation. Default for this model: {default}; at most {most}."
  The two quoted names are the very texts of MSG-08 and MSG-12.
- **MSET-12** What is read as a longest reply: digits and nothing else (of any script), at most 9 of
  them, with blanks around them ignored. An entry that is not such a number, or lies outside the
  range of MSET-11, is answered with "Enter a whole number from {least} to {most}. Not saved until
  valid."
- **MSET-13** The limits are the engine's own numbers (ENG-04, FMT-16): it does not begin a reply
  with less than 16 tokens of room, and it keeps at most half of the context's limit free for a
  reply while it leaves older messages out, so a longer limit could not be kept. The 4,096 is a
  decision: that is plenty for one answer of a small model on a phone, however large the context may
  become.
- **MSET-14** A stored longest reply that lies outside the limits (how far a context may grow
  depends on the build, the device and the size chosen for it, all of which can change) is not used:
  the model's own value applies, itself held to the limits. It is not moved to the nearest limit,
  because nobody chose that value. When the page is opened, such a value is not shown either. When
  the limits change while the page is open, because another size is chosen for the context, an entry
  that no longer lies within them stays in the field as typed and is marked as in MSET-12; it is in
  use again as soon as the limits include it.
- **MSET-15** The model card: a row "Model card" with the line "{address} · opens in your
  browser", where the address is `huggingface.co/{owner}/{name}` of the model's source
  repository. A tap (described as "open it in your browser") hands `https://` followed by that
  address to the device's own browser at once, without a confirmation: the address is the app's
  own, fixed in the build and shown on the row that opens it. Nothing is loaded inside the app.
  If no app opens web addresses, the app says "No app on this phone could open that link." The
  address is only ever built from a repository written as one owner and one name (letters,
  digits, `.`, `_` and `-`, each part beginning with a letter or a digit and at most 96
  characters, without `..`); for anything else the row is not shown, so no other host, path,
  query or user name can be smuggled in.
- **MSET-16** "Restore this model's defaults" empties both number fields, whatever is in them, and
  sets the context back to automatic (MSET-19). It is enabled while either field holds an entry or a
  size is chosen for the context. The line under it says "Sets the temperature and the longest reply
  back to this model's own values, and the context to automatic. The system prompt stays as it is.",
  or for a model without a system prompt "Sets the temperature and the longest reply back to this
  model's own values, and the context to automatic." The system prompt is not a value with a default
  but something the user wrote, and stays.
- **MSET-17** A value that is not set is not stored. A model whose own values improve in a later
  build therefore improves for everyone who never changed them.
- **MSET-18** Not adjustable in the app: top-k, top-p, min-p, the penalties, the random seed, the
  size a context starts with and what ends a reply.
- **MSET-19** How far the model's context may grow is chosen under the heading "Context", for a
  model whose context can grow on this device (ENG-29). The text under the heading says, with the
  size the context starts with as its number: "How much of a conversation this model reads at once,
  in tokens. It starts at {start} and grows only when a conversation needs more, which takes more of
  the phone's memory. After the app was locked and left, the whole conversation is read again before
  the next answer, so a longer conversation means a longer wait. An answer that goes on after the
  app locks is stopped after ten minutes at the latest, so a conversation that takes longer than
  that to read is best read with the app open. Beyond the limit chosen here, the oldest messages are
  left out of an answer." Under it one size is chosen from a list:
  - first "Automatic: up to {n} tokens on this phone", which is the default and stores nothing. The
    number is the limit the context has when it is left to itself (ENG-29), worked out with the
    reading times of MODEL-04;
  - then "Up to {n} tokens" for each fixed size: the size the context starts with, twice that, four
    times, and so on, and as the last one the most the device's memory allows the model, which is
    what the model was trained with when the memory holds that, and otherwise need not be a power of
    two.

  With 5,500,000,000 bytes of memory the list for Qwen3.5 is automatic up to 7,168 and the fixed
  sizes 4,096, 8,192, 16,384, 32,768, 65,536 and 111,616; for Peacebell it is automatic up to 9,216
  and the fixed sizes 4,096, 8,192, 16,384 and 20,480.
- **MSET-20** Every size in that list says under its name what it costs, when the catalogue knows
  the model's bytes for each token: "About {memory} of memory when full, and about {time} to read it
  all again.", or for a model without known reading times "About {memory} of memory when full."
  The memory is the size times the model's bytes for each token (MODEL-04), in the platform's short
  form for an amount of bytes. The time is what the model's reading times (MODEL-04) give for the
  size, by the arithmetic of FMT-20, rounded up to whole seconds. Below 90 seconds it is said in
  seconds, "{n} seconds" (for one, "{n} second"); from there on, below 5,400 seconds (an hour and a
  half), in minutes rounded to the nearest, "{n} minutes" (the form for one, "{n} minute", exists
  and is never produced, because the least is 2); from 5,400 seconds on in hours rounded to the
  nearest, "{n} hours" (the form for one, "{n} hour", exists and is never produced, because the
  least is 2). Because reading slows as more has been read (ENG-30), the time grows much faster
  than the size. With 5,500,000,000 bytes of memory and the reading times of MODEL-04 the times
  for Qwen3.5 are: automatic (7,168) 120 seconds, "about 2 minutes"; 4,096, "about 64 seconds";
  8,192, 139 seconds, "about 2 minutes"; 16,384, 327 seconds, "about 5 minutes"; 32,768, "about 14
  minutes"; 65,536, "about 41 minutes"; 111,616, 5,988 seconds, "about 2 hours" (the last four
  extrapolated beyond the last measured length, 15,872). For Peacebell: automatic (9,216) 108
  seconds, "about 2 minutes"; 4,096, "about 36 seconds"; 8,192, 90 seconds, "about 2 minutes";
  16,384, "about 4 minutes"; 20,480, "about 6 minutes". Both figures come from the catalogue and from one
  phone, which is why the sentences say "about"; the engine itself decides with what the loaded
  model reports and with the reading times scaled to its own measurement (ENG-30, ENG-31).
  - **On Android:** `Formatter.formatShortFileSize`, which writes the amount in the user's locale
    with its unit, such as 50 MB.
- **MSET-21** A model whose context cannot grow on this device has nothing to choose, and one
  sentence under the heading instead. When the size it has is all the model was trained with
  (Boris-2, with 2,048): "This model reads at most {n} tokens of a conversation at once; that is the
  model's own limit. Beyond it, the oldest messages are left out of an answer." When the device's
  memory is the reason: "On this phone this model reads at most {n} tokens of a conversation at
  once; the phone does not have the memory for more. Beyond it, the oldest messages are left out of
  an answer."
- **MSET-22** A size is stored the moment it is chosen and applies from the next answer (MSG-19).
  Automatic is the absence of a stored size (MSET-17). A fixed size is the user's own decision: it
  is held to the model and to the device's memory, and not to the reading time that bounds the
  automatic size (ENG-29). A size smaller than the context already is makes the next answer leave
  out what does not fit (ENG-30). Only the sizes of the list can be chosen. A stored size that is
  not in the list (the build or the device was another when it was stored) stands for the largest
  size of the list below it, or for the smallest when there is none; what the page shows as chosen
  and what the engine is given are both that size.

## 16. Security settings (SECSET)

**Source:** `lock/SecuritySettings.kt`, `settings/SettingsScreen.kt`,
`security/ScreenshotAllowance.kt`; tests `lock/SecurityViewModelTest.kt`.

- **SECSET-01** The Settings screen is titled "Settings", has a back button described as "Back",
  and holds three groups under the headings "Models" (MSET-02), "Security" and "About"
  (ABOUT-01). The Security group holds, in this order, the items below.
- **SECSET-02** The button "Change passphrase" (PASS-15).
- **SECSET-03** The switch "Unlock with fingerprint", with the line "Your fingerprint unlocks the
  encryption key through the phone's secure hardware. Turning this on or off needs your
  passphrase." (BIO-03, BIO-06, BIO-15).
- **SECSET-12** Right after it, the choice "Fingerprint works for": "15 minutes", "1 hour" (the
  default), "8 hours" or "24 hours" (BIO-18), with the line "The fingerprint unlocks the app
  for this long after it locks. After that, or after the phone restarts, the passphrase is
  needed." It is shown whether or not fingerprint unlock is on, and is built and protected as
  SECSET-04 is: the choice needs no passphrase and is stored in the lock configuration, because
  it is needed while the app is locked; if it cannot be stored, the choice shows the stored
  value again and the group says "That did not work: the phone's key store or storage reported
  an error." A new choice applies to the period that is running.
- **SECSET-04** The choice "Lock after": "Immediately", "1 minute", "5 minutes" or "15 minutes",
  with the line "Time in the background before the app locks. The time starts when the phone
  reports that the app has left the screen, a fraction of a second after you leave it: if you
  come straight back within that moment, the app is still open, also with Immediately. It is
  always locked after a restart." The choice needs no passphrase and is stored in the lock
  configuration, because it is needed while the app is locked. If it cannot be stored, the
  choice shows the stored value again and the group says "That did not work: the phone's key
  store or storage reported an error."
- **SECSET-13** Right after it, the choice "Delete messages after": "1 day", "7 days" (the
  default), "30 days", "90 days" or "Never", with the line "Messages older than this are
  deleted from this phone whenever the app runs, also while it is locked. A question and its
  answers go together, up to a day early (3 hours with 1 day)." (CHAT-22). No passphrase is
  asked for. The setting itself is stored encrypted with the settings, and a readable copy in
  the lock configuration (FMT-06) for what is deleted while the app is locked (STORE-22); the
  copy is written first, and if it cannot be written nothing changes, the choice shows the
  stored value again and the group says "That did not work: the phone's key store or storage
  reported an error." A choice that deletes messages now (a shorter limit, or any limit after
  "Never") first asks: title "Delete older messages now?"; text "Messages older than {limit}
  will be deleted now, and from then on every message when it reaches that age. A chat left
  without messages is deleted. This cannot be undone."; buttons "Delete" (in the colour for
  errors) and "Cancel", which leaves the setting as it was. Any other choice applies at once.
  The default of 7 days is only ever taken from settings that were read and do not name a
  limit. When the limit cannot be known from the settings (there is no settings file, it cannot
  be read, or the stored value is not one of the choices), the readable copy in the lock
  configuration is the limit for everything the unlocked app does and shows; that copy is not
  changed by it, and the settings get the limit back with their next change. So a settings
  file that cannot be read never deletes what the user chose to keep.
- **SECSET-14** Right after it, the switch "Background maintenance", on by default, with the
  line "Lets the app wake for a moment every few hours, also after the phone restarts, to delete
  what has expired. It never unlocks anything and never loads a model. Off: messages are only
  deleted when you open the app." (BG-12). No passphrase is asked for. It is stored in the lock
  configuration (FMT-06), because it is needed while the app is locked; a file without the
  field means on. Turning it on schedules the job and turning it off cancels it; if the setting
  cannot be stored, the switch shows the stored value again and the group says "That did not
  work: the phone's key store or storage reported an error."
- **SECSET-05** The switch "Finish the answer after locking" (section 6), on by default and
  stored in the lock configuration. The line under it says, when on: "On: if the app locks by
  itself while an answer is being written, the screen locks as always and the answer is
  finished behind it. Until it is finished, for at most 10 minutes, the encryption key stays in
  memory; then it is erased. Lock now always stops the answer and erases the key at once." And
  when off: "Off: when the app locks, an answer that is being written is stopped and the
  encryption key is erased at once. Turn this on to let an answer be finished behind the lock
  screen, for at most 10 minutes."
- **SECSET-06** The button "Lock now" (LOCK-16).
- **SECSET-07** The switch "Allow screenshots for 10 minutes" (PRIV-02). No passphrase is asked
  for: the app is unlocked, and the allowance opens no key. The line under it says:
  - off: "Screenshots and screen recordings are blocked.";
  - on: "On until {time}. It also ends when the screen turns off or the app is closed. While it
    is on, screenshots and screen recordings can capture your chats; the preview in the app
    switcher stays hidden. The lock screen and anything that asks for your passphrase are never
    captured.", with the time as a time of day in the user's own format;
  - where it cannot be offered (PRIV-07), with the switch disabled: "Needs Android 13 or later.
    On this version of Android the preview in the app switcher cannot be hidden by itself, so
    screenshots stay blocked."

  The switch follows the allowance itself and turns off on the screen at the moment the
  allowance ends. If the allowance could not be given (PRIV-08), the group says "Screenshots
  could not be allowed: the phone did not let the app watch for the screen turning off."
- **SECSET-08** A line of text in place of three switches that do not exist: "The app's content
  is always hidden in the app switcher, and the keyboard is always asked not to learn from what
  you type." There is no switch for the lock itself either.
- **SECSET-09** A place for messages of this group: the failures above, PASS-17, and "The
  passphrase was changed."
- **SECSET-10** The action "Reset app data", in the colour for errors, with the line "Deletes
  every chat and setting and your passphrase, then starts again with first-time set-up."
  (RESET-05).
- **SECSET-11** The texts of SECSET-05 and SECSET-07 take their number of minutes from the code
  and have a singular form for a limit of one minute. No build shows the singular forms of the
  two texts of SECSET-05, because that limit is 10 minutes: they are the same two sentences with
  "minute" in place of "minutes", and they are the only wording of the app that this document
  does not quote in full.

## 17. Screen capture, clipboard, keyboard and overlays (PRIV)

**Source:** `ScreenSecurity.kt`, `security/ScreenshotAllowance.kt` (`WindowSecurity`),
`MainActivity.kt`, `ui/SecureInput.kt`, `ui/UiSupport.kt`, `ui/SelectionMenu.kt`,
`ui/ObscuredTouches.kt`, `ProsePlowApp.kt`, `app/src/main/AndroidManifest.xml`; tests
`security/ScreenshotAllowanceTest.kt`, `SourceRulesTest.kt`, `ui/ObscuredTouchesTest.kt`,
`ui/SelectionMenuTest.kt`, and the instrumented tests in `app/src/androidTest/`.

- **PRIV-01** By default nothing the app shows can be captured: not in a screenshot, not in a
  screen recording, not through screen sharing. That holds from the first frame of every start,
  before anything is known about the lock.
  - **On Android:** the window flag `FLAG_SECURE`.
  - **Parity note:** a platform may offer no way for an app to refuse a screenshot or a
    recording. The property is that chat text and anything typed cannot be captured unless the
    user has allowed it for a while; where it cannot be had, that has to be said, and the
    stricter parts (PRIV-03, PRIV-04, PRIV-07) should still be provided as far as the platform
    allows.
- **PRIV-02** The user can allow screenshots and recordings of the unlocked app for 10 minutes
  (SECSET-07). The allowance ends at the earliest of: its time being up; the device's screen
  turning off; the user switching it off; a reset; the end of the app's process. Giving it again
  while it runs starts the full time afresh.
- **PRIV-03** An allowance only ever opens the unlocked app. Wherever the app is not unlocked
  (first-time set-up, the lock screen, both failure screens) the screen cannot be captured,
  allowance or not. Locking does not end an allowance: it has no effect while the app is locked
  and applies again after an unlock within its time, as long as the process is the same one
  (END-03).
- **PRIV-04** The dialogs of the unlocked app in which a passphrase is typed can never be
  captured, whatever is allowed: they are secure by a setting of their own and do not follow the
  screen under them.
- **PRIV-05** When an allowance ends while the unlocked app is open, every dialog and menu that
  was opened during it is built again, so that it does not stay capturable until it is closed: a
  dialog opens again under the secured screen, a menu is closed, and nothing the user was doing
  is lost. If the app cannot be seen at that moment (the screen turned off, or the app is in the
  background) this is done when it is shown again.
- **PRIV-06** Nothing about an allowance is ever stored: it exists in memory only, so there is
  nothing in any file that could turn screenshots on, and every start of the app is blocked
  again. Its time is counted on the device clock, so changing the date does not stretch it. A
  timer ends it when it is due; because a timer can be late, the deadline itself is checked
  whenever the app comes to the front and whenever it is decided whether the screen may be
  captured.
- **PRIV-07** The preview that the list of recent apps shows of the app is always hidden. No
  allowance covers it, because that picture is shown without the user doing anything.
  - **On Android:** from Android 13 the preview is switched off by itself
    (`setRecentsScreenshotEnabled(false)`). Before that only the secure window hides it, so on
    Android 12 and earlier the window stays secure and the allowance is not offered.
- **PRIV-08** The screen turning off is watched before anything is allowed. If that cannot be
  set up, nothing is allowed.
  - **On Android:** a receiver for the screen-off broadcast, registered with the application
    (not with a screen, so it works while no screen exists) and not exported.
- **PRIV-09** Every piece of text that is copied out of the app is marked as sensitive, which
  keeps it out of the preview the platform shows after a copy. That holds for the copy buttons
  and for Copy and Cut in the menu of a text selection alike, in dialogs too: every clip passes
  through one wrapper around the clipboard. If the wrapper cannot be put in place, the app stops
  at its first frame rather than let clips out unmarked. The mark only hides the preview: what
  is copied can still be read by the keyboard and by the app it is pasted into.
  - **On Android:** `ClipDescription.EXTRA_IS_SENSITIVE`, which Android 13 and later honour.
- **PRIV-10** Every text field in the app asks the keyboard not to learn from what is typed
  ("incognito" in several keyboards). It is not a setting and nothing turns it off. A keyboard
  may ignore the request.
  - **On Android:** `IME_FLAG_NO_PERSONALIZED_LEARNING`, added to every field's editor
    information, for the fields of dialogs as well.
- **PRIV-11** Nothing in the app is offered to, or saved by, an autofill service: the whole
  screen and every dialog are excluded.
- **PRIV-12** Selecting text hands it to nothing outside the app. The assistance that gives a
  selection to the system's text classifier ("smart selection") is switched off for the whole
  process, and the menu of a selection is held to a list of what is known (Cut, Copy, Paste,
  Select all): the entries other apps register for selected text (Translate, Search and the
  like), the classifier's suggestions and autofill are left out, and an entry of a kind a later
  version of the text library adds is dropped until it has been looked at.
- **PRIV-13** A tap is discarded while another app's window covers the place that is tapped, on
  the app's screen and on each of its dialogs and menus, so that an overlay cannot pass a tap
  through to a button the user does not see. The system's own bars and the keyboard do not count
  as covering. An overlay of another app that covers the whole screen (a screen dimmer, a colour
  filter) therefore stops every tap while it is active, and the app says why: "Another app is
  drawing over Prose Plow, so taps are ignored. Close or pause that app to go on." The message
  is not repeated more often than once in 10 seconds, and it is not shown for a tap within 4
  seconds of one of the app's own pop-up messages, because those are overlays too.
  - **On Android:** `filterTouchesWhenObscured` on the root of the content and of every dialog
    and menu window.
  - **Parity note:** where the platform lets no other app draw over this one, there is nothing
    to do.
- **PRIV-14** Another app cannot place one of its screens into this app's task and so come up in
  place of the lock screen.
  - **On Android:** the one activity has an empty task affinity, which on Android 9 and 10
    takes away the documented way of "task hijacking"; Android 11 and later prevent it
    themselves. The activity is the only component the app exports. The background service is
    not exported, the app declares no broadcast receiver, and a library adds one receiver that
    other apps cannot call. No build contains the interface library's preview activity.
- **PRIV-15** What the app shows or stores as a technical detail of a failure never contains
  text of a conversation, a prompt or a key: it is the name of the kind of failure, a status
  code, or a place in the code.
- **PRIV-16** The app causes no network traffic through another process either. The library
  initialiser that would ask a system service for a downloadable emoji font is removed, because
  that request would be made by another process on the app's behalf, which the lack of a network
  permission does not prevent.

## 18. Background work and notifications (BG)

**Source:** `background/AnswerWork.kt`, `background/AnswerService.kt`,
`background/AnswerNotifications.kt`, `background/MaintenanceJob.kt`,
`background/MaintenanceSchedule.kt`, `security/AppLock.kt` (`startMaintenance`),
`MainActivity.kt` (`askForNotificationsOnce`), `ProsePlowApp.kt`,
`app/src/main/AndroidManifest.xml`; tests `background/AnswerWorkTest.kt`,
`background/MaintenanceScheduleTest.kt`, `security/AppLockTest.kt`, `AppSessionTest.kt`,
`SourceRulesTest.kt`.

- **BG-01** An answer goes on being written when the app is put in the background or the
  device's screen turns off. For that, the app keeps its process alive and the processor awake
  for exactly as long as there is something to finish.
  - **On Android:** a foreground service of the type "special use" (required from Android 14;
    no named type describes running a model on the device, and the manifest states the use),
    with a partial wake lock that is taken with a limit of 2 minutes and renewed every minute,
    so that it can never be left held. The service is started as an ordinary service while the
    app is in front and puts itself in the foreground the moment it is created; it is not
    started with `startForegroundService`, because a service started that way that fails to
    reach the foreground can only stop by crashing the app. It counts as running only from the
    moment the system confirms that it is in the foreground (a `startForeground` that returns
    without effect is a refusal) until it says, before leaving the foreground, that it ends.
    Each time it renews its wake lock it asks the system whether it is still in the
    foreground, and ends if it is not.
- **BG-02** The keep-alive begins when an answer begins to be written while the app is in front
  (the platform lets it be started from nowhere else). It ends by itself at the moment no answer
  is being written any more and no session is still ending behind the lock: it is held until the
  data key has been overwritten, so that the process cannot be put to sleep on its way there.
  If it was refused or lost while an answer is still being written, it is asked for again when
  the app comes back to the front.
- **BG-03** If the platform refuses the keep-alive, does not grant it although it raised no
  error, or the user has restricted the app's background use, the answer is written all the
  same, as far as the system lets the process run, but it is not finished behind the lock: the
  app's own lock stops it (LEASE-01, LEASE-09). A refusal is recorded at once, so nothing goes on
  counting on a keep-alive. A keep-alive that the platform stops while an answer is being
  finished behind the lock ends that session at once (LEASE-03).
- **BG-04** While the keep-alive runs, a notification says "Writing an answer…" and nothing
  else. It is silent, is marked as ongoing and carries no badge. In the system's settings for
  the app's notifications its kind is named "Answer in progress". It is gone with the
  keep-alive.
- **BG-05** The keep-alive does nothing itself: the answer is written by the unlocked session.
  It takes no commands, cannot be started by another app, and is never restarted by the system:
  if the process dies, the answer died with it and the next start of the app is locked.
- **BG-06** When the model has finished an answer by itself (it ended its turn, reached a stop
  string or reached its length limit) while the app is not in front, a second notification says
  "Your answer is ready."; its kind is named "Answer ready". It is not shown for an answer that
  was stopped or that failed, it is taken away when the app comes to the front, and it goes away
  when tapped.
  - **On Android:** two notification channels, the first of low importance and the second of
    the default importance.
- **BG-07** Each notification is one fixed sentence. No word of an answer, no title of a chat
  and no name of a model ever goes into one; there is no second line, no action button and no
  picture. The code that builds them has no access to a chat, and a test that reads the sources
  fails if a notification is ever given a text with something filled in.
- **BG-08** Tapping either notification opens the app as its icon does: at the lock screen if it
  is locked.
- **BG-09** The app asks once whether it may show notifications: at the first answer that is
  started while the app is on screen. That it asked is recorded, in the encrypted settings and
  before the question is shown, so it is never asked a second time, whatever the answer was.
  Without the permission everything else works the same; the two notifications are simply not
  shown.
  - **On Android:** the question exists from Android 13; on earlier versions nothing is asked
    and the notifications are shown.
- **BG-10** Out of sight the platform gives the app fewer and slower processor cores, and the
  engine adapts its threads by itself (NAT-03). On the phone that was tested, reading the
  conversation into the model was about a quarter slower in the background, and writing the
  answer was as fast as in front.
- **BG-11** The app asks for five permissions: four for finishing an answer out of sight (the
  foreground service, its type, notifications, and keeping the processor awake), and one that
  keeps the background maintenance job scheduled across restarts of the device (BG-12, DEC-43).
  Libraries add three more: two for the use of biometrics, and one private permission of the
  app's own that guards its internal receivers. The network is not among them (BUILD-01).
  - **Parity note:** computing for minutes while the app is not on screen, or while the screen
    is off, is restricted on some platforms. The properties to preserve: an answer the user
    asked for is not lost by leaving the app if the platform allows it to go on; the user can
    see that the app is working; and a notification never says anything about the content.

- **BG-12** While "Background maintenance" is on (SECSET-14), a job runs about every 6 hours,
  also after the device restarts, in which the app deletes what has expired without the key
  (STORE-22). It is scheduled at every start of the lock while the switch is on, so that an
  update or a schedule that was lost puts itself right; a job that is already scheduled as it
  should be is left as it is, so the next run is not put off. Turning the switch off cancels it,
  and with no passphrase set up (after a reset) it is cancelled too: then nothing of the app runs
  at start-up or in the background. The job never unlocks anything, never touches a key, never
  loads a model and shows no notification.
  - **On Android:** the platform's job scheduler (no library): one periodic job of 6 hours,
    persisted across restarts (which needs `RECEIVE_BOOT_COMPLETED`), run by a job service that
    is not exported and that only the system may bind (`BIND_JOB_SERVICE`). The platform
    decides when within each period a run comes, may put runs off or stop them for an app that
    is not used, cancels the job when the app is force-stopped, and runs nothing before the
    device's first unlock after a restart, because the app's files are not readable until then.
- **BG-13** A run that starts the app's process: the process starts and the lock starts as it
  always does (LOCK-22), which runs the clean-up under the lock's own serialisation. The run
  waits for that start to end and does nothing more, so the clean-up is not done twice. A run
  in a process that was alive already (the app is open, or a session is alive behind the lock)
  asks the lock for the same clean-up, which waits its turn behind the lock's other work; with a
  session alive it only stores the trusted time (CHAT-24).
- **BG-14** A run holds the process until its work is done: until then the process is not ended
  (END-01). Then the platform is told the job is finished, and the hold is let go only after
  that has reached the platform, never in the same step; then the process is looked at again,
  so it ends exactly as it would have without the job (locked and out of sight, it ends at
  once). Ending the process first would make the platform see a job die unfinished, count it
  as failed and run it again sooner. A run the platform stops early, or one still going when its
  service is taken down, is not reported as finished and lets go of the process at once; a run
  holds it at most once and lets go of it at most once.
  - **On Android:** `jobFinished` only queues the platform's own message on the main thread;
    the hold is let go from a message posted to the same thread after it, so the platform's
    message is handled first. No delay is involved, only the order of the queue.
- **BG-15** Opening the app during a run changes nothing for either: the lock screen comes up
  as at any start, a passphrase is checked once the lock's start (and its clean-up) is done, and
  the process is not ended while the app is on screen.

## 19. The inference engine (ENG)

**Source:** `core/src/main/kotlin/us/theworkmans/proseplow/core/engine/` (`LlamaEngine.kt`,
`InferenceEngine.kt`, `GenerationEvent.kt`, `ContextBudget.kt`, `ContextPolicy.kt`,
`PromptTokeniser.kt`, `ReplyAssembler.kt`, `StopStringMatcher.kt`, `Utf8Assembler.kt`,
`EngineError.kt`, `NativeLlama.kt`), `DeviceMemoryGauge.kt`, `background/MemoryPressure.kt`; tests
in `core/src/test/kotlin/us/theworkmans/proseplow/core/engine/`,
`background/MemoryPressureTest.kt`.

The engine is plain code above a narrow native interface: every decision about a reply is made
here, and the native side only carries out single operations (section 20). The behaviour below
is what a port has to reproduce, whatever it is written in.

- **ENG-01** The engine holds one loaded model and the memory of one conversation. Replies never
  overlap: a second request waits until the first has ended.
- **ENG-02** The engine assumes nothing about who is calling: no screen, no lifecycle, no
  particular thread. A reply is a stream of events that anything can consume.
- **ENG-03** The events of one reply, in order: notices about the size of the context, if it changed
  or could not change as needed (that it was made larger, with the old size, the new size and
  whether the conversation is read again, ENG-28; that it could not be made as large as needed, with
  the size that is now the limit and the reason, ENG-33); at most one notice that the oldest
  messages were left out (ENG-04); progress of reading the prompt, as tokens read of tokens in all,
  the first of which already counts what was still in memory; pieces of text; a replacement of the
  text when the model took something back (ENG-17); and exactly one end, which is either "done" with
  the reason, or a failure. Nothing follows the end.
- **ENG-04** When the conversation and the room reserved for the reply do not fit together into the
  model's context even at the limit that context may grow to (ENG-29), the oldest whole turns are
  left out, and no more of them than needed. Below the limit the context is made larger instead, and
  nothing is left out (ENG-28). A turn is a message of the user with everything up to the next
  message of the user. The system prompt is always kept, and so is the last turn: the latest message
  of the user and anything after it, such as a partial answer that is being continued. The room
  reserved for the reply is the longest reply asked for, but never more than half of that limit and
  never more than 4,096 tokens (FMT-16).
- **ENG-05** When only the last turn is left and it still does not fit with the full reserve, it is
  sent as long as at least 16 tokens remain for a reply (or the longest reply asked for, if that is
  less), and the reply then gets whatever room is left. If not even that remains, no reply is begun
  and the request fails as "too long", with the number of tokens the shortest possible prompt needs
  and the limit of the context (MSG-14).
- **ENG-06** The conversation is rendered again from the start of what is sent for every
  request; the model's memory is never shifted or edited in place.
- **ENG-07** The engine reuses what the model's memory already holds. It keeps a copy of the
  tokens in that memory; the longest common beginning of that copy and the new prompt stays, and
  only the rest is read. So a next turn reads only what is new, answering again goes back to
  just before the reply, an edit goes back to the common beginning, and showing another
  conversation keeps only what the two have in common. At least the last token of the prompt is
  always read afresh, because the model's prediction only exists after reading.
- **ENG-08** Removing the end of the memory is not possible for every model (NAT-08). When the
  model refuses, the engine goes back to the nearest saved checkpoint at or before the point it
  needs, and reads on from there; with no such checkpoint it clears the memory and reads the
  whole prompt.
- **ENG-09** Checkpoints of the model's memory are saved while a prompt is read, at the
  beginnings of the last messages and of the turn the model is about to write: at most 4 are
  kept, the latest ones, which covers a new turn, answering the last answer again, and editing
  either of the last two messages of the user. Checkpoints beyond a point the conversation was
  cut at are dropped. A place inside a piece of text is only used if the tokeniser really puts
  a boundary between two tokens there, which is checked, not assumed. A native side that cannot
  save a checkpoint is tolerated; it costs time, never correctness.
- **ENG-10** If the native memory and the engine's copy of it ever disagree in length, the
  memory is cleared and rebuilt.
- **ENG-11** The part of the prompt that is not in memory is read in chunks, with a progress
  report after each chunk and a chance to stop between chunks. The prompt is read in parts that
  end at its checkpoints (ENG-09); a part that leaves fewer than 1,024 tokens in memory is read in
  chunks of 32 tokens, and a part that ends at or beyond 1,024 tokens in the model's chunk of a deep
  read (MODEL-04): 128 tokens for a model with recurrent state (Qwen3.5, Boris-2), the session's
  whole batch of 256 for a model with attention only (Peacebell, Shard-2). The reason: every chunk
  costs time in proportion to the tokens already in memory, on top of its tokens' own cost, so small
  chunks make a long conversation slow to read (on the tested phone, with 7,168 tokens read, a token
  cost Qwen3.5 33.7 ms in chunks of 32 and 19.3 ms in chunks of 128, Peacebell 48.7 ms in chunks of
  32 and 14.5 ms in chunks of 256; with nothing read the chunk makes no difference). A model with
  recurrent state can be stopped only between chunks (NAT-07), so it gets the smaller chunk.
- **ENG-12** The reply is sampled token by token with the model's sampling values, or with the
  request's in their place, and always with the model's banned tokens. The sampler is set up
  once at the start of every reply and told where the reply began for the penalties: where
  sampling begins, or for a continued answer where its content begins in memory (FMT-15). No
  bundled model fixes the random seed, so a new one is drawn for every reply and no reply is
  reproducible.
- **ENG-13** For each sampled token, in this order: if it is the model's deletion token, it
  takes back the token before it (ENG-17); otherwise, if the model file marks it as ending a
  turn or the catalogue lists it as a stop token, the reply ends, and that token is neither
  shown nor put into the memory; otherwise its bytes are added to the reply. Then: a stop string
  in the text ends the reply; reaching the limit ends it, and the last token is then not put
  into the memory; otherwise the token is put into the memory and the next one is sampled. The
  limit is the longest reply asked for, or the room left in the context if that is less.
- **ENG-14** The text of a reply is put together from the bytes of its tokens in three steps
  that must happen in this order (FMT-17):
  1. Bytes become whole characters. A character spread over several tokens is emitted once,
     whole; bytes that cannot become a valid character are replaced by the replacement character
     and decoding carries on; a character that was never completed when the reply ends is
     dropped.
  2. Stop strings are looked for in the text exactly as the model wrote it. An ending of the
     text that could still become the beginning of a stop string is held back until more text
     settles it; the stop string itself is never emitted, in whole or in part. Text that was
     held back and never became a stop string is delivered when the reply ends.
  3. Whitespace at the very start of a new reply of a chat model is dropped. Whitespace at the
     end is delivered as written.
- **ENG-15** A request can add stop strings of its own to the model's, and the end of the reply
  says which one stopped it. The app adds none today; the ability exists for TOOL-05.
- **ENG-16** A request can ask for the last message, a partial answer, to be continued instead
  of a new reply. The prompt then ends inside that message, with no end marker and no new turn,
  and the model carries on from its last character. The events carry only the continuation. Its
  leading whitespace is kept, because there it is a real space between two words. The presence
  and frequency penalties count the whole answer, not only the continuation (FMT-15). Continuing
  an empty answer is the same as a new reply.
- **ENG-17** For a model with a deletion token: the token is put into the model's memory like
  any other and counts towards the reply's limit. What changes is the reader's view: the last
  token of the visible reply is taken back and the three steps of ENG-14 are run again over the
  tokens that remain, so a deletion can take back half of a character (the character goes, and
  can be completed differently), the end of a possible stop string, or the space that was
  dropped from the start. Deletions in a row take back one token each and stop at the start of
  what this generation wrote. The catalogue's word that a token is the deletion token goes
  before what the model file says about it.
- **ENG-18** Abandoning a reply (no longer listening to its events) stops it promptly: within
  one token while it is being written, at the next chunk while the prompt is being read (NAT-07
  says when the native side can stop sooner). The engine stays usable. A request that is
  abandoned before it started touches nothing.
- **ENG-19** The engine never throws a failure at its caller; a reply ends with one of these
  failures instead, which the chat maps to the texts of MSG-14: no model loaded; another model
  loaded than the request names; the model could not be loaded; the request cannot be rendered
  (no message of the user, a continuation of something that is not a reply, a conversation the
  model's own template rejects); the prompt is too long (ENG-05); the memory ran out although
  the budget said it would fit; the native layer failed (with its status code, NAT-12); anything
  unexpected. After a native or unexpected failure the memory is cleared and the engine is ready
  for the next request. An unexpected failure is described by its kind and its place in the
  code, never by its message.
- **ENG-20** Loading reports progress from 0 to 1 and ends either ready or failed; it does not
  throw. Loading the model that is loaded does nothing. Loading another model frees the first
  and ends a reply in flight as cancelled. Abandoning a load leaves the engine without a model.
- **ENG-21** "Forget" drops everything the engine and the native layer remember of any
  conversation and returns when that is done; the model stays loaded. A reply in flight ends as
  cancelled, with its text so far; a reply that was still waiting its turn when forgetting was
  asked for never runs. Forgetting cannot be abandoned half-way, and it does not wait for a
  consumer of events that has stopped listening. If the native memory cannot be cleared, the
  model is freed, which clears it for certain. The app calls this at every lock (LOCK-17).
  A context that had grown is back at the size it was opened with afterwards (ENG-35).
- **ENG-22** A reply stands aside while a passphrase key is being derived (PASS-11): it pauses
  between two chunks of the prompt or between two tokens of the reply, never inside a
  computation (so while a long conversation is read, the chunk in progress, up to 128 or 256 tokens
  by ENG-11, is finished first: on the tested phone about 1.4 to 2.5 s for 128 tokens of Qwen3.5,
  and more the longer the conversation) (where its worker threads would keep the processor as busy as computing does),
  and goes on from where it was when the derivation has returned, whatever its result. A paused
  reply has not ended and loses nothing. While paused it asks at least every 25 milliseconds
  whether it should end, so stopping it, locking and forgetting all work during a pause. The
  time spent paused is left out of the speeds of ENG-23.
- **ENG-23** For every reply the engine computes the size of the prompt in tokens, how many of
  them were reused, how many tokens were written, the time for each part, how many messages were
  left out and how many deletions there were. The app shows none of this anywhere.
- **ENG-24** A system prompt that is blank is not sent. A model that was trained without a
  system message is given no system text of any kind, whatever the request holds.
- **ENG-25** A piece of text handed to the tokeniser in one call may be at most 262,144 bytes of
  UTF-8 (NAT-06). Before anything is tokenised, the oldest whole turns (as ENG-04 counts them) are
  left out until every piece of the rendered prompt is within that limit, and this is reported as
  any shortening by ENG-04 is; ENG-04 then shortens further if the context needs it. For the
  formats that tokenise each message on its own a piece is one message, so an older message beyond
  the limit is left out with its turn. For the plain-text ChatML format (Boris-2), where the whole
  conversation is one piece, the conversation sent is the longest run of the latest turns whose
  rendered text is within the limit. Only a last turn whose own text is beyond the limit (a single
  message of more than 262,144 bytes, in any format, the text-continuing one included) still fails
  the request with the native failure of MSG-14.
- **ENG-26** The size of the context is read back from the native side once the model is loaded and
  after every change of that size, not assumed from the catalogue or from what was asked for.
- **ENG-27** Every native call is made from one dedicated thread, one at a time.
- **ENG-28** The context follows the conversation. A model is opened with a context of its starting
  size (MODEL-04; ENG-36 for a device that cannot hold that). Before every reply the engine fits the
  conversation to the limit of the context (ENG-29), not to the size it has. If the prompt and the
  room reserved for the reply (FMT-16) then need more than the context has, the context is made
  larger before anything is read: to the smallest of its steps that holds them. The steps are the
  starting size, twice that, four times, and so on, with the limit itself as the last one; the step
  that is needed is taken at once, without those between. A consumer is told with an event that
  carries the old size, the new size and whether the conversation is read again (ENG-34).
- **ENG-29** The limit of a context is the smallest of the following that apply (FMT-20 has the
  arithmetic):
  - what the model was trained with: the smaller of the catalogue's figure and what the model file
    itself reports (the two differ for Shard-2, MODEL-21);
  - what the device's memory allows: as many tokens as a quarter of the device's memory holds at the
    model's bytes for each token, in whole multiples of 1,024, and never less than the starting
    size;
  - when the request names no size (the model's page says "Automatic"): the largest whole multiple
    of 1,024 tokens that the model reads from nothing within 120 seconds by its reading times
    (ENG-30), and never less than the starting size;
  - when the request names a size (MSET-22): that size, which then takes the place of the reading
    time;
  - a limit the engine has run into since the model was loaded (ENG-33).

  The reasons. The memory of a context is allocated in full when the context is created, whether or
  not a conversation fills it, so the largest context a model could have is not simply created. And
  once the app was locked and left, its process has ended (END-01) and the next answer in a
  conversation reads all of it again; left to itself the app does not let that wait grow beyond
  about two minutes, and a longer one is the user's to choose.

  With 5,500,000,000 bytes of memory and the figures of MODEL-04 the limits are 7,168 tokens for
  Qwen3.5 left to itself (the time decides: 7,168 tokens take 120 seconds, 8,192 take 139) and
  111,616 at most by choice; 9,216 for Peacebell left to itself (the time decides: 108 seconds,
  10,240 take 126) and 20,480 at most by choice (the memory decides); and 2,048 for Boris-2.
- **ENG-30** Reading slows as a conversation gets longer: for every token it reads, the model
  looks at every token already read, so a token costs more the more tokens are in memory, and the
  time to read a conversation grows much faster than its length. The reading times behind the
  automatic limit are therefore a curve: the catalogue's, measured on the tested phone (MODEL-04),
  scaled by a factor the engine has measured on this device. After every prompt of which at least
  256 tokens had to be read, taking the memory from `d0` to `d1` tokens in `t` seconds (without the
  time spent standing aside, ENG-22, and without the fitting and growing before it), the factor is
  `t` divided by what the catalogue's curve gives for reading from `d0` to `d1`, held between 0.25
  and 4; the latest measurement replaces the earlier one, and until there is one the factor is 1.
  Because the comparison is made at the depth the prompt was read at, a short prompt read deep in a
  long conversation does not make the device look slower than it is. A model whose catalogue entry
  has no reading times uses a constant speed instead, the tokens it last read divided by the time,
  and stays at its starting size when left to itself until it has read such a prompt. A context that has
  grown is not cut back by a slower measurement: while the request names no size, the limit is never
  below the size the context has, so a conversation keeps what it was given until its memory is
  cleared (ENG-35). A size the request names holds at once, also below what the context has: the
  conversation is then fitted to it and what does not fit is left out (ENG-04).
  - **Parity note:** the catalogue's reading times are those of one Android phone, with the engine
    reading in the chunks of ENG-11. A port needs curves measured on its own devices for the time
    before the first measurement, and for what the settings show (MSET-20).
- **ENG-31** The device is asked before every step. Its memory is read at the moment a step is
  decided, never remembered from an earlier one: how much the device has, how much of it is counted
  as available now, the amount at which the system itself considers memory low, and whether it does
  so now. The context's memory may then occupy at most the budget: the smaller of a quarter of the
  device's memory, and what the context holds now plus what is available above the floor. The floor
  is the larger of a fifth of the device's memory and twice the system's own low-memory amount. A
  step that costs more than the budget is not asked for; the largest step that fits it is taken
  instead, if there is one (ENG-33). What a size costs is asked of the loaded model (ENG-37). The
  budget counts what the tokens of a context take (the model's bytes for each token times the
  size). The native side counts more: what a loaded model reserves whatever the size of its
  context, most of which is never touched. That part is the same before and after a step, so it is
  taken from the model's own figure for the size it has and added to the budget, and the sum goes
  to the native side with the step, which keeps the context's memory within it at every moment of
  the change. A loaded model that cannot say what a context costs is not asked for another size:
  the size it has is its limit (ENG-33).
  - **On Android:** `ActivityManager.getMemoryInfo`: the fields `totalMem`, `availMem`, `threshold`
    and `lowMemory`, read afresh for every decision.
  - **Parity note:** a platform may not say how much memory is available to all apps, or may only
    say how much this app may still take, and it may end an app that takes too much instead of
    refusing the allocation. The properties to preserve: no step is taken that the device is not
    known to have room for at that moment; room is left for everything else on the device; and the
    decision is made with a fresh reading.
- **ENG-32** While the system says that memory is low, nothing is added to a context. That is so
  when the reading of ENG-31 says it, and for 60 seconds, on the device clock, after the system has
  told the app so. The context then stays as it is for that reply, the oldest messages are left out
  beyond it (ENG-04) and the consumer is told (ENG-33). Unlike a step that could not be taken, this
  is not remembered as a limit: the next reply after the pressure has gone makes the context larger
  as usual.
  - **On Android:** `onLowMemory`, and `onTrimMemory` at the levels `TRIM_MEMORY_RUNNING_LOW`,
    `TRIM_MEMORY_RUNNING_CRITICAL`, `TRIM_MEMORY_MODERATE` and `TRIM_MEMORY_COMPLETE`, both received
    by the application object. The levels `TRIM_MEMORY_RUNNING_MODERATE`, `TRIM_MEMORY_UI_HIDDEN`
    and `TRIM_MEMORY_BACKGROUND` do not count: the first says that memory is only beginning to get
    short, and the others speak of the app's own place and not of memory. Since Android 14 a running
    app is no longer told most of these levels, which is why the reading of ENG-31 is the guard and
    these callbacks only add to it.
  - **Parity note:** a platform's memory warning takes the part of these callbacks. The property to
    preserve: for a while after a warning the app takes no more memory.
- **ENG-33** When a step cannot be taken, the size the context has becomes its limit. When the
  budget of ENG-31 does not hold the step that is needed, the context grows as far as the budget
  allows, which may be not at all, and the size it then has is the limit for that model until it is
  loaded again. The same holds when the native side refuses a step, and when a larger context could
  not be created although the budget held it: the native side then sets up the previous size again,
  with what the model had read if that could be saved, or failing that a smaller size (ENG-37), and
  whatever exists is the limit. If no context exists at all any more, the model counts as not loaded
  and the reply fails as in ENG-36. A consumer is told with an event that carries the limit and the
  reason, which is either too little memory available or a context that could not be created; the
  event is sent once for a limit, not with every reply that runs into it. The conversation is then
  fitted to the limit (ENG-04).
- **ENG-34** What a step costs. The model's memory of the conversation is carried into the larger
  context, with its checkpoints (ENG-09), when the native side can do that within the budget; only
  what is new is then read (ENG-07). When it cannot, the memory comes back empty and the whole
  conversation is read again; the event of ENG-28 says which. Either way at least one token is read
  before the first token of the reply is sampled, because no prediction survives the change. Reading
  after a step is reading like any other: it reports its progress, stops between chunks when the
  reply is stopped or the app locks, and stands aside for a key derivation (ENG-11, ENG-22). The
  sampler and the threads are as they were.
- **ENG-35** The memory of a grown context is given back by clearing the model's memory, which
  returns the context to the size it was opened with. That happens:
  - at every lock, with "forget" (ENG-21, LOCK-17);
  - when a reply is asked for in a conversation that fits the starting size while the context is
    larger, and at most 1,024 of the tokens in memory could have been used for it; those are then
    read again. That is the case for another conversation, which shares only its beginning with the
    one in memory;
  - whenever the memory is cleared for another reason: the model cannot go back to the point a new
    prompt needs (ENG-08), or a reply failed (ENG-19).

  A conversation that only became shorter (its latest message was edited, say) keeps its context and
  what was read of it until the next lock: reading thousands of tokens again would cost the user
  more than the memory is worth until then. A limit found by ENG-33 stays in force across all of
  this.
- **ENG-36** Opening a model is held to the quarter of ENG-31 alone: the tokens of a context of the
  starting size, at the catalogue's bytes for each token, must fit a quarter of the device's memory.
  What is available at that moment, the floor and the system's report of low memory (ENG-32) play
  no part in opening; they bound growth only. The system makes room for the app in front by ending
  others, and a model refused at opening leaves the user with no model at all, while a refused step
  of growth only caps the conversation. If the tokens do not fit, half the
  size is taken, and half again, as long as the half is at least 1,024 tokens. Below that the model
  is not loaded: loading ends as failed for the reason "too little memory available", which the chat
  words on its own (MODEL-14, MSG-14). The engine decides this itself, before the native side is
  asked for anything, and gives the native side no narrower budget for the opening: what a model
  reserves beyond its tokens is the same at every size and not known before it is loaded. Should
  the native side refuse a size all the same, it is asked for half in the same way. A context
  opened smaller is the starting size for everything else in this section.
- **ENG-37** What the engine asks of the native side for all this: the context the model was trained
  with; the bytes a context of a given size takes; and a change of size within a budget, which has
  three outcomes. Done: the context has the size asked for, the memory was kept with its checkpoints
  or is empty, and no prediction is ready. Refused before anything was touched (status 9): the size
  is above the trained context or costs more than the budget. Not created after the old context was
  given up (status 10): the previous size has been set up again, with its memory if that could be
  saved, or failing that ever smaller sizes, and the size read back says what exists, 0 for nothing.
  Opening takes the starting size and a budget, and answers a size beyond the budget with status 9
  without creating anything; the engine states no budget there (ENG-36). Clearing the memory returns
  the context to the size it was opened with.
  To the sampler the engine says explicitly that the penalties have no window (FMT-15), so that they
  cover the whole memory whatever size the context has.

## 20. The native session (NAT)

**Source:** `native/README.md` ("The generation session", "Forgetting", "On the phone"),
`native/session/include/proseplow_session.h`, `native/session/src/session.cpp`,
`native/lib/include/proseplow_native.h`,
`llama/src/main/kotlin/us/theworkmans/proseplow/llama/CpuTopology.kt`, `NativeEngine.kt`,
`native/patches/`, `native/llama-ext/`; tests `native/session/tests/`, `native/tests/`,
`llama/src/test/`, `llama/src/androidTest/`.

Nothing under `native/` needs Android: it is plain C and C++ over llama.cpp, built and tested on
an ordinary desktop machine, so the same code can be compiled for another platform.

- **NAT-01** The native session is one loaded model with one context holding one conversation,
  driven call by call. It decides nothing: it never renders a prompt, looks for a stop string,
  shortens or shifts the context, chooses a sampling default or retries.
- **NAT-02** It is built on llama.cpp at a pinned commit with four small patches, of which only
  the core library is built and linked (BUILD-02). The project adds three model architectures
  that llama.cpp does not have (`peacebell`, `boris2`, `shard2`) and, for the Peacebell
  tokeniser, a front-end that reproduces the model's reference tokeniser exactly and keeps the
  chat markers typed in a message as ordinary characters.
- **NAT-03** Threads. One thread runs on each of the fast cores of the processor, pinned to it,
  when the system allows that; otherwise a smaller number of threads runs without pinning. The
  fast cores are those whose capacity, as the kernel ranks it, is at least 60% of the largest,
  up to 6 of them; without pinning there is one thread fewer than fast cores, and never fewer
  than 2; when the capacities cannot be read, the count is two fewer than the processors, kept
  between 2 and 4. Which of the two arrangements applies is decided again before every chunk of
  a computation, because the platform moves a background app to other cores in the middle of a
  long prompt (BG-10). This is a choice of the implementation; what must hold everywhere is that
  two computing threads never share one core, which was measured to slow a prompt by more than
  ten times.
- **NAT-04** The compute back end is built once for each generation of processor instructions,
  and the best one for the device's processor is chosen when the engine first starts.
- **NAT-05** Only the processor is used: no layer is given to a graphics processor.
- **NAT-06** Every size that arrives from outside is held to a limit before anything is
  allocated for it, and a value beyond its limit comes back as a failure, not as a crash:

  | Size | Limit |
  | --- | --- |
  | Context | the context the model was trained for (2,048 tokens for Boris-2, 32,768 for Peacebell, 262,144 for Qwen3.5); below that, a budget in bytes that the caller states: the session computes what a context of the size costs and refuses it before anything is allocated when it costs more (NAT-16) |
  | Tokens read in one go | 32,768; the app uses 256 |
  | Text tokenised in one call | 262,144 bytes |
  | Checkpoints held at once | 8; the engine keeps 4 |
  | Banned tokens | the size of the vocabulary |
  | A model inside a larger file | its header and every tensor must end within the length given |

- **NAT-07** Stopping. For a model whose memory is attention only (Peacebell, Shard-2) a
  computation that is under way stops within about a millisecond, and the interrupted chunk is
  taken out of the memory again. A model with recurrent state (Qwen3.5, Boris-2) cannot give an
  interrupted chunk back, so it is only asked between chunks and a stop waits for the chunk in
  progress: at most about half a second on the phone that was tested for a chunk of 32 tokens, and
  for the chunk of 128 tokens of a deep read (ENG-11) about 1.4 s with nothing read and about
  2.5 s with 7,000 read for Qwen3.5, more the longer the conversation. A lock waits for it too.
- **NAT-08** Removing the end of the memory. Peacebell can. Qwen3.5 and Boris-2 cannot: they
  answer "no" with the memory untouched, and a checkpoint is restored instead. Shard-2 can,
  except after a checkpoint has been restored and the cut would need more of its window than is
  still held; the session then answers "no" itself rather than produce wrong predictions.
- **NAT-09** A checkpoint is the part of the model's state that cannot be recomputed by cutting,
  kept in native memory with the length it belongs to: next to nothing for Peacebell, 381,700
  bytes for Boris-2, about 20 MB for Qwen3.5, up to about 8.4 MB for Shard-2. After a restore at
  least one token has to be read before sampling, because a checkpoint holds state, not
  predictions.
- **NAT-10** Sampling follows a fixed order and computes the penalties itself, so that each
  penalty covers what it covers where the model's settings come from (FMT-15). The sampler is
  never asked to choose among nothing: when every candidate has been ruled out, or a score is
  not a number, sampling fails with a status instead of naming an arbitrary token. A temperature
  below one millionth selects the most-likely-token path.
- **NAT-11** Boris-2 cannot be given its own end-of-text token as input (its computation would
  stop the process); the session refuses that token with a status and leaves the memory alone.
- **NAT-12** A failure is one of ten status codes, shared with the engine and never
  renumbered: 1 load failed, 2 load cancelled, 3 tokenising failed, 4 context full, 5 evaluation
  failed, 6 sampling failed, 7 out of memory, 8 invalid argument, 9 context refused, 10 context
  not created (ENG-37). A short technical description
  goes with it and never contains text of a conversation.
- **NAT-13** Forgetting a conversation frees the context, the token batch and the sampler and
  creates them again, the context at the size the model was opened with whatever it had grown
  to, after overwriting what the session can reach of the conversation: every checkpoint, the
  scores of the last prediction, the candidate list, the tokens in the batch, the session's own
  record of the tokens in memory, and the key-value and state buffers of the memory. The model
  stays loaded. The allocator is then asked to give its free pages back. On the phone that was
  tested this took between 15 and about 160 milliseconds, depending on the model, with contexts
  of the starting size; a context that has grown has more to overwrite (on a desktop: Qwen3.5
  27 ms at 4,096 tokens and 104 ms at 65,536, Peacebell 105 ms at 4,096 and 189 ms at 16,384).
  Changing the size of the context (NAT-16) overwrites and frees in the same way. MEM-04 says
  what it does not reach.
- **NAT-14** Nothing in the native code logs or prints text, token ids or pieces. llama.cpp's
  own log is written nowhere: the session takes from it only the name of the compute back end
  that was loaded, the description of a failure and the sizes of the buffers of a context, none
  of which contains text of a conversation.
- **NAT-15** Closing a model frees everything after the same overwriting, and the memory it used
  is given back to the system.
- **NAT-16** The size of the context can change while the model stays loaded. The native layer
  says what the model's largest context is and what a context of a given size costs in memory
  (llama.cpp's own figures for the buffers it allocates, read when a context is created), and
  changes the size when asked, within a budget in bytes. A size above the model's largest, or
  whose cost is above the budget, is refused with nothing touched (code 9). Otherwise the old
  context is overwritten and freed and the new one created; the memory is carried over when the
  budget also covers the block it is copied through, and comes back empty otherwise;
  checkpoints survive exactly when the memory does, and no prediction is ready afterwards. A
  size that cannot be created falls back to the previous size, then to halves (code 10). Sizes
  are whole multiples of 256 tokens, as llama.cpp rounds them. Not promised: surviving an
  allocation that the system grants and cannot back, where the budget is the protection, or a
  failure inside ggml's own bookkeeping allocation.

## 21. What stays in memory (MEM)

**Source:** `README.md` ("What stays in memory"), `native/README.md` ("Forgetting"),
`security/Envelope.kt`, `security/Passphrase.kt`, `AppSession.kt`, `core/.../engine/LlamaEngine.kt`,
`native/session/tests/test_residue.cpp`.

- **MEM-01** While the app is unlocked, the data key, the decrypted chats and settings, and the
  model's working memory of the conversation are in the app's memory. That working memory is the
  model's context, whose size follows the conversation (ENG-28): from the size it starts with up to
  a quarter of the device's memory (ENG-29).
- **MEM-02** When the session is closed (at a lock, or when an answer that was finished behind
  the lock has been saved), the data key's bytes are overwritten and the key refuses every
  further use; the engine overwrites its own copy of the conversation's tokens; and the native
  session overwrites its buffers and checkpoints before it frees them (NAT-13). The native
  binding overwrites its copies of what it was handed.
- **MEM-03** The loaded model stays in memory for as long as the process lives. Its weights are
  public.
- **MEM-04** Overwriting does not reach everything in the native library. llama.cpp makes copies
  of its own while it works and frees them without overwriting them: the text of every piece it
  tokenised, and the token ids of the prompt. Measured on the phone that was tested, after the
  clearing a lock makes, copies of the prompt's text were still readable in the process for two
  of the four models and copies of the prompt's token ids for all four, some of them even after
  the model had been closed. The token ids of replies were not found.
- **MEM-05** Nor does it reach what the language runtime holds: strings cannot be overwritten at
  all, and chat text passes through strings in the text fields, the Markdown renderer and the
  JSON codec; the cryptography classes keep copies of keys inside short-lived objects; the text
  fields keep buffers of what was typed. All of that stays until the memory is reused or the
  process ends.
- **MEM-06** A passphrase is kept out of strings as far as possible (PASS-14). Two residues
  remain: the buffers of the text field it was typed into, which is emptied but not the app's to
  overwrite; and, only for a passphrase that had to be normalised (PASS-03), one string that the
  normaliser returns. A passphrase whose characters are all below U+0300, which includes every
  ASCII and Latin-1 passphrase, is never handed to the normaliser.
- **MEM-07** Because of MEM-04 and MEM-05 the process is ended once the app is locked, has
  nothing left to finish and is not on screen (END-01); with the process every one of those
  copies is released. Until then (the lock screen is still being looked at, or an answer is
  being finished behind the lock) they remain.
- **MEM-08** While an answer is being finished behind the lock, everything of MEM-01 is still in
  memory, the data key included, for at most 10 minutes and then the closing, which is bounded
  by three waits of up to 10 seconds each (LEASE-03, LEASE-12).
- **MEM-09** Nothing is done about what the system may have moved to compressed swap.
- **MEM-10** Clearing the model's memory at a lock also returns a grown context to the size it
  started with (ENG-35), so the memory a long conversation took is given back to the device then.
  The phone's times in NAT-13 were measured with contexts of the starting size; a context that has
  grown has more to overwrite, and NAT-13 gives the times measured on a desktop.

## 22. About, licences and notices (ABOUT)

**Source:** `settings/SettingsScreen.kt`, `settings/AboutScreen.kt`,
`settings/ThirdPartyNoticesScreen.kt`, `settings/ThirdPartyNoticesText.kt`,
`settings/ThirdPartyNoticesViewModel.kt`, `app/notices/`, `fastlane/metadata/android/en-US/`;
tests `settings/ThirdPartyNoticesTextTest.kt`, `ThirdPartyNoticesTest.kt`.

- **ABOUT-01** The "About" group of Settings shows the app's name and version, as "Prose Plow
  {version}", and the button "About and licences".
- **ABOUT-02** The About screen is titled "About" and shows, in this order:
  - "Prose Plow {version}";
  - "Runs language models on this phone. Chats are stored encrypted on the device and are never
    sent anywhere.";
  - the heading "Models in this build", and for each model the build offers: its name, its
    description, the row that opens its model card (MSET-15) and "Licence: {licence}". A model
    whose licence is not recorded would show "Licence: not stated yet". A build without models
    says "This build contains no model.";
  - the heading "Open-source software and models", the text "The models are run by llama.cpp.
    The app is built with it and with other open-source software, and carries the models listed
    above. The notices name every one of them, with its copyright lines and the text of its
    licence.", and the button "Third-party notices".

  The text of the screen can be selected and copied.
- **ABOUT-03** The screen "Third-party notices" shows the whole text of the notices: every
  library, model and piece of artwork in the app that is someone else's work, with its copyright
  lines, and the text of every licence once. The text is part of the app and the same for every
  user. It is read in the background when the screen is opened; if it cannot be read, the screen
  says "The notices could not be read." in place of an empty page.
- **ABOUT-04** The notices are written for a page of eighty columns and are laid out for the
  width of the screen without changing them: no word is added, dropped, changed or moved, which
  a test holds every licence text to.
  - The first part, the project's own list of components, has one paragraph on each line, and
    every line is shown as a paragraph.
  - After the heading `PART 2. LICENCE TEXTS` come the licence texts with the line breaks their
    authors gave them; the lines of each paragraph are joined again so that they do not break a
    second time on a narrow screen. A line carries on the one before it when it begins with a
    small letter (and is not an address), or when the line before it reached column 56; a blank
    line, a dividing line, a heading and the start of a list entry begin something new.
  - A line directly above a row of `=` or `-` is a heading; such rows are dividing lines, and
    two in a row are one. Every heading has a dividing line above it.
  - A paragraph keeps its indentation (up to a limit suited to a narrow screen), and lines that
    were centred on the page (twenty or more leading blanks) stay centred and are not joined.
  - Nothing scrolls sideways, all of it can be selected, and only what is on the screen is laid
    out.
- **ABOUT-05** The notices cover every model a build can contain; the About screen lists the
  models of this copy.
- **ABOUT-06** The app itself is licensed under the Apache License 2.0.
- **ABOUT-07** How the app describes itself where it is listed: the name "Prose Plow"; the line
  "Small language models that run on the phone, offline, with encrypted chats"; and a longer
  description in `fastlane/metadata/android/en-US/full_description.txt`, which names the models
  with their licences, the size of the package, and the limits of what the lock protects.

## 23. Accessibility (A11Y)

**Source:** the screens under `chat/`, `lock/`, `settings/` and `ui/` (their `semantics`
blocks), `ui/SecureInput.kt`; tests `SourceRulesTest.kt`,
`app/src/androidTest/.../ui/PassphraseFieldAccessibilityTest.kt`.

- **A11Y-01** Every button that shows only an icon has a spoken description; the descriptions
  are quoted with the buttons throughout this document ("Open the list of chats", "New chat",
  "More options", "Send", "Send the edited message", "Stop", "Copy message", "Edit message",
  "Answer again", "Copy code", "Cancel editing", "Go to the latest message", "Delete {title}",
  "Back", and the two that empty a number field). Icons that only repeat the words beside them
  have none.
- **A11Y-02** Things that appear and matter are announced politely when they appear, without
  taking the focus: every notice and failure card; the messages under the fields of the set-up
  and lock screens; the messages of the security settings; the line that says a number entry is
  not valid; the line that says a message is being edited; the screen of a chat that cannot be
  opened; and the line that says a model is being loaded.
- **A11Y-03** A reply is not read out word by word while it streams. The line under it is
  announced when it moves from one stage to the next ("Loading the model…", "Reading the
  conversation…", "Writing…"), never for every step of a progress bar, and the newest reply is
  announced once, when it is complete.
- **A11Y-04** The line "This app uses AI, and AI can make mistakes." is never announced by
  itself, only when the user moves to it (CHAT-20).
- **A11Y-05** The titles of Settings and of a model's page, the headings of the groups in
  Settings and About, the heading "Context" on a model's page, and every heading of the notices
  are marked as headings, so a screen reader can move from one to the next.
- **A11Y-06** A row that is one action is read as one thing with what a tap does: a model's row
  in Settings ("open its settings"), the model card row ("open it in your browser"), and the
  title of the chat screen ("Switch model"). While the model picker is unavailable the title
  says why ("Unavailable while an answer is being written"), and in the picker the current model
  is reported as selected.
- **A11Y-07** A spinner that stands alone has a description ("Starting…", "An answer is being
  written"); a spinner beside words that say the same has none.
- **A11Y-08** Touch targets keep the platform's full size even where the icon is small. Text
  follows the user's font size; long lines wrap rather than being cut where their content
  matters (the AI notice, the buttons of a card), and titles that are cut end in an ellipsis.
- **A11Y-09** A passphrase field tells accessibility services one bullet for each character it
  holds and never the characters themselves, so a service the user has enabled cannot read a
  passphrase out of the field, while a screen reader still announces a password field and how
  much is in it. A test on a device looks at the field the way such a service does.
- **A11Y-10** An accessibility service that the user has enabled can otherwise read what is on
  the screen, chats included (THREAT-12).
- **A11Y-11** The sizes of MSET-19 are one group of radio buttons. Each row, its name together with
  its cost, is one radio button that reports whether it is selected, and the whole row is the touch
  target.

## 24. Guarantees of the build (BUILD)

**Source:** `app/build.gradle.kts`, `llama/build.gradle.kts`, `llama/apk-checks.gradle.kts`,
`llama/src/main/cpp/CMakeLists.txt`, `native/CMakeLists.txt`, `models/manifest.json`,
`app/src/test/java/us/theworkmans/proseplow/SourceRulesTest.kt`, `ThirdPartyNoticesTest.kt`,
`README.md` ("Build from source"), `native/README.md`.

These are properties a build of the app is held to by the build itself or by tests. A port needs
its own means for each of them.

- **BUILD-01** The build fails when the merged list of permissions contains anything outside a
  fixed list: the five the app asks for (BG-11), the two the biometric library adds, and one
  private permission a support library declares for the app's own internal receivers. The
  network permission is not on the list. The check is a step of the build and not a task beside
  it: what is packaged is the very list the check handed on, so nothing can be built or
  installed from a list that did not pass. The reason: a dependency can add a permission
  unnoticed. What a component requires of others (the job service's `BIND_JOB_SERVICE`) is not a
  permission of the app and is not on the list.
- **BUILD-02** No native library that carries a network client is linked. Of llama.cpp only the
  core library is built; its helper library contains an HTTP client and a downloader, and the
  build fails if that target so much as exists.
- **BUILD-03** A model gets into a package only as the pinned file: the packaging step compares
  its SHA-256 with `models/manifest.json`, and every package that is written is checked again on
  the bytes as they are inside it. Any model file that the manifest does not mark as bundled,
  anywhere in the package, fails the build.
- **BUILD-04** Every finished package is also checked for what the native code depends on: each
  model stored uncompressed on a 16 KiB boundary, every native library aligned for 16 KB pages,
  and no native library of an unknown name.
- **BUILD-05** The third-party notices reach the app only through a step that first compares the
  list of libraries with what the build really packages, in both directions and version by
  version. A test holds the models in the notices to the bundled ones, llama.cpp and the tool
  chain to the pinned versions, and the text to the list and the licence files word for word. A
  bundled model without an entry fails.
- **BUILD-06** Tests that read the sources hold the whole app to these rules:
  - no logging call anywhere in the app's code, so chat text, prompts and key material can
    never reach a log;
  - nothing decrypted in the state the platform saves for the screens: the chat, settings and
    storage code may save flags and scroll positions only;
  - the lock and encryption code knows neither the screens nor the application, and its core has
    no dependency on the platform; the stores know neither the screens nor the platform; the
    plain core module does not depend on the app;
  - what decides whether the screen can be captured is carried out in exactly one place, and no
    dialog opts out of it except the passphrase dialog, which is always secure;
  - a passphrase field is only ever used where the screen cannot be captured, never shows a
    typed character, and replaces what it tells accessibility services;
  - every clip leaves through the clipboard that marks it as sensitive;
  - touches under another app's overlay are discarded on the screen and in every dialog and
    menu;
  - the selection menu keeps its four entries and smart selection stays off;
  - the two notifications are fixed sentences, and the code that builds them cannot reach a
    chat;
  - the process is ended in one place and never in a test run;
  - every key derivation has the model stand aside, and never longer than the derivation;
  - the lock's own bookkeeping is only reached one caller at a time.
- **BUILD-07** The native libraries are reproducible: built three times from nothing, in two
  different places, they were identical byte for byte, and none contains a path of the build
  machine. Versions of every tool and library are pinned, nothing is fetched while building
  beyond the declared dependencies, and the package carries no hidden list of its dependencies.
- **BUILD-08** Everything is built from source. There are no prebuilt binaries and no
  proprietary kits in the app.
- **BUILD-09** Model conversion is deterministic and pinned: the manifest names the exact
  revision of each source repository and the SHA-256 of every file that is read, and the
  converted file must have the pinned SHA-256. A checkpoint that is a Python pickle (Shard-2) is
  only ever loaded in the mode that constructs weights and refuses everything else, and the code
  published with it is never run.
- **BUILD-10** A release build contains no development aid: the code that looks for a model file
  placed by a developer is not compiled into it (MODEL-23).
- **BUILD-11** The tests that run on a device use hardware keys under a name space of their own
  and run inside the development build only, so they can never see, replace or delete the keys
  or the data of a real installation.

## 25. Exact formats and rules (FMT)

**Source:** `security/Envelope.kt`, `security/LockConfig.kt`, `security/LockManager.kt`,
`security/KeyVault.kt`, `security/Passphrase.kt`, `security/AppFiles.kt`, `AppSession.kt`,
`data/SealedFile.kt`, `data/SessionStore.kt`, `data/SettingsStore.kt`,
`core/.../core/chat/SessionCodec.kt`, `core/.../core/chat/PromptHistory.kt`,
`core/.../core/prompt/`, `core/.../core/model/ModelCatalog.kt`, `core/.../core/engine/`,
`core/src/test/resources/golden/`, `models/manifest.json`; tests
`security/EnvelopeTest.kt`, `security/MandatoryLockTest.kt`,
`core/.../core/chat/SessionCodecTest.kt`, `core/.../core/prompt/GoldenPromptTest.kt`,
`native/session/tests/test_penalties.cpp`.

This section is exact where the others describe. Each item carries one of three marks:

- **Must match**: the behaviour of a model, or the meaning of what the user set, differs if an
  implementation deviates. A port has to reproduce it exactly.
- **Adopt**: a stored form that is frozen on Android. Nothing moves data between devices
  (STORE-19), and the wrapped data key cannot be opened away from the device it was made on
  (KEY-03), so no installation ever reads another's files today. A port should adopt the same
  forms all the same, so that there is one specification and one set of test vectors and a later
  way of moving data needs no migration; departing from one is a decision to take deliberately.
- **Free**: a choice of this implementation that a port may make differently, as long as the
  behaviour described elsewhere holds.

### Encryption

- **FMT-01** *(Adopt)* Every encrypted blob has this layout, and nothing else is ever written
  into an encrypted file:

  | Bytes | Content |
  | --- | --- |
  | 3 | The magic `50 50 45` (the ASCII letters `PPE`) |
  | 1 | The format version, `01` |
  | 12 | The nonce, from the system's random generator, new for every write |
  | the length of the plaintext | The AES-256-GCM ciphertext |
  | 16 | The GCM authentication tag (128 bits) |

  The additional authenticated data is the first four bytes followed by the UTF-8 bytes of
  `proseplow:` and the purpose (FMT-02). A blob shorter than 32 bytes, without the magic, with
  another version, or that does not authenticate is "not decryptable"; none of these is ever
  repaired or guessed at. The magic, the version byte and the prefix `proseplow:` are
  permanent.
- **FMT-02** *(Adopt)* Purposes. A purpose names what a blob is and is authenticated with it. It
  is a name space, or a name space, a colon and an id; a name space is a lower-case letter
  followed by lower-case letters, digits and hyphens, and contains no colon. In use:
  `settings`; `session-index`; `session:` followed by the chat's id. The name space `dek` is
  reserved for the lock (`dek:passphrase`, FMT-04) and refused to every store. A purpose is
  permanent once data has been sealed with it.
- **FMT-03** *(Adopt)* Names of files. A chat is `sessions/<id>.enc`, where the id is the text
  form of a random (version 4) UUID; an id is valid as a file name if it matches
  `[A-Za-z0-9_-]{1,64}`. A kept copy (STORE-09) is `kept/<name>.<digest>.enc`, where the name is
  `settings`, `session-index` or `session-` followed by the id, and the digest is the first 8
  bytes of the SHA-256 of the copied bytes as 16 lower-case hexadecimal digits. The temporary
  file of a write is the file's name with `.tmp` appended.
- **FMT-04** *(Adopt; the last step is device-specific, see KEY-03)* The passphrase key and its
  wrap:
  1. The passphrase is normalised to Unicode Normalisation Form C and encoded as UTF-8 (text
     that cannot be encoded is replaced as the encoder replaces it).
  2. `stretched` = PBKDF2-HMAC-SHA256 of those bytes with the stored salt (16 random bytes) and
     the stored iteration count (600,000), 32 bytes long.
  3. The passphrase key = HMAC-SHA256, keyed with the binding key of the stored generation, over
     the ASCII bytes of `proseplow passphrase key v1` followed by `stretched`. It is 32 bytes.
  4. The data key (32 random bytes) is sealed as a blob of FMT-01 with the passphrase key and
     the purpose `dek:passphrase`: 64 bytes, stored as standard Base64 with padding.

  A passphrase is right exactly when that blob opens.
- **FMT-05** *(Adopt in form; the key is device-specific)* The fingerprint wrap is not a blob of
  FMT-01. It is the output of the hardware AES-256-GCM key (128-bit tag) over the data key, with
  the additional authenticated data `proseplow:dek:biometric:v1`, stored as Base64 of the
  12-byte nonce followed by the 32 bytes of ciphertext and the 16-byte tag: 60 bytes.
- **FMT-06** *(Adopt)* The lock configuration, `security/lock.json`, is one JSON object. Every
  field is written, defaults included. Fields a reader does not know are ignored.

  | Field | Type | Meaning | Must hold, or the configuration counts as damaged |
  | --- | --- | --- | --- |
  | `version` | number | The version of this format | present, and 1 |
  | `passphrase` | object | The passphrase wrap | present |
  | `passphrase.kdf` | string | The name of the derivation of FMT-04 | present, and `PBKDF2-HMAC-SHA256+keystore-HMAC-SHA256` |
  | `passphrase.salt` | string | Base64 of the salt | decodes to 16 to 64 bytes |
  | `passphrase.iterations` | number | The PBKDF2 iteration count | 600,000 to 10,000,000 |
  | `passphrase.wrappedKey` | string | Base64 of the blob of FMT-04 | decodes to 64 bytes that begin with the magic |
  | `passphrase.bindingGeneration` | number | The generation of the binding key | 1 to 999,999,999 |
  | `biometric` | object or null | The fingerprint wrap; null when fingerprint unlock is off | when not null, both fields below |
  | `biometric.wrappedKey` | string | Base64 of the wrap of FMT-05 | decodes to 60 bytes |
  | `biometric.keyGeneration` | number | The generation of the fingerprint key | 1 to 999,999,999 |
  | `lockTimeoutSeconds` | number, default 60 | LOCK-11 | one of 0, 60, 300, 900 |
  | `finishAnswerAfterLocking` | boolean, default true | SECSET-05; a file without the field means on | |
  | `failedAttempts` | number, default 0 | Wrong passphrases since the last success | not negative |
  | `lastFailureWallMillis` | number, default 0 | When the current wait started, by the date and time; a record only | not negative |
  | `lastFailureElapsedMillis` | number, default 0 | The same moment on the device clock; the one the wait is timed by | not negative |
  | `fingerprintInvalidated` | boolean, default false | Fingerprint unlock was removed because its key became unusable; offer it again (BIO-12) | |
  | `highestKeyGeneration` | number | The highest generation committed so far; generations are never reused | present, 1 to 999,999,999, and not below either generation in use |
  | `fingerprintPeriodSeconds` | number, default 3600 | How long the fingerprint opens the app after the period starts (BIO-18, SECSET-12); a file without the field means the default | one of 900, 3600, 28800, 86400 |
  | `fingerprintPeriodStart` | object or null, default null | When the period of BIO-18 last started; null means it has run out (BIO-21) | when not null, both fields below |
  | `fingerprintPeriodStart.elapsedMillis` | number | That moment on the device clock | not negative |
  | `fingerprintPeriodStart.bootCount` | number | The device's count of starts at that moment | not negative |
  | `messageLifetimeDays` | number, default 7 | The readable copy of SECSET-13, in days, 0 for "Never"; a file without the field means 7 | one of 1, 7, 30, 90, 0 |
  | `trustedTime` | object or null, default null | The trusted time of CHAT-24 as last recorded; null when none was, which a file without the field means | when not null, the fields below |
  | `trustedTime.wallMillis` | number | The trusted time, in milliseconds since 1970 | 0 to 253,402,300,799,999 (the end of the year 9999) |
  | `trustedTime.elapsedMillis` | number | That moment on the device clock | not negative |
  | `trustedTime.bootCount` | number or null | The device's count of starts at that moment; null when it could not be read | when not null, not negative |
  | `backgroundMaintenance` | boolean, default true | SECSET-14; a file without the field means on | |

  The hardware keys are named `proseplow.passphrase.binding.` and `proseplow.dek.biometric.`
  followed by the generation in decimal without leading zeros. Every key of the app begins with
  `proseplow.`, which is how a reset finds keys of a form it does not know. These names, like
  the label and the two purposes of FMT-04 and FMT-05, are permanent.
- **FMT-07** *(Adopt)* A chat file holds, encrypted, the UTF-8 text of one JSON object:
  `{"schemaVersion": 1, "session": {...}}`. Writing is complete: every field is written,
  defaults included.

  | Field of `session` | Type | Meaning |
  | --- | --- | --- |
  | `id` | string | The chat's id; must equal the id in the file's name |
  | `title` | string | CHAT-09; empty for a chat without messages |
  | `createdAtMillis`, `updatedAtMillis` | number | Milliseconds since 1970, by the trusted time (CHAT-24) |
  | `modelId` | string | The model of the replies to come |
  | `draft` | string | CHAT-15 |
  | `messages` | array | The conversation, oldest first |
  | `error` | object or null | The failure of the last request (MSG-14) |

  | Field of a message | Type | Meaning |
  | --- | --- | --- |
  | `id` | string | A random UUID, stable for the life of the message, also across an edit |
  | `role` | string | `USER` or `ASSISTANT` |
  | `text` | string | What was typed, or what the model wrote |
  | `status` | string | `STREAMING`, `COMPLETE` or `STOPPED` |
  | `finishReason` | string or null | `END_OF_TURN`, `STOP_STRING`, `LENGTH` or `CANCELLED`; null for a message of the user, while streaming and after a failure |
  | `modelId` | string or null | For a reply, the model that wrote it |
  | `createdAtMillis` | number | When the message was added, by the trusted time (CHAT-24); what it expires by (CHAT-22). 0, or missing, means not known (STORE-23) |

  | Field of `error` | Type | Meaning |
  | --- | --- | --- |
  | `kind` | string | `MODEL_NOT_LOADED`, `MODEL_LOAD_FAILED`, `INVALID_REQUEST`, `PROMPT_TOO_LONG`, `CONTEXT_FULL`, `NATIVE` or `UNEXPECTED` |
  | `detail` | string | The technical detail |
  | `userMessageId` | string or null | The message whose reply failed |
  | `promptTokens`, `contextSize` | number or null | For `PROMPT_TOO_LONG`: the two numbers of MSG-14 |

  Reading is as forgiving as it can be without inventing a conversation:
  - fields a reader does not know are ignored, in the chat and in every message;
  - only the schema version (a number of at least 1), the `session` object and a non-empty `id`
    are indispensable; any other field of the chat that is missing or of the wrong type takes
    its default (empty text, 0, the creation time for a missing update time, no messages);
  - messages are read one by one: one that cannot be read (a role the reader does not know, a
    missing id) is left out and counted, and the others are kept;
  - a status the reader does not know becomes `COMPLETE`, a finish reason it does not know
    becomes null, a failure of an unknown kind becomes `UNEXPECTED`, and a failure that cannot
    be read at all is dropped and noted;
  - a higher schema version is read by the same rules and noted;
  - anything left out or noted makes the chat "partly read" (CHAT-17), which also means a copy
    is kept before it is written over (STORE-09);
  - a reply that is `STREAMING` in the file becomes `STOPPED` when it is loaded, with its text.
- **FMT-08** *(Adopt)* The two other encrypted files.
  - The list of chats: `{"schemaVersion": 1, "index": {"activeId": ..., "sessions": [...]}}`,
    where `activeId` is the id of the chat that was on screen last (null when that was an empty
    chat) and each entry of `sessions` has `id`, `title`, `createdAtMillis`, `updatedAtMillis`,
    `modelId`, `messageCount` and `oldestMessageAtMillis` (the date of the chat's oldest message,
    or null; STORE-23). Unknown fields are ignored; a list that cannot be read is
    rebuilt from the chat files (STORE-11).
  - The settings: `{"schemaVersion": 1, "settings": {...}}`, where `settings` has `systemPrompts`
    (an object from model id to text), `models` (an object from model id to an object with the
    optional numbers `temperature`, `maxNewTokens` and `contextTokens`, the last being the size
    chosen for the context, MSET-22), the boolean `notificationsAsked` and the number
    `messageLifetimeDays` (SECSET-13, as in FMT-06). A value that is not set is not written, a
    model with nothing set has no entry, `notificationsAsked` is written only once it is true, and
    `messageLifetimeDays` only when it is not 7. On reading, unknown fields are ignored; a system prompt that is not text, a
    temperature that is not a number from 0 to 2, a longest reply that is not a whole number of at
    least 16 written as one, a context size that is not a whole number of at least 1,024 written as
    one, a flag that is not a boolean and a limit for messages that is not one of the choices are
    left out and counted; anything counted, or a higher
    schema version, makes the settings "partly read" (STORE-16); text that is not such an object is
    "unreadable".

### Prompts

- **FMT-09** *(Must match)* Rules that hold for every prompt format.
  - A prompt is a sequence of pieces. Each piece is tokenised by its own call, and the results
    are joined in order, so the border between two pieces is also a border between two tokens.
    No beginning-of-sequence, end-of-sequence or other token is ever added by the tokeniser; the
    formats place every such token themselves.
  - A piece is one of three kinds: ordinary text, tokenised so that text which merely spells a
    control token comes out as the tokens of its characters; marker text written by the format
    itself, tokenised so that the control tokens spelled in it become their single tokens; or
    one token given by its number. The content of a message always travels as ordinary text, so
    nothing a user types or a model quotes can close a turn or open one (with the one exception
    stated in FMT-12). An empty piece of text is tokenised to nothing.
  - Text reaches the tokeniser as standard UTF-8 bytes.
  - "Trimmed" means exactly what Python's `str.strip()` removes, which is these 29 characters
    and no others: U+0009 to U+000D, U+001C to U+001F, U+0020, U+0085, U+00A0, U+1680, U+2000 to
    U+200A, U+2028, U+2029, U+202F, U+205F and U+3000. Other languages' notions of whitespace
    differ from it.
  - The system text of a request is the model's system prompt; nothing else produces system
    text today (TOOL-03).
  - The authoritative examples are the files under `core/src/test/resources/golden/`: for each
    format a set of conversations with the exact text (or, for Peacebell, the exact sequence of
    injected ids and separately encoded texts) that the model's own published template or
    reference code produces. The three chat fixtures are generated from the models' own files at
    the pinned revisions by `generate_golden.py` in that directory. A port must pass the same
    cases byte for byte.
- **FMT-10** *(Must match)* Qwen3.5: ChatML whose markers are control tokens, as the model's own
  chat template renders a text-only conversation without tools and with thinking off. Fixture:
  `qwen35-chatml.json`. Written with `\n` for a line feed:
  - a system message: `<|im_start|>system\n` then the trimmed system text then `<|im_end|>\n`;
  - a message of the user: `<|im_start|>user\n` then the trimmed text then `<|im_end|>\n`;
  - an earlier reply: `<|im_start|>assistant\n` then the answer then `<|im_end|>\n`. The answer
    is the trimmed text; if it contains `</think>`, everything up to the last `</think>` is
    reasoning and is dropped, and the line feeds that follow that tag are removed (only line
    feeds; spaces stay);
  - a reply that comes after the last query of the user is laid out differently:
    `<|im_start|>assistant\n<think>\n` then its reasoning (trimmed; empty for this app) then
    `\n</think>\n\n` then the answer, and `<|im_end|>\n` unless it is the message being
    continued;
  - the opening of a new reply: `<|im_start|>assistant\n<think>\n\n</think>\n\n`, which is how
    the template switches thinking off;
  - a message being continued keeps its end exactly and loses only the whitespace at its start;
    continuing an empty answer is the opening of a new reply, token for token.

  "The last query" is the last message of the user whose trimmed text is not wrapped in
  `<tool_response>` and `</tool_response>`; a conversation without one is refused, as the
  template refuses it. Pieces: `<|im_start|>`, `<|im_end|>\n`, `<|im_start|>assistant\n<think>`,
  `</think>` and the whole opening of a new reply are marker text; everything between two
  markers is one single piece of ordinary text, never two (`\n\n` is one token, two `\n` are
  not). `<think>` and `</think>` are ordinary added tokens of this vocabulary and are matched in
  any text; what ordinary text keeps out is `<|im_start|>`, `<|im_end|>` and `<|endoftext|>`.
- **FMT-11** *(Must match)* Peacebell: ChatML whose two markers are injected as token numbers,
  as the model's own tokeniser class encodes a conversation. Fixture: `peacebell-chatml.json`.
  - a message: token 3, then the encoding of the role and a line feed (`user\n`,
    `assistant\n`), then the encoding of the content, then token 4;
  - the opening of a new reply: token 3, then the encoding of `assistant\n`;
  - a message being continued has no token 4 after it and no new turn.

  The role line and the content are two separate encodings and must stay two, because the
  tokeniser begins every call with a word-start piece. Nothing follows token 4: there is no line
  feed between messages. The format does not trim content (the rules of FMT-13 trim it before).
  An empty content is still its own, empty, encoding. The model was trained without a system
  message and is never given one (ENG-24).
- **FMT-12** *(Must match)* The two remaining formats.
  - Plain-text ChatML (Boris-2-Instruct), as the template in the model's tokeniser
    configuration renders it. Fixture: `plain-chatml.json`. Every message, the system message
    included, is `<|im_start|>` then the role then `\n` then the trimmed content then
    `<|im_end|>\n`; the opening of a new reply is `<|im_start|>assistant\n`; a message being
    continued is rendered without `<|im_end|>` and loses only the whitespace at its start. The
    whole conversation is one piece of ordinary text, tokenised in one call: the markers are
    plain characters that the tokeniser spells with several ordinary tokens. Two consequences:
    this format cannot tell a marker typed by the user from its own, which is a property of how
    the model was trained; and a reply ends on the marker's text, not on a token (FMT-14).
  - Text continuation (Shard-2). Fixture: `completion.json`, written by hand from the model's
    reference script. The prompt is the text of the latest message of the user, exactly as
    written, in one piece of ordinary text: nothing before it, nothing after it, nothing
    trimmed. Earlier turns and any system text are not part of it. A message being continued is
    that text followed directly by the partial answer.
- **FMT-13** *(Must match)* Which messages of a chat are sent, in order:
  - every message of the user, also one whose reply failed (two messages of the user in a row
    are possible and every format accepts them);
  - a reply only if it has text: a reply that failed, was stopped or ended without any text
    (or with nothing but whitespace) is left out, and so is the empty reply that is about to be
    written; a stopped reply that has text is part of the conversation;
  - a reply that is being continued is sent last, as the message to continue.

  For a chat model every text is trimmed (FMT-09), except that a reply being continued is
  trimmed at its start only. For a model that continues text nothing is trimmed anywhere.

### Tokens and sampling

- **FMT-14** *(Must match)* What ends a reply, and what is never produced:

  | Model | Ends a reply | Never produced | Takes back the token before it |
  | --- | --- | --- | --- |
  | Qwen3.5 0.8B | token 248046 (end of turn) or 248044 (end of text) | | |
  | Peacebell v1 291M | token 4 (end of turn) | token 0 | |
  | Boris-2-Instruct | the end-of-turn marker as text (a stop string), or token 0 (end of text) | | |
  | Shard-2 | token 1 or token 2 | | token 4096 |

  The end-of-turn marker is the ten characters of `<|im_end|>`. In addition, any token the model
  file itself marks as ending generation ends a reply. A token that ends a reply is neither
  shown nor put into the model's memory. A stop string is searched for in the text as the model
  wrote it (FMT-17).
- **FMT-15** *(Must match)* Sampling. The values of each model, which are the reference settings
  from the model's own published files:

  | | Qwen3.5 0.8B | Peacebell v1 291M | Boris-2-Instruct | Shard-2 |
  | --- | --- | --- | --- | --- |
  | Temperature | 1.0 | 0 | 0.7 | 0.9 |
  | Top-k | 20 | 50 | 50 | 100 |
  | Top-p | 1.0 (off) | 0.9 | 0.9 | 1.0 (off) |
  | Min-p | 0 (off) | 0 (off) | 0 (off) | 0 (off) |
  | Repetition penalty | 1.0 (off) | 1.0 (off) | 1.1 | 1.0 (off) |
  | Presence penalty | 2.0 | 0 (off) | 0 (off) | 0 (off) |
  | Frequency penalty | 0 (off) | 0 (off) | 0 (off) | 0 (off) |
  | Seed | new for every reply | new for every reply | new for every reply | new for every reply |

  A token is chosen in exactly this order:
  1. The penalties, on the raw scores. The repetition penalty covers every token in the model's
     memory, the prompt as much as the reply, each token once however often it occurs: a score
     below zero is multiplied by the penalty, any other divided by it. The frequency and
     presence penalties cover the tokens of the reply and no token of the prompt: the score less
     the frequency penalty times the number of times the token occurs in the reply, less the
     presence penalty if that number is not zero. "The reply" is what the memory holds from the
     length it had when the first token of this reply was sampled; for a continued answer
     ("Continue", ENG-16) it is the whole answer, from the first token of its content in the
     prompt (after the role line and, for Qwen3.5, after the empty thinking block; for a model
     that continues text, after the user's text), so what was written before the continuation
     counts too. When the tokenizer puts no token boundary where the answer's content begins, or
     the answer is empty, the reply is counted from where sampling begins. No window limits them: the whole memory counts. A token is
     covered from the moment it is in the memory and no longer once it has been removed from it.
  2. The banned tokens are given a score of minus infinity.
  3. With a temperature above 0: the temperature is applied; then top-k (unless it is 0); then
     top-p (unless it is 1 or more); then min-p (unless it is 0 or less); then the random choice
     with the seed.
  4. With a temperature of 0 (or below one millionth): the most likely token is taken, and
     top-k, top-p, min-p and the seed have no effect. The penalties and the banned tokens still
     apply.

  The order is the one the models' reference implementations use: the truncations see the
  distribution after the temperature. llama.cpp's own default order applies the temperature
  last, which gives a different result, and its built-in penalties give all three the same
  tokens to look at; neither is used. A temperature set by the user replaces the temperature in
  this table and nothing else.
- **FMT-16** *(Must match)* Budget. With `context` the limit of the model's context in tokens
  (FMT-20) and `asked` the longest reply (the user's value for the model, or the model's own):
  - `reserve` = the smallest of `asked`, half of `context` (rounded down) and 4,096;
  - `minimum` = the smaller of `asked` and 16;
  - the conversation may begin at its first message or at any later message of the user. The longest
    such conversation whose prompt, in tokens, plus `reserve` is not more than `context` is sent,
    and the messages before its beginning are the ones reported as left out;
  - if not even the conversation that begins at the latest message of the user fits with `reserve`,
    that one is sent provided its prompt plus `minimum` is not more than `context`; otherwise the
    request is too long (ENG-05);
  - the context is then made to hold the prompt plus `reserve`, or `context` if that is less
    (FMT-20), and the reply may have at most the smaller of `asked` and the size the context then
    has less the prompt's tokens. A deletion counts as a token of the reply; the token that ends a
    reply does not.

  The lengths are counted in tokens of the rendered prompt, system text included, never in
  characters. A model that continues text has no earlier turns to leave out. The longest reply a
  user can set is therefore from 16 tokens to the smaller of half of `context` and 4,096 (MSET-11).
- **FMT-17** *(Must match)* The text of a reply, from the bytes of its tokens (ENG-14): bytes are
  decoded as UTF-8 across token borders, with one replacement character (U+FFFD) for each
  longest invalid prefix and the bytes of an unfinished character dropped at the end; stop
  strings are matched in the text before anything is trimmed, the one that completes first
  winning and, of two that complete at the same character, the one that began earlier; then
  whitespace (FMT-09) at the start of a new reply of a chat model is dropped. When a reply is
  finished by the model or by a stop string, the whitespace at its end is removed before it is
  stored; in every other case, and always for a model that continues text, it is stored exactly
  (MSG-08).

### Model files

- **FMT-18** *(Must match)* The model files are GGUF files of 16-bit floats, converted from
  pinned revisions of their source repositories by the converters in this repository. The
  conversion is deterministic, and `models/manifest.json` is the authority for every hash,
  including the SHA-256 of each source file that is read:

  | Model | Source repository and revision | Bytes | SHA-256 of the converted file |
  | --- | --- | --- | --- |
  | Qwen3.5 0.8B | `Qwen/Qwen3.5-0.8B` at `2fc06364715b967f1860aea9cf38778875588b17` | 1,516,742,752 | `91bcb80bb68c2a51e7eb4e28cf088f79678e236f9f16120060b84a57ae7f0978` |
  | Peacebell v1 291M | `wayneworkman2012/peacebell-v1-291M` at `a44de191d94e8bf944b31a482fc0f04fa4433eb1` | 583,680,096 | `df565d191ad1506227f0fb645822ad0224538dcd3a540eb5497ab00ab8dc1868` |
  | Boris-2-Instruct | `opencerebral/Boris-2-Instruct` at `9f60d918ffeeaca15e3caf9d349d8ca34ea5cc7d` | 291,853,184 | `758d2df848b418064c3e0f7b640c99ff3cad2d8734341d8c4253abc74e6860c6` |
  | Shard-2 (not bundled) | `Glint-Research/Shard-2` at `46b9eb99c475694a1129ad8f3a9825d244a8ec79` | 99,048,160 | `c4b8e0fa3a9ef78a24708d5876fe56f6e4cafb44fd89049933bba562b140b67f` |

  Peacebell, Boris-2 and Shard-2 are architectures that llama.cpp does not have; they need the
  architecture files and patches of `native/`, and Peacebell also the tokeniser front-end
  (NAT-02). Against the 32-bit reference models the 16-bit files must stay within a mean KL
  divergence of 1e-5 (5e-5 for Boris-2) and agree on the most likely token at 99.5% of positions
  or more; the tests of `native/tests/` hold them to that and to the reference's greedy replies
  token for token.
- **FMT-19** *(Free)* How a model file is carried and opened (packaged inside the app, mapped
  from an offset, the fallback to reading it), the threads (NAT-03), the choice among processor
  variants (NAT-04), the size of a chunk of prompt (32 tokens, and from 1,024 tokens in memory on
  the model's chunk of a deep read, ENG-11; both change the reading times of FMT-20), the number of checkpoints (4),
  the delays of the writer (1 second for lazy writes, 10 seconds at a lock), the names of files
  inside the private storage as far as FMT-03 does not fix them, and every matter of layout and
  colour.
- **FMT-20** *(Must match)* The size of a context. All sizes are in tokens. `total` is the device's
  memory in bytes, `available` the memory counted as available now and `low` the amount at which the
  system considers memory low (ENG-31); `start` is the size the context was opened with, `trained`
  the smaller of the catalogue's and the model file's trained context, `bytes` the model's bytes for
  each token and `seconds(n)` the time its reading times (MODEL-04), scaled as ENG-30 says, give
  for reading `n` tokens from nothing:
  - the reading times are measured points (`n`, `s`) with both strictly increasing, and the point
    (0, 0) in front. Between two points `seconds` is interpolated on a straight line. Beyond the
    last point (`N`, `S`): with `c1` and `c2` the average seconds per token of the last two
    segments and `m1` and `m2` their middles, `g` = the larger of 0 and (`c2` - `c1`) / (`m2` -
    `m1`) (0 when there is only one segment), and `seconds(n)` = `S` + `c2` (`n` - `N`) + `g` / 2
    ((`n` - `m2`)² - (`N` - `m2`)²);
  - scaled by a factor `f`, every `s` is multiplied by `f`;
  - `cap` = a quarter of `total`; `floor` = the larger of a fifth of `total` and twice `low`;
  - `byMemory` = `trained` if `cap` holds that many tokens at `bytes` each, and otherwise the number
    of tokens `cap` holds, rounded down to a multiple of 1,024; in either case not less than the
    smaller of `start` and `trained`. With `bytes` not known, `byMemory` = `trained`;
  - `byTime` = the largest `n` with `seconds(n)` not more than 120, rounded down to a multiple of
    1,024, and not less than `start`; with no reading times known, `start`;
  - the limit with no size chosen = the smallest of `trained`, `byMemory`, `byTime` and a limit
    found by ENG-33, and then not less than the size the context has now; the limit with a size
    chosen = that size, held between 1 and `byMemory`, and not more than a limit found by ENG-33;
  - the steps = `start`, twice `start`, four times `start` and so on while below the limit, then the
    limit; only the limit when it is not above `start`;
  - the sizes offered for choosing (MSET-19) = the steps from `start` to `byMemory`; a stored size
    stands for the largest of them not above it, or the smallest (MSET-22);
  - the budget of a step, with `held` the bytes of the tokens of the context as it is (its size
    times `bytes`) = the smaller of `cap` and `held` + `available` - `floor`, and not less than 0.
    The bytes of a step that are held against it are those of its tokens (ENG-31);
  - the budget of an opening = `cap`, whatever `available`, `floor` and `low` are (ENG-36);
  - when a reply needs more than the context has, `needed` = the prompt's tokens plus `reserve`
    (FMT-16), or the limit if that is less. The target is the smallest step not below `needed`. The
    step taken is the largest step above the present size and not above the target whose bytes are
    not more than the budget; if there is none, or while the system reports low memory (ENG-32),
    none is taken.

  The quarter, the fifth, the factor of two, the 120 seconds and the 1,024 are the values of one
  policy that the engine and the settings share. For `byMemory` the engine takes `bytes` from what
  the loaded model reports for two sizes, and the settings take it from the catalogue; for a step
  the bytes of a size are what the loaded model reports for that size, less what it reports beyond
  the tokens of the size it has (ENG-31). What `total`, `available` and `low` are is the platform's
  to say.

## 26. Security model (THREAT, DEC)

**Source:** `README.md` ("Security", the whole section), the classes under `security/`,
`MainActivity.kt`, `ScreenSecurity.kt`, the tests named in sections 3 to 10 and 17, and
`SourceRulesTest.kt`. The reasons below are the ones the code, its tests and the README give.

Security is not one feature of this app but the reason for many of them. This section states
what the lock is meant to protect, what it does not protect, and each decision with its reason
as a property a port has to preserve. A port that provides the screens of sections 2 to 8
without these properties has not reached parity.

### What the app protects against

- **THREAT-01** Someone who holds the device, or a copy of its storage, without the passphrase
  and without an enrolled finger cannot read any chat, draft, title, setting or system prompt:
  they exist on disk only encrypted (STORE-04). The passphrase cannot be guessed away from the
  device, because every guess needs the device's own key store (KEY-03), and guesses on the
  device through the app are slowed down (PASS-06).
- **THREAT-02** Someone who looks at the device while the app is locked, at the list of recent
  apps, or at the notifications sees nothing of a conversation: the lock screen shows none, the
  preview is always hidden (PRIV-07), and the notifications are fixed sentences (BG-07).
- **THREAT-03** Other software that captures the screen (screenshots, recordings, screen
  sharing) gets nothing unless the user has allowed it for at most 10 minutes, and never gets a
  screen on which a passphrase is typed (PRIV-01 to PRIV-06).
- **THREAT-04** Other apps get no way in and nothing handed to them: the app has one entry
  point, takes no taps through overlays, gives no text to autofill, to the keyboard's learning,
  to the text classifier or to entries other apps put into the selection menu, hides what is
  copied from the clipboard preview, takes part in no backup and uses no network (PRIV-09 to
  PRIV-16, STORE-19).
- **THREAT-05** What a model writes cannot reach out by itself: images are never loaded, a link
  is only handed on after the user has seen its full address and confirmed it, and text that
  spells a chat marker cannot close or open a turn (MD-03, MD-05, FMT-09).
- **THREAT-06** After a lock the key is gone from memory, the model's memory of the conversation
  is cleared, and once the app is out of sight its process is ended, which releases every copy
  that could not be overwritten (LOCK-17, END-01).
- **THREAT-07** A build cannot quietly gain a network permission, a network client or a model
  that is not the pinned one (BUILD-01 to BUILD-03).

### What the app does not protect against

- **THREAT-08** Anyone who knows the passphrase and, while fingerprint unlock is on, anyone
  whose fingerprint is enrolled on the device.
- **THREAT-09** The unlocked app. Whoever holds the device while the app is unlocked reads
  everything, and the app stays unlocked in the background for the time the user chose. With
  "Finish the answer after locking" on, the lock screen shows, but for up to 10 minutes, and then
  the closing of LEASE-03, the data key and the conversation are in memory as if the app were
  unlocked; nothing on the screen
  gives access to them, but whoever can read the process's memory in that time finds them.
- **THREAT-10** A compromised device. Root access, malware with system privileges or an
  exploited system can read the app's memory while it is unlocked, record what is typed and
  capture the screen whatever the app asks for. The app relies on the platform's separation of
  apps.
- **THREAT-11** Guessing on the device itself by someone with root access. The count of wrong
  attempts is in a plain file and can be reset, and code that runs as the app can use the
  binding key; the 600,000 iterations can then be computed elsewhere in advance, so that a guess
  costs one operation of the key store. If the key store can be made to give up the binding key,
  the passphrase key is only as strong as PBKDF2 with 600,000 iterations. In both cases only the
  strength of the passphrase protects the data.
- **THREAT-12** The keyboard, the clipboard and accessibility services. A keyboard sees
  everything typed into it and may ignore the request not to learn. Text the user copies leaves
  the app and can be read by the keyboard and by the app it is pasted into. An accessibility
  service the user has enabled can read what is on the screen, chats included (but not a
  passphrase, PASS-13).
- **THREAT-13** Capture the user allowed, and capture from outside: during a screenshot
  allowance anything that can capture a screen can capture the unlocked app, and a camera
  pointed at the device is never prevented.
- **THREAT-14** Traces in memory after a lock, for as long as the process lives (MEM-04,
  MEM-05): the app ends the process only once it is out of sight and has nothing to finish, and
  cannot end it while a write to failing storage does not return. And whatever the system moved
  to compressed swap.
- **THREAT-15** An older copy of a file put back. The encryption proves what a file is, not that
  it is the newest version. Someone who can write the app's private storage, without knowing any
  key, can replace a chat or the settings with an older copy of the same file and can put back a
  chat that was deleted; the app accepts both without notice. An older lock configuration is
  accepted in the same way unless the passphrase was changed after it was written.
- **THREAT-16** Another app that imitates this one. The app cannot show the user that the screen
  in front is its own; an app that may draw over others can show a look-alike screen, or cover
  everything except one button. Discarding covered taps (PRIV-13) does not help against what is
  drawn around a button, and the measure of PRIV-14 is a mitigation on the Android versions
  that need it, not a complete defence.
- **THREAT-17** Loss, and deletion by others. A forgotten passphrase, an uninstalled app or a
  lost device loses the chats, and "Reset app data" needs no passphrase, so anyone who holds the
  device can delete everything, though not read it.
- **THREAT-18** What the files reveal without being decrypted, to someone who can read the
  app's private storage: that the app is installed and which models it carries; how many chats
  there are, their sizes and when they were changed; and the content of the lock configuration
  (whether fingerprint unlock is on, whether an answer is finished after locking, how many wrong
  attempts were made and when).
- **THREAT-19** What the notifications tell: that the app is writing an answer, or has finished
  one, and when. Nothing else.
- **THREAT-25** What the expiry of messages (CHAT-22) does not do. Deleting is not erasing:
  the medium may keep the encrypted bytes of an expired message (THREAT-20), and text that
  was decrypted stays in the memory of the process until it ends (MEM-04, MEM-05). Nothing
  expires while the app does not run: deletion happens when it starts, is unlocked, or runs
  while unlocked. A date set forward on the device makes messages expire early, and the trusted
  time then stays ahead until the real date catches up (CHAT-24). A kept copy of a chat that
  could not be read completely (STORE-09) cannot be rewritten: its older messages stay until
  the copy's own date, that of its newest message, is past the limit, which can be up to one
  more limit period after they expired. The list of chats cannot be changed without the key: it
  keeps the titles of the chats deleted while the app was locked, encrypted, until the next
  unlock (STORE-22). The files' dates (STORE-21) tell anyone who can read the private storage
  when each chat last changed.
- **THREAT-20** Old data on the storage medium. Deleting a chat deletes its file, but the medium
  may keep the encrypted bytes until it reuses the space; they stay as unreadable as they were,
  and after a reset the keys that could open them are gone. Changing the passphrase does not
  replace the data key (KEY-07).
- **THREAT-21** What another app does with a link it was handed (MD-06, MSET-15): it uses the
  network. The app only passes the address on.
- **THREAT-22** A development build: it can be debugged, its private files can be read by a
  computer the user authorised for debugging, and it accepts a model file that nothing verified
  (MODEL-23).
- **THREAT-23** The inputs of a build that are pinned by version or name only (library packages,
  tool-chain packages), as opposed to those pinned by hash (the llama.cpp commit, the build
  tool's distribution, the model files).
- **THREAT-24** Anything an outside review would have found: the code has been reviewed for
  security once, within the project, and no outside party has audited it.

### Decisions and their reasons

Each item names a decision, what the sources say about why, and the property a port must keep.
Where the code, the tests or the README give a reason, it is given as the reason. Where they
only state a rule, the item says so and states its effect instead of inventing a motive.

- **DEC-01** *The lock cannot be switched off* (LOCK-01). Stated as a rule, and built so that it
  cannot be otherwise: the data key only ever exists wrapped by the passphrase key and,
  optionally, the fingerprint key, so there is no key that opens the data without the user
  (KEY-02). Preserve: no setting, stored field or code path makes the data readable without a
  passphrase or an authorised biometric.
- **DEC-02** *A passphrase of the app's own, never a PIN, and never the device's PIN, pattern or
  password* (PASS-01, BIO-01). Reason for the passphrase: in the cases the lock cannot rule out
  (THREAT-11), only the strength of the passphrase protects the data. The exclusion of the
  device credential is stated as a rule, in three places: the rules refuse digits alone, the
  biometric prompt cannot fall back to the device credential, and no key of the app can be
  authorised by it. Its effect is that whoever can unlock the device cannot, by that alone,
  open the app. Preserve: all three.
- **DEC-03** *The passphrase key depends on a key that cannot leave the device* (KEY-03).
  Reason: a copy of the lock configuration cannot then be attacked away from the device; every
  guess needs this device's key store. A new binding key for every new passphrase means that an
  older copy of the configuration, with an older passphrase, no longer opens once its key is
  deleted. Preserve: guessing needs the device, and replacing the passphrase invalidates older
  configurations.
- **DEC-04** *A slow derivation with enforced bounds, implemented in the app* (KEY-04). Reason:
  nothing read from the file can then make the derivation fail, hang or run with weakened
  parameters; and the app's own implementation turns the passphrase into bytes in an array it
  overwrites afterwards, with a result that never depends on how a library converts characters.
  Preserve: the bounds are enforced on reading, and passphrase bytes are overwritten after use.
- **DEC-05** *Passphrases are normalised* (PASS-03). Reason: the same text can be typed as
  different sequences of code points depending on the keyboard; without normalisation a
  passphrase set with one keyboard would not unlock with another, and there is no recovery.
  Preserve: the normal form and the encoding, for ever.
- **DEC-06** *An attempt is counted on disk before it is checked* (PASS-05). Reason: ending the
  app in the middle of a check must not avoid the count. Preserve: the order.
- **DEC-07** *Waits are timed on the device clock and never on the date and time* (PASS-08).
  Reason: the device clock never runs ahead of real time, so the date could not make a wait any
  safer, while the date can be set by anyone holding the device, or corrected by the network, in
  either direction. A wait that followed it could be cut short by setting the date ahead or,
  worse in an app without any recovery, held for as long as the date was set back: the right
  passphrase refused for days because a clock was corrected. Preserve: nothing a holder of the
  device can set shortens a wait or stretches it beyond one full delay.
- **DEC-08** *Nothing is deleted after wrong attempts* (PASS-10). Stated as a rule. Its effect,
  together with THREAT-17: a person who cannot unlock can delete the data only by the reset,
  deliberately.
- **DEC-09** *The fingerprint key is held in hardware, needs the strongest class of biometric
  for every single use, cannot be authorised by the device credential, and is invalidated when
  the enrolled biometrics change* (BIO-01, BIO-12). Stated as the properties of the key. Their
  effect: the wrap cannot be opened by software alone, by a weaker biometric, by the device
  credential, or by a finger enrolled after the user turned the feature on; after such a change
  the passphrase is needed. Preserve: all four.
- **DEC-10** *Changing how the app unlocks needs the passphrase, entered within the last 2
  minutes; a fingerprint unlock gives no such proof* (PASS-12). Stated as a rule. Its effect:
  neither an enrolled finger nor a moment with the unlocked app is enough to replace the
  passphrase or to add or remove a way in. Preserve: the three protected actions and the limit.
- **DEC-11** *A fingerprint unlock ends a streak of wrong passphrases* (BIO-09). Reason: someone
  who only ever unlocks by fingerprint would otherwise carry failures from months ago, and one
  more wrong passphrase would start at the long waits.
- **DEC-12** *Only "no strong biometric enrolled" removes fingerprint unlock on the platform's
  word* (BIO-13). Reason: any other unavailability may pass, and the passphrase serves until the
  fingerprint works again. A wrap is removed otherwise only when its own, authorised key cannot
  open it, because it is then of no further use.
- **DEC-13** *No recovery, and a reset that asks for a typed word but for no passphrase*
  (RESET-01, RESET-06). No recovery is stated as a rule: there is no recovery key, no reset link
  and no second way in. The reset asks for no passphrase because it is the way on when the
  passphrase is forgotten. Preserve: no second way in exists, in the app or outside it.
- **DEC-14** *A reset deletes the keys first and waits for every late write; set-up is never
  offered over existing data* (RESET-07, SETUP-10). Reasons: from the moment the keys are gone
  nothing stored can be decrypted, whatever else still gets deleted; a write that landed after
  the deletion would put a file back; and a new key made over old data would make that data
  unreadable for good. Preserve: the order, the wait, and the refusal.
- **DEC-15** *Keys are replaced crash-safely, and a write is durable before anything depends on
  it* (KEY-05, STORE-05, STORE-06, SETUP-07). Reason: a stop at any point, a power loss
  included, must leave the stored configuration referring to a key that exists, and no data may
  be encrypted with a key whose only wrap a power loss could still take away. Preserve: new key
  first, durable configuration second, deletion of the old key last.
- **DEC-16** *Every blob is authenticated together with what it is* (KEY-01). Reason: a blob
  moved to another file or handed to another store must not open. The limit is stated, not
  hidden: which version of a file it is, is not authenticated (THREAT-15).
- **DEC-17** *At a lock the screens go within the same call, and the key goes after bounded
  waits whatever is stuck* (LOCK-17, LOCK-18). Reasons: nothing of the chats or settings may
  stay referenced by the interface; and a write or a native call that hangs must not keep the
  key alive. Preserve: every wait before the key is destroyed is bounded, and a destroyed key
  can be used for nothing.
- **DEC-18** *Decrypted data lives only inside a session, and what becomes of a session at a
  lock is decided in one place* (LOCK-10, LOCK-17). Reason: the lock can then end everything
  that holds decrypted data without knowing what a session contains. Nothing that outlives a
  session (the engine, the keep-alive, the notifications, the window policy) holds the key or
  anything decrypted. Preserve: both.
- **DEC-19** *"In the background" begins when the screen is no longer shown, not when it loses
  the focus* (LOCK-12). Reason: the fingerprint prompt and the question about notifications take
  the focus too and must not lock the app.
- **DEC-20** *An answer may be finished behind the lock, by default, for at most 10 minutes*
  (section 6). Reason: an answer the user asked for is finished instead of lost when the app
  locks by itself. The price, which is told to the user where the feature is switched
  (SECSET-05): until then the key is still in memory. Preserve: the screen is locked regardless;
  the limit is enforced even if a timer is late; unlocking is the full check, because a key that
  happens to be in memory must shorten nothing; only a key that equals the session's key
  reattaches it; "Lock now" and a reset end it; and it can be turned off.
- **DEC-21** *The model stands aside while a key is derived* (PASS-11, ENG-22). Reason: the
  derivation is meant to take a second or two of one core, a person is waiting for it, and next
  to a model that keeps the fast cores busy it took several times as long. Preserve: the pause
  lasts exactly as long as the derivation, ends whatever the derivation's result, and changes
  nothing else.
- **DEC-22** *The app ends its own process once it is locked, idle and out of sight* (section
  7). Reason: a lock overwrites the key, but copies of a conversation stay in memory that
  nothing can overwrite (strings, buffers a native library has freed); the end of the process is
  the only thing that releases them all. Preserve: no readable copy of a conversation remains
  once the app is locked and out of sight.
- **DEC-23** *Nothing can be captured by default, and an allowance is temporary and exists in
  memory only* (PRIV-01, PRIV-02, PRIV-06). Reason: nothing the app shows should end up in a
  screenshot or a recording unless the user has allowed it for a while; and because nothing of
  an allowance is stored, there is nothing in any file that could turn screenshots on, and a
  fresh start is always secure from its first frame. That it ends when the screen turns off is
  stated as a rule. Preserve: every start is blocked from its first frame, and an allowance
  cannot outlive its limit, the screen turning off or the process.
- **DEC-24** *A screen on which a passphrase is typed is never captured, and the preview in the
  list of recent apps is always hidden* (PRIV-03, PRIV-04, PRIV-07). The first is stated as a
  rule. Reason for the second: that picture of the app is shown without the user doing anything,
  so no allowance of the user's covers it.
- **DEC-25** *A passphrase field shows no character, tells accessibility services none, and
  hands its content over as an array that is overwritten* (PASS-13, PASS-14). Reasons: showing
  the last typed character for a moment is enough for a camera, a screen recording or someone
  watching to read a passphrase one character at a time; any accessibility service the user has
  enabled could otherwise read the passphrase while it is in the field; and a string cannot be
  overwritten.
- **DEC-26** *Every clip is marked as sensitive, through one wrapper, and the app does not start
  without it* (PRIV-09). Reason: everything that can be copied out of the app is text that is
  otherwise only stored encrypted, so there is no clip that should not be marked; with one
  wrapper no clip can leave without passing it; and to carry on without it would be to let
  clips out unmarked without anybody noticing. Copying itself must keep working.
- **DEC-27** *The keyboard is asked not to learn, autofill is excluded, smart selection is off
  and the selection menu is an allow-list* (PRIV-10 to PRIV-12). The purposes are given one by
  one: nothing in the app (passphrases, prompts) is to be offered to or saved by an autofill
  service; selecting text in a chat must not hand that text to the system's text classifier,
  which on some devices is supplied by the manufacturer or by another app; and chat text should
  not leave the app by a tap on a menu entry that another app put there. The menu is held to a
  list of what is known, not cleared of what is unwanted, so that an entry of a new kind is
  dropped until it has been looked at.
- **DEC-28** *Taps under another app's overlay are discarded* (PRIV-13). Reason: an app that may
  draw over others can put a harmless-looking picture over this one and let the taps through,
  so that the user presses a button of this app ("Allow screenshots", "Lock after", a
  confirmation) without seeing it.
- **DEC-29** *One entry point, in a task of its own, and nothing else that another app can
  call* (PRIV-14). Reason: on the platform versions that allow it, another app could otherwise
  have one of its screens placed in this app's task, where it comes up in place of the lock
  screen and can ask for the passphrase.
- **DEC-30** *Notifications carry one fixed sentence each* (BG-07). Reason: a notification is
  shown outside the app, on the device's own lock screen and to whatever is allowed to read
  notifications. Preserve: no content, no title of a chat, no name of a model, and code that
  cannot reach them.
- **DEC-31** *A link from an answer asks first and shows its full address, only web and mail
  links are handed on, and images are never loaded* (MD-03, MD-05, MD-06). Reason: a model can
  write an image or a link to any address, and what a link says and where it leads can differ.
  Nothing is ever fetched for an answer.
- **DEC-32** *The model card opens at a tap, without a question* (MSET-15). Reason: the address
  is the app's own, fixed in the catalogue, built in one place and shown on the row that opens
  it; and that one place yields no address at all, rather than one that leads somewhere else,
  for a repository written in any unexpected way.
- **DEC-33** *No network permission, enforced by the build; no native library with a network
  client; no request through another process; models never downloaded* (BUILD-01, BUILD-02,
  PRIV-16, MODEL-02). Reason: the app's promise is that it never uses the network, and a
  dependency could otherwise add a permission unnoticed; llama.cpp's helper library carries an
  HTTP client and a downloader; and a request made by another process on the app's behalf is
  not prevented by the missing permission. Preserve: the promise as a property of the package
  that is checked, not of the code's good behaviour.
- **DEC-34** *No backup and no transfer to another device* (STORE-19). Stated as a rule. A fact
  beside it: the files would be of no use elsewhere, because the key they depend on cannot leave
  the device (KEY-03).
- **DEC-35** *No logging, no conversation text in a technical detail, nothing decrypted in the
  state the platform saves* (BUILD-06, PRIV-15, APP-14). Reason: saved state is written by the
  system, in the clear; the others are stated as rules of the same kind. Preserve: chat text,
  prompts and key material reach no storage outside the encryption.
- **DEC-36** *Data that could not be read completely is never written over without a copy, and a
  chat that cannot be read is left exactly as it is* (STORE-09, STORE-10). Reason: a chat that
  will not open is lost to its owner, and a later version may be able to read what this one
  cannot; breaking the rule would lose data silently.
- **DEC-37** *The content of a message always reaches the model as ordinary text* (FMT-09).
  Reason: text that merely looks like a control token, typed by the user or quoted by the model,
  must never close a turn or open one.
- **DEC-38** *Every size from outside is limited before memory is allocated for it, and the
  sampler is never asked to choose among nothing* (NAT-06, NAT-10). Reason: the native library
  allocates at once for whatever size it is handed, and a device ends the process long before
  such a call returns; and with every candidate ruled out its samplers name an arbitrary token
  or end the process.
- **DEC-39** *A model gets into a package only as the pinned file, and a release build looks for
  no other* (BUILD-03, BUILD-10). Reason: a model file from anywhere else would be packaged, or
  run, with nothing vouching for it.
- **DEC-40** *A destroyed key hands out nothing* (KEY-06). Reason: a destroyed key is all zeros,
  and zeros must never be taken for a key: data sealed with them would be readable by anyone,
  and wrapped under a new passphrase they would replace the only copy of the real key.
- **DEC-41** *A development build is a different app* (APP-16, BUILD-11). Reason: installing,
  clearing data, uninstalling and running the tests on a device must never be able to touch a
  real installation and the chats in it.
- **DEC-42** *The fingerprint works only for a chosen time after the last unlock or the moment
  the last lock was due, and never after a restart; running out deletes nothing* (BIO-18). The owner's decision. Reasons:
  a device left alone for long, or restarted, should need the passphrase, while ordinary use
  keeps the fingerprint working; the date and time can be set by anyone holding the device, so
  only the device clock and the count of starts are used; and the check is made only when the
  app is opened, so deleting the key when the time is up would add nothing against someone who
  can get past the check, and would force setting fingerprint unlock up again each time.
  Preserve: a restart always ends the period; whatever cannot be decided counts as run out; a
  lock that ran late starts the period when it was due; the check is made again at the moment
  of use; and running out never removes fingerprint unlock.
- **DEC-43** *A scheduled background job, and the permission it needs to survive a restart*
  (BG-12, SECSET-14). The owner's decision of 2026-11-23, which added `RECEIVE_BOOT_COMPLETED` as
  the eighth entry of the build's allow-list (BUILD-01). Reasons: messages are to expire whether
  or not the app is opened, and a job that ends with a restart would stop deleting after the
  first one. Preserve: the job is used only while its switch is on and cancelled when it is
  off; it has no receiver of its own (the platform's scheduler handles the restart); it runs the
  lock's own clean-up through the lock and never beside it; it never touches a key, loads a
  model or shows anything; and it never keeps the process alive longer than its work.

## 27. Planned tools: design considerations (TOOL)

**Nothing in this section is built.** The app has no tools today, and no item below may be
ticked off as a feature. The section exists because the owner intends the models to use tools
in a later version, and a port should be designed for them from the start, as this code is. For
each consideration it says what in today's code already provides for it (checked in the code,
with the type or file named), what is only an intention, and what is open. Where the list of
intentions leaves a question open, it is recorded as open and not answered here.

**Source of what is provided today:** `core/.../core/prompt/PromptFormat.kt`,
`core/.../core/prompt/Qwen35ChatMlFormat.kt`, `core/.../core/engine/InferenceEngine.kt`,
`core/.../core/engine/LlamaEngine.kt`, `core/.../core/engine/GenerationEvent.kt`,
`core/.../core/chat/ChatMessage.kt`, `core/.../core/chat/SessionCodec.kt`,
`core/.../core/chat/PromptHistory.kt`, `data/SealedFile.kt`, `data/SettingsStore.kt`,
`security/AppFiles.kt`, `security/AppLock.kt`, `security/UnlockedSession.kt`,
`background/AnswerWork.kt`, `app/build.gradle.kts`; tests
`core/.../core/engine/LlamaEngineGenerationTest.kt`, `core/.../core/chat/SessionCodecTest.kt`,
the fixtures under `core/src/test/resources/golden/`.

### The intention

- **TOOL-01** The tools the owner intends, none of which exists:
  - a search of Wikipedia, and reading a Wikipedia article, with the article converted to
    Markdown by the app;
  - a web search through Brave, with the user's access token stored in the encrypted settings;
  - "memory" files in Markdown that the model can update, and free-form Markdown files that it
    can create and read later, all stored encrypted, with a search over those files in the
    manner of grep;
  - a calculator, in the form the models were trained on rather than a new one;
  - setting and deleting alarms on the device;
  - running in the background and posting a notification when something is done or needs
    attention.
- **TOOL-02** Two rules stand over all of them. Inference and the delivery of models never use
  the network, tools or not: models are packaged in the app and never downloaded by it. And the
  network, when a tool needs it, is something the user turns on for that tool; until then the
  app is as section 28 describes it.

### What is provided for, what is intended, what is open

- **TOOL-03** *Describing tools to a model.* The intention: the instructions are plain text in
  the system message.
  - Provided today: a request has a second field of system text beside the model's system
    prompt (`GenerationRequest.extraSystemText`, `PromptInput.extraSystemText`). There is a
    single rule for where it goes (`PromptInput.systemText()`): the system prompt, a blank line,
    then the extra text; either alone when the other is absent. Every chat format renders its
    system message from that, and each of the three chat fixtures has the case "system prompt
    followed by extra system text". The engine passes the field through, and a test holds it to
    that.
  - Only intended: nothing produces such text; the chat layer never sets the field.
  - Limits found in the code: the engine gives a model that was trained without a system message
    no system text of any kind (ENG-24), so instructions sent this way do not reach Peacebell,
    and the text-continuation format ignores system text altogether. The system text is never
    left out when a conversation is shortened (ENG-04) and counts against the model's context
    (MODEL-04), which for Boris-2 is 2,048 tokens and cannot grow.
  - Open: which models get tools at all, and how long the instructions may be.
- **TOOL-04** *A call written by the model.* The intention: the model writes a call as one line
  in the syntax of a Python call; some models have another, native form, and Qwen's resembles
  XML.
  - Provided today: a request can carry stop strings of its own, and the end of the reply says
    which one stopped it (ENG-15), so a reply can be ended at a marker a caller chooses. The
    comment on the reasons for which a reply ends anticipates one for "a reply that ends in a
    tool call", and a stored reason that a reader does not know is read as absent (FMT-07).
  - Only intended: nothing recognises, parses or carries out a call. The Qwen format reproduces
    the model's template for a conversation without tools; the template's own way of describing
    tools and of writing calls is not implemented.
  - Open: the exact syntax for each model, and what happens to a call that cannot be parsed.
- **TOOL-05** *A tool's result in the prompt.* The intention: each prompt format has one place
  where a result is rendered.
  - Provided today: the roles a format knows are a closed list of two (`PromptRole`), and every
    format decides its layout in one branch over the role, with a comment at that branch saying
    that a tool result will be rendered there; adding a role makes the compiler point at exactly
    those places, and at the one place where stored messages are mapped to prompt messages
    (`promptHistory`). For Qwen the template's rule that a message of the user wrapped in
    `<tool_response>` tags is not a query is already implemented and has two cases in its
    fixture (FMT-10). The text-continuation format has no place for a result and says so.
  - Only intended: no role for results exists, in the prompt or in a stored chat.
  - Open: the layout of a result for Peacebell and for the plain-text format (their comments say
    "a message with a role of its own" and nothing more).
- **TOOL-06** *The calculator, as an inline tool.* The intention: the models were trained on
  lines of the form `expression = output`, ending in `#### result`. Generation pauses, the app
  appends the value to the partial text of the answer, and generation continues.
  - Provided today: the two engine abilities this needs. A reply can be stopped at a string the
    request names, and a partial answer can be continued from its last character with only the
    continuation reported (ENG-15, ENG-16). The test "an inline tool - stop, append the result,
    continue" runs the whole pattern through the engine over a scripted native layer: the reply
    stops at ` = `, the caller appends the stop string and the value, the continuation is asked
    for, and the question and the model's own words are reused from memory instead of being
    read again. Each chat fixture has the case "continue after an inline calculation".
  - Only intended: there is no calculator, the chat layer names no stop strings and has no step
    that appends to an answer in flight, and the form of the lines exists only in that test.
  - Limits found in the code, for whoever builds it: when a stop string ends a reply, the text
    reported is exactly what stood before it, so the caller puts the stop string back itself;
    the chat layer, as it is, removes whitespace at the end of a reply that a stop string ended
    (MSG-08), which an inline tool must not suffer; and on the models that
    cannot remove the end of their memory (NAT-08) a continuation costs a return to the last
    checkpoint whenever the appended text does not simply extend what the memory holds.
  - Open: how the paused answer, the value and the continuation appear in the chat while they
    happen.
- **TOOL-07** *Stored chats that tolerate what they do not know.* The intention: tool calls and
  tool results can be added to the stored form without a migration.
  - Provided today: the reading rules of FMT-07, each of which a test holds: unknown fields are
    ignored everywhere; a message with a role the reader does not know is left out and counted
    while the chat still loads; an unknown status or finish reason falls back; a newer schema
    version is read and noted. New fields of a message are added with a default.
  - What that tolerance amounts to, exactly: a version that does not know a role does not show
    those messages, says so (CHAT-17), and keeps a copy of the file before it writes over it
    (STORE-09); the file it then writes no longer contains them. Nothing is lost for good, but
    the two versions do not show the same chat.
  - Open: the stored form of a call and of a result.
- **TOOL-08** *Encrypted files for tools.* The intention: memory files and free-form files are
  stored encrypted like everything else, and can be searched.
  - Provided today: storage is not specific to chats. An encrypted file is a blob sealed with a
    namespaced purpose (`SealedFile`, `SealedDirectory`, `Purposes`), written atomically,
    protected by the rule that unread content is never written over, and written through the
    one background writer. A new store needs a name space of its own and three rules it must
    follow, which is all the lock relies on: its files end in `.enc` and lie under the private
    files directory outside `security/`; every blob is sealed with a purpose; and a reset
    deletes everything. The lock then knows that the store's data exists, and a reset removes
    it, without either being changed (STORE-02, RESET-07).
  - Only intended: the files themselves, the model's ways to read, create and update them, and
    the search.
  - To keep in mind: the name of a file on disk can be read without any key (THREAT-18). Chats
    are named by random ids for that reason, and an id must fit FMT-03. What a tool file is
    called by the model or the user is therefore content, and where that name is kept is open.
    A search can only run where the files can be decrypted, which is inside an unlocked session.
- **TOOL-09** *A secret in the settings.* The intention: the access token of the search service
  is stored in the encrypted settings.
  - Provided today: the settings are one sealed file, and a setting added later is a new field
    with a default that old files simply lack (FMT-08). The settings type documents this very
    case: a secret needs nothing more than that, and must only be left out of the text form of
    the settings, as the system prompts are, so that it cannot reach a log.
  - Open: where the field sits in Settings, how it is shown once entered, and whether changing
    it needs the passphrase.
- **TOOL-10** *An engine that needs no screen.* The intention: a tool run, or a background task,
  uses the engine without a screen being there.
  - Provided today: the engine's interface assumes no interface, lifecycle or thread (ENG-02),
    the module it lives in has no dependency on the platform at all, and the chats with the
    reply that may be running belong to the unlocked session, not to a screen (CHAT-18).
- **TOOL-11** *Working while the app is locked.* Whether a background task may keep the data key
  in memory while the app is locked was an open question when the lock was designed. Two things
  in the code answer for it.
  - The decision is taken in one place. What becomes of the unlocked session when the app leaves
    the unlocked state is decided in a single function (`AppLock.endSession`), and the lock knows
    a session only through a small interface (`UnlockedSession`): it asks whether the session
    has unfinished work, tells it to start nothing new, waits for the work with a limit, and
    either closes it or hands the same session back after a full unlock.
  - "Finish the answer after locking" (section 6) is the first answer, and the pattern a
    background tool run would follow: the screen locks at once; the session lives on behind it
    with its key for a bounded time; unlocking is never shortened; "Lock now" and a reset always
    end it; the keep-alive is held until the key is gone; and then the process ends.
  - Its limits, as built: it only applies when the app locks by itself, and only if the user
    left the setting on, and while the keep-alive runs; it lasts at most 10 minutes and the
    closing after them (LEASE-03); a session behind the lock starts nothing
    new, neither a reply nor a model load, so a run that needs a further generation after a tool
    has answered (TOOL-06 is such a run) stops at that step; the keep-alive can only be started
    while the app is in front, so work that begins in the background has nothing to keep its
    process alive; and the process ends as soon as the work does, so nothing can wait in the
    background for a later moment.
  - Open: whether any of these limits is to be widened for tools, and what the user is told
    when it is. Each widening keeps the data key in memory for longer while the app looks
    locked (THREAT-09).
- **TOOL-12** *Saying that something is done or needs attention.*
  - Provided today: the two notifications of section 18, the single place that decides when "an
    answer is ready" is said and to whom (`AnswerWork`), and the rule, held by a test, that a
    notification is one fixed sentence that carries nothing of a chat (BG-07).
  - Only intended: a notification for a finished tool run, and any notion of "needs attention".
  - To keep: whatever is added says nothing about its content, for the reason of DEC-30.
  - Open: its wording, and when it is shown.
- **TOOL-13** *The network.* The intention: network code lives in a package of its own behind an
  interface, talks only to named hosts, and is off until the user turns a tool on; the build's
  list of permissions is widened deliberately and only for that.
  - Provided today: its absence, which is enforced rather than merely true. The app declares no
    network permission, the build fails on one (BUILD-01), a test asserts that neither the
    manifest nor the build file names one, another asserts that the app contains no browser of
    its own, and no native library with a network client is linked (BUILD-02).
  - Only intended: all of the network code and its isolation. No such package or interface
    exists.
  - To keep: a web tool does not change how a link written by a model is treated (MD-05), and
    it does not make inference or the delivery of models use the network (TOOL-02).
  - Open: the hosts; what the user sees before a tool first uses the network; and the wording of
    the three places where the app says today that nothing leaves the device (CHAT-21, MODEL-21,
    ABOUT-02), which would no longer be true without qualification once a web tool is on.
- **TOOL-14** *Alarms.* Nothing provides for them today. Setting and deleting an alarm is an
  action outside the app, on the user's device, which nothing in the app does at present;
  whatever permission it needs would be an addition to the list of BUILD-01. Open: everything
  else, including whether the model may set one without the user confirming it.
- **TOOL-15** *Showing a tool at work.* Open: how a call, its result and a failure of a tool
  appear in a chat. What exists and would apply to a result shown as Markdown: nothing is
  fetched for it, images appear as their alternative text, and a link asks first (section 13).
- **TOOL-16** *Tools and a short context.* A conversation is shortened by whole turns, and a
  turn begins at a message of the user (ENG-04). Open: whether a tool's result belongs to the
  turn that asked for it or can begin one, which decides whether a result can be separated from
  its call when the oldest turns are left out.

## 28. What the app does not do (NOT)

**Source:** the absence of each thing in `app/src/main/AndroidManifest.xml`, in the sources and
in `strings.xml`; `SourceRulesTest.kt` for the absences a test enforces.

Parity includes the absences. A port that adds one of these has changed what the app is.

- **NOT-01** No network. The app has no permission to use it, opens no connection, links no
  library that could, and downloads nothing: no models, no fonts, no updates, no remote
  configuration.
- **NOT-02** No account, no sign-in, no synchronisation between devices, no server of any kind.
- **NOT-03** No analytics, no crash reporting, no advertising, no tracking, and no logging of
  anything by the app's own code.
- **NOT-04** No backup, export or import of chats, and no way to hand a chat to another app
  other than copying text out of it. The app is not a share target and handles no links.
- **NOT-05** No recovery of a forgotten passphrase, no hint, no way to show what was typed, no
  PIN, no unlocking with the device's credential, no setting that turns the lock off, no
  deletion after wrong attempts, and no staying unlocked across a start.
- **NOT-06** No browser inside the app, no loading of images, no previews of links, and no link
  from a model that opens without a question.
- **NOT-07** No attachments, pictures, voice input or spoken output: text only.
- **NOT-08** No tools: a model cannot calculate, search, read a page, keep notes, set an alarm
  or do anything but write text (section 27).
- **NOT-09** No model that the user downloads, imports or deletes; no quantised models; no use
  of a graphics processor or neural accelerator; never two models in memory; never two replies
  at once.
- **NOT-10** No settings for a single chat and no setting for all models; no control over top-k,
  top-p, the penalties, the seed, the size a context starts with or what ends a reply; no "thinking"
  mode for Qwen3.5; no reproducible reply.
- **NOT-11** No renaming of a chat, no search across chats, no folders, pins or archive, no
  alternative versions of an answer to go back to, no selecting several chats, and no undo of a
  deletion.
- **NOT-12** No display of speeds or token counts, and no diagnostics screen, although the
  engine computes the figures (ENG-23).
- **NOT-13** No setting for the theme, the font size or the language; the app follows the
  system and is in English.
- **NOT-14** No widget, no shortcut, no quick action, and no notification that carries content
  or offers an action.
- **NOT-15** No lasting permission to take screenshots, and no setting for the preview in the
  list of recent apps or for the keyboard's learning.
- **NOT-16** No queue of messages and no automatic restart of an answer that was interrupted: an
  answer that was stopped, by anything, stays stopped until the user continues it or asks again.
- **NOT-17** Nothing is written on the user's behalf: no reply is started without a tap, and an
  answer is never begun while the app is locked.
- **NOT-18** No promise that the system never ends the app for lack of memory. The engine takes
  memory only after asking how much there is (ENG-31), but what a system reports can change a moment
  later, and a system may end an app without refusing anything first. What is kept then is the chat
  on the disk (STORE-07): every message as it was sent, and the answer under way as far as it had
  been written, at most a second behind.
