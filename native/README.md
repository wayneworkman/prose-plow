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
| `llama-ext/` | Files we add to llama.cpp, at the path they get inside it (`src/models/peacebell.cpp`, `conversion/peacebell.py`) |
| `tools/prepare-llama-src.sh` | Writes the patched copy of llama.cpp that everything compiles and imports |
| `tools/make-hook-patch.sh` | The reverse: turns edits made in a patched copy back into a patch |
| `tools/env.sh`, `tools/host-check.sh` | The environment for the commands below, and all of them in one script |
| `convert/` | Converter drivers (checkpoint directory to GGUF) |
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
  `patches/0001-architecture-hooks.patch`: 20 added lines in six files (7 in C++, 13 in Python).
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
| `PROSEPLOW_BUILD` | A scratch directory for everything generated: the patched copy, the build tree, converted models, on-demand reference data. About 3 GB after a full test run, 5 GB while it runs |
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

A clean build takes about 40 seconds with 6 jobs, the conversions a few seconds per file, the
test suite about four minutes with 6 threads. The build is CPU only, static, without OpenMP and
without ccache, and by default tuned to the build machine (`-march=native`; pass
`-DGGML_NATIVE=OFF` to cmake for a portable binary). The explicit conversion step is optional:
the tests convert what they need and reuse it while it is current.

`ctest -R tokenizer`, `ctest -R 291m`, `ctest -R logits-f32` and so on select tests by name;
`ctest -V` shows each test's measurements even when it passes.

## What the tests check

Each test prints one line per check with the measured value and its limit. A test whose
checkpoint is not on the machine prints `SKIPPED:` with the checkpoint it looked for and is
reported by ctest as skipped, never as failed.

| Test | What must hold |
| --- | --- |
| `<model>.convert-determinism` | Two fresh conversions and the converted file have the same SHA-256, for F32 and F16 |
| `<model>.gguf-f32` | Every tensor of the F32 file equals the checkpoint tensor byte for byte; the ReZero scalars equal the stored ones bit for bit; hyper-parameters equal `config.json`; vocabulary, token types and the recorded normaliser equal the tokenizer file |
| `<model>.gguf-f16` | Every matrix is exactly the round-to-nearest half-precision cast of the checkpoint; norm weights and ReZero scalars are still float32; no infinities; under 0.5% half-precision subnormals in every tensor; metadata identical to the F32 file |
| `<model>.tokenizer` | The front-end reproduces the committed token vectors: 0 mismatches in exact mode and in safe mode, and safe mode never produces a chat marker from text; plus the calling contract (buffer sizes, malformed UTF-8, null arguments) |
| `<model>.tokenizer-live` | The same against the SentencePiece library itself, on a larger corpus generated at test time that puts every user-defined symbol of the tokenizer file in every awkward position |
| `<model>.logits-f32` | F32 file, F32 KV cache, no flash attention: logits within 5e-3 of the PyTorch reference for one batch, token by token, uneven batches, micro-batch sizes 1, 2, 7, 32 and 512, and after a rewind with `llama_memory_seq_rm`; all of those within 5e-3 of each other over the whole vocabulary; 20 rewinds at arbitrary positions equal to a fresh evaluation, with a control that the wrong prefix does change the logits; greedy replies identical to the reference; a suppressed token is never sampled, even against a +1e9 bias |
| `<model>.logits-f16` | F16 file as it ships (F16 KV cache, default attention path), same ways of feeding the tokens: mean KL at most 1e-5 and max KL at most 5e-3 against the fp32 reference, top-1 agreement at least 99.5%. Greedy divergence, if any, is reported |
| `peacebell-bpe-random` | All of the above for a seeded random checkpoint with the shape of the family's byte-level BPE member, which is not trained yet: the converter's other tokenizer branch, llama.cpp's own tokenizer against the `tokenizers` library, and the sampler mask on the training pad token |

For orientation, the values measured when this was written (6 threads, a desktop CPU with AVX2):

| | peacebell-v1-291M | peacebell-v1-148M |
| --- | --- | --- |
| F32, largest logit error against the reference (limit 5e-3) | 8.7e-5 | 3.1e-5 |
| F16, mean KL / max KL (limits 1e-5 / 5e-3) | 9.7e-7 / 3.8e-5 | 6.3e-7 / 9.8e-6 |
| F16, top-1 agreement; largest logit error | 100%; 0.034 | 100%; 0.020 |
| Generation, F32 / F16, tokens per second | 38 / 77 | 74 / 139 |

### Reference data

The reference is always the PyTorch model, fp32 on the CPU.

- Committed in `tests/data/` (2 MB): the token vectors `peacebell-v1-tokens.bin` (7,642 texts and
  22 conversations with the ids of the SentencePiece library, in both modes) and, per
  checkpoint, `<model>-logits.bin` (five conversations with the reference's greedy reply; the
  top 20 logits at every position and the whole logit vector at the last prompt position).
  These pin the expected behaviour independently of what is installed.
- Generated on demand into the build tree, because they are too large to commit: the logits of
  the whole vocabulary at every position (the KL test needs them), the live token vectors, and
  the random checkpoint. A regenerated reference is first compared with the committed one, so a
  PyTorch that computes something else is noticed.

The generators are in `tests/refgen/`; the two binary formats are described in
`tests/refgen/formats.py`. To regenerate the committed files after a checkpoint or tokenizer
changes (with exactly the package versions of `requirements.txt`):

```sh
python3 native/tests/refgen/gen_logit_ref.py --model-dir /path/to/checkpoint --out native/tests/data/<model>-logits.bin
python3 native/tests/refgen/gen_token_vectors.py --model-dir /path/to/checkpoint --out native/tests/data/peacebell-v1-tokens.bin
```

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
   architecture that needs its own memory type or classification adds those `case`s too.
3. A driver in `convert/` if the model cannot go through upstream's `convert_hf_to_gguf.py`,
   and an entry in `../models/manifest.json`.
4. Reference data and tests: a PyTorch reference loader and generators in `tests/refgen/`, the
   committed data in `tests/data/`, and the tests registered in `tests/CMakeLists.txt`. The F32
   comparison with the reference is the gate for the port, the F16 comparison the gate for the
   file that ships.

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
   `build_ffn`), the SentencePiece-BPE path of `llama_tokenize`, `llama_memory_seq_rm`, and the
   conversion base class in `conversion/base.py`.
4. Convert the models again: their records name the converter sources, so every GGUF is
   rebuilt, and the determinism test tells whether the bytes changed.

### Using this build from another build system

`CMakeLists.txt` works as the native project of another tool (the Android build will use it
that way): pass `-DPROSEPLOW_BUILD_TESTS=OFF`, and the patched copy is written into that build's
own tree unless `PROSEPLOW_LLAMA_SRC` says otherwise. The targets are `llama` and
`proseplow_native`.
