#include "models.h"

#include "llama-impl.h"

#include <stdexcept>

// Reads one of the two per-layer flag arrays. The loader's own getters would also accept a
// single number there (which they spread over every layer) or an array of another length; for
// this architecture either describes another model, so the load fails instead. Bad metadata
// throws: an assertion would take the whole process down.
static void shard2_read_layer_flags(llama_model_loader & ml, enum llm_kv kid, std::array<uint32_t, LLAMA_MAX_LAYERS> & out, uint32_t n_layer) {
    const gguf_context * ctx = ml.metadata;
    const std::string    key = ml.llm_kv(kid);
    const int64_t        id  = gguf_find_key(ctx, key.c_str());
    if (id < 0 || gguf_get_kv_type(ctx, id) != GGUF_TYPE_ARRAY || gguf_get_arr_type(ctx, id) != GGUF_TYPE_BOOL || gguf_get_arr_n(ctx, id) != n_layer) {
        throw std::runtime_error(format("%s must be an array of %u booleans, one per layer", key.c_str(), n_layer));
    }
    ml.get_arr(kid, out);
}

void llama_model_shard2::load_arch_hparams(llama_model_loader & ml) {
    ml.get_key(LLM_KV_ATTENTION_LAYERNORM_RMS_EPS, hparams.f_norm_rms_eps);
    ml.get_key(LLM_KV_ATTENTION_SLIDING_WINDOW,    hparams.n_swa);

    // Which layers see a window of n_swa keys (the others see everything) and which rotate Q
    // and K by position. In the one published checkpoint the two arrays are equal.
    shard2_read_layer_flags(ml, LLM_KV_ATTENTION_SLIDING_WINDOW_PATTERN, hparams.is_swa_impl,  hparams.n_layer());
    shard2_read_layer_flags(ml, LLM_KV_ATTENTION_ROPE_PATTERN,           hparams.rope_pattern, hparams.n_layer());

    // The graph is built for upstream's interleaved cache, which exists only for a model that
    // has a window (and whose constructor asserts that a layer uses it).
    if (hparams.n_swa == 0 || !hparams.is_swa_any()) {
        throw std::runtime_error("shard2: a model without windowed layers is not supported");
    }
    // A key is hidden from a query when query - key >= n_swa: the token itself and the n_swa - 1 before it.
    hparams.swa_type = LLAMA_SWA_TYPE_STANDARD;

    // The fused projection is cut into three equal parts.
    if (hparams.n_head() != hparams.n_head_kv()) {
        throw std::runtime_error("shard2: grouped-query attention is not supported");
    }

    // Upstream takes the rotary base of a windowed layer from a hyper-parameter of its own, which
    // is 10,000 unless an architecture sets it. This model has one base for every layer that rotates.
    hparams.rope_freq_base_train_swa = hparams.rope_freq_base_train;

    type = LLM_TYPE_UNKNOWN;
}

void llama_model_shard2::load_arch_tensors(llama_model_loader &) {
    LLAMA_LOAD_LOCALS;

    tok_embd = create_tensor(tn(LLM_TENSOR_TOKEN_EMBD, "weight"), {n_embd, n_vocab}, 0);

    // The head is tied to the embedding: the file carries no output.weight, the embedding is
    // loaded a second time for the output projection.
    output_norm = create_tensor(tn(LLM_TENSOR_OUTPUT_NORM, "weight"), {n_embd}, 0);
    output      = create_tensor(tn(LLM_TENSOR_TOKEN_EMBD,  "weight"), {n_embd, n_vocab}, TENSOR_DUPLICATED);

    for (int i = 0; i < n_layer; ++i) {
        auto & layer = layers[i];

        layer.attn_norm = create_tensor(tn(LLM_TENSOR_ATTN_NORM, "weight", i), {n_embd}, 0);

        // One fused projection whose rows are ordered Q, K, V.
        layer.wqkv = create_tensor(tn(LLM_TENSOR_ATTN_QKV, "weight", i), {n_embd, 3 * n_embd}, 0);
        // The weights of the norm over one head of Q and of K, shared by the heads of a layer.
        layer.attn_q_norm = create_tensor(tn(LLM_TENSOR_ATTN_Q_NORM, "weight", i), {n_embd_head_k}, 0);
        layer.attn_k_norm = create_tensor(tn(LLM_TENSOR_ATTN_K_NORM, "weight", i), {n_embd_head_k}, 0);

        layer.wo             = create_tensor(tn(LLM_TENSOR_ATTN_OUT,       "weight", i), {n_embd, n_embd}, 0);
        layer.attn_post_norm = create_tensor(tn(LLM_TENSOR_ATTN_POST_NORM, "weight", i), {n_embd}, 0);

        layer.ffn_norm = create_tensor(tn(LLM_TENSOR_FFN_NORM, "weight", i), {n_embd}, 0);
        // Gate and up projection fused, the gate half first.
        layer.ffn_up        = create_tensor(tn(LLM_TENSOR_FFN_UP,        "weight", i), {n_embd, 2 * n_ff}, 0);
        layer.ffn_down      = create_tensor(tn(LLM_TENSOR_FFN_DOWN,      "weight", i), {  n_ff,   n_embd}, 0);
        layer.ffn_post_norm = create_tensor(tn(LLM_TENSOR_FFN_POST_NORM, "weight", i), {n_embd}, 0);
    }
}

std::unique_ptr<llm_graph_context> llama_model_shard2::build_arch_graph(const llm_graph_params & params) const {
    return std::make_unique<graph>(params);
}

llama_model_shard2::graph::graph(const llm_graph_params & params) : llm_graph_context(params) {
    const int64_t n_embd_head = hparams.n_embd_head_v();

    GGML_ASSERT(n_embd_head == hparams.n_embd_head_k());

    ggml_tensor * cur;
    ggml_tensor * inpL;

    inpL = build_inp_embd(model.tok_embd);

    ggml_tensor * inp_pos = build_inp_pos();

    // One cell store for the global layers and one for the local layers; build_attn() picks the
    // store, and with it the mask, from hparams.is_swa(il).
    auto * inp_attn = build_attn_inp_kv_iswa();

    ggml_tensor * inp_out_ids = build_inp_out_ids();

    for (int il = 0; il < n_layer; ++il) {
        const auto & layer = model.layers[il];

        // x = x + PostNorm(Attn(PreNorm(x)))
        cur = build_norm(inpL, layer.attn_norm, NULL, LLM_NORM_RMS, il);
        cb(cur, "attn_norm", il);

        {
            auto [Qcur, Kcur, Vcur] = build_qkv(layer, cur, n_embd_head, n_head, n_head_kv, il);

            // The norm runs over the first dimension of the [head size, heads, tokens] views,
            // that is over each head on its own, and comes before the rotation.
            Qcur = build_norm(Qcur, layer.attn_q_norm, NULL, LLM_NORM_RMS, il);
            cb(Qcur, "Qcur_normed", il);
            Kcur = build_norm(Kcur, layer.attn_k_norm, NULL, LLM_NORM_RMS, il);
            cb(Kcur, "Kcur_normed", il);

            // Does nothing on a layer the file's rope pattern leaves out: a global layer has no
            // position encoding of any kind.
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

        cur = build_norm(cur, layer.attn_post_norm, NULL, LLM_NORM_RMS, il);
        cb(cur, "attn_post_norm", il);

        ggml_tensor * ffn_inp = ggml_add(ctx0, cur, inpL);
        cb(ffn_inp, "ffn_inp", il);

        // x = x + PostNorm(down(silu(gate) * up)), gate and up being the two halves of one projection
        cur = build_norm(ffn_inp, layer.ffn_norm, NULL, LLM_NORM_RMS, il);
        cb(cur, "ffn_norm", il);

        cur = build_ffn(cur,
                layer.ffn_up,   NULL, NULL,
                NULL,           NULL, NULL,
                layer.ffn_down, NULL, NULL,
                NULL,
                LLM_FFN_SWIGLU, LLM_FFN_SEQ, il);
        cb(cur, "ffn_out", il);

        cur = build_norm(cur, layer.ffn_post_norm, NULL, LLM_NORM_RMS, il);
        cb(cur, "ffn_post_norm", il);

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
