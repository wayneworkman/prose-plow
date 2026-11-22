"""Loads the PyTorch reference implementation of a Boris-2 checkpoint, CPU and fp32.

The reference is the modelling code that ships inside the checkpoint directory
(configuration_boris2.py, modeling_boris2.py). It is imported from there directly rather than
through transformers' trust_remote_code, which would ask for confirmation and copy the modules
into a cache in the home directory; nothing is written next to the checkpoint either.

fp32 on the CPU is the reference precision for the port: the checkpoint is stored in fp32, and
the reference then computes everything in fp32.

The reference has no cache: every step of a generation is a forward pass over the whole
prefix (see chatml_text.greedy_reply), which is what the port's incremental decoding has to
reproduce. Its chat format is plain-text ChatML (chatml_text.py).
"""
from __future__ import annotations

import importlib.util
import json
import os
import sys
import types

from pathlib import Path
from typing import Any

sys.dont_write_bytecode = True
os.environ["CUDA_VISIBLE_DEVICES"] = ""
# The reference is read from local files only; the libraries must fail rather than fetch.
os.environ["HF_HUB_OFFLINE"] = "1"
os.environ["TRANSFORMERS_OFFLINE"] = "1"

import torch  # noqa: E402

from safetensors.torch import load_file  # noqa: E402

torch.set_grad_enabled(False)
torch.set_num_threads(max(1, int(os.environ.get("PROSEPLOW_THREADS", "4"))))

# Keys of config.json that describe the file, not the model.
_NOT_MODEL_ARGS = ("architectures", "auto_map", "model_type", "transformers_version", "dtype")


def is_boris2(model_dir: Path) -> bool:
    with open(model_dir / "config.json", encoding="utf-8") as f:
        return json.load(f).get("model_type") == "boris2"


def _import_reference_code(code_dir: Path) -> dict[str, Any]:
    """Imports the two reference modules as a synthetic package so their relative imports resolve."""
    package_name = "boris2_reference"
    package = types.ModuleType(package_name)
    package.__path__ = [str(code_dir)]
    sys.modules[package_name] = package
    modules = {}
    for name in ("configuration_boris2", "modeling_boris2"):
        spec = importlib.util.spec_from_file_location(f"{package_name}.{name}", code_dir / f"{name}.py")
        assert spec is not None and spec.loader is not None
        module = importlib.util.module_from_spec(spec)
        sys.modules[spec.name] = module
        spec.loader.exec_module(module)
        modules[name] = module
    return modules


def load_reference(model_dir: Path, ngram_lookup: bool = True):
    """Returns (model, config) for the checkpoint in model_dir.

    With ngram_lookup false every token gets the miss row of the n-gram table, which is what a
    GGUF file without the lookup arrays computes: the rest of the model in isolation.
    """
    modules = _import_reference_code(model_dir)
    with open(model_dir / "config.json", encoding="utf-8") as f:
        config_json = json.load(f)
    config = modules["configuration_boris2"].Boris2Config(**{k: v for k, v in config_json.items() if k not in _NOT_MODEL_ARGS})
    model = modules["modeling_boris2"].Boris2ForCausalLM(config)
    state = load_file(str(model_dir / "model.safetensors"))
    missing, unexpected = model.load_state_dict(state, strict=False)
    # The head is tied to the embedding and therefore absent from the file; nothing else may be.
    assert unexpected == [] and set(missing) <= {"lm_head.weight"}, (missing, unexpected)
    model.lm_head.weight = model.model.embed_tokens.weight
    if not ngram_lookup:
        ngram = model.model.ngram
        ngram._lookup = lambda keys, rows, q: torch.full_like(q, ngram.oov)
    return model.to(torch.float32).eval(), config
