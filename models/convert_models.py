#!/usr/bin/env python3
"""Converts the models of manifest.json from local checkpoint directories to GGUF files.

    convert_models.py [--src DIR] [--out DIR] [--llama-src DIR] [--also-f32] [--available] [--force] [ID ...]
    convert_models.py --list

--src (or PROSEPLOW_MODEL_SRC) is a directory that holds the downloaded checkpoints, each
either as <src>/<id>/ or in the layout of a Hugging Face hub cache,
<src>/models--<owner>--<name>/snapshots/<revision>/. --out (or PROSEPLOW_MODEL_OUT) receives
<out>/<id>/<output>. --llama-src (or PROSEPLOW_LLAMA_SRC) is the patched llama.cpp copy written
by native/tools/prepare-llama-src.sh, which holds the converters.

Before anything is converted, every source file of the model is hashed and compared with the
manifest: a checkpoint that is not byte for byte the pinned one is refused. Next to each output
a small record (<output>.json) says what it was made from and with which converter sources; a
model whose record still matches is not converted again, so the script can be run as often as
wanted. --also-f32 additionally writes <id>-f32.gguf for every model whose architecture is a
port of ours: the file the validation tests compare with the stored weights; it is never
shipped. A model whose checkpoint is not under --src is an error, unless --available says to
convert only what is there.

Conversion is deterministic, and the manifest pins the SHA-256 of each shipping file
(output_sha256). A conversion that produces other bytes fails: either something on this machine
differs from the pinned tool versions, or the converter or the llama.cpp pin was changed on
purpose, in which case the new hash is recorded in the manifest in the same change.

This script never downloads anything.
"""
from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
import os
import subprocess
import sys

from dataclasses import dataclass
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
MANIFEST = Path(__file__).resolve().parent / "manifest.json"

_DRIVERS = REPO / "native" / "convert"


@dataclass(frozen=True)
class Converter:
    """One way of turning a checkpoint directory into a GGUF file.

    Every driver takes --llama-src, --outtype, --outfile, --name and the checkpoint directory.
    """
    driver: Path
    # True for an architecture this project ported: its F32 file is compared with the stored
    # weights by the validation tests. An architecture that upstream llama.cpp already loads has
    # nothing of ours to validate, and its F32 file would only cost disk space.
    validation_f32: bool
    # True when the driver must be told which files it may read (--file) and takes the entry's
    # converter_args. The drivers of our own ports read a fixed set of files by name instead.
    staged: bool = False


# Converter name in the manifest -> how it is run.
CONVERTERS = {
    "peacebell": Converter(_DRIVERS / "convert_peacebell.py", validation_f32=True),
    "boris2": Converter(_DRIVERS / "convert_boris2.py", validation_f32=True),
    "shard2": Converter(_DRIVERS / "convert_shard2.py", validation_f32=True),
    "upstream": Converter(_DRIVERS / "convert_upstream.py", validation_f32=False, staged=True),
}


class ModelError(Exception):
    """A model cannot be converted as the manifest describes it; the message says why."""


def load_manifest() -> list[dict]:
    with open(MANIFEST, encoding="utf-8") as f:
        manifest = json.load(f)
    if manifest.get("format") != 1:
        raise ModelError(f"{MANIFEST.name} has an unknown format")
    return manifest["models"]


def find_entry(model_id: str) -> dict:
    for entry in load_manifest():
        if entry["id"] == model_id:
            return entry
    raise ModelError(f"no model {model_id!r} in {MANIFEST.name}")


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with open(path, "rb") as f:
        while chunk := f.read(1 << 20):
            digest.update(chunk)
    return digest.hexdigest()


def source_dir(entry: dict, src: Path | None) -> Path | None:
    """The directory that holds the checkpoint of entry, or None when it is not on this machine."""
    if src is None:
        return None
    owner, _, name = entry["repository"].partition("/")
    candidates = [src / entry["id"]]
    if entry.get("revision"):
        candidates.append(src / f"models--{owner}--{name}" / "snapshots" / entry["revision"])
    for candidate in candidates:
        if candidate.is_dir():
            return candidate
    return None


def verify_files(directory: Path, files: dict[str, str]) -> None:
    """Raises unless every named file exists in directory with the pinned SHA-256."""
    for name, expected in files.items():
        path = directory / name
        if not path.is_file():
            raise ModelError(f"{name} is missing from {directory}")
        actual = sha256_file(path)
        if actual != expected:
            raise ModelError(f"{name} in {directory} has SHA-256 {actual}, the manifest pins {expected}")


def converter_key(entry: dict, llama_src: Path) -> str:
    """Identifies the code that turns the checkpoint into a GGUF: the patched copy and the driver."""
    stamp = llama_src / ".proseplow-prepared"
    if not stamp.is_file():
        raise ModelError(f"{llama_src} is not a prepared llama.cpp copy (run native/tools/prepare-llama-src.sh)")
    digest = hashlib.sha256()
    digest.update(stamp.read_bytes())
    digest.update(CONVERTERS[entry["converter"]].driver.read_bytes())
    return digest.hexdigest()


def output_path(entry: dict, out: Path, ftype: str) -> Path:
    name = entry["output"] if ftype == entry["ftype"] else f"{entry['id']}-{ftype}.gguf"
    return out / entry["id"] / name


def ftypes(entry: dict, also_f32: bool) -> list[str]:
    """The float types converted for entry: the shipping one, then F32 where it validates a port."""
    wanted = [entry["ftype"]]
    if also_f32 and entry["ftype"] != "f32" and CONVERTERS[entry["converter"]].validation_f32:
        wanted.append("f32")
    return wanted


def _record(entry: dict, ftype: str, key: str) -> dict:
    """Everything the bytes of an output depend on; an output whose record differs is stale."""
    return {"id": entry["id"], "revision": entry["revision"], "files": entry["files"], "ftype": ftype, "converter_key": key,
            "name": entry["name"], "converter_args": entry.get("converter_args", [])}


def is_current(entry: dict, out: Path, llama_src: Path, ftype: str) -> bool:
    """True when the output exists and was made from the pinned files by the present converter."""
    path = output_path(entry, out, ftype)
    record_path = path.with_name(path.name + ".json")
    if not path.is_file() or not record_path.is_file():
        return False
    try:
        with open(record_path, encoding="utf-8") as f:
            record = json.load(f)
    except (OSError, ValueError):
        return False
    expected = _record(entry, ftype, converter_key(entry, llama_src))
    pinned = entry.get("output_sha256") if ftype == entry["ftype"] else None
    return (all(record.get(k) == v for k, v in expected.items()) and record.get("size") == path.stat().st_size
            and (pinned is None or record.get("sha256") == pinned))


def run_converter(entry: dict, src_dir: Path, path: Path, llama_src: Path, ftype: str) -> None:
    """Runs the entry's converter on src_dir and writes path. Nothing is verified or recorded here."""
    if entry.get("status") != "ready" or entry.get("converter") not in CONVERTERS:
        raise ModelError(f"{entry['id']} is not convertible yet: {entry.get('note', 'no converter')}")
    converter = CONVERTERS[entry["converter"]]
    command = [sys.executable, str(converter.driver), "--llama-src", str(llama_src),
               "--outtype", ftype, "--outfile", str(path), "--name", entry["name"]]
    if converter.staged:
        # The driver shows upstream's converter the pinned files and nothing else.
        for name in entry["files"]:
            command += ["--file", name]
        command += [f"--converter-arg={arg}" for arg in entry.get("converter_args", [])]
    elif entry.get("converter_args"):
        raise ModelError(f"{entry['id']}: the {entry['converter']} converter takes no converter_args")
    command.append(str(src_dir))
    env = dict(os.environ, PYTHONDONTWRITEBYTECODE="1", CUDA_VISIBLE_DEVICES="", TQDM_DISABLE="1")
    result = subprocess.run(command, env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    if result.returncode != 0:
        raise ModelError(f"the {entry['converter']} converter failed for {entry['id']}:\n{result.stdout}")


def convert(entry: dict, src_dir: Path, out: Path, llama_src: Path, ftype: str, quiet: bool = False) -> Path:
    """Verifies the source files and converts unconditionally. Returns the output path."""
    if entry.get("status") != "ready" or entry.get("converter") not in CONVERTERS:
        raise ModelError(f"{entry['id']} is not convertible yet: {entry.get('note', 'no converter')}")
    verify_files(src_dir, entry["files"])
    path = output_path(entry, out, ftype)
    record_path = path.with_name(path.name + ".json")
    record_path.unlink(missing_ok=True)
    run_converter(entry, src_dir, path, llama_src, ftype)
    record = _record(entry, ftype, converter_key(entry, llama_src))
    record.update({"size": path.stat().st_size, "sha256": sha256_file(path)})
    pinned = entry.get("output_sha256") if ftype == entry["ftype"] else None
    if pinned is not None and record["sha256"] != pinned:
        raise ModelError(f"{path.name} has SHA-256 {record['sha256']}, the manifest pins {pinned}: the conversion is not "
                         "reproducing the recorded file (see output_sha256 in models/README.md)")
    with open(record_path, "w", encoding="utf-8") as f:
        json.dump(record, f, indent=2, sort_keys=True)
        f.write("\n")
    if not quiet:
        print(f"{entry['id']}: wrote {path.name}, {record['size']} bytes, sha256 {record['sha256']}")
    return path


def ensure(entry: dict, src: Path | None, out: Path, llama_src: Path, ftype: str) -> Path | None:
    """The output for (entry, ftype): the existing one when current, else a fresh conversion.

    Returns None when a conversion is needed and the checkpoint is not on this machine.
    """
    if is_current(entry, out, llama_src, ftype):
        return output_path(entry, out, ftype)
    src_dir = source_dir(entry, src)
    if src_dir is None:
        return None
    return convert(entry, src_dir, out, llama_src, ftype, quiet=True)


# The packages the converters import (native/requirements.txt pins them). They are looked for before
# anything is converted, so that an environment without them says how to fix it instead of failing
# halfway through a driver with an ImportError.
_PACKAGES = ("numpy", "torch", "transformers", "tokenizers", "sentencepiece", "google.protobuf", "safetensors", "yaml")

# The exit status when packages are missing; the Gradle build (llama/build.gradle.kts) recognises it.
EXIT_MISSING_PACKAGES = 3


def missing_packages() -> list[str]:
    """The packages of _PACKAGES this Python cannot import, found without importing them."""
    missing = []
    for name in _PACKAGES:
        try:
            found = importlib.util.find_spec(name) is not None
        except ModuleNotFoundError:  # a parent package (google) is missing
            found = False
        if not found:
            missing.append(name)
    return missing


def _env_path(name: str) -> Path | None:
    value = os.environ.get(name)
    return Path(value) if value else None


def main() -> int:
    parser = argparse.ArgumentParser(description="Convert the models of manifest.json to GGUF")
    parser.add_argument("ids", nargs="*", help="model ids to convert (default: every ready model)")
    parser.add_argument("--src", type=Path, default=_env_path("PROSEPLOW_MODEL_SRC"), help="directory of the downloaded checkpoints")
    parser.add_argument("--out", type=Path, default=_env_path("PROSEPLOW_MODEL_OUT"), help="directory for the GGUF files")
    parser.add_argument("--llama-src", type=Path, default=_env_path("PROSEPLOW_LLAMA_SRC"), help="the patched llama.cpp copy")
    parser.add_argument("--also-f32", action="store_true", help="also write the F32 file of every ported architecture, used by the validation tests")
    parser.add_argument("--available", action="store_true", help="skip models whose checkpoint is not under --src instead of failing")
    parser.add_argument("--force", action="store_true", help="convert even when the output is current")
    parser.add_argument("--list", action="store_true", help="list the manifest and exit")
    args = parser.parse_args()

    try:
        entries = load_manifest()
        if args.list:
            for e in entries:
                print(f"{e['id']:22} {e['status']:8} {e.get('purpose', ''):10} {e['repository']}@{(e.get('revision') or 'unpinned')[:12]}  ->  {e.get('output') or '-'}")
            return 0
        for name, value in (("--src / PROSEPLOW_MODEL_SRC", args.src), ("--out / PROSEPLOW_MODEL_OUT", args.out),
                            ("--llama-src / PROSEPLOW_LLAMA_SRC", args.llama_src)):
            if value is None:
                parser.error(f"{name} is not set")
        unknown = set(args.ids) - {e["id"] for e in entries}
        if unknown:
            parser.error(f"not in the manifest: {', '.join(sorted(unknown))}")
        missing = missing_packages()
        if missing:
            print(f"convert_models: this Python ({sys.executable}) lacks {', '.join(missing)}; "
                  f"run `pip install -r native/requirements.txt` in its virtualenv", file=sys.stderr)
            return EXIT_MISSING_PACKAGES

        failed = False
        for entry in entries:
            if args.ids and entry["id"] not in args.ids:
                continue
            if entry["status"] != "ready":
                print(f"{entry['id']}: skipped, {entry['status']} ({entry.get('note', '')})")
                failed = failed or bool(args.ids)
                continue
            src_dir = source_dir(entry, args.src)
            if src_dir is None:
                print(f"{entry['id']}: the checkpoint {entry['repository']} at revision {entry['revision']} is not under {args.src}",
                      file=sys.stdout if args.available else sys.stderr)
                failed = failed or not args.available
                continue
            for ftype in ftypes(entry, args.also_f32):
                if not args.force and is_current(entry, args.out, args.llama_src, ftype):
                    print(f"{entry['id']}: {output_path(entry, args.out, ftype).name} is current")
                else:
                    convert(entry, src_dir, args.out, args.llama_src, ftype)
        return 1 if failed else 0
    except ModelError as e:
        print(f"convert_models: {e}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
