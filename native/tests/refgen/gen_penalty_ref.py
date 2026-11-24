#!/usr/bin/env python3
"""Writes the penalty reference of a Boris-2 checkpoint: replies generated with a repetition penalty.

    gen_penalty_ref.py --model-dir DIR --out FILE

The checkpoint's generation settings name a repetition penalty (generation_config.json:
"repetition_penalty": 1.1), and what that means is defined by the library the settings are
written for. So the replies here are made by that library and by nothing written for this
project: transformers' own generate(), greedy (do_sample=False), with its own
RepetitionPenaltyLogitsProcessor, on the checkpoint's own modelling code. The processor
penalises every token of the sequence it is shown, which in generate() is the whole prompt and
the reply so far, each token once however often it occurs, and it has no window. The port has
to choose the same tokens.

The requests are what an application sends over the life of a conversation, each a whole
conversation: three turns appended one after the other, the last question of the second turn
changed, then another beginning with a turn appended to it. Every reply is generated from
scratch, so a request's reply depends on nothing but its messages; the port serves them from
one memory that it keeps, cuts back and clears.

For each request the file carries:
- the reply without the penalty, by the same generate() call with the penalty at 1: the two
  differ, and where they first differ the choice without the penalty is a token that occurs in
  the prompt and nowhere in the reply before it. A port that penalised the reply alone would
  choose that token; this is asserted here, so the file can tell the two behaviours apart;
- for every token of the reply, how far its score was ahead of the next best one after the
  penalty, so that a test of a 16-bit file can tell a choice from a close call (the replies
  are left as the model gives them, close calls included; this script only insists that they
  are few and that none is at a place where the two replies part);
- the first position of the reply, where the reply is empty and only the prompt can be
  penalised: the tokens with the highest scores after the penalty, with their logits before it
  and their scores after it, both as generate() reports them (output_logits, output_scores).

The output is a function of the checkpoint and of the PyTorch build: regenerate the committed
file only when the checkpoint changes, with the exact package versions of
native/requirements.txt.
"""
from __future__ import annotations

import argparse
import hashlib
import sys

from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import chatml_text  # noqa: E402
import formats  # noqa: E402
import reference_boris2  # noqa: E402

import torch  # noqa: E402

from transformers import GenerationConfig  # noqa: E402

PENALTY = 1.1
MAX_NEW_TOKENS = 64
TOP_K = 16
# The lead of a chosen token over the next best one that makes it a clear choice: the 16-bit
# file moves a logit by a few hundredths at most. Nine reply tokens in ten must be that clear.
CLEAR_LEAD = 0.1
CLEAR_SHARE = 0.9

SYSTEM = ("system", "Be brief.")


def generate(model, prompt: list[int], eos: int, penalty: float):
    """transformers' greedy generation from the prompt: the new tokens, the logits and the scores of every step."""
    config = GenerationConfig(do_sample=False, repetition_penalty=penalty, max_new_tokens=MAX_NEW_TOKENS, eos_token_id=eos,
                              pad_token_id=eos, use_cache=False, output_logits=True, output_scores=True, return_dict_in_generate=True)
    ids = torch.tensor([prompt])
    out = model.generate(input_ids=ids, attention_mask=torch.ones_like(ids), generation_config=config)
    new = out.sequences[0, len(prompt):].tolist()
    return new, [step[0].float() for step in out.logits], [step[0].float() for step in out.scores]


def cut_at_marker(tokenizer: chatml_text.ChatTokenizer, new: list[int], eos: int) -> tuple[list[int], str | None]:
    """The reply as an application ends it: with the token that completes the end marker, or before end-of-text."""
    for n in range(1, len(new) + 1):
        if new[n - 1] == eos:
            return new[:n - 1], tokenizer.decode(new[:n - 1])
        text = tokenizer.decode(new[:n])
        if chatml_text.END_MARKER in text:
            return new[:n], text.split(chatml_text.END_MARKER, 1)[0]
    return new, None


def parting(r: formats.PenaltyRequest) -> int | None:
    """The first reply token at which the reply with the penalty and the one without it differ, if they do."""
    return next((i for i, (x, y) in enumerate(zip(r.plain, r.reply)) if x != y), None if len(r.plain) == len(r.reply) else min(len(r.plain), len(r.reply)))


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--model-dir", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()

    if not reference_boris2.is_boris2(args.model_dir):
        parser.error(f"{args.model_dir} is not a Boris-2 checkpoint")
    model, config = reference_boris2.load_reference(args.model_dir)
    tokenizer = chatml_text.ChatTokenizer(args.model_dir)
    eos = config.eos_token_id
    requests: list[formats.PenaltyRequest] = []

    def ask(messages: list[tuple[str, str]]) -> str:
        prompt = tokenizer.encode(tokenizer.render(messages, add_generation_prompt=True))
        new, logits, scores = generate(model, prompt, eos, PENALTY)
        reply, text = cut_at_marker(tokenizer, new, eos)
        plain, _ = cut_at_marker(tokenizer, generate(model, prompt, eos, 1.0)[0], eos)
        assert eos not in prompt + reply + plain
        assert text is not None, f"the reply to {messages[-1][1]!r} did not end within {MAX_NEW_TOKENS} tokens: it cannot become a turn"

        margins = []
        for i, token in enumerate(reply):
            # What the library did, read off its own outputs: the scores differ from the logits
            # at exactly the tokens of the prompt and of the reply so far, and nowhere else.
            seen = torch.zeros(logits[i].shape[0], dtype=torch.bool)
            seen[torch.tensor(prompt + reply[:i])] = True
            changed = scores[i] != logits[i]
            assert not bool((changed & ~seen).any()), "a token outside the sequence was penalised"
            assert bool((changed | ~seen | (logits[i] == 0)).all()), "a token of the sequence was not penalised"
            best = torch.topk(scores[i], 2)
            assert int(best.indices[0]) == token
            margins.append(float(best.values[0] - best.values[1]))

        first = torch.topk(scores[0], TOP_K)
        requests.append(formats.PenaltyRequest(list(messages), prompt, plain, reply, margins, first.indices.tolist(),
                                               logits[0][first.indices].tolist(), first.values.tolist()))
        parts = parting(requests[-1])
        print(f"gen_penalty_ref: {len(prompt)} prompt tokens, {len(reply)} reply tokens ({len(plain)} without the penalty), "
              f"{sum(m < CLEAR_LEAD for m in margins)} close calls, smallest lead {min(margins):.3f}, "
              f"{'the same reply without the penalty' if parts is None else f'parts from the reply without the penalty at token {parts}'}: {text!r}", flush=True)
        return text

    first = [SYSTEM, ("user", "What is the capital of France?")]
    answer_1 = ask(first)
    second = first + [("assistant", answer_1), ("user", "And what is the capital of Italy?")]
    answer_2 = ask(second)
    third = second + [("assistant", answer_2), ("user", "Which of the two cities is older?")]
    ask(third)
    # The last question of the second turn changed: what came after it is no longer in the
    # conversation, and its tokens are no longer penalised.
    ask(first + [("assistant", answer_1), ("user", "And what is the capital of Spain?")])
    # Another beginning: nothing of the conversation above is left to penalise. Without the
    # instruction to be brief the replies are longer, and the penalty has more to act on.
    other = [("user", "How many legs does a spider have?")]
    answer_5 = ask(other)
    ask(other + [("assistant", answer_5), ("user", "And an insect?")])

    # The file must be able to tell a penalty over the whole sequence from one over the reply
    # alone (see the top of this file), and the place where it does must be a clear choice.
    telling = 0
    for r in requests:
        d = parting(r)
        if d is not None and r.plain[d] in r.prompt and r.plain[d] not in r.reply[:d] and r.margins[d] >= CLEAR_LEAD:
            telling += 1
    assert telling >= 2, f"only {telling} of {len(requests)} replies show that the prompt is penalised"
    assert any(r.plain[0] != r.reply[0] and r.margins[0] >= CLEAR_LEAD for r in requests), \
        "no reply differs in its first token, where only the prompt can be penalised"
    margins = [m for r in requests for m in r.margins]
    clear = sum(m >= CLEAR_LEAD for m in margins)
    assert clear >= CLEAR_SHARE * len(margins), f"only {clear} of {len(margins)} reply tokens were chosen with a lead of {CLEAR_LEAD} or more"

    sha = hashlib.sha256((args.model_dir / "tokenizer.json").read_bytes()).hexdigest()
    formats.write_penalty_reference(args.out, sha, PENALTY, MAX_NEW_TOKENS, TOP_K, requests)
    print(f"gen_penalty_ref: wrote {args.out}: {len(requests)} requests, {len(margins)} reply tokens, {clear} of them chosen with a lead of "
          f"{CLEAR_LEAD} or more, {telling} replies that part from the reply without the penalty at a token of the prompt alone, "
          f"{args.out.stat().st_size} bytes")
    return 0


if __name__ == "__main__":
    sys.exit(main())
