#!/usr/bin/env python3
"""Runs the session conformance test (test_session) for one model of models/manifest.json.

    run_session.py --bin-dir DIR --work-dir DIR --llama-src DIR [--residue] MODEL_ID

ctest calls this (see CMakeLists.txt next to it). Finding the model follows the rules of
native/tests/run.py, whose Runner is reused: the GGUF is located from PROSEPLOW_MODEL_OUT,
converted on demand when the checkpoint is under PROSEPLOW_MODEL_SRC, and a model whose weights
are not on this machine makes the test exit with 77, which ctest reports as skipped.

With --residue it runs test_residue on the model file instead, which needs nothing else.

Besides the model file the test gets:
- for an architecture ported by this project (peacebell, boris2, shard2), the committed logit
  reference (its prompts and greedy replies) and the committed token vectors of
  native/tests/data; for a model with windowed layers (shard2) also its committed reference of
  sequences longer than the window;
- two scratch files that hold the model the way an APK holds an asset, written here and deleted
  afterwards: one with the model at a 16 KiB-aligned offset between other data (another model
  of the manifest when its GGUF is already on the machine, else the head of the same file), and
  for models below 1 GiB one with the model at an offset that is not a multiple of 32.
"""
from __future__ import annotations

import argparse
import shutil
import sys

from pathlib import Path

HERE = Path(__file__).resolve().parent
NATIVE = HERE.parent.parent

sys.dont_write_bytecode = True
sys.path.insert(0, str(NATIVE / "tests"))

import run as harness  # noqa: E402  (native/tests/run.py)

models = harness.models

PAGE = 16384       # the alignment of an uncompressed .so-named entry of an APK
HEAD = 32 << 20    # how much of a file stands in for a neighbouring model when there is no other
SMALL = 1 << 30    # models below this also get the unaligned file


def filler(n: int) -> bytes:
    """n bytes that are neither zeros nor a model: a recognisable repeating pattern."""
    pattern = b"not a model. "
    return (pattern * (n // len(pattern) + 1))[:n]


def pad_to_page(out) -> None:
    out.write(filler(-out.tell() % PAGE))


def neighbour(runner: harness.Runner, entry: dict, gguf: Path) -> tuple[Path, int | None]:
    """The file to put next to the model, and how much of it (None: all of it)."""
    for other in models.load_manifest():
        if other["id"] == entry["id"] or other.get("status") != "ready" or other.get("purpose") != "bundled":
            continue
        try:
            if models.is_current(other, runner.out, runner.llama_src, other["ftype"]):
                return models.output_path(other, runner.out, other["ftype"]), None
        except models.ModelError:
            continue
    return gguf, HEAD


def copy(src: Path, out, limit: int | None) -> None:
    with open(src, "rb") as f:
        if limit is None:
            shutil.copyfileobj(f, out, 1 << 24)
        else:
            out.write(f.read(limit))


def write_aligned(path: Path, gguf: Path, beside: Path, beside_limit: int | None) -> int:
    """Other data, the model on a 16 KiB boundary, other data. Returns the model's offset."""
    with open(path, "wb") as out:
        out.write(filler(PAGE))
        copy(beside, out, beside_limit)
        pad_to_page(out)
        offset = out.tell()
        copy(gguf, out, None)
        pad_to_page(out)
        copy(beside, out, HEAD)
        out.write(filler(5))
    return offset


def write_unaligned(path: Path, gguf: Path) -> int:
    """The model at an offset that no alignment explains. Returns the offset."""
    offset = 2 * PAGE + 12
    with open(path, "wb") as out:
        out.write(filler(offset))
        copy(gguf, out, None)
        out.write(filler(1000))
    return offset


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--bin-dir", type=Path, required=True)
    parser.add_argument("--work-dir", type=Path, required=True)
    parser.add_argument("--llama-src", type=Path, required=True)
    parser.add_argument("--residue", action="store_true",
                        help="run test_residue (what a forgotten conversation leaves in memory) instead of test_session")
    parser.add_argument("model")
    args = parser.parse_args()

    scratch = args.work_dir / "session" / args.model
    try:
        runner = harness.Runner(args)
        entry = models.find_entry(args.model)
        gguf = runner.gguf(entry, entry["ftype"])  # exits with 77 when the weights are not here
        if args.residue:
            return runner.binary("test_residue", "--gguf", gguf)
        size = gguf.stat().st_size

        command = ["--gguf", gguf]
        if models.CONVERTERS[entry["converter"]].validation_f32:
            # A port of ours: it has reference data, and the harness knows which vector file is its own.
            command += ["--ref", harness.DATA / f"{entry['id']}-logits.bin", "--vectors", harness.Runner.programs(entry)["vectors"]]
            longer = harness.DATA / f"{entry['id']}-long-logits.bin"
            if longer.is_file():
                command += ["--long-ref", longer]

        shutil.rmtree(scratch, ignore_errors=True)
        scratch.mkdir(parents=True)
        beside, beside_limit = neighbour(runner, entry, gguf)
        aligned = scratch / "aligned.bin"
        offset = write_aligned(aligned, gguf, beside, beside_limit)
        print(f"note  {aligned.name}: {aligned.stat().st_size} bytes, the model at offset {offset} next to "
              f"{'the head of itself' if beside_limit else beside.name}")
        command += ["--embedded", f"{aligned}:{offset}:{size}"]
        if size < SMALL:
            unaligned = scratch / "unaligned.bin"
            command += ["--embedded-unaligned", f"{unaligned}:{write_unaligned(unaligned, gguf)}:{size}"]

        return runner.binary("test_session", *command)
    except models.ModelError as e:
        print(f"FAILED: {e}")
        return 1
    finally:
        # Gigabytes that nobody needs afterwards, pass or fail.
        shutil.rmtree(scratch, ignore_errors=True)


if __name__ == "__main__":
    sys.exit(main())
