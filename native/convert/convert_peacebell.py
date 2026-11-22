#!/usr/bin/env python3
"""Converts a peacebell-family checkpoint directory to one GGUF file.

    convert_peacebell.py [--llama-src DIR] --outtype {f32,f16,bf16} --outfile FILE [--name NAME] MODEL_DIR

MODEL_DIR holds config.json, model.safetensors and the tokenizer file(s). The conversion code is
conversion/peacebell.py inside the patched llama.cpp copy (native/tools/prepare-llama-src.sh),
whose gguf-py knows the architecture; --llama-src or PROSEPLOW_LLAMA_SRC says where that copy is.

Shipping files are f16. f32 exists to validate the port bit for bit against the stored weights;
bf16 only to keep a measurement reproducible, it is not a supported shipping format.

This driver exists instead of upstream's convert_hf_to_gguf.py for two reasons. Upstream's driver
first asks the transformers library to interpret config.json, which for a checkpoint with custom
code means a prompt or a fallback; here config.json is simply read. And the output must be a
function of the input files and the arguments only, so the model's name is an argument rather
than something derived from the directory's name.
"""
from __future__ import annotations

import argparse
import json
import logging
import os
import sys

from pathlib import Path


def main() -> int:
    parser = argparse.ArgumentParser(description="Convert a peacebell-family checkpoint to GGUF")
    parser.add_argument("model", type=Path, help="directory with config.json, model.safetensors and the tokenizer")
    parser.add_argument("--llama-src", type=Path, default=os.environ.get("PROSEPLOW_LLAMA_SRC"),
                        help="the patched llama.cpp copy (default: $PROSEPLOW_LLAMA_SRC)")
    parser.add_argument("--outtype", choices=("f32", "f16", "bf16"), required=True)
    parser.add_argument("--outfile", type=Path, required=True)
    parser.add_argument("--name", default=None, help="general.name of the result (default: peacebell)")
    parser.add_argument("--verbose", action="store_true")
    args = parser.parse_args()

    if args.llama_src is None:
        parser.error("no patched llama.cpp copy: pass --llama-src or set PROSEPLOW_LLAMA_SRC")
    llama_src = Path(args.llama_src).resolve()
    if not (llama_src / "conversion" / "peacebell.py").is_file():
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
    from conversion.peacebell import PeacebellConversionError, PeacebellModel

    file_types = {
        "f32": gguf.LlamaFileType.ALL_F32,
        "f16": gguf.LlamaFileType.MOSTLY_F16,
        "bf16": gguf.LlamaFileType.MOSTLY_BF16,
    }

    with open(args.model / "config.json", encoding="utf-8") as f:
        hparams = json.load(f)

    # Written under a temporary name and renamed at the end: a reader never sees half a file, and
    # a failed conversion leaves no file that looks finished.
    out = args.outfile.resolve()
    out.parent.mkdir(parents=True, exist_ok=True)
    partial = out.with_name(out.name + ".partial")
    try:
        with torch.inference_mode():
            model = PeacebellModel(args.model, file_types[args.outtype], partial, hparams=hparams, model_name=args.name)
            model.write()
        os.replace(partial, out)
    except PeacebellConversionError as e:
        print(f"convert_peacebell: {args.model}: {e}", file=sys.stderr)
        return 1
    finally:
        partial.unlink(missing_ok=True)

    print(f"convert_peacebell: wrote {out} ({out.stat().st_size} bytes, {args.outtype})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
