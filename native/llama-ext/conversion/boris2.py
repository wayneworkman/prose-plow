"""GGUF conversion of Boris-2 (general.architecture = "boris2").

This module is not part of upstream llama.cpp: native/tools/prepare-llama-src.sh copies it into
the conversion package of the patched copy, and native/convert/convert_boris2.py drives it.

What the model is: a pre-norm decoder with RMSNorm, a fused q/k/v projection, NeoX RoPE over
the whole head, a fused SwiGLU MLP and an output head tied to the token embedding, with two
additions.

- Canon: after the fused projection every channel is mixed with its own values at the two
  preceding positions, `y[t] = x[t] + w0 * x[t] + w1 * x[t-1] + w2 * x[t-2]`. The C++ graph
  computes this with ggml's causal depthwise convolution over `[x[t-2], x[t-1], x[t]]`, so the
  stored kernel is the checkpoint's three columns in reverse order with the identity folded
  into the tap of the current position: `[w2, w1, 1 + w0]`. This is the only tensor that is
  transformed; an F32 conversion carries every other tensor's bytes unchanged.
- An n-gram embedding: a table row chosen by an exact lookup of the token pair or triple that
  ends at a position, projected to the model width and added to the residual stream before one
  block. The lookup keys are 64-bit integers and are needed on the host only, so the four
  sorted key and row arrays become GGUF metadata arrays, not tensors.

Float types of a 16-bit file: matrices and the n-gram table follow the file type; norm weights,
the Canon kernels (ggml's convolution takes an F32 kernel) and the n-gram projection (it feeds
the residual stream of every token) stay float32.

The tokenizer is a GPT-2 style byte-level BPE. ChatML is plain text for this model: the markers
are not in the vocabulary, a conversation is tokenised in one call and generation stops on the
marker string.

The output depends only on the files named in INPUT_FILES and on the arguments: not on the
directory's name, the model card or the clock. The same input therefore gives the same bytes.
"""
from __future__ import annotations

import hashlib
import json
import re

from typing import Iterable, TYPE_CHECKING

import numpy as np
import torch

if TYPE_CHECKING:
    from torch import Tensor

from .base import ModelBase, TextModel, gguf

# The files a conversion reads. Anything else in the directory is ignored, so these are the
# files to pin by hash.
INPUT_FILES = ("config.json", "model.safetensors", "tokenizer.json", "tokenizer_config.json")

# The integer buffers of the n-gram lookup: (checkpoint tensor, metadata key suffix, config key with the length).
_NGRAM_BUFFERS = (
    ("model.ngram.bigram_keys", "bigram_keys", "ngram_bigram_entries"),
    ("model.ngram.bigram_rows", "bigram_rows", "ngram_bigram_entries"),
    ("model.ngram.trigram_keys", "trigram_keys", "ngram_trigram_entries"),
    ("model.ngram.trigram_rows", "trigram_rows", "ngram_trigram_entries"),
)

_TOKEN_NORMAL, _TOKEN_CONTROL = 1, 3  # gguf.TokenType

# llama.cpp identifies a byte-level BPE pre-tokenizer by hashing the ids of this text. It is the
# text upstream's converter uses, repeated here so that the hash below stays meaningful if
# upstream ever changes its copy.
_PRE_CHECK_TEXT = '\n \n\n \n\n\n \t \t\t \t\n  \n   \n    \n     \n🚀 (normal) 😶‍🌫️ (multiple emojis concatenated) ✅ 🦙🦙 3 33 333 3333 33333 333333 3333333 33333333 3.3 3..3 3...3 កាន់តែពិសេសអាច😁 ?我想在apple工作1314151天～ ------======= нещо на Български \'\'\'\'\'\'```````""""......!!!!!!?????? I\'ve been \'told he\'s there, \'RE you sure? \'M not sure I\'ll make it, \'D you like some tea? We\'Ve a\'lL'

# Tokenizers whose llama.cpp tokenisation was verified token for token against the reference,
# with the pre-tokenizer name that reproduces them. An unknown hash stops the conversion:
# verify the new tokenizer first, then add its hash.
_KNOWN_BPE_PRE = {
    "665bc70bcb63e5d084887e25deb0ecf571ff6e818ca0f4d8c0155f49772a3b24": "gpt-2",
}


class Boris2ConversionError(ValueError):
    """The checkpoint is not something this converter and the C++ architecture agree on."""


def _require(condition: bool, message: str) -> None:
    if not condition:
        raise Boris2ConversionError(message)


@ModelBase.register("Boris2ForCausalLM")
class Boris2Model(TextModel):
    model_arch = gguf.MODEL_ARCH.BORIS2

    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        hp = self.hparams

        _require(hp.get("architectures") == ["Boris2ForCausalLM"] and hp.get("model_type") == "boris2",
                 "config.json does not describe a Boris-2 checkpoint (architectures / model_type)")
        for key in ("hidden_size", "intermediate_size", "num_hidden_layers", "num_attention_heads", "num_key_value_heads",
                    "max_position_embeddings", "vocab_size", "rms_norm_eps", "rope_theta", "canon_kernel", "ngram_insert_after",
                    "ngram_rank", "ngram_rows", "ngram_oov_row", "ngram_bigram_entries", "ngram_trigram_entries", "eos_token_id"):
            _require(key in hp, f"config.json has no {key!r}")

        self.n_embd: int = hp["hidden_size"]
        self.n_ff: int = hp["intermediate_size"]
        self.n_head: int = hp["num_attention_heads"]
        self.n_layer: int = hp["num_hidden_layers"]
        self.n_vocab: int = hp["vocab_size"]
        self.n_qkv: int = 3 * self.n_embd
        _require(self.n_embd % self.n_head == 0, "hidden_size is not a multiple of num_attention_heads")
        _require((self.n_embd // self.n_head) % 2 == 0, "the head size is odd, RoPE needs pairs")
        # The C++ graph cuts the fused projection into three equal parts.
        _require(hp["num_key_value_heads"] == self.n_head, "grouped-query attention (num_key_value_heads != num_attention_heads) is not ported")
        # Two saved columns of history per layer: the C++ state row is sized for exactly this kernel.
        _require(hp["canon_kernel"] == 3, "only a Canon kernel of 3 taps is ported")
        _require(hp.get("tie_word_embeddings", True) is True, "the output head is not tied to the embedding")
        _require(0 <= hp["ngram_insert_after"] < self.n_layer, "ngram_insert_after is not a block index")
        _require(0 <= hp["ngram_oov_row"] < hp["ngram_rows"], "ngram_oov_row is not a row of the table")
        # A trigram key is (a * V + b) * V + c: it must fit the unsigned 64-bit metadata type.
        _require(self.n_vocab ** 3 <= 2 ** 63, "the vocabulary is too large for 64-bit trigram keys")

        self._check_tensors()

        # The base class fills general.* from the model card and, failing that, from the name of
        # the directory. Neither is an input of this converter: the name is an argument.
        self.dir_model_card = None  # type: ignore[assignment]
        if self.model_name is None:
            self.model_name = "boris2"

    @classmethod
    def filter_tensors(cls, item):
        # The integer lookup buffers never enter the tensor stream, which would cast them to
        # float32 (a trigram key needs 45 bits); set_gguf_parameters reads them from the file.
        if item[0] in {name for name, _, _ in _NGRAM_BUFFERS}:
            return None
        return super().filter_tensors(item)

    def _check_tensors(self) -> None:
        """Every tensor the graph needs is present with the expected shape, and nothing else is."""
        d, v, hp = self.n_embd, self.n_vocab, self.hparams
        expected: dict[str, tuple[int, ...]] = {
            "model.embed_tokens.weight": (v, d),
            "model.norm.weight": (d,),
            "model.ngram.table": (hp["ngram_rows"], hp["ngram_rank"]),
            "model.ngram.proj": (d, hp["ngram_rank"]),
        }
        for i in range(self.n_layer):
            p = f"model.layers.{i}."
            expected.update({
                p + "input_layernorm.weight": (d,),
                p + "self_attn.qkv_proj.weight": (self.n_qkv, d),
                p + "self_attn.canon": (self.n_qkv, 3),
                p + "self_attn.o_proj.weight": (d, d),
                p + "post_attention_layernorm.weight": (d,),
                p + "mlp.gate_up_proj.weight": (2 * self.n_ff, d),
                p + "mlp.down_proj.weight": (d, self.n_ff),
            })
        found = {name: tuple(gen().shape) for name, gen in self.model_tensors.items()}
        missing = sorted(set(expected) - set(found))
        extra = sorted(set(found) - set(expected))
        _require(not missing and not extra, f"unexpected tensor set: missing {missing[:4]}, not understood {extra[:4]}")
        wrong = [f"{name}: {found[name]} instead of {shape}" for name, shape in expected.items() if found[name] != shape]
        _require(not wrong, "unexpected tensor shapes: " + "; ".join(wrong[:4]))

    def modify_tensors(self, data_torch: Tensor, name: str, bid: int | None) -> Iterable[tuple[str, Tensor]]:
        if re.fullmatch(r"model\.layers\.\d+\.self_attn\.canon", name):
            # Checkpoint columns weight positions t, t-1, t-2 and leave out the identity; the
            # convolution window is ordered t-2, t-1, t and has no separate identity term.
            kernel = torch.stack([data_torch[:, 2], data_torch[:, 1], 1.0 + data_torch[:, 0]], dim=1)
            return [(self.format_tensor_name(gguf.MODEL_TENSOR.SHORTCONV_CONV, bid), kernel)]
        if name == "model.ngram.table":
            return [(self.format_tensor_name(gguf.MODEL_TENSOR.PER_LAYER_TOKEN_EMBD), data_torch)]
        if name == "model.ngram.proj":
            return [(self.format_tensor_name(gguf.MODEL_TENSOR.PER_LAYER_MODEL_PROJ), data_torch)]
        return super().modify_tensors(data_torch, name, bid)

    def tensor_force_quant(self, name: str, new_name: str, bid: int | None, n_dims: int) -> gguf.GGMLQuantizationType | bool:
        # The projection is 32,768 values that feed the residual stream of every token: it stays
        # float32 in every file type. (The Canon kernels are kept float32 by the base class.)
        if new_name == self.format_tensor_name(gguf.MODEL_TENSOR.PER_LAYER_MODEL_PROJ):
            return gguf.GGMLQuantizationType.F32
        return super().tensor_force_quant(name, new_name, bid, n_dims)

    def _ngram_buffers(self) -> dict[str, np.ndarray]:
        """The four lookup buffers as int64 arrays, checked for everything the C++ lookup relies on."""
        hp = self.hparams
        buffers: dict[str, np.ndarray] = {}
        with gguf.utility.SafetensorsLocal(self.dir_model / "model.safetensors") as tensors:
            for source, key, length_key in _NGRAM_BUFFERS:
                _require(source in tensors, f"model.safetensors has no {source}")
                tensor = tensors[source]
                _require(tensor.dtype == "I64" and tensor.shape == (hp[length_key],),
                         f"{source} is {tensor.dtype}{list(tensor.shape)}, expected I64[{hp[length_key]}]")
                buffers[key] = np.frombuffer(tensor.mmap_bytes().tobytes(), dtype="<i8")
        for kind, n_tokens in (("bigram", 2), ("trigram", 3)):
            keys, rows = buffers[f"{kind}_keys"], buffers[f"{kind}_rows"]
            # The lookup is a binary search for an exact key.
            _require(len(keys) == 0 or (int(keys[0]) >= 0 and bool(np.all(keys[1:] > keys[:-1]))),
                     f"the {kind} keys are not non-negative and strictly increasing")
            _require(len(keys) == 0 or int(keys[-1]) < self.n_vocab ** n_tokens, f"a {kind} key is not a key of {n_tokens} token ids")
            _require(len(rows) == 0 or (int(rows.min()) >= 0 and int(rows.max()) < hp["ngram_rows"]), f"a {kind} row is outside the table")
        return buffers

    def set_gguf_parameters(self):
        # The base class writes context_length, block_count, embedding_length, feed_forward_length,
        # the head counts, rope.freq_base, the RMS epsilon and the file type from config.json.
        super().set_gguf_parameters()
        w = self.gguf_writer
        hp = self.hparams
        arch = gguf.MODEL_ARCH_NAMES[self.model_arch]

        w.add_shortconv_l_cache(hp["canon_kernel"])
        w.add_uint32(f"{arch}.ngram.row_count", hp["ngram_rows"])
        w.add_uint32(f"{arch}.ngram.embedding_length", hp["ngram_rank"])
        w.add_uint32(f"{arch}.ngram.insert_layer", hp["ngram_insert_after"])
        w.add_uint32(f"{arch}.ngram.oov_row", hp["ngram_oov_row"])
        for key, values in self._ngram_buffers().items():
            # The element type is given explicitly: the writer would otherwise guess INT32 from
            # the first Python int, and a trigram key does not fit.
            sub_type = gguf.GGUFValueType.UINT64 if key.endswith("_keys") else gguf.GGUFValueType.INT32
            w.add_key_value(f"{arch}.ngram.{key}", values.tolist(), gguf.GGUFValueType.ARRAY, sub_type=sub_type)

        w.add_string(f"{arch}.chat.format", "chatml-text")

    def set_vocab(self):
        """A GPT-2 style byte-level BPE whose only special token is the end-of-text token.

        The tokenizer file is read directly. The base class would go through transformers'
        AutoTokenizer, which for a checkpoint with custom code asks whether to run it.
        """
        from tokenizers import Tokenizer

        hp = self.hparams
        with open(self.dir_model / "tokenizer.json", encoding="utf-8") as f:
            tj = json.load(f)
        model, pre = tj["model"], tj.get("pre_tokenizer") or {}
        _require(model.get("type") == "BPE" and not model.get("byte_fallback") and not model.get("ignore_merges"), "tokenizer.json is not a plain BPE model")
        _require(tj.get("normalizer") is None, "tokenizer.json has a normaliser")
        _require(pre.get("type") == "ByteLevel" and not pre.get("add_prefix_space") and pre.get("use_regex", True), "tokenizer.json is not byte-level with the GPT-2 split")
        _require((tj.get("decoder") or {}).get("type") == "ByteLevel", "tokenizer.json has no byte-level decoder")

        vocab: dict[str, int] = model["vocab"]
        by_id = {i: t for t, i in vocab.items()}
        _require(sorted(by_id) == list(range(self.n_vocab)), f"tokenizer.json does not define exactly the ids 0..{self.n_vocab - 1}")
        added = tj.get("added_tokens", [])
        _require(all(t.get("special") and by_id.get(t["id"]) == t["content"] for t in added), "tokenizer.json has added tokens that are not special tokens of the vocabulary")
        special = {t["id"] for t in added}

        check_ids = Tokenizer.from_file(str(self.dir_model / "tokenizer.json")).encode(_PRE_CHECK_TEXT).ids
        check_hash = hashlib.sha256(str(check_ids).encode()).hexdigest()
        _require(check_hash in _KNOWN_BPE_PRE,
                 f"unknown byte-level BPE tokenizer (check hash {check_hash}): verify its llama.cpp tokenisation against the "
                 "reference before adding it to _KNOWN_BPE_PRE")

        # The C++ graph treats a conversation as one document, which is exact only while the
        # end-of-text token is never fed. That holds because it is the one special token: text is
        # tokenised without special-token parsing and can then never produce it.
        eos, pad = hp["eos_token_id"], hp.get("pad_token_id")
        _require(special == {eos}, "the end-of-text token is not the only special token of tokenizer.json")
        _require(hp.get("bos_token_id") is None, "a beginning-of-text token is not expected")

        merges = [" ".join(m) if isinstance(m, list) else m for m in model["merges"]]

        w = self.gguf_writer
        w.add_tokenizer_model("gpt2")
        w.add_tokenizer_pre(_KNOWN_BPE_PRE[check_hash])
        w.add_token_list([by_id[i].encode("utf-8") for i in range(self.n_vocab)])
        w.add_token_types([_TOKEN_CONTROL if i in special else _TOKEN_NORMAL for i in range(self.n_vocab)])
        w.add_token_merges([m.encode("utf-8") for m in merges])
        w.add_eos_token_id(eos)
        if pad is not None:
            w.add_pad_token_id(pad)
        w.add_add_bos_token(False)
        w.add_add_eos_token(False)
        # llama.cpp defaults a BPE vocabulary to "clean up spaces" when detokenising; the reference does not.
        w.add_clean_spaces(False)

        with open(self.dir_model / "tokenizer_config.json", encoding="utf-8") as f:
            chat_template = json.load(f).get("chat_template")
        _require(isinstance(chat_template, str) and bool(chat_template), "tokenizer_config.json has no chat template")
        w.add_chat_template(chat_template)

        arch = gguf.MODEL_ARCH_NAMES[self.model_arch]
        w.add_string(f"{arch}.tokenizer.kind", "byte-bpe")
        w.add_string(f"{arch}.tokenizer.source_sha256", hashlib.sha256((self.dir_model / "tokenizer.json").read_bytes()).hexdigest())
