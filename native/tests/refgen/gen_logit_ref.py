#!/usr/bin/env python3
"""Writes the logit reference of one checkpoint from its PyTorch reference model.

    gen_logit_ref.py --model-dir DIR --out FILE [--full] [--code-dir DIR] [--random-sequences N] [--no-ngram-lookup]
    gen_logit_ref.py --model-dir DIR --out FILE [--full] [--long] [--ids-from FILE [--tile N]]      (Shard-2)

The architecture follows from config.json.

peacebell: for a checkpoint with the SentencePiece tokenizer the sequences are fixed
conversations: the ChatML prompt, the reference's greedy reply and the end marker. For a
checkpoint with the byte-level BPE they are, when it has a chat template, the conversations
below rendered by that template to plain text, each followed by the reference's greedy reply
(which ends with the tokens that spell the end marker, or before an end-of-text token, or at
the length limit); and when it has none, because it continues text, five prompts followed by
the reference's greedy continuation. The tokens such a checkpoint must never sample are banned
in both. --random-sequences N uses N seeded random token sequences instead of text, for a
seeded random checkpoint whose replies would say nothing. --code-dir names the checkpoint
directory whose reference modules build the model, when the checkpoint's own are not to be run.

Boris-2: fixed conversations rendered by the chat template to plain text, each followed by the
reference's greedy reply. The reference has no cache, so that reply is made by recomputing the
whole prefix for every token; it ends with the tokens that spell the end marker, unless it
reached the length limit first. --no-ngram-lookup gives every token the miss row of the n-gram
table: the reference for a GGUF file without the lookup arrays, which isolates Canon and the
memory it needs from the n-gram input.

Shard-2 (a model that continues text; it has no chat format and no token that ends a reply):
- by default, five short prompts written for this project, each followed by the reference's
  greedy continuation, every token of it chosen from a forward pass over the whole prefix;
- with --long, sequences that are longer than the model's attention window of 256 tokens (300,
  600 and 1,500 tokens): a prompt followed by what the reference itself samples with its
  script's settings, deletion tokens included, so that the text is of the kind the model
  writes; and one short sequence in which each of the four special ids occurs as an input;
- with --ids-from FILE, the token sequences of an existing reference file with their logits
  computed afresh. That is how the on-demand files are made: sampling and greedy choices are
  made once, when a committed file is written, and never again on another machine, where a
  logit that differs in its last bit could choose another token;
- with --ids-from FILE --tile N, ONE sequence of N tokens made of the sequences of FILE one
  after the other, repeated: the input of the context-length test.
The checkpoint is a pickle and is loaded with weights_only=True (reference_shard2.py); the
script that ships with it is read as text, to check that the copied architecture still is the
script's, and never run.

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
import reference_shard2  # noqa: E402

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

# The same for the models whose ChatML is plain text (Boris-2, the byte-level BPE members of
# peacebell), whose template trims the content: outer whitespace of several kinds must vanish,
# inner newlines must stay. The short questions get replies that end with the end marker inside
# the length limit, the open ones run into the limit.
BORIS2_CONVERSATIONS = [
    [("user", "What is the water cycle?")],
    [("system", "Be brief."), ("user", "  What is 2 + 2?  "), ("assistant", "4.\n"), ("user", "And 3 + 3?")],
    [("user", "Write a haiku.\nAbout rain."), ("assistant", "Soft rain on the roof"), ("user", "Another.")],
    [("user", "What is the capital of France?")],
    [("system", "Answer in one word."), ("user", "\u00a0Is water wet?\t\n")],
]


# Prompts for a peacebell checkpoint that continues text. They begin alike, as conversations
# do, so that the rewind test has a common prefix to cut back to.
PEACEBELL_PROMPTS = [
    "The old lighthouse keeper climbed the stairs every evening, and",
    "The best way to learn a new language is to",
    "The river flooded the lower town in the spring of 1848.\n\nThe mayor",
    "The recipe needs two onions, a cup of lentils and",
    "The three largest cities of the country are",
]

# Shard-2 continues text. Prompts written for this project: prose, a date, a blank line, a list,
# outer spaces. The first is long enough to serve as "a whole prompt" in the session test.
SHARD2_PROMPTS = [
    "The old lighthouse keeper climbed the stairs every evening, and",
    "A good way to learn a new language is to",
    "In the spring of 1848 the river flooded the lower town.\n\nThe mayor",
    "Recipe: lentil soup\n\nIngredients:\n- 2 onions\n",
    "  The three largest cities of the country are",
]
# The long sequences: (prompt, total length in tokens, sampling seed). The lengths are chosen
# around the window of 256 keys: just beyond it, beyond twice it, and several times it.
SHARD2_LONG = [(SHARD2_PROMPTS[0], 300, 20261122), (SHARD2_PROMPTS[1], 600, 20261123), (SHARD2_PROMPTS[2], 1500, 20261124)]


def shard2_special_sequence(tokenizer, config) -> list[int]:
    """Ordinary text with each special id as an input: the end-of-text id, the two untrained ids and the deletion token."""
    def text(t: str) -> list[int]:
        return tokenizer.encode(t, add_special_tokens=False).ids

    bs = config.backspace_token_id
    return (text("The meeting was moved to Thursday.") + [2] + text("Dear Anna,\n\nthank you for") + [bs] + text(" your letter") + [0]
            + text(", which") + [1] + text(" arrived") + [bs, bs] + text(" came yesterday. I was glad to hear that the") + [2, 2]
            + text("Chapter 3\n\nIt had rained all") + [bs] + text(" night, and the road to the village was"))


def shard2_main(args) -> int:
    from tokenizers import Tokenizer

    script = args.model_dir / "infer.py"
    if script.is_file():
        reference_shard2.check_copy_of(script)
    model, config = reference_shard2.load_reference(args.model_dir)
    n_vocab = config.vocab_size
    end_of_text = reference_shard2.end_of_text_id(args.model_dir)

    entries: list[tuple[list[int], int, list[int]]] = []  # (ids, n_prompt, greedy)
    if args.ids_from is not None:
        file_vocab, _, stored = formats.read_logit_reference(args.ids_from)
        assert file_vocab == n_vocab, f"{args.ids_from} was made for another vocabulary"
        entries = [(s.ids.tolist(), int(s.n_prompt), s.greedy.tolist()) for s in stored]
        if args.tile > 0:
            assert args.tile <= config.max_context_len, f"--tile {args.tile} exceeds the model's context of {config.max_context_len}"
            joined = [t for ids, _, _ in entries for t in ids]
            tiled = (joined * (args.tile // len(joined) + 1))[:args.tile]
            entries = [(tiled, len(tiled), [])]
    else:
        tokenizer = Tokenizer.from_file(str(args.model_dir / "tokenizer.json"))
        if args.long:
            for text, length, seed in SHARD2_LONG:
                prompt = tokenizer.encode(text, add_special_tokens=False).ids
                stream = reference_shard2.sampled_stream(model, prompt, length - len(prompt), seed)
                entries.append((prompt + stream, len(prompt), []))
            special = shard2_special_sequence(tokenizer, config)
            entries.append((special, len(special), []))
        else:
            for text in SHARD2_PROMPTS:
                prompt = tokenizer.encode(text, add_special_tokens=False).ids
                reply = reference_shard2.greedy_reply(model, prompt, MAX_NEW_TOKENS)
                # A test's greedy loop ends at the end-of-text token; a reply that held one could not be compared.
                assert end_of_text not in reply, "a greedy reply contains the end-of-text token: choose another prompt"
                entries.append((prompt + reply, len(prompt), reply))

    sequences = []
    for ids, n_prompt, reply in entries:
        logits = reference_shard2.all_logits(model, ids)
        top_val, top_idx = logits.topk(TOP_K, dim=-1)
        full_pos = list(range(len(ids))) if args.full else [n_prompt - 1]
        sequences.append(formats.LogitSequence(
            ids=np.asarray(ids, dtype=np.int32),
            n_prompt=n_prompt,
            greedy=np.asarray(reply, dtype=np.int32),
            top_idx=top_idx.numpy().astype(np.int32),
            top_val=top_val.numpy().astype(np.float32),
            full_pos=np.asarray(full_pos, dtype=np.int32),
            full=logits[full_pos].numpy().astype(np.float32),
        ))

    formats.write_logit_reference(args.out, n_vocab, TOP_K, sequences)
    n_positions = sum(len(s.ids) for s in sequences)
    n_bs = sum(int((s.ids == config.backspace_token_id).sum()) for s in sequences)
    print(f"gen_logit_ref: wrote {args.out}: {len(sequences)} sequences of {[len(s.ids) for s in sequences]} tokens, {n_positions} positions "
          f"({n_bs} deletion tokens among the inputs), {args.out.stat().st_size} bytes, torch {torch.__version__}")
    return 0


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


def greedy_without_cache(model, prompt: list[int], banned: list[int], stop: int | None, max_new_tokens: int) -> list[int]:
    """The same continuation from a forward pass over the whole prefix for every token.

    That is how the packaged byte-level BPE members generate (their model code has no cache),
    and it asks nothing of the reference code but its plain forward pass.
    """
    ids = list(prompt)
    out: list[int] = []
    for _ in range(max_new_tokens):
        logits = model(input_ids=torch.tensor([ids])).logits[0, -1].float()
        logits[banned] = float("-inf")
        token = int(torch.argmax(logits))
        if token == stop:
            break
        out.append(token)
        ids.append(token)
    return out


def suppressed_tokens(model_dir: Path) -> list[int]:
    """What a byte-level BPE peacebell checkpoint must never sample: its training pad and what its generation settings list."""
    with open(model_dir / "config.json", encoding="utf-8") as f:
        suppressed = {json.load(f)["train_pad_id"]}
    generation_config = model_dir / "generation_config.json"
    if generation_config.is_file():
        with open(generation_config, encoding="utf-8") as f:
            suppressed.update(json.load(f).get("suppress_tokens") or [])
    return sorted(suppressed)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--model-dir", type=Path, required=True)
    parser.add_argument("--code-dir", type=Path, default=None, help="directory of the reference modules when the checkpoint has none")
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--full", action="store_true", help="store the whole logit vector at every position")
    parser.add_argument("--random-sequences", type=int, default=0, help="use this many seeded random sequences instead of conversations")
    parser.add_argument("--seed", type=int, default=99)
    parser.add_argument("--no-ngram-lookup", action="store_true", help="Boris-2 only: every token gets the miss row of the n-gram table")
    parser.add_argument("--long", action="store_true", help="Shard-2 only: the sequences longer than the attention window instead of the short prompts")
    parser.add_argument("--ids-from", type=Path, default=None, help="Shard-2 only: take the token sequences from this reference file")
    parser.add_argument("--tile", type=int, default=0, help="Shard-2 only, with --ids-from: one sequence of this many tokens made by repeating the file's sequences")
    args = parser.parse_args()

    if reference_shard2.is_shard2(args.model_dir):
        return shard2_main(args)

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
    elif (args.model_dir / "tokenizer.json").is_file():
        banned, eos = suppressed_tokens(args.model_dir), config.eos_token_id
        if chatml_text.has_chat_template(args.model_dir):
            tokenizer = chatml_text.ChatTokenizer(args.model_dir)
            for conversation in BORIS2_CONVERSATIONS:
                prompt = tokenizer.encode(tokenizer.render(conversation, add_generation_prompt=True))
                reply, _ = chatml_text.greedy_reply(model, tokenizer, prompt, eos, MAX_NEW_TOKENS, banned)
                prompts.append(prompt)
                replies.append(reply)
                tails.append([])
        else:
            from tokenizers import Tokenizer

            tokenizer = Tokenizer.from_file(str(args.model_dir / "tokenizer.json"))
            for text in PEACEBELL_PROMPTS:
                prompt = tokenizer.encode(text, add_special_tokens=False).ids
                prompts.append(prompt)
                replies.append(greedy_without_cache(model, prompt, banned, eos, MAX_NEW_TOKENS))
                tails.append([])
        # One sequence is one document, as in training: the end-of-text token is never an input.
        assert all(eos not in prompt + reply for prompt, reply in zip(prompts, replies))
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
