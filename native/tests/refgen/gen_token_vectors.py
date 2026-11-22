#!/usr/bin/env python3
"""Writes token vectors: test strings with the ids the reference tokenizer gives them.

    gen_token_vectors.py --model-dir DIR --out FILE                      committed vectors
    gen_token_vectors.py --model-dir DIR --out FILE --live --llama-src D  on-demand vectors

The tokenizer kind follows from the files in DIR: sentencepiece.model (the reference is the
SentencePiece library) or tokenizer.json (the reference is the tokenizers library). With
tokenizer.json and a chat template (plain-text ChatML), the conversations are the template
rendered by transformers and tokenised in one call.

Every case carries two id lists. "exact" is the reference as it is. "safe" is the reference
with its special tokens treated as ordinary text, which is how message content is tokenised in
the app. For SentencePiece the safe reference is the same model with the two chat markers
retyped from user-defined to control, so that the library itself, not a re-implementation,
says what the text becomes without them. For tokenizers it is the same file without its added
tokens.

The committed file is generated without --live from text that belongs to this project and
contains only the symbols common to the family; strings that happen to contain a symbol
specific to one tokenizer file are left out of it and covered by the live vectors instead.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import sys

from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import chatml_text  # noqa: E402
import corpus  # noqa: E402
import formats  # noqa: E402

_SP_CONTROL, _SP_USER_DEFINED = 3, 4


def sentencepiece_encoders(model_file: Path):
    import sentencepiece
    from sentencepiece import sentencepiece_model_pb2 as sp_pb2

    data = model_file.read_bytes()
    proto = sp_pb2.ModelProto()  # type: ignore[attr-defined]
    proto.ParseFromString(data)
    symbols = [p.piece for p in proto.pieces if p.type == _SP_USER_DEFINED]
    start = next(i for i, p in enumerate(proto.pieces) if p.piece == corpus.MARKERS[0])
    end = next(i for i, p in enumerate(proto.pieces) if p.piece == corpus.MARKERS[1])
    for i in (start, end):
        proto.pieces[i].type = _SP_CONTROL
    exact = sentencepiece.SentencePieceProcessor(model_proto=data)
    safe = sentencepiece.SentencePieceProcessor(model_proto=proto.SerializeToString())
    return exact.encode, safe.encode, symbols, (start, end)


def bpe_encoders(tokenizer_file: Path):
    from tokenizers import Tokenizer

    text = tokenizer_file.read_text(encoding="utf-8")
    exact = Tokenizer.from_str(text)
    stripped = json.loads(text)
    stripped["added_tokens"] = []
    safe = Tokenizer.from_str(json.dumps(stripped))
    return (lambda t: exact.encode(t, add_special_tokens=False).ids), (lambda t: safe.encode(t, add_special_tokens=False).ids)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--model-dir", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--live", action="store_true", help="the large on-demand corpus, with every symbol of this tokenizer file")
    parser.add_argument("--llama-src", type=Path, default=None, help="the patched llama.cpp copy (needed with --live)")
    args = parser.parse_args()
    if args.live and args.llama_src is None:
        parser.error("--live needs --llama-src")

    spm_file = args.model_dir / "sentencepiece.model"
    bpe_file = args.model_dir / "tokenizer.json"
    chats: list[formats.ChatCase] = []
    if spm_file.is_file():
        source = spm_file
        encode_exact, encode_safe, symbols, (start, end) = sentencepiece_encoders(spm_file)
        if args.live:
            texts = corpus.live(args.llama_src, symbols)
        else:
            private = [s for s in symbols if not corpus.is_public_symbol(s)]
            texts = [t for t in corpus.committed() if not any(s in t for s in private)]

        def chat(encode, conversation, add_generation_prompt):
            ids: list[int] = []
            for role, content in conversation:
                ids += [start] + encode(role + "\n") + encode(content) + [end]
            return ids + ([start] + encode("assistant\n") if add_generation_prompt else [])

        for conversation in corpus.CONVERSATIONS:
            for add_generation_prompt in (True, False):
                chats.append(formats.ChatCase(conversation, add_generation_prompt,
                                              chat(encode_exact, conversation, add_generation_prompt),
                                              chat(encode_safe, conversation, add_generation_prompt)))
    elif bpe_file.is_file():
        source = bpe_file
        encode_exact, encode_safe = bpe_encoders(bpe_file)
        texts = corpus.live(args.llama_src, []) if args.live else corpus.committed()
        if chatml_text.has_chat_template(args.model_dir):
            renderer = chatml_text.ChatTokenizer(args.model_dir)
            for conversation in corpus.text_chatml_conversations():
                for add_generation_prompt in (True, False):
                    rendered = renderer.render(conversation, add_generation_prompt)
                    chats.append(formats.ChatCase(conversation, add_generation_prompt, encode_exact(rendered), encode_safe(rendered)))
    else:
        parser.error(f"{args.model_dir} has neither sentencepiece.model nor tokenizer.json")

    cases = [formats.TextCase(t, encode_exact(t), encode_safe(t)) for t in texts]
    formats.write_token_vectors(args.out, hashlib.sha256(source.read_bytes()).hexdigest(), cases, chats)
    n_differ = sum(1 for c in cases if c.safe != c.exact)
    print(f"gen_token_vectors: wrote {args.out}: {len(cases)} texts ({n_differ} differ between exact and safe), "
          f"{len(chats)} conversations, {sum(len(c.exact) for c in cases)} tokens, {args.out.stat().st_size} bytes")
    return 0


if __name__ == "__main__":
    sys.exit(main())
