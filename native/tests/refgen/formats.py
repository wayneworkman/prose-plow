"""The two binary reference-data formats shared by the generators here and the C++ tests.

Both are little-endian and deliberately trivial, so that the C++ side reads them with a few
lines and no library (see native/tests/support/test_support.h, which must stay in step).

Token vectors ("PPTOKV01"): texts with the token ids the reference tokenizer gives them.

    magic            8 bytes  "PPTOKV01"
    tokenizer sha    64 bytes hex SHA-256 of the tokenizer file the ids come from
    n_text           u32
    n_text times:    text (u32 length + UTF-8 bytes), exact ids (u32 count + u16 each),
                     u8 has_safe, and when 1 the safe ids (u32 count + u16 each)
    n_chat           u32
    n_chat times:    u32 n_messages, per message role and content (u32 length + bytes each),
                     u8 add_generation_prompt, exact ids, u8 has_safe, safe ids when 1

"exact" is what the reference produces; "safe" is the same with the chat markers treated as
plain characters, stored only when it differs.

Logit reference ("PPLOGIT1"): fixed token sequences with the reference model's logits.

    magic            8 bytes  "PPLOGIT1"
    n_vocab, n_seq, top_k    u32 each
    n_seq times:
        n_tokens, n_prompt, n_greedy, n_full    u32 each
        ids          i32[n_tokens]      prompt, then the reference's greedy reply, then the end marker
        greedy       i32[n_greedy]      the greedy reply alone
        top_idx      i32[n_tokens * top_k]   the top_k token ids at every position, best first
        top_val      f32[n_tokens * top_k]   their logits
        full_pos     i32[n_full]        positions that also carry the whole logit vector
        full         f32[n_full * n_vocab]

The committed files carry the whole vector at one position per sequence to stay small; the
on-demand "full" files carry it at every position, which is what a KL divergence needs.
"""
from __future__ import annotations

import struct

from dataclasses import dataclass
from pathlib import Path

import numpy as np

TOKEN_MAGIC = b"PPTOKV01"
LOGIT_MAGIC = b"PPLOGIT1"


@dataclass
class TextCase:
    text: str
    exact: list[int]
    safe: list[int] | None = None  # None: same as exact


@dataclass
class ChatCase:
    messages: list[tuple[str, str]]  # (role, content)
    add_generation_prompt: bool
    exact: list[int]
    safe: list[int] | None = None


def _put_bytes(out: bytearray, data: bytes) -> None:
    out += struct.pack("<I", len(data)) + data


def _put_ids(out: bytearray, ids: list[int]) -> None:
    # u16 keeps the committed files small; every vocabulary of the family is below 65,536.
    out += struct.pack("<I", len(ids)) + np.asarray(ids, dtype="<u2").tobytes()


def _put_variants(out: bytearray, exact: list[int], safe: list[int] | None) -> None:
    _put_ids(out, exact)
    if safe is None or safe == exact:
        out += b"\x00"
    else:
        out += b"\x01"
        _put_ids(out, safe)


def write_token_vectors(path: Path, tokenizer_sha256: str, texts: list[TextCase], chats: list[ChatCase]) -> None:
    assert len(tokenizer_sha256) == 64
    for case in texts + chats:  # type: ignore[operator]
        assert all(0 <= t < 65536 for t in case.exact + (case.safe or []))
    out = bytearray(TOKEN_MAGIC + tokenizer_sha256.encode("ascii"))
    out += struct.pack("<I", len(texts))
    for t in texts:
        _put_bytes(out, t.text.encode("utf-8"))
        _put_variants(out, t.exact, t.safe)
    out += struct.pack("<I", len(chats))
    for c in chats:
        out += struct.pack("<I", len(c.messages))
        for role, content in c.messages:
            _put_bytes(out, role.encode("utf-8"))
            _put_bytes(out, content.encode("utf-8"))
        out += b"\x01" if c.add_generation_prompt else b"\x00"
        _put_variants(out, c.exact, c.safe)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(bytes(out))


@dataclass
class LogitSequence:
    ids: np.ndarray        # i32[n_tokens]
    n_prompt: int
    greedy: np.ndarray     # i32[n_greedy]
    top_idx: np.ndarray    # i32[n_tokens, top_k]
    top_val: np.ndarray    # f32[n_tokens, top_k]
    full_pos: np.ndarray   # i32[n_full]
    full: np.ndarray       # f32[n_full, n_vocab]


def write_logit_reference(path: Path, n_vocab: int, top_k: int, sequences: list[LogitSequence]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with open(path, "wb") as f:
        f.write(LOGIT_MAGIC + struct.pack("<III", n_vocab, len(sequences), top_k))
        for s in sequences:
            n = len(s.ids)
            assert s.top_idx.shape == (n, top_k) and s.top_val.shape == (n, top_k)
            assert s.full.shape == (len(s.full_pos), n_vocab)
            f.write(struct.pack("<IIII", n, s.n_prompt, len(s.greedy), len(s.full_pos)))
            f.write(np.ascontiguousarray(s.ids, dtype="<i4").tobytes())
            f.write(np.ascontiguousarray(s.greedy, dtype="<i4").tobytes())
            f.write(np.ascontiguousarray(s.top_idx, dtype="<i4").tobytes())
            f.write(np.ascontiguousarray(s.top_val, dtype="<f4").tobytes())
            f.write(np.ascontiguousarray(s.full_pos, dtype="<i4").tobytes())
            f.write(np.ascontiguousarray(s.full, dtype="<f4").tobytes())


def read_logit_reference(path: Path) -> tuple[int, int, list[LogitSequence]]:
    data = path.read_bytes()
    assert data[:8] == LOGIT_MAGIC, f"{path} is not a logit reference"
    n_vocab, n_seq, top_k = struct.unpack_from("<III", data, 8)
    pos = 20
    sequences = []

    def take(dtype: str, count: int) -> np.ndarray:
        nonlocal pos
        a = np.frombuffer(data, dtype=dtype, count=count, offset=pos)
        pos += a.nbytes
        return a

    for _ in range(n_seq):
        n, n_prompt, n_greedy, n_full = struct.unpack_from("<IIII", data, pos)
        pos += 16
        ids = take("<i4", n)
        greedy = take("<i4", n_greedy)
        top_idx = take("<i4", n * top_k).reshape(n, top_k)
        top_val = take("<f4", n * top_k).reshape(n, top_k)
        full_pos = take("<i4", n_full)
        full = take("<f4", n_full * n_vocab).reshape(n_full, n_vocab)
        sequences.append(LogitSequence(ids, n_prompt, greedy, top_idx, top_val, full_pos, full))
    assert pos == len(data), f"{path} has trailing bytes"
    return n_vocab, top_k, sequences
