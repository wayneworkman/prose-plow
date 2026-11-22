#!/usr/bin/env python3
"""Writes a seeded random checkpoint of the byte-level BPE member of the peacebell family.

    make_random_checkpoint.py --code-dir DIR --tokenizer-json FILE --out DIR

That member (960 wide, 15 heads, 11 layers, a 32,768-token byte-level BPE, a training pad token
that must never be sampled) is not trained yet. Until it is, this stand-in lets the converter
branch, the C++ architecture and the sampler mask be tested against the PyTorch reference with
the real shape. --code-dir is a checkpoint directory of the family whose reference modules are
used to build the model; --tokenizer-json is a byte-level BPE tokenizer of the expected kind.

Default initialisation would make the test blind: the ReZero scalars start at zero and the norm
weights at one, so the blocks would add exactly nothing and a broken graph would still match.
The scalars and the norm weights are therefore randomised and the matrices scaled up, which
makes the blocks change the logits by double digits.
"""
from __future__ import annotations

import argparse
import json
import shutil
import sys

from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import torch  # noqa: E402

from safetensors.torch import save_file  # noqa: E402

import reference  # noqa: E402

# The documented shape of the family member this checkpoint stands in for.
CONFIG = {
    "vocab_size": 32768,
    "n_embd": 960,
    "n_head": 15,
    "n_layer": 11,
    "block_size": 32768,
    "train_pad_id": 188,
    "im_start_id": None,
    "im_end_id": None,
    "unk_pad_id": None,
    "bos_token_id": None,
    "eos_token_id": 0,
    "pad_token_id": 0,
}
EXPECTED_PARAMETERS = 153_130_582

# ChatML as plain text, the template these checkpoints are trained with.
CHAT_TEMPLATE = ("{% for m in messages %}<|im_start|>{{ m['role'] }}\n{{ m['content'] | trim }}<|im_end|>\n{% endfor %}"
                 "{% if add_generation_prompt %}<|im_start|>assistant\n{% endif %}")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--code-dir", type=Path, required=True)
    parser.add_argument("--tokenizer-json", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()

    modules = reference.import_reference_code(args.code_dir)
    config = modules["configuration_gpt"].GPTConfig(**CONFIG)
    torch.manual_seed(1234)
    model = modules["modeling_gpt"].GPTForCausalLM(config).eval()
    n_parameters = sum(p.numel() for p in model.parameters())
    assert n_parameters == EXPECTED_PARAMETERS, n_parameters

    generator = torch.Generator().manual_seed(99)
    with torch.no_grad():
        for name, parameter in model.named_parameters():
            if name.endswith("_scale"):
                parameter.copy_(torch.empty(1).uniform_(0.05, 0.3, generator=generator))
            elif "ln_" in name:
                parameter.copy_(torch.empty_like(parameter).uniform_(0.5, 1.5, generator=generator))
            elif parameter.ndim == 2 and "wte" not in name:
                parameter.mul_(3.0)  # sharper attention patterns than the tiny default initialisation gives

    args.out.mkdir(parents=True, exist_ok=True)
    state = {k: v.detach().contiguous() for k, v in model.state_dict().items() if k != "lm_head.weight"}
    save_file(state, str(args.out / "model.safetensors"))
    config_json = dict(CONFIG, architectures=["GPTForCausalLM"], model_type="gpt", dtype="float32", dropout=0,
                       rezero_scale_init=0.01, rms_norm_eps=1e-05, rope_theta=1000000.0, tie_weights=True, tie_word_embeddings=True)
    with open(args.out / "config.json", "w", encoding="utf-8") as f:
        json.dump(config_json, f, indent=2, sort_keys=True)
    with open(args.out / "generation_config.json", "w", encoding="utf-8") as f:
        json.dump({"eos_token_id": 0, "pad_token_id": 0, "suppress_tokens": [CONFIG["train_pad_id"]]}, f, indent=2)
    with open(args.out / "tokenizer_config.json", "w", encoding="utf-8") as f:
        json.dump({"chat_template": CHAT_TEMPLATE, "eos_token": "<|endoftext|>", "pad_token": "<|endoftext|>"}, f, indent=2)
    shutil.copyfile(args.tokenizer_json, args.out / "tokenizer.json")
    print(f"make_random_checkpoint: wrote {args.out}: {n_parameters} parameters")
    return 0


if __name__ == "__main__":
    sys.exit(main())
