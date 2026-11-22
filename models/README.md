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
| `name` | Written into the GGUF as `general.name` |
| `repository`, `revision` | The model repository on the Hugging Face hub and the exact commit of it |
| `converter` | Which converter turns the checkpoint into a GGUF; the names are the keys of `CONVERTERS` in `convert_models.py` |
| `ftype` | The float type of the file that ships. Always `f16`: models ship at 16-bit precision, and no other quantisation is used |
| `output` | The file name of the result, under `<output directory>/<id>/` |
| `output_sha256` | The SHA-256 the converted file must have. Conversion is deterministic, so anyone who converts the pinned files with the pinned tools gets these bytes; a conversion that produces others fails. Leave it out while adding a model, then record the hash the script prints |
| `files` | Every file the converter reads, with its SHA-256. A conversion is refused unless all of them match |
| `reference_files` | Files that only the validation tests read (the PyTorch reference code that ships with a checkpoint). They are verified before the tests execute them |

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
3. Choose the converter. A model of a family that already has one (`peacebell`) needs nothing
   else: the converters are driven by `config.json`. A model that upstream llama.cpp already
   loads needs a new entry in `CONVERTERS` that runs the patched copy's `convert_hf_to_gguf.py`.
   A new architecture needs the port first, see "Adding an architecture" in `native/README.md`.
4. Set `ftype` to `f16` and name the `output`.
5. Convert, record the printed SHA-256 as `output_sha256`, then add the model's tests: reference data generated from its PyTorch reference
   (`native/tests/refgen/`), and its entries in `native/tests/CMakeLists.txt`. A model is not
   ready to bundle until its F32 file reproduces the reference and its F16 file is within the
   16-bit limits.
6. Tell the app: the model catalogue and the prompt format of the model live in the Kotlin
   `core` module, and the catalogue must agree with what the GGUF says about itself (for
   peacebell, `peacebell.chat.format`).

## Removing a model

Delete its entry from `manifest.json`, its tests from `native/tests/CMakeLists.txt`, its
committed reference data from `native/tests/data/`, and its catalogue entry in the app.
Converted files are not tracked: remove `<output directory>/<id>/` by hand. If it was the last
model of its architecture, the architecture's files in `native/llama-ext/` and its hook lines
can go too, but nothing breaks if they stay.

## Placeholders

Two entries are `planned` and are skipped by the script:

- `boris-2-instruct` needs an architecture that is not ported yet. Its `tokenizer.json` is
  already read by one test, as a stand-in for the byte-level BPE tokenizer of the peacebell
  family; that is the only reason the entry carries a revision and one hash.
- `qwen3.5-0.8b` is loaded by upstream llama.cpp as it is; what is missing is the converter
  entry, the pinned revision and the hashes.
