"""GGUF conversion of the peacebell family (general.architecture = "peacebell").

This module is not part of upstream llama.cpp: native/tools/prepare-llama-src.sh copies it into
the conversion package of the patched copy, and native/convert/convert_peacebell.py drives it.

What the family is: a pre-norm decoder with RMSNorm, a fused q/k/v projection, NeoX RoPE over
the whole head, an ungated exact-erf GELU MLP, one ReZero scalar per sublayer, and an output
head tied to the token embedding. No tensor is transformed on the way into the file, so an F32
conversion carries the checkpoint's bytes unchanged. The ReZero scalars become two float32
metadata arrays that the C++ graph applies as a multiply; they are deliberately not folded into
the output projections, because folding would push a fifth of layer 0 into the half-precision
subnormal range of a 16-bit file.

Two tokenizer kinds exist in the family and the checkpoint's config.json says which one it has:

- no `train_pad_id`: a SentencePiece BPE model (sentencepiece.model) and ChatML built by
  injecting the two marker ids around separately encoded segments ("chatml-ids");
- `train_pad_id` present: a byte-level BPE (tokenizer.json) and a training pad token that must
  never be sampled. Such a checkpoint is a chat model when its tokenizer_config.json carries
  the plain-text ChatML template ("chatml-text"), and a model that continues text, with no
  roles and no template, when it carries none ("completion").

The result is recorded as `peacebell.chat.format`, which is what tells the native library and
the application how a prompt for this file is built.

The output depends only on the files named in INPUT_FILES and on the arguments: not on the
directory's name, the model card or the clock. The same input therefore gives the same bytes.
"""
from __future__ import annotations

import hashlib
import json
import re

from typing import Any, Iterable, TYPE_CHECKING

if TYPE_CHECKING:
    from torch import Tensor

from .base import LazyTorchTensor, ModelBase, TextModel, gguf, logger

# The files a conversion reads, by tokenizer kind; the last two of the second kind only when
# they exist. Anything else in the directory is ignored, so these are the files to pin by hash.
INPUT_FILES = {
    "sentencepiece": ("config.json", "model.safetensors", "sentencepiece.model"),
    "bpe": ("config.json", "model.safetensors", "tokenizer.json", "tokenizer_config.json", "generation_config.json"),
}

IM_START = "<|im_start|>"
IM_END = "<|im_end|>"

# SentencePiece piece types; gguf.TokenType uses the same numbers.
_SP_NORMAL, _SP_UNKNOWN, _SP_CONTROL, _SP_USER_DEFINED, _SP_UNUSED, _SP_BYTE = 1, 2, 3, 4, 5, 6
_SP_MODEL_TYPE_BPE = 2

# llama.cpp identifies a byte-level BPE pre-tokenizer by hashing the ids of this text. It is the
# text upstream's converter uses, repeated here so that the hashes below stay meaningful if
# upstream ever changes its copy.
_PRE_CHECK_TEXT = '\n \n\n \n\n\n \t \t\t \t\n  \n   \n    \n     \n🚀 (normal) 😶‍🌫️ (multiple emojis concatenated) ✅ 🦙🦙 3 33 333 3333 33333 333333 3333333 33333333 3.3 3..3 3...3 កាន់តែពិសេសអាច😁 ?我想在apple工作1314151天～ ------======= нещо на Български \'\'\'\'\'\'```````""""......!!!!!!?????? I\'ve been \'told he\'s there, \'RE you sure? \'M not sure I\'ll make it, \'D you like some tea? We\'Ve a\'lL'

# The chat template of the byte-level BPE chat checkpoints: the one template that the native
# plain-text encoder (pp_chatml_text_tokenize) writes out. A checkpoint with another template
# would be rendered by that encoder in a way it was not trained on, so it is refused here.
_CHATML_TEXT_TEMPLATE = ("{% for m in messages %}<|im_start|>{{ m['role'] }}\n{{ m['content'] | trim }}<|im_end|>\n{% endfor %}"
                         "{% if add_generation_prompt %}<|im_start|>assistant\n{% endif %}")

# Byte-level BPE tokenizers whose llama.cpp tokenisation was verified token for token against
# the reference, with the pre-tokenizer name that reproduces them. An unknown hash stops the
# conversion: verify the new tokenizer first, then add its hash.
_KNOWN_BPE_PRE = {
    "665bc70bcb63e5d084887e25deb0ecf571ff6e818ca0f4d8c0155f49772a3b24": "gpt-2",
}


class PeacebellConversionError(ValueError):
    """The checkpoint is not something this converter and the C++ architecture agree on."""


def _require(condition: bool, message: str) -> None:
    if not condition:
        raise PeacebellConversionError(message)


@ModelBase.register("GPTForCausalLM", "PeacebellForCausalLM")
class PeacebellModel(TextModel):
    model_arch = gguf.MODEL_ARCH.PEACEBELL

    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        hp = self.hparams

        _require(hp.get("architectures") == ["GPTForCausalLM"] and hp.get("model_type") == "gpt",
                 "config.json does not describe a peacebell checkpoint (architectures / model_type)")
        for key in ("n_embd", "n_head", "n_layer", "block_size", "vocab_size", "rms_norm_eps", "rope_theta", "rezero_scale_init"):
            _require(key in hp, f"config.json has no {key!r}")

        self.n_embd: int = hp["n_embd"]
        self.n_head: int = hp["n_head"]
        self.n_layer: int = hp["n_layer"]
        self.n_vocab: int = hp["vocab_size"]
        _require(self.n_embd % self.n_head == 0, "n_embd is not a multiple of n_head")
        _require((self.n_embd // self.n_head) % 2 == 0, "the head size is odd, RoPE needs pairs")
        _require(hp.get("tie_word_embeddings", True) is True, "the output head is not tied to the embedding")

        self.tokenizer_kind = "bpe" if hp.get("train_pad_id") is not None else "sentencepiece"
        has_spm = (self.dir_model / "sentencepiece.model").is_file()
        has_bpe = (self.dir_model / "tokenizer.json").is_file()
        if self.tokenizer_kind == "bpe":
            _require(has_bpe and not has_spm, "config.json has train_pad_id, so the directory must hold tokenizer.json and no sentencepiece.model")
            self.chat_template = self._read_chat_template()
            self.chat_format = "chatml-text" if self.chat_template is not None else "completion"
        else:
            self.chat_template = None
            self.chat_format = "chatml-ids"
            _require(has_spm and not has_bpe, "config.json has no train_pad_id, so the directory must hold sentencepiece.model and no tokenizer.json")
            _require(hp.get("im_start_id") is not None and hp.get("im_end_id") is not None, "config.json has no im_start_id / im_end_id")

        self._check_tensors()

        # ReZero scalars, filled by modify_tensors as the tensor stream passes.
        self._attn_scale: list[float | None] = [None] * self.n_layer
        self._mlp_scale: list[float | None] = [None] * self.n_layer

        # The base class fills general.* from the model card and, failing that, from the name of
        # the directory. Neither is an input of this converter: the name is an argument.
        self.dir_model_card = None  # type: ignore[assignment]
        if self.model_name is None:
            self.model_name = "peacebell"

    def _read_chat_template(self) -> str | None:
        """The chat template of a byte-level BPE checkpoint, or None for one that continues text."""
        tokenizer_config = self.dir_model / "tokenizer_config.json"
        if not tokenizer_config.is_file():
            return None
        with open(tokenizer_config, encoding="utf-8") as f:
            chat_template = json.load(f).get("chat_template")
        if chat_template is None:
            return None
        _require(chat_template == _CHATML_TEXT_TEMPLATE,
                 "tokenizer_config.json has a chat template other than the plain-text ChatML one that the native encoder "
                 "implements; conversations of this checkpoint cannot be rendered as it was trained")
        return chat_template

    def _check_tensors(self) -> None:
        """Every tensor the graph needs is present with the expected shape, and nothing else is."""
        d, v = self.n_embd, self.n_vocab
        expected: dict[str, tuple[int, ...]] = {"wte.weight": (v, d), "ln_f.weight": (d,)}
        for i in range(self.n_layer):
            expected.update({
                f"h.{i}.ln_1.weight": (d,),
                f"h.{i}.attn.c_attn.weight": (3 * d, d),
                f"h.{i}.attn.c_proj.weight": (d, d),
                f"h.{i}.attn_scale": (1,),
                f"h.{i}.ln_2.weight": (d,),
                f"h.{i}.mlp.c_fc.weight": (4 * d, d),
                f"h.{i}.mlp.c_proj.weight": (d, 4 * d),
                f"h.{i}.mlp_scale": (1,),
            })
        found = {name: tuple(gen().shape) for name, gen in self.model_tensors.items()}
        missing = sorted(set(expected) - set(found))
        extra = sorted(set(found) - set(expected))
        _require(not missing and not extra, f"unexpected tensor set: missing {missing[:4]}, not understood {extra[:4]}")
        wrong = [f"{name}: {found[name]} instead of {shape}" for name, shape in expected.items() if found[name] != shape]
        _require(not wrong, "unexpected tensor shapes: " + "; ".join(wrong[:4]))

    def modify_tensors(self, data_torch: Tensor, name: str, bid: int | None) -> Iterable[tuple[str, Tensor]]:
        m = re.fullmatch(r"h\.(\d+)\.(attn|mlp)_scale", name)
        if m is None:
            return super().modify_tensors(data_torch, name, bid)
        # A ReZero scalar leaves the tensor stream here and comes back as metadata.
        value = float(LazyTorchTensor.to_eager(data_torch).reshape(-1)[0])
        (self._attn_scale if m.group(2) == "attn" else self._mlp_scale)[int(m.group(1))] = value
        return []

    def set_gguf_parameters(self):
        # The base class writes block_count, embedding_length, head_count, rope.freq_base, the RMS
        # epsilon and the file type from config.json; the rest has no standard config name.
        super().set_gguf_parameters()
        w = self.gguf_writer
        arch = gguf.MODEL_ARCH_NAMES[self.model_arch]

        w.add_context_length(self.hparams["block_size"])
        w.add_feed_forward_length(4 * self.n_embd)

        _require(all(s is not None for s in self._attn_scale + self._mlp_scale), "a ReZero scalar is missing")
        w.add_array(f"{arch}.attention.output_scale", list(self._attn_scale))
        w.add_array(f"{arch}.feed_forward.output_scale", list(self._mlp_scale))

        w.add_string(f"{arch}.chat.format", self.chat_format)

    def set_vocab(self):
        if self.tokenizer_kind == "bpe":
            self._set_vocab_bpe()
        else:
            self._set_vocab_sentencepiece_bpe()

    def _sha256(self, file_name: str) -> str:
        return hashlib.sha256((self.dir_model / file_name).read_bytes()).hexdigest()

    def _set_vocab_sentencepiece_bpe(self) -> None:
        """A SentencePiece BPE model, written so that the native front-end can reproduce sp.encode.

        llama.cpp's "llama" vocabulary reproduces the BPE merges exactly, but not SentencePiece's
        normaliser or its handling of user-defined symbols. Those live in the front-end of our
        native library, which implements exactly one normaliser configuration. This method
        refuses any other, and records what it saw so the front-end can check at load time that
        the file in front of it is one it understands.
        """
        from sentencepiece import sentencepiece_model_pb2 as sp_pb2

        proto: Any = sp_pb2.ModelProto()  # ty: ignore[unresolved-attribute]
        proto.ParseFromString((self.dir_model / "sentencepiece.model").read_bytes())
        trainer, normalizer = proto.trainer_spec, proto.normalizer_spec
        pieces = proto.pieces
        hp = self.hparams
        im_start, im_end, unk = hp["im_start_id"], hp["im_end_id"], hp.get("unk_pad_id", 0)

        _require(len(pieces) == self.n_vocab, f"sentencepiece.model has {len(pieces)} pieces, config.json says {self.n_vocab}")
        _require(trainer.model_type == _SP_MODEL_TYPE_BPE, "the SentencePiece model is not BPE")
        _require(trainer.byte_fallback and not trainer.treat_whitespace_as_suffix, "unsupported SentencePiece trainer options")
        _require(normalizer.name == "identity" and len(normalizer.precompiled_charsmap) == 0
                 and normalizer.add_dummy_prefix and normalizer.remove_extra_whitespaces and normalizer.escape_whitespaces,
                 "unsupported SentencePiece normaliser: the native front-end implements only identity + dummy prefix + "
                 "remove extra whitespaces + escape whitespaces")
        _require(len(proto.denormalizer_spec.precompiled_charsmap) == 0, "unsupported SentencePiece denormaliser")
        _require(pieces[im_start].piece == IM_START and pieces[im_end].piece == IM_END
                 and pieces[im_start].type == _SP_USER_DEFINED and pieces[im_end].type == _SP_USER_DEFINED,
                 "im_start_id / im_end_id do not name the ChatML markers as user-defined symbols")
        _require(pieces[unk].type == _SP_UNKNOWN, "unk_pad_id does not name the unknown piece")

        user_defined = [(i, p.piece) for i, p in enumerate(pieces) if p.type == _SP_USER_DEFINED]
        # The front-end matches symbols on the raw text and collapses spaces between them; a symbol
        # with a space (raw or escaped) in it would need SentencePiece's full prefix matcher.
        _require(all(s and " " not in s and "▁" not in s for _, s in user_defined), "a user-defined symbol contains a space")

        tokens = [p.piece.encode("utf-8") for p in pieces]
        scores = [p.score for p in pieces]
        # The two markers are written as CONTROL: plain tokenisation then never produces them from
        # typed text, llama.cpp renders them as nothing when detokenising, and its own load-time
        # retyping of "<|im_end|>" has nothing left to do. The chat builder injects their ids.
        toktypes = [_SP_CONTROL if i in (im_start, im_end) else int(p.type) for i, p in enumerate(pieces)]

        w = self.gguf_writer
        w.add_tokenizer_model("llama")
        w.add_tokenizer_pre("default")
        w.add_token_list(tokens)
        w.add_token_scores(scores)
        w.add_token_types(toktypes)
        w.add_bos_token_id(hp["bos_token_id"])
        w.add_eos_token_id(hp["eos_token_id"])
        w.add_eot_token_id(im_end)
        w.add_unk_token_id(unk)
        w.add_pad_token_id(hp["pad_token_id"])
        w.add_add_bos_token(False)
        w.add_add_eos_token(False)
        # The front-end supplies the single dummy prefix itself and tokenises run by run.
        w.add_add_space_prefix(False)
        w.add_remove_extra_whitespaces(True)
        # The unknown piece doubles as the training pad: its output row is untrained.
        w.add_suppress_tokens([unk])

        arch = gguf.MODEL_ARCH_NAMES[self.model_arch]
        w.add_string(f"{arch}.tokenizer.kind", "sentencepiece-bpe")
        w.add_string(f"{arch}.tokenizer.source_sha256", self._sha256("sentencepiece.model"))
        w.add_string(f"{arch}.tokenizer.spm.normalizer.name", normalizer.name)
        w.add_bool(f"{arch}.tokenizer.spm.normalizer.add_dummy_prefix", bool(normalizer.add_dummy_prefix))
        w.add_bool(f"{arch}.tokenizer.spm.normalizer.remove_extra_whitespaces", bool(normalizer.remove_extra_whitespaces))
        w.add_bool(f"{arch}.tokenizer.spm.normalizer.escape_whitespaces", bool(normalizer.escape_whitespaces))
        w.add_uint32(f"{arch}.tokenizer.spm.normalizer.precompiled_charsmap_size", len(normalizer.precompiled_charsmap))
        w.add_bool(f"{arch}.tokenizer.spm.byte_fallback", bool(trainer.byte_fallback))
        w.add_bool(f"{arch}.tokenizer.spm.treat_whitespace_as_suffix", bool(trainer.treat_whitespace_as_suffix))
        # All user-defined symbols as SentencePiece has them, the two markers included. The count
        # is a scalar because llama.cpp's metadata API cannot read arrays back.
        w.add_uint32(f"{arch}.tokenizer.spm.user_defined_symbol_count", len(user_defined))
        w.add_array(f"{arch}.tokenizer.spm.user_defined_symbol_ids", [i for i, _ in user_defined])
        w.add_array(f"{arch}.tokenizer.spm.user_defined_symbols", [s for _, s in user_defined])
        w.add_uint32(f"{arch}.chat.start_token_id", im_start)
        w.add_uint32(f"{arch}.chat.end_token_id", im_end)

    def _set_vocab_bpe(self) -> None:
        """A GPT-2 style byte-level BPE whose only special token is the end-of-text token.

        ChatML, where a checkpoint has it, is plain text: the markers are not in the vocabulary,
        the whole conversation is tokenised in one call and generation stops on the marker string.
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
        special = {t["id"] for t in tj.get("added_tokens", []) if t.get("special")}
        _require(all(not t.get("special") or by_id.get(t["id"]) == t["content"] for t in tj.get("added_tokens", [])), "an added token is not in the vocabulary")
        _require(all(t.get("special") for t in tj.get("added_tokens", [])), "tokenizer.json has added tokens that are not special")

        check_ids = Tokenizer.from_file(str(self.dir_model / "tokenizer.json")).encode(_PRE_CHECK_TEXT).ids
        check_hash = hashlib.sha256(str(check_ids).encode()).hexdigest()
        _require(check_hash in _KNOWN_BPE_PRE,
                 f"unknown byte-level BPE tokenizer (check hash {check_hash}): verify its llama.cpp tokenisation against the "
                 "reference before adding it to _KNOWN_BPE_PRE")

        eos, pad, train_pad = hp["eos_token_id"], hp["pad_token_id"], hp["train_pad_id"]
        _require(eos in special, "eos_token_id is not a special token of tokenizer.json")
        _require(0 <= train_pad < self.n_vocab and train_pad not in special, "train_pad_id is out of range or a special token")

        # Tokens that must never be sampled: whatever the checkpoint's generation config lists,
        # plus the training pad, whose output row only ever saw the padding of the training batches.
        suppress = {train_pad}
        generation_config = self.dir_model / "generation_config.json"
        if generation_config.is_file():
            with open(generation_config, encoding="utf-8") as f:
                suppress.update(json.load(f).get("suppress_tokens") or [])
        _require(all(isinstance(t, int) and 0 <= t < self.n_vocab for t in suppress), "a suppressed token id is out of range")

        merges = [" ".join(m) if isinstance(m, list) else m for m in model["merges"]]

        w = self.gguf_writer
        w.add_tokenizer_model("gpt2")
        w.add_tokenizer_pre(_KNOWN_BPE_PRE[check_hash])
        w.add_token_list([by_id[i].encode("utf-8") for i in range(self.n_vocab)])
        w.add_token_types([_SP_CONTROL if i in special else _SP_NORMAL for i in range(self.n_vocab)])
        w.add_token_merges([m.encode("utf-8") for m in merges])
        w.add_eos_token_id(eos)
        w.add_pad_token_id(pad)
        w.add_add_bos_token(False)
        w.add_add_eos_token(False)
        # llama.cpp defaults a BPE vocabulary to "clean up spaces" when detokenising; the reference does not.
        w.add_clean_spaces(False)
        w.add_suppress_tokens(sorted(suppress))

        if self.chat_template is not None:
            w.add_chat_template(self.chat_template)

        arch = gguf.MODEL_ARCH_NAMES[self.model_arch]
        w.add_string(f"{arch}.tokenizer.kind", "byte-bpe")
        w.add_string(f"{arch}.tokenizer.source_sha256", self._sha256("tokenizer.json"))
        logger.info(f"gguf: byte-level BPE, suppressed tokens {sorted(suppress)}")
