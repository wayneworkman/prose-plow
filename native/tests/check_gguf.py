"""Checks of a converted peacebell GGUF file against the checkpoint it was made from.

check_f32: the validation file is the checkpoint, byte for byte: every tensor's data equals the
stored tensor, the ReZero scalars equal the stored scalars bit for bit, the hyper-parameters
equal config.json and the vocabulary equals the tokenizer file.

check_f16: the shipping file is exactly the 16-bit rounding of the checkpoint and nothing else:
matrices are IEEE half precision rounded to nearest, vectors and scalars stay float32, no
value overflowed, and the share of values that land in the half-precision subnormal range stays
small (a backend that flushes subnormals to zero must not be able to change the model).

Both return a list of problems, empty when the file is right, and print what they measured.
"""
from __future__ import annotations

import json
import struct
import sys

from pathlib import Path

import numpy as np

F16_SMALLEST_NORMAL = 6.103515625e-05
MAX_SUBNORMAL_FRACTION = 0.005

# GGUF tensor name pattern -> checkpoint tensor name pattern.
_BLOCK_TENSORS = {
    "blk.{i}.attn_norm.weight": "h.{i}.ln_1.weight",
    "blk.{i}.attn_qkv.weight": "h.{i}.attn.c_attn.weight",
    "blk.{i}.attn_output.weight": "h.{i}.attn.c_proj.weight",
    "blk.{i}.ffn_norm.weight": "h.{i}.ln_2.weight",
    "blk.{i}.ffn_up.weight": "h.{i}.mlp.c_fc.weight",
    "blk.{i}.ffn_down.weight": "h.{i}.mlp.c_proj.weight",
}
_TOP_TENSORS = {"token_embd.weight": "wte.weight", "output_norm.weight": "ln_f.weight"}


def load_gguf_module(llama_src: Path):
    """The gguf package of the patched llama.cpp copy: only it knows the architecture."""
    sys.dont_write_bytecode = True
    path = str(llama_src / "gguf-py")
    if path not in sys.path:
        sys.path.insert(0, path)
    import gguf

    return gguf


def read_safetensors(path: Path) -> dict[str, np.ndarray]:
    """Maps the tensors of a safetensors file without copying; float32 tensors only."""
    with open(path, "rb") as f:
        (header_len,) = struct.unpack("<Q", f.read(8))
        header = json.loads(f.read(header_len))
    data = np.memmap(path, dtype=np.uint8, mode="r", offset=8 + header_len)
    tensors = {}
    for name, info in header.items():
        if name == "__metadata__":
            continue
        assert info["dtype"] == "F32", f"{name} is {info['dtype']}, expected F32"
        begin, end = info["data_offsets"]
        tensors[name] = data[begin:end].view("<f4").reshape(info["shape"])
    return tensors


class _File:
    def __init__(self, gguf, path: Path):
        self.reader = gguf.GGUFReader(str(path))
        self.gguf = gguf
        self.tensors = {t.name: t for t in self.reader.tensors}

    def value(self, key: str):
        field = self.reader.get_field(key)
        return None if field is None else field.contents()

    def keys(self) -> list[str]:
        return [k for k in self.reader.fields if not k.startswith("GGUF.")]


def _common(f: _File, model_dir: Path, problems: list[str]) -> tuple[dict, dict[str, np.ndarray], dict[str, str]]:
    """Checks shared by both file types; returns config, checkpoint tensors and the name map."""
    with open(model_dir / "config.json", encoding="utf-8") as fp:
        config = json.load(fp)
    source = read_safetensors(model_dir / "model.safetensors")
    n_layer = config["n_layer"]

    def expect(key: str, wanted, exact: bool = True) -> None:
        got = f.value(key)
        same = got == wanted if exact else (got is not None and np.float32(got) == np.float32(wanted))
        if not same:
            problems.append(f"{key} is {got!r}, expected {wanted!r}")

    expect("general.architecture", "peacebell")
    expect("peacebell.context_length", config["block_size"])
    expect("peacebell.embedding_length", config["n_embd"])
    expect("peacebell.block_count", n_layer)
    expect("peacebell.feed_forward_length", 4 * config["n_embd"])
    expect("peacebell.attention.head_count", config["n_head"])
    expect("peacebell.attention.layer_norm_rms_epsilon", config["rms_norm_eps"], exact=False)
    expect("peacebell.rope.freq_base", config["rope_theta"], exact=False)

    # The ReZero scalars are metadata and must be the stored float32 values exactly, in any file type.
    for key, pattern in (("peacebell.attention.output_scale", "h.{i}.attn_scale"), ("peacebell.feed_forward.output_scale", "h.{i}.mlp_scale")):
        field = f.reader.get_field(key)
        stored = np.array([source[pattern.format(i=i)].reshape(-1)[0] for i in range(n_layer)], dtype="<f4")
        if field is None or field.types[-1] != f.gguf.GGUFValueType.FLOAT32:
            problems.append(f"{key} is missing or not a float32 array")
        elif np.array(field.contents(), dtype="<f4").tobytes() != stored.tobytes():
            problems.append(f"{key} differs from the stored scalars")

    names = dict(_TOP_TENSORS)
    for i in range(n_layer):
        names.update({g.format(i=i): s.format(i=i) for g, s in _BLOCK_TENSORS.items()})
    if set(f.tensors) != set(names):
        problems.append(f"tensor set differs: missing {sorted(set(names) - set(f.tensors))[:3]}, unexpected {sorted(set(f.tensors) - set(names))[:3]}")

    _check_vocab(f, model_dir, config, problems)
    return config, source, names


def _check_vocab(f: _File, model_dir: Path, config: dict, problems: list[str]) -> None:
    def expect(key: str, wanted) -> None:
        got = f.value(key)
        if got != wanted:
            shown = (repr(got)[:60], repr(wanted)[:60])
            problems.append(f"{key} is {shown[0]}, expected {shown[1]}")

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
        expect("peacebell.tokenizer.kind", "sentencepiece-bpe")
        expect("peacebell.tokenizer.spm.user_defined_symbols", [s for _, s in user_defined])
        expect("peacebell.tokenizer.spm.user_defined_symbol_ids", [i for i, _ in user_defined])
        expect("peacebell.tokenizer.spm.user_defined_symbol_count", len(user_defined))
        expect("peacebell.tokenizer.spm.normalizer.name", proto.normalizer_spec.name)
        expect("peacebell.tokenizer.spm.normalizer.add_dummy_prefix", proto.normalizer_spec.add_dummy_prefix)
        expect("peacebell.tokenizer.spm.normalizer.remove_extra_whitespaces", proto.normalizer_spec.remove_extra_whitespaces)
        expect("peacebell.tokenizer.spm.normalizer.escape_whitespaces", proto.normalizer_spec.escape_whitespaces)
        expect("peacebell.chat.format", "chatml-ids")
        expect("peacebell.chat.start_token_id", start)
        expect("peacebell.chat.end_token_id", end)
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
        expect("peacebell.tokenizer.kind", "byte-bpe")
        expect("peacebell.chat.format", "chatml-text")
        expect("tokenizer.ggml.eos_token_id", config["eos_token_id"])
        expect("tokenizer.ggml.padding_token_id", config["pad_token_id"])
        expect("tokenizer.ggml.add_bos_token", False)
        expect("tokenizer.ggml.clean_spaces", False)
        # The training pad must be in the list of tokens that are never sampled.
        suppress = f.value("tokenizer.ggml.suppress_tokens") or []
        if config["train_pad_id"] not in suppress:
            problems.append(f"tokenizer.ggml.suppress_tokens {suppress} lacks the training pad {config['train_pad_id']}")
        with open(model_dir / "tokenizer_config.json", encoding="utf-8") as fp:
            expect("tokenizer.chat_template", json.load(fp).get("chat_template"))


def check_f32(llama_src: Path, gguf_path: Path, model_dir: Path) -> list[str]:
    gguf = load_gguf_module(llama_src)
    f = _File(gguf, gguf_path)
    problems: list[str] = []
    _, source, names = _common(f, model_dir, problems)
    n_bytes = 0
    for name, source_name in names.items():
        tensor = f.tensors.get(name)
        if tensor is None:
            continue
        if tensor.tensor_type != gguf.GGMLQuantizationType.F32:
            problems.append(f"{name} is {tensor.tensor_type.name}, expected F32")
        elif tensor.data.tobytes() != source[source_name].tobytes():
            problems.append(f"{name} differs from {source_name}")
        n_bytes += tensor.data.nbytes
    if f.value("general.file_type") != int(gguf.LlamaFileType.ALL_F32):
        problems.append("general.file_type is not ALL_F32")
    print(f"note  {len(names)} tensors, {n_bytes} bytes of tensor data compared with the checkpoint byte for byte; "
          f"{len(f.keys())} metadata keys")
    return problems


def check_f16(llama_src: Path, gguf_path: Path, model_dir: Path, f32_path: Path) -> list[str]:
    gguf = load_gguf_module(llama_src)
    f = _File(gguf, gguf_path)
    problems: list[str] = []
    _, source, names = _common(f, model_dir, problems)
    worst_subnormal = (0.0, "")
    n_f16 = n_f32 = n_zeroed = 0
    for name, source_name in names.items():
        tensor = f.tensors.get(name)
        if tensor is None:
            continue
        stored = source[source_name]
        if stored.ndim < 2:
            # Vectors (the norm weights) multiply every activation and are tiny: they stay float32.
            n_f32 += 1
            if tensor.tensor_type != gguf.GGMLQuantizationType.F32 or tensor.data.tobytes() != stored.tobytes():
                problems.append(f"{name} must be F32 and equal to {source_name}")
            continue
        n_f16 += 1
        if tensor.tensor_type != gguf.GGMLQuantizationType.F16:
            problems.append(f"{name} is {tensor.tensor_type.name}, expected F16")
            continue
        half = np.asarray(tensor.data).reshape(-1)
        if half.tobytes() != stored.astype("<f2").reshape(-1).tobytes():
            problems.append(f"{name} is not the round-to-nearest half-precision cast of {source_name}")
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

    print(f"note  {n_f16} F16 matrices equal to the rounded checkpoint, {n_f32} F32 vectors equal to the checkpoint; "
          f"largest subnormal share {100 * worst_subnormal[0]:.3f}% ({worst_subnormal[1]}, limit {100 * MAX_SUBNORMAL_FRACTION}%); "
          f"{n_zeroed} non-zero values rounded to zero; no infinities")
    return problems
