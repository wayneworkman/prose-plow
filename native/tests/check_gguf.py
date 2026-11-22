"""Checks of a converted GGUF file of one of our architectures against the checkpoint it was made from.

check_f32: the validation file is the checkpoint, byte for byte: every tensor's data equals the
stored tensor (or, for the few tensors an architecture transforms, the documented transform of
it), the values carried as metadata equal the stored ones bit for bit, the hyper-parameters
equal config.json and the vocabulary equals the tokenizer file.

check_f16: the shipping file is exactly the 16-bit rounding of the checkpoint and nothing else:
matrices are IEEE half precision rounded to nearest, the tensors an architecture keeps in
float32 are still float32 and unchanged, no value overflowed, and the share of values that land
in the half-precision subnormal range stays small (a backend that flushes subnormals to zero
must not be able to change the model).

What a file of each architecture must contain is described once, in ARCHITECTURES below: the
tensor map from GGUF names to checkpoint names with their transforms, the tensors that stay
float32, and the metadata. Both checks return a list of problems, empty when the file is right,
and print what they measured.
"""
from __future__ import annotations

import json
import struct
import sys

from pathlib import Path
from typing import Callable

import numpy as np

F16_SMALLEST_NORMAL = 6.103515625e-05
MAX_SUBNORMAL_FRACTION = 0.005

_SAFETENSORS_DTYPES = {"F32": "<f4", "I64": "<i8"}


def load_gguf_module(llama_src: Path):
    """The gguf package of the patched llama.cpp copy: only it knows the architectures."""
    sys.dont_write_bytecode = True
    path = str(llama_src / "gguf-py")
    if path not in sys.path:
        sys.path.insert(0, path)
    import gguf

    return gguf


def read_safetensors(path: Path) -> dict[str, np.ndarray]:
    """Maps the tensors of a safetensors file without copying; float32 and int64 tensors only."""
    with open(path, "rb") as f:
        (header_len,) = struct.unpack("<Q", f.read(8))
        header = json.loads(f.read(header_len))
    data = np.memmap(path, dtype=np.uint8, mode="r", offset=8 + header_len)
    tensors = {}
    for name, info in header.items():
        if name == "__metadata__":
            continue
        assert info["dtype"] in _SAFETENSORS_DTYPES, f"{name} is {info['dtype']}, expected one of {sorted(_SAFETENSORS_DTYPES)}"
        begin, end = info["data_offsets"]
        tensors[name] = data[begin:end].view(_SAFETENSORS_DTYPES[info["dtype"]]).reshape(info["shape"])
    return tensors


class _File:
    def __init__(self, gguf, path: Path):
        self.reader = gguf.GGUFReader(str(path))
        self.gguf = gguf
        self.tensors = {t.name: t for t in self.reader.tensors}

    def value(self, key: str):
        field = self.reader.get_field(key)
        return None if field is None else field.contents()

    def array(self, key: str, value_type) -> np.ndarray | None:
        """The numeric array stored under key, or None when it is absent or of another element type."""
        field = self.reader.get_field(key)
        if field is None or len(field.types) != 2 or field.types[0] != self.gguf.GGUFValueType.ARRAY or field.types[1] != value_type:
            return None
        # One part per element; reading them through contents() would build a Python list of
        # hundreds of thousands of objects for the n-gram tables.
        return np.concatenate([field.parts[i] for i in field.data]) if field.data else np.empty(0)

    def keys(self) -> list[str]:
        return [k for k in self.reader.fields if not k.startswith("GGUF.")]


class _Expect:
    """Collects mismatches between metadata values and what the checkpoint says they must be."""

    def __init__(self, f: _File, problems: list[str]):
        self.f = f
        self.problems = problems

    def equal(self, key: str, wanted) -> None:
        got = self.f.value(key)
        if got != wanted:
            self.problems.append(f"{key} is {repr(got)[:60]}, expected {repr(wanted)[:60]}")

    def float32(self, key: str, wanted) -> None:
        got = self.f.value(key)
        if got is None or np.float32(got) != np.float32(wanted):
            self.problems.append(f"{key} is {got!r}, expected {wanted!r}")


# One tensor of a file: the checkpoint tensor it comes from and, where the converter changes
# the values, the transform that must reproduce them exactly.
_Source = tuple[str, Callable[[np.ndarray], np.ndarray] | None]


class _Peacebell:
    """A pre-norm decoder whose only non-tensor weights are the ReZero scalars, kept as metadata."""

    name = "peacebell"
    model_type = "gpt"  # of the checkpoint's config.json

    def tensors(self, config: dict) -> dict[str, _Source]:
        names: dict[str, _Source] = {"token_embd.weight": ("wte.weight", None), "output_norm.weight": ("ln_f.weight", None)}
        for i in range(config["n_layer"]):
            names.update({
                f"blk.{i}.attn_norm.weight": (f"h.{i}.ln_1.weight", None),
                f"blk.{i}.attn_qkv.weight": (f"h.{i}.attn.c_attn.weight", None),
                f"blk.{i}.attn_output.weight": (f"h.{i}.attn.c_proj.weight", None),
                f"blk.{i}.ffn_norm.weight": (f"h.{i}.ln_2.weight", None),
                f"blk.{i}.ffn_up.weight": (f"h.{i}.mlp.c_fc.weight", None),
                f"blk.{i}.ffn_down.weight": (f"h.{i}.mlp.c_proj.weight", None),
            })
        return names

    def stays_f32(self, name: str, stored: np.ndarray) -> bool:
        # Vectors (the norm weights) multiply every activation and are tiny: they stay float32.
        return stored.ndim < 2

    def check_metadata(self, f: _File, config: dict, source: dict[str, np.ndarray], problems: list[str]) -> None:
        expect = _Expect(f, problems)
        expect.equal("peacebell.context_length", config["block_size"])
        expect.equal("peacebell.embedding_length", config["n_embd"])
        expect.equal("peacebell.block_count", config["n_layer"])
        expect.equal("peacebell.feed_forward_length", 4 * config["n_embd"])
        expect.equal("peacebell.attention.head_count", config["n_head"])
        expect.float32("peacebell.attention.layer_norm_rms_epsilon", config["rms_norm_eps"])
        expect.float32("peacebell.rope.freq_base", config["rope_theta"])

        # The ReZero scalars are metadata and must be the stored float32 values exactly, in any file type.
        for key, pattern in (("peacebell.attention.output_scale", "h.{i}.attn_scale"), ("peacebell.feed_forward.output_scale", "h.{i}.mlp_scale")):
            stored = np.array([source[pattern.format(i=i)].reshape(-1)[0] for i in range(config["n_layer"])], dtype="<f4")
            got = f.array(key, f.gguf.GGUFValueType.FLOAT32)
            if got is None:
                problems.append(f"{key} is missing or not a float32 array")
            elif got.astype("<f4").tobytes() != stored.tobytes():
                problems.append(f"{key} differs from the stored scalars")


def _canon_kernel(stored: np.ndarray) -> np.ndarray:
    """The Canon kernel as the C++ convolution takes it.

    The checkpoint's columns weight positions t, t-1 and t-2 and leave the identity out; the
    convolution window is ordered t-2, t-1, t and has no separate identity term.
    """
    return np.stack([stored[:, 2], stored[:, 1], np.float32(1.0) + stored[:, 0]], axis=1).astype("<f4")


class _Boris2:
    """Boris-2: Canon kernels are transformed, the n-gram lookup tables are integer metadata."""

    name = "boris2"
    model_type = "boris2"

    # The four integer buffers of the n-gram lookup: metadata key suffix (also the end of the
    # checkpoint tensor's name) and element type.
    _lookup = (("bigram_keys", "UINT64"), ("bigram_rows", "INT32"), ("trigram_keys", "UINT64"), ("trigram_rows", "INT32"))

    def tensors(self, config: dict) -> dict[str, _Source]:
        names: dict[str, _Source] = {
            "token_embd.weight": ("model.embed_tokens.weight", None),
            "output_norm.weight": ("model.norm.weight", None),
            "per_layer_token_embd.weight": ("model.ngram.table", None),
            "per_layer_model_proj.weight": ("model.ngram.proj", None),
        }
        for i in range(config["num_hidden_layers"]):
            p = f"model.layers.{i}."
            names.update({
                f"blk.{i}.attn_norm.weight": (p + "input_layernorm.weight", None),
                f"blk.{i}.attn_qkv.weight": (p + "self_attn.qkv_proj.weight", None),
                f"blk.{i}.shortconv.conv.weight": (p + "self_attn.canon", _canon_kernel),
                f"blk.{i}.attn_output.weight": (p + "self_attn.o_proj.weight", None),
                f"blk.{i}.ffn_norm.weight": (p + "post_attention_layernorm.weight", None),
                f"blk.{i}.ffn_up.weight": (p + "mlp.gate_up_proj.weight", None),
                f"blk.{i}.ffn_down.weight": (p + "mlp.down_proj.weight", None),
            })
        return names

    def stays_f32(self, name: str, stored: np.ndarray) -> bool:
        # Norm weights; the Canon kernels, which ggml's convolution takes in float32 only; and the
        # n-gram projection, which feeds the residual stream of every token.
        return stored.ndim < 2 or name.endswith(".shortconv.conv.weight") or name == "per_layer_model_proj.weight"

    def check_metadata(self, f: _File, config: dict, source: dict[str, np.ndarray], problems: list[str]) -> None:
        expect = _Expect(f, problems)
        expect.equal("boris2.context_length", config["max_position_embeddings"])
        expect.equal("boris2.embedding_length", config["hidden_size"])
        expect.equal("boris2.block_count", config["num_hidden_layers"])
        expect.equal("boris2.feed_forward_length", config["intermediate_size"])
        expect.equal("boris2.attention.head_count", config["num_attention_heads"])
        expect.equal("boris2.attention.head_count_kv", config["num_key_value_heads"])
        expect.float32("boris2.attention.layer_norm_rms_epsilon", config["rms_norm_eps"])
        expect.float32("boris2.rope.freq_base", config["rope_theta"])
        expect.equal("boris2.shortconv.l_cache", config["canon_kernel"])
        expect.equal("boris2.ngram.row_count", config["ngram_rows"])
        expect.equal("boris2.ngram.embedding_length", config["ngram_rank"])
        expect.equal("boris2.ngram.insert_layer", config["ngram_insert_after"])
        expect.equal("boris2.ngram.oov_row", config["ngram_oov_row"])

        # The lookup tables are the checkpoint's int64 buffers value for value, the keys as
        # unsigned 64-bit and the rows as 32-bit integers, and the keys can be binary-searched.
        for suffix, type_name in self._lookup:
            stored = source[f"model.ngram.{suffix}"]
            got = f.array(f"boris2.ngram.{suffix}", f.gguf.GGUFValueType[type_name])
            if got is None:
                problems.append(f"boris2.ngram.{suffix} is missing or not a {type_name} array")
            elif len(got) != len(stored) or not np.array_equal(got.astype(np.int64), stored):
                problems.append(f"boris2.ngram.{suffix} differs from model.ngram.{suffix}")
            elif suffix.endswith("_keys") and not np.all(got[1:] > got[:-1]):
                problems.append(f"boris2.ngram.{suffix} is not strictly increasing")


ARCHITECTURES = {arch.name: arch for arch in (_Peacebell(), _Boris2())}


def _common(f: _File, model_dir: Path, problems: list[str]):
    """Checks shared by both file types; returns the architecture, the checkpoint tensors and the tensor map."""
    with open(model_dir / "config.json", encoding="utf-8") as fp:
        config = json.load(fp)
    source = read_safetensors(model_dir / "model.safetensors")

    arch = ARCHITECTURES.get(f.value("general.architecture"))
    if arch is None:
        raise ValueError(f"general.architecture is {f.value('general.architecture')!r}: no description of such a file in check_gguf.py")
    if config.get("model_type") != arch.model_type:
        raise ValueError(f"a {arch.name} file cannot come from the checkpoint in {model_dir} (model_type {config.get('model_type')!r})")
    arch.check_metadata(f, config, source, problems)

    names = arch.tensors(config)
    if set(f.tensors) != set(names):
        problems.append(f"tensor set differs: missing {sorted(set(names) - set(f.tensors))[:3]}, unexpected {sorted(set(f.tensors) - set(names))[:3]}")

    _check_vocab(f, arch.name, model_dir, config, problems)
    return arch, source, names


def _check_vocab(f: _File, arch: str, model_dir: Path, config: dict, problems: list[str]) -> None:
    expect = _Expect(f, problems).equal

    tokens = f.value("tokenizer.ggml.tokens")
    types = f.value("tokenizer.ggml.token_type")
    if (model_dir / "sentencepiece.model").is_file():
        from sentencepiece import sentencepiece_model_pb2 as sp_pb2

        proto = sp_pb2.ModelProto()  # type: ignore[attr-defined]
        proto.ParseFromString((model_dir / "sentencepiece.model").read_bytes())
        start, end, unk = config["im_start_id"], config["im_end_id"], config["unk_pad_id"]
        expect("tokenizer.ggml.model", "llama")
        if tokens != [p.piece for p in proto.pieces]:
            problems.append("tokenizer.ggml.tokens differ from the SentencePiece pieces")
        if not np.array_equal(np.array(f.value("tokenizer.ggml.scores"), dtype=np.float32), np.array([p.score for p in proto.pieces], dtype=np.float32)):
            problems.append("tokenizer.ggml.scores differ from the SentencePiece scores")
        # Piece types carry over one to one, except the two markers, which become control tokens.
        wanted_types = [3 if i in (start, end) else int(p.type) for i, p in enumerate(proto.pieces)]
        if types != wanted_types:
            problems.append("tokenizer.ggml.token_type differs from the SentencePiece piece types")
        user_defined = [(i, p.piece) for i, p in enumerate(proto.pieces) if p.type == 4]
        expect(f"{arch}.tokenizer.kind", "sentencepiece-bpe")
        expect(f"{arch}.tokenizer.spm.user_defined_symbols", [s for _, s in user_defined])
        expect(f"{arch}.tokenizer.spm.user_defined_symbol_ids", [i for i, _ in user_defined])
        expect(f"{arch}.tokenizer.spm.user_defined_symbol_count", len(user_defined))
        expect(f"{arch}.tokenizer.spm.normalizer.name", proto.normalizer_spec.name)
        expect(f"{arch}.tokenizer.spm.normalizer.add_dummy_prefix", proto.normalizer_spec.add_dummy_prefix)
        expect(f"{arch}.tokenizer.spm.normalizer.remove_extra_whitespaces", proto.normalizer_spec.remove_extra_whitespaces)
        expect(f"{arch}.tokenizer.spm.normalizer.escape_whitespaces", proto.normalizer_spec.escape_whitespaces)
        expect(f"{arch}.chat.format", "chatml-ids")
        expect(f"{arch}.chat.start_token_id", start)
        expect(f"{arch}.chat.end_token_id", end)
        expect("tokenizer.ggml.add_space_prefix", False)
        expect("tokenizer.ggml.add_bos_token", False)
        expect("tokenizer.ggml.bos_token_id", config["bos_token_id"])
        expect("tokenizer.ggml.eos_token_id", config["eos_token_id"])
        expect("tokenizer.ggml.eot_token_id", end)
        expect("tokenizer.ggml.unknown_token_id", unk)
        expect("tokenizer.ggml.suppress_tokens", [unk])
    else:
        with open(model_dir / "tokenizer.json", encoding="utf-8") as fp:
            tokenizer = json.load(fp)
        by_id = {i: t for t, i in tokenizer["model"]["vocab"].items()}
        special = {t["id"] for t in tokenizer["added_tokens"] if t.get("special")}
        expect("tokenizer.ggml.model", "gpt2")
        expect("tokenizer.ggml.pre", "gpt-2")
        if tokens != [by_id[i] for i in range(len(by_id))]:
            problems.append("tokenizer.ggml.tokens differ from tokenizer.json")
        if types != [3 if i in special else 1 for i in range(len(by_id))]:
            problems.append("tokenizer.ggml.token_type differs from tokenizer.json")
        merges = [" ".join(m) if isinstance(m, list) else m for m in tokenizer["model"]["merges"]]
        expect("tokenizer.ggml.merges", merges)
        expect(f"{arch}.tokenizer.kind", "byte-bpe")
        expect(f"{arch}.chat.format", "chatml-text")
        expect("tokenizer.ggml.eos_token_id", config["eos_token_id"])
        expect("tokenizer.ggml.padding_token_id", config["pad_token_id"])
        expect("tokenizer.ggml.add_bos_token", False)
        expect("tokenizer.ggml.add_eos_token", False)
        expect("tokenizer.ggml.clean_spaces", False)
        if "train_pad_id" in config:
            # The training pad must be in the list of tokens that are never sampled.
            suppress = f.value("tokenizer.ggml.suppress_tokens") or []
            if config["train_pad_id"] not in suppress:
                problems.append(f"tokenizer.ggml.suppress_tokens {suppress} lacks the training pad {config['train_pad_id']}")
        with open(model_dir / "tokenizer_config.json", encoding="utf-8") as fp:
            expect("tokenizer.chat_template", json.load(fp).get("chat_template"))


def _expected(source: dict[str, np.ndarray], spec: _Source) -> np.ndarray:
    source_name, transform = spec
    stored = source[source_name]
    return stored if transform is None else transform(stored)


def check_f32(llama_src: Path, gguf_path: Path, model_dir: Path) -> list[str]:
    gguf = load_gguf_module(llama_src)
    f = _File(gguf, gguf_path)
    problems: list[str] = []
    _, source, names = _common(f, model_dir, problems)
    n_bytes = n_transformed = 0
    for name, spec in names.items():
        tensor = f.tensors.get(name)
        if tensor is None:
            continue
        if tensor.tensor_type != gguf.GGMLQuantizationType.F32:
            problems.append(f"{name} is {tensor.tensor_type.name}, expected F32")
        elif tensor.data.tobytes() != _expected(source, spec).tobytes():
            problems.append(f"{name} differs from {spec[0]}" + ("" if spec[1] is None else " after its transform"))
        n_bytes += tensor.data.nbytes
        n_transformed += spec[1] is not None
    if f.value("general.file_type") != int(gguf.LlamaFileType.ALL_F32):
        problems.append("general.file_type is not ALL_F32")
    transformed = f" ({n_transformed} of them after their documented transform)" if n_transformed else ""
    print(f"note  {len(names)} tensors, {n_bytes} bytes of tensor data compared with the checkpoint byte for byte{transformed}; "
          f"{len(f.keys())} metadata keys")
    return problems


def check_f16(llama_src: Path, gguf_path: Path, model_dir: Path, f32_path: Path) -> list[str]:
    gguf = load_gguf_module(llama_src)
    f = _File(gguf, gguf_path)
    problems: list[str] = []
    arch, source, names = _common(f, model_dir, problems)
    worst_subnormal = (0.0, "")
    n_f16 = n_f32 = n_zeroed = 0
    for name, spec in names.items():
        tensor = f.tensors.get(name)
        if tensor is None:
            continue
        stored = _expected(source, spec)
        if arch.stays_f32(name, stored):
            n_f32 += 1
            if tensor.tensor_type != gguf.GGMLQuantizationType.F32 or tensor.data.tobytes() != stored.tobytes():
                problems.append(f"{name} must be F32 and equal to {spec[0]}")
            continue
        n_f16 += 1
        if tensor.tensor_type != gguf.GGMLQuantizationType.F16:
            problems.append(f"{name} is {tensor.tensor_type.name}, expected F16")
            continue
        half = np.asarray(tensor.data).reshape(-1)
        if half.tobytes() != stored.astype("<f2").reshape(-1).tobytes():
            problems.append(f"{name} is not the round-to-nearest half-precision cast of {spec[0]}")
        if not np.isfinite(half).all():
            problems.append(f"{name} has non-finite values")
        magnitude = np.abs(half.astype(np.float32))
        subnormal = float(np.count_nonzero((magnitude > 0) & (magnitude < F16_SMALLEST_NORMAL))) / half.size
        n_zeroed += int(np.count_nonzero((magnitude == 0) & (stored.reshape(-1) != 0)))
        worst_subnormal = max(worst_subnormal, (subnormal, name))
        if subnormal >= MAX_SUBNORMAL_FRACTION:
            problems.append(f"{name}: {100 * subnormal:.3f}% of the values are half-precision subnormals (limit {100 * MAX_SUBNORMAL_FRACTION}%)")
    if f.value("general.file_type") != int(gguf.LlamaFileType.MOSTLY_F16):
        problems.append("general.file_type is not MOSTLY_F16")

    # Apart from the file type, the metadata must not depend on the precision.
    f32 = _File(gguf, f32_path)
    differing = [k for k in sorted(set(f.keys()) | set(f32.keys())) if k != "general.file_type" and f.value(k) != f32.value(k)]
    if differing:
        problems.append(f"metadata differs from the F32 file: {differing[:4]}")

    print(f"note  {n_f16} F16 matrices equal to the rounded checkpoint, {n_f32} F32 tensors equal to the checkpoint; "
          f"largest subnormal share {100 * worst_subnormal[0]:.3f}% ({worst_subnormal[1]}, limit {100 * MAX_SUBNORMAL_FRACTION}%); "
          f"{n_zeroed} non-zero values rounded to zero; no infinities")
    return problems
