#!/usr/bin/env python3
"""Converts a Boris-2 checkpoint directory to one GGUF file.

    convert_boris2.py [--llama-src DIR] --outtype {f32,f16} --outfile FILE [--name NAME] MODEL_DIR

MODEL_DIR holds config.json, model.safetensors, tokenizer.json and tokenizer_config.json. The
conversion code is conversion/boris2.py inside the patched llama.cpp copy
(native/tools/prepare-llama-src.sh), whose gguf-py knows the architecture; --llama-src or
PROSEPLOW_LLAMA_SRC says where that copy is.

Shipping files are f16: matrices and the n-gram table in half precision, norm weights, Canon
kernels and the n-gram projection in float32, the n-gram lookup keys as integers. f32 exists to
validate the port against the stored weights and is never shipped.

This driver exists instead of upstream's convert_hf_to_gguf.py for the same two reasons as the
peacebell one. Upstream's driver first asks the transformers library to interpret config.json,
which for a checkpoint with custom code means a prompt or a fallback; here config.json is simply
read. And the output must be a function of the input files and the arguments only, so the
model's name is an argument rather than something derived from the directory's name.
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
    parser = argparse.ArgumentParser(description="Convert a Boris-2 checkpoint to GGUF")
    parser.add_argument("model", type=Path, help="directory with config.json, model.safetensors and the tokenizer files")
    parser.add_argument("--llama-src", type=Path, default=os.environ.get("PROSEPLOW_LLAMA_SRC"),
                        help="the patched llama.cpp copy (default: $PROSEPLOW_LLAMA_SRC)")
    parser.add_argument("--outtype", choices=("f32", "f16"), required=True)
    parser.add_argument("--outfile", type=Path, required=True)
    parser.add_argument("--name", default=None, help="general.name of the result (default: boris2)")
    parser.add_argument("--verbose", action="store_true")
    args = parser.parse_args()

    if args.llama_src is None:
        parser.error("no patched llama.cpp copy: pass --llama-src or set PROSEPLOW_LLAMA_SRC")
    llama_src = Path(args.llama_src).resolve()
    if not (llama_src / "conversion" / "boris2.py").is_file():
        parser.error(f"{llama_src} is not a prepared copy (run native/tools/prepare-llama-src.sh)")
    if not args.model.is_dir():
        parser.error(f"{args.model} is not a directory")

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
    from conversion.boris2 import Boris2ConversionError, Boris2Model

    file_types = {
        "f32": gguf.LlamaFileType.ALL_F32,
        "f16": gguf.LlamaFileType.MOSTLY_F16,
    }

    with open(args.model / "config.json", encoding="utf-8") as f:
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
            model = Boris2Model(args.model, file_types[args.outtype], partial, hparams=hparams, model_name=args.name)
            model.write()
        # mkstemp creates the file for its owner only; the result gets the usual permissions.
        umask = os.umask(0)
        os.umask(umask)
        os.chmod(partial, 0o666 & ~umask)
        os.replace(partial, out)
    except Boris2ConversionError as e:
        print(f"convert_boris2: {args.model}: {e}", file=sys.stderr)
        return 1
    finally:
        partial.unlink(missing_ok=True)

    print(f"convert_boris2: wrote {out} ({out.stat().st_size} bytes, {args.outtype})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
