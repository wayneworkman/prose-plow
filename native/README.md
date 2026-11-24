# Native code

Everything below the JVM: llama.cpp with the few changes this project carries, our own native
libraries on top of it, the model converters, and the host tests that validate all of it against
the PyTorch reference models. Nothing in this directory needs Android: the code is built and
tested on an ordinary Linux machine. The Android build, in `../llama/`, compiles the same
sources for the phone and adds the JNI binding and the packaging of the models; it is described
here too, from "The Android build" on.

Tested on Ubuntu 24.04 (x86_64). Windows and macOS are untested: the scripts need bash 4.4 or
newer, git, tar and a C++17 compiler, so WSL or a recent bash should work, but nobody has tried.

## What is where

| Path | What it holds |
| --- | --- |
| `../third_party/llama.cpp` | Upstream llama.cpp as a git submodule, pinned. **Never edited**, see below |
| `patches/` | The changes to upstream files, as patches, `UPSTREAM_COMMIT`, the commit they apply to, and `README.md`, the step-by-step runbook for moving that commit |
| `llama-ext/` | Files we add to llama.cpp, at the path they get inside it: per architecture `src/models/<arch>.cpp`, `src/models/<arch>.h` and `conversion/<arch>.py`, for `peacebell`, `boris2` and `shard2` |
| `tools/prepare-llama-src.sh` | Writes the patched copy of llama.cpp that everything compiles and imports |
| `tools/make-hook-patch.sh` | The reverse: turns edits made in a patched copy back into a patch |
| `tools/env.sh`, `tools/host-check.sh` | The environment for the commands below, and all of them in one script |
| `convert/` | Converter drivers (checkpoint directory to GGUF): one per ported architecture, and `convert_upstream.py` for models that upstream llama.cpp already supports |
| `lib/` | `proseplow_native`, our library above libllama, with a C interface (`lib/include/proseplow_native.h`): the tokenizer front-end and the chat encoders |
| `session/` | `proseplow_session`, the generation session (`session/include/proseplow_session.h`): one loaded model with one context, driven call by call. Its tests are in `session/tests/` |
| `../llama/` | The Android library module: the Gradle build of all of this for arm64 (`src/main/cpp/CMakeLists.txt`), the JNI binding (`src/main/cpp/proseplow_jni.cpp`), its Kotlin side, the packaging of the models, and the tests that run on a phone |
| `tests/` | Host tests, their committed reference data (`tests/data/`) and the generators of that data (`tests/refgen/`) |
| `CMakeLists.txt` | The build of all of the above |
| `requirements.txt` | The Python packages, every version pinned |

The models themselves, their pinned revisions and hashes are described in `../models/`.

## The submodule is never edited

`third_party/llama.cpp` is upstream, byte for byte, at a pinned commit. No build compiles it in
place and no script writes into it. Every build, converter and test works on a **patched copy**
that `tools/prepare-llama-src.sh` writes somewhere else:

    the tracked files of the pinned commit (git archive: untracked or edited files cannot leak in)
    + every patches/*.patch, applied in name order, exactly (no fuzz) or not at all
    + every file under llama-ext/, copied to the same relative path (adding only, never replacing)

The copy is a function of those inputs. A stamp in it records them, so running the script again
does nothing until an input changes, and then only the files whose content changed get a new
modification time (an incremental build recompiles just those). The CMake project runs the
script at configure time and re-runs it when a patch or an added file changes, so there is no
other way for a llama.cpp source tree to reach a build.

What goes where:

- A **new file** (an architecture's C++ file and header, a converter module) goes under
  `llama-ext/`.
- A **change to an upstream file** goes into a patch, and stays as small as upstream's tables
  demand: an enum value, a name, a `case`. The architectures share one patch,
  `patches/0001-architecture-hooks.patch`: 68 added lines in seven files (22 in C++, 46 in
  Python), two of which replace an existing line. peacebell accounts for 6 and 13 of them,
  boris2 for 10 and 16: it also needs the hybrid memory with both halves in every layer, a
  refusal by the model saver, and the token id of each KV cell kept in a saved state. shard2
  accounts for 6 and 17 and needs no hook for its memory: upstream gives every model with
  windowed layers the cache it needs.
  All architecture hooks live in this one patch because they sit on neighbouring lines of the
  same tables; an unrelated change (a kernel, a loader fix) gets a patch of its own. There are
  four (all line counts here are the ones `tools/make-hook-patch.sh` prints, which leave out
  blank lines):
  - `patches/0002-arm-f16-weights-kernel.patch` (23 added and 3 removed lines in two files)
    makes the CPU backend use tinyBLAS's batch kernel for 16-bit float weights times 32-bit activations on
    every ARM build. Upstream has the kernel but compiles it out of every build with half-float
    arithmetic (`ggml/src/ggml-cpu/llamafile/sgemm.cpp`) and leaves tinyBLAS out altogether where
    the int8 matrix instructions or SVE exist (`ggml/src/ggml-cpu/ggml-cpu.c`), which together
    is every phone processor of the last years. For those builds the patch lets that one pair of
    types through and nothing else. It only takes effect with `GGML_LLAMAFILE=ON`, changes
    nothing on x86, and makes a prompt of an F16 model about three times as fast on the phone
    ("On the phone" has the numbers); generation, one token at a time, does not go through it.
  - `patches/0003-mmap-window-for-embedded-models.patch` (50 added and 9 removed lines in three
    files) makes a model that is loaded from an open file at an offset
    (`llama_model_load_from_file_ptr`) map only its own tensor data. Upstream maps the whole containing file from its first byte
    and asks for all of it to be read ahead; for a model inside an APK that would be every
    other model in the APK as well. The loader now tells the file which bytes are the model's
    (`llama_file::set_mmap_window`), and the mapping, its read-ahead and every later unmapping
    are confined to them, while tensors are still addressed by their offset in the file. A
    model loaded by path is mapped as before. The patch also frees what upstream leaks when
    it refuses to map a model at an unaligned offset. Not adjusted: `mlock` of such a model,
    which this project does not use (the lock fails with a warning and the load goes on).
  - `patches/0004-linear-byte-fallback-in-spm-tokenizer.patch` (one line removed, two lines of
    comment added in its place) makes the SentencePiece tokenizer take time in proportion to
    the text. For a character that is not in
    the vocabulary it emits the character's bytes, and before doing so upstream reserved room in
    the output for exactly those bytes; a vector asked for an exact size every time grows by
    copying itself every time, so a text of such characters took time with the square of its
    length: 4 seconds for 256 KiB of line breaks through the peacebell vocabulary on a desktop
    processor, 0.03 with the line gone. Tokenising cannot be interrupted, which is what made this matter. The line
    is in `resegment` of the SentencePiece tokenizer session in `src/llama-vocab.cpp`, a file
    that no other patch touches.
  - `patches/0005-wipe-token-ids-of-a-restored-state.patch` (20 added lines in one file) makes
    llama.cpp overwrite two lists before it frees them. When it reads the state of a sequence
    back (`llama_kv_cache::state_read_meta`, which an import of a memory and a change of
    context size that carries the memory over both go through), it collects the cells to
    restore in a batch of its own and in a list of what each cell recorded, and for a model
    whose state carries the id of every token (Qwen3.5, Boris-2) both hold the ids of the
    whole conversation, in order. Upstream frees them as they are, and `<model>.residue` then
    found every window of the reply's ids in the freed blocks, for Qwen3.5 still after the
    conversation was forgotten and the model closed. The patch is one object in that function
    whose destructor zeroes the two lists on every way out of it, through a volatile pointer.
    It is in the file in which 0001 makes a boris2 cell record its token id, 500 lines from
    that line and independent of it, and has to be looked at again whenever upstream changes
    `state_read_meta`: a list of token ids added there would be freed as it is.
- Only `libllama` is built and linked. llama.cpp's helper library (`common`) contains an HTTP
  client and a downloader; the CMake project fails if that target even exists.

## What you need

| Tool | Version that was used | Where from |
| --- | --- | --- |
| Ubuntu | 24.04 LTS, x86_64 | |
| GCC (`gcc`, `g++`) | 13.3.0 | `apt install build-essential` |
| git | 2.43.0 | `apt install git` |
| bash, tar, coreutils | 5.2.21, 1.35, 9.4 | part of the system |
| Python | 3.12.3, with the `venv` module | `apt install python3 python3-venv` |
| CMake | 4.4.5 | pip, see below |
| Ninja | 1.13.2 | pip, see below |
| PyTorch (CPU only) | 2.15.0+cpu | pip, see below |
| numpy, transformers, tokenizers, sentencepiece, protobuf, safetensors | 2.5.5, 5.24.0, 0.23.3, 0.2.3, 7.36.2, 0.8.0 | pip, see below |

CMake, Ninja and every Python package come from `requirements.txt`, which pins all of them and
everything they depend on. Create a virtualenv and install into it (about 1.3 GB, most of it
PyTorch; nothing here uses a GPU, and the CPU-only wheel is selected in the file). It is the
same one the root `README.md` creates ("The Python environment") and the Gradle build converts
the models with; `~/proseplow/venv` is where both look unless `PROSEPLOW_VENV` says otherwise:

```sh
python3 -m venv ~/proseplow/venv
~/proseplow/venv/bin/pip install -r native/requirements.txt
```

No other C or C++ library is needed, and nothing is installed system-wide.

## Build and test

Three locations are passed in environment variables, each with a default that is also the
Gradle build's; nothing is ever written next to the sources:

| Variable | Default | Meaning |
| --- | --- | --- |
| `PROSEPLOW_VENV` | `~/proseplow/venv`, when it exists | The virtualenv from above. Optional when it is already activated |
| `PROSEPLOW_BUILD` | `~/proseplow/build` | A scratch directory for everything generated: the patched copy, the build tree, converted models, on-demand reference data. About 5.5 GB after a full test run (5.0 GB of it the converted models), 7.5 GB while it runs |
| `PROSEPLOW_MODEL_SRC` | `~/proseplow/checkpoints` | The directory that holds the downloaded checkpoints (see `../models/README.md`). When a checkpoint is not there, the code still builds and the tests that need its weights are skipped |

Optional: `PROSEPLOW_JOBS` (parallel compile jobs, default all processors), `PROSEPLOW_THREADS`
(inference threads in the tests, default 4), `PROSEPLOW_MODEL_OUT` (where converted models go,
default `$PROSEPLOW_BUILD/models`), `PROSEPLOW_LLAMA_SRC` (the patched copy, default
`$PROSEPLOW_BUILD/llama-src`), `PROSEPLOW_PEACEBELL_PACKAGES` (checkpoint directories of
peacebell models that are not published yet, separated by `:`, for the one test that reads
them; see `peacebell-bpe-packages` below).

Everything in one command, from the repository root:

```sh
git submodule update --init third_party/llama.cpp     # once, after cloning

native/tools/host-check.sh       # after exporting any of the three that differs from its default
```

The same, step by step:

```sh
. native/tools/env.sh                                   # tools on PATH, CPU only
native/tools/prepare-llama-src.sh                       # the patched copy, $PROSEPLOW_LLAMA_SRC
cmake -S native -B "$PROSEPLOW_BUILD/host" -G Ninja -DCMAKE_BUILD_TYPE=Release
cmake --build "$PROSEPLOW_BUILD/host" -j "${PROSEPLOW_JOBS:-4}"
python3 models/convert_models.py --also-f32 --available # F16 to ship, F32 to validate
ctest --test-dir "$PROSEPLOW_BUILD/host" --output-on-failure
```

A clean build takes about 40 seconds with 6 jobs, the conversions a few seconds per file (a
minute for all nine), the test suite about fourteen minutes with 6 threads. The build is CPU
only, static, without OpenMP and without ccache, and by default tuned to the build machine
(`-march=native`; pass `-DGGML_NATIVE=OFF` to cmake for a portable binary). The explicit
conversion step is optional: the tests convert what they need and reuse it while it is current.

`ctest -R tokenizer`, `ctest -R 291m`, `ctest -R logits-f32` and so on select tests by name;
`ctest -V` shows each test's measurements even when it passes.

## What the tests check

Each test prints one line per check with the measured value and its limit. A test whose
checkpoint is not on the machine prints `SKIPPED:` with the checkpoint it looked for and is
reported by ctest as skipped, never as failed.

| Test | What must hold |
| --- | --- |
| `<model>.convert-determinism` | Two fresh conversions and the converted file have the same SHA-256, for F32 and F16 (F16 alone for a stock architecture, which has no validation file) |
| `<model>.gguf-f32` | Every tensor of the F32 file equals the checkpoint tensor byte for byte; values carried as metadata equal the stored ones exactly (peacebell: the ReZero scalars; boris2: the four n-gram lookup arrays, with their integer types); hyper-parameters equal `config.json`; vocabulary, token types and the recorded normaliser equal the tokenizer file. boris2: the 31 Canon kernels equal the documented transform of the stored ones. shard2: the checkpoint's one unused tensor is the only one left out; the window, the two per-layer flag arrays (which layers are windowed, which rotate), the epsilon and the special ids are what the checkpoint and its reference code say; the vocabulary is the tokenizer's followed by the deletion token, and the four tokens that are not text are control tokens |
| `<model>.gguf-f16` | Every matrix is exactly the round-to-nearest half-precision cast of the checkpoint; the tensors the architecture keeps in float32 are still float32 and unchanged (norm weights; boris2: also the Canon kernels and the n-gram projection); no infinities; under 0.5% half-precision subnormals in every tensor; metadata identical to the F32 file |
| `<model>.tokenizer` | peacebell: the front-end reproduces the committed token vectors: 0 mismatches in exact mode and in safe mode, and safe mode never produces a chat marker from text; plus the calling contract (buffer sizes, malformed UTF-8, null arguments). boris2: llama.cpp's own tokenizer reproduces the committed vectors of the `tokenizers` library with special tokens parsed and as plain text, where the end-of-text token never occurs; `pp_chatml_text_tokenize` reproduces 118 conversations rendered by transformers' template renderer, among them every character the template's trim removes; every reported message end is the token count of the shorter conversation; the calling contract. shard2: llama.cpp's own tokenizer reproduces the committed vectors, which add 507 texts that spell its four control tokens (`<pad>`, `<bos>`, `<eos>` and the deletion token `<bs>`, which the tokenizer file does not contain); typed as text each of them stays plain characters, none of them is ever produced from text, and none contributes anything when a token is turned back into text. Every model of this test is held to the last three |
| `<model>.tokenizer-live` | The same against the reference library itself (SentencePiece, or `tokenizers` and transformers), on a larger corpus generated at test time; for SentencePiece it puts every user-defined symbol of the tokenizer file in every awkward position |
| `<model>.logits-f32` | F32 file, F32 KV cache, no flash attention: logits within 5e-3 of the PyTorch reference for one batch, token by token, uneven batches, micro-batch sizes 1, 2, 7, 32 and 512, and after a rewind; all of those within 5e-3 of each other over the whole vocabulary; 20 rewinds at arbitrary positions equal to a fresh evaluation, with a control that the wrong prefix does change the logits; greedy replies identical to the reference. peacebell: a suppressed token is never sampled, even against a +1e9 bias. boris2 and shard2: see below |
| `<model>.logits-f16` | F16 file as it ships (F16 KV cache, default attention path), same ways of feeding the tokens: mean KL at most 1e-5 (boris2: 5e-5) and max KL at most 5e-3 against the fp32 reference, top-1 agreement at least 99.5%. Greedy divergence, if any, is reported. shard2: also 4,000 tokens sampled with the reference script's settings, with the share of the deletion token and of each special id |
| `peacebell-bpe-random` | All of the peacebell checks for a seeded random checkpoint with the shape of the family's byte-level BPE members, none of which is published yet: the converter's other tokenizer branch, llama.cpp's own tokenizer against the `tokenizers` library, the plain-text ChatML encoder, the sampler mask on the training pad token, and a chat through the session (`session/tests/test_chat_text.cpp`: a conversation with a system message rendered, evaluated in chunks and answered; an earlier message changed and the memory cut back to what the two conversations share; a reply ended by the end-of-text token and by the end marker's text, whose first character arrives inside another token; the next turn appended without a cut). Then the same weights without a chat template, which must give a file that says `completion`, and with a template the encoder does not implement, which the converter must refuse |
| `peacebell-bpe-packages` | The same checks, with text instead of random tokens as reference sequences, for each checkpoint directory named in `PROSEPLOW_PEACEBELL_PACKAGES`: the real byte-level BPE members exist as packages of the training pipeline before they are published. A package with a chat template is tested as a chat model, one without as a model that continues text; a package that records training step 0 is untrained, and three limits that only make sense for a trained model are relaxed for it (ties in the top-1 count, the size of the rewind control, the subnormal share). The package's own model code is never run: the reference is the verified code of the published family with the package's weights. Skipped wherever the variable is not set |
| `boris-2-instruct.chat-f32`, `.chat-f16` | A session of eight chat requests served from one context whose memory is kept between them (a turn appended, the same request again, an earlier message changed, another beginning): the encoder gives the reference's prompt tokens; every rewind goes through a checkpoint (the cut is refused before the restore and succeeds after it) or starts over when none fits; each reply, ended by the stop string, equals the reply the reference generated from scratch, as tokens and as text (F16: reported) |
| `boris-2-instruct.no-ngram-lookup` | The F32 file with its four lookup arrays removed, against the reference with the lookup switched off: all the checks of `logits-f32`. It separates Canon and the hybrid memory from the n-gram input |
| `<model>.session` | The generation session against the file that ships, for each bundled model (peacebell-v1-291m, boris-2-instruct, qwen3.5-0.8b, shard-2): what must fail when opening and how; tokenising equal to the committed vectors as plain text and with special tokens parsed; evaluation in chunks with progress; sampling only from a fresh prediction; greedy replies equal to the reference's, whatever the chunk size; shortening the memory (peacebell and Shard-2: the end is removed and the continuation equals a fresh evaluation; Boris-2 and Qwen3.5: the removal is refused, the memory is untouched by the refusal, and a checkpoint brings it back with an identical continuation, twice; Shard-2 further, on a text of 1,500 tokens: a cut many windows back is exact, a checkpoint brings the memory back exactly, and after that restore a cut below what the restored window reaches is refused with the memory untouched while a cut within it is made); clearing; banned tokens never sampled, greedy or at temperature 2; cancelling between chunks and (peacebell) inside one; Boris-2: its end-of-text token refused as input; threads pinned one per CPU, re-pinned after the affinities were replaced from outside, and unpinned when a listed CPU is not available; memory given back on close; the model loaded by descriptor, offset and length from a larger file next to another model, read and mapped, with the mapping covering the model's bytes only; mapping refused at an unaligned offset; a context too small. And the limits on what comes from outside: a context or batch of 2,147,483,647 tokens, a context above the ceiling or above what the model was trained for, a text one byte too long, a ninth checkpoint, more banned tokens than the vocabulary has and a model cut short by less than its header are each refused with their status and without an allocation, a penalty window of 2,147,483,647 costs nothing, 256 KiB of line breaks, Chinese or emoji tokenise in time proportional to their length, every token banned gives `SAMPLING_FAILED` and a denormal temperature the greedy token, forgetting in the middle of a prompt works, and the threads are refitted when the affinities are replaced in the middle of one call |
| `<model>.penalties` | For each bundled model (peacebell-v1-291m, boris-2-instruct, qwen3.5-0.8b, shard-2), what the sampling penalties cover. A second context on the same file, which the session does not know, scores the same memory, and the penalties' definitions are applied to its scores as the test writes them out: the session's greedy choice must be the token that comes out on top, step by step, for a repetition penalty over the whole memory (1.1 and 1.6), for presence and frequency penalties over the reply alone (Qwen3.5's 2.0 among them), after going back to the end of the prompt and into the middle of a reply without configuring the sampler again (by removing the end of the memory where the model allows it, and through a checkpoint), with every checkpoint dropped, after a clear, after a prompt whose evaluation was cancelled and finished by a second call, with a window, and for a reply that continues an answer already in memory, configured with `reply_start` at the end of the prompt (presence and frequency count the whole answer, told from counting where this sampling began, again after going back through a checkpoint; a value below -1 refused). Each check names the mistake it is there to catch (a penalty over the reply alone, one that counts the prompt too, a record that still holds tokens which left the memory or lacks tokens that are in it) and fails when no step would have exposed it. Boris-2 further, against `boris-2-instruct-penalty.bin`: six requests of a conversation served from one memory, greedy with the model's repetition penalty of 1.1, choose the tokens that transformers' `generate()` chooses (128 reply tokens; a reference choice with a lead below 0.1 is reported, not required); at the first position of each reply, where only the prompt can be penalised, the logits and the scores after the penalty are the reference's to within 0.05 and the session's first choices in order are the reference's; with the penalty off the first token is the reference's without a penalty |
| `<model>.context` | For each bundled model, the size of a session's context. The model's `trained_context` and `context_bytes_per_token` of `models/manifest.json` are held to the file. What a session that knows two sizes says a context costs is at least what the allocator handed llama.cpp for contexts of 256, 512 and 1,024 tokens (less 0.01%, the count's own noise between two states of the process) and at most 1% more; at the model's largest context it is at least, and at most 3% more than, llama.cpp's own sizing of it from a model loaded without allocating, so nothing large is allocated; a session that knows one size costs a larger one at least at what is allocated. A session whose batch (512) is larger than a context it creates (256) costs a larger size exactly as before that context, and at least what llama.cpp allocates for it at that batch. Memory freed on another thread while a context is created (through llama.cpp's log, which the test hooks) does not lower any later cost. With llama.cpp's report made unreadable the same way, an opening with a budget is refused and one with a budget of `INT64_MAX` opens and refuses every change of size. A size above the model's largest or above the budget, also the present size with a budget below its cost, is refused with the memory, a checkpoint and a ready prediction untouched; a budget one byte below the peak of a carry-over comes back with an empty memory and one at the peak with the memory carried over, its checkpoint valid and its continuation the same as a fresh evaluation at that size; a reply carried over goes on with its penalties counting from where it began, told from a reply begun anew at the change; a size smaller than the memory empties it. With the address space of the test process limited (never by using memory up; at the moment the old context is freed for the last two), a larger size that cannot be mapped leaves the previous size with its memory carried over and the reply going on as before, a tighter limit leaves half of it with the memory carried into it, none at all leaves no context and no memory, and a clear repairs that; opening over the budget is refused, by the check after the allocation for a budget just below the cost and by the estimate before it for half the cost. The check of llama.cpp's report says in its own text what to do when the report's form changes |
| `<model>.memory` | For each bundled model, the memory written out as an image and read back (`pp_session_export_memory`, `pp_session_import_memory`). Two sessions do the same, call for call: a prompt with two checkpoints and the beginning of a reply; one has its memory written out, forgotten and read back, the other is never interrupted. `pp_session_memory_state_bytes` is exactly what the sink is handed, in pieces of the buffer's size and a last one, "last" said once; the image holds the token ids and then the state byte for byte as `llama_state_seq_get_data_ext` writes it into one block, and it grows with each token by exactly the model's `context_bytes_per_token` of `models/manifest.json` and 16 to 28 bytes more. After the import the state of the sequence, taken from llama.cpp in one block, is byte for byte what it was, the memory written out again gives the same image, the record of the tokens and both checkpoints are back, and no prediction is ready. The prediction after the next token is the control's bit for bit over the whole vocabulary, and so are the scores at each of 64 greedy steps; replies chosen with a repetition penalty over the whole memory and with presence and frequency penalties are the control's; going back through the checkpoints that came out of the image gives, bit for bit, what the control predicts after going back through its own. The image goes into a context twice the size and into one its tokens fill exactly, and one token too many is refused (`PP_STATUS_CONTEXT_FULL`) after one piece. An export stopped by its sink at every piece in turn leaves the memory, the checkpoints and the prediction that was ready as they were (the prediction is compared bit for bit after every one). An import whose source fails at every call in turn, the call after the last piece included, or whose image ends before every piece in turn, leaves no token, no record, no checkpoint and zeros in the caller's buffer, and the session then predicts bit for bit what a new session predicts. Refused with nothing left behind: a piece or a byte behind the image, a source that returns more than its buffer holds, each of the image's first 176 bytes changed in turn (but for a checkpoint's length changed into another length within the memory, which the test counts and names), a token id outside the vocabulary, a state one byte shorter or longer than llama.cpp needs (stopped at the state, not at the image's end), a state llama.cpp refuses, a checkpoint larger than one of the whole memory; and with nothing touched: an import into a memory that holds tokens or beside a checkpoint, an export of an empty memory, of an unknown or twice-named checkpoint or of one beyond the end of the memory, and every unusable sink or source. The caller's own bytes at the start of the first piece come through unchanged. With the address space of the process limited to 2 MiB more than is mapped, where a block of the state's size cannot be allocated, the export and the import of an image without checkpoints both succeed. Shard-2 further, with a memory of 600 tokens against its window of 256: after the import a cut far back is refused with the memory untouched, a cut of the last token is made, and the prediction is within 5e-3 of the control's. The test reads the scores and the state of the context a session works with, which a session does not show: it is linked with `--wrap=llama_init_from_model`, so that every context the session library creates passes through a function of the test |
| `<model>.residue` | For the four bundled models: after a text was tokenised and evaluated and a reply sampled, `pp_session_clear_memory` and `pp_session_close` leave no token ids of the reply anywhere in the writable memory of the process (a stretch of the reply that also occurs in the prompt is counted with the prompt). How much of the text and of the prompt's token ids is still there is reported, not required ("Forgetting" below says why). Then the conversation again with a checkpoint, written out as an image that the test keeps masked: after the export and a clear, after an import that its source stopped at the last call with the whole image in, after a complete import and a clear, and after closing, no stretch of the memory's key and value rows or recurrent state is found (24 bytes from each of eight places of the image) and no token ids of the reply. For the models whose state carries the id of every token (Qwen3.5, Boris-2, which the test finds out from the image) that last holds only with patch 0005, and this test is what holds the patch to it |
| `qwen3.5-0.8b.load-generate` | The stock architecture's file loads with our build: architecture `qwen35`, tokenizer `gpt2` / `qwen35`, the ids of the end-of-sequence, padding, ChatML, thinking and tool markers; ChatML markers inside a message are plain characters while thinking and tool markers are still tokens; a greedy reply to a ChatML prompt is identical after a rewind through a checkpoint of the recurrent state, after clearing the memory and in a new context |

What `logits-f32` and `logits-f16` check for boris2 beyond the list above, because its Canon
layers keep a recurrent state that cannot be rewound:

- the reference's greedy replies, which it generated by recomputing the whole prefix for every
  token, are reproduced by decoding one token at a time;
- `llama_memory_seq_rm` into the sequence returns false, removes nothing and damages nothing,
  and decoding at the refused position is rejected; a cut at the end and the removal of the
  whole sequence succeed;
- every rewind in the test restores a checkpoint (`LLAMA_STATE_SEQ_FLAGS_PARTIAL_ONLY`) first,
  some of them a few tokens before the cut with the tokens in between decoded again;
- the negative control: the KV cache cut back while the Canon rows are those of the old tail
  must change the logits by more than 0.5 (the state is built from two sequences, because the
  API refuses to produce it for one);
- a full sequence state restored into a new context gives bit-identical logits for the next
  tokens, at a position where the n-gram row depends on the restored token history;
- two sequences of different lengths, interleaved in one batch, come out as each does alone;
- the size of a checkpoint;
- with the F32 file, in a run of its own because it ends the process: feeding the end-of-text
  token trips the graph's assertion.

What `logits-f32` and `logits-f16` check for shard2. Both are judged against the reference's
whole logit vector at every position (the vocabulary has 4,097 tokens, so that costs little),
on five short prompts with greedy continuations and on sequences longer than the window of its
local layers: 300, 600 and 1,500 tokens against a window of 256, made of a prompt and what the
reference itself sampled after it, deletion tokens included, plus one sequence in which each of
the four special ids is an input.

- every way of feeding the tokens (one batch, token by token, uneven batches, micro-batches of
  1, 2, 7, 32 and 512) with the local layers' cell store full-size and pruned (see "The shard2
  architecture" for the two);
- the logits of the ids 0, 1 and 2 and of the deletion token one by one at every position, and
  the deletion token's log-probability;
- the reference's greedy continuations, which it generated by recomputing the whole prefix for
  every token, reproduced by decoding one token at a time;
- **the context**: one sequence of 8,192 tokens, twice the length the model was trained on,
  against the reference in four stretches; a token beyond the context is refused and nothing is
  lost; the last tokens decoded again one at a time; the pruned store over the same length;
- **cuts of the memory**, which llama.cpp never refuses for this cache: with the full-size
  store 20 successive cuts at arbitrary positions, most of them many windows back, equal a
  fresh evaluation (with the control that a wrong beginning changes the logits); with the
  pruned store the cuts that the `llama_memory_seq_pos_min` test allows equal a fresh
  evaluation, down to the last token it allows, and the cuts it forbids are accepted all the
  same and are wrong by more than 0.5 (the negative control), also for the full-size store
  once a checkpoint was restored into it;
- the discipline: cuts that the test forbids, made through a checkpoint instead (restore, cut
  at the checkpoint's length, decode forward), equal a fresh evaluation; the size of a
  checkpoint; the removal of the whole sequence;
- a full sequence state restored into a new context: the next tokens within the limit (not bit
  for bit: the restored cells land in other slots), and the local store then begins at the
  window of the saved position;
- with the F32 file, in three short runs of their own: a file without the window or without
  one of its two per-layer flag arrays is refused when it is loaded.

For orientation, the values measured when this was written (6 threads, a desktop CPU with AVX2):

| | peacebell-v1-291M | peacebell-v1-148M | Boris-2-Instruct | Shard-2 |
| --- | --- | --- | --- | --- |
| F32, largest logit error against the reference (limit 5e-3) | 8.7e-5 | 3.1e-5 | 1.7e-4 | 6.7e-4 up to 1,500 tokens, 3.6e-3 over 8,192 |
| F16, mean KL / max KL (limits 1e-5, boris2 5e-5 / 5e-3) | 9.7e-7 / 3.8e-5 | 6.3e-7 / 9.8e-6 | 8.6e-6 / 4.9e-4 | 2.7e-6 / 2.4e-5 (5.5e-5 over 8,192 tokens) |
| F16, top-1 agreement; largest logit error | 100%; 0.034 | 100%; 0.020 | 100%; 0.27 | 99.93%; 0.038 |
| Greedy replies identical to the reference, F32 / F16 | 5 of 5 / 5 of 5 | 5 of 5 / 5 of 5 | 5 of 5 / 5 of 5, and 8 of 8 chat replies | 5 of 5 / 5 of 5 |
| Generation, F32 / F16, tokens per second | 38 / 77 | 74 / 139 | 80 / 150 | 154 / 284 |
| Prompt in one batch, F16, tokens per second | 560 | 1040 | 1100 | 2780 |

Boris-2 further: 0 tokenizer mismatches in 7,642 committed and 25,045 live texts and in 118
conversations; a rewind through a checkpoint within 1.8e-4 of a fresh evaluation, the same
rewind with stale Canon history off by 4.3 at least; a checkpoint is 381,700 bytes. The stock
Qwen3.5-0.8B file generates about 30 tokens per second, and its checkpoint is 20,201,932 bytes.

Shard-2 further: 0 tokenizer mismatches in 8,139 committed and 25,145 live texts; the logits of
its ids 0, 1, 2 and of the deletion token within 4e-4 of the reference at every position (F32);
a cut of the memory that the `llama_memory_seq_pos_min` test allows within 2e-5 of a fresh
evaluation, a cut it forbids off by 1.6 at least, and by 0.02 when one key of 255 is missing; a
checkpoint is 8,392,096 bytes with an F16 cache; 6.3% of 4,000 sampled tokens are the deletion
token, the ids 0 and 1 never come and id 2 once. Its speeds are for the sequence of 1,500
tokens and for single tokens fed with two threads, which this small a model prefers when the
machine is busy; a context of 8,192 tokens fills at 1,900 tokens per second (F16), and through
the session with 6 threads it generates about 440 tokens per second. All of these moved by a
factor of two with the load of the shared machine they were measured on.

### Reference data

The reference is always the PyTorch model, fp32 on the CPU.

- Committed in `tests/data/` (5.0 MB): the token vectors `peacebell-v1-tokens.bin` (7,642 texts
  and 22 conversations with the ids of the SentencePiece library, in both modes),
  `boris-2-instruct-tokens.bin` (the same texts and 118 conversations with the ids of the
  `tokenizers` library) and `shard-2-tokens.bin` (the same texts and 507 that spell its four
  control tokens; no conversations, the model has no chat format); per checkpoint,
  `<model>-logits.bin` (five conversations, for Shard-2 five prompts, with the reference's
  greedy reply; the top 20 logits at every position and the whole logit vector at the last
  prompt position); `boris-2-instruct-chat.bin` (the chat session with the reference's
  replies); `boris-2-instruct-penalty.bin` (6,098 bytes: six requests of a conversation with
  the replies that transformers' `generate()` gives with the model's repetition penalty of
  1.1 and without it, the lead of every chosen token, and the 16 highest scores of each
  reply's first position before and after the penalty); and `shard-2-long-logits.bin` (the
  sequences longer than its attention window: 300, 600 and 1,500 tokens sampled by the
  reference, and one that feeds every special id). These pin the expected behaviour
  independently of what is installed.
- Generated on demand into the build tree, because they are too large to commit: the logits of
  the whole vocabulary at every position (the KL test needs them; for Shard-2 both precisions
  are judged against them, and one more file covers a sequence of 8,192 tokens, 186 MB in
  all), the live token vectors, the random checkpoint, and the boris2 reference without the
  n-gram lookup. A regenerated reference is first compared with the committed one, so a
  PyTorch that computes something else is noticed.
- For Shard-2 the on-demand files take their token sequences from the committed ones instead
  of making them again: its long sequences were sampled, and a logit that differs in its last
  bit on another machine could choose another token. Sampling and greedy choices are made
  once, when a committed file is written.

The generators are in `tests/refgen/`; the four binary formats are described in
`tests/refgen/formats.py`. To regenerate the committed files after a checkpoint or tokenizer
changes (with exactly the package versions of `requirements.txt`):

```sh
python3 native/tests/refgen/gen_logit_ref.py --model-dir /path/to/checkpoint --out native/tests/data/<model>-logits.bin
python3 native/tests/refgen/gen_token_vectors.py --model-dir /path/to/peacebell --out native/tests/data/peacebell-v1-tokens.bin
python3 native/tests/refgen/gen_token_vectors.py --model-dir /path/to/boris-2 --out native/tests/data/boris-2-instruct-tokens.bin
python3 native/tests/refgen/gen_chat_ref.py --model-dir /path/to/boris-2 --out native/tests/data/boris-2-instruct-chat.bin
python3 native/tests/refgen/gen_penalty_ref.py --model-dir /path/to/boris-2 --out native/tests/data/boris-2-instruct-penalty.bin
python3 native/tests/refgen/gen_logit_ref.py --model-dir /path/to/shard-2 --long --out native/tests/data/shard-2-long-logits.bin
python3 native/tests/refgen/gen_token_vectors.py --model-dir /path/to/shard-2 --out native/tests/data/shard-2-tokens.bin
```

The Boris-2 reference has no cache, so its replies are made by a forward pass over the whole
prefix for every token: generating the first three files takes about a minute with 6 threads
and the penalty reference a little over two minutes. The
three Shard-2 files take about a minute as well. Run these with `python3 -I` when the
checkpoint directory is not your own work: `-I` keeps the current directory and the user's
site packages off the module search path.

The committed token vectors contain only text written for this project and seeded random text,
with the symbols every tokenizer of the family has (years, month names, the URL scheme, the chat
markers). Symbols specific to one tokenizer file are covered by the live test, which reads them
from the file. The committed Shard-2 logit references contain prompts written for this project
and what the model itself wrote after them.

## The peacebell architecture

`general.architecture = "peacebell"`: a pre-norm decoder with RMSNorm, a fused q/k/v projection,
NeoX RoPE over the whole head, an ungated exact-erf GELU MLP, a ReZero scalar on each sublayer
and an output head tied to the embedding. No existing llama.cpp architecture computes this (the
ones with an ungated GELU use LayerNorm and the tanh approximation, which alone costs 0.1 in
the logits), hence the small architecture file.

- Nothing is specific to one checkpoint: width, depth, heads, context length, vocabulary and
  RoPE base come from GGUF metadata. The same code runs both published checkpoints and the
  random stand-in of the third.
- The ReZero scalars are two float32 metadata arrays (`peacebell.attention.output_scale`,
  `peacebell.feed_forward.output_scale`) applied as a multiply in the graph. They are not folded
  into the weights: folded, a fifth of layer 0 would be half-precision subnormals in the file
  that ships.
- Float formats: **F16 ships**; norm weights and the scalars stay float32 in it. F32 exists to
  validate the port bit for bit against the stored weights and is never shipped.
- Tokenizer: the SentencePiece checkpoints need the front-end in `lib/` (next section). The
  converter refuses a SentencePiece model whose normaliser the front-end does not implement and
  records what it saw under `peacebell.tokenizer.*`; the front-end checks that record when it
  loads.
- The family's other tokenizer is a byte-level BPE (`tokenizer.ggml.model = gpt2`, pre-tokenizer
  `gpt-2`), which llama.cpp reproduces token for token without a front-end. Those checkpoints
  have a training pad token whose output row was never trained; the file lists it under
  `tokenizer.ggml.suppress_tokens`, and whoever samples must ban it.
- `peacebell.chat.format` says how a prompt is built: `chatml-ids` (SentencePiece: the two marker
  ids around separately encoded segments), `chatml-text` (byte-level BPE with the plain-text
  ChatML template: `pp_chatml_text_tokenize`, a reply ends on the marker's text or the
  end-of-text token) or `completion` (byte-level BPE without a chat template: the prompt is the
  text, the end-of-text token ends it). The converter decides from the checkpoint's files and
  refuses a chat template other than the one the encoder writes out.

## The boris2 architecture

`general.architecture = "boris2"` (opencerebral/Boris-2-Instruct): the block of Phi-3, that is
a pre-norm decoder with RMSNorm, a fused q/k/v projection, NeoX RoPE over the whole head, a
fused SwiGLU MLP and a head tied to the embedding, with two additions that no stock
architecture computes.

- **Canon.** After the fused projection each of its 1,536 channels is mixed with its own
  values at the two preceding positions. The graph does this with `ggml_ssm_conv` over the two
  saved columns followed by the new ones, so a batch and a single token run the same graph.
  The kernel is `blk.N.shortconv.conv.weight`: the checkpoint's three columns in reverse order
  with the identity folded into the tap of the current position.
- **An n-gram embedding.** A row of a rank-64 table, chosen by an exact lookup of the token
  pair or triple that ends at a position (the trigram wins), is projected to the model width
  and added to the residual stream before block 2. The lookup runs on the host, in a graph
  input defined in the architecture file: its keys are 64-bit integers, which ggml cannot
  compute with. The keys and rows are four sorted GGUF metadata arrays
  (`boris2.ngram.bigram_keys` and so on, 2.8 MB), not tensors. The two preceding token ids are
  not kept by the input: the KV cache stores the token id of every cell, so the history is
  whatever the cache holds and follows a rewind for free.

Facts that matter when working on it:

- Width, depth, heads, context length, RoPE base, the block that receives the n-gram embedding,
  the table's size and its miss row all come from metadata. A file without the four lookup
  arrays is valid and gives every token the miss row; a test runs the model that way to tell a
  fault in Canon or the memory from a fault in the n-gram input.
- **Memory.** Canon at a position needs the raw projections of the two positions before it at
  every layer. No KV cache holds them (it stores K and V after Canon and RoPE, and no Q), so
  the architecture uses llama.cpp's hybrid memory with both halves in every layer: a KV cache,
  and a recurrent row of 2 x 1,536 float32 that is read, prepended and stored back by the
  helper Qwen3.5's recurrent layers use (`llm_build_delta_net_base::build_conv_state`).
- Float formats: **F16 ships**: matrices and the n-gram table in half precision, the norm
  weights, the Canon kernels (ggml's convolution takes a float32 kernel) and the n-gram
  projection in float32. F32 validates the port and is never shipped.
- **One sequence is one document.** The reference restarts positions, attention, Canon and the
  n-gram lookup after every end-of-text token (id 0). The graph has no such reset: it is exact
  as long as that token is never an input, and it stops the process (`GGML_ASSERT`) if one is
  fed rather than compute something the reference would not. In chat use the token is never
  fed: text tokenised without special-token parsing cannot produce it, and a generated one ends
  the reply. A program that wants to say why it is going down can install
  `ggml_set_abort_callback`, as the test of this refusal does; to start a new document, remove
  the sequence and decode from position 0.
- Not supported, and refused when the file is loaded or the batch is fed: grouped-query
  attention, a Canon kernel other than 3 taps, embedding input, a token that belongs to several
  sequences.
- The tokenizer needs no front-end: `tokenizer.ggml.model = gpt2` with the pre-tokenizer
  `gpt-2` reproduces the reference's byte-level BPE token for token.
- Nothing was run on a GPU backend or on ARM.

### Chat format

ChatML is plain text for this model (`boris2.chat.format = chatml-text`): the markers are not
tokens. `pp_chatml_text_tokenize` in the native library renders a conversation as the model's
chat template does and tokenises it in one call. What follows from that:

- **A reply ends on a stop string**, `<|im_end|>` in the decoded text. The marker's first
  character can share a token with the text before it (`.<` is one token), so there is no token
  sequence to wait for. While streaming, hold back any tail of the text that is a prefix of the
  marker. The end-of-text token (`llama_vocab_is_eog`) ends a reply too and is never fed back.
- **Marker text in a message is a marker.** A message that contains `<|im_end|>` closes its
  turn as far as the model can tell, because a real marker is the same characters. The
  peacebell front-end has a safe mode for this; here there is nothing to withhold.
- The template trims the content of every message with Python's `str.strip()`, which removes
  29 code points and not what other languages call whitespace (Kotlin's `trim` keeps the
  no-break space, for one). The encoder implements exactly that list.

### Checkpoints: what an application must do to rewind

A KV cache can be cut at any position. The Canon rows cannot: they hold the history of the
last position only. So for this model `llama_memory_seq_rm(mem, seq, p, -1)` with `p` inside
the sequence **returns false and removes nothing**, unless the recurrent state of exactly
position `p` was restored first. (Removing the whole sequence, `p = -1`, always works.)

The state to save is the partial sequence state, which for this architecture is the Canon rows
and nothing else: **381,700 bytes** (31 layers x 2 columns x 1,536 float32, plus 772 bytes of
framing), whatever the length of the conversation.

```c
// Save, when the memory holds exactly n tokens of the sequence:
size_t size = llama_state_seq_get_size_ext(ctx, seq, LLAMA_STATE_SEQ_FLAGS_PARTIAL_ONLY);
llama_state_seq_get_data_ext(ctx, buffer, size, seq, LLAMA_STATE_SEQ_FLAGS_PARTIAL_ONLY);   // returns size

// Rewind to n tokens, in this order:
llama_state_seq_set_data_ext(ctx, buffer, size, seq, LLAMA_STATE_SEQ_FLAGS_PARTIAL_ONLY);   // returns size
llama_memory_seq_rm(llama_get_memory(ctx), seq, n, -1);                                     // returns true
// then decode from position n
```

The discipline, which `tests/test_boris2_chat.cpp` implements and checks against the reference:

1. **When to save.** After decoding up to the end of each message and up to the end of the
   generation prompt. `pp_chatml_text_tokenize` reports the token count at which each message
   ends; those counts are token boundaries of the whole conversation, so a conversation that
   changes later can be cut there.
2. **Serving a request.** Tokenise the whole conversation and take its common prefix with the
   tokens in memory, **less one token**: a checkpoint restores state, not logits, so the last
   token of the prompt is always decoded again. If tokens must go, restore the latest checkpoint
   at or before that prefix, cut the memory at the checkpoint's token count, drop the later
   checkpoints, and decode from there, saving new checkpoints on the way.
3. **No usable checkpoint** (the conversation differs before the first one): remove the whole
   sequence and decode from position 0.
4. **Check every return value.** `llama_memory_seq_rm` returning false is the signal that a
   checkpoint was needed and missing.

Typical costs: a new turn appends and rewinds nothing; "regenerate" goes back to the checkpoint
at the end of the last message and decodes the 10 tokens of the generation prompt again; an
edited message goes back to the checkpoint before it. A reply whose text the template trims,
or that the model spelled with other tokens than the tokenizer would, simply shortens the
common prefix: the checkpoint at the end of the generation prompt is then the one that serves.

What happens when the discipline is skipped:

- A cut without the restore is refused, and decoding at the cut is then rejected by
  llama.cpp's position check (`llama_process` returns -1): the failure is loud.
- If the KV cache were cut while the Canon rows stayed those of the old tail, nothing would
  crash and every following logit would be wrong, by 4 and more against a limit of 0.005 in
  the test's control. The API never produces that state for one sequence; restoring a
  checkpoint that was saved at another position, or from another conversation, does.
- A checkpoint that is not kept costs time, never correctness: the fallback is to decode the
  conversation again from the start (about a thousand tokens per second in one batch on the
  desktop CPU of the table above).

The checkpoints of the last few turns are all a chat needs; each is 381.7 KB of host memory.
A full sequence state (`llama_state_seq_get_data`, without the flag) carries the KV cells with
their token ids as well as the Canon rows, and restores into a new context bit for bit; it
grows by about 64 KB per token with an F16 cache.

## The shard2 architecture

`general.architecture = "shard2"` (Glint-Research/Shard-2, 49.4 M parameters): a model that
continues text, with one token of its own, `<bs>`, by which it takes back the token before it.
Its block is "sandwich-normed", `x += post_norm(attn(pre_norm(x)))` and
`x += post_norm(ffn(pre_norm(x)))`, every norm an RMSNorm; attention has a fused q/k/v
projection and an RMS norm over each head of Q and of K; the feed-forward is a fused SwiGLU;
the head is tied to the embedding. Its 20 layers are of two kinds:

- **local** layers (16) see a sliding window of 256 keys, the token itself and the 255 before
  it, and rotate Q and K by position (RoPE over neighbouring pairs);
- **global** layers (4: every sixth counting back from the last, so 1, 7, 13 and 19) see the
  whole sequence and carry no position encoding of any kind.

No stock architecture computes this (the nearest, gemma3, scales the embedding, has a GELU
feed-forward with separate matrices and rotates every layer), but every piece of it exists
upstream, so the architecture file contains no computation of its own:

| What | Upstream machinery it uses, unchanged |
| --- | --- |
| Local and global layers in one model | The interleaved sliding-window cache (`llama_kv_cache_iswa`): one cell store for the global layers, one for the local ones. `llama_model::create_memory` builds it for any model whose hyper-parameters name a window; no hook |
| The window | `LLAMA_SWA_TYPE_STANDARD`: a key is hidden when `query - key >= n_swa`. With `n_swa = 256` that is the reference's `(query - key) < 256` exactly, not off by one |
| Which layers are local, which rotate | Two per-layer flag arrays of the file, `shard2.attention.sliding_window_pattern` and `shard2.attention.rope_pattern`, which upstream reads into `hparams.is_swa_impl` and `hparams.rope_pattern`; `build_rope` does nothing on a layer without the flag. Upstream's pattern helper (`set_swa_pattern`) cannot place the global layers where this model has them, so the file carries the arrays |
| QK-norm, sandwich norms | The tensors `attn_q_norm`, `attn_k_norm`, `post_attention_norm`, `post_ffw_norm` and `build_norm`, as gemma3 uses them |
| Fused projections | `build_qkv` and `build_ffn(..., LLM_FFN_SWIGLU, LLM_FFN_SEQ)` |

Facts that matter when working on it:

- Everything comes from metadata: width, depth, heads, the window, the two flag arrays, the
  RoPE base and the epsilon. One thing did not fit: upstream takes the RoPE base of a windowed
  layer from a hyper-parameter of its own that is 10,000 whatever the file says, so the
  architecture copies the file's `rope.freq_base` into it.
- **The epsilon** of every norm is `1.1920929e-07`, the machine epsilon of float32: the
  reference builds its norms with `nn.RMSNorm()` and no epsilon, and PyTorch then uses that
  value. It is not in `config.json`; the converter writes it.
- Float formats: **F16 ships**; every norm weight (of the blocks, of Q and K, the final one)
  stays float32. F32 validates the port and is never shipped. No tensor is transformed; the
  checkpoint's `indexer.gate`, one number that the model's forward pass never reads, is left out.
- **The vocabulary** has 4,097 tokens: the 4,096 of the tokenizer file (a byte-level BPE,
  `tokenizer.ggml.model = gpt2` with the pre-tokenizer `gpt-2`, token for token the reference)
  and `<bs>` at 4096, recorded as `shard2.backspace_token_id`. Four tokens are not text and are
  typed as control tokens: text tokenised without special-token parsing never produces them,
  and `llama_token_to_piece` gives nothing for them.
  - `<bs>` is an ordinary token to the network and a frequent one (4% of what the reference
    predicts over ordinary text). It is fed back like any other token; taking the token
    before it out of what a reader sees is the application's business.
  - Id 2 is, by its behaviour, the one special token the model was trained with: the boundary
    between two texts. It is predicted where a text ends (up to 1% after a closing full stop)
    and what follows it is the beginning of another text. The tokenizer calls it `<eos>`,
    `config.json` lists it as `bos_token_id`; the file names it as both and adds neither to a
    prompt (`add_bos_token = false`), as the reference does.
  - Ids 0 (`<pad>`) and 1 (`<bos>`, and `eos_token_id` in `config.json`) are rows that training
    evidently never used: the two embedding rows agree to three decimals (cosine 1.000, norms
    6.229 and 6.231) and their logits are equal and low (never above a probability of 1.1e-4
    in ordinary text). Feeding either does no harm beyond a slightly worse prediction.
  - The reference stops on nothing and suppresses nothing: it writes a fixed number of tokens
    and feeds every one of them back. Whether an application ends a reply at id 2, and whether
    it bans ids 0 and 1, is its own decision; the file suppresses none.
- **No chat format** (`shard2.chat.format = completion`): no template, no roles. The prompt is
  the text, tokenised as it is.
- **The context** is 8,192 tokens; the model was trained on 4,096
  (`shard2.trained_sequence_length`). The port follows the reference over the whole 8,192. Its
  distance from the reference grows with the position, 1e-3 in the first quarter and 3.6e-3
  in the last against the limit of 5e-3, because RoPE angles are float32 on both sides and
  lose absolute precision as positions grow; the 16-bit file is unaffected (its rounding is
  larger than that everywhere).
- Not supported, and refused when the file is loaded: grouped-query attention, a file without
  windowed layers or without the two flag arrays.
- Nothing was run on a GPU backend or on ARM.

### The checkpoint is a pickle

`Shard-2.pt` is a PyTorch pickle from a third party, and loading a pickle can run any code it
contains. The rule, for the converter, the tests and anything written later:

- load it with `torch.load(path, map_location="cpu", weights_only=True)` **and in no other
  way**. That unpickler constructs tensors, dicts, lists and numbers, and refuses everything
  else. If it ever refuses a checkpoint, the checkpoint holds something that is not weights:
  do not fall back to `weights_only=False` and do not add types to an allow-list to get past it;
- **never import or run the `infer.py`** that ships with the checkpoint: its loader unpickles
  without that restriction. The tests' reference model is a copy of the script's architecture
  in `tests/refgen/reference_shard2.py` (with the MIT permission notice that the model card
  declares, attributed to Glint-Research without a copyright line, since the repository gives
  none), and the script itself
  is only read as text, to check that the copy still is the script's, character for character;
- treat the checkpoint directory as data: scripts live elsewhere, and the converter takes that
  directory off Python's module search path before it imports anything.

The pinned checkpoint needs no exception: its pickle refers to `collections.OrderedDict`,
`torch.FloatStorage` and `torch._utils._rebuild_tensor_v2` and nothing else, and holds a state
dict of 203 float32 tensors, a step count, a loss and a configuration of plain values.

### Cutting the memory back: what an application must do to rewind

For this cache `llama_memory_seq_rm(mem, seq, n, -1)` **always returns true**. Whether the cut
is right depends on what the local layers' cell store still holds, because the token decoded
next, at position `n`, looks at the 255 positions before it:

```c
// A cut to n tokens (n > 0) is right when, and only when:
llama_memory_seq_pos_min(mem, seq) <= max(0, n - (llama_model_n_swa(model) - 1))
```

`llama_memory_seq_pos_min` is the first position the local layers hold; llama.cpp guarantees
that they hold every position from there to the end. How far back that is depends on the
context parameter `swa_full`:

| | `swa_full = true` (llama.cpp's default, and what the session library uses) | `swa_full = false` |
| --- | --- | --- |
| Cells of the local layers | as many as the context | the window plus one micro-batch, rounded up to 256: 512 for micro-batches up to 256 tokens |
| Their first position | 0, until a checkpoint or a full state is restored | moves forward as the text grows: a cell is reused once the window has passed it |
| A cut is right | at any length, until a checkpoint or a full state is restored | back to about 256 tokens before the end (about 512 with micro-batches of 512 tokens), and no further |
| KV memory, F16, context 4,096 | 168 MB | 50 MB |
| KV memory, F16, context 8,192 | 336 MB | 84 MB |

A cut made against the test is not refused and not reported by anything: every logit after it
is wrong, by 1.6 and more against a limit of 0.005 in the tests' controls (by 0.02 when a
single key is missing, so the test is exact, not cautious).

The state to save for a later cut is the partial sequence state, which for this cache is the
window of the local layers and nothing of the global ones: **8,392,096 bytes** with an F16
cache once the text is longer than the window (256 cells x 16 layers x 1,024 values x 2 bytes,
plus 3,488 bytes of framing; less for a shorter text).

```c
// Save, when the memory holds exactly n tokens of the sequence:
size_t size = llama_state_seq_get_size_ext(ctx, seq, LLAMA_STATE_SEQ_FLAGS_PARTIAL_ONLY);
llama_state_seq_get_data_ext(ctx, buffer, size, seq, LLAMA_STATE_SEQ_FLAGS_PARTIAL_ONLY);   // returns size

// Rewind to n tokens, in this order:
llama_state_seq_set_data_ext(ctx, buffer, size, seq, LLAMA_STATE_SEQ_FLAGS_PARTIAL_ONLY);   // returns size
llama_memory_seq_rm(llama_get_memory(ctx), seq, n, -1);                                     // returns true
// then decode from position n
```

The discipline:

1. **Before every cut, make the test above.** When it holds, cut and decode.
2. **When it fails**, restore the latest checkpoint at or before the wanted length, cut at
   the checkpoint's length, and decode forward from there. The memory must still begin with
   the tokens it held when the checkpoint was saved: the global layers are not part of a
   checkpoint, their cells are simply kept.
3. **No checkpoint that early**: remove the whole sequence and decode from position 0.
4. **A restore narrows the store.** Restoring a checkpoint (or a full sequence state) leaves
   the local layers with the saved window and nothing older, whatever the size of their
   store. With `swa_full = true` this is the only way the test can come to fail, and it then
   fails for every cut more than one token below the restored length.

`pp_session_rewind_to` makes the test and answers "not removed" when it fails, so that the
caller falls back to a checkpoint exactly as it does for a model with recurrent state; the
session test shows what happens without it (the cut is made and the continuation differs).

A full sequence state (`llama_state_seq_get_data`, without the flag) holds every cell of the
global layers and the same window of the local ones (14 MB for 700 tokens with an F16 cache),
and restores into a new context; the continuation is then equal within the limits of the
format but not bit for bit, because the restored cells land in other slots than they had.

## The native library

`lib/include/proseplow_native.h` is the whole interface: plain C, no JNI, no logging, no
exceptions across the boundary, text as UTF-8 with an explicit length. It links `libllama` and
nothing else. Today it contains:

- `pp_peacebell_tokenize`: the tokens the reference SentencePiece tokenizer gives a text. It
  normalises as SentencePiece does, splits on the user-defined symbols and calls
  `llama_tokenize` once per stretch between symbols.
- `pp_peacebell_chat_tokenize`: a conversation as the model was trained to see it, with the two
  chat marker ids injected around separately encoded segments.
- Two modes for marker text inside a message. **Safe** (the default for anything a person typed
  or pasted) keeps `<|im_start|>` and `<|im_end|>` as ordinary characters, so a message can
  never close its own turn. **Exact** turns them into the marker tokens as the reference does;
  it exists for the tests.
- `pp_chatml_text_tokenize` (declared in `lib/include/proseplow_chatml_text.h`, which the main
  header includes): a conversation for the models whose ChatML is plain text, Boris-2 and the
  byte-level BPE members of the peacebell family. It writes out the chat template, trims the
  content as the template does and tokenises the whole text in one `llama_tokenize` call
  without special-token parsing. It also reports the token count at which each message ends
  (the positions for checkpoints, see the boris2 section), returns an error rather than tokens
  if the end-of-text token ever appeared in the result, and refuses a model whose markers are
  tokens (`pp_chatml_text_supported`).

Generation is not in this library but in the next one, which is built on it. Keep host-only
code out of `lib/`: the Android build compiles it as it is.

## The generation session

`session/include/proseplow_session.h` is the native half of the seam the Kotlin engine drives
(`NativeLlama` in the `core` module), call for call: one loaded model, one context, one
conversation. The rule of that seam is "thin native, smart Kotlin": the session exposes
llama.cpp operations and decides nothing. It never renders a prompt, looks for a stop string,
trims or shifts the context, chooses a sampling default or retries. It is plain C over
`libllama` and `proseplow_native`, without JNI, so the host tests drive exactly the code the
phone runs. The header is the reference for every function; this is the outline:

| Call | What it stands for in llama.cpp |
| --- | --- |
| `pp_runtime_init` | Once per process: logging off, numeric locale `C`, the compute backend loaded. With the backends linked in (the host) there is nothing to choose; given the names of the CPU backend variants (Android), the one that scores highest on this processor is loaded with `ggml_backend_load` |
| `pp_session_open_path`, `pp_session_open_fd` | `llama_model_load_from_file`, or `llama_model_load_from_file_ptr` on a duplicate of the descriptor positioned at the offset, after the GGUF header there has been read and found to end, tensors included, within the given length; then `llama_init_from_model` with the given `n_ctx`, batch size and threads, each held to its limit first. Always the CPU: `n_gpu_layers = 0` and an empty device list. Read into memory or mapped (`pp_load_mode`) |
| `pp_session_tokenize`, `pp_session_token_to_bytes` | The front-end of `lib/` for a peacebell model with the SentencePiece tokenizer, `llama_tokenize` with `add_special = false` for every other model, for a text of at most 256 KiB; `llama_token_to_piece` with `special = false`, bytes returned raw |
| `pp_session_evaluate` | Chunks through `llama_batch_ext` and `llama_process` with explicit positions and output for the last token only; a progress and a cancel callback |
| `pp_session_rewind_to` | `llama_memory_seq_rm(mem, 0, n, -1)` and its own answer, which is "no" for a model with recurrent state. For a model with windowed layers the session answers "no" itself when those layers no longer hold the window before the cut (see below). Nothing ever calls `llama_memory_seq_add` or `llama_memory_seq_div` |
| `pp_session_save_checkpoint`, `..._restore_checkpoint`, `..._drop_checkpoint` | The partial sequence state (`LLAMA_STATE_SEQ_FLAGS_PARTIAL_ONLY`) kept in native memory with the token count it belongs to; restoring is the state, then the cut, in that order (see "Checkpoints" above) |
| `pp_session_max_context_size`, `pp_session_context_bytes`, `pp_session_carry_bytes` | The context the model was trained for (`llama_model_n_ctx_train`); what a context of a given size costs, from llama.cpp's own report of the buffers it allocated for the contexts of this session (see "A context that grows with the conversation" below); the size of the sequence state a carry-over holds beside a context (`llama_state_seq_get_size_ext`) |
| `pp_session_resize_context` | Another context size with the model still loaded, held to a budget in bytes before anything is touched: the sequence state written into one block (`llama_state_seq_get_data_ext`) when the budget covers it, the old context overwritten and freed as at a clear, the new one created, the block read into it (`llama_state_seq_set_data_ext`), overwritten and freed; otherwise an empty memory. A size that cannot be created falls back to the previous one, then to halves |
| `pp_session_memory_state_bytes`, `pp_session_export_memory`, `pp_session_import_memory`, `pp_session_memory_tokens` | The memory as an image that leaves and enters the session in pieces, through a buffer and a callback of the caller's: the session's record of the tokens, the state of the sequence exactly as `llama_state_seq_get_data_ext` writes it but never as a block (llama.cpp's own `llama_io_write_i` and `llama_io_read_i`, implemented over the caller's buffer in `session/src/state_stream.cpp`, handed to `llama_memory_i::state_write` and `::state_read`), and the checkpoints the caller names. Nothing is written to a file. See "A memory that is written out and read back" below |
| `pp_session_clear_memory` | The context goes back to the size the session was opened with. Every checkpoint zeroed and freed; the sampler chain, the batch and the context freed and created again, after `llama_memory_clear(mem, true)` and after overwriting what of them can be reached (the last prediction, the batch's tokens) and the session's own record of the tokens in memory; the allocator asked to give its free pages back. See "Forgetting" below for what this removes and what it does not |
| `pp_session_configure_sampler`, `pp_session_sample_next` | A fixed order: the penalties, which the session computes itself from its record of the tokens in memory (see "Penalties that cover what their definitions cover" below), then a chain of banned tokens (logit bias of minus infinity) and, for a temperature of a millionth or more, temperature, top-k, top-p, min-p and the random choice, otherwise greedy; this is what `llama_sampler_sample` does (`llama_sampler_apply` on the scores, `llama_sampler_accept`), once per complete evaluation, on a candidate list of the session's own, with the sampler that chooses applied after the others and only when there is something to choose from |
| `pp_session_close` | Everything freed, after the same overwriting as a clear |

A failure is a status code, and the codes are the ones of the Kotlin `NativeLlamaException`,
one for one (11 and 12, which only the export and import of a memory return, are its
`MEMORY_STOPPED` and `MEMORY_FAILED`). No function logs anything, and llama.cpp's own log is written nowhere: the session
takes from it only the name of the compute back end that was loaded, the description of a
failure and the sizes of the buffers of a context. The buffers the session and the front-end allocate themselves for text, tokens,
predictions or model state are overwritten before they are freed; llama.cpp's are not, which
is the subject of "Forgetting" below.

Things the session does that llama.cpp alone would not, each because the plain call would be
wrong on a phone or for one of the models:

- **Cancelling.** For a model whose memory is attention only (peacebell, Shard-2), the cancel
  callback is also polled from llama.cpp's abort callback, about once a millisecond, so a
  chunk that is being computed stops within a graph node, and llama.cpp takes the interrupted
  chunk out of the memory again. A model with recurrent state (Boris-2, Qwen3.5) cannot give
  an interrupted chunk back: after an abort its memory claims every token of the chunk while
  their state is half computed, and nothing short of clearing repairs that. For such a model
  the callback is therefore only polled between chunks, and a cancellation waits for the
  chunk in progress.
- **The token Boris-2 cannot take.** That model's graph asserts that its end-of-text token is
  never an input, and a failed assertion ends the process. `pp_session_evaluate` answers
  `PP_STATUS_INVALID_ARGUMENT` for it instead and leaves the memory alone.
- **The cut Shard-2 cannot serve.** llama.cpp cuts the memory of a model with windowed layers
  at any length and says it did, also when those layers no longer hold the keys the next token
  will look at; every prediction after such a cut is wrong and nothing reports it. A session
  keeps every key of those layers (it does not set `swa_full` to false), so this can only
  happen after a checkpoint was restored, which leaves them with the window before the
  checkpoint's end. `pp_session_rewind_to` therefore makes the test of "The shard2
  architecture" before it cuts, and answers "not removed" with the memory untouched when it
  fails. To the caller that is the answer of a model with recurrent state, and the same
  fallback serves: a checkpoint, or clearing.
- **Threads.** A session can be given the CPUs to pin one thread to each (the big cores of a
  phone) and a smaller number of threads to run when that is not allowed. Android confines a
  process to a set of cores according to whether its app is in front and replaces every thread
  affinity when it moves the process to another set, so the session checks the calling thread's
  affinity before every chunk of an evaluation and rebuilds its thread pool when it has
  changed: pinned when every listed CPU is allowed, unpinned otherwise. Before every chunk and
  not once per call, because a long prompt is one call of many seconds, and a move during it
  would leave pinned threads sharing cores until it ends. A replaced pool is kept until the
  next computation has run, because ggml's CPU backend still pauses it then. The pool outlives
  the context: forgetting a conversation creates a new context and hands it the same threads.
- **Giving memory back.** Android's allocator keeps the pages of freed blocks for reuse;
  closing a session and forgetting a conversation ask it to release them
  (`mallopt(M_PURGE_ALL)`, or `M_PURGE` before Android 14; `malloc_trim` with glibc), or a
  closed model would stay in the app's memory figures until the allocator is next used.
- **Sizes from outside.** llama.cpp allocates for whatever size it is handed, at once: a
  context of 2,147,483,647 tokens is 104 GiB of bookkeeping before the first buffer is asked
  for, and a phone ends the process long before any call returns. So every size is held to a
  limit before llama.cpp sees it, and a value beyond its limit comes back as a status:

  | Size | Limit | Beyond it |
  | --- | --- | --- |
  | Context | The context the model was trained for, and a budget in bytes that the caller states | More than the model's is taken as the model's when a session is opened and refused when a size is changed; a size whose computed cost is above the budget, or whose cost is not known, is refused (`PP_STATUS_CONTEXT_REFUSED`): before anything is allocated when the size changes, and at an opening by the two checks described below |
  | Batch (`n_batch`) | 32768 tokens (`PP_BATCH_TOKENS_MAX`) | `PP_STATUS_INVALID_ARGUMENT`, before the file is opened; more than the opening context is taken as that, for the life of the session |
  | Model embedded in a file | The header and every tensor end within offset + length | `PP_STATUS_LOAD_FAILED`, before anything is loaded |
  | Text to tokenise | 262144 bytes (`PP_TOKENIZE_BYTES_MAX`) | `PP_ERROR` (`PP_STATUS_TOKENIZE_FAILED`) |
  | Checkpoints held at once | 8 (`PP_CHECKPOINTS_MAX`) | -1, until one is dropped |
  | Penalty window | None | `PP_PENALTY_NO_WINDOW` (-1) is the whole memory, which is what the engine passes; 0 and other negative values are refused |
  | Reply start | None | `PP_REPLY_START_AT_SAMPLING` (-1) or a position from 0, which is what the engine passes (-1, or where a continued answer began); below -1 is `PP_STATUS_INVALID_ARGUMENT` |
  | Banned tokens | The size of the vocabulary | `PP_STATUS_INVALID_ARGUMENT` |
  | Buffer for the pieces of a memory image | At least 4096 bytes (`PP_MEMORY_PIECE_BYTES_MIN`); no largest size, it is the caller's buffer and nothing is allocated to match it | `PP_STATUS_INVALID_ARGUMENT`, before a callback is called |
  | What a memory image says of itself | Tokens: what the context holds. Checkpoints: 8, each no larger than a checkpoint of the whole restored memory, which llama.cpp is asked for before one is allocated. The state: llama.cpp is given the bytes the image says it has and not one more | `PP_STATUS_CONTEXT_FULL` for the tokens, `PP_STATUS_MEMORY_FAILED` for the rest, each with the memory left empty |

  The context has no fixed ceiling any more: what a model was trained for is its limit
  (2,048 tokens for Boris-2, 32,768 for peacebell, 262,144 for Qwen3.5), and because the
  largest of those is more than any phone holds, a size below that limit is held to a budget
  in bytes before it is allocated ("A context that grows with the conversation" below). The
  batch keeps a ceiling, because the compute buffers are reserved for a whole batch of
  scores. The text limit is a limit and not tokenising in
  pieces with a way to stop in between, for two reasons: where a piece ends changes the tokens
  on both sides of the cut, so pieces are only right at places the tokenizer knows and the
  session does not; and the call has nobody to ask whether to stop. With patch 0004 the
  slowest 256 KiB measured take 0.7 seconds on a desktop processor.
- **A sampler that is never asked to choose among nothing.** A banned token is a score of
  minus infinity, and llama.cpp's samplers take it for granted that some token is left: with
  every token banned the random choice names the last candidate in a release build and ends
  the process on an assertion in a build that has assertions, and the greedy one names token
  0. The session therefore applies the chain in two steps, everything that changes and narrows
  the scores first, and looks at them before it lets the last sampler choose:
  `pp_session_sample_next` answers `PP_STATUS_SAMPLING_FAILED` when no score is a finite
  number or one is not a number at all. A temperature below a millionth, a denormal for
  instance, would divide the scores into infinities; it selects greedy decoding, which is what
  it means.

- **Penalties that cover what their definitions cover.** The settings a model comes with name
  penalties, and what a name means is decided where the settings come from. `repetition_penalty`
  in a `generation_config.json` (Boris-2-Instruct: 1.1) is the transformers library's: its
  processor is shown the whole sequence, so it penalises every token of the prompt and of the
  reply so far, each once, with no window. `presence_penalty` in Qwen3.5's model card (2.0) is
  the OpenAI API's, and the servers that card names for it count the tokens of the output only
  (vLLM's `apply_penalties` in `vllm/model_executor/layers/utils.py` subtracts frequency and
  presence over the output tokens and applies the repetition penalty over prompt and output;
  SGLang's `sampling/penalties.py` keeps "output-token counts" for all three). llama.cpp's
  penalties are one sampler for all three, computed from whatever tokens it was told of: in
  its own server that is prompt and reply within a window of 64, and in this library it was
  the sampled tokens, so the repetition penalty never reached the prompt and Boris-2 did not
  generate what its reference generates. The session therefore computes the penalties itself,
  with llama.cpp's arithmetic, from a record it keeps of the tokens in memory: the repetition
  penalty over all of it, presence and frequency over the part from where the reply began
  (the length of the memory when the first token is sampled after the sampler was configured).
  The record is one id per position, written when tokens are evaluated and cut when the end of
  the memory is removed, a checkpoint restored or the conversation forgotten, so a token is
  covered exactly while it is in the memory, whoever chose it; a checkpoint holds none of it.
  It is the session's own allocation, sized for a full context when the context is created
  and overwritten wherever it is cut, which the sampler's ring buffer and table were not
  entirely. A window (`penalty_window`) limits all three to the latest tokens; the engine
  passes `PP_PENALTY_NO_WINDOW` (-1), the whole memory, which is what the definitions have.

  Where the reply begins is the caller's to say when it knows better than the session: a
  reply that continues an answer already in memory ("Continue" in the app: a new generation
  after a partial assistant message) should count the whole answer, not only what is sampled
  now. `pp_sampler_config.reply_start` is the memory position at which the answer began, or
  `PP_REPLY_START_AT_SAMPLING` (-1) for "where the first token is sampled after this
  configuration", which is what every other reply uses. Below -1 is refused. The binding
  passes `SamplerConfig.replyStart` through (`LlamaJni.configureSampler`, after the penalty
  window): the engine sets the token position where the content of the answer being continued
  begins in the prompt as evaluated, and -1 for a new reply, an empty answer, or an answer
  whose start the tokenizer does not put on a token boundary. A position beyond
  the memory counts nothing until the memory reaches it; a clear, or a change of size that
  empties the memory, ends the reply, and the next one begins where sampling begins. A
  zero-initialised `pp_sampler_config` says position 0, the whole memory: set the field.

- **A context that grows with the conversation.** A session is opened with a small context
  (the app: 4,096 tokens, or the model's largest where that is less) and given another size
  when the conversation needs one, up to the context the model was trained for. Two things
  keep that from being an unchecked allocation. The caller states a budget in bytes, and the
  session computes what a size costs and refuses it before anything is touched when it costs
  more. And before llama.cpp is asked for a context, the session maps the whole cost once,
  untouched, and unmaps it: a size the address space has no room for is reported and never
  reaches llama.cpp.

  What a context costs is llama.cpp's own figure. At every `llama_init_from_model` it logs the
  size of each buffer it allocated, and the session reads those lines through the log
  callback: the key-value buffers, which are rows of one size, give the bytes per token; the
  compute reservation, which grows by the attention mask, is taken at two sizes (a session
  creates a small context first for that, one batch or more, and frees it); the scheduler's
  and the cells' bookkeeping, which llama.cpp does not report, is the rest of what the
  allocator handed out. Nothing is derived from an architecture. Every figure errs towards
  costing more, so that a budget the cost fits is a budget the context fits:

  - Only contexts at the session's batch are measured. llama.cpp gives a context no batch above
    its own size, and the compute reservation is mostly the scores of one batch, so a context
    smaller than the batch would make every larger size cost too little; its report is not
    used, and a size below the batch is costed at the whole batch (too high). A batch larger
    than the opening context is taken as that size for the life of the session, so that the
    opening context is always one that is measured.
  - The unreported part is the allocator's count of bytes in use (`mallinfo`) after
    `llama_init_from_model` less the count before, less what was reported. That count is the
    whole process's: whatever another thread allocates or frees during the creation is in it.
    So the figure is a fixed part plus a part per token, the part per token fitted once from
    the first two sizes (made one straight after the other at the opening), and the fixed part
    the largest any creation since has shown, never below the largest measurement. A later
    creation can raise it and never lower it: memory freed elsewhere meanwhile is ignored,
    memory allocated elsewhere is counted (too high). The exception: memory freed elsewhere
    during the second of the first two creations lowers the part per token for good (the
    real bookkeeping is about 52 bytes per token). Not counted at all: the sets in which
    llama.cpp records the cells in use, which grow by about 90 bytes per token in memory as a
    conversation runs. Where the allocator cannot be asked the part is 0.
  - While only one size is known (a session opened at about one batch, which makes no small
    first context), a larger size is costed as if the compute reservation and the unreported
    part grew in proportion to the size: several times too high, until the first larger
    context gives the second size.
  - Beyond the largest size created the line through the two sizes is continued, and what
    grows can grow faster there (the compute reservation is the largest of several buffers):
    without a margin the costs were up to 0.3% short at a model's largest context, so what a
    size beyond adds is counted 2% higher. A measured margin, not a bound.

  An opening is checked against its budget twice. Before the opening context is allocated, an
  estimate from the small first context alone, in which the growth up to the opening size is
  not known and counted as nothing (a budget below the estimate is refused with nothing more
  allocated); after it exists, its cost from its own report, which refuses what the estimate
  let through and frees the context again. The opening context can so exist for a moment
  beyond the budget, by that growth (the attention mask and the bookkeeping per token). The
  app opens with no limit. If the lines cannot be read the cost is unknown: an opening with a
  budget below `INT64_MAX` is refused, one with `INT64_MAX` (no limit stated) opens, every
  change of size is refused, and `<model>.context` fails with a message that says so.

  In one process contexts are created from one thread at a time only (no two openings,
  changes of size or clears at once, in one session or several): llama.cpp's log callback is
  one for the process, and the session reads each context's report and an opening's error from
  it. The engine has one session and serialises its calls. `pp_session_context_bytes`
  is what is allocated, an upper bound on what becomes resident: the key-value buffers are
  zeroed when they are created and resident at once, the compute reservation and the
  bookkeeping are mostly never touched. For a model's entry in `models/manifest.json`:

  | Model | Trained context | Key-value bytes per token | Context of 4,096 tokens, allocated | Of which fixed, mostly untouched | The largest context, allocated |
  | --- | --- | --- | --- | --- | --- |
  | qwen3.5-0.8b | 262,144 | 12,288 | 516 MiB | 19 MiB recurrent state, 250 MiB compute reservation (the scores of a batch of 256 for 248,320 tokens), 198 MiB bookkeeping | 3,739 MiB |
  | peacebell-v1-291m | 32,768 | 66,560 | 324 MiB | 36 MiB compute reservation, 28 MiB bookkeeping | 2,158 MiB |
  | boris-2-instruct | 2,048 | 63,488 | 313 MiB at its 2,048 | 36 MiB compute reservation, 153 MiB bookkeeping | 313 MiB |
  | shard-2 | 8,192 in the file, 4,096 by its card | 40,960 | 240 MiB | 11 MiB compute reservation, 70 MiB bookkeeping | 404 MiB |

  When the size changes, the memory is carried over if the budget allows: the state of the
  sequence is written into one block, the old context is overwritten and freed, the new one
  is created, the block is read into it and overwritten before it is freed. The old context
  goes before the new one comes, never the other way round, so the peak is the block beside
  one context: the larger of the two contexts plus the tokens in memory times the key-value
  bytes per token (plus the recurrent state, 19 MiB for Qwen3.5). A memory carried over
  continues exactly as one evaluated at that size, with its checkpoints and with the record
  the penalties are computed from. When the budget does not cover the block the memory comes
  back empty, as after a clear, and the caller reads the conversation again; it asks
  `pp_session_memory_token_count` which of the two happened. A forgotten conversation
  (`pp_session_clear_memory`) returns the context to the size the session was opened with,
  so a lock gives the memory back and costs one overwriting of the old buffers and one small
  context.

  What is promised about a size that cannot be had, and what is not. A size above the budget
  or above the model's largest is refused with nothing touched (`PP_STATUS_CONTEXT_REFUSED`).
  A size that fits the budget and cannot be created after the old context was freed is
  reported (`PP_STATUS_CONTEXT_NOT_CREATED`): the session tries the size it had, then halves,
  and says what exists; a memory being carried over goes into whichever of those was created
  when it fits there, and is gone otherwise. When the sampler has to be built again (the
  memory did not come through) and cannot be, the answer is `PP_STATUS_OUT_OF_MEMORY`, which
  outranks the code for the size. The checks come in this order: a size or budget below 1, a
  size above the model's largest, an unknown cost, a cost above the budget, and only then
  "the size it has already", so the present size with a budget below its cost is refused. Not promised: that the process survives every allocation that fails.
  llama.cpp reports a key-value or compute buffer it could not allocate, but ggml ends the
  process when one of its own bookkeeping buffers cannot be had (`GGML_ASSERT` in `ggml_init`;
  seen with Boris-2 under an address-space limit before the session tested for room itself).
  The session's own test for room closes that for a size that cannot be mapped at the moment
  of the test; it errs towards "no room" by the tens of MiB an allocator keeps of what was
  freed, and it cannot help when another thread takes the room in between. And on a system
  that grants address space without memory behind it, Android among them, an allocation does
  not fail at all: the process is ended while a buffer is zeroed. There the budget is the
  only protection, and deriving it from the memory the system has free is the caller's part.

  Measured on a desktop (6 threads, a batch of 256, a busy machine; for the phone see
  `ContextGrowthDeviceTest`), with 280 tokens in memory where a memory is carried:

  | Model, context | Allocated | Resident beyond the context of 4,096 | Created, empty | Created, memory carried | Prompt, generation (tokens/s) | Forgotten (back to 4,096) |
  | --- | --- | --- | --- | --- | --- | --- |
  | Qwen3.5, 4,096 | 516 MiB | | | | 341, 28.9 | 27 ms |
  | Qwen3.5, 8,192 | 566 MiB | 48 MiB | 40 ms | 54 ms | 348, 28.6 | 33 ms |
  | Qwen3.5, 16,384 | 665 MiB | 145 MiB | 69 ms | 79 ms | 349, 28.9 | 45 ms |
  | Qwen3.5, 32,768 | 865 MiB | 337 MiB | 136 ms | 136 ms | 347, 29.1 | 66 ms |
  | Qwen3.5, 65,536 | 1,265 MiB | 721 MiB | 237 ms | 281 ms | 338, 28.1 | 104 ms |
  | peacebell, 4,096 | 324 MiB | | | | 657, 71.2 | 105 ms |
  | peacebell, 8,192 | 586 MiB | 260 MiB | 186 ms | 192 ms | 635, 69.0 | 132 ms |
  | peacebell, 16,384 | 1,110 MiB | 781 MiB | 331 ms | 344 ms | 636, 72.4 | 189 ms |
  | Shard-2, 8,192 | 403 MiB | 160 MiB | 113 ms | 122 ms | 2,898, 369 | 82 ms |

  Creating and forgetting grow with the size, because every byte of the key-value buffers is
  written once at each; a context that is large and nearly empty is as fast as a small one
  (llama.cpp attends over the cells in use, not over the context); a full one is slower by
  what the conversation costs, not the context (at 4,096 tokens in memory: Qwen3.5 255 prompt
  tokens/s on average and 25.7 in generation, peacebell 281 and 37.3). A checkpoint does not
  grow with the context (Qwen3.5 19.3 MiB, Boris-2 0.36 MiB, Shard-2 up to 8 MiB, peacebell 8
  bytes), nor does the batch. The block of a carry-over is as large as the memory in use:
  22.6 MiB for those 280 tokens of Qwen3.5 (19.3 of it the recurrent state), 67 MiB at 4,096;
  17.8 MiB and 260 MiB for peacebell. Nothing above 1.1 GiB was allocated for these figures;
  the largest contexts are costed, not measured.

- **A memory that is written out and read back.** A conversation that was read once need not
  be read again after a lock if the state it left in the context is kept: the app means to
  write that state out, sealed, when it leaves the screen, and to read it back at the next
  unlock (that side is not in this directory; the JNI binding and the engine's seam carry the
  pieces, and nothing above them is written yet). llama.cpp's public
  interface moves the state of a sequence in two ways, and neither will do. One block
  (`llama_state_seq_get_data_ext`, which a change of context size uses inside its budget) is
  the whole state a second time beside the context, 578 MiB for peacebell at 9,100 tokens; a
  file (`llama_state_seq_save_file`) is the state in clear on storage. Inside, llama.cpp
  writes and reads every state through two small interfaces of its own, `llama_io_write_i`
  and `llama_io_read_i` (`src/llama-io.h`): they are handed the scalars of a state one by one
  and each stretch of key and value rows as "this tensor, from this offset, this many bytes".
  `session/src/state_stream.cpp` implements the two over a buffer that the caller owns, and
  calls the two virtual functions every state goes through (`llama_memory_i::state_write` and
  `::state_read`, `src/llama-memory.h`) on the memory of the session's context. The rows go
  straight between llama.cpp's buffers and the caller's buffer, as many bytes at a time as
  the buffer has room for, and no block of the state's size exists at any moment. That file
  is the only one of this project compiled against llama.cpp's internal headers (its entry in
  `session/CMakeLists.txt` gives it, and no other file, the directory), and it needs no
  symbol that `libllama` does not export: the functions are reached through the object's own
  table of virtual functions. No patch to llama.cpp is involved in that; the one patch that
  belongs to this (0005, above) only overwrites what llama.cpp frees after reading a state.

  A **memory image** is what `pp_session_export_memory` hands to the caller's sink and what
  `pp_session_import_memory` takes from the caller's source: 176 bytes that say what follows
  and how large each part is, the session's record of the tokens in memory (4 bytes each),
  the state of the sequence exactly as `llama_state_seq_get_data_ext` writes it behind its
  first 8 bytes, and the checkpoints the caller named, each as it sits in native memory, with
  the length of memory it belongs to. The checkpoints belong in it because a model with
  recurrent state can only go back through one, and nearly every request after an import goes
  back: the last token is read afresh, a reply is tokenised again, an answer is asked for
  again. Not in it: the sampler, which both functions leave as it is, and a prediction (after
  an import at least one token is evaluated before the next is sampled, as after a restored
  checkpoint). An image is not a file format. It is written as the machine holds it, its
  state is the pinned llama.cpp's for this model, and it is only ever read by the build that
  wrote it, on the same model file; the size of the context is not part of that, an image
  goes into any context that has room for its tokens. `pp_session_memory_state_bytes` says
  beforehand, by walking the cells and copying nothing, exactly how many bytes an export
  hands over, and `pp_session_memory_tokens` gives a caller that keeps its own copy of the
  token ids the record to set it from after an import.

  How it is driven. The caller gives a buffer and a callback (`pp_memory_sink`,
  `pp_memory_source`). An export fills the buffer and calls the sink with every full piece
  and with the last one, saying which is the last; the sink returns false to stop. An import
  calls the source whenever it needs more; the source puts the next bytes at the start of the
  buffer and says how many (0 for "no more", a negative number for "cannot" or "stop"), and
  after the image's last byte it is asked once more and must answer 0. The size of the buffer
  is the size of a piece and the caller's choice, from 4,096 bytes up. `first_piece_offset`
  keeps room at the start of the first piece for bytes of the caller's own (a header), so
  that every piece it seals has the same size, and makes the import skip as many. On Android
  the buffer is meant to be the memory of a direct `ByteBuffer`, which a cipher reads and
  writes in place: the state then crosses the binding without being copied into an array on
  the Java heap. That is what the binding does (next paragraph). `NativeLlamaException` names
  the two status codes that only these functions return: `MEMORY_STOPPED` (11, the caller's
  callback ended it) and `MEMORY_FAILED` (12).

  What the JNI binding does with it (`../llama/src/main/cpp/proseplow_jni.cpp`, and
  `NativeLlama.exportMemory`, `importMemory`, `memoryStateBytes` and `memoryTokens` in
  `../core`). The Kotlin caller owns the buffer, a direct `ByteBuffer` of at least 4,096
  bytes; the binding hands the session its memory and capacity as they are
  (`GetDirectBufferAddress`), so the state goes between llama.cpp's buffers and that memory and
  through nothing else: no array of the Java heap, no copy in the binding. The buffer's
  position and limit are neither read nor changed: a piece is always at index 0. A buffer that
  is not direct or is read-only, is below 4,096 bytes or does not contain the caller's offset,
  and more than eight checkpoints, are refused in Kotlin before native code is reached
  (`INVALID_ARGUMENT`). The sink and the source are one Kotlin call per piece, on the calling
  thread: `MemorySink.write(bytes, last)` answers whether to go on, `MemorySource.read()` how
  many bytes it put in the buffer (0 for the end, negative to stop). An exception thrown by
  either is taken off the thread at once (`ExceptionOccurred`, `ExceptionClear`) and answered
  with "stop" (false, or -1); the session then winds the call down with no exception pending in
  the VM (a source that throws in the middle of the state is carried out of llama.cpp's
  reading as a C++ exception, between this library and `libllama.so`, and the import ends as
  any stopped import does, with an empty memory), and the binding throws the Kotlin exception
  again once the session has returned, so the caller gets it as itself rather than code 11. A
  call from another thread, which the session never makes, is answered with "stop" without
  touching the VM. Nothing on the binding's side holds a byte of the state, before, during or
  after the call: the only copy outside llama.cpp is the caller's buffer, which the session
  overwrites. The record of the tokens (`memoryTokens`) is copied once into a buffer of the
  binding's that is overwritten before it is freed, and then into the array returned, which
  is the caller's to overwrite. Of the image's checkpoints the binding passes on only handles
  and lengths.

  What is promised. An export only reads: whatever becomes of it (complete, stopped by the
  sink at any piece, failed), the memory, the checkpoints, the record of the tokens, the
  sampler and a prediction that was ready are as they were, and a reply goes on as if there
  had been no export. An import is all or nothing. It needs an empty memory and no checkpoint
  (as after `pp_session_clear_memory`) and a context with room for the image's tokens, which
  the caller gives its size first; after any result but `PP_STATUS_OK`, at whatever byte it
  ended, the memory is empty with its buffers overwritten (`llama_memory_clear(mem, true)`),
  the record is overwritten and empty, no checkpoint is held, and the session predicts as
  one whose memory was never filled. Both overwrite the caller's buffer before they return.
  Neither allocates with the state: an export nothing at all in this library; an import the
  image's checkpoints, which stay as checkpoints of the session, and llama.cpp its
  bookkeeping for the restored cells (some tens of bytes per token). A memory that was read
  back continues exactly as the one that was written out: for the four bundled models the
  predictions are the same bit for bit, in the context it came from and in one of another
  size.

  What the caller must guarantee, because the session cannot check it: the bytes handed to
  `pp_session_import_memory` are, complete and in order, what one export of this same build
  wrote for this same model file, and nobody could have changed them since. llama.cpp reads a
  state as its own writer left it. It holds the number of cells to the context and the
  number of layers and the type and size of every row to the model, and it believes the
  positions and sequence ids of the cells, the token ids in them and the rows themselves. A
  state changed there can leave the memory in a condition that a later call ends the process
  over (the assertions llama.cpp checks its own bookkeeping with), or one that predicts
  wrongly without a sign; the same holds for a checkpoint of an image, which llama.cpp only
  reads when it is restored. So every piece is to be authenticated before the source returns
  it, and the build and the model compared before the import is called. Short of that the
  session refuses what it can tell: an image that does not begin as one of this layout; one
  written for a model of another shape (the sizes of the vocabulary, the trained context, the
  embedding and the layers, the number of parameters, the size of the weights, the kind of
  memory and the architecture's name, as one number in the image); sizes that do not add up;
  more tokens than the context holds (`PP_STATUS_CONTEXT_FULL`, from the image's first
  piece); a token id outside the vocabulary, or the token this model cannot take as input;
  a checkpoint that belongs to a length beyond the image's tokens, or is larger than a
  checkpoint of the whole restored memory; a state for which llama.cpp asks for more bytes
  than the image says it has, or leaves some; a range llama.cpp names outside the tensor it
  is filling (ggml ends the process over that; here it fails the import); a memory that does
  not hold the image's number of tokens afterwards; and anything behind the image. Of the
  176 bytes an image begins with, a change to any one is refused but for one thing nothing in
  an image can settle: the length a checkpoint belongs to, changed into another length
  within the memory.

  A model with windowed layers (Shard-2) writes of those layers only the window before the
  end of the memory into a state. An image of a memory longer than the window is therefore
  much smaller than the buffers it came from (71 MiB for 8,100 tokens), and the memory that
  is read back is what a memory is after a checkpoint was restored: a cut is only right while
  the windowed layers still hold the window before it, `pp_session_rewind_to` refuses any
  other as it does there, and the predictions are the uninterrupted memory's to within
  rounding (1.3e-4 at most where the test looks), not bit for bit, because the restored
  cells lie in other places of the buffers. While the memory is no longer than the window it
  comes back cell for cell, and bit for bit.

  Measured on a desktop (6 threads, a batch of 256, pieces of 1 MiB, four checkpoints at the
  quarters of the memory as the engine then kept four; not yet measured on the phone; the engine
  now keeps two more, which follow a reply, and an image of Qwen3.5 or Boris-2 carries up to
  two checkpoints more than below, 19.3 MiB or 0.36 MiB each):

  | Model, tokens in memory (context) | Image | Of which the four checkpoints | Export, the sink only takes the piece | Export, the sink copies each piece | Import, the source copies each piece | Reading the same tokens instead |
  | --- | --- | --- | --- | --- | --- | --- |
  | Qwen3.5, 4,000 (4,096) | 143.3 MiB | 77.1 MiB | 9 ms | 21 ms | 39 ms | 11.7 s |
  | Qwen3.5, 7,100 (7,168) | 179.7 MiB | 77.1 MiB | 12 ms | 25 ms | 46 ms | 24.7 s |
  | Qwen3.5, 16,200 (16,384) | 286.6 MiB | 77.1 MiB | 18 ms | 39 ms | 60 ms | 95.4 s |
  | peacebell, 4,000 (4,096) | 254.0 MiB | 32 bytes | 17 ms | 36 ms | 37 ms | 7.4 s |
  | peacebell, 9,100 (9,216) | 577.8 MiB | 32 bytes | 38 ms | 83 ms | 80 ms | 26.0 s |
  | Boris-2, 500 (512) | 32.1 MiB | 1.5 MiB | 2 ms | 5 ms | 5 ms | 0.4 s |
  | Boris-2, 2,040 (2,048) | 125.4 MiB | 1.5 MiB | 8 ms | 17 ms | 18 ms | 2.1 s |
  | Shard-2, 4,000 (4,096) | 71.3 MiB | 32.0 MiB | 4 ms | 10 ms | 17 ms | 2.2 s |
  | Shard-2, 8,100 (8,192) | 103.4 MiB | 32.0 MiB | 7 ms | 15 ms | 24 ms | 4.5 s |

  An image grows by the key-value bytes of a token (the table of "A context that grows with
  the conversation"), the 12 or 24 bytes llama.cpp records of its cell and the 4 of its id:
  12,316 bytes for Qwen3.5, 66,576 for peacebell, 63,516 for Boris-2; for Shard-2 40,988
  up to its window and 8,208 beyond it, where only the layers without a window add rows. The
  fixed part is the recurrent state (19.3 MiB for Qwen3.5, 0.36 MiB for Boris-2), and a
  checkpoint is what it is in memory (19.3 MiB, 0.36 MiB, 8 bytes for peacebell, 8.0 MiB for
  Shard-2 beyond its window). On this machine the copy itself is 15 GiB per second out of
  the context and 3.6 to 7 into it (the lower figures where megabytes of checkpoints are
  allocated on the way), so these times say only that the native part will not be what a
  save or a restore waits for: sealing, storage and the phone's memory bandwidth are, and
  none of them is in these figures. After an import the process holds the checkpoints again
  and nothing else it did not hold before (77 MiB more resident for Qwen3.5, 1.5 MiB for
  Boris-2). What an import allocates on the way was held to a limit on the address space of
  the process: peacebell's 578 MiB went in with 256 KiB to spare, and Qwen3.5's 16,200 tokens
  with 1 MiB to spare beyond the 77 MiB of the four checkpoints; with 256 KiB a checkpoint
  could not be allocated, and the import answered `PP_STATUS_OUT_OF_MEMORY` with the memory
  empty. So llama.cpp's bookkeeping for the restored cells is between 16 and 65 bytes per
  token there. None of this has run on a phone. `test_memory` is built for one as
  `test_residue` is (the commands under "Forgetting", with `--target test_memory`) and takes
  the same `--gguf`; the checks with a limit on the address space are left out there, because
  Android's allocator ends the process over a mapping that fails.

What a caller must know beyond the header is little: one thread should make all the calls of a
session, a prediction exists only between a complete evaluation and the next call that changes
the memory (sampling at any other time is an error, not stale output), and after a restore at
least one token has to be evaluated before sampling, because a checkpoint holds state, not
scores.

The tests are `session/tests/test_session.cpp`, registered as `<model>.session`,
`session/tests/test_penalties.cpp`, registered as `<model>.penalties`, and
`session/tests/test_memory.cpp`, registered as `<model>.memory` (a few seconds to half a
minute each), run with the rest by `ctest` and `tools/host-check.sh` (`ctest -R session` for
the first alone, `ctest -R penalties` for the second, `ctest -R memory` for the third). Each session test takes
between ten seconds and two minutes, depending on the model and on what else the machine is
doing, and writes up to 2.2 GB into the build directory while it runs, the file that holds the
model next to another model, and deletes it afterwards. The checks that hand the session a
size no machine can serve run inside a limit on the address space of the test process
(`address_space_limit` in that file), so that a session which tried to serve one fails the
check instead of taking the machine down; keep new checks of that kind inside it.

### Forgetting

`pp_session_clear_memory` is what runs when the app locks. It frees the sampler chain, the
batch and the context and creates them again from the parameters they were created with, the
context at the size the session was opened with; the model stays loaded and the thread pool is
kept. `pp_session_resize_context` does the same overwriting and freeing for another size, and
the block it carries the memory through is overwritten before it is freed; `<model>.residue`
changes the size both ways (memory carried, memory emptied) and requires of the result what
it requires of a clear. `pp_session_export_memory` and `pp_session_import_memory` put the
memory through a buffer of the caller's and overwrite it before they return; an import that
does not succeed overwrites what it had taken in (the memory's buffers, the record of the
tokens, the checkpoints it had read) and leaves the memory empty. Before each is freed, what it holds of the
conversation and can be reached is overwritten: every checkpoint, the scores of the last
prediction (which are also overwritten as soon as a token has been sampled from them, and when
the next evaluation begins), the session's candidate list, the tokens in the batch, the
session's record of the tokens in memory, from which its penalties are computed (the sampler
chain itself holds no token), and the key-value and state buffers of the memory. The front-end overwrites its normalised copy of a text and the token ids it
produced before it frees them, and the JNI binding its copies of the arrays it was handed.
Then the allocator is asked to give its free pages back.

The allocator is also asked to give its free pages back at once wherever one context replaces
another: after the old context is overwritten and freed and before the new one is created (a
clear, a change of size and each of its fall-back attempts, and the first small context of an
opening). An allocator that keeps a freed block's pages resident until it is told to purge them
(Android's) would otherwise carve the new context's large, never-touched reservations out of
those pages, which then stay resident for good under live allocations, with whatever of the
old context was not overwritten still in them. On the host glibc gives large blocks back as
soon as they are freed, so no host test can see the difference; it is measured on the phone.

**That does not remove the conversation from the process, and nothing here claims it does.**
llama.cpp copies what it is given into allocations of its own and frees them as they are:

| What stays readable | Which allocation of llama.cpp it is in |
| --- | --- |
| The text of every piece that was tokenised | The `std::string` that `llama_tokenize` makes of its argument (`llama_vocab::tokenize`, `src/llama-vocab.cpp`), the fragment's text in `llama_vocab::impl::tokenize`, and for a SentencePiece vocabulary the string that `llama_escape_whitespace` builds from it. Three copies of a text for peacebell, two for the others, each time it is tokenised |
| Token ids of the prompt | The vector the tokenizer collects its result in, each time it grows, and the result vector of `llama_tokenize`; `token_vec` of the context's batch allocator (`llama_batch_allocr::init`, `src/llama-batch.cpp`), which holds the tokens of the largest chunk evaluated; the copy each micro-batch makes of its tokens (`llama_batch_allocr::ubatch_add`) |
| Not searched for by the test below | The activations of the last chunk in the context's compute buffers, from which the rows of the embedding table give back the tokens; words of the text, shorter than a whole window, in the temporaries of the BPE tokenizers; whatever a call left on a stack |

A freed block stays readable until the allocator hands it out again or gives its page back,
and the last step above only achieves that for pages that are free as a whole. What is found
therefore depends on what the allocator did with those blocks, which changes with the program
and the build. `session/tests/test_residue.cpp` (`ctest -R residue`) measures it: it evaluates
a text, samples a reply, forgets, and searches every writable page of its own process for 16
consecutive bytes of the text, 6 consecutive token ids of the prompt and 4 of the reply. It
requires that no token ids of the reply are found, which the session can promise, and reports
the rest. On a desktop with glibc, as windows found after `pp_session_clear_memory` (the
figures after `pp_session_close` are the same or lower):

| Model | Text, before this was done | Text, now | Prompt ids, before | Prompt ids, now | Reply ids, before | Reply ids, now |
| --- | --- | --- | --- | --- | --- | --- |
| peacebell-v1-291m | 914 | 705 | 69 | 21 | 9 | 0 |
| boris-2-instruct | 162 | 162 | 69 | 0 | 9 | 0 |
| qwen3.5-0.8b | 34 | 0 | 74 | 0 | 16 | 0 |

"Before" is a session that reset the context and the sampler instead of freeing them and
overwrote only the checkpoints, measured by the program this test was made from. The figures
are the same from run to run and not from build to build: that program, with the same search
and another heap, finds no prompt ids for peacebell where this test finds 21, and an earlier
build of this test found 21 for Qwen3.5. The zeros in the text and prompt columns are
therefore the allocator's doing, not the session's, and nothing to rely on; the 705 and the
162 are the copies of the first table, untouched.

The same program then writes the conversation out as a memory image, which it keeps masked
as it keeps everything, and looks for the memory itself as well: 24 consecutive bytes from
each of eight places of the image's state, key and value rows and recurrent state. None is
found after the export and a clear, after an import that its source stopped at the very last
call (when the whole image was in), after a complete import and a clear, or after closing,
for any of the four models; while the memory is there, all eight are. No window of the
reply's token ids is found at any of those points either, and the test requires it. For
Qwen3.5 and Boris-2 that is the work of patch 0005. Their state carries the id of every
token, llama.cpp reads those ids into two lists of its own when it restores the cells, and
without the patch the scan found, after the import that was stopped, all 9 windows of the
reply's ids and 64 of the prompt's (Boris-2) or 9 and 45 (Qwen3.5) in the freed lists, and
for Qwen3.5 the same 9 and 45 after the complete import, a clear and closing; with it, none
of either. A change of context size that carries the memory over goes through the same
reader: straight after one, without the patch, the scan found the reply's ids of Boris-2
twice, once in the session's record and once in a freed list.

A window of the reply that also occurs in the prompt is counted with the prompt and not as the
reply's: Qwen3.5 answers this text by repeating part of it, ten of its twelve reply tokens are
a stretch of the prompt, and finding those says where the prompt is. That is also why its
"before" figure is 16 and not 9: seven of the sixteen were the prompt, in a micro-batch copy.

On the phone the allocator is another one (Android's scudo), and it keeps more. The same test
built for Android and run from `adb shell` on the reference phone of "On the phone", twice for
each model with identical results, found after `pp_session_clear_memory` and after
`pp_session_close`:

| Model | Text after tokenising alone | Text, after clearing / closing | Prompt ids, after clearing / closing | Reply ids, after clearing / closing |
| --- | --- | --- | --- | --- |
| peacebell-v1-291m | 744 | 496 / 0 | 39 / 27 | 0 / 0 |
| boris-2-instruct | 534 | 0 / 0 | 40 / 27 | 0 / 0 |
| qwen3.5-0.8b | 534 | 0 / 0 | 27 / 0 | 0 / 0 |
| shard-2 | 534 | 534 / 534 | 54 / 54 | 0 / 0 |

So on the phone, too, the text of a prompt (peacebell, Shard-2) and stretches of its token ids
(all four) are still in the process after the call that a lock makes, and for Shard-2 even
after the model was closed. These were measured in a program started from the shell, not in
the app's own process, which the system starts differently and may run with other allocator
settings; they show what this allocator does with freed blocks, not a figure for the app.
The test is built for the phone from this directory with the NDK's toolchain file and nothing
else, and leaves nothing on the phone:

```sh
# cmake and ninja of the SDK ($ANDROID_HOME/cmake/3.31.6/bin) first on PATH, as measured
cmake -S native -B /path/to/scratch/android -G Ninja \
      -DCMAKE_TOOLCHAIN_FILE="$ANDROID_HOME/ndk/29.0.14206865/build/cmake/android.toolchain.cmake" \
      -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-28 -DANDROID_STL=c++_static
cmake --build /path/to/scratch/android --target test_residue
adb shell mkdir -p /data/local/tmp/proseplow-dev
adb push /path/to/scratch/android/session/tests/test_residue /path/to/models/<id>/<id>-f16.gguf /data/local/tmp/proseplow-dev/
adb shell chmod 755 /data/local/tmp/proseplow-dev/test_residue
adb shell /data/local/tmp/proseplow-dev/test_residue --gguf /data/local/tmp/proseplow-dev/<id>-f16.gguf
adb shell rm -r /data/local/tmp/proseplow-dev
```

What would remove the rest is not in this library. Two things can: ending the process, which
is the only one that is certain; or overwriting every block as it is freed, in every library
built here. The second was tried on the host by linking a replacement of the global `operator
delete` that zeroes a block before it frees it: the same test then finds no text at any
stage, not even straight after tokenising, and nothing at all once the conversation is
forgotten, for all three models. It costs a pass over every freed block, which on the host did
not show: the session test ran at the same speeds with it, prompt and generation, within the
noise between two runs. It is not part of the build, because the place where it has to work
has not been tried: on Android the replacement must be the one the dynamic linker resolves
for `libllama.so`, for the CPU backend loaded later and for `libc++_shared.so`, and whether to
go that way or to end the process at a lock is a decision about the app, not about this
library.

## The Android build

`../llama/` is an Android library module. Building it compiles everything above for arm64 and
packages it with the JNI binding; the application module depends on it and calls three things:
`NativeEngine.factory(context)`, which opens a model for the engine,
`NativeEngine.availableModels(context)`, which says which models this build carries, and
`NativeEngine.runtimeDescription(context)` for a diagnostics line.

### What you need

In addition to the host tools above (only git and bash are used by this build, by
`tools/prepare-llama-src.sh`):

| Tool | Version that was used | Where from |
| --- | --- | --- |
| JDK | Temurin 21.0.13 | the same JDK as for the rest of the repository; `source env.sh` puts it on `PATH` |
| Gradle | 9.7.0 | the wrapper, `./gradlew` |
| Android Gradle plugin | 9.3.3 | `gradle/libs.versions.toml` |
| Android SDK platform, build-tools | 37, 37.0.0 | SDK packages, as for the rest of the repository |
| Android NDK | r29, `29.0.14206865` (clang 21.0.0) | the SDK package `ndk;29.0.14206865`, installed with `sdkmanager` like the others |
| CMake, Ninja | 3.31.6, 1.12.1 | the SDK package `cmake;3.31.6`, which brings its own Ninja |

The NDK and CMake versions are pinned in `../llama/build.gradle.kts` (`ndkVersion`,
`externalNativeBuild.cmake.version`); Gradle refuses to build with others rather than download
them (`android.builder.sdkDownload=false`). The NDK is the one upstream llama.cpp builds its own
Android release with.

### Building

```sh
git submodule update --init third_party/llama.cpp     # once, after cloning
source env.sh
export PROSEPLOW_JOBS=6                                # optional, see below
./gradlew :llama:testDebugUnitTest                     # its JVM tests (thread plan, asset names)
./gradlew assembleDebug                                # the app with the libraries and the models inside
```

Four things happen that an ordinary library module does not have:

1. **`prepareLlamaSource`** runs `tools/prepare-llama-src.sh` into `llama/build/llama-src`. Its
   inputs are the pinned commit, the patches and the files of `llama-ext/`, so it runs again
   exactly when one of them changes.
2. **CMake** configures and builds `../llama/src/main/cpp/CMakeLists.txt` with the NDK's
   toolchain file. Gradle passes only the ABI and platform (`arm64-v8a`, `android-28`, from the
   module's `abiFilters` and `minSdk`), `ANDROID_STL=c++_shared`, `CMAKE_BUILD_TYPE=Release`
   (for every variant: a debug and a release APK contain the same optimised libraries) and the
   location of the prepared copy. Everything else is in that file, with its reasons:

   | Option | Value | Why |
   | --- | --- | --- |
   | `BUILD_SHARED_LIBS`, `GGML_BACKEND_DL`, `GGML_CPU_ALL_VARIANTS` | `ON` | The CPU backend is built seven times, from ARMv8.0 to ARMv9.2 with SME, and the best one for the phone's processor is loaded at run time |
   | `GGML_NATIVE` | `OFF` | Nothing may be tuned to the build machine; no `-march` is set anywhere but by the variants |
   | `GGML_OPENMP` | `OFF` | The thread count is exactly the session's; no OpenMP runtime to package |
   | `GGML_LLAMAFILE` | `ON` | tinyBLAS, for the F16 kernel of patch 0002 |
   | `GGML_CCACHE`, `GGML_CPU_KLEIDIAI`, `GGML_LTO` | `OFF` | Nothing from outside the build tree; KleidiAI would be downloaded at configure time |
   | `GGML_OPENCL`, `GGML_VULKAN`, `GGML_HEXAGON`, `GGML_RPC`, `GGML_BLAS` | `OFF` | The CPU only (measured on the reference phone, the GPU generates more slowly than the CPU; Hexagon needs a proprietary SDK) |
   | `LLAMA_BUILD_COMMON`, `..._TESTS`, `..._TOOLS`, `..._EXAMPLES`, `..._SERVER`, `..._APP`, `LLAMA_OPENSSL`, `LLAMA_LLGUIDANCE` | `OFF` | Only `libllama`: the helper library carries a network client, and its presence fails the configuration |
   | `LLAMA_BUILD_NUMBER`, `LLAMA_BUILD_COMMIT` | `0`, the first seven characters of `patches/UPSTREAM_COMMIT` | The version stamp of the binaries does not depend on this repository's history |
   | `-ffile-prefix-map` | the repository, the NDK, the build tree and the prepared copy, each to a fixed name | No path of the build machine in any binary |

   `c++_shared` because there are eleven libraries of ours: with the static runtime each would
   carry a private copy, which the NDK only allows for an app with a single library. The
   runtime, `libc++_shared.so`, comes from the NDK and is packaged as it is.
3. **`convertModels`** makes sure the converted file of every selected model is there and is the
   pinned one, converting what is missing from its checkpoint with `../models/convert_models.py`,
   one model at a time, in the Python environment (`../models/README.md`, "Converting").
4. **`package<Variant>Models`** puts the selected model files into a directory of assets that
   only the app's APK and this module's test APK take in ("Models in the APK" below).

A clean native build takes 45 seconds with 6 jobs. Gradle starts Ninja without a job limit,
and Ninja then uses every processor; set `PROSEPLOW_JOBS` in the environment of the Gradle
invocation to limit it (the limit is fixed when CMake configures, as a Ninja job pool). The
native build tree is `llama/.cxx/`, about 160 MB, and the prepared copy another 200 MB under
`llama/build`. `./gradlew clean` does not remove `llama/.cxx`: delete it, and `llama/build`, for
a build from nothing.

### What is built

Twelve libraries of this module reach the APK, eleven built here and the C++ runtime. Sizes are
for the stripped files that both a debug and a release APK contain, measured for a build of
commit `af14daa`; later changes to the native code move `libllama.so` and
`libproseplow_jni.so` by some kilobytes. The app's APK carries one library more,
`libandroidx.graphics.path.so` (10,096 bytes), which Compose brings: thirteen in all, 13,588,024
bytes in that build, as the root `README.md` says. The app module strips them when it
packages them, which needs the same `ndkVersion` there as here: without it the build still
succeeds, prints "Unable to strip the following libraries", and every APK is about 80 MB larger.

| Library | Bytes | What it is |
| --- | --- | --- |
| `libllama.so` | 4,155,512 | llama.cpp |
| `libggml-base.so`, `libggml.so` | 771,272, 55,384 | ggml and its backend registry |
| `libggml-cpu-android_armv8.0_1.so` | 951,248 | The CPU backend for any arm64 processor |
| `libggml-cpu-android_armv8.2_1.so` | 985,568 | with the dot product instructions |
| `libggml-cpu-android_armv8.2_2.so` | 1,022,640 | and half-float arithmetic |
| `libggml-cpu-android_armv8.6_1.so` | 1,034,768 | and int8 matrix multiply |
| `libggml-cpu-android_armv9.0_1.so` | 1,038,816 | and SVE2 |
| `libggml-cpu-android_armv9.2_1.so`, `..._armv9.2_2.so` | 1,038,816 each | and SME, in two combinations |
| `libproseplow_jni.so` | 110,752 | The session, the tokenizer front-end and the JNI binding |
| `libc++_shared.so` | 1,374,336 | The C++ runtime, from the NDK |
| all twelve | 13,577,928 | |

Every loadable segment of every one is aligned to 16 KiB (`llvm-objdump -p`), so they load on
devices with 16 KB pages; the build checks that for each APK it writes (next section).

`libproseplow_jni.so` exports one symbol, `JNI_OnLoad`, which registers the native methods of
the Kotlin object `LlamaJni` from a table. The table and the Kotlin declarations must change
together; when they disagree, loading the library fails, not the first call. Everything the
native code looks up by name in Kotlin (that object, the constructor of `NativeLlamaException`,
the four callback interfaces) is listed in `../llama/consumer-rules.pro`, so an app that
switches minification on cannot rename it.

### Reproducibility

The native libraries are a function of the pinned llama.cpp commit, the patches, `llama-ext/`,
`lib/`, `session/`, the JNI sources and the pinned NDK and CMake, and of nothing else: not of
the directory the repository is checked out in, the time, the user or this repository's git
history. That was verified by building three times from nothing and comparing the SHA-256 of
every library, unstripped and stripped: twice in the same place, and once from a copy of the
sources under another path with the build tree and the prepared llama.cpp copy somewhere else
again.

All eleven libraries built here were identical in the three builds, byte for byte, and identical
to the ones in the APKs that Gradle built from the same sources; none contains a path of the
build machine (`strings` finds the repository as `.`, the prepared copy as `llama.cpp`, the
build tree as `build` and the NDK as `ndk`).

Three things make it so, and a change to the build should keep them: the prefix maps and the
fixed version stamp of the table above, and the absence of anything fetched or cached from
outside the build tree. The linker's build id stays switched on; it is a hash of the output
and therefore as reproducible as the output is.

## Models in the APK

The app does not download models and does not copy them at first start: a model is an asset of
the APK, stored without compression on a page boundary, which the app opens with
`AssetManager.openFd` and hands to native code as a descriptor, an offset and a length. Native
code maps exactly those bytes (patch 0003), so the weights are in memory once, as file-backed
pages that the system can drop and read again when memory is short.

Which models a build carries is decided when it is built:

| Gradle property | Environment variable | Default | Meaning |
| --- | --- | --- | --- |
| `-Pproseplow.models=...` | `PROSEPLOW_MODELS` | `all` | `all`, `none`, or ids of `../models/manifest.json` separated by commas. `all` is every entry that is `ready` and `bundled` |
| `-Pproseplow.modelDir=...` | `PROSEPLOW_MODEL_OUT` | `$PROSEPLOW_BUILD/models`, else `~/proseplow/build/models` | The directory that holds the converted models as `<id>/<output>`, which is where `models/convert_models.py` writes them (`../models/README.md`) |
| `-Pproseplow.modelSrc=...` | `PROSEPLOW_MODEL_SRC` | `~/proseplow/checkpoints` | The checkpoints, for a model that must be converted first |
| `-Pproseplow.venv=...` | `PROSEPLOW_VENV` | `~/proseplow/venv` | The Python environment that converts |

A property wins over the variable of the same row.

```sh
./gradlew assembleDebug -Pproseplow.models=none                # no model: quick to build and to install
./gradlew assembleDebug -Pproseplow.models=peacebell-v1-291m
./gradlew assembleRelease                                      # a real build, signed with your key (root README.md)
```

`convertModels` runs first and leaves every selected file converted and pinned, or fails with
what to download. The models are not assets of the library itself, because Android lint of the
app reads the library's merged assets and would then convert and copy them for every `check`.
They reach the app's APK through the app's task `add<Variant>ModelAssets`, which joins them
(as hard links where the file system allows) to the app's merged assets, and this module's test
APK as generated assets of its `androidTest` component. Only the tasks that build one of those
APKs (`assemble`, `install`, `bundle` and the connected tests) convert and package models;
`check`, lint and the unit tests need none. `package<Variant>Models` (in `../llama/build.gradle.kts`) copies each selected file to the
asset `models/<id>.gguf.so` and computes its SHA-256 on the way; a file that is not byte for
byte the conversion pinned by `output_sha256` in the manifest fails the build. The task runs
again when the selection, the manifest or a model file changes. A build without models says so
in its output. The app copes with any subset: it offers what `NativeEngine.availableModels`
returns, and the catalogue of the `core` module is tied to the manifest by a unit test of this
module (an asset is named after the manifest's id, the app looks for the catalogue's file name).

The `.so` at the end of the asset's name is there for the packaging tools, and two settings of
the **application** module are needed with it (they are in `../app/build.gradle.kts`):

- `androidResources { noCompress += "gguf.so" }`: the asset is stored, not deflated. Only a
  stored asset can be opened as a file descriptor. (With the packaging of the next point AGP
  stores every entry named `.so` in any case; the line says what is meant and holds if that
  ever changes.)
- `packaging { jniLibs { useLegacyPackaging = false } }`: native libraries are stored in the
  APK and mapped from there rather than extracted when the app is installed. In this mode, and
  only in this mode, AGP aligns every stored entry whose name ends in `.so` to 16 KiB, assets
  included; with the legacy packaging a stored asset is aligned to 4 bytes, and a model
  could then only be mapped by luck. `zipalign` and `apksigner` treat the suffix the same way,
  so the alignment survives re-signing.

Because the libraries are not extracted, there is no directory of them to list, which is how
llama.cpp itself chooses a CPU variant (`ggml_backend_load_all_from_path`). The Kotlin side
reads the names of the `libggml-cpu-*.so` entries from the APK instead (or from the library
directory, on an installation that did extract them), and `pp_runtime_init` loads the best of
them by name, which the dynamic loader resolves inside the APK.

The settings are checked on the result, not trusted, and so is the identity of the models: the
application module applies `../llama/apk-checks.gradle.kts` (and `../llama/` applies it to
itself, for the test APK of "On the phone"), which makes every task of the module that writes
an APK, an app bundle or an APK set fail unless

- each model in it is one the manifest accounts for: an asset `models/<id>.gguf.so` for an entry
  that is `ready` and `bundled`, whose SHA-256, computed on the bytes **as they are in the
  archive**, is the pinned one. Anything else under `assets/models/`, and any file named
  `*.gguf` or `*.gguf.so` anywhere in the archive, fails the build: an asset of that name in
  another source directory would otherwise be packaged with nothing vouching for it;
- in an APK, each model is stored and starts on a 16 KiB boundary;
- each native library has its loadable segments aligned to 16 KiB.

A bundle is checked for the first and the last; where its models lie is decided when APKs are
made from it, and an APK set is unpacked and each APK in it checked. The tasks are not found by
name or class: every task of the module looks at its own outputs when it has run. Hashing adds
about a second per gigabyte of models to the packaging task. The findings are in
`app/build/reports/native-packaging/` (`llama/build/...` for the test APK), one file per task
that wrote an archive.

What packaging costs, measured for a debug build on a desktop with an SSD:

| Bundled | Time of `:app:assembleDebug` after a build without it | APK | Under `app/build` and `llama/build` for it |
| --- | --- | --- | --- |
| nothing | | 50.2 MB | |
| peacebell-v1-291m (583.7 MB) | 10 s | about 634 MB | 2.9 GB |
| qwen3.5-0.8b (1,516.7 MB) | 27 s | about 1,567 MB | 7.4 GB |
| three: peacebell, Boris-2, Qwen3.5 (2,392.3 MB) | 65 s | 2,442.5 MB | 12 GB |
| all four, with Shard-2 (2,491.3 MB) | 76 s, which now includes hashing the models in the APK | 2,541.3 MB; 2,542.1 MB when built again with Shard-2 bundled for good | not measured |

AGP keeps four copies of an asset on its way into an APK (the generated directory, the merged
assets of the library, those of the app, and a packaging intermediate) besides the APK itself:
five times the model. A build that changes nothing takes 3 seconds; installing the APK with
three models over USB took 70 to 77 seconds, with all four 72. The times and the disk use of
the first four rows were measured before the libraries were stripped; the APK sizes of the two
single-model rows are computed from the stripped build, the others were built. A debug APK
is written afresh when the selection of models changes: `../llama/apk-checks.gradle.kts` makes
the selection and the manifest inputs of the packaging tasks. Without that, AGP updates the
file in place, and a model that leaves the selection leaves a gap of its own size.

## On the phone

Everything in this section was run on a Samsung Galaxy XCover7 Pro (SM-G766U1, Snapdragon 7s
Gen 3, four Cortex-A720 and four Cortex-A520 cores, 5.5 GB of memory, Android 17). The tests
need a device with USB debugging, awake and unlocked: they put an activity of their own in
front while they measure, because Android gives a process without a visible activity fewer
cores.

### A model without rebuilding the test APK

For the device tests below only: a model file in the private storage of the test package is
used in place of (or instead of) the asset of the test APK, so that 1.5 GB of weights need not
be packaged and installed again for every change to the code. The file is `files/models/<id>.gguf` in the data
directory of the test package, written through `run-as` without a temporary copy on the phone:

```sh
PACKAGE=us.theworkmans.proseplow.llama.test
adb shell "run-as $PACKAGE sh -c 'umask 077 && mkdir -p files/models'"
adb exec-in "run-as $PACKAGE sh -c 'umask 077 && cat > files/models/qwen3.5-0.8b.gguf'" < /path/to/models/qwen3.5-0.8b/qwen3.5-0.8b-f16.gguf
adb shell "run-as $PACKAGE sha256sum files/models/qwen3.5-0.8b.gguf"    # compare with output_sha256 in the manifest
adb shell "run-as $PACKAGE rm files/models/qwen3.5-0.8b.gguf"           # to remove it again
```

`adb exec-in` sends the file to the command's standard input as it is (`adb shell` would
translate line ends). It took 5 seconds for the 1.5 GB file over USB 3. The file survives
`adb install -r` and is deleted with the package's data. For the tests, a sideloaded file wins
over an asset of the same model. Nothing verifies it, which is the point of it and the reason
the code that looks for it is part of the tests (`../llama/src/androidTest/.../SideloadedModels.kt`,
handed to the factory by the tests themselves) and of no build of the library or the app: the
library's own lookup finds the assets of its APK and nothing else, so a debug build of the app
runs exactly the models a release build carries. `RuntimeDeviceTest` checks that on the phone
for every file placed.

### The tests

`../llama/src/androidTest` holds the checks that need the phone's processor: they are not part
of `check`. The models come from the test APK's own assets or from a sideloaded file, and a
test whose model is absent is reported as skipped.

```sh
# 1. The test APK with the two smaller models inside (0.9 GB), built and installed by hand so
#    that it stays installed; Qwen3.5 (and Shard-2, if wanted) sideloaded as above with the
#    test package's name. Or all four bundled models inside with -Pproseplow.models=all:
#    2.5 GB, over a minute to install over USB.
./gradlew :llama:assembleDebugAndroidTest -Pproseplow.models=peacebell-v1-291m,boris-2-instruct
adb install -r llama/build/outputs/apk/androidTest/debug/llama-debug-androidTest.apk

# 2. Everything, or one class
adb shell am instrument -w us.theworkmans.proseplow.llama.test/androidx.test.runner.AndroidJUnitRunner
adb shell am instrument -w -e class us.theworkmans.proseplow.llama.QwenDeviceTest us.theworkmans.proseplow.llama.test/androidx.test.runner.AndroidJUnitRunner

# 3. Afterwards
adb uninstall us.theworkmans.proseplow.llama.test
```

`./gradlew :llama:connectedDebugAndroidTest` with the same properties does the first two steps
in one, but uninstalls the test APK when it is done, and a sideloaded model with it. Each
measurement is printed as a line that begins with `PROSEPLOW` (`am instrument -r` shows them);
no test prints prompt or reply text. The whole run takes about four minutes with all four
models.

The phone has to stay awake and unlocked for all of it, and a screen that times out locks it
in the middle of a run without any test failing: the correctness checks pass behind a lock
screen as well, only every speed is then that of a process that is not in front (the lines
report the set of cores they were measured in, which is how to tell). `input keyevent
KEYCODE_WAKEUP` sent from a computer does not count as activity and does not stop the timeout;
a key event that reaches a window (a bare Shift, `input keyevent 59`) does.

What is checked, for each model that is present:

| Test class | What must hold |
| --- | --- |
| `RuntimeDeviceTest` | The libraries load from inside the APK and a CPU variant is selected; `cpu_capacity` is readable and gives a thread plan; the models the build reports are the ones that can be located |
| `PeacebellDeviceTest`, `BorisDeviceTest` | Tokenising the 7,642 committed texts and the committed conversations gives the host's token ids, as plain text and with special tokens parsed; the greedy replies to the committed prompts equal the PyTorch reference's token for token; the memory is shortened (peacebell) or refuses and comes back through a checkpoint (Boris-2) with a continuation identical to a fresh evaluation; a cancellation interrupts a chunk (peacebell) or waits for it (Boris-2) and leaves the memory usable; load time, memory and speed are reported; memory is given back on close. Boris-2 further: the end-of-text token is refused as input, and the reference's chat session of eight requests through the engine of `core` gives the reference's prompt lengths and replies |
| `QwenDeviceTest` | A greedy reply to a ChatML prompt is sane and the same twice; the ChatML markers are single tokens when the app writes them and text inside a message; the removal of the end of the memory is refused and a checkpoint brings it back with an identical continuation, twice; cancelling; load time and memory, mapped and read; speed with pinned and unpinned threads, in front and not |
| `ShardDeviceTest` | Tokenising the 8,139 committed texts gives the host's token ids, as plain text and with special tokens parsed; the greedy continuations of the committed beginnings equal the reference's token for token, deletion tokens included; within the window the end of the memory is removed and the continuation equals a fresh evaluation; on the committed text of 1,500 tokens a cut many windows back is exact, a checkpoint brings the memory back exactly, and after that restore a cut below what the restored window reaches is refused with the memory untouched while a cut within it is made; a cancellation interrupts a chunk; load time, memory and speed are reported |
| the three chat models | The engine of `core` (`LlamaEngine`) holds a chat of two turns on the real native layer and reuses its memory for the second |
| `MemoryImageDeviceTest` | For each model present: a prompt of up to 240 tokens with two checkpoints is written out through the binding in pieces of 4 MiB, with 64 bytes of the caller's own at the start of the first piece, which come through unchanged; exactly the announced bytes arrive, "last" is said once, and the buffer is zeros afterwards. An export stopped by the sink, by false and by an exception, leaves the memory as it was. After the memory is forgotten, an import whose source throws in the middle of the state (crossing from llama.cpp's reading of the state back out as a C++ exception) ends in that same exception, one whose source stops ends in code 11 and one whose image ends early in code 12, each with no token, no record and no checkpoint left and the buffer zeros. The image then goes back in: the record of the tokens and both checkpoints come back, no prediction is ready, and the next token and 16 greedy tokens equal those of the uninterrupted control, also after going back through a restored checkpoint. The size of the image and the time of the export and of the import (to and from a direct buffer, without encryption or storage) are reported |
| `JniBoundaryDeviceTest` | With the smallest model present: a mistake of the caller arrives as `NativeLlamaException` with its code and leaves the session usable; an exception thrown by a progress or cancel callback during a native call comes back out as itself; a load can be abandoned, and an abandoned or failed load leaves nothing allocated; a closed session never reaches native code; a size beyond its limit (a context beyond its budget, a text, a number of tokens or of banned tokens) arrives as an exception with its code and allocates nothing, what a context costs is known, a change to a size above the model's largest or above the budget is refused with the context as it was, a penalty window beyond the context costs nothing, the ninth checkpoint is refused, and forgetting a conversation leaves a session that is whole |
| `RereadDiagnosisDeviceTest` | For Qwen3.5 and Boris-2, through the engine of `core` over the real session, with a fixed seed: after an answer that ended by itself and one cut off at 96 tokens, a read-ahead (it must read for Qwen3.5, and for Boris-2 find nothing to do and change nothing), after which the next message must keep the whole memory and read no more than its own text and 48 tokens; after an answer stopped after 60 pieces, "Continue" without a read-ahead must read no more than 64 tokens and what differs from the memory. Each case prints a `PROSEPLOW reread` line with counts and times. Run it alone with `-e class us.theworkmans.proseplow.llama.RereadDiagnosisDeviceTest` |
| `ThreadMoveDeviceTest` | With the smallest model present: the test's activity leaves the front while one long prompt is being evaluated, and when that same call returns the session runs the plan's free threads and no longer claims to be pinned; the time of each chunk before and after the move is reported |
| `BackgroundSpeedDeviceTest` | Nothing is required: for Qwen3.5 and peacebell, the speed of a prompt and of generation with the test's activity in front and then with nothing of the test in front, each line with the set of cores the process was in |
| `ContextGrowthDeviceTest` | Nothing is required beyond the sizes and codes: opt-in (`-e contextGrowth true`, and `-e contextFraction` for another share than a quarter). For Qwen3.5, peacebell and Boris-2 it reports the cost of the opening context and of the largest, the speed of a prompt and of generation at the opening size nearly empty and full, then doubles the context for as long as the growth stays inside that share of the memory the system reports free above its low-memory threshold, with 256 tokens in memory to carry over: for each size the time, how many tokens were still in memory, the process's memory before and after, and the speeds nearly empty; then the time of forgetting at the largest size and the memory after it, which shows whether a freed context goes back to the system |
| `ReadingDepthDeviceTest` | Nothing is required: opt-in (`-e readingDepth` with a number of tokens; `-e readingChunk`, `-e readingModel` and `-e contextFraction` as its header says). With the test's activity in front it reads a model to that depth in blocks of 512 tokens, reports the time of each block and of generation at that depth, and fits a line through the cost of a token against the tokens already read |
| `ScreenOffSpeedDeviceTest` | Nothing is required, and nothing happens unless it is asked for with `-e screenOff true`: it turns the screen off, which locks the phone, and measures the same two speeds without and with a partial wake lock. **Built, never run**: the figures for a locked phone below come from a run of the whole suite during which the phone had locked itself |

`JniBoundaryDeviceTest` also times the longest text a session accepts, for every model present
(`theLongestTextIsTokenisedWithoutAStall`): 256 KiB of line breaks must tokenise in under five
seconds.

### What was measured

The CPU variant selected is `libggml-cpu-android_armv8.6_1.so` (NEON, FMA, half-float
arithmetic, dot product, int8 matrix multiply). `cpu_capacity` reads 453 for cpu0 to cpu3, 984
for cpu4 to cpu6 and 1024 for cpu7, which gives four pinned threads on cpu4 to cpu7 and three
unpinned threads otherwise. All four models are the F16 files, with a context of 4,096 tokens
(Boris-2: 2,048), mapped.

| | peacebell-v1-291M | Boris-2-Instruct | Qwen3.5-0.8B | Shard-2 |
| --- | --- | --- | --- | --- |
| Tokenisation against the host vectors, texts / conversations differing | 0 of 7,642 / 0 of 22 | 0 of 7,642 / 0 of 118 | | 0 of 8,139 (it has no conversations) |
| Greedy replies equal to the reference | 5 of 5 | 5 of 5, and 8 of 8 chat replies through the engine | same twice, names the capital | 5 of 5 |
| Load, mapped / read into memory | 0.15 s / 0.5 to 0.6 s | 0.15 s | 0.52 s / 2.0 s | 0.07 s |
| Memory of the test process (PSS) before the model is loaded | 67 MiB | 64 MiB | 53 MiB | 47 MiB |
| With the model loaded, mapped: all, anonymous + file-backed | 888 MiB, 298 + 590 | 475 MiB, 167 + 308 | 1,621 MiB, 156 + 1,465 | 304 MiB, 190 + 115 |
| The same read into memory | 882 MiB, 849 + 32 | | 1,597 MiB, 1,582 + 16 | |
| After closing | 62 MiB | 61 MiB | 51 MiB | 41 MiB |
| Prompt, four pinned threads, in the engine's chunks of 32 / one batch, tokens per second | 183 / 190 | 379 / 413 | 89 / 92 | 791 to 834 / 887 to 938 |
| Prompt, three unpinned threads | 140 / 150 | 289 / 323 | 67 / 70 | 640 / 750 |
| Generation, tokens per second | 24 | 52 | 9.3 | 123 to 129 |
| A checkpoint | 8 bytes | 381,700 bytes | 20,201,932 bytes | the window of its windowed layers: 8,392,096 bytes once the window is full (measured on the host) |
| Cancelling | 0.1 ms, inside a chunk | at the end of the chunk: at most 0.11 s | at the end of the chunk: at most 0.46 s in a prompt, 0.14 s in a reply | 0.1 ms, inside a chunk |
| Forgetting a conversation after a prompt of about 180 tokens | 38 to 161 ms | 24 to 28 ms | 15 to 19 ms | 28 to 70 ms |
| The longest text a session accepts, 256 KiB of line breaks, tokenised | 61 ms | 45 ms | 255 ms | 55 ms |

Speeds varied by a few per cent between runs, and by more when another app was busy; the
figures of the first three columns are from one run of the whole suite, the cancellation times
the longest of three runs. That run was made before the session refitted its threads at every
chunk, freed and recreated its context when forgetting and checked its sizes; the speeds were
measured again afterwards. The prompts came out the same to within one per cent (184 / 190,
378 / 411 and 90 / 92 tokens per second), generation at 24, 49 to 54 and 9.4 against the 24,
52 and 9.3 of the table. The load times, the last two rows and the Shard-2 column are from
those later runs. The mapped loads are 0.01 to 0.07 s longer than they were, more for the
models with the larger headers; the likely reason is that loading reads the header twice now,
once to hold the model to the length it was given. In one of three repeats of the peacebell
measurement the process was in the `foreground` set and not in `top-app` when it began, could
not be pinned and ran its prompt at 137 instead of 184; the line said so.

- **Threads.** Four pinned threads are the fastest arrangement and the default; they need the
  app in front. With nothing of the test in front, the process was in the `foreground` set
  (cpu0 to cpu6), pinning was not possible, and the same default settings ran three unpinned
  threads by themselves: 63 to 67 tokens per second for a Qwen3.5 prompt instead of 89,
  generation unchanged. Four unpinned threads came out between the two, 68 to 80, differently
  in every run. Two threads on one core, by contrast, is the case to fear: it happened once
  through a mistake in the pinning, and a test that takes seconds had not finished after seven
  minutes.
- **A move in the middle of a prompt.** `ThreadMoveDeviceTest` evaluated 1,536 tokens with
  Boris-2 as one call of 48 chunks and took the test's activity out of the front after the
  second. Those two chunks took about 80 ms each on four pinned threads in the `top-app` set;
  the system then moved the process to the `foreground` set (cpu0 to cpu6), the session
  noticed at the next chunk and went on with the plan's three free threads, at 110 to 340 ms a
  chunk in two runs, and that is how the same call returned. A session that looked at its threads only
  when a call begins would have kept four threads pinned to cores of which one was no longer
  the process's to use.
- **Not in front, and locked.** Measured for the plan to let an answer finish after the app
  was put in the background or the phone locked; a prompt of 160 tokens in chunks of 32 and 24
  generated tokens, with the settings the app uses:

  | Where the test process was | Set of cores | Threads | Qwen3.5, prompt / generation | peacebell, prompt / generation |
  | --- | --- | --- | --- | --- |
  | Its activity in front (two runs) | `top-app`, cpu0 to cpu7 | 4 pinned | 90 / 9.4 to 9.5 | 183 to 185 / 22 to 25 |
  | Nothing of it in front, screen on (two runs) | `foreground`, cpu0 to cpu6 | 3 free | 67 / 9.2 to 9.3 | 138 / 25 |
  | The phone locked and its screen off, no wake lock held (one run) | `foreground`, cpu0 to cpu6 | 3 free | 67 / 9.3 | 139 / 25 |

  So a model that is not in front loses the fastest core and a quarter of its prompt speed,
  and keeps its generation speed; the lock and the dark screen by themselves took nothing
  more. (A second pair of measurements behind the lock screen, during one of which the screen
  came on, gave 52 / 8.8 and 120 / 24.) Three things limit what this says about the app. The
  test process is an instrumented one, which the system kept in the `foreground` set
  throughout; an app that merely went to the background, with no foreground service, may be
  put in a smaller set or stopped altogether, and that was not measured. The phone was on USB
  power, where it does not suspend as it does on battery, so "no wake lock" was not put to the
  test. And the locked figures were not taken by the test written for them
  (`ScreenOffSpeedDeviceTest`, which was not run because it locks the phone) but by the whole
  suite running while the phone had locked itself.
- **Forgetting.** What a lock waits for is in the table: 15 to 161 ms, the longest for the
  model with the largest memory per token. It frees and recreates the whole context whatever
  is in it, so it should cost the same after a long conversation; it was only measured after
  short ones (64 and about 180 tokens, with the same result for Boris-2).
- **The F16 kernel (patch 0002).** Measured with peacebell on three unpinned threads, the same
  build before and after the patch reached this phone's variant: a prompt at 46 tokens per
  second before and 132 after, generation at 21.5 and 24.3, the greedy replies equal to the
  reference both times.
- **Mapping against reading.** The same total, but mapped weights are file-backed: under
  memory pressure the system dropped 280 MiB of them in one run and read them back, where
  read weights could only have gone to swap. Mapped models also load three to four times as
  fast.
- **Memory to plan for.** peacebell's key-value memory for 4,096 tokens is about 270 MiB of its
  anonymous part, whatever the length of the conversation: clearing the memory writes zeros
  over all of it. A model with recurrent state adds its checkpoints: the engine keeps four at
  the boundaries of the prompt and two that follow a reply while it is written (one taken every
  32 tokens of the reply, the latest two kept), so at most six, 121 MB for Qwen3.5. The two
  that follow a reply serve "Continue" after a stop and a reply whose end tokenises
  differently; they do not spare Qwen3.5 reading an answer again before the next message,
  which its template causes before the answer's first token. The engine's read-ahead does that
  reading while the answer is read, and keeps one checkpoint where the next message begins in
  their place (`RereadDiagnosisDeviceTest`).
- **A context that grows** (`ContextGrowthDeviceTest`). The session's count of a context of
  4,096 tokens is 544 MiB for Qwen3.5 and 392 MiB for peacebell (346 MiB for Boris-2 at its
  2,048), of which the process had 143, 284 and 145 MiB resident as anonymous memory: the
  reservations that are never touched stay untouched on the phone as on the desktop. Growing
  Qwen3.5 with a short conversation in memory took 90 ms to 8,192 tokens, 133 ms to 16,384
  and 291 ms to 32,768, every token kept, and added 39, 87 and 184 MiB of resident memory,
  which is the new key-value buffers less the old; peacebell took 294 ms to 8,192 and added
  258 MiB. Forgetting at 32,768 tokens took Qwen3.5 100 ms and brought its anonymous memory
  from 490 back to 143 MiB at once; peacebell at 8,192 took 180 ms, from 535 to 272 MiB.
  Before the session gave the allocator's free pages back between freeing a context and
  creating the next (see "Forgetting"), the same run left Qwen3.5 at about 540 MiB after
  forgetting: this phone's allocator keeps freed pages resident, and the new context's
  untouched reservations had settled on them. A model in a large context that is nearly
  empty is as fast as in a small one.
- **Reading a long conversation** (`ReadingDepthDeviceTest`, the test's activity in front,
  four pinned threads). A token costs more the more tokens were already read, and so does
  every chunk that is evaluated in one go. Milliseconds for one token:

  | Tokens already read | Qwen3.5, chunks of 32 | chunks of 128 | chunks of 256 | peacebell, chunks of 32 | chunks of 256 |
  | --- | --- | --- | --- | --- | --- |
  | 0 | 11.3 | 14.0 | 11.0 | 7.5 | 6.6 |
  | 2,048 | 16.2 | 15.6 | 12.9 | 16.8 | 8.9 |
  | 4,096 | 21.2 | 17.0 | 17.4 | 28.0 | 11.2 |
  | 7,168 | 33.7 | 19.7 | 19.2 | 48.7 | 14.5 |
  | 15,360 | 71.7 | 25.2 | | | 23.4 |

  From nothing, Qwen3.5 read 4,096 tokens in 64 s, 8,192 in 180 s and 15,872 in 619 s in
  chunks of 32, and in 64, 139 and 314 s in chunks of 128. peacebell read 4,096 tokens in
  65 s and 7,680 in 198 s in chunks of 32, and in 36 and 82 s in chunks of 256, where 15,872
  took 242 s. Boris-2 read its first 1,536 tokens in 5 s in chunks of 128. Generation with
  15,872 tokens in memory ran at 5.7 tokens a second for Qwen3.5 and 6.7 for peacebell. The
  runs were taken one after another while the phone warmed (its battery from 26 to 34 degrees
  Celsius), and with nothing read the same model was about a quarter slower at the end than at
  the beginning, which is why the columns do not start alike. These figures are behind the
  reading times of the model catalogue and the engine's larger chunk for a long read (128
  tokens for a model with recurrent state, which can be stopped only between chunks, and 256
  for the others).

## Changing things

### Changing a hook, or adding one

1. Run `tools/prepare-llama-src.sh` and edit the upstream files **in the copy**
   (`$PROSEPLOW_LLAMA_SRC`). The copy is left alone while the patches and added files are
   unchanged, so the edits survive rebuilds.
2. Build and test against the copy as usual.
3. `tools/make-hook-patch.sh 0001-architecture-hooks.patch` rewrites that patch as the
   difference between "upstream plus the other patches" and the copy. It also lists files that
   exist only in the copy, so a new source file is not forgotten there: move it to `llama-ext/`.
4. Run `tools/prepare-llama-src.sh --force` and the tests once more: the copy is now rebuilt
   from the patches alone, which is what everybody else will get.

### Adding an architecture

Upstream's own checklist is `docs/development/HOWTO-add-model.md` in the submodule. Here it
becomes:

1. New files under `llama-ext/`: `src/models/<arch>.cpp` and `src/models/<arch>.h` (the model
   struct; upstream's CMake compiles every `src/models/*.cpp`, so no build file changes), and
   `conversion/<arch>.py` (a `TextModel` subclass).
2. Hooks, added to `patches/0001-architecture-hooks.patch` as described above: the `LLM_ARCH_*`
   enum value (`src/llama-arch.h`), its name (`src/llama-arch.cpp`), the factory `case` and the
   RoPE type `case` (`src/llama-model.cpp`), an `#include` of the new header at the end of
   `src/models/models.h`; on the Python side the `MODEL_ARCH` value, its name and its tensor
   list (`gguf-py/gguf/constants.py`) and the class-name entry in `conversion/__init__.py`. An
   architecture that needs its own memory type or classification adds those `case`s too:
   boris2 is listed as hybrid (`llm_arch_is_hybrid`), gets both layer filters in
   `llama_model::create_memory`, is excluded from the model saver (`llama_arch_supports_save`,
   which does not know its lookup arrays) and makes the KV cache write the token id of each
   cell into a saved state (`llama_kv_cache::has_cell_ext`). shard2 adds none: its RoPE type
   `case` goes into the list of the architectures that rotate neighbouring pairs, and its
   memory follows from hyper-parameters.
3. A driver in `convert/` if the model cannot go through upstream's `convert_hf_to_gguf.py`,
   an entry for it in `CONVERTERS` of `../models/convert_models.py`, and an entry in
   `../models/manifest.json`. A checkpoint that is a pickle is loaded with `weights_only=True`
   and nothing else (see "The checkpoint is a pickle").
4. Reference data and tests: a PyTorch reference loader and generators in `tests/refgen/`, the
   committed data in `tests/data/`, the architecture's tensor map in `tests/check_gguf.py`
   (`ARCHITECTURES`), and the tests registered in `tests/CMakeLists.txt`. The F32 comparison
   with the reference is the gate for the port, the F16 comparison the gate for the file that
   ships.

Bring an architecture up in pieces that can each be compared with the reference. boris2 was
done in two: first with every token on the miss row of the n-gram table (Canon and the hybrid
memory alone, against a reference with the lookup switched off), then with the lookup. For
shard2 the pieces were upstream's: the model was first written in PyTorch in the shape
llama.cpp would compute it (the two cell stores, a mask made from cell positions, the float32
RoPE angles of ggml, upstream's rule for reusing a windowed cell) and compared with the
reference, which settled the memory's behaviour before any C++ existed.

Make it data-driven: read every dimension from metadata, and prefer a metadata key that the
converter writes over a constant in C++.

### Moving the upstream pin

`patches/README.md` is the step-by-step runbook for this, with the commands, what each
prints and when to stop and ask. What follows is the background it relies on.

1. Check out the new commit in `third_party/llama.cpp` and write its full hash into
   `patches/UPSTREAM_COMMIT`. The prepare script refuses to run while the two disagree: moving
   the pin is a deliberate change to both.
2. Run `tools/prepare-llama-src.sh`. If a patch no longer applies, the script stops and names
   it. Prepare a copy without that patch (move the patch aside), redo its few lines by hand in
   the copy, and regenerate it with `tools/make-hook-patch.sh`.
3. Rebuild from scratch (the script gives every file of a new commit a fresh modification
   time, so the build tree rebuilds everything) and run the whole test suite with the
   checkpoints present. One test is about a log line and not about behaviour: the session
   learns what a context costs from the lines in which llama.cpp reports the buffers of a
   context it created (`... KV buffer size = 48.00 MiB`, `... RS buffer size`, `... compute
   buffer size`, `... output buffer size`, read by `read_report` in `session/src/session.cpp`).
   If their form has changed, `<model>.context` fails and says so, the session no longer knows
   a cost and refuses every change of context size (it keeps working at the size it was opened
   with); bring `read_report` in line with the new form. The same test relies on a model
   loaded with `no_alloc` reporting the compute reservation of a context it does not allocate,
   and on `GGML_PAD(n_ctx, 256)` being how a context size is rounded (`k_ctx_step`). The compiler catches a renamed helper; only the tests catch a helper
   whose behaviour changed. The parts of upstream that our code relies on are the graph helpers
   used in `llama-ext/src/models/` (`build_norm`, `build_qkv`, `build_rope`, `build_attn`,
   `build_ffn`), the SentencePiece-BPE and the GPT-2 BPE paths of `llama_tokenize`,
   `llama_memory_seq_rm`, and the conversion base class in `conversion/base.py`. boris2 relies
   on more, none of it visible to the compiler: the hybrid memory and its refusal to cut a
   recurrent state (`llama_memory_hybrid`, `llama_memory_recurrent::seq_rm`), the recurrent row
   whose width comes from `n_sconv_channels` and `n_shortconv_l_cache`
   (`llama_hparams::n_embd_r`), `build_inp_mem_hybrid` and
   `llm_build_delta_net_base::build_conv_state`, the kernel layout of `ggml_ssm_conv`, the
   token id kept in every KV cell and `get_prev_tokens`, the partial sequence state
   (`LLAMA_STATE_SEQ_FLAGS_PARTIAL_ONLY`), and the tensor names `per_layer_token_embd`,
   `per_layer_model_proj` and `shortconv.conv` with what upstream ties to them (operation,
   float type at conversion). shard2 relies on upstream for everything it computes: the
   interleaved sliding-window cache and the rule that makes `create_memory` choose it
   (`hparams.swa_type`), the meaning of `LLAMA_SWA_TYPE_STANDARD` (`llama_hparams::is_masked_swa`),
   the two per-layer arrays and `get_rope_params` honouring the RoPE one, the RoPE base of a
   windowed layer (`rope_freq_base_train_swa`), `build_attn_inp_kv_iswa`, the tensor names
   `attn_q_norm`, `attn_k_norm`, `post_attention_norm` and `post_ffw_norm`, the promise that
   the windowed store holds every position from `llama_memory_seq_pos_min` on, what a partial
   and a full sequence state contain for that store, and the default of `swa_full`; its tests
   pin every one of them. For Qwen it is upstream's whole converter and graph; the tests
   there pin the result. One test also runs upstream's
   `gguf-py/gguf/scripts/gguf_new_metadata.py`. The two other patches rely on the shape of the
   code they change: the branch for half-float arithmetic in `llamafile_sgemm` and the guard
   that removes tinyBLAS in `ggml-cpu.c` (0002, which no host test exercises: it is ARM code,
   checked by the tests on the phone), and `llama_mmap::impl`, `llama_file` and the branch of
   `llama_model_loader` that loads from a `FILE *` (0003, checked by the session tests). The
   session itself relies on `llama_process` taking an interrupted micro-batch out of an
   attention-only memory again, on the abort callback being called on the calling thread, on
   `ggml_threadpool_new` pinning the creating thread to the last CPU of a strict mask, and on
   the CPU backend pausing the pool it used before when it is given another; the session
   tests cover each of these. The export and import of a memory rely on two internal
   headers, which no other code of this project includes: `llama_io_write_i` (`write`,
   `write_tensor`, `n_bytes`) and `llama_io_read_i` (`read`, `read_tensor`, `discard`,
   `n_bytes`) in `src/llama-io.h`, and the virtual functions `state_write` and `state_read`
   of `llama_memory_i` in `src/llama-memory.h`, called with a sequence id and
   `LLAMA_STATE_SEQ_FLAGS_NONE`. They are all that `llama_context::state_seq_write_data` and
   `::state_seq_read_data` do; the public calls put a mark and the sequence id in front,
   eight bytes that an image does not carry. A member added to either interface stops
   `session/src/state_stream.cpp` compiling, and `session/CMakeLists.txt` stops the
   configuration when either header is gone. What the compiler cannot see, `<model>.memory`
   does: it compares the state in an image with the block of the public call byte for byte,
   and a memory that was read back with one that never left, bit for bit. That test relies
   on a state beginning with the number of streams and the number of cells of a key-value
   cache (it sets the second beyond any context, to see llama.cpp refuse a state), and on
   `llama_init_from_model` being the one function a session creates a context with (the
   linker routes it through the test). Should the interfaces ever go, what remains is the
   one block that a change of context size uses, for memories that fit a budget beside the
   context: `llama_state_seq_get_data_ext` handed to the sink in pieces.
4. Convert the models again: their records name the converter sources, so every GGUF is
   rebuilt, and the determinism test tells whether the bytes changed.

### Using this build from another build system

`CMakeLists.txt` works as the native project of another tool: pass
`-DPROSEPLOW_BUILD_TESTS=OFF`, and the patched copy is written into that build's own tree unless
`PROSEPLOW_LLAMA_SRC` says otherwise. The targets are `llama`, `proseplow_native` and
`proseplow_session`. It builds llama.cpp as static libraries tuned to the build machine, which
is right for a host and wrong for a phone; the Android build therefore has a CMake project of
its own (`../llama/src/main/cpp/CMakeLists.txt`) that adds the prepared copy, `lib/` and
`session/` directly, with the options an APK needs. A change to how `lib/` or `session/` are
built belongs in their own `CMakeLists.txt`, which both builds use.
