#!/usr/bin/env python3
"""Runs one host test: finds its files, prepares what is missing, runs the check.

    run.py --bin-dir DIR --work-dir DIR --llama-src DIR TEST [MODEL_ID]

ctest calls this for every test (see CMakeLists.txt). The files a test needs depend on the
machine, so they are located when the test runs, from the environment:

    PROSEPLOW_MODEL_SRC   directory of the downloaded checkpoints (see models/README.md)
    PROSEPLOW_MODEL_OUT   directory of the converted GGUF files (default: <work-dir>/models)
    PROSEPLOW_THREADS     inference threads (default 4)
    PROSEPLOW_PEACEBELL_PACKAGES   checkpoint directories of byte-level BPE members of the peacebell
                          family that are not published (and so not in the manifest), separated
                          by ':'; only the bpe-packages test reads it

A test whose checkpoint is not on this machine exits with 77, which ctest reports as skipped,
after saying which checkpoint it looked for. It never fails for that reason and never
downloads anything. GGUF files are converted on demand and reused while they are current, so
running ctest alone is enough; reference data that is too large to commit is generated into
the work directory the same way.

Tests:
    convert-determinism  two conversions of the same checkpoint give the same bytes
    gguf-f32             the F32 file equals the checkpoint byte for byte, metadata included
    gguf-f16             the F16 file is the rounded checkpoint; types and subnormal share
    tokenizer            the tokenizer (and chat encoder) against the committed token vectors
    tokenizer-live       the same against the reference library itself, on a larger corpus made at test time
    logits-f32           the F32 file against the committed reference logits (Shard-2: against the
                         whole logit vector at every position, generated on demand)
    logits-f16           the F16 file against reference logits at every position (KL, top-1)
    chat-f32, chat-f16   Boris-2: a chat session kept in one context against the reference's replies
    no-ngram-lookup      Boris-2: the F32 file without its n-gram lookup arrays against the reference without the lookup
    random-bpe           all of the peacebell checks for a seeded random byte-level BPE checkpoint
    bpe-packages         the same for the checkpoint directories named in PROSEPLOW_PEACEBELL_PACKAGES
    qwen-load-generate   the stock Qwen file loads, has the expected tokenizer and repeats a greedy reply
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import shutil
import subprocess
import sys

from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parent.parent
DATA = HERE / "data"
REFGEN = HERE / "refgen"
SKIP = 77

# The model code that ships inside the unpublished byte-level BPE packages is never run here: the
# reference model is built by the verified code of a published checkpoint of the family and
# loaded with the package's weights. That is only a reference for the package if the two compute
# the same thing, which somebody has to read to know. These are the versions that were read
# (SHA-256 of modeling_gpt.py); another version gets a line in the test's output saying so.
REVIEWED_PACKAGE_CODE = {"119378d383ce69f0fad141734ec5ad103f4567937c152e1c2fc7301e736ed14f"}

sys.dont_write_bytecode = True
sys.path.insert(0, str(REPO / "models"))
sys.path.insert(0, str(HERE))

import convert_models as models  # noqa: E402


def skip(reason: str):
    print(f"SKIPPED: {reason}")
    sys.exit(SKIP)


def fail(problems: list[str]) -> int:
    for p in problems:
        print(f"FAIL  {p}")
    print("FAILED" if problems else "PASSED")
    return 1 if problems else 0


class Runner:
    def __init__(self, args):
        self.bin_dir: Path = args.bin_dir
        self.work: Path = args.work_dir
        self.llama_src: Path = args.llama_src
        src = os.environ.get("PROSEPLOW_MODEL_SRC")
        self.src = Path(src) if src else None
        out = os.environ.get("PROSEPLOW_MODEL_OUT")
        self.out = Path(out) if out else self.work / "models"
        self.env = dict(os.environ, PYTHONDONTWRITEBYTECODE="1", CUDA_VISIBLE_DEVICES="", TQDM_DISABLE="1")

    # -- locating things ---------------------------------------------------------------------

    def where(self, entry: dict) -> str:
        place = f"under {self.src}" if self.src else "anywhere: PROSEPLOW_MODEL_SRC is not set"
        return f"the checkpoint {entry['repository']} (revision {entry['revision']}) was not found {place}"

    def source(self, entry: dict) -> Path:
        """The checkpoint directory, with the pinned files verified; skips when it is absent."""
        directory = models.source_dir(entry, self.src)
        if directory is None:
            skip(self.where(entry))
        if entry.get("files"):
            models.verify_files(directory, entry["files"])
        return directory

    def reference_code(self, entry: dict) -> Path:
        """The checkpoint directory as a source of reference code: verified before it is executed."""
        directory = self.source(entry)
        models.verify_files(directory, entry["reference_files"])
        return directory

    def gguf(self, entry: dict, ftype: str) -> Path:
        path = models.ensure(entry, self.src, self.out, self.llama_src, ftype)
        if path is None:
            skip(self.where(entry) + f", and there is no current {ftype} GGUF for it under {self.out}")
        return path

    # -- running things ----------------------------------------------------------------------

    def python(self, script: Path, *args) -> None:
        result = subprocess.run([sys.executable, str(script), *map(str, args)], env=self.env)
        if result.returncode != 0:
            print(f"FAILED: {script.name} exited with {result.returncode}")
            sys.exit(1)

    def binary(self, name: str, *args) -> int:
        sys.stdout.flush()
        return subprocess.run([str(self.bin_dir / name), *map(str, args)], env=self.env).returncode

    def session_binary(self, name: str, *args) -> int:
        """A test program of native/session/tests, whose programs are built next to this directory's."""
        sys.stdout.flush()
        return subprocess.run([str(self.bin_dir.parent / "session" / "tests" / name), *map(str, args)], env=self.env).returncode

    def convert_to(self, model_dir: Path, ftype: str, out: Path, name: str) -> None:
        self.python(REPO / "native" / "convert" / "convert_peacebell.py", "--llama-src", self.llama_src,
                    "--outtype", ftype, "--outfile", out, "--name", name, model_dir)

    def cached(self, path: Path, key_parts: list[str], make) -> Path:
        """Runs make() unless path was already made from the same inputs."""
        key = hashlib.sha256("\n".join(key_parts).encode()).hexdigest()
        stamp = path.with_name(path.name + ".key")
        if not (path.is_file() and stamp.is_file() and stamp.read_text() == key):
            stamp.unlink(missing_ok=True)
            path.parent.mkdir(parents=True, exist_ok=True)
            make()
            stamp.write_text(key)
        return path

    def generator_key(self) -> str:
        """Changes when any reference generator changes, so cached reference data is rebuilt."""
        digest = hashlib.sha256()
        for script in sorted(REFGEN.glob("*.py")):
            digest.update(script.read_bytes())
        return digest.hexdigest()

    # -- the tests ---------------------------------------------------------------------------

    def convert_determinism(self, entry: dict) -> int:
        model_dir = self.source(entry)
        scratch = self.work / "determinism" / entry["id"]
        shutil.rmtree(scratch, ignore_errors=True)
        problems = []
        # F32 first where it exists (the ported architectures), then the file that ships.
        for ftype in reversed(models.ftypes(entry, also_f32=True)):
            hashes = []
            for attempt in ("a", "b"):
                path = scratch / f"{attempt}-{ftype}.gguf"
                models.run_converter(entry, model_dir, path, self.llama_src, ftype)
                hashes.append(models.sha256_file(path))
                size = path.stat().st_size
                path.unlink()
            # The file the other tests use was written by the conversion script; it must be the same file.
            hashes.append(models.sha256_file(self.gguf(entry, ftype)))
            same = len(set(hashes)) == 1
            print(f"{'ok  ' if same else 'FAIL'}  {ftype}: {size} bytes, sha256 {hashes[0]} (two fresh conversions and the converted file)")
            if not same:
                problems.append(f"{ftype} conversions differ: {hashes}")
        shutil.rmtree(scratch, ignore_errors=True)
        return fail(problems)

    def gguf_f32(self, entry: dict) -> int:
        import check_gguf

        return fail(check_gguf.check_f32(self.llama_src, self.gguf(entry, "f32"), self.source(entry)))

    def gguf_f16(self, entry: dict) -> int:
        import check_gguf

        return fail(check_gguf.check_f16(self.llama_src, self.gguf(entry, "f16"), self.source(entry), self.gguf(entry, "f32")))

    @staticmethod
    def programs(entry: dict) -> dict:
        """What differs between the architectures in the tests they share."""
        if entry["converter"] == "shard2":
            # llama.cpp's own tokenizer again; the weights are a PyTorch checkpoint, not safetensors.
            return {"tokenizer": "test_plain_tokenizer", "logits": "test_shard2_logits",
                    "vectors": DATA / f"{entry['id']}-tokens.bin", "tokenizer_file": "tokenizer.json"}
        if entry["converter"] == "boris2":
            # llama.cpp's own tokenizer is exact for this model; the vectors belong to the checkpoint.
            return {"tokenizer": "test_plain_tokenizer", "logits": "test_boris2_logits",
                    "vectors": DATA / f"{entry['id']}-tokens.bin", "tokenizer_file": "tokenizer.json"}
        if "tokenizer.json" in (entry.get("files") or {}):
            # A byte-level BPE member of the peacebell family: llama.cpp's own tokenizer is exact for
            # it too, and its tokenizer file is the one Boris-2 has, so the vectors are Boris-2's
            # (the tokenizer test compares the file's recorded hash with the vectors' before anything else).
            return {"tokenizer": "test_plain_tokenizer", "logits": "test_peacebell_logits",
                    "vectors": DATA / "boris-2-instruct-tokens.bin", "tokenizer_file": "tokenizer.json"}
        # The two peacebell-v1 checkpoints share one tokenizer file and therefore one vector file.
        return {"tokenizer": "test_peacebell_tokenizer", "logits": "test_peacebell_logits",
                "vectors": DATA / "peacebell-v1-tokens.bin", "tokenizer_file": "sentencepiece.model"}

    def tokenizer(self, entry: dict) -> int:
        programs = self.programs(entry)
        return self.binary(programs["tokenizer"], "--gguf", self.gguf(entry, entry["ftype"]), "--vectors", programs["vectors"])

    def tokenizer_live(self, entry: dict) -> int:
        programs = self.programs(entry)
        model_dir = self.source(entry)
        gguf = self.gguf(entry, entry["ftype"])
        stamp = (self.llama_src / ".proseplow-prepared").read_text()
        vectors = self.cached(
            self.work / "refdata" / f"{entry['id']}-tokens-live.bin",
            [entry["files"][programs["tokenizer_file"]], self.generator_key(), stamp],
            lambda: self.python(REFGEN / "gen_token_vectors.py", "--model-dir", model_dir, "--live", "--llama-src", self.llama_src,
                                "--out", self.work / "refdata" / f"{entry['id']}-tokens-live.bin"))
        return self.binary(programs["tokenizer"], "--gguf", gguf, "--vectors", vectors)

    def same_story(self, committed: Path, regenerated: Path) -> bool:
        """True when an on-demand reference tells the story of the committed one: same tokens, same logits.

        The on-demand reference comes from whatever PyTorch is installed now. If it disagreed
        with the committed data, two tests would judge different models.
        """
        sys.path.insert(0, str(REFGEN))
        import numpy as np

        import formats

        _, _, old = formats.read_logit_reference(committed)
        _, _, new = formats.read_logit_reference(regenerated)
        drift = 0.0
        same_tokens = len(old) == len(new)
        for a, b in zip(old, new):
            same_tokens = same_tokens and np.array_equal(a.ids, b.ids) and np.array_equal(a.greedy, b.greedy)
            if same_tokens:
                drift = max(drift, float(np.abs(a.top_val - b.top_val).max()))
        if not same_tokens:
            print("FAIL  the regenerated reference has other token sequences than the committed one")
            return False
        ok = drift <= 1e-4
        print(f"{'ok  ' if ok else 'FAIL'}  regenerated reference vs committed reference ({committed.name}): max |dlogit| {drift:.3g} (limit 1e-04)")
        return ok

    def shard2_logits(self, entry: dict, precision: str) -> int:
        """Shard-2: both precisions are judged against the whole logit vector at every position.

        With a vocabulary of 4,097 tokens that reference is small, and it lets the F32 test hold
        every logit to its limit, the deletion token's and the special ids' included. The
        token sequences are never made here: they are read from the committed files.
        """
        import json

        code_dir = self.reference_code(entry)
        gguf = self.gguf(entry, precision)
        with open(code_dir / "config.json", encoding="utf-8") as f:
            n_context = json.load(f)["max_position_embeddings"]
        key = [entry["files"]["Shard-2.pt"], self.generator_key()]
        refdata = self.work / "refdata"
        generator = REFGEN / "gen_logit_ref.py"
        references = {}
        for name, committed, extra in (("logits", DATA / f"{entry['id']}-logits.bin", []),
                                       ("long-logits", DATA / f"{entry['id']}-long-logits.bin", []),
                                       # one sequence over the model's whole context, twice the length it was trained on
                                       (f"context-{n_context}-logits", DATA / f"{entry['id']}-long-logits.bin", ["--tile", n_context])):
            path = refdata / f"{entry['id']}-{name}-full.bin"
            references[name] = self.cached(path, key + [models.sha256_file(committed)] + [str(x) for x in extra],
                                           lambda path=path, committed=committed, extra=extra: self.python(
                                               generator, "--model-dir", code_dir, "--ids-from", committed, "--full", *extra, "--out", path))
            if not extra and not self.same_story(committed, path):
                return fail(["the PyTorch reference drifted from the committed data: check the package versions"])
        arguments = ["--gguf", gguf, "--precision", precision, "--ref", references["logits"], "--long-ref", references["long-logits"],
                     "--context-ref", references[f"context-{n_context}-logits"]]
        if precision == "f16":
            # The file that ships also gets the sampling measurement: a few thousand tokens with the reference script's settings.
            arguments += ["--sample", 4000]
        code = self.binary(self.programs(entry)["logits"], *arguments)
        if precision == "f32":
            code = self.shard2_refusals(gguf) or code
        return code

    def shard2_refusals(self, gguf: Path) -> int:
        """A file without its window, or without one of its two per-layer flag arrays, must fail to load."""
        scratch = self.work / "shard2-refusals"
        shutil.rmtree(scratch, ignore_errors=True)
        scratch.mkdir(parents=True)
        code = 0
        for key in ("attention.sliding_window", "attention.sliding_window_pattern", "attention.rope_pattern"):
            stripped = scratch / f"without-{key}.gguf"
            # Upstream's own tool for copying a file with other metadata; the tensors are untouched.
            command = [sys.executable, str(self.llama_src / "gguf-py" / "gguf" / "scripts" / "gguf_new_metadata.py"), str(gguf), str(stripped), "--force",
                       "--remove-metadata", f"shard2.{key}"]
            result = subprocess.run(command, env=self.env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
            if result.returncode != 0:
                print(result.stdout)
                return fail([f"could not write the file without shard2.{key}"])
            code = self.binary("test_shard2_logits", "--gguf", stripped, "--mode", "refuse") or code
        shutil.rmtree(scratch, ignore_errors=True)
        return code

    def logits_f32(self, entry: dict) -> int:
        if entry["converter"] == "shard2":
            return self.shard2_logits(entry, "f32")
        arguments = ("--gguf", self.gguf(entry, "f32"), "--ref", DATA / f"{entry['id']}-logits.bin", "--precision", "f32")
        code = self.binary(self.programs(entry)["logits"], *arguments)
        if entry["converter"] == "boris2":
            # The graph must stop the process when the end-of-text token is fed; that takes a run of its own.
            code = self.binary(self.programs(entry)["logits"], *arguments, "--mode", "refuse-eos") or code
        return code

    def full_reference(self, entry: dict) -> Path:
        code_dir = self.reference_code(entry)
        path = self.work / "refdata" / f"{entry['id']}-logits-full.bin"
        return self.cached(path, [entry["files"]["model.safetensors"], self.generator_key()],
                           lambda: self.python(REFGEN / "gen_logit_ref.py", "--model-dir", code_dir, "--full", "--out", path))

    def logits_f16(self, entry: dict) -> int:
        if entry["converter"] == "shard2":
            return self.shard2_logits(entry, "f16")
        gguf = self.gguf(entry, "f16")
        full = self.full_reference(entry)
        if not self.same_story(DATA / f"{entry['id']}-logits.bin", full):
            return fail(["the PyTorch reference drifted from the committed data: check the package versions"])
        return self.binary(self.programs(entry)["logits"], "--gguf", gguf, "--ref", full, "--precision", "f16")

    def chat(self, entry: dict, precision: str) -> int:
        return self.binary("test_boris2_chat", "--gguf", self.gguf(entry, precision), "--ref", DATA / f"{entry['id']}-chat.bin", "--precision", precision)

    def no_ngram_lookup(self, entry: dict) -> int:
        """Canon and the hybrid memory in isolation: a file without the lookup arrays gives every token the miss row."""
        code_dir = self.reference_code(entry)
        f32 = self.gguf(entry, "f32")
        reference = self.work / "refdata" / f"{entry['id']}-logits-no-ngram-lookup.bin"
        self.cached(reference, [entry["files"]["model.safetensors"], self.generator_key()],
                    lambda: self.python(REFGEN / "gen_logit_ref.py", "--model-dir", code_dir, "--no-ngram-lookup", "--out", reference))
        scratch = self.work / "no-ngram-lookup"
        shutil.rmtree(scratch, ignore_errors=True)
        scratch.mkdir(parents=True)
        stripped = scratch / f"{entry['id']}-f32-no-ngram-lookup.gguf"
        # Upstream's own tool for copying a file with other metadata; the tensors are untouched.
        command = [sys.executable, str(self.llama_src / "gguf-py" / "gguf" / "scripts" / "gguf_new_metadata.py"), str(f32), str(stripped), "--force"]
        for key in ("bigram_keys", "bigram_rows", "trigram_keys", "trigram_rows"):
            command += ["--remove-metadata", f"boris2.ngram.{key}"]
        result = subprocess.run(command, env=self.env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        if result.returncode != 0:
            print(result.stdout)
            return fail(["could not write the file without the lookup arrays"])
        code = self.binary("test_boris2_logits", "--gguf", stripped, "--ref", reference, "--precision", "f32")
        if code == 0:
            shutil.rmtree(scratch, ignore_errors=True)
        return code

    def qwen_load_generate(self, entry: dict) -> int:
        return self.binary("test_qwen", "--gguf", self.gguf(entry, entry["ftype"]))

    def bpe_checkpoint(self, checkpoint: Path, code_dir: Path, scratch: Path, name: str, random_sequences: bool, flat: bool) -> int:
        """Every peacebell check for one byte-level BPE checkpoint directory, with its reference data made on the spot.

        random_sequences: the reference sequences are seeded random tokens instead of text (a
        seeded random checkpoint, whose greedy replies to text would say nothing).
        flat: the weights are untrained. The distributions are then nearly flat: first choices
        closer than 1e-2 in the reference are ties that any rounding may flip, and they are left
        out of the 16-bit top-1 count; a wrong beginning moves the logits less than it does for
        a trained model, so the rewind test's control asks for ten times the limit instead of
        0.5; and the share of half-precision subnormals is that of the initialisation, which is
        reported and not judged.
        """
        import check_gguf

        f32, f16, again = scratch / f"{name}-f32.gguf", scratch / f"{name}-f16.gguf", scratch / f"{name}-f16-again.gguf"
        self.convert_to(checkpoint, "f32", f32, name)
        self.convert_to(checkpoint, "f16", f16, name)
        self.convert_to(checkpoint, "f16", again, name)

        problems = []
        if models.sha256_file(f16) != models.sha256_file(again):
            problems.append(f"two conversions of {name} differ")
        again.unlink()
        problems += check_gguf.check_f32(self.llama_src, f32, checkpoint)
        problems += check_gguf.check_f16(self.llama_src, f16, checkpoint, f32, trained=not flat)
        print(f"{'FAIL' if problems else 'ok  '}  converter, byte-level BPE branch: F32 equals the checkpoint, F16 is its rounding, conversion is deterministic "
              f"(F16: {f16.stat().st_size} bytes, sha256 {models.sha256_file(f16)})")

        reference = scratch / f"{name}-logits-full.bin"
        self.python(REFGEN / "gen_logit_ref.py", "--model-dir", checkpoint, "--code-dir", code_dir, "--full", "--out", reference,
                    *(["--random-sequences", "3"] if random_sequences else []))
        vectors = scratch / f"{name}-tokens.bin"
        self.python(REFGEN / "gen_token_vectors.py", "--model-dir", checkpoint, "--live", "--llama-src", self.llama_src, "--out", vectors)

        # The random sequences come with replies of 24 tokens, text with the generator's usual limit.
        limit = ["--max-new-tokens", "24"] if random_sequences else []
        untrained = ["--top1-margin", "1e-2", "--control-min", "0.05"] if flat else []
        codes = [
            self.binary("test_plain_tokenizer", "--gguf", f16, "--vectors", vectors),
            self.binary("test_peacebell_logits", "--gguf", f32, "--ref", reference, "--precision", "f32", *limit, *untrained),
            self.binary("test_peacebell_logits", "--gguf", f16, "--ref", reference, "--precision", "f16", *limit, *untrained),
        ]
        with open(checkpoint / "tokenizer_config.json", encoding="utf-8") as f:
            if json.load(f).get("chat_template") is not None:
                # A chat model: the whole path of a conversation through the session, with the file that ships.
                codes.append(self.session_binary("test_chat_text", "--gguf", f16))
        return fail(problems) or (1 if any(codes) else 0)

    def random_bpe(self) -> int:
        import check_gguf

        code_entry = models.find_entry("peacebell-v1-291m")
        tokenizer_entry = models.find_entry("boris-2-instruct")
        code_dir = self.reference_code(code_entry)
        tokenizer_dir = models.source_dir(tokenizer_entry, self.src)
        if tokenizer_dir is None:
            skip(self.where(tokenizer_entry) + "; its tokenizer.json stands in for the byte-level BPE tokenizer of the family")
        models.verify_files(tokenizer_dir, {"tokenizer.json": tokenizer_entry["files"]["tokenizer.json"]})

        scratch = self.work / "random-bpe"
        shutil.rmtree(scratch, ignore_errors=True)
        checkpoint = scratch / "checkpoint"
        self.python(REFGEN / "make_random_checkpoint.py", "--code-dir", code_dir, "--tokenizer-json", tokenizer_dir / "tokenizer.json", "--out", checkpoint)
        code = self.bpe_checkpoint(checkpoint, code_dir, scratch, "random", random_sequences=True, flat=True)

        # The same weights as a member that continues text: without a chat template the file must
        # say "completion". And with a template the native encoder does not implement, there must
        # be no file at all.
        problems = []
        with open(checkpoint / "tokenizer_config.json", encoding="utf-8") as f:
            tokenizer_config = json.load(f)
        template = tokenizer_config.pop("chat_template")
        with open(checkpoint / "tokenizer_config.json", "w", encoding="utf-8") as f:
            json.dump(tokenizer_config, f, indent=2)
        base = scratch / "random-base-f16.gguf"
        self.convert_to(checkpoint, "f16", base, "random")
        problems += check_gguf.check_f16(self.llama_src, base, checkpoint, None, trained=False)
        print(f"{'FAIL' if problems else 'ok  '}  without a chat template the file says 'completion' and carries no template")
        with open(checkpoint / "tokenizer_config.json", "w", encoding="utf-8") as f:
            json.dump(dict(tokenizer_config, chat_template=template.replace("| trim", "")), f, indent=2)
        other = scratch / "random-other-template-f16.gguf"
        result = subprocess.run([sys.executable, str(REPO / "native" / "convert" / "convert_peacebell.py"), "--llama-src", str(self.llama_src),
                                 "--outtype", "f16", "--outfile", str(other), "--name", "random", str(checkpoint)],
                                env=self.env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        refused = result.returncode != 0 and "chat template" in result.stdout and not other.exists()
        print(f"{'ok  ' if refused else 'FAIL'}  a chat template other than the one the native encoder implements is refused by the converter")
        if not refused:
            problems.append("the converter accepted an unknown chat template")

        if code == 0 and not problems:
            # Two gigabytes of scratch files nobody needs after a pass; kept after a failure.
            shutil.rmtree(scratch, ignore_errors=True)
        return fail(problems) or code

    def bpe_packages(self) -> int:
        """The unpublished byte-level BPE members of the family, where their checkpoint directories exist.

        A directory is what the training pipeline packages: config.json, model.safetensors,
        tokenizer.json, tokenizer_config.json, generation_config.json, and model code that is
        not run here. A member with a chat template is tested as a chat model, one without as
        a model that continues text. Nothing is written into the directories.
        """
        listed = [Path(p) for p in os.environ.get("PROSEPLOW_PEACEBELL_PACKAGES", "").split(":") if p]
        if not listed:
            skip("PROSEPLOW_PEACEBELL_PACKAGES names no checkpoint directory: the unpublished byte-level BPE members of the "
                 "peacebell family are tested only where their packages are")
        code_dir = self.reference_code(models.find_entry("peacebell-v1-291m"))
        code = 0
        for checkpoint in listed:
            name = checkpoint.name
            print(f"note  package {name}")
            if not (checkpoint / "config.json").is_file():
                code = fail([f"{checkpoint} is not a checkpoint directory"]) or code
                continue
            own_code = checkpoint / "modeling_gpt.py"
            if own_code.is_file() and models.sha256_file(own_code) not in REVIEWED_PACKAGE_CODE:
                print("note  the package's modeling_gpt.py is not a version that was read: the reference below is the published family "
                      "code with the package's weights, which is the package's own model only if the two compute the same")
            # A package that records its training step says whether it was trained at all.
            flat = False
            info = checkpoint / "package_info.json"
            if info.is_file():
                with open(info, encoding="utf-8") as f:
                    flat = json.load(f).get("step") == 0
            scratch = self.work / "bpe-packages" / name
            shutil.rmtree(scratch, ignore_errors=True)
            scratch.mkdir(parents=True)
            result = self.bpe_checkpoint(checkpoint, code_dir, scratch, name, random_sequences=False, flat=flat)
            if result == 0:
                shutil.rmtree(scratch, ignore_errors=True)
            code = result or code
        return code


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--bin-dir", type=Path, required=True)
    parser.add_argument("--work-dir", type=Path, required=True)
    parser.add_argument("--llama-src", type=Path, required=True)
    parser.add_argument("test")
    parser.add_argument("model", nargs="?")
    args = parser.parse_args()

    runner = Runner(args)
    try:
        if args.test == "random-bpe":
            return runner.random_bpe()
        if args.test == "bpe-packages":
            return runner.bpe_packages()
        tests = {
            "convert-determinism": runner.convert_determinism,
            "gguf-f32": runner.gguf_f32,
            "gguf-f16": runner.gguf_f16,
            "tokenizer": runner.tokenizer,
            "tokenizer-live": runner.tokenizer_live,
            "logits-f32": runner.logits_f32,
            "logits-f16": runner.logits_f16,
            "chat-f32": lambda entry: runner.chat(entry, "f32"),
            "chat-f16": lambda entry: runner.chat(entry, "f16"),
            "no-ngram-lookup": runner.no_ngram_lookup,
            "qwen-load-generate": runner.qwen_load_generate,
        }
        if args.test not in tests or args.model is None:
            parser.error("unknown test, or no model id")
        return tests[args.test](models.find_entry(args.model))
    except models.ModelError as e:
        print(f"FAILED: {e}")
        return 1


if __name__ == "__main__":
    sys.exit(main())
