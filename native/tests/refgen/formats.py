"""The binary reference-data formats shared by the generators here and the C++ tests.

All are little-endian and deliberately trivial, so that the C++ side reads them with a few
lines and no library (see native/tests/support/, which must stay in step).

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

Chat reference ("PPCHAT01"): a session of chat requests against a plain-text ChatML model, each
with the reference's tokenisation of the rendered conversation and its greedy reply.

    magic            8 bytes  "PPCHAT01"
    tokenizer sha    64 bytes hex SHA-256 of the tokenizer file
    max_new_tokens   u32      the length limit the replies were generated with
    n_request        u32
    n_request times:
        n_messages   u32, per message role and content (u32 length + UTF-8 bytes each)
        prompt       u32 count + i32 each   the conversation with the generation prompt, tokenised
        reply        u32 count + i32 each   the greedy continuation; when ended, it finishes with
                                            the tokens that complete the end marker in the text
        ended        u8       1: the reply text contains the end marker; 0: cut by max_new_tokens
        text         u32 length + UTF-8 bytes   the reply as text, up to the end marker

The requests are in the order an application would send them, each a whole conversation: a new
turn appended, the same conversation again (regenerate), an earlier message changed, another
beginning. A request's expected reply never depends on what was sent before it.

Penalty reference ("PPPENAL1"): chat requests against a plain-text ChatML model, each with the
greedy reply the reference library generates with a repetition penalty.

    magic            8 bytes  "PPPENAL1"
    tokenizer sha    64 bytes hex SHA-256 of the tokenizer file
    penalty          f32      the repetition penalty the replies were generated with
    max_new_tokens   u32      the length limit they were generated with
    top_k            u32      how many tokens of the first reply position are described
    n_request        u32
    n_request times:
        n_messages   u32, per message role and content (u32 length + UTF-8 bytes each)
        prompt       u32 count + i32 each   the conversation with the generation prompt, tokenised
        plain        u32 count + i32 each   the greedy reply without the penalty
        reply        u32 count + i32 each   the greedy reply with it; both end with the tokens
                                            that complete the end marker in the text
        margin       f32[count of reply]    how far the score of each reply token was ahead of
                                            the next best one, after the penalty
        first_idx    i32[top_k]   the tokens with the highest scores at the first position of
                                  the reply after the penalty, best first
        first_logit  f32[top_k]   their logits, before the penalty
        first_score  f32[top_k]   their scores, after it

The requests are again whole conversations in the order an application would send them, and
each reply was generated from scratch.
"""
from __future__ import annotations

import struct

from dataclasses import dataclass
from pathlib import Path

import numpy as np

TOKEN_MAGIC = b"PPTOKV01"
LOGIT_MAGIC = b"PPLOGIT1"
CHAT_MAGIC = b"PPCHAT01"
PENALTY_MAGIC = b"PPPENAL1"


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


@dataclass
class ChatRequest:
    messages: list[tuple[str, str]]  # (role, content)
    prompt: list[int]
    reply: list[int]
    ended: bool
    text: str


def write_chat_reference(path: Path, tokenizer_sha256: str, max_new_tokens: int, requests: list[ChatRequest]) -> None:
    assert len(tokenizer_sha256) == 64
    out = bytearray(CHAT_MAGIC + tokenizer_sha256.encode("ascii"))
    out += struct.pack("<II", max_new_tokens, len(requests))
    for r in requests:
        out += struct.pack("<I", len(r.messages))
        for role, content in r.messages:
            _put_bytes(out, role.encode("utf-8"))
            _put_bytes(out, content.encode("utf-8"))
        for ids in (r.prompt, r.reply):
            out += struct.pack("<I", len(ids)) + np.asarray(ids, dtype="<i4").tobytes()
        out += b"\x01" if r.ended else b"\x00"
        _put_bytes(out, r.text.encode("utf-8"))
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(bytes(out))


def read_chat_reference(path: Path) -> tuple[str, int, list[ChatRequest]]:
    data = path.read_bytes()
    assert data[:8] == CHAT_MAGIC, f"{path} is not a chat reference"
    sha = data[8:72].decode("ascii")
    max_new_tokens, n_request = struct.unpack_from("<II", data, 72)
    pos = 80

    def take_bytes() -> bytes:
        nonlocal pos
        (n,) = struct.unpack_from("<I", data, pos)
        pos += 4 + n
        return data[pos - n:pos]

    def take_ids() -> list[int]:
        nonlocal pos
        (n,) = struct.unpack_from("<I", data, pos)
        ids = np.frombuffer(data, dtype="<i4", count=n, offset=pos + 4).tolist()
        pos += 4 + 4 * n
        return ids

    requests = []
    for _ in range(n_request):
        (n_messages,) = struct.unpack_from("<I", data, pos)
        pos += 4
        messages = [(take_bytes().decode("utf-8"), take_bytes().decode("utf-8")) for _ in range(n_messages)]
        prompt, reply = take_ids(), take_ids()
        ended = data[pos] != 0
        pos += 1
        requests.append(ChatRequest(messages, prompt, reply, ended, take_bytes().decode("utf-8")))
    assert pos == len(data), f"{path} has trailing bytes"
    return sha, max_new_tokens, requests


@dataclass
class PenaltyRequest:
    messages: list[tuple[str, str]]  # (role, content)
    prompt: list[int]
    plain: list[int]
    reply: list[int]
    margins: list[float]       # one per reply token
    first_idx: list[int]       # top_k each
    first_logit: list[float]
    first_score: list[float]


def write_penalty_reference(path: Path, tokenizer_sha256: str, penalty: float, max_new_tokens: int, top_k: int,
                            requests: list[PenaltyRequest]) -> None:
    assert len(tokenizer_sha256) == 64
    out = bytearray(PENALTY_MAGIC + tokenizer_sha256.encode("ascii"))
    out += struct.pack("<fIII", penalty, max_new_tokens, top_k, len(requests))
    for r in requests:
        assert len(r.margins) == len(r.reply) and len(r.first_idx) == len(r.first_logit) == len(r.first_score) == top_k
        out += struct.pack("<I", len(r.messages))
        for role, content in r.messages:
            _put_bytes(out, role.encode("utf-8"))
            _put_bytes(out, content.encode("utf-8"))
        for ids in (r.prompt, r.plain, r.reply):
            out += struct.pack("<I", len(ids)) + np.asarray(ids, dtype="<i4").tobytes()
        out += np.asarray(r.margins, dtype="<f4").tobytes()
        out += np.asarray(r.first_idx, dtype="<i4").tobytes()
        out += np.asarray(r.first_logit, dtype="<f4").tobytes()
        out += np.asarray(r.first_score, dtype="<f4").tobytes()
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(bytes(out))
