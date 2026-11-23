# Prose Plow

Prose Plow is an Android app that runs small language models entirely on the phone. The models
are packaged inside the APK and run on the phone's processor through
[llama.cpp](https://github.com/ggml-org/llama.cpp), which this project builds from source with
four small patches. Nothing is downloaded while the app runs, and the app declares no network
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
- **A page of settings for each model.** Settings lists the models, each with its current
  values on one line; tapping a model opens its own page, which can also put the model's own
  values back.
- **A link to each model's card.** A model's page, and the About screen, show the address of
  the model's page on Hugging Face (`huggingface.co/<owner>/<name>`) and open it in the phone's
  own browser at a tap. The app has no browser of its own and loads nothing itself.
- **A system prompt for each model**, on its page, for the models that were trained with one
  (Qwen3.5 and Boris-2; Peacebell was trained without one and has none).
- **A temperature for each model**, from 0 to 2. Left empty, a model uses its own reference
  value; 0 always picks the most likely word. It applies from the next answer and switches
  with the model.
- **A longest reply for each model**, in tokens (a token is about three quarters of a word).
  An answer that reaches it stops with "Reached the length limit" and can be continued. Left
  empty, a model uses its own value from the table below; it can be set from 16 tokens to
  half of what the model's context may grow to on this phone, because the room for a reply is
  taken from the room for the earlier conversation, and never to more than 4,096.
- **Answers as Markdown**, with selectable text, a copy button on every message and on every
  block of code. Images are never loaded: the app shows their alternative text. A link is shown
  in full and is handed to another app only after you have confirmed it.
- **A context that grows with the conversation.** A model starts with a context of 4,096
  tokens (Boris-2 with its 2,048) and is given more only when a conversation needs it, up to
  a limit: what the model was trained with, what a quarter of the phone's memory holds, and,
  unless you choose a size yourself on the model's page, what the model can read again in
  about two minutes (reading slows as a conversation gets longer, so that is 7,168 tokens for
  Qwen3.5 on the tested phone). Before every step the app asks Android how much memory is
  available, and
  it takes no step that would leave too little. The chat says when the context has grown and
  when it could not. Locking the app gives the memory back, and so does the next answer in
  another conversation that needs no more than the starting size.
  [The context grows with the conversation](#the-context-grows-with-the-conversation) has
  the numbers.
- **Long conversations.** At the limit of the context, the oldest messages are left out of
  the latest answer and the app says how many. They stay in the chat.
- **At the edge of the phone's memory Android can still end the app.** The app checks before
  it takes memory, but it cannot promise what Android does a moment later. If the app is
  ended, the chats are on disk, encrypted: only what was not yet written of the answer under
  way is lost.
- **Drafts.** What you have typed and not sent is kept with its chat.
- **An answer is finished when you leave.** While an answer is being written the app runs a
  foreground service, with a silent notification that says "Writing an answer..." and nothing
  else, so the answer goes on when the app is in the background or the screen is off. When one
  is finished while you are not looking, a second notification says "Your answer is ready.";
  neither ever contains a word of an answer, the name of a chat or of a model. The app asks
  once, at the first answer, whether it may show notifications (Android 13 and later); without
  the permission everything else works the same. Out of sight the phone gives the app fewer
  and slower processor cores: on the one phone that was tested, reading the conversation into
  the model was about a quarter slower, and writing the answer was as fast as in front.

Privacy:

- **Encrypted storage.** Chats, drafts, the list of chats and the settings are only ever written
  to the phone encrypted (AES-256-GCM).
- **A mandatory lock.** The first start asks for a passphrase of at least 10 characters that is
  not only digits, so a PIN is refused. There is no way to use the app without one and no
  setting that turns the lock off.
- **Passphrase or fingerprint, never a PIN.** Fingerprint unlock is optional, uses strong
  (Class 3) biometrics only, and cannot fall back to the phone's PIN, pattern or password.
- **Locks by itself.** The app is locked at every start and after it has been in the background
  for the chosen time: immediately, 1 minute (the default), 5 or 15 minutes. The time starts
  when Android reports that the app has left the screen, which is a fraction of a second after
  you leave it; coming straight back within that moment finds the app still open, also with
  "immediately". "Lock now", in the chat's menu and in Settings, does not wait for anything.
- **Finishes the answer after locking**, unless you turn that off. If the app locks by itself
  while an answer is being written, the screen is locked as always, and behind it that one
  answer is finished and saved before the encryption key is erased. The price: until then the
  key is still in memory, for at most ten minutes and then the closing of the session, which
  takes at most three waits of ten seconds each (see [The lock](#the-lock)). Unlocking in that time takes the full
  passphrase or fingerprint check as always and brings you back to the answer under way. "Lock
  now" never waits: it stops the answer and erases the key at once.
- **Ends its own process when it is locked.** Once the app is locked, has nothing left to
  finish and is not on screen, it ends its process, because that is the only thing that
  removes every copy of a conversation from memory. Opening it again starts it afresh, at the
  lock screen; the model is then loaded again at the next unlock.
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
- **No network permission.** The app asks for four permissions, all of them for finishing an
  answer out of sight: the foreground service and its type, notifications, and keeping the
  processor awake. The build fails if the merged manifest contains any permission that is not
  on its allow-list (those four, and the three that the fingerprint and support libraries
  add); the network is not on it.
- **No backups.** The app's data is excluded from Android's cloud backup and from
  device-to-device transfer.

The About screen lists the models in the build with their sources and licences, and opens the
third-party notices: every library, model and icon in the app that is someone else's work, with
its copyright lines, and the text of every licence.

## The models

A model is part of the build: which of them an APK carries is decided when it is built, and the
app offers exactly those. Three are bundled. A fourth, Shard-2, is supported and tested but not
bundled; see [Supported, not bundled: Shard-2](#supported-not-bundled-shard-2).

| `id` | Name in the app | Source repository on Hugging Face | Licence | Parameters | File in the APK |
| --- | --- | --- | --- | --- | --- |
| `qwen3.5-0.8b` | Qwen3.5 0.8B | `Qwen/Qwen3.5-0.8B` | Apache-2.0 | 0.8 billion in the checkpoint; 752 million in the file, which is text only | 1,516,742,752 bytes |
| `peacebell-v1-291m` | Peacebell v1 291M | `wayneworkman2012/peacebell-v1-291M` | Apache-2.0; the name Peacebell is reserved by the model card and is used here with its owner's permission | 291 million | 583,680,096 bytes |
| `boris-2-instruct` | Boris-2-Instruct | `opencerebral/Boris-2-Instruct` | Apache-2.0 | 144 million | 291,853,184 bytes |

All three together are 2,392,276,032 bytes (2.39 GB). The exact revision of each repository and
the SHA-256 of every file that is read from it are pinned in `models/manifest.json`, together
with the SHA-256 the converted file must have.

| | Qwen3.5 0.8B | Peacebell v1 291M | Boris-2-Instruct |
| --- | --- | --- | --- |
| What it is | A general assistant in many languages; the default for a new chat | Answers questions about the Second World War from memory; not a general assistant | A very small chat model: fluent, often wrong on facts |
| Architecture in llama.cpp | `qwen35`, as upstream has it | `peacebell`, ported by this project | `boris2`, ported by this project |
| Context, tokens: at the start, and the most the model was trained with | 4,096; 262,144 | 4,096; 32,768 | 2,048; 2,048 |
| Longest reply, tokens: the default, and the most it can be set to | 1,024; 4,096 | 1,024; 4,096 | 512; 1,024 |
| Sampling | temperature 1.0, top-k 20, presence penalty 2.0 | greedy | temperature 0.7, top-k 50, top-p 0.9, repetition penalty 1.1 |
| System prompt | yes | no | yes |

The sampling values are each model's own reference settings, taken from its published files.
The temperature, the longest reply and how far the context may grow can be changed for each
model in Settings; the other values are fixed. The most a reply can be set to is half of what
the context may grow to on the phone under that setting, and never more than 4,096 tokens,
which is plenty for one answer of a small model: half is as much room as the engine keeps free
for a reply while it leaves older messages out, so a longer limit could not be kept. With the
context held at 4,096 tokens the most is therefore 2,048.
The Qwen3.5 file leaves out the vision tower and the multi-token-prediction head of the
checkpoint: the app is text only.

### The context grows with the conversation

A model's context is how much of a conversation it reads at once. Its memory is allocated in
full when the context is created, whether or not a conversation fills it, so the app does not
create the largest context a model could have: it starts small and grows when a conversation
needs more.

| | Qwen3.5 0.8B | Peacebell v1 291M | Boris-2-Instruct |
| --- | --- | --- | --- |
| Context at the start, tokens | 4,096 | 4,096 | 2,048 |
| The most the model was trained with, tokens | 262,144 | 32,768 | 2,048 |
| Memory for each token of context, bytes | 12,288 | 66,560 | 63,488 |
| Memory of the context at the start | 50 MB | 273 MB | 130 MB |

- **Steps.** When a conversation, and the room kept free for its reply, no longer fit, the
  context is made larger: to twice the starting size, four times, and so on, with the limit
  itself as the last step. The smallest step that fits is taken in one go.
- **The limit** is the smallest of three. What the model was trained with. What a quarter of
  the phone's memory holds at the model's bytes for each token, in whole 1,024 tokens. And,
  only while the model's page says "Automatic", what the model reads again in about two
  minutes: the largest whole multiple of 1,024 tokens that it reads from nothing within 120
  seconds, and never less than the starting size.
- **Reading slows as a conversation gets longer.** For every token it reads, a model looks at
  every token it has already read (that is what attention does), so a token costs more the
  longer the conversation already is, and reading a whole conversation takes much longer than
  its length divided by the speed of a short prompt. Every chunk of tokens the model reads in
  one go also costs time in proportion to what is already in memory, so long stretches are read
  in larger chunks: up to 1,024 tokens in memory the app reads 32 tokens at a time, and beyond
  that 128 (Qwen3.5, Boris-2) or 256 (Peacebell, Shard-2). With 7,168 tokens read that brought a
  token of Qwen3.5 from 33.7 to 19.3 ms and one of Peacebell from 48.7 to 14.5 ms. The larger
  chunk has a price: a model with recurrent state can be stopped only between chunks, and every
  model makes way for a passphrase check only between chunks, so a stop, a lock, or an unlock
  while a long conversation is being read can wait for one chunk longer, about 1.4 to 2.5
  seconds for Qwen3.5 on the tested phone and more the longer the conversation. The app takes the
  reading time from a curve measured on the tested phone with these chunks, kept in the model
  catalogue: Qwen3.5 read 2,048 tokens from nothing in 30 seconds, 4,096 in 64, 8,192 in 139
  and 15,872 in 314; Peacebell 2,048 in 16, 4,096 in 36, 8,192 in 89 and 15,872 in 242;
  Boris-2 its first 1,536 in 5. The phone had been measuring for an hour by then and was warm
  (battery at 34 degrees Celsius); cool, it began about a quarter faster. Between two measured
  lengths the time is interpolated; beyond the longest one it is extrapolated, with the cost
  of a token growing on at the rate the last measurements show, so the times of the largest
  sizes are estimates. On another phone
  the curve is scaled: once the running app has read a prompt of 256 tokens or more, it
  compares the time that took with what the curve gives for the same stretch, at the depth it
  was read at, and from then on uses the curve times that factor (held between a quarter and
  four times). A context that has grown is not cut back by a slower measurement: it keeps its
  size until the conversation is left. On the tested phone this makes 7,168 tokens for
  Qwen3.5 (7,168 tokens take 120 seconds, 8,192 take 139) and 9,216 for Peacebell (108
  seconds; its memory would allow 20,480), where the time decides for both.
- **A size of your own.** Besides "Automatic", a model's page offers the fixed sizes from the
  starting size up to what the phone's memory allows, each with the memory it takes when it
  is full and the time the model needs to read it all again. A fixed size is not held to the
  two minutes; that time is yours to spend. A model that cannot grow on the phone has no
  choice there, and one sentence that says how much it reads.
- **Asked before every step.** The app reads Android's own account of the memory
  (`ActivityManager.MemoryInfo`) at that moment, never an earlier one. A step is taken only
  if, with the context at its new size, the memory still counted as available is at least a
  fifth of the phone's memory and at least twice the amount at which Android itself calls
  memory low. While Android reports low memory, or has told the app so within the last minute
  (`onLowMemory`, and `onTrimMemory` at the levels that speak of memory running low), nothing
  is added. On Android 14 and later a running app is no longer told most of those levels,
  which is why the reading at the moment is the guard and the callbacks only add to it.
- **When a step cannot be taken**, the context grows as far as it can, and the size it then
  has is the limit for that model until it is loaded again. The chat says so once, and from
  there the oldest messages are left out, as at any limit. If a larger context cannot be
  created although the memory seemed to be there, the previous one is set up again with what
  the model had read, and the same applies.
- **What growing costs.** The model keeps what it has read when its context grows, where that
  can be carried over, and reads only what is new. Where it cannot, the conversation is read
  again from the start, and the chat says so.
- **What a long conversation costs after a lock.** When the app is locked and left, its
  process ends (see [The lock](#the-lock)), and the next answer in a conversation reads all of
  it again. By the curve measured on the tested phone, 7,168 tokens take Qwen3.5 about two
  minutes, 10,240 about three, 16,384 about five and a half, 32,768 about fourteen, and the
  111,616 that the phone's memory allows it about an hour and forty minutes (everything
  beyond 15,872 tokens extrapolated). Peacebell reads 9,216 tokens in under two minutes,
  16,384 in about four, and the 20,480 that the phone's memory allows it in about six.
  Behind the lock nothing changes for that: an answer that is still being read or
  written ten minutes after the app locked is stopped. A conversation that takes longer than
  that to read has to be read with the app open.
- **Giving it back.** Clearing the model's memory returns the context to its starting size.
  That happens at every lock, and when an answer is asked for in another conversation that
  fits the starting size: the little the two conversations have in common (1,024 tokens at
  most) is then read again. A conversation that only became shorter, because its latest
  message was edited, keeps its context and what was read of it until the next lock.
- **Opening a model** follows the same rule. If the phone does not have the memory available
  for the starting size, half of it is tried, and half again, down to 1,024 tokens; below
  that the model is not loaded, and the app says that the phone has too little free memory
  for it right now.
- **What is counted.** The memory in all of this is what the tokens of a context take: the
  model's bytes for each token times the size, which is in use from the moment a context is
  created. A loaded model also reserves working memory that does not depend on the size of
  its context and is mostly never touched, above all room for the scores of a whole batch of
  tokens (see the table under "The generation session" in
  [native/README.md](native/README.md)). That part is the same before and after a step and
  was there before a context could grow, so it is not counted against the quarter or the
  room; what of it is in use is already missing from the memory Android reports as
  available.

This is no promise that the app never runs out of memory. Every check is made with what
Android reports at that moment; another app can take the memory a moment later, and Android
then ends apps without asking. If it ends this one, the chats are safe: a message is written
to disk, encrypted, when it is sent, and an answer while it is being written, so at most the
last words of the answer under way are lost.

### Supported, not bundled: Shard-2

The repository carries everything that is needed to run `Glint-Research/Shard-2` (49.4 million
parameters; 99,048,160 bytes as a 16-bit file): the `shard2` architecture for llama.cpp, its
converter, its tests on the host and on a phone, and the prompt format and engine support for a
model that continues text. **No build of the app contains the model, and the app does not offer
it.** The reason is its licence: the model's repository names one (MIT) only in the metadata of
its model card and has no licence file and no copyright notice, and this app ships a model only
when its licence is established. When that is settled, switching it on is a small change:
`"purpose": "bundled"` for `shard-2` in `models/manifest.json`, `bundled = true` for it in
`core/src/main/kotlin/us/theworkmans/proseplow/core/model/ModelCatalog.kt`, its entry in the
third-party notices (`app/notices/`; a test fails while a bundled model has none), and the
model's lines in this file and in the store description.

What it is, for when that happens: Shard-2 is not a chat model. It is given only the latest
message, exactly as written, and writes on from it (context 4,096 tokens, which is what its
card says it was trained with and where it stays; replies of up to 200,
temperature 0.9 and top-k 100 as in its reference script, no system prompt). It has one token
beyond its tokenizer that takes back the token before it: the app applies that as it happens,
so words the model retracts disappear from the reply while the model itself still sees what it
abandoned. Its checkpoint is published as a Python pickle; the converter loads it in PyTorch's
weights-only mode and runs none of the code published with it.

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
The prompt speeds are those of a short prompt read from nothing. A long conversation is read
more slowly, every token more slowly than the one before; the times the app works with are in
[The context grows with the conversation](#the-context-grows-with-the-conversation).

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
| `files/settings.enc` | The settings: the system prompt, the temperature, the longest reply and the context size of each model, and whether the app has asked for permission to show notifications | Encrypted |
| `files/kept/*.enc` | Exact copies of files that could not be read completely, kept before they are replaced | Encrypted, as the original was |
| `files/security/lock.json` | The lock configuration: salt, iteration count, the wrapped data key or keys, the lock timeout, whether an answer is finished after locking, the count and time of wrong passphrase attempts | Plain JSON, because it must be readable before the app is unlocked. It holds no secret in the clear |

The models are assets of the APK and are not encrypted: they are public files.

Each encrypted file is one blob:

    "PPE" | version 0x01 | nonce (12 random bytes) | AES-256-GCM ciphertext | tag (16 bytes)

The key is a 256-bit **data key** made from the system's random generator at first-time set-up.
A new random nonce is drawn for every write. The additional authenticated data is the four-byte
header followed by `proseplow:<purpose>`, where the purpose names what the blob is (for example
`session:<id>`), so a file that is renamed, or moved into the place of another, does not
decrypt. The purpose says what a blob is, not which version of it: an older copy of the same
file decrypts as well (see "What this does not protect against"). Files are replaced atomically
(temporary file, `fsync`, rename, `fsync` of the directory).

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
  and the JDK's implementation. The iteration count and the salt are read from `lock.json`; an
  iteration count below 600,000 or above 10,000,000, or a salt shorter than 16 or longer than 64
  bytes, is refused as a damaged configuration.
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
  shown when the app comes back. "In the background" begins when Android stops the app's
  screen, not when the screen merely loses the focus: the fingerprint prompt and the question
  about notifications take the focus too, and must not lock the app. Android stops a screen a
  fraction of a second after it was left (on the phone that was tested, between 0.2 and 0.5
  seconds after the Home gesture), so someone who leaves and comes straight back within that
  moment finds the app as it was, also with "immediately".
- When it locks, the screens are torn down at once, a reply that is being written is stopped and
  kept as a stopped message (the lock waits up to ten seconds for the engine to let go), the
  inference engine is told to forget the conversation (again up to ten seconds), the writes
  that are waiting get up to ten seconds to finish, and the data key is overwritten with zeros
  whether or not any of that did. Closing a session therefore takes at most three waits of ten
  seconds each, plus the moment a passphrase check that is running at the same time needs to
  return. A write that the storage does not complete in that time is
  not waited for, so that it cannot keep the key in memory; only a reset waits for it, because
  a reset deletes the files.
- **An answer can be finished behind the lock.** When the app locks by itself (the timeout, or
  the check on coming back) while an answer is being written, and "Finish the answer after
  locking" is on (the default), everything above happens for the screen: it is torn down, the
  lock screen is all there is to see or capture. But the unlocked session is left alive, data
  key included, and told to start nothing new, provided the service that keeps the app running
  in the background has really started; if Android refused it, the answer is stopped and the
  key overwritten as with the setting off, since nothing would then keep the app awake to end
  the session on time. When that one answer is finished, or ten minutes after the moment of
  locking if it is not (it is then stopped and kept as a stopped message), the session is
  closed in the order described above, which takes at most three waits of ten seconds, and the
  key is overwritten.
  The ten minutes count whatever the model is doing: reading a long conversation again is
  part of the answer, so one that takes longer than that to read is not finished behind the
  lock (see [The context grows with the conversation](#the-context-grows-with-the-conversation)).
  "Lock now" is the stronger action and never leaves anything alive, whatever the setting; a
  reset ends it too. The setting is a field of `lock.json`, because it is needed while the app
  is locked; a file without the field means on.
- **Unlocking in that time is a real unlock.** The passphrase is put through the whole key
  derivation and the Keystore, or the fingerprint through its prompt and its key, exactly as
  from a cold lock, with the same count and the same waits; that a key happens to be in memory
  shortens nothing, and a failed attempt changes nothing about the answer under way. Only a
  success, and only if the key it produced is the key of the session behind the lock, puts
  that same session back on the screen, so the answer is neither lost nor begun again.
- **The process ends when it is no longer needed.** Whenever the app is locked, no session is
  being finished or closed, nothing is being checked or written, and no screen of the app is
  visible, it kills its own process. The session's last write has returned by then, so
  nothing is lost, and the task stays in the list of recent apps: opening it is a cold start
  into the lock screen. The app never does this while it is visible, while an answer is being
  finished, or before the files are written.
- **The model makes way for the key derivation.** Turning a passphrase into a key is meant to
  take a second or two of one processor core, and next to a model that keeps the fast cores
  busy it took several times as long. So while a passphrase is being checked (an unlock while
  an answer is finished behind the lock, a check or a change in Settings), the answer under way
  pauses between two tokens, or between two chunks of the conversation it is reading (beyond
  1,024 tokens a chunk is 128 or 256 tokens, which can take a few seconds, more the longer the
  conversation; see [The context grows with the conversation](#the-context-grows-with-the-conversation)),
  and goes on when the check has returned, whatever its result. The pause is not an end: nothing of the
  answer is lost, the ten minutes above go on counting, and stopping the answer, "Lock now"
  and a reset work during it as always.
- **Wrong passphrases are slowed down.** The first four wrong attempts cost nothing. The fifth
  costs 30 seconds, and every further one doubles the wait, up to one hour. An attempt is
  counted on disk before it is checked, so killing the app in the middle of a check does not
  avoid the count. Wrong passphrases entered in Settings count as well. The wait is timed on
  the clock that counts from the phone's start and never on the date and time, so setting the
  phone's clock, in either direction, neither ends a wait nor prolongs it. A restart of the
  phone during a wait starts the full wait again. The right passphrase ends the count, and so
  does a successful fingerprint unlock. There is no wipe after a number of failures.
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
  working memory of the conversation are in the app's memory. That working memory is the
  model's context: 4,096 tokens at the start (2,048 for Boris-2), and more when a conversation
  has made it grow, up to a quarter of the phone's memory (see
  [The context grows with the conversation](#the-context-grows-with-the-conversation)).
- When the session is closed (at a lock, or when an answer that was finished behind the lock
  is saved), the data key's array is zeroed and the key refuses every further use, and the
  engine clears the model's memory of the conversation: the native session overwrites its own
  buffers and the saved checkpoints in place before it frees them, which takes between 15 and
  about 160 milliseconds on the phone that was tested, depending on the model. That was
  measured with contexts of the starting size; a context that has grown has more to
  overwrite. Clearing also returns the context to its starting size, so the memory a long
  conversation took is given back to the phone.
- That does not reach everything. llama.cpp makes copies of its own while it works and frees
  them without overwriting them: measured on that phone after the native clear, copies of the
  prompt's text were still readable in the process for two of the four models that were
  measured (the three bundled ones and Shard-2), and copies of the prompt's token numbers for
  all four, some of them even after the session was closed (the
  token numbers of replies were not). Java strings cannot be overwritten at all.
- **So the process is ended.** When the app is locked, has nothing left to finish and is not
  on screen, it ends its own process (see "The lock"), and with the process every one of
  those copies is released, in the app's memory and in the native library's. Until that
  happens (the lock screen is still being looked at, or an answer is being finished) they
  remain.
- A passphrase is taken out of its text field as a character array, and that array and the keys
  derived from it are wiped after use. The text field itself is not the app's to wipe: it is
  emptied, but the buffers in which it held what was typed stay in memory, with what they
  hold, until the garbage collector reuses them. A passphrase that contains characters which
  had to be normalised also passes through one Java string, which cannot be wiped.
- What cannot be wiped stays until the garbage collector reuses the memory or the process ends:
  the copies of keys inside the Java cryptography classes, the text fields' buffers just
  mentioned, and the strings in which the text fields, the Markdown renderer and the JSON codec
  held chat text.
- While an answer is being finished behind the lock, everything of the first point is still in
  memory, the data key included: for at most ten minutes, and then for as long as the session
  takes to close (at most three waits of ten seconds each, see [The lock](#the-lock)).
- The loaded model stays in memory for as long as the process lives, across "Lock now" for
  one. Its weights are no secret. After the process has ended, the next unlock loads it again.
- Nothing decrypted is put into the saved instance state, and the main code contains no logging
  call; both are checked by tests that read the sources.

### Other measures

- The window is secure (`FLAG_SECURE`) from its first frame, which keeps it out of screenshots,
  screen recordings and screen sharing, except while the app is unlocked and a screenshot
  allowance runs (see "What the app does"). The allowance exists in memory only and is timed on
  the clock that counts from the phone's start, so changing the date does not stretch it. When
  it ends while the app is open, the screen is built again, so that a dialog or a menu that
  was opened during it does not stay capturable. The preview in the app switcher is switched
  off separately and always.
- **Where a passphrase is typed, nothing is captured, allowance or not.** The set-up screen, the
  lock screen and the error screens are secure whatever the allowance says: locking the app
  does not end an allowance, but it has no effect until the app is unlocked again within its
  time. The dialogs of the unlocked app in which a passphrase is typed (changing it, confirming
  it) are secure by a setting of their own. A passphrase field shows a bullet for every
  character from the moment it is typed, never the last character, and gives accessibility
  services bullets instead of the typed text.
- Every text field asks the keyboard not to learn from it
  (`IME_FLAG_NO_PERSONALIZED_LEARNING`). Keyboards may ignore the request.
- The app's windows are excluded from autofill.
- Text copied out of the app is marked as sensitive, which hides it in the clipboard preview of
  Android 13 and later. That holds for the copy buttons and for Copy and Cut in the menu of a
  text selection alike: every clip passes through one wrapper around the clipboard.
- Selecting text hands it to nothing outside the app. The text library's "smart selection",
  which gives a selection to the system's text classifier, is switched off, and the menu of a
  selection offers Cut, Copy, Paste and Select all only: the entries that other apps register
  for selected text (Translate, Search and the like), the classifier's suggestions and autofill
  are left out.
- A tap is discarded while another app's window covers the place that is tapped
  (`filterTouchesWhenObscured`, on the app's screen and on each of its dialogs and menus), so
  an overlay cannot pass a tap through to a button that the user does not see. The app says so
  in a short message when it happens on its screen.
- The app's one activity has an empty task affinity, which on Android 9 and 10 keeps another
  app from joining this app's task by its name (see "What this does not protect against"). It
  is the only exported component the app declares; a library adds one more, the receiver
  through which the system's shell installs a start-up profile, which other apps cannot call
  (it requires the `DUMP` permission). No build type contains the user interface library's
  preview activity.
- The app's two notifications are each one fixed sentence ("Writing an answer...", "Your
  answer is ready."). The code that builds them has no access to a chat, and a test that reads
  the sources fails if a notification is ever given a text with something filled in.
- The service that keeps the process alive while an answer is written is not exported, takes
  no commands and does nothing itself; its type is "special use", stated in the manifest,
  because Android has no type for running a model on the device. The wake lock it holds has a
  time limit and is renewed, so it cannot be left held.
- The check of the merged manifest against the permission allow-list is a step of the build and
  not a task beside it: what is packaged is the manifest the check handed on, so no APK or
  bundle can be built or installed from a manifest that did not pass.
- The initialiser through which a library would ask Google Play Services for a downloadable
  emoji font is removed from the manifest, because that request would be made by another
  process on the app's behalf.

### What this does not protect against

- **Anyone who knows the passphrase**, and, while fingerprint unlock is on, anyone whose
  fingerprint is enrolled on the phone.
- **The unlocked app.** Whoever holds the phone while the app is unlocked reads everything, and
  the app stays unlocked in the background for the time you chose.
- **The minutes in which an answer is finished behind the lock.** With "Finish the answer after
  locking" on, the lock screen shows, but for up to ten minutes, and the half minute at most
  that closing the session then takes, the data key and the conversation are in memory as if
  the app were unlocked. Nothing on the screen or in the
  app's interface gives access to them without the passphrase or the fingerprint; an attacker
  who can read the memory of the process in that time (a compromised phone, see below) finds
  them. Turn the setting off, or use "Lock now", if that matters more than the answer.
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
- **Traces in memory after a lock**, as described above, for as long as the process lives: the
  app ends it only once it is out of sight and has nothing to finish, and cannot end it while
  a write to failing storage does not return. And whatever the system moved to compressed
  swap.
- **What the notifications tell.** Anyone who sees the phone's notifications learns that the
  app is writing an answer, or has finished one, and when. They never learn anything else.
- **The keyboard, the clipboard and accessibility services.** A keyboard sees everything typed
  into it. Text you copy leaves the app and can be read by the keyboard and by the app it is
  pasted into. An accessibility service that you have enabled can read what is on the screen,
  chats included.
- **Capture you allowed, and capture from outside.** During a screenshot allowance the screen
  of the unlocked app can be captured by anything that can capture a screen. A camera pointed
  at the phone is never prevented.
- **An older copy of a file put back.** The encryption proves what a file is (this chat, the
  settings), not that it is the newest version of it. Someone who can write the app's private
  storage, without knowing any key, can replace a chat or the settings with an older copy of
  the same file, and can put back a chat that was deleted; the app accepts both without
  notice. An older `lock.json` is accepted in the same way unless the passphrase was changed
  after it was written. On a phone that is not rooted only the app itself can write there
  (and, for a debug build, a computer that is authorised for USB debugging).
- **Another app that imitates this one.** The app cannot show you that the screen in front is
  its own. On Android 9 and 10 an installed app can make one of its screens appear when this
  app is opened ("task hijacking") and ask for the passphrase there. The empty task affinity
  takes away the documented way of doing that, which is to join the app's task by its name; it
  is a mitigation and not a complete defence on those versions, and a phone on Android 9
  without the security patches of May 2020 has a second flaw of this kind that no setting of
  an app touches. Android 11 and later prevent task hijacking themselves. On every version, an
  app that you allowed to draw over other apps can show a look-alike screen, or cover
  everything except one button and so change what you believe you are pressing; discarding
  covered taps does not help against what is drawn around a button.
- **What the files reveal without being decrypted**, to someone who can read the app's private
  storage: that the app is installed and which models it carries, how many chats there are, their
  sizes and the times they were changed, and the content of `lock.json` (whether fingerprint
  unlock is on, whether an answer is finished after locking, how many wrong attempts were made
  and when).
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
  which uses the network: a link in an answer, after you have seen its address in full and
  confirmed it; and a model's card on Hugging Face, whose address is the app's own, fixed in
  the build and shown on the row that opens it, which therefore opens at a tap. In both cases
  the app only passes the address to Android; it opens no connection itself, has no in-app
  browser, and still has no network permission.
- **The sources of the build.** The llama.cpp commit, the Gradle distribution and the model
  files are pinned by hash. Maven and Python packages are pinned by version only, and the
  Android SDK packages by name.

The code has been reviewed for security once, within the project; what that review found is
fixed, or is listed above as something the app does not protect against. No outside party has
audited it.

## Build from source on Ubuntu 24.04

### What was verified, and how

The check described here covers the three bundled models. Shard-2, which is supported but not
bundled, was downloaded, converted and tested on the development machine only, not in this
clean-machine check.

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

The manifest lists two more models, `peacebell-v1-148m` and `shard-2`, which are converted and
tested but not bundled. They are optional: without them, the native tests that need them report
themselves as skipped. To run those too:

```sh
hf download wayneworkman2012/peacebell-v1-148M --revision 9155a36017601957565a569c73a2b53c9c6a67ec \
    --local-dir "$PROSEPLOW_MODEL_SRC/peacebell-v1-148m"
hf download Glint-Research/Shard-2 --revision 46b9eb99c475694a1129ad8f3a9825d244a8ec79 \
    --local-dir "$PROSEPLOW_MODEL_SRC/shard-2"
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
`--available`: without either, the script also wants the two optional checkpoints and ends
with an error when one is not there, after converting the others.

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

This runs the unit tests of all three Gradle modules on the JVM, Android lint, the check of
the merged manifests against the permission allow-list, and the check of the third-party
notices against the libraries the build packages. The first Gradle command downloads
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

**Changing the selection of models needs nothing by hand.** The packaging tasks take the
selection and the manifest as inputs, so the APK is written afresh whenever the set of models
in it may have changed. Left to itself, the Android Gradle plugin updates a debug APK in place
and never makes that file smaller: a model that leaves the selection would leave a gap of its
own size, and one that comes back would be appended after it.

### Check the APK

The build checks its own result and fails if a check does not hold; the reports are text files:

```sh
cat app/build/reports/manifest-permissions/debug.txt     # every permission of the merged manifest
cat app/build/reports/native-packaging/packageDebug.txt  # models stored on 16 KiB boundaries, libraries aligned
```

The first must list exactly seven: the four the app asks for,
`android.permission.FOREGROUND_SERVICE`, `android.permission.FOREGROUND_SERVICE_SPECIAL_USE`,
`android.permission.POST_NOTIFICATIONS` and `android.permission.WAKE_LOCK`; the two that the
biometric library needs, `android.permission.USE_BIOMETRIC` and
`android.permission.USE_FINGERPRINT`; and
`us.theworkmans.proseplow.debug.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`, which protects the
app's own internal broadcasts. There is no `android.permission.INTERNET`. To look at the APK itself with the SDK's tools:

```sh
BT="$ANDROID_HOME/build-tools/37.0.0"
APK=app/build/outputs/apk/debug/app-debug.apk

"$BT/aapt2" dump permissions "$APK"                # the same seven permissions, read from the APK
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

The project's own key is made and used by three small scripts, for a Linux desktop with a login
keyring (GNOME Keyring through libsecret; the system Python's `gi` bindings): `tools/signing-key.sh`
creates a 4096-bit RSA key once, in `~/.config/proseplow/` or where `PROSEPLOW_KEYSTORE` says, with
a random password that goes into the keyring and is never typed or shown; `tools/sign-release.sh`
signs the unsigned release APK with it (no v1 signature, alignment preserved) and verifies the
result; `tools/keyring.py get` prints the password for a backup. They are a convenience, not a
requirement: the commands above do the same by hand.

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
| `core` (JVM, 372 tests) | The chat model and its stored form, the prompt format of each model against the models' own templates, the model catalogue against the models' files, how large a context may be on a device and through which sizes it grows, and the inference engine over a scripted native layer: context budget, the growth of the context and what happens when it cannot grow, memory reuse and checkpoints, stop strings, cancellation | `./gradlew :core:test` |
| `app` (JVM, 562 tests) | The lock and the encryption with software keys and virtual time: the envelope, PBKDF2 against RFC vectors, the back-off, key rotation with a stop at every step, the atomic write, the mandatory lock; the encrypted stores; the chat controller and the view models over a scripted engine; rules about the sources (no logging, nothing decrypted in saved state, package dependencies); `FEATURES.md` against the app's wording, its own identifiers and its links; the third-party notices against the bundled models, the pinned llama.cpp and NDK, and the licence texts | `./gradlew :app:testDebugUnitTest` |
| `llama` (JVM, 11 tests) | The thread plan for a phone's processor, and that the build and the catalogue agree on the names of the model assets | `./gradlew :llama:testDebugUnitTest` |
| All of the above, lint, the permission check and the notices check | | `./gradlew check` |
| Native host suite (50 tests) | Deterministic conversion; every tensor of the converted files against the checkpoint; tokenizers against the reference libraries; logits and greedy replies against the PyTorch reference, in 32 and in 16 bits; the generation session with every bundled file | `native/tools/host-check.sh` |
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
   settings, context (the size it starts with, the size it was trained with, the bytes for
   each token and its reading times on the tested phone, measured with `ReadingDepthDeviceTest`
   in `llama/src/androidTest`), its chunk of a deep read (128 tokens for a model with recurrent
   state, 256 otherwise) and licence, and its name to the
   app's strings. A test compares the context numbers with the manifest's.
5. Add it to the third-party notices (`app/notices/`): its licence, its copyright lines and
   what the conversion changed. A model is bundled only when its licence is established; a
   test fails while a bundled model has no entry there.

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
| `native/` | Everything below the JVM: the patches to llama.cpp, the three architectures this project adds, the converters, the native library and the generation session, and the host tests. See `native/README.md` |
| `models/` | `manifest.json` with the pinned revisions and hashes, and `convert_models.py`. See `models/README.md` |
| `fastlane/` | The store metadata in the layout F-Droid reads (`metadata/android/en-US/`: title, descriptions, changelog, icon), and the script that renders the icon from the launcher artwork. See `fastlane/README.md` |
| `third_party/llama.cpp` | Upstream llama.cpp as a git submodule at a pinned commit. It is never edited: builds work on a patched copy |
| `gradle/libs.versions.toml` | Every Gradle plugin and library with its version |
| `gradle/wrapper/` | The Gradle wrapper, with the version and SHA-256 of the distribution |
| `env.sh` | Puts the JDK and `adb` on `PATH` for the current shell |
| `local.properties.example` | A template for the optional `local.properties` |
| `FEATURES.md` | Everything the app does that a user or a tester can observe, item by item and with its exact wording, so that a version for another platform can be held to the same behaviour |
| `LICENSE` | The Apache License 2.0 |

## Contributing

Corrections and improvements are welcome as pull requests, above all for the Windows and macOS
sections, from someone who has actually followed them.

- Run `./gradlew check` before you send a change, and `native/tools/host-check.sh` if it touches
  anything under `native/` or `models/`.
- Tests arrive in the same change as the code they cover.
- `FEATURES.md` describes the app item by item and quotes every text it shows. A change of
  behaviour changes its item there, and a string that is added or reworded is quoted there in
  the same commit: a test fails otherwise and says what to do.
- Logic that needs no Android belongs in `core`. `app` depends on `core`, never the reverse.
- The app requests four permissions, all for finishing an answer out of sight (see
  [What the app does](#what-the-app-does)), and none for the network. The build checks the merged
  manifest against an allow-list of seven entries (those four, the two the fingerprint library
  adds and the one the support library declares for the app's own receivers): a change that adds
  a permission, or a dependency that brings one, fails the build and needs a very good reason.
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

An APK built from this repository also contains other people's work, each under its own
licence. The complete list is `app/notices/notices.json`: one entry for each component, with
its version, licence, copyright lines and NOTICE text, and where each of them was read.
`app/notices/NOTICES.txt` is the text the app shows (Settings, About, "Third-party notices"),
with every licence once; the licence texts are also files in `app/notices/licences/`. In
summary:

| Component | Licence | Notice |
| --- | --- | --- |
| llama.cpp and ggml, built from the pinned commit with this project's patches. Only the libraries `libllama` and `ggml` are built; llama.cpp's helper library, its tools and everything under its `vendor/` directory are not | MIT | Copyright (c) 2023-2026 The ggml authors. Inside them, with notices of their own: tinyBLAS (Copyright 2024 Mozilla Foundation), the YaRN algorithm (Copyright (c) 2023 Jeffrey Quesnelle and Bowen Peng) and a tokenizer adapted from ggllm.cpp, all MIT. Listed as a precaution, because the source credits them without a notice: code after OpenVINO (Apache-2.0), Arm Optimized Routines and the FP16 library (MIT), and tables made from the Unicode Character Database (Unicode-3.0) |
| `libc++_shared.so`, the C++ runtime (libc++, libc++abi, libunwind and compiler-rt builtins), taken from the Android NDK with its debugging symbols stripped | Apache-2.0 with LLVM exceptions | The LLVM project; its licence file, which includes the older NCSA and MIT texts, is in the notices |
| Start-up objects of Android's C library, which the NDK links into every native library that is built here | BSD-2-Clause | The Android Open Source Project |
| Kotlin standard library, kotlinx.coroutines, kotlinx.serialization, kotlinx.collections.immutable | Apache-2.0 | JetBrains s.r.o. and contributors; the notices carry the NOTICE files of the first three. The standard library contains code derived from the ThreeTen backport (BSD-3-Clause) and from Boost (BSL-1.0) |
| AndroidX libraries: Compose (through its BOM), Material 3, activity, core, lifecycle, navigation, biometric, fragment, and the ones these depend on, AppCompat among them. One of them, `androidx.graphics:graphics-path`, brings the native library `libandroidx.graphics.path.so`; `androidx.emoji2` contains a repackaged FlatBuffers runtime | Apache-2.0 | The Android Open Source Project; FlatBuffers: Copyright 2014 Google Inc. |
| `com.mikepenz:multiplatform-markdown-renderer-m3`, and the JetBrains Markdown parser and Compose Multiplatform libraries it uses | Apache-2.0; parts of the renderer MIT | Mike Penz; JetBrains s.r.o.; Copyright (c) 2021 Erik Hellman |
| Other libraries the ones above depend on: Guava's ListenableFuture, JSpecify, JetBrains annotations | Apache-2.0 | Their respective authors |
| The icons of the app's buttons and of its notification: Material Design icons | Apache-2.0 | Google LLC |
| Support classes that the Android build tools (D8/R8) write into the app's code | BSD-3-Clause | The R8 project authors; listed as a precaution |
| The models, when they are bundled | See [The models](#the-models): Apache-2.0 each | Qwen3.5-0.8B: Copyright 2026 Alibaba Cloud; peacebell-v1-291M, whose model card reserves the name Peacebell for the unmodified weights; Boris-2-Instruct: Copyright 2026 OpenCerebral. The files in the app are conversions, and the notices say what each conversion changed. The `boris2` architecture was written against that model's reference implementation, which the notices credit |

The exact versions are in `gradle/libs.versions.toml` and in `app/notices/notices.json`. Used
for building and testing only, and not part of an APK: Gradle and the Android Gradle plugin
(Apache-2.0), JUnit 4 (Eclipse Public License 1.0), the AndroidX Test libraries (Apache-2.0),
the Python packages of `native/requirements.txt` under their own licences, and the Android SDK
and NDK under the Android Software Development Kit License Agreement, which you accept yourself
when you install them.

### Keeping the notices right

The files under `app/notices/` are kept by hand, and three checks hold them to what is packaged.
The tasks `generateDebugThirdPartyNotices` and `generateReleaseThirdPartyNotices` (part of every
build of the app and of `check`) compare the `jvm-library` entries of `notices.json` with the
libraries Gradle resolved, and only when the two agree write `NOTICES.txt` into the app, as the
raw resource `third_party_notices`. `ThirdPartyNoticesTest` in `app` holds the models of the
list to the ones `models/manifest.json` marks as bundled, llama.cpp, the NDK and the Android
Gradle plugin to the versions the build pins, and the text to the list and the licence files,
word for word. A native library of an unknown name in an APK fails the task that wrote the APK
(`llama/apk-checks.gradle.kts`).

A failure names what differs. It asks for the notices of the new thing to be read before a
number is changed:

- **A library was added or updated.** Read the `<licenses>` of the artifact's POM and any
  LICENSE or NOTICE file inside the artifact and in its repository at that version (and, for the
  Kotlin standard library and the Markdown renderer, their lists of other people's code inside
  them). Then correct or add its entry in `notices.json` and its mention in part 1 of
  `NOTICES.txt`. A licence the app does not have yet needs its text in `app/notices/licences/`
  and in part 2.
- **llama.cpp was moved to another commit.** Search the files that are compiled (the compile
  commands under `llama/.cxx` name them; nothing of `vendor/` or `common/` may be among them) for
  copyright and licence statements and for "adapted from", "borrowed" and "ported from", and
  bring the entries that sit inside llama.cpp up to date with the commit.
- **The NDK or the Android Gradle plugin changed.** Compare the libc++ block of the NDK's
  `toolchains/llvm/prebuilt/<host>/NOTICE` and the licence of R8 in the plugin's `builder`
  artifact with the two texts in `app/notices/licences/`.
- **A model is bundled, or no longer bundled.** A bundled model has an entry with its
  repository, revision and licence, the copyright lines its repository states and a statement
  of what the conversion changed, and the same in `NOTICES.txt`; any other model has neither.

In `NOTICES.txt` the list of part 1 has one paragraph on one line, which the app wraps to the
screen. The licence texts of part 2 are copied unchanged, line breaks included; the app joins
the lines of each paragraph again when it shows them.
