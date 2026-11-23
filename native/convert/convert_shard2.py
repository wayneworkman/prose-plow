#!/usr/bin/env python3
"""Converts a Shard-2 checkpoint directory to one GGUF file.

    convert_shard2.py [--llama-src DIR] --outtype {f32,f16} --outfile FILE [--name NAME] MODEL_DIR

MODEL_DIR holds config.json, Shard-2.pt and tokenizer.json. The conversion code is
conversion/shard2.py inside the patched llama.cpp copy (native/tools/prepare-llama-src.sh),
whose gguf-py knows the architecture; --llama-src or PROSEPLOW_LLAMA_SRC says where that copy is.

Shard-2.pt is a pickle from a third party. It is loaded with
torch.load(..., weights_only=True) and in no other way, which constructs tensors and plain
containers only and refuses everything else; nothing else in MODEL_DIR is imported or run,
least of all the infer.py that ships with the checkpoint (its loader unpickles without that
restriction). MODEL_DIR is treated as data: this script must not be started from inside it,
and it removes the script directory's neighbours and the current directory from the module
search path before anything is imported.

Shipping files are f16: the matrices in half precision, every norm weight in float32. f32
exists to validate the port against the stored weights and is never shipped. The checkpoint's
`indexer.gate`, one number the model's forward pass never reads, is not written.

This driver exists instead of upstream's convert_hf_to_gguf.py for the reasons of the other two:
upstream's driver first asks the transformers library to interpret config.json, and the output
must be a function of the input files and the arguments only, so the model's name is an
argument rather than something derived from the directory's name. It has a third reason of its
own: upstream's driver looks for model.safetensors or pytorch_model.bin, not for this file.
"""
from __future__ import annotations

import argparse
import json
import logging
import os
import sys
import tempfile

from pathlib import Path


def main() -> int:
    parser = argparse.ArgumentParser(description="Convert a Shard-2 checkpoint to GGUF")
    parser.add_argument("model", type=Path, help="directory with config.json, Shard-2.pt and tokenizer.json")
    parser.add_argument("--llama-src", type=Path, default=os.environ.get("PROSEPLOW_LLAMA_SRC"),
                        help="the patched llama.cpp copy (default: $PROSEPLOW_LLAMA_SRC)")
    parser.add_argument("--outtype", choices=("f32", "f16"), required=True)
    parser.add_argument("--outfile", type=Path, required=True)
    parser.add_argument("--name", default=None, help="general.name of the result (default: shard2)")
    parser.add_argument("--verbose", action="store_true")
    args = parser.parse_args()

    if args.llama_src is None:
        parser.error("no patched llama.cpp copy: pass --llama-src or set PROSEPLOW_LLAMA_SRC")
    llama_src = Path(args.llama_src).resolve()
    if not (llama_src / "conversion" / "shard2.py").is_file():
        parser.error(f"{llama_src} is not a prepared copy (run native/tools/prepare-llama-src.sh)")
    if not args.model.is_dir():
        parser.error(f"{args.model} is not a directory")

    # The checkpoint directory is untrusted data. Python puts the script's directory, or the
    # current directory, first on the module search path; neither may be that directory, or a
    # file planted next to the checkpoint could be imported in place of a library.
    model_dir = args.model.resolve()
    for entry in list(sys.path):
        if Path(entry or os.getcwd()).resolve() == model_dir:
            sys.path.remove(entry)

    # The copy's own gguf-py, never an installed one: only the patched constants know the architecture.
    sys.dont_write_bytecode = True
    os.environ.pop("NO_LOCAL_GGUF", None)
    os.environ.setdefault("TQDM_DISABLE", "1")  # the writer's progress bar is noise in a build log
    # A conversion reads local files only. The libraries the conversion framework imports must
    # fail rather than fetch if they ever think otherwise.
    os.environ["HF_HUB_OFFLINE"] = "1"
    os.environ["TRANSFORMERS_OFFLINE"] = "1"
    sys.path.insert(0, str(llama_src))
    sys.path.insert(1, str(llama_src / "gguf-py"))

    logging.basicConfig(level=logging.DEBUG if args.verbose else logging.WARNING)

    import torch

    import gguf
    from conversion.shard2 import Shard2ConversionError, Shard2Model

    file_types = {
        "f32": gguf.LlamaFileType.ALL_F32,
        "f16": gguf.LlamaFileType.MOSTLY_F16,
    }

    with open(model_dir / "config.json", encoding="utf-8") as f:
        hparams = json.load(f)

    # Written under a temporary name of its own and renamed at the end: a reader never sees half
    # a file, a failed conversion leaves no file that looks finished, and two conversions of the
    # same model at the same time cannot write into each other's file.
    out = args.outfile.resolve()
    out.parent.mkdir(parents=True, exist_ok=True)
    handle, partial_name = tempfile.mkstemp(prefix=f".{out.name}.", suffix=".partial", dir=out.parent)
    os.close(handle)
    partial = Path(partial_name)
    try:
        with torch.inference_mode():
            model = Shard2Model(model_dir, file_types[args.outtype], partial, hparams=hparams, model_name=args.name)
            model.write()
        # mkstemp creates the file for its owner only; the result gets the usual permissions.
        umask = os.umask(0)
        os.umask(umask)
        os.chmod(partial, 0o666 & ~umask)
        os.replace(partial, out)
    except Shard2ConversionError as e:
        print(f"convert_shard2: {model_dir}: {e}", file=sys.stderr)
        return 1
    finally:
        partial.unlink(missing_ok=True)

    print(f"convert_shard2: wrote {out} ({out.stat().st_size} bytes, {args.outtype})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
