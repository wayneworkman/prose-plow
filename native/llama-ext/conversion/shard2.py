"""GGUF conversion of Shard-2 (general.architecture = "shard2").

This module is not part of upstream llama.cpp: native/tools/prepare-llama-src.sh copies it into
the conversion package of the patched copy, and native/convert/convert_shard2.py drives it.

The checkpoint is a pickle, and it is never trusted. It is loaded with

    torch.load(path, map_location="cpu", weights_only=True)

and in no other way. That unpickler constructs tensors, dicts, lists and plain numbers and
nothing else: a payload that asks for any other object makes it raise, and the conversion then
fails. Do not add a fallback, an allow-list entry or weights_only=False here, and never import
or run the infer.py that ships next to the checkpoint (its loader unpickles without restriction).
The pinned checkpoint needs none of that: its pickle refers to collections.OrderedDict,
torch.FloatStorage and torch._utils._rebuild_tensor_v2 only.

What the model is: a decoder of "sandwich-normed" blocks, x += post_norm(attn(pre_norm(x))) and
x += post_norm(ffn(pre_norm(x))), every norm an RMSNorm with PyTorch's default epsilon; a fused
Q|K|V projection with an RMS norm over the head on Q and on K; a fused gate|up SwiGLU; a final
norm and an output head tied to the token embedding. Its layers are of two kinds. LOCAL layers
see a sliding window of keys and rotate Q and K by position (rotary pairs of neighbours).
GLOBAL layers, every sixth counting back from the last, see the whole sequence and carry no
position encoding at all. Which layer is which goes into the file as two per-layer arrays, so
the C++ side holds no rule of its own.

No tensor is transformed on the way into the file: an F32 conversion carries the checkpoint's
bytes unchanged. One tensor is left out, `indexer.gate`: a single number that belongs to a
sparse-attention path this model has switched off and that its forward pass never reads.

Float types of a 16-bit file: the matrices follow the file type; every norm weight (block,
head and final norms) stays float32.

The vocabulary is the tokenizer's 4,096-token byte-level BPE plus one token the tokenizer does
not have: `<bs>`, the model's deletion token, at the next id. The tokenizer's three special
tokens and `<bs>` are control tokens in the file: text tokenised without special-token parsing
can never produce them, and they detokenise to nothing. To the network `<bs>` is a token like
any other; taking back the token before it is a matter of how an application renders the stream.

The output depends only on the files named in INPUT_FILES and on the arguments: not on the
directory's name, the model card or the clock. The same input therefore gives the same bytes.
"""
from __future__ import annotations

import hashlib
import json
import re

from typing import Any, Callable, Iterable, TYPE_CHECKING

import numpy as np
import torch

if TYPE_CHECKING:
    from torch import Tensor

from .base import LazyTorchTensor, ModelBase, TextModel, gguf

CHECKPOINT = "Shard-2.pt"

# The files a conversion reads. Anything else in the directory is ignored, so these are the
# files to pin by hash.
INPUT_FILES = ("config.json", CHECKPOINT, "tokenizer.json")

# The tensors of one block, by the end of their checkpoint name.
_BLOCK_TENSORS = {
    "attn_norm.weight": gguf.MODEL_TENSOR.ATTN_NORM,
    "attn.qkv.weight": gguf.MODEL_TENSOR.ATTN_QKV,
    "attn.q_norm.weight": gguf.MODEL_TENSOR.ATTN_Q_NORM,
    "attn.k_norm.weight": gguf.MODEL_TENSOR.ATTN_K_NORM,
    "attn.out.weight": gguf.MODEL_TENSOR.ATTN_OUT,
    "attn_post_norm.weight": gguf.MODEL_TENSOR.ATTN_POST_NORM,
    "ffn_norm.weight": gguf.MODEL_TENSOR.FFN_NORM,
    "ffn.gate_up.weight": gguf.MODEL_TENSOR.FFN_UP,
    "ffn.down.weight": gguf.MODEL_TENSOR.FFN_DOWN,
    "ffn_post_norm.weight": gguf.MODEL_TENSOR.FFN_POST_NORM,
}

# The one tensor of the checkpoint that takes no part in the forward pass.
_UNUSED_TENSOR = "indexer.gate"

# What the configuration stored inside the checkpoint must say for the port to compute the same
# model: the paths of the training code that this architecture does not have are switched off.
_PORTED_CHECKPOINT_CONFIG = {
    "coda_layers": 0, "shared_loops": 0, "qk_norm": True, "sandwich_norm": True, "fp4_weights": False,
    "thinking_enabled": False, "moe_experts_per_layer": 0, "hyper_connections": False,
    "linear_attention_every": 0, "short_conv_kernel": 0, "no_rope": False, "sft_adapter_rank": 0,
    "value_residual": False, "layernorm_scaling": False,
}

_TOKEN_NORMAL, _TOKEN_CONTROL = 1, 3  # gguf.TokenType

# llama.cpp identifies a byte-level BPE pre-tokenizer by hashing the ids of this text. It is the
# text upstream's converter uses, repeated here so that the hash below stays meaningful if
# upstream ever changes its copy.
_PRE_CHECK_TEXT = '\n \n\n \n\n\n \t \t\t \t\n  \n   \n    \n     \n🚀 (normal) 😶‍🌫️ (multiple emojis concatenated) ✅ 🦙🦙 3 33 333 3333 33333 333333 3333333 33333333 3.3 3..3 3...3 កាន់តែពិសេសអាច😁 ?我想在apple工作1314151天～ ------======= нещо на Български \'\'\'\'\'\'```````""""......!!!!!!?????? I\'ve been \'told he\'s there, \'RE you sure? \'M not sure I\'ll make it, \'D you like some tea? We\'Ve a\'lL'

# Tokenizers whose llama.cpp tokenisation was verified token for token against the reference,
# with the pre-tokenizer name that reproduces them. An unknown hash stops the conversion:
# verify the new tokenizer first, then add its hash.
_KNOWN_BPE_PRE = {
    "fe49975e7f7f93a48c67779b58a39fdd06b233c768e54c60311bd89596fd2cf2": "gpt-2",
}


class Shard2ConversionError(ValueError):
    """The checkpoint is not something this converter and the C++ architecture agree on."""


def _require(condition: bool, message: str) -> None:
    if not condition:
        raise Shard2ConversionError(message)


@ModelBase.register("Shard2ForCausalLM")
class Shard2Model(TextModel):
    model_arch = gguf.MODEL_ARCH.SHARD2

    def __init__(self, *args, **kwargs):
        # Filled by index_tensors, which the base class calls from its constructor.
        self._checkpoint_config: dict[str, Any] = {}
        self._unused_shape: tuple[int, ...] | None = None
        super().__init__(*args, **kwargs)
        hp = self.hparams

        _require(hp.get("architectures") == ["Shard2ForCausalLM"] and hp.get("model_type") == "shard2",
                 "config.json does not describe a Shard-2 checkpoint (architectures / model_type)")
        for key in ("vocab_size", "hidden_size", "num_hidden_layers", "num_attention_heads", "num_key_value_heads", "head_dim",
                    "intermediate_size", "max_position_embeddings", "trained_sequence_length", "rope_theta", "sliding_window",
                    "global_attention_every", "backspace_token_id", "backspace_token", "pad_token_id", "eos_token_id", "bos_token_id"):
            _require(key in hp, f"config.json has no {key!r}")

        self.n_embd: int = hp["hidden_size"]
        self.n_ff: int = hp["intermediate_size"]
        self.n_head: int = hp["num_attention_heads"]
        self.n_layer: int = hp["num_hidden_layers"]
        self.n_vocab: int = hp["vocab_size"]
        _require(self.n_embd == self.n_head * hp["head_dim"], "hidden_size is not num_attention_heads x head_dim")
        _require(hp["head_dim"] % 2 == 0, "the head size is odd, the rotary encoding needs pairs")
        # The C++ graph cuts the fused projection into three equal parts.
        _require(hp["num_key_value_heads"] == self.n_head, "grouped-query attention (num_key_value_heads != num_attention_heads) is not ported")
        for key, wanted in (("qk_norm", True), ("sandwich_norm", True), ("no_rope_on_global_layers", True), ("tie_word_embeddings", True),
                            ("hidden_act", "silu")):
            _require(hp.get(key) == wanted, f"config.json has {key} = {hp.get(key)!r}; the ported architecture is the one with {wanted!r}")
        _require(hp["sliding_window"] > 0 and hp["global_attention_every"] > 0, "a model without a window or without global layers is not ported")
        _require(0 < hp["trained_sequence_length"] <= hp["max_position_embeddings"], "trained_sequence_length is not within max_position_embeddings")
        # The deletion token is the one token beyond the tokenizer's, at the last id.
        _require(hp["backspace_token_id"] == self.n_vocab - 1, "backspace_token_id is not the last id of the vocabulary")

        # True for a layer that sees the window and rotates; false for one that sees everything.
        self.local_layers = [(self.n_layer - 1 - i) % hp["global_attention_every"] != 0 for i in range(self.n_layer)]
        _require(any(self.local_layers) and not all(self.local_layers), "the model must have local and global layers")

        self._check_checkpoint_config()
        self._check_tensors()

        # The base class fills general.* from the model card and, failing that, from the name of
        # the directory. Neither is an input of this converter: the name is an argument.
        self.dir_model_card = None  # type: ignore[assignment]
        if self.model_name is None:
            self.model_name = "shard2"

    def index_tensors(self, remote_hf_model_id: str | None = None) -> dict[str, Callable[[], Tensor]]:
        """The checkpoint's state dict, loaded the one safe way (see the module text)."""
        _require(remote_hf_model_id is None, "a Shard-2 checkpoint is converted from local files only")
        path = self.dir_model / CHECKPOINT
        _require(path.is_file(), f"{CHECKPOINT} is missing from {self.dir_model}")
        # weights_only=True is the whole safety argument. If it ever refuses a checkpoint, that
        # checkpoint holds something other than weights and must not be converted.
        payload = torch.load(str(path), map_location="cpu", weights_only=True)
        _require(isinstance(payload, dict) and isinstance(payload.get("model"), dict), f"{CHECKPOINT} is not a dict with a 'model' state dict")
        config = payload.get("model_config")
        self._checkpoint_config = config if isinstance(config, dict) else {}

        tensors: dict[str, Callable[[], Tensor]] = {}
        for name, tensor in payload["model"].items():
            _require(isinstance(name, str) and isinstance(tensor, torch.Tensor), f"{CHECKPOINT}: the state dict holds something that is not a named tensor")
            _require(tensor.dtype == torch.float32, f"{name} is {tensor.dtype}, expected float32")
            if name == _UNUSED_TENSOR:
                self._unused_shape = tuple(tensor.shape)
            data_gen = (lambda t=tensor: LazyTorchTensor.from_eager(t)) if self.lazy else (lambda t=tensor: t)
            titem = self.filter_tensors((name, data_gen))
            self._source_tensor_names[name] = titem[0] if titem else None
            if titem:
                tensors[titem[0]] = titem[1]
        return tensors

    @classmethod
    def filter_tensors(cls, item):
        # Left out on purpose: the forward pass of this model never reads it.
        if item[0] == _UNUSED_TENSOR:
            return None
        return super().filter_tensors(item)

    def _check_checkpoint_config(self) -> None:
        """The configuration inside the checkpoint describes the same model as config.json, with nothing else switched on."""
        hp, cc = self.hparams, self._checkpoint_config
        _require(bool(cc), f"{CHECKPOINT} carries no model_config")
        same = {"vocab_size": "vocab_size", "dim": "hidden_size", "n_heads": "num_attention_heads", "prelude_layers": "num_hidden_layers",
                "ffn_hidden": "intermediate_size", "max_seq_len": "trained_sequence_length", "max_context_len": "max_position_embeddings",
                "rope_base": "rope_theta", "attention_window": "sliding_window", "global_attention_every": "global_attention_every"}
        wrong = [f"{a} = {cc.get(a)!r} but {b} = {hp[b]!r}" for a, b in same.items() if cc.get(a) != hp[b]]
        _require(not wrong, f"{CHECKPOINT} and config.json disagree: " + "; ".join(wrong[:4]))
        wrong = [f"{key} = {cc.get(key)!r}" for key, wanted in _PORTED_CHECKPOINT_CONFIG.items() if cc.get(key) != wanted]
        _require(not wrong, f"{CHECKPOINT} switches on something the port does not compute: " + "; ".join(wrong[:4]))

    def _check_tensors(self) -> None:
        """Every tensor the graph needs is present with the expected shape, and nothing else is."""
        d, hd = self.n_embd, self.hparams["head_dim"]
        expected: dict[str, tuple[int, ...]] = {"embed.weight": (self.n_vocab, d), "final_norm.weight": (d,)}
        for i in range(self.n_layer):
            p = f"prelude.{i}."
            expected.update({
                p + "attn_norm.weight": (d,),
                p + "attn.qkv.weight": (3 * d, d),
                p + "attn.q_norm.weight": (hd,),
                p + "attn.k_norm.weight": (hd,),
                p + "attn.out.weight": (d, d),
                p + "attn_post_norm.weight": (d,),
                p + "ffn_norm.weight": (d,),
                p + "ffn.gate_up.weight": (2 * self.n_ff, d),
                p + "ffn.down.weight": (d, self.n_ff),
                p + "ffn_post_norm.weight": (d,),
            })
        found = {name: tuple(gen().shape) for name, gen in self.model_tensors.items()}
        missing = sorted(set(expected) - set(found))
        extra = sorted(set(found) - set(expected))
        _require(not missing and not extra, f"unexpected tensor set: missing {missing[:4]}, not understood {extra[:4]}")
        wrong = [f"{name}: {found[name]} instead of {shape}" for name, shape in expected.items() if found[name] != shape]
        _require(not wrong, "unexpected tensor shapes: " + "; ".join(wrong[:4]))
        _require(self._unused_shape == (1,), f"{_UNUSED_TENSOR} is not the single unused number this converter leaves out")

    def modify_tensors(self, data_torch: Tensor, name: str, bid: int | None) -> Iterable[tuple[str, Tensor]]:
        # Upstream's name table does not know this checkpoint's names; the few there are, are mapped here.
        if name == "embed.weight":
            return [(self.format_tensor_name(gguf.MODEL_TENSOR.TOKEN_EMBD), data_torch)]
        if name == "final_norm.weight":
            return [(self.format_tensor_name(gguf.MODEL_TENSOR.OUTPUT_NORM), data_torch)]
        m = re.fullmatch(r"prelude\.(\d+)\.(.+)", name)
        _require(m is not None and m.group(2) in _BLOCK_TENSORS and int(m.group(1)) == bid, f"no place in the file for the tensor {name}")
        assert m is not None
        return [(self.format_tensor_name(_BLOCK_TENSORS[m.group(2)], bid), data_torch)]

    def set_gguf_parameters(self):
        # The base class writes context_length, block_count, embedding_length, feed_forward_length,
        # the head counts, the key and value length, rope.freq_base and the file type from config.json.
        super().set_gguf_parameters()
        w = self.gguf_writer
        hp = self.hparams
        arch = gguf.MODEL_ARCH_NAMES[self.model_arch]

        # nn.RMSNorm() without an epsilon uses the machine epsilon of its input's type. config.json
        # does not carry it; it is a fact of the reference code, and exact in float32.
        w.add_layer_norm_rms_eps(float(np.finfo(np.float32).eps))

        # A local layer sees a key while query - key < sliding_window: the token itself and the
        # sliding_window - 1 before it. One flag per layer says which layers are local, another
        # which layers rotate; in this model they are the same layers.
        w.add_sliding_window(hp["sliding_window"])
        w.add_sliding_window_pattern(self.local_layers)
        w.add_rope_pattern(self.local_layers)

        w.add_uint32(f"{arch}.trained_sequence_length", hp["trained_sequence_length"])
        w.add_uint32(f"{arch}.backspace_token_id", hp["backspace_token_id"])
        # No chat template and no roles: the model continues the text it is given.
        w.add_string(f"{arch}.chat.format", "completion")

    def set_vocab(self):
        """The tokenizer's byte-level BPE, then the deletion token; all four specials are control tokens.

        The tokenizer file is read directly. The base class would go through transformers'
        AutoTokenizer, which this checkpoint has no configuration for.
        """
        from tokenizers import Tokenizer

        hp = self.hparams
        with open(self.dir_model / "tokenizer.json", encoding="utf-8") as f:
            tj = json.load(f)
        model, pre = tj["model"], tj.get("pre_tokenizer") or {}
        _require(model.get("type") == "BPE" and not model.get("byte_fallback") and not model.get("ignore_merges") and model.get("unk_token") is None,
                 "tokenizer.json is not a plain BPE model")
        _require(tj.get("normalizer") is None and tj.get("post_processor") is None, "tokenizer.json has a normaliser or a post-processor")
        _require(pre.get("type") == "ByteLevel" and not pre.get("add_prefix_space") and pre.get("use_regex", True), "tokenizer.json is not byte-level with the GPT-2 split")
        _require((tj.get("decoder") or {}).get("type") == "ByteLevel", "tokenizer.json has no byte-level decoder")

        vocab: dict[str, int] = model["vocab"]
        by_id = {i: t for t, i in vocab.items()}
        n_bpe = self.n_vocab - 1
        _require(sorted(by_id) == list(range(n_bpe)), f"tokenizer.json does not define exactly the ids 0..{n_bpe - 1}")
        added = tj.get("added_tokens", [])
        _require(all(t.get("special") and not t.get("normalized") and by_id.get(t["id"]) == t["content"] for t in added),
                 "tokenizer.json has added tokens that are not special tokens of the vocabulary")
        names = {t["content"]: t["id"] for t in added}
        _require(set(names) == {"<pad>", "<bos>", "<eos>"}, "the special tokens of tokenizer.json are not <pad>, <bos> and <eos>")
        backspace = hp["backspace_token"]
        _require(isinstance(backspace, str) and bool(backspace) and backspace not in vocab, "the deletion token is already in tokenizer.json")
        _require(hp["pad_token_id"] == names["<pad>"], "pad_token_id is not the id of <pad>")
        # The two files name ids 1 and 2 the other way round. What the weights say: the row the
        # tokenizer calls <eos> is the trained one and marks the boundary between two texts (it
        # is predicted where a text ends and followed by the start of another); the rows of
        # <pad> and <bos> never occurred in training. So that id is both of llama.cpp's
        # markers, and neither is ever added to a prompt: the reference feeds the text alone.
        boundary = names["<eos>"]
        _require({hp["bos_token_id"], hp["eos_token_id"]} == {names["<bos>"], names["<eos>"]} and hp["bos_token_id"] == boundary,
                 "config.json and tokenizer.json do not name the two markers the way this converter was verified with")

        check_ids = Tokenizer.from_file(str(self.dir_model / "tokenizer.json")).encode(_PRE_CHECK_TEXT).ids
        check_hash = hashlib.sha256(str(check_ids).encode()).hexdigest()
        _require(check_hash in _KNOWN_BPE_PRE,
                 f"unknown byte-level BPE tokenizer (check hash {check_hash}): verify its llama.cpp tokenisation against the "
                 "reference before adding it to _KNOWN_BPE_PRE")

        tokens = [by_id[i] for i in range(n_bpe)] + [backspace]
        control = set(names.values()) | {hp["backspace_token_id"]}
        merges = [" ".join(m) if isinstance(m, list) else m for m in model["merges"]]

        w = self.gguf_writer
        w.add_tokenizer_model("gpt2")
        w.add_tokenizer_pre(_KNOWN_BPE_PRE[check_hash])
        w.add_token_list([t.encode("utf-8") for t in tokens])
        # A control token is never produced from text tokenised without special-token parsing
        # and has no piece when a token is turned back into text.
        w.add_token_types([_TOKEN_CONTROL if i in control else _TOKEN_NORMAL for i in range(self.n_vocab)])
        w.add_token_merges([m.encode("utf-8") for m in merges])
        w.add_bos_token_id(boundary)
        w.add_eos_token_id(boundary)
        w.add_pad_token_id(hp["pad_token_id"])
        w.add_add_bos_token(False)
        w.add_add_eos_token(False)
        # llama.cpp defaults a BPE vocabulary to "clean up spaces" when detokenising; the reference does not.
        w.add_clean_spaces(False)

        arch = gguf.MODEL_ARCH_NAMES[self.model_arch]
        w.add_string(f"{arch}.tokenizer.kind", "byte-bpe")
        w.add_string(f"{arch}.tokenizer.source_sha256", hashlib.sha256((self.dir_model / "tokenizer.json").read_bytes()).hexdigest())
