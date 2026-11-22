#!/usr/bin/env python3
"""Writes the logit reference of one checkpoint from its PyTorch reference model.

    gen_logit_ref.py --model-dir DIR --out FILE [--full] [--code-dir DIR] [--random-sequences N] [--no-ngram-lookup]

The architecture follows from config.json.

peacebell: for a checkpoint with the SentencePiece tokenizer the sequences are fixed
conversations: the ChatML prompt, the reference's greedy reply and the end marker. For a
checkpoint without a usable chat tokenizer (the seeded random one that stands in for a family
member that is not trained yet) --random-sequences N uses N seeded random token sequences
instead.

Boris-2: fixed conversations rendered by the chat template to plain text, each followed by the
reference's greedy reply. The reference has no cache, so that reply is made by recomputing the
whole prefix for every token; it ends with the tokens that spell the end marker, unless it
reached the length limit first. --no-ngram-lookup gives every token the miss row of the n-gram
table: the reference for a GGUF file without the lookup arrays, which isolates Canon and the
memory it needs from the n-gram input.

Without --full the file carries the top 20 logits at every position and the whole logit vector
at the last prompt position of each sequence: small enough to commit. With --full it carries
the whole vector everywhere, which the 16-bit tests need for a KL divergence; that file is
generated on demand and never committed.

The output is a function of the checkpoint and of the PyTorch build: regenerate the committed
files only when a checkpoint changes, and keep the exact package versions of
native/requirements.txt when you do.
"""
from __future__ import annotations

import argparse
import json
import sys

from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import numpy as np  # noqa: E402
import torch  # noqa: E402

import chatml_text  # noqa: E402
import formats  # noqa: E402
import reference  # noqa: E402
import reference_boris2  # noqa: E402

TOP_K = 20
MAX_NEW_TOKENS = 96

# Short prompts that exercise the chat layout: one turn, a system message, several turns, outer
# spaces and an inner newline in the content.
CONVERSATIONS = [
    [("user", "When did World War II end?")],
    [("user", "Who are you?")],
    [("system", "Answer briefly."), ("user", "Who was Erwin Rommel?")],
    [("user", "Tell me about the Battle of Midway."),
     ("assistant", "The Battle of Midway was fought in June 1942."),
     ("user", "Explain that again, in more detail.")],
    [("user", "  Explain that again,\nwith two lines.  ")],
]

# The same for Boris-2, whose template trims the content: outer whitespace of several kinds
# must vanish, inner newlines must stay. The short questions get replies that end with the end
# marker inside the length limit, the open ones run into the limit.
BORIS2_CONVERSATIONS = [
    [("user", "What is the water cycle?")],
    [("system", "Be brief."), ("user", "  What is 2 + 2?  "), ("assistant", "4.\n"), ("user", "And 3 + 3?")],
    [("user", "Write a haiku.\nAbout rain."), ("assistant", "Soft rain on the roof"), ("user", "Another.")],
    [("user", "What is the capital of France?")],
    [("system", "Answer in one word."), ("user", "\u00a0Is water wet?\t\n")],
]


def greedy(model, prompt: list[int], banned: list[int], stop: int | None, max_new_tokens: int) -> list[int]:
    """Greedy continuation with a KV cache: banned ids are never chosen, stop ends the reply and is not returned."""
    out: list[int] = []
    past = None
    cur = torch.tensor([prompt])
    for _ in range(max_new_tokens):
        result = model(cur, past_key_values=past, use_cache=True)
        past = result.past_key_values
        logits = result.logits[0, -1, :].float()
        logits[banned] = float("-inf")
        token = int(torch.argmax(logits))
        if token == stop:
            break
        out.append(token)
        cur = torch.tensor([[token]])
    return out


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--model-dir", type=Path, required=True)
    parser.add_argument("--code-dir", type=Path, default=None, help="directory of the reference modules when the checkpoint has none")
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--full", action="store_true", help="store the whole logit vector at every position")
    parser.add_argument("--random-sequences", type=int, default=0, help="use this many seeded random sequences instead of conversations")
    parser.add_argument("--seed", type=int, default=99)
    parser.add_argument("--no-ngram-lookup", action="store_true", help="Boris-2 only: every token gets the miss row of the n-gram table")
    args = parser.parse_args()

    boris2 = reference_boris2.is_boris2(args.model_dir)
    if boris2:
        model, config = reference_boris2.load_reference(args.model_dir, ngram_lookup=not args.no_ngram_lookup)
    else:
        model, config = reference.load_reference(args.model_dir, args.code_dir)
    n_vocab = config.vocab_size

    prompts: list[list[int]] = []
    replies: list[list[int]] = []
    tails: list[list[int]] = []
    if boris2:
        tokenizer = chatml_text.ChatTokenizer(args.model_dir)
        eos = config.eos_token_id
        for conversation in BORIS2_CONVERSATIONS:
            prompt = tokenizer.encode(tokenizer.render(conversation, add_generation_prompt=True))
            reply, _ = chatml_text.greedy_reply(model, tokenizer, prompt, eos, MAX_NEW_TOKENS)
            # One conversation is one document for the port: the end-of-text token is never fed.
            assert eos not in prompt + reply
            prompts.append(prompt)
            replies.append(reply)
            tails.append([])
    elif args.random_sequences > 0:
        # Random ids exercise every embedding row class, including the training pad, which may
        # appear in the input: only its output logit is masked.
        with open(args.model_dir / "config.json", encoding="utf-8") as f:
            raw_config = json.load(f)
        banned = [raw_config["train_pad_id"]]
        generator = torch.Generator().manual_seed(args.seed)
        # The sequences share their first tokens, as conversations share their opening, so that
        # the rewind test has a common prefix to cut back to.
        shared = torch.randint(1, n_vocab, (8,), generator=generator).tolist()
        for _ in range(args.random_sequences):
            prompt = shared + torch.randint(1, n_vocab, (32,), generator=generator).tolist()
            prompt[5] = banned[0]
            prompts.append(prompt)
            replies.append(greedy(model, prompt, banned, config.eos_token_id, 24))
            tails.append(torch.randint(1, n_vocab, (16,), generator=generator).tolist())
    else:
        import sentencepiece

        sp = sentencepiece.SentencePieceProcessor(model_file=str(args.model_dir / "sentencepiece.model"))
        start, end, banned = config.im_start_id, config.im_end_id, [config.unk_pad_id]
        for conversation in CONVERSATIONS:
            prompt: list[int] = []
            for role, content in conversation:
                prompt += [start] + sp.encode(role + "\n") + sp.encode(content) + [end]
            prompt += [start] + sp.encode("assistant\n")
            reply = greedy(model, prompt, banned, end, MAX_NEW_TOKENS)
            # The reference's own generation loop must agree with the plain loop above.
            assert reply == model.chat_generate(torch.tensor(prompt), max_new_tokens=MAX_NEW_TOKENS, temperature=0.0)
            prompts.append(prompt)
            replies.append(reply)
            tails.append([end])

    sequences = []
    for prompt, reply, tail in zip(prompts, replies, tails):
        ids = prompt + reply + tail
        logits = reference.all_logits(model, ids)
        top_val, top_idx = logits.topk(TOP_K, dim=-1)
        full_pos = list(range(len(ids))) if args.full else [len(prompt) - 1]
        sequences.append(formats.LogitSequence(
            ids=np.asarray(ids, dtype=np.int32),
            n_prompt=len(prompt),
            greedy=np.asarray(reply, dtype=np.int32),
            top_idx=top_idx.numpy().astype(np.int32),
            top_val=top_val.numpy().astype(np.float32),
            full_pos=np.asarray(full_pos, dtype=np.int32),
            full=logits[full_pos].numpy().astype(np.float32),
        ))

    formats.write_logit_reference(args.out, n_vocab, TOP_K, sequences)
    n_positions = sum(len(s.ids) for s in sequences)
    print(f"gen_logit_ref: wrote {args.out}: {len(sequences)} sequences, {n_positions} positions, "
          f"{args.out.stat().st_size} bytes, torch {torch.__version__}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
