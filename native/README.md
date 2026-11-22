# Native code

Everything below the JVM: llama.cpp with the few changes this project carries, our own native
library on top of it, the model converters, and the host tests that validate all of it against
the PyTorch reference models. Nothing here needs Android: the code is built and tested on an
ordinary Linux machine, and the Android build will compile the same sources.

Tested on Ubuntu 24.04 (x86_64). Windows and macOS are untested: the scripts need bash 4.4 or
newer, git, tar and a C++17 compiler, so WSL or a recent bash should work, but nobody has tried.

## What is where

| Path | What it holds |
| --- | --- |
| `../third_party/llama.cpp` | Upstream llama.cpp as a git submodule, pinned. **Never edited**, see below |
| `patches/` | The changes to upstream files, as patches, and `UPSTREAM_COMMIT`, the commit they apply to |
| `llama-ext/` | Files we add to llama.cpp, at the path they get inside it: per architecture `src/models/<arch>.cpp`, `src/models/<arch>.h` and `conversion/<arch>.py`, for `peacebell` and `boris2` |
| `tools/prepare-llama-src.sh` | Writes the patched copy of llama.cpp that everything compiles and imports |
| `tools/make-hook-patch.sh` | The reverse: turns edits made in a patched copy back into a patch |
| `tools/env.sh`, `tools/host-check.sh` | The environment for the commands below, and all of them in one script |
| `convert/` | Converter drivers (checkpoint directory to GGUF): one per ported architecture, and `convert_upstream.py` for models that upstream llama.cpp already supports |
| `lib/` | `proseplow_native`, our library above libllama, with a C interface (`lib/include/proseplow_native.h`) |
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
  demand: an enum value, a name, a `case`. Today that is one patch,
  `patches/0001-architecture-hooks.patch`: 46 added lines in seven files (17 in C++, 29 in
  Python), two of which replace an existing line. peacebell accounts for 7 and 13 of them,
  boris2 for 10 and 16: it also needs the hybrid memory with both halves in every layer, a
  refusal by the model saver, and the token id of each KV cell kept in a saved state.
  All architecture hooks live in this one patch because they sit on neighbouring lines of the
  same tables; an unrelated change (a kernel, a loader fix) gets a patch of its own.
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
everything they depend on. Create a virtualenv wherever you keep such things and install into it
(about 1.3 GB, most of it PyTorch; nothing here uses a GPU, and the CPU-only wheel is selected
in the file):

```sh
python3 -m venv /path/to/venv
/path/to/venv/bin/pip install -r native/requirements.txt
```

No other C or C++ library is needed, and nothing is installed system-wide.

## Build and test

Three locations are yours to choose; they are passed in environment variables and nothing is
ever written next to the sources:

| Variable | Meaning |
| --- | --- |
| `PROSEPLOW_VENV` | The virtualenv from above. Optional when it is already activated |
| `PROSEPLOW_BUILD` | A scratch directory for everything generated: the patched copy, the build tree, converted models, on-demand reference data. About 5 GB after a full test run (4.7 GB of it the converted models), 7 GB while it runs |
| `PROSEPLOW_MODEL_SRC` | The directory that holds the downloaded checkpoints (see `../models/README.md`). Optional: without it the code still builds, and the tests that need weights are skipped |

Optional: `PROSEPLOW_JOBS` (parallel compile jobs, default all processors), `PROSEPLOW_THREADS`
(inference threads in the tests, default 4), `PROSEPLOW_MODEL_OUT` (where converted models go,
default `$PROSEPLOW_BUILD/models`), `PROSEPLOW_LLAMA_SRC` (the patched copy, default
`$PROSEPLOW_BUILD/llama-src`).

Everything in one command, from the repository root:

```sh
git submodule update --init third_party/llama.cpp     # once, after cloning

export PROSEPLOW_VENV=/path/to/venv
export PROSEPLOW_BUILD=/path/to/scratch
export PROSEPLOW_MODEL_SRC=/path/to/checkpoints
native/tools/host-check.sh
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
minute for all seven), the test suite about eight minutes with 6 threads. The build is CPU
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
| `<model>.gguf-f32` | Every tensor of the F32 file equals the checkpoint tensor byte for byte; values carried as metadata equal the stored ones exactly (peacebell: the ReZero scalars; boris2: the four n-gram lookup arrays, with their integer types); hyper-parameters equal `config.json`; vocabulary, token types and the recorded normaliser equal the tokenizer file. boris2: the 31 Canon kernels equal the documented transform of the stored ones |
| `<model>.gguf-f16` | Every matrix is exactly the round-to-nearest half-precision cast of the checkpoint; the tensors the architecture keeps in float32 are still float32 and unchanged (norm weights; boris2: also the Canon kernels and the n-gram projection); no infinities; under 0.5% half-precision subnormals in every tensor; metadata identical to the F32 file |
| `<model>.tokenizer` | peacebell: the front-end reproduces the committed token vectors: 0 mismatches in exact mode and in safe mode, and safe mode never produces a chat marker from text; plus the calling contract (buffer sizes, malformed UTF-8, null arguments). boris2: llama.cpp's own tokenizer reproduces the committed vectors of the `tokenizers` library with special tokens parsed and as plain text, where the end-of-text token never occurs; `pp_chatml_text_tokenize` reproduces 118 conversations rendered by transformers' template renderer, among them every character the template's trim removes; every reported message end is the token count of the shorter conversation; the calling contract |
| `<model>.tokenizer-live` | The same against the reference library itself (SentencePiece, or `tokenizers` and transformers), on a larger corpus generated at test time; for SentencePiece it puts every user-defined symbol of the tokenizer file in every awkward position |
| `<model>.logits-f32` | F32 file, F32 KV cache, no flash attention: logits within 5e-3 of the PyTorch reference for one batch, token by token, uneven batches, micro-batch sizes 1, 2, 7, 32 and 512, and after a rewind; all of those within 5e-3 of each other over the whole vocabulary; 20 rewinds at arbitrary positions equal to a fresh evaluation, with a control that the wrong prefix does change the logits; greedy replies identical to the reference. peacebell: a suppressed token is never sampled, even against a +1e9 bias. boris2: see below |
| `<model>.logits-f16` | F16 file as it ships (F16 KV cache, default attention path), same ways of feeding the tokens: mean KL at most 1e-5 (boris2: 5e-5) and max KL at most 5e-3 against the fp32 reference, top-1 agreement at least 99.5%. Greedy divergence, if any, is reported |
| `peacebell-bpe-random` | All of the peacebell checks for a seeded random checkpoint with the shape of the family's byte-level BPE member, which is not trained yet: the converter's other tokenizer branch, llama.cpp's own tokenizer against the `tokenizers` library, the plain-text ChatML encoder, and the sampler mask on the training pad token |
| `boris-2-instruct.chat-f32`, `.chat-f16` | A session of eight chat requests served from one context whose memory is kept between them (a turn appended, the same request again, an earlier message changed, another beginning): the encoder gives the reference's prompt tokens; every rewind goes through a checkpoint (the cut is refused before the restore and succeeds after it) or starts over when none fits; each reply, ended by the stop string, equals the reply the reference generated from scratch, as tokens and as text (F16: reported) |
| `boris-2-instruct.no-ngram-lookup` | The F32 file with its four lookup arrays removed, against the reference with the lookup switched off: all the checks of `logits-f32`. It separates Canon and the hybrid memory from the n-gram input |
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

For orientation, the values measured when this was written (6 threads, a desktop CPU with AVX2):

| | peacebell-v1-291M | peacebell-v1-148M | Boris-2-Instruct |
| --- | --- | --- | --- |
| F32, largest logit error against the reference (limit 5e-3) | 8.7e-5 | 3.1e-5 | 1.7e-4 |
| F16, mean KL / max KL (limits 1e-5, boris2 5e-5 / 5e-3) | 9.7e-7 / 3.8e-5 | 6.3e-7 / 9.8e-6 | 8.6e-6 / 4.9e-4 |
| F16, top-1 agreement; largest logit error | 100%; 0.034 | 100%; 0.020 | 100%; 0.27 |
| Greedy replies identical to the reference, F32 / F16 | 5 of 5 / 5 of 5 | 5 of 5 / 5 of 5 | 5 of 5 / 5 of 5, and 8 of 8 chat replies |
| Generation, F32 / F16, tokens per second | 38 / 77 | 74 / 139 | 80 / 150 |
| Prompt in one batch, F16, tokens per second | 560 | 1040 | 1100 |

Boris-2 further: 0 tokenizer mismatches in 7,642 committed and 25,045 live texts and in 118
conversations; a rewind through a checkpoint within 1.8e-4 of a fresh evaluation, the same
rewind with stale Canon history off by 4.3 at least; a checkpoint is 381,700 bytes. The stock
Qwen3.5-0.8B file generates about 30 tokens per second, and its checkpoint is 20,201,932 bytes.

### Reference data

The reference is always the PyTorch model, fp32 on the CPU.

- Committed in `tests/data/` (3.5 MB): the token vectors `peacebell-v1-tokens.bin` (7,642 texts
  and 22 conversations with the ids of the SentencePiece library, in both modes) and
  `boris-2-instruct-tokens.bin` (the same texts and 118 conversations with the ids of the
  `tokenizers` library); per checkpoint, `<model>-logits.bin` (five conversations with the
  reference's greedy reply; the top 20 logits at every position and the whole logit vector at
  the last prompt position); and `boris-2-instruct-chat.bin` (the chat session with the
  reference's replies). These pin the expected behaviour independently of what is installed.
- Generated on demand into the build tree, because they are too large to commit: the logits of
  the whole vocabulary at every position (the KL test needs them), the live token vectors, the
  random checkpoint, and the boris2 reference without the n-gram lookup. A regenerated
  reference is first compared with the committed one, so a PyTorch that computes something
  else is noticed.

The generators are in `tests/refgen/`; the three binary formats are described in
`tests/refgen/formats.py`. To regenerate the committed files after a checkpoint or tokenizer
changes (with exactly the package versions of `requirements.txt`):

```sh
python3 native/tests/refgen/gen_logit_ref.py --model-dir /path/to/checkpoint --out native/tests/data/<model>-logits.bin
python3 native/tests/refgen/gen_token_vectors.py --model-dir /path/to/peacebell --out native/tests/data/peacebell-v1-tokens.bin
python3 native/tests/refgen/gen_token_vectors.py --model-dir /path/to/boris-2 --out native/tests/data/boris-2-instruct-tokens.bin
python3 native/tests/refgen/gen_chat_ref.py --model-dir /path/to/boris-2 --out native/tests/data/boris-2-instruct-chat.bin
```

The Boris-2 reference has no cache, so its replies are made by a forward pass over the whole
prefix for every token: generating the three files takes about a minute with 6 threads.

The committed token vectors contain only text written for this project and seeded random text,
with the symbols every tokenizer of the family has (years, month names, the URL scheme, the chat
markers). Symbols specific to one tokenizer file are covered by the live test, which reads them
from the file.

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

The generation session will be added to this library, and the Android bridge will be a thin
layer over it. Keep host-only code out of `lib/`.

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
   cell into a saved state (`llama_kv_cache::has_cell_ext`).
3. A driver in `convert/` if the model cannot go through upstream's `convert_hf_to_gguf.py`,
   and an entry in `../models/manifest.json`.
4. Reference data and tests: a PyTorch reference loader and generators in `tests/refgen/`, the
   committed data in `tests/data/`, the architecture's tensor map in `tests/check_gguf.py`
   (`ARCHITECTURES`), and the tests registered in `tests/CMakeLists.txt`. The F32 comparison
   with the reference is the gate for the port, the F16 comparison the gate for the file that
   ships.

Bring an architecture up in pieces that can each be compared with the reference. boris2 was
done in two: first with every token on the miss row of the n-gram table (Canon and the hybrid
memory alone, against a reference with the lookup switched off), then with the lookup.

Make it data-driven: read every dimension from metadata, and prefer a metadata key that the
converter writes over a constant in C++.

### Moving the upstream pin

1. Check out the new commit in `third_party/llama.cpp` and write its full hash into
   `patches/UPSTREAM_COMMIT`. The prepare script refuses to run while the two disagree: moving
   the pin is a deliberate change to both.
2. Run `tools/prepare-llama-src.sh`. If a patch no longer applies, the script stops and names
   it. Prepare a copy without that patch (move the patch aside), redo its few lines by hand in
   the copy, and regenerate it with `tools/make-hook-patch.sh`.
3. Rebuild from scratch (the script gives every file of a new commit a fresh modification
   time, so the build tree rebuilds everything) and run the whole test suite with the
   checkpoints present. The compiler catches a renamed helper; only the tests catch a helper
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
   float type at conversion). For Qwen it is upstream's whole converter and graph; the tests
   there pin the result. One test also runs upstream's `gguf-py/gguf/scripts/gguf_new_metadata.py`.
4. Convert the models again: their records name the converter sources, so every GGUF is
   rebuilt, and the determinism test tells whether the bytes changed.

### Using this build from another build system

`CMakeLists.txt` works as the native project of another tool (the Android build will use it
that way): pass `-DPROSEPLOW_BUILD_TESTS=OFF`, and the patched copy is written into that build's
own tree unless `PROSEPLOW_LLAMA_SRC` says otherwise. The targets are `llama` and
`proseplow_native`.
