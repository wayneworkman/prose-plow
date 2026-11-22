#!/usr/bin/env python3
"""Runs one host test: finds its files, prepares what is missing, runs the check.

    run.py --bin-dir DIR --work-dir DIR --llama-src DIR TEST [MODEL_ID]

ctest calls this for every test (see CMakeLists.txt). The files a test needs depend on the
machine, so they are located when the test runs, from the environment:

    PROSEPLOW_MODEL_SRC   directory of the downloaded checkpoints (see models/README.md)
    PROSEPLOW_MODEL_OUT   directory of the converted GGUF files (default: <work-dir>/models)
    PROSEPLOW_THREADS     inference threads (default 4)

A test whose checkpoint is not on this machine exits with 77, which ctest reports as skipped,
after saying which checkpoint it looked for. It never fails for that reason and never
downloads anything. GGUF files are converted on demand and reused while they are current, so
running ctest alone is enough; reference data that is too large to commit is generated into
the work directory the same way.

Tests:
    convert-determinism  two conversions of the same checkpoint give the same bytes
    gguf-f32             the F32 file equals the checkpoint byte for byte, metadata included
    gguf-f16             the F16 file is the rounded checkpoint; types and subnormal share
    tokenizer            the front-end against the committed token vectors
    tokenizer-live       the front-end against the SentencePiece library, every symbol of the file
    logits-f32           the F32 file against the committed reference logits
    logits-f16           the F16 file against reference logits at every position (KL, top-1)
    random-bpe           all of the above for a seeded random byte-level BPE checkpoint
"""
from __future__ import annotations

import argparse
import hashlib
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
        for ftype in ("f32", "f16"):
            hashes = []
            for attempt in ("a", "b"):
                path = scratch / f"{attempt}-{ftype}.gguf"
                self.convert_to(model_dir, ftype, path, entry["name"])
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

    def tokenizer(self, entry: dict) -> int:
        return self.binary("test_peacebell_tokenizer", "--gguf", self.gguf(entry, entry["ftype"]), "--vectors", DATA / "peacebell-v1-tokens.bin")

    def tokenizer_live(self, entry: dict) -> int:
        model_dir = self.source(entry)
        gguf = self.gguf(entry, entry["ftype"])
        stamp = (self.llama_src / ".proseplow-prepared").read_text()
        vectors = self.cached(
            self.work / "refdata" / f"{entry['id']}-tokens-live.bin",
            [entry["files"]["sentencepiece.model"], self.generator_key(), stamp],
            lambda: self.python(REFGEN / "gen_token_vectors.py", "--model-dir", model_dir, "--live", "--llama-src", self.llama_src,
                                "--out", self.work / "refdata" / f"{entry['id']}-tokens-live.bin"))
        return self.binary("test_peacebell_tokenizer", "--gguf", gguf, "--vectors", vectors)

    def logits_f32(self, entry: dict) -> int:
        return self.binary("test_peacebell_logits", "--gguf", self.gguf(entry, "f32"), "--ref", DATA / f"{entry['id']}-logits.bin", "--precision", "f32")

    def full_reference(self, entry: dict) -> Path:
        code_dir = self.reference_code(entry)
        path = self.work / "refdata" / f"{entry['id']}-logits-full.bin"
        return self.cached(path, [entry["files"]["model.safetensors"], self.generator_key()],
                           lambda: self.python(REFGEN / "gen_logit_ref.py", "--model-dir", code_dir, "--full", "--out", path))

    def logits_f16(self, entry: dict) -> int:
        gguf = self.gguf(entry, "f16")
        full = self.full_reference(entry)
        # The on-demand reference comes from whatever PyTorch is installed now. It must tell the
        # same story as the committed one, or the two precision tests would judge different models.
        sys.path.insert(0, str(REFGEN))
        import numpy as np

        import formats

        _, _, committed = formats.read_logit_reference(DATA / f"{entry['id']}-logits.bin")
        _, _, regenerated = formats.read_logit_reference(full)
        drift = 0.0
        for a, b in zip(committed, regenerated):
            if not (np.array_equal(a.ids, b.ids) and np.array_equal(a.greedy, b.greedy)):
                return fail(["the regenerated reference has other token sequences than the committed one"])
            drift = max(drift, float(np.abs(a.top_val - b.top_val).max()))
        ok = drift <= 1e-4
        print(f"{'ok  ' if ok else 'FAIL'}  regenerated reference vs committed reference: max |dlogit| {drift:.3g} (limit 1e-04)")
        if not ok:
            return fail(["the PyTorch reference drifted from the committed data: check the package versions"])
        return self.binary("test_peacebell_logits", "--gguf", gguf, "--ref", full, "--precision", "f16")

    def random_bpe(self) -> int:
        import check_gguf

        code_entry = models.find_entry("peacebell-v1-291m")
        tokenizer_entry = models.find_entry("boris-2-instruct")
        code_dir = self.reference_code(code_entry)
        tokenizer_dir = models.source_dir(tokenizer_entry, self.src)
        if tokenizer_dir is None:
            skip(self.where(tokenizer_entry) + "; its tokenizer.json stands in for the byte-level BPE tokenizer of the family")
        models.verify_files(tokenizer_dir, tokenizer_entry["reference_files"])

        scratch = self.work / "random-bpe"
        shutil.rmtree(scratch, ignore_errors=True)
        checkpoint = scratch / "checkpoint"
        self.python(REFGEN / "make_random_checkpoint.py", "--code-dir", code_dir, "--tokenizer-json", tokenizer_dir / "tokenizer.json", "--out", checkpoint)
        f32, f16, again = scratch / "random-f32.gguf", scratch / "random-f16.gguf", scratch / "random-f16-again.gguf"
        self.convert_to(checkpoint, "f32", f32, "peacebell-bpe-random")
        self.convert_to(checkpoint, "f16", f16, "peacebell-bpe-random")
        self.convert_to(checkpoint, "f16", again, "peacebell-bpe-random")

        problems = []
        if models.sha256_file(f16) != models.sha256_file(again):
            problems.append("two conversions of the random checkpoint differ")
        again.unlink()
        problems += check_gguf.check_f32(self.llama_src, f32, checkpoint)
        problems += check_gguf.check_f16(self.llama_src, f16, checkpoint, f32)
        print(f"{'FAIL' if problems else 'ok  '}  converter, byte-level BPE branch: F32 equals the checkpoint, F16 is its rounding, conversion is deterministic")

        reference = scratch / "random-logits-full.bin"
        self.python(REFGEN / "gen_logit_ref.py", "--model-dir", checkpoint, "--code-dir", code_dir, "--full", "--random-sequences", "3", "--out", reference)
        vectors = scratch / "random-tokens.bin"
        self.python(REFGEN / "gen_token_vectors.py", "--model-dir", checkpoint, "--live", "--llama-src", self.llama_src, "--out", vectors)

        codes = [
            self.binary("test_plain_tokenizer", "--gguf", f16, "--vectors", vectors),
            self.binary("test_peacebell_logits", "--gguf", f32, "--ref", reference, "--precision", "f32", "--max-new-tokens", "24"),
            # Random weights give nearly flat distributions: first choices closer than 1e-2 in the
            # reference are ties that any rounding may flip, and are left out of the top-1 count.
            self.binary("test_peacebell_logits", "--gguf", f16, "--ref", reference, "--precision", "f16", "--max-new-tokens", "24",
                        "--top1-margin", "1e-2"),
        ]
        if not problems and all(c == 0 for c in codes):
            # Two gigabytes of scratch files nobody needs after a pass; kept after a failure.
            shutil.rmtree(scratch, ignore_errors=True)
        return fail(problems) or (1 if any(codes) else 0)


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
        tests = {
            "convert-determinism": runner.convert_determinism,
            "gguf-f32": runner.gguf_f32,
            "gguf-f16": runner.gguf_f16,
            "tokenizer": runner.tokenizer,
            "tokenizer-live": runner.tokenizer_live,
            "logits-f32": runner.logits_f32,
            "logits-f16": runner.logits_f16,
        }
        if args.test not in tests or args.model is None:
            parser.error("unknown test, or no model id")
        return tests[args.test](models.find_entry(args.model))
    except models.ModelError as e:
        print(f"FAILED: {e}")
        return 1


if __name__ == "__main__":
    sys.exit(main())
