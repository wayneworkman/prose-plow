#!/usr/bin/env python3
"""Converts a checkpoint of an architecture that upstream llama.cpp already supports.

    convert_upstream.py [--llama-src DIR] --outtype {f32,f16} --outfile FILE --name NAME
                        --file NAME [--file NAME ...] [--converter-arg=ARG ...] MODEL_DIR

The conversion code is upstream's own convert_hf_to_gguf.py, taken from the patched llama.cpp
copy (native/tools/prepare-llama-src.sh) so that every model of the app is converted by one
tree; --llama-src or PROSEPLOW_LLAMA_SRC says where that copy is. The result is text only:
upstream's converter leaves the vision tower of a multimodal checkpoint out unless asked for it.

Upstream's converter is not a function of the weights alone, and this driver exists to make it
one:

- It derives general.name, general.basename and general.size_label from the NAME OF THE
  DIRECTORY it is given, so the same checkpoint converts to different bytes from a hub cache
  (".../snapshots/<revision>") and from a directory called after the model. Here it always
  sees a directory called NAME.
- It reads whatever it finds next to the weights (the model card, tokenizer files, optional
  configuration files). Here it sees exactly the files named with --file, as links in a staging
  directory: a file that is not pinned by hash in the manifest cannot change the output.
- It may consult the Hugging Face cache of the user. Here that cache is an empty directory
  inside the staging directory and the libraries are told to stay offline.

--converter-arg passes one argument through to upstream's converter (for example --no-mtp,
which leaves out a speculative-decoding head the app does not use).
"""
from __future__ import annotations

import argparse
import os
import shutil
import subprocess
import sys
import tempfile

from pathlib import Path


def main() -> int:
    parser = argparse.ArgumentParser(description="Convert a stock-architecture checkpoint with upstream's converter")
    parser.add_argument("model", type=Path, help="directory that holds the checkpoint files")
    parser.add_argument("--llama-src", type=Path, default=os.environ.get("PROSEPLOW_LLAMA_SRC"),
                        help="the patched llama.cpp copy (default: $PROSEPLOW_LLAMA_SRC)")
    parser.add_argument("--outtype", choices=("f32", "f16"), required=True)
    parser.add_argument("--outfile", type=Path, required=True)
    parser.add_argument("--name", required=True, help="the directory name upstream's converter sees; it becomes general.name")
    parser.add_argument("--file", action="append", default=[], dest="files", metavar="NAME",
                        help="a file of the checkpoint the converter may read (repeat for each)")
    parser.add_argument("--converter-arg", action="append", default=[], dest="converter_args", metavar="ARG",
                        help="an argument passed through to upstream's converter (repeat for each)")
    parser.add_argument("--verbose", action="store_true")
    args = parser.parse_args()

    if args.llama_src is None:
        parser.error("no patched llama.cpp copy: pass --llama-src or set PROSEPLOW_LLAMA_SRC")
    llama_src = Path(args.llama_src).resolve()
    converter = llama_src / "convert_hf_to_gguf.py"
    if not converter.is_file() or not (llama_src / ".proseplow-prepared").is_file():
        parser.error(f"{llama_src} is not a prepared copy (run native/tools/prepare-llama-src.sh)")
    if not args.model.is_dir():
        parser.error(f"{args.model} is not a directory")
    if not args.files:
        parser.error("no --file: name every file the converter may read")
    if args.name in ("", ".", "..") or "/" in args.name or os.sep in args.name:
        parser.error("--name must be a plain directory name")
    for name in args.files:
        if Path(name).name != name or not (args.model / name).is_file():
            parser.error(f"{name} is not a file directly inside {args.model}")

    out = args.outfile.resolve()
    out.parent.mkdir(parents=True, exist_ok=True)
    # Everything temporary lives in one directory next to the output, with a name of its own, so
    # that two conversions never share a file and a failed one leaves nothing that looks finished.
    scratch = Path(tempfile.mkdtemp(prefix=f".{out.name}.", dir=out.parent))
    try:
        staged = scratch / args.name
        staged.mkdir()
        for name in args.files:
            os.symlink((args.model / name).resolve(), staged / name)
        hf_home = scratch / "hf-home"
        hf_home.mkdir()
        partial = scratch / "partial.gguf"

        env = dict(os.environ)
        env.pop("NO_LOCAL_GGUF", None)  # the copy's own gguf-py, never an installed one
        env.update({
            "PYTHONDONTWRITEBYTECODE": "1",
            "CUDA_VISIBLE_DEVICES": "",
            "HF_HUB_OFFLINE": "1",
            "TRANSFORMERS_OFFLINE": "1",
            "HF_HOME": str(hf_home),
            "TQDM_DISABLE": "1",
            "TOKENIZERS_PARALLELISM": "false",
        })
        command = [sys.executable, str(converter), str(staged), "--outtype", args.outtype, "--outfile", str(partial),
                   *args.converter_args]
        if args.verbose:
            command.append("--verbose")
        result = subprocess.run(command, env=env, stdout=None if args.verbose else subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        if result.returncode != 0 or not partial.is_file():
            if result.stdout:
                # Upstream logs every tensor; the reason for a failure is at the end.
                sys.stderr.write("\n".join(result.stdout.splitlines()[-40:]) + "\n")
            print(f"convert_upstream: {args.model}: upstream's converter exited with {result.returncode}", file=sys.stderr)
            return 1
        os.replace(partial, out)
    finally:
        shutil.rmtree(scratch, ignore_errors=True)

    print(f"convert_upstream: wrote {out} ({out.stat().st_size} bytes, {args.outtype})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
