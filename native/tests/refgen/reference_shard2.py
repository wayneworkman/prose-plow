"""The PyTorch reference implementation of a Shard-2 checkpoint, CPU and fp32.

Unlike the other models, Shard-2 does not ship modelling code that can be imported: its
reference is one script, infer.py, and its weights are a PyTorch pickle. Nothing of that
directory is ever executed here.

- The architecture below is a copy of the one in infer.py (the classes Attention, SwiGlu, Block
  and Shard2 with their helpers), kept as it is there so that it can be compared line by line.
  The script itself is never imported or run: its loader calls torch.load with
  weights_only=False, which executes whatever the pickle says.
- The checkpoint is loaded with torch.load(..., weights_only=True) and with nothing else. That
  unpickler builds tensors, dicts, lists and plain numbers only; a payload that needs anything
  more makes it raise, and this module then fails rather than fall back.

fp32 on the CPU is the reference precision for the port: the checkpoint is stored in fp32.

What the reference computes, for the reader of the port: a decoder of "sandwich-normed" blocks
(x += post_norm(attn(pre_norm(x))), x += post_norm(ffn(pre_norm(x)))), all norms RMSNorm with
PyTorch's default epsilon (the machine epsilon of float32); attention with one fused Q|K|V
projection, an RMSNorm over the head dimension on Q and on K, then rotary positions on the
LOCAL layers only; local layers see a window of 256 keys ((query - key) < 256), GLOBAL layers
(every sixth counting back from the last) see everything and carry no position at all; SwiGLU
with a fused gate|up projection; a final norm and a readout tied to the embedding.

The copied code is under the licence that the model repository declares: `license: mit` in the
metadata of the model card (README.md) of Glint-Research/Shard-2 at revision
46b9eb99c475694a1129ad8f3a9825d244a8ec79, which models/manifest.json pins. The repository has
no licence file, infer.py has no licence header, and no copyright line is given anywhere in it,
so none is written here. The code is Glint-Research's, as the publisher of the repository, and
the MIT permission notice is:

    MIT License

    Permission is hereby granted, free of charge, to any person obtaining a copy of this
    software and associated documentation files (the "Software"), to deal in the Software
    without restriction, including without limitation the rights to use, copy, modify, merge,
    publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons
    to whom the Software is furnished to do so, subject to the following conditions:

    The above copyright notice and this permission notice shall be included in all copies or
    substantial portions of the Software.

    THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED,
    INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR
    PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE
    FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR
    OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER
    DEALINGS IN THE SOFTWARE.
"""
from __future__ import annotations

import json
import os
import random
import sys

from dataclasses import dataclass
from pathlib import Path

sys.dont_write_bytecode = True
os.environ["CUDA_VISIBLE_DEVICES"] = ""

import torch  # noqa: E402

from torch import Tensor, nn  # noqa: E402
from torch.nn import functional  # noqa: E402

torch.set_grad_enabled(False)
torch.set_num_threads(max(1, int(os.environ.get("PROSEPLOW_THREADS", "4"))))

CHECKPOINT_NAME = "Shard-2.pt"

# What the reference script samples with when it is given no other settings.
SAMPLING_TEMPERATURE = 0.9
SAMPLING_TOP_K = 100


def is_shard2(model_dir: Path) -> bool:
    with open(model_dir / "config.json", encoding="utf-8") as f:
        return json.load(f).get("model_type") == "shard2"


def load_checkpoint(path: Path) -> dict:
    """The checkpoint's payload, read without executing anything it contains.

    weights_only=True is the whole safety argument: never replace it, and never catch its
    refusal in order to try another way.
    """
    payload = torch.load(path, map_location="cpu", weights_only=True)
    assert isinstance(payload, dict) and isinstance(payload.get("model"), dict), "the checkpoint is not a dict with a 'model' state dict"
    return payload


# ---------------------------------------------------------------------------------------------
# The architecture, copied from infer.py. Changes: the configuration is read from an explicit
# path, and the loader and the generation helpers of the script are left out (see below).
# ---------------------------------------------------------------------------------------------


@dataclass(frozen=True)
class ShardConfig:
    """The architecture fields this checkpoint needs, read from config.json."""

    vocab_size: int = 4097
    dim: int = 512
    n_heads: int = 8
    layers: int = 20
    ffn_hidden: int = 856
    max_context_len: int = 8192
    rope_base: float = 10000.0
    attention_window: int = 256
    global_attention_every: int = 6
    backspace_token_id: int = 4096

    @classmethod
    def load(cls, path: Path) -> ShardConfig:
        raw = json.loads(path.read_text())
        return cls(
            vocab_size=int(raw["vocab_size"]),
            dim=int(raw["hidden_size"]),
            n_heads=int(raw["num_attention_heads"]),
            layers=int(raw["num_hidden_layers"]),
            ffn_hidden=int(raw["intermediate_size"]),
            max_context_len=int(raw["max_position_embeddings"]),
            rope_base=float(raw["rope_theta"]),
            attention_window=int(raw["sliding_window"]),
            global_attention_every=int(raw["global_attention_every"]),
            backspace_token_id=int(raw["backspace_token_id"]),
        )

    @property
    def head_dim(self) -> int:
        return self.dim // self.n_heads

    def is_global_layer(self, index: int) -> bool:
        """Every sixth layer counting back from the last, so the final layer is global.

        Global layers see the whole sequence and carry no position encoding. Local
        layers see the previous ``attention_window`` keys and use rotary positions.
        """
        if self.global_attention_every <= 0 or self.attention_window <= 0:
            return False
        return (self.layers - 1 - index) % self.global_attention_every == 0


def build_rope(config: ShardConfig, length: int, device: torch.device) -> tuple[Tensor, Tensor]:
    positions = torch.arange(length, dtype=torch.float32, device=device)
    steps = torch.arange(0, config.head_dim, 2, dtype=torch.float32, device=device)
    inv_freq = 1.0 / (config.rope_base ** (steps / config.head_dim))
    angles = torch.outer(positions, inv_freq)
    return torch.cos(angles), torch.sin(angles)


def apply_rope(x: Tensor, cos: Tensor, sin: Tensor) -> Tensor:
    even = x[..., 0::2]
    odd = x[..., 1::2]
    return torch.stack((even * cos - odd * sin, even * sin + odd * cos), dim=-1).flatten(-2)


class Attention(nn.Module):
    def __init__(self, config: ShardConfig, is_global: bool) -> None:
        super().__init__()
        self.n_heads = config.n_heads
        self.head_dim = config.head_dim
        self.is_global = is_global
        self.window = 0 if is_global else config.attention_window
        self.qkv = nn.Linear(config.dim, 3 * config.dim, bias=False)
        self.out = nn.Linear(config.dim, config.dim, bias=False)
        self.q_norm = nn.RMSNorm(self.head_dim)
        self.k_norm = nn.RMSNorm(self.head_dim)

    def forward(
        self,
        x: Tensor,
        cos: Tensor,
        sin: Tensor,
        cache: list[tuple[Tensor, Tensor] | None],
        slot: int,
    ) -> Tensor:
        batch, seq_len, dim = x.shape
        q, k, v = self.qkv(x).split(dim, dim=-1)
        shape = (batch, seq_len, self.n_heads, self.head_dim)
        q = q.view(shape).transpose(1, 2)
        k = k.view(shape).transpose(1, 2)
        v = v.view(shape).transpose(1, 2)
        q = self.q_norm(q)
        k = self.k_norm(k)
        if not self.is_global:
            q = apply_rope(q, cos, sin)
            k = apply_rope(k, cos, sin)
        past = cache[slot]
        if past is not None:
            k = torch.cat((past[0], k), dim=-2)
            v = torch.cat((past[1], v), dim=-2)
        attended = self._attend(q, k, v, seq_len)
        if self.window > 0 and k.shape[-2] > self.window:
            k, v = k[..., -self.window :, :], v[..., -self.window :, :]
        cache[slot] = (k, v)
        merged = attended.transpose(1, 2).reshape(batch, seq_len, dim)
        return self.out(merged)

    def _attend(self, q: Tensor, k: Tensor, v: Tensor, query_len: int) -> Tensor:
        total = k.shape[-2]
        if query_len == 1:
            keys, values = k, v
            if self.window > 0 and total > self.window:
                keys, values = k[..., -self.window :, :], v[..., -self.window :, :]
            # one query against every key it is allowed to see, so causal already
            return functional.scaled_dot_product_attention(q, keys, values)
        offset = total - query_len
        key_positions = torch.arange(total, device=q.device)[None, :]
        query_positions = torch.arange(query_len, device=q.device)[:, None] + offset
        allowed = key_positions <= query_positions
        if self.window > 0:
            allowed &= (query_positions - key_positions) < self.window
        return functional.scaled_dot_product_attention(q, k, v, attn_mask=allowed)


class SwiGlu(nn.Module):
    def __init__(self, dim: int, hidden: int) -> None:
        super().__init__()
        self.gate_up = nn.Linear(dim, 2 * hidden, bias=False)
        self.down = nn.Linear(hidden, dim, bias=False)

    def forward(self, x: Tensor) -> Tensor:
        gate, up = self.gate_up(x).chunk(2, dim=-1)
        return self.down(functional.silu(gate) * up)


class Block(nn.Module):
    """Sandwich-normed attention and feed-forward, both residual."""

    def __init__(self, config: ShardConfig, is_global: bool) -> None:
        super().__init__()
        self.attn_norm = nn.RMSNorm(config.dim)
        self.attn = Attention(config, is_global)
        self.ffn_norm = nn.RMSNorm(config.dim)
        self.ffn = SwiGlu(config.dim, config.ffn_hidden)
        self.attn_post_norm = nn.RMSNorm(config.dim)
        self.ffn_post_norm = nn.RMSNorm(config.dim)

    def forward(
        self,
        x: Tensor,
        cos: Tensor,
        sin: Tensor,
        cache: list[tuple[Tensor, Tensor] | None],
        slot: int,
    ) -> Tensor:
        attended = self.attn(self.attn_norm(x), cos, sin, cache, slot)
        x = x + self.attn_post_norm(attended)
        gated = self.ffn(self.ffn_norm(x))
        return x + self.ffn_post_norm(gated)


class SparseIndexer(nn.Module):
    """Carries the checkpoint's ``indexer.gate`` and nothing else.

    The gate belongs to a sparse-attention path this configuration leaves switched
    off, so it takes no part in the forward pass. It is declared here so the
    checkpoint loads with every key accounted for.
    """

    def __init__(self) -> None:
        super().__init__()
        self.gate = nn.Parameter(torch.tensor([0.1]))


class Shard2(nn.Module):
    """The 49.4M stack: tied embedding, 20 blocks, tied readout in fp32."""

    def __init__(self, config: ShardConfig) -> None:
        super().__init__()
        self.config = config
        self.embed = nn.Embedding(config.vocab_size, config.dim)
        self.indexer = SparseIndexer()
        self.prelude = nn.ModuleList(
            Block(config, config.is_global_layer(index)) for index in range(config.layers)
        )
        self.final_norm = nn.RMSNorm(config.dim)

    def forward(
        self,
        tokens: Tensor,
        cache: list[tuple[Tensor, Tensor] | None] | None = None,
        past_len: int = 0,
    ) -> Tensor:
        seq_len = tokens.shape[1]
        if past_len + seq_len > self.config.max_context_len:
            raise ValueError(
                f"sequence length {past_len + seq_len} exceeds the model's context of "
                f"{self.config.max_context_len}"
            )
        if cache is None:
            cache = [None] * len(self.prelude)
        x = self.embed(tokens)
        cos, sin = build_rope(self.config, past_len + seq_len, x.device)
        cos = cos[past_len : past_len + seq_len]
        sin = sin[past_len : past_len + seq_len]
        for slot, block in enumerate(self.prelude):
            x = block(x, cos, sin, cache, slot)
        x = self.final_norm(x)
        return functional.linear(x.float(), self.embed.weight.float())


# ---------------------------------------------------------------------------------------------
# Ours from here on.
# ---------------------------------------------------------------------------------------------


_COPY_BEGIN = "    @property\n    def head_dim(self) -> int:"
_COPY_END = "        return functional.linear(x.float(), self.embed.weight.float())\n"


def check_copy_of(script: Path) -> None:
    """Raises unless the architecture above is, character for character, the one in the reference script.

    The script is read as text and never imported. The compared stretch runs from the head size
    and the rule for global layers to the end of the model's forward pass: the rotary encoding,
    attention with its cache and window, the feed-forward, the block and the stack.
    """
    def stretch(text: str, where: str) -> str:
        begin = text.find(_COPY_BEGIN)
        end = text.find(_COPY_END, begin)
        assert begin >= 0 and end >= 0, f"{where} does not contain the architecture where it is expected"
        return text[begin:end + len(_COPY_END)]

    theirs = stretch(script.read_text(encoding="utf-8"), script.name)
    ours = stretch(Path(__file__).read_text(encoding="utf-8"), Path(__file__).name)
    assert ours == theirs, f"the architecture copied into {Path(__file__).name} differs from {script.name}: the reference is no longer the script's"


def end_of_text_id(model_dir: Path) -> int:
    """The id of the one special token the model was trained with, the boundary between two texts.

    The tokenizer calls it <eos>; config.json lists the same id as bos_token_id. The ids of
    <pad> and <bos> are rows the model never saw.
    """
    with open(model_dir / "tokenizer.json", encoding="utf-8") as f:
        added = {t["content"]: t["id"] for t in json.load(f)["added_tokens"]}
    return added["<eos>"]


def load_reference(model_dir: Path):
    """Returns (model, config) for the checkpoint in model_dir, loaded safely (see the module text)."""
    config = ShardConfig.load(model_dir / "config.json")
    model = Shard2(config)
    # strict: every tensor of the checkpoint has its place in the module and the other way
    # round, indexer.gate included, which the forward pass never reads.
    model.load_state_dict(load_checkpoint(model_dir / CHECKPOINT_NAME)["model"], strict=True)
    model.eval()
    model.requires_grad_(False)
    return model.to(torch.float32), config


# A forward pass over a whole sequence builds, in every layer, one attention matrix of
# length x length per head: 135 MB at this length and 2.1 GB at 8,192. Longer sequences are
# therefore computed in pieces, so that no reference run needs gigabytes.
FULL_PASS_LIMIT = 2048
CHUNK = 512


def full_pass_logits(model: Shard2, ids: list[int]) -> Tensor:
    """The logits at every position from ONE forward pass over the whole sequence, without a cache.

    The plainest statement of what the model computes. Memory grows with the square of the
    length: keep to FULL_PASS_LIMIT tokens.
    """
    return model(torch.tensor([ids], dtype=torch.long))[0].float()


def chunked_logits(model: Shard2, ids: list[int], chunk: int = CHUNK) -> Tensor:
    """The same logits computed chunk tokens at a time through the architecture's key-value cache.

    That cache is the script's own (a local layer keeps the last 256 keys, a global layer all of
    them) and gives every query exactly the keys the mask of a full pass allows, so the two
    differ only in the order of floating-point sums (measured: below 1e-4 in the logits).
    Memory grows with chunk x length instead of length squared.
    """
    cache: list[tuple[Tensor, Tensor] | None] = [None] * len(model.prelude)
    rows = []
    for begin in range(0, len(ids), chunk):
        piece = ids[begin:begin + chunk]
        rows.append(model(torch.tensor([piece], dtype=torch.long), cache=cache, past_len=begin)[0].float())
    return torch.cat(rows, dim=0)


def all_logits(model: Shard2, ids: list[int]) -> Tensor:
    """The reference logits at every position of one sequence, [n_tokens, n_vocab], float32.

    One full pass for sequences up to FULL_PASS_LIMIT tokens, the chunked computation beyond.
    """
    return full_pass_logits(model, ids) if len(ids) <= FULL_PASS_LIMIT else chunked_logits(model, ids)


def greedy_reply(model: Shard2, prompt: list[int], n_tokens: int) -> list[int]:
    """n_tokens greedy tokens after prompt, each chosen from a forward pass over the whole prefix.

    The reference script generates through its key-value cache; recomputing the prefix instead
    makes the reply independent of that cache, which is the thing the port replaces. The model
    has no token that ends a text: like the script, this runs for a fixed number of tokens.
    """
    ids = list(prompt)
    for _ in range(n_tokens):
        ids.append(int(torch.argmax(all_logits(model, ids)[-1])))
    return ids[len(prompt):]


def sample_token(logits: Tensor, uniform: float, temperature: float = SAMPLING_TEMPERATURE, top_k: int = SAMPLING_TOP_K) -> int:
    """One draw with the script's sampling rule: temperature, then the top_k best, then the distribution.

    The draw is taken by inverting the cumulative distribution at a number the caller supplies,
    not with torch.multinomial, so that a seeded sequence depends on the logits alone and not on
    how a PyTorch version consumes its random stream.
    """
    scaled = logits.double() / temperature
    if 0 < top_k < scaled.shape[-1]:
        floor = scaled.topk(top_k).values[-1]
        scaled = scaled.masked_fill(scaled < floor, float("-inf"))
    cumulative = torch.cumsum(torch.softmax(scaled, dim=-1), dim=-1)
    return int(min(int(torch.searchsorted(cumulative, uniform * float(cumulative[-1]), right=True)), scaled.shape[-1] - 1))


def sampled_stream(model: Shard2, prompt: list[int], n_tokens: int, seed: int) -> list[int]:
    """n_tokens sampled after prompt as the reference script generates them, deletion tokens included.

    This runs the script's own generation path: the key-value cache of the copied
    architecture, one token at a time, every emitted token fed back whatever it is.
    """
    rng = random.Random(seed)
    cache: list[tuple[Tensor, Tensor] | None] = [None] * len(model.prelude)
    emitted: list[int] = []
    pending = list(prompt)
    past_len = 0
    for _ in range(n_tokens):
        logits = model(torch.tensor([pending], dtype=torch.long), cache=cache, past_len=past_len)[0, -1]
        past_len += len(pending)
        token = sample_token(logits, rng.random())
        emitted.append(token)
        pending = [token]
    return emitted
