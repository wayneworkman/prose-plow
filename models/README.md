# Models

The app bundles its models: they are converted to GGUF at build time and packaged with it,
never downloaded by it. This directory says which models, from exactly which files, and holds
the script that converts them.

| File | What it is |
| --- | --- |
| `manifest.json` | One entry per model: where it comes from, the revision and SHA-256 of every source file, the converter, the float type and the output file name |
| `convert_models.py` | Verifies the source files against the manifest and converts them |

Model weights are never committed: `*.gguf` and `*.safetensors` are git-ignored, and both the
checkpoints and the converted files live outside the source directories.

A checkpoint is data from somebody else. Keep each in a directory of its own, never run
anything from it, and note which kind of file the weights are: safetensors is data by
construction, while a PyTorch `.pt` file is a pickle that can run code when it is loaded
carelessly (see "shard-2" below for the one model here that has one).

## The manifest

```json
{
  "id": "peacebell-v1-291m",
  "status": "ready",
  "purpose": "bundled",
  "name": "peacebell-v1-291M",
  "repository": "<owner>/<name>",
  "revision": "<commit of the model repository>",
  "converter": "peacebell",
  "ftype": "f16",
  "output": "peacebell-v1-291m-f16.gguf",
  "output_sha256": "<sha256 of the converted file>",
  "files": { "config.json": "<sha256>", "model.safetensors": "<sha256>", "sentencepiece.model": "<sha256>" },
  "reference_files": { "modeling_gpt.py": "<sha256>" }
}
```

| Field | Meaning |
| --- | --- |
| `id` | Our name for the model: the directory and file names are derived from it. Lower case, no spaces |
| `status` | `ready`: converted by the script. `planned`: a placeholder for a model whose port or wiring does not exist yet; the script skips it and the `note` says what is missing |
| `purpose` | `bundled`: goes into the app. `validation`: converted and tested only |
| `name` | Written into the GGUF as `general.name`. For the `upstream` converter it is the name of the directory that converter is shown, from which it derives `general.name` (`Qwen3.5-0.8B` becomes "Qwen3.5 0.8B"), `general.basename` and `general.size_label` |
| `repository`, `revision` | The model repository on the Hugging Face hub and the exact commit of it |
| `converter` | Which converter turns the checkpoint into a GGUF; the names are the keys of `CONVERTERS` in `convert_models.py`: `peacebell`, `boris2` and `shard2` for the architectures this project ported, `upstream` for a model that upstream llama.cpp supports as it is |
| `converter_args` | Only with `upstream`: arguments passed through to upstream's `convert_hf_to_gguf.py`, for example `["--no-mtp"]` |
| `ftype` | The float type of the file that ships. Always `f16`: models ship at 16-bit precision, and no other quantisation is used |
| `output` | The file name of the result, under `<output directory>/<id>/` |
| `output_sha256` | The SHA-256 the converted file must have. Conversion is deterministic, so anyone who converts the pinned files with the pinned tools gets these bytes; a conversion that produces others fails. Leave it out while adding a model, then record the hash the script prints |
| `files` | Every file the converter reads, with its SHA-256. A conversion is refused unless all of them match. With `upstream` these are also the only files the converter gets to see (see "The models" below) |
| `reference_files` | Files that only the validation tests read (the PyTorch reference code that ships with a checkpoint). They are verified before the tests execute them. For `shard-2` the file is never executed: the tests read it as text, to check that their own copy of its architecture is still the same |

## Getting the checkpoints

Nothing in this repository downloads a model. Fetch each `ready` entry yourself, at the pinned
revision, into one directory of your choice. Either layout works:

- `<directory>/<id>/` with the files directly inside, for example with the Hugging Face CLI
  (installed with `native/requirements.txt`), taking repository and revision from the manifest:

  ```sh
  hf download <repository> --revision <revision> --local-dir /path/to/checkpoints/<id>
  ```

- or a Hugging Face hub cache, where the same files are at
  `<directory>/models--<owner>--<name>/snapshots/<revision>/`.

How the files got there does not matter: the hashes decide whether they are the right ones.

## Converting

With the environment of `native/README.md` (`PROSEPLOW_VENV`, `PROSEPLOW_BUILD`, and
`. native/tools/env.sh`):

```sh
export PROSEPLOW_MODEL_SRC=/path/to/checkpoints
native/tools/prepare-llama-src.sh            # the converters live in the patched llama.cpp copy
python3 models/convert_models.py             # every ready model, as it ships (F16)
python3 models/convert_models.py --also-f32  # also the F32 files the validation tests use
python3 models/convert_models.py --list      # what the manifest contains
```

`--also-f32` writes `<id>-f32.gguf` for the models whose architecture this project ported: that
file is compared with the stored weights and with the PyTorch reference, and is never shipped.
A model that upstream supports as it is has nothing of ours to validate and gets no F32 file.

The results are `$PROSEPLOW_MODEL_OUT/<id>/<output>` (default `$PROSEPLOW_BUILD/models`), each
with a small `<output>.json` next to it that records the source hashes, the converter sources
and the SHA-256 of the result. A model whose record still matches is not converted again.
`--src`, `--out` and `--llama-src` replace the environment variables; naming ids converts only
those; `--available` skips models whose checkpoint is not on the machine instead of failing.

Conversion is deterministic: the same source files and the same converter give the same bytes
on every run and on every machine. `ctest` checks the first for each model (see
`native/README.md`); the `output_sha256` of the manifest enforces the second. When a change to a
converter or to the llama.cpp pin changes the bytes on purpose, update `output_sha256` in the
same change, so the new hash is reviewed with its cause.

## Adding a model

1. Download it and decide its `id`.
2. Add an entry to `manifest.json` with the repository, the revision you downloaded, and the
   SHA-256 (`sha256sum`) of every file the converter reads. List under `reference_files` what
   only the tests read.
3. Choose the converter. A model of an architecture that already has one (`peacebell`,
   `boris2`, `shard2`) needs nothing else: the converters are driven by `config.json`. A model that
   upstream llama.cpp already loads uses `upstream`: list under `files` everything upstream's
   converter reads for it (the weights and their index, `config.json`, the tokenizer files, the
   chat template and `README.md`, whose front matter becomes `general.*` metadata), and put any
   converter option into `converter_args`. A file that is not listed does not exist for the
   converter, so a conversion that needs one fails instead of depending on it silently.
   A new architecture needs the port first, see "Adding an architecture" in `native/README.md`.
4. Set `ftype` to `f16` and name the `output`.
5. Convert, record the printed SHA-256 as `output_sha256`, then add the model's tests in
   `native/tests/CMakeLists.txt`. For a ported architecture that means reference data generated
   from its PyTorch reference (`native/tests/refgen/`): the model is not ready to bundle until
   its F32 file reproduces the reference and its F16 file is within the 16-bit limits. For a
   stock architecture it means the determinism test and a test like
   `native/tests/test_qwen.cpp`, which pins what the app's prompt format assumes about the file.
6. Tell the app: the model catalogue and the prompt format of the model live in the Kotlin
   `core` module, and the catalogue must agree with what the GGUF says about itself (for the
   ported architectures, `<architecture>.chat.format`).

## Removing a model

Delete its entry from `manifest.json`, its tests from `native/tests/CMakeLists.txt`, its
committed reference data from `native/tests/data/`, and its catalogue entry in the app.
Converted files are not tracked: remove `<output directory>/<id>/` by hand. If it was the last
model of its architecture, the architecture's files in `native/llama-ext/` and its hook lines
can go too, but nothing breaks if they stay.

## The models

| `id` | Architecture | Converter | Shipping file | What an application must know |
| --- | --- | --- | --- | --- |
| `peacebell-v1-291m`, `peacebell-v1-148m` | `peacebell` (ported) | `peacebell` | 583,680,096 and 297,831,072 bytes | SentencePiece tokenizer through the front-end of our native library; the chat markers are tokens |
| `boris-2-instruct` | `boris2` (ported) | `boris2` | 291,853,184 bytes | ChatML is plain text and a reply ends on a stop string; the memory can only be rewound through checkpoints. Both are described under "The boris2 architecture" in `native/README.md` |
| `shard-2` | `shard2` (ported) | `shard2` | 99,048,160 bytes | It continues text and has no chat format; one of its tokens deletes the token before it, which is for the application to show; its memory can be cut back freely until a checkpoint is restored. See below, and "The shard2 architecture" in `native/README.md` |
| `qwen3.5-0.8b` | `qwen35` (upstream) | `upstream` | 1,516,742,752 bytes | See below |

### boris-2-instruct

The four files under `files` are all the converter reads. Its `tokenizer.json` has a second
use: it stands in for the byte-level BPE tokenizer of the peacebell family in the
random-weights test.

In the 16-bit file the matrices and the n-gram table are F16; the norm weights, the Canon
kernels and the n-gram projection are F32; the n-gram lookup keys and rows are integer metadata
arrays (2.8 MB). The F32 file equals the checkpoint byte for byte except for the 31 Canon
kernels, which are stored in the order and with the identity term the C++ convolution expects.

### shard-2

Glint-Research/Shard-2, 49.4 M parameters, MIT. The converter reads three files:
`config.json`, `tokenizer.json` and the weights, `Shard-2.pt`.

**The weights are a pickle.** `Shard-2.pt` is a PyTorch checkpoint, and a pickle can run code
when it is loaded. The converter and the tests load it with
`torch.load(path, map_location="cpu", weights_only=True)` and in no other way: that unpickler
constructs tensors and plain containers and refuses everything else. Never load it with
`weights_only=False`, and never run the `infer.py` that the repository ships beside it, whose
loader does exactly that. `infer.py` is listed under `reference_files` because the tests read
it as text: their reference model is a copy of its architecture, and the copy is compared with
the pinned script character for character. The pinned checkpoint loads under the restriction
as it is; its pickle refers to three library names (an ordered dict, a float storage and the
tensor rebuild function) and holds the state dict, a step count, a loss and a configuration of
plain values.

In the 16-bit file the 81 matrices are F16 and the 121 norm weights F32. The F32 file equals
the checkpoint byte for byte in every tensor it carries; it carries all but one,
`indexer.gate`, a single number that the model's forward pass never reads.

What the app's side relies on, and the tests check:

- **No chat format** (`shard2.chat.format = completion`): the prompt is the text as it is, and
  nothing is added at either end. Tokenizer `gpt2` with pre-tokenizer `gpt-2`; 4,097 tokens.
- **The deletion token** `<bs>` is id 4096 (`shard2.backspace_token_id`), the one token beyond
  the tokenizer file's 4,096. To the model it is a token like any other and is fed back like
  any other; removing the token before it from what a reader sees is the application's work.
  With the reference's settings (temperature 0.9, the 100 most likely tokens) it was 6.3% of
  4,000 sampled tokens here; the model card reports 5.4% for its own prompts.
- **Four tokens are not text**: `<pad>` (0), `<bos>` (1), `<eos>` (2) and `<bs>`. They are
  control tokens: a message that spells one of them stays plain characters, and none of them
  has a piece when tokens are turned into text.
- **Id 2 ends a text.** By its behaviour it is the only special token the model was trained
  with, the boundary between two texts: `tokenizer.ggml.eos_token_id` and `bos_token_id` are
  both 2 (the tokenizer calls it `<eos>`, `config.json` calls it the beginning marker). In
  those 4,000 sampled tokens it came once. Ids 0 and 1 are rows that training evidently never
  used (the two are the same vector to three decimals); they were never sampled. The
  reference itself stops on nothing: it writes a fixed number of tokens.
- **The context** is 8,192 tokens and the model was trained on 4,096
  (`shard2.trained_sequence_length`). The port follows the reference over all 8,192, but the
  reference itself predicts text slightly worse with more than about 5,000 tokens in front of
  it than with a context that stays inside the trained length, so 4,096 is the size to give it.
- **The memory**: attention only, so a cancelled evaluation is taken back and the end of the
  memory can be removed, at any length; but a checkpoint of this model is the window of its
  local layers (8.4 MB), and after one was restored only cuts that this window covers are
  right. The session library refuses the others. Key-value memory for 4,096 tokens: 168 MB.

### qwen3.5-0.8b

Converted by upstream's own `convert_hf_to_gguf.py`, taken from the patched copy so that one
tree converts every model. The file is

- **text only**: upstream's converter leaves the vision tower of this multimodal checkpoint out
  unless it is asked for a projector file;
- **without the multi-token-prediction head** (`--no-mtp`): 40.9 MB that only speculative
  decoding would use;
- **F16**, with the 133 tensors upstream keeps in float32 whatever the type (norm weights and
  the tensors of the recurrent layers).

Upstream's converter is not a function of the weights alone, and
`native/convert/convert_upstream.py` exists to make it one. It derives `general.name`,
`general.basename` and `general.size_label` from the name of the directory it is given, so the
same checkpoint converts to different bytes from a hub cache and from a directory called after
the model; it reads whatever it finds next to the weights; and it may consult the user's
Hugging Face cache. The driver therefore shows it a staging directory called after the entry's
`name` that links exactly the files under `files`, with an empty cache. Seven files are read:
`config.json`, the weights and their index, `tokenizer.json`, `tokenizer_config.json`,
`chat_template.jinja` and `README.md` (its front matter becomes the licence, tag and base-model
metadata). `vocab.json` and `merges.txt` are not read with the pinned package versions.

The pinned `output_sha256` is the hash of the file that the unpatched pinned llama.cpp produces
from a directory named `Qwen3.5-0.8B` with `--outtype f16 --no-mtp`: our patches do not change
upstream's converter for a stock architecture.

What the app's prompt format relies on, and `native/tests/test_qwen.cpp` checks:

- Tokenizer `gpt2` with pre-tokenizer `qwen35`; 248,320 tokens; no token is added at either
  end. The file has no beginning-of-text token.
- `<|im_end|>` (248046) ends a turn and is the end-of-sequence token; `<|endoftext|>` (248044)
  is the padding token and ends a generation too; `<|im_start|>` is 248045, `<think>` and
  `</think>` are 248068 and 248069.
- **Inside a message the two kinds of marker behave differently.** The ChatML markers are
  control tokens: tokenised with `parse_special = false` they are plain characters, so a
  message cannot close its own turn. `<think>`, `</think>`, `<tool_call>`, `</tool_call>`,
  `<tool_response>` and `</tool_response>` are user-defined tokens, which llama.cpp, like the
  reference tokenizer, matches in any text: a message that contains `<think>` does produce the
  marker token.
- The model's context length is 262,144 tokens: always set the context size, a context of
  the default size would allocate gigabytes.
- It is a hybrid architecture as well (18 recurrent layers next to 6 attention layers), so the
  rule of Boris-2 holds here too: `llama_memory_seq_rm` into the sequence is refused until
  the recurrent state of the cut position is restored from a checkpoint
  (`LLAMA_STATE_SEQ_FLAGS_PARTIAL_ONLY`), and a rewind through a checkpoint reproduces the
  reply. The difference is the price: a checkpoint of this model is **20,201,932 bytes**,
  against 381,700 for Boris-2, because its recurrent layers keep a 128 x 2,048 state each.
