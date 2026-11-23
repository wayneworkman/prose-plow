"""Plain-text ChatML as the reference libraries produce it: rendering, tokenising, replying.

For the models whose GGUF says "<architecture>.chat.format = chatml-text" the chat markers are
not in the vocabulary. A conversation is the checkpoint's chat template rendered to a string
and tokenised in one call, and a reply ends where the decoded text first contains the end
marker, wherever the token boundaries fall.

The renderer is transformers' own (apply_chat_template) and the tokenizer is the tokenizers
library on the checkpoint's tokenizer.json: nothing here re-implements what the port is
compared with.
"""
from __future__ import annotations

import json
import os
import sys

from pathlib import Path

sys.dont_write_bytecode = True
# Local files only; the libraries must fail rather than fetch.
os.environ["HF_HUB_OFFLINE"] = "1"
os.environ["TRANSFORMERS_OFFLINE"] = "1"
os.environ.setdefault("TOKENIZERS_PARALLELISM", "false")

import torch  # noqa: E402

END_MARKER = "<|im_end|>"


def has_chat_template(model_dir: Path) -> bool:
    config = model_dir / "tokenizer_config.json"
    if not (config.is_file() and (model_dir / "tokenizer.json").is_file()):
        return False
    with open(config, encoding="utf-8") as f:
        return bool(json.load(f).get("chat_template"))


class ChatTokenizer:
    """The reference tokenizer and chat template of a checkpoint directory.

    The tokenizer file is opened directly: AutoTokenizer would look at config.json, find custom
    code and ask whether to run it.
    """

    def __init__(self, model_dir: Path):
        from tokenizers import Tokenizer
        from transformers import PreTrainedTokenizerFast

        with open(model_dir / "tokenizer_config.json", encoding="utf-8") as f:
            config = json.load(f)
        self._tokenizer = Tokenizer.from_file(str(model_dir / "tokenizer.json"))
        self._fast = PreTrainedTokenizerFast(tokenizer_file=str(model_dir / "tokenizer.json"),
                                             eos_token=config["eos_token"], pad_token=config["pad_token"])
        self._fast.chat_template = config["chat_template"]

    def render(self, messages: list[tuple[str, str]], add_generation_prompt: bool) -> str:
        """The conversation as text, by transformers' own template renderer."""
        return self._fast.apply_chat_template([{"role": role, "content": content} for role, content in messages],
                                              tokenize=False, add_generation_prompt=add_generation_prompt)

    def encode(self, text: str) -> list[int]:
        return self._tokenizer.encode(text, add_special_tokens=False).ids

    def decode(self, ids: list[int]) -> str:
        return self._tokenizer.decode(ids, skip_special_tokens=False)


def greedy_reply(model, tokenizer: ChatTokenizer, prompt: list[int], eos: int, max_new_tokens: int,
                 banned: list[int] | None = None) -> tuple[list[int], str | None]:
    """Greedy continuation by recomputing the whole prefix for every token, as the reference generates.

    Returns the generated tokens and the reply text. Generation stops after the token that
    completes the end marker in the decoded text; those tokens are part of the result, because
    the marker has no token boundary of its own (its first character can merge with the text
    before it). It also stops before an end-of-text token, which is never part of the result,
    and at max_new_tokens. The text is None when the reply was cut by max_new_tokens. A banned
    token is never chosen: the tokens a checkpoint's generation settings suppress.
    """
    ids = list(prompt)
    out: list[int] = []
    for _ in range(max_new_tokens):
        logits = model(input_ids=torch.tensor([ids])).logits[0, -1].float()
        if banned:
            logits[banned] = float("-inf")
        token = int(torch.argmax(logits))
        if token == eos:
            return out, tokenizer.decode(out)
        out.append(token)
        ids.append(token)
        text = tokenizer.decode(out)
        if END_MARKER in text:
            return out, text.split(END_MARKER, 1)[0]
    return out, None
