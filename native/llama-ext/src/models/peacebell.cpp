#include "models.h"

#include <algorithm>
#include <stdexcept>

// Reads one optional per-layer F32 array straight from the GGUF metadata. The raw gguf calls are
// used instead of the loader's typed getters because only one of the two keys has an upstream
// enum entry, and adding the other would widen the hook patch for no gain.
static void peacebell_read_layer_scales(
        const llama_model_loader & ml,
        const std::string & key,
        uint32_t n_layer,
        std::array<float, LLAMA_MAX_LAYERS> & out) {
    out.fill(1.0f);

    const gguf_context * ctx = ml.metadata;
    const int64_t kid = gguf_find_key(ctx, key.c_str());
    if (kid < 0) {
        return;
    }
    // A present but malformed array must not be read as "ungated": the model would run and be wrong.
    if (gguf_get_kv_type(ctx, kid) != GGUF_TYPE_ARRAY ||
        gguf_get_arr_type(ctx, kid) != GGUF_TYPE_FLOAT32 ||
        gguf_get_arr_n(ctx, kid) != n_layer) {
        throw std::runtime_error(format("%s must be an array of %u float32 values", key.c_str(), n_layer));
    }
    const float * data = (const float *) gguf_get_arr_data(ctx, kid);
    std::copy(data, data + n_layer, out.begin());
}

void llama_model_peacebell::load_arch_hparams(llama_model_loader & ml) {
    ml.get_key(LLM_KV_ATTENTION_LAYERNORM_RMS_EPS, hparams.f_norm_rms_eps);

    const std::string arch_name = ml.get_arch_name();
    peacebell_read_layer_scales(ml, arch_name + ".attention.output_scale",    hparams.n_layer(), attn_out_scale);
    peacebell_read_layer_scales(ml, arch_name + ".feed_forward.output_scale", hparams.n_layer(), ffn_out_scale);

    type = LLM_TYPE_UNKNOWN;
}

void llama_model_peacebell::load_arch_tensors(llama_model_loader &) {
    LLAMA_LOAD_LOCALS;

    tok_embd = create_tensor(tn(LLM_TENSOR_TOKEN_EMBD, "weight"), {n_embd, n_vocab}, 0);

    // The head is tied to the embedding: the file carries no output.weight, the embedding is
    // loaded a second time for the output projection.
    output_norm = create_tensor(tn(LLM_TENSOR_OUTPUT_NORM, "weight"), {n_embd}, 0);
    output      = create_tensor(tn(LLM_TENSOR_TOKEN_EMBD,  "weight"), {n_embd, n_vocab}, TENSOR_DUPLICATED);

    for (int i = 0; i < n_layer; ++i) {
        auto & layer = layers[i];

        layer.attn_norm = create_tensor(tn(LLM_TENSOR_ATTN_NORM, "weight", i), {n_embd}, 0);

        // The checkpoint stores one fused projection whose rows are already ordered Q, K, V.
        create_tensor_qkv(layer, i, n_embd, n_embd, n_embd_gqa, n_embd_gqa, 0);
        layer.wo = create_tensor(tn(LLM_TENSOR_ATTN_OUT, "weight", i), {n_embd, n_embd}, 0);

        layer.ffn_norm = create_tensor(tn(LLM_TENSOR_FFN_NORM, "weight", i), {n_embd}, 0);
        layer.ffn_up   = create_tensor(tn(LLM_TENSOR_FFN_UP,   "weight", i), {n_embd,   n_ff}, 0);
        layer.ffn_down = create_tensor(tn(LLM_TENSOR_FFN_DOWN, "weight", i), {  n_ff, n_embd}, 0);
    }
}

std::unique_ptr<llm_graph_context> llama_model_peacebell::build_arch_graph(const llm_graph_params & params) const {
    return std::make_unique<graph>(params);
}

llama_model_peacebell::graph::graph(const llm_graph_params & params) : llm_graph_context(params) {
    const auto & pb = static_cast<const llama_model_peacebell &>(model);

    const int64_t n_embd_head = hparams.n_embd_head_v();

    GGML_ASSERT(n_embd_head == hparams.n_embd_head_k());

    ggml_tensor * cur;
    ggml_tensor * inpL;

    inpL = build_inp_embd(model.tok_embd);

    ggml_tensor * inp_pos = build_inp_pos();

    auto * inp_attn = build_attn_inp_kv();

    ggml_tensor * inp_out_ids = build_inp_out_ids();

    for (int il = 0; il < n_layer; ++il) {
        const auto & layer = model.layers[il];

        // x = x + attn_scale * Attn(RMSNorm(x))
        cur = build_norm(inpL, layer.attn_norm, NULL, LLM_NORM_RMS, il);
        cb(cur, "attn_norm", il);

        {
            auto [Qcur, Kcur, Vcur] = build_qkv(layer, cur, n_embd_head, n_head, n_head_kv, il);

            Qcur = build_rope(Qcur, inp_pos, il);
            Kcur = build_rope(Kcur, inp_pos, il);

            cb(Qcur, "Qcur", il);
            cb(Kcur, "Kcur", il);
            cb(Vcur, "Vcur", il);

            cur = build_attn(inp_attn,
                    layer.wo, NULL, layer.wo_s,
                    Qcur, Kcur, Vcur, nullptr, nullptr, nullptr, 1.0f/sqrtf(float(n_embd_head)), il);
        }

        if (il == n_layer - 1 && inp_out_ids) {
            cur  = ggml_get_rows(ctx0, cur,  inp_out_ids);
            inpL = ggml_get_rows(ctx0, inpL, inp_out_ids);
        }

        cur = ggml_scale(ctx0, cur, pb.attn_out_scale[il]);
        cb(cur, "attn_scaled", il);

        ggml_tensor * ffn_inp = ggml_add(ctx0, cur, inpL);
        cb(ffn_inp, "ffn_inp", il);

        // x = x + mlp_scale * MLP(RMSNorm(x)), MLP = down(gelu(up(x))) with the exact erf GELU:
        // the tanh approximation that LLM_FFN_GELU selects costs 0.1 in the logits for this family.
        cur = build_norm(ffn_inp, layer.ffn_norm, NULL, LLM_NORM_RMS, il);
        cb(cur, "ffn_norm", il);

        cur = build_ffn(cur,
                layer.ffn_up,   NULL, NULL,
                NULL,           NULL, NULL,
                layer.ffn_down, NULL, NULL,
                NULL,
                LLM_FFN_GELU_ERF, LLM_FFN_SEQ, il);
        cb(cur, "ffn_out", il);

        cur = ggml_scale(ctx0, cur, pb.ffn_out_scale[il]);
        cb(cur, "ffn_scaled", il);

        cur = ggml_add(ctx0, cur, ffn_inp);

        cur = build_cvec(cur, il);
        cb(cur, "l_out", il);

        inpL = cur;
    }

    cur = build_norm(inpL, model.output_norm, NULL, LLM_NORM_RMS, -1);

    cb(cur, "result_norm", -1);
    res->t_embd = cur;

    cur = build_lora_mm(model.output, cur, model.output_s);

    cb(cur, "result_output", -1);
    res->t_logits = cur;
}
