"""Loads the PyTorch reference implementation of a peacebell checkpoint, CPU and fp32.

The reference is the modelling code that ships inside the checkpoint directory
(configuration_gpt.py, modeling_gpt.py). It is imported from there directly rather than through
transformers' trust_remote_code, which would copy the modules into a cache in the home directory;
nothing is written next to the checkpoint either (no bytecode).

fp32 on the CPU is the reference precision for the port: the checkpoints are stored in fp32 and
the reference normalises in fp32 in every mode.
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


def import_reference_code(code_dir: Path) -> dict[str, Any]:
    """Imports the two reference modules as a synthetic package so their relative imports resolve."""
    package_name = "peacebell_reference"
    package = types.ModuleType(package_name)
    package.__path__ = [str(code_dir)]
    sys.modules[package_name] = package
    modules = {}
    for name in ("configuration_gpt", "modeling_gpt"):
        spec = importlib.util.spec_from_file_location(f"{package_name}.{name}", code_dir / f"{name}.py")
        assert spec is not None and spec.loader is not None
        module = importlib.util.module_from_spec(spec)
        sys.modules[spec.name] = module
        spec.loader.exec_module(module)
        modules[name] = module
    return modules


def load_reference(model_dir: Path, code_dir: Path | None = None):
    """Returns (model, config) for the checkpoint in model_dir.

    code_dir is where the reference modules are read from when the checkpoint directory itself
    has none (a generated checkpoint of the same family).
    """
    modules = import_reference_code(code_dir or model_dir)
    with open(model_dir / "config.json", encoding="utf-8") as f:
        config_json = json.load(f)
    config = modules["configuration_gpt"].GPTConfig(**{k: v for k, v in config_json.items() if k not in _NOT_MODEL_ARGS})
    model = modules["modeling_gpt"].GPTForCausalLM(config)
    state = load_file(str(model_dir / "model.safetensors"))
    missing, unexpected = model.load_state_dict(state, strict=False)
    # The head is tied to the embedding and therefore absent from the file; nothing else may be.
    assert unexpected == [] and set(missing) <= {"lm_head.weight"}, (missing, unexpected)
    assert model.lm_head.weight is model.wte.weight
    return model.to(torch.float32).eval(), config


def all_logits(model, ids: list[int]) -> torch.Tensor:
    """The logits at every position of one sequence, [n_tokens, n_vocab], float32."""
    return model(input_ids=torch.tensor([ids])).logits[0].float()
