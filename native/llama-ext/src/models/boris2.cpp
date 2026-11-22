#include "models.h"

#include "llama-impl.h"
#include "llama-memory-hybrid.h"
#include "llama-memory-recurrent.h"

#include <algorithm>
#include <stdexcept>

// The keys below have no upstream enum entry, and adding entries would widen the hook patch for
// no gain: they are read with the raw gguf calls. Bad metadata throws, which fails the load;
// an assertion would take the whole process down.

static uint32_t boris2_read_u32(const gguf_context * ctx, const std::string & key) {
    const int64_t kid = gguf_find_key(ctx, key.c_str());
    if (kid < 0 || gguf_get_kv_type(ctx, kid) != GGUF_TYPE_UINT32) {
        throw std::runtime_error(format("%s must be a uint32 value", key.c_str()));
    }
    return gguf_get_val_u32(ctx, kid);
}

// Reads an optional array of one element type. Returns false when the key is absent.
template <typename T>
static bool boris2_read_array(const gguf_context * ctx, const std::string & key, gguf_type type, std::vector<T> & out) {
    out.clear();
    const int64_t kid = gguf_find_key(ctx, key.c_str());
    if (kid < 0) {
        return false;
    }
    if (gguf_get_kv_type(ctx, kid) != GGUF_TYPE_ARRAY || gguf_get_arr_type(ctx, kid) != type) {
        throw std::runtime_error(format("%s has the wrong type", key.c_str()));
    }
    const T * data = (const T *) gguf_get_arr_data(ctx, kid);
    out.assign(data, data + gguf_get_arr_n(ctx, kid));
    return true;
}

// One lookup table is usable when the keys can be binary-searched and every row exists.
static void boris2_check_table(const std::string & name, const std::vector<uint64_t> & keys, const std::vector<int32_t> & rows, uint32_t n_rows) {
    if (keys.size() != rows.size()) {
        throw std::runtime_error(format("%s: %zu keys but %zu rows", name.c_str(), keys.size(), rows.size()));
    }
    for (size_t i = 0; i < keys.size(); i++) {
        if (i > 0 && keys[i] <= keys[i - 1]) {
            throw std::runtime_error(format("%s: the keys are not strictly increasing", name.c_str()));
        }
        if (rows[i] < 0 || (uint32_t) rows[i] >= n_rows) {
            throw std::runtime_error(format("%s: a row is outside the table", name.c_str()));
        }
    }
}

void llama_model_boris2::load_arch_hparams(llama_model_loader & ml) {
    ml.get_key(LLM_KV_ATTENTION_LAYERNORM_RMS_EPS, hparams.f_norm_rms_eps);
    ml.get_key(LLM_KV_SHORTCONV_L_CACHE,           hparams.n_shortconv_l_cache);

    // The graph cuts the fused projection into three equal parts and keeps exactly two columns
    // of Canon history per layer.
    if (hparams.n_head() != hparams.n_head_kv()) {
        throw std::runtime_error("boris2: grouped-query attention is not supported");
    }
    if (hparams.n_shortconv_l_cache != 3) {
        throw std::runtime_error(format("boris2: a Canon kernel of %u taps is not supported, only 3", hparams.n_shortconv_l_cache));
    }

    // Width of the fused projection, Q | K | V. Together with the kernel size it gives the
    // recurrent row its size: n_sconv_channels * (n_shortconv_l_cache - 1) values per layer.
    hparams.n_sconv_channels = hparams.n_embd + 2 * hparams.n_embd_k_gqa();

    const gguf_context * ctx  = ml.metadata;
    const std::string    arch = ml.get_arch_name();

    ngram_n_rows  = boris2_read_u32(ctx, arch + ".ngram.row_count");
    ngram_n_embd  = boris2_read_u32(ctx, arch + ".ngram.embedding_length");
    ngram_layer   = boris2_read_u32(ctx, arch + ".ngram.insert_layer");
    ngram_oov_row = boris2_read_u32(ctx, arch + ".ngram.oov_row");
    if (ngram_n_rows == 0 || ngram_n_embd == 0 || ngram_oov_row >= ngram_n_rows || ngram_layer >= hparams.n_layer()) {
        throw std::runtime_error("boris2: inconsistent n-gram table description");
    }

    const bool has_bigrams  = boris2_read_array(ctx, arch + ".ngram.bigram_keys",  GGUF_TYPE_UINT64, ngram_bigram_keys);
    const bool has_trigrams = boris2_read_array(ctx, arch + ".ngram.trigram_keys", GGUF_TYPE_UINT64, ngram_trigram_keys);
    boris2_read_array(ctx, arch + ".ngram.bigram_rows",  GGUF_TYPE_INT32, ngram_bigram_rows);
    boris2_read_array(ctx, arch + ".ngram.trigram_rows", GGUF_TYPE_INT32, ngram_trigram_rows);
    if (has_bigrams != has_trigrams) {
        throw std::runtime_error("boris2: the n-gram key arrays must be both present or both absent");
    }
    boris2_check_table(arch + ".ngram.bigram_keys",  ngram_bigram_keys,  ngram_bigram_rows,  ngram_n_rows);
    boris2_check_table(arch + ".ngram.trigram_keys", ngram_trigram_keys, ngram_trigram_rows, ngram_n_rows);

    type = LLM_TYPE_UNKNOWN;
}

void llama_model_boris2::load_arch_tensors(llama_model_loader &) {
    LLAMA_LOAD_LOCALS;

    const int64_t n_qkv  = hparams.n_sconv_channels;
    const int64_t n_conv = hparams.n_shortconv_l_cache;

    tok_embd = create_tensor(tn(LLM_TENSOR_TOKEN_EMBD, "weight"), {n_embd, n_vocab}, 0);

    // The head is tied to the embedding: the file carries no output.weight, the embedding is
    // loaded a second time for the output projection.
    output_norm = create_tensor(tn(LLM_TENSOR_OUTPUT_NORM, "weight"), {n_embd}, 0);
    output      = create_tensor(tn(LLM_TENSOR_TOKEN_EMBD,  "weight"), {n_embd, n_vocab}, TENSOR_DUPLICATED);

    // The n-gram table and its projection to the model width, under the names upstream already
    // has for a per-layer embedding table. Upstream's tensor table counts the projection among
    // the repeating layers although its name carries no layer number, so the loader wants one:
    // the block that consumes it, which also places it on that block's device.
    per_layer_tok_embd   = create_tensor(tn(LLM_TENSOR_PER_LAYER_TOKEN_EMBD, "weight"), {(int64_t) ngram_n_embd, (int64_t) ngram_n_rows}, 0);
    per_layer_model_proj = create_tensor(tn(LLM_TENSOR_PER_LAYER_MODEL_PROJ, "weight", (int) ngram_layer), {(int64_t) ngram_n_embd, n_embd}, 0);

    for (int i = 0; i < n_layer; ++i) {
        auto & layer = layers[i];

        layer.attn_norm = create_tensor(tn(LLM_TENSOR_ATTN_NORM, "weight", i), {n_embd}, 0);

        // One fused projection whose rows are ordered Q, K, V, and the Canon kernel over it.
        layer.wqkv           = create_tensor(tn(LLM_TENSOR_ATTN_QKV,       "weight", i), {n_embd, n_qkv}, 0);
        layer.shortconv.conv = create_tensor(tn(LLM_TENSOR_SHORTCONV_CONV, "weight", i), {n_conv, n_qkv}, 0);
        layer.wo             = create_tensor(tn(LLM_TENSOR_ATTN_OUT,       "weight", i), {n_embd, n_embd}, 0);

        layer.ffn_norm = create_tensor(tn(LLM_TENSOR_FFN_NORM, "weight", i), {n_embd}, 0);
        // Gate and up projection fused, the gate half first.
        layer.ffn_up   = create_tensor(tn(LLM_TENSOR_FFN_UP,   "weight", i), {n_embd, 2 * n_ff}, 0);
        layer.ffn_down = create_tensor(tn(LLM_TENSOR_FFN_DOWN, "weight", i), {  n_ff,   n_embd}, 0);
    }
}

int32_t llama_model_boris2::ngram_row(llama_token t2, llama_token t1, llama_token t0) const {
    const int32_t  oov     = (int32_t) ngram_oov_row;
    const uint64_t n_vocab = vocab.n_tokens();

    const auto lookup = [oov](const std::vector<uint64_t> & keys, const std::vector<int32_t> & rows, uint64_t key) {
        const auto it = std::lower_bound(keys.begin(), keys.end(), key);
        return it != keys.end() && *it == key ? rows[it - keys.begin()] : oov;
    };

    // The first token of a sequence has no n-gram, the second no trigram.
    if (t1 < 0) {
        return oov;
    }
    const int32_t bigram  = lookup(ngram_bigram_keys, ngram_bigram_rows, (uint64_t) t1 * n_vocab + (uint64_t) t0);
    const int32_t trigram = t2 < 0 ? oov
        : lookup(ngram_trigram_keys, ngram_trigram_rows, ((uint64_t) t2 * n_vocab + (uint64_t) t1) * n_vocab + (uint64_t) t0);

    return trigram != oov ? trigram : bigram;
}

// The row of the n-gram table for every token of a ubatch. The lookup runs on the host: its
// keys are 64-bit integers, which ggml has no tensor operations for.
class llm_graph_input_boris2_ngram : public llm_graph_input_i {
public:
    llm_graph_input_boris2_ngram(const llama_model_boris2 & model, const llama_kv_cache_context * mctx) : model(model), mctx(mctx) {}
    virtual ~llm_graph_input_boris2_ngram() = default;

    void set_input(const llama_ubatch * ubatch) override;

    bool can_reuse(const llm_graph_params & params) override {
        mctx = static_cast<const llama_memory_hybrid_context *>(params.mctx)->get_attn();
        return rows->ne[0] == params.ubatch.n_tokens;
    }

    ggml_tensor * rows = nullptr; // I32 [n_tokens]

    const llama_model_boris2 & model;

    // The two preceding token ids are not kept by this input: the KV cache stores the id of
    // every cell, so the history is whatever the cache holds and follows it through a rewind.
    const llama_kv_cache_context * mctx;

    // scratch, reused across set_input() calls
    std::vector<llama_token> prev;
    std::vector<int32_t>     idx;
};

void llm_graph_input_boris2_ngram::set_input(const llama_ubatch * ubatch) {
    const int64_t     n_tokens = ubatch->n_tokens;
    const llama_token eos      = model.vocab.token_eos();

    // The n-gram of a position is made of token ids: there is nothing to look up for raw embeddings.
    GGML_ASSERT(ubatch->token != nullptr && "boris2 takes token input only");
    GGML_ASSERT(mctx != nullptr);

    for (int64_t i = 0; i < n_tokens; ++i) {
        // the preceding tokens would be ambiguous, see get_prev_tokens()
        GGML_ASSERT(ubatch->n_seq_id[i] == 1 && "boris2 does not support tokens shared by several sequences");
        // The reference starts a new document after this token (positions, attention, Canon and
        // this lookup all restart). The graph has no such reset, so the token must not be fed.
        GGML_ASSERT(ubatch->token[i] != eos && "boris2: the end-of-text token cannot be an input; start a new sequence instead");
    }

    // apply_ubatch() has already stored this ubatch, so predecessors inside it, in an earlier
    // ubatch of the same call and in earlier calls are all found the same way.
    // prev[i*2 + 0] is the token two positions back, prev[i*2 + 1] the one before; LLAMA_TOKEN_NULL when absent.
    mctx->get_prev_tokens(*ubatch, 2, prev);

    idx.resize(n_tokens);
    for (int64_t i = 0; i < n_tokens; ++i) {
        idx[i] = model.ngram_row(prev[i*2 + 0], prev[i*2 + 1], ubatch->token[i]);
    }

    ggml_backend_tensor_set(rows, idx.data(), 0, idx.size()*ggml_element_size(rows));
}

std::unique_ptr<llm_graph_context> llama_model_boris2::build_arch_graph(const llm_graph_params & params) const {
    return std::make_unique<graph>(params);
}

llama_model_boris2::graph::graph(const llm_graph_params & params) : llm_build_delta_net_base(params) {
    const auto & bm = static_cast<const llama_model_boris2 &>(model);

    const int64_t n_embd_head = hparams.n_embd_head_v();

    GGML_ASSERT(n_embd_head == hparams.n_embd_head_k());

    const int64_t n_qkv        = hparams.n_sconv_channels;
    const int64_t n_conv       = hparams.n_shortconv_l_cache;
    const int64_t n_seqs       = ubatch.n_seqs;
    const int64_t n_seq_tokens = ubatch.n_seq_tokens;

    // The hybrid memory splits a batch so that every sequence of a ubatch has the same number
    // of tokens, sequence after sequence; the Canon reshape below relies on it.
    GGML_ASSERT(n_tokens == n_seq_tokens * n_seqs);

    ggml_tensor * cur;
    ggml_tensor * inpL;

    inpL = build_inp_embd(model.tok_embd);

    ggml_tensor * inp_pos = build_inp_pos();

    // KV cache and recurrent row of every layer
    auto *       inp      = build_inp_mem_hybrid();
    const auto * mctx_hyb = static_cast<const llama_memory_hybrid_context *>(mctx);

    ggml_tensor * ngram_rows = nullptr;
    {
        auto inp_ngram = std::make_unique<llm_graph_input_boris2_ngram>(bm, mctx_hyb->get_attn());
        inp_ngram->rows = ggml_new_tensor_1d(ctx0, GGML_TYPE_I32, n_tokens);
        ggml_set_input(inp_ngram->rows);
        ngram_rows = inp_ngram->rows;
        res->add_input(std::move(inp_ngram));
    }

    ggml_tensor * inp_out_ids = build_inp_out_ids();

    for (int il = 0; il < n_layer; ++il) {
        const auto & layer = model.layers[il];

        if (il == (int) bm.ngram_layer) {
            // x = x + proj * table[row]
            ggml_tensor * ngram = ggml_get_rows(ctx0, model.per_layer_tok_embd, ngram_rows);
            ngram = build_lora_mm(model.per_layer_model_proj, ngram);
            cb(ngram, "ngram_embd", il);
            inpL = ggml_add(ctx0, inpL, ngram);
        }

        // x = x + Attn(RMSNorm(x))
        cur = build_norm(inpL, layer.attn_norm, NULL, LLM_NORM_RMS, il);
        cb(cur, "attn_norm", il);

        {
            ggml_tensor * qkv = build_lora_mm(layer.wqkv, cur, layer.wqkv_s);
            cb(qkv, "wqkv", il);

            // Canon: each channel mixed with its values at the two preceding positions. The
            // helper prepends the two columns saved in the layer's recurrent row (zeros when
            // the sequence starts) and stores the last two columns back, so a batch and a
            // single token run the same graph. The kernel holds the identity term.
            qkv = ggml_reshape_3d(ctx0, qkv, n_qkv, n_seq_tokens, n_seqs);
            ggml_tensor * conv_x = build_conv_state(inp->get_recr(), mctx_hyb->get_recr()->get_r_l(il), qkv, n_conv, n_qkv, il);
            qkv = ggml_ssm_conv(ctx0, conv_x, layer.shortconv.conv);
            qkv = ggml_reshape_2d(ctx0, qkv, n_qkv, n_tokens);
            cb(qkv, "canon", il);

            // Q | K | V, as the fused branch of build_qkv() cuts them
            ggml_tensor * Qcur = ggml_view_3d(ctx0, qkv, n_embd_head, n_head,    n_tokens, ggml_row_size(qkv->type, n_embd_head), qkv->nb[1], 0);
            ggml_tensor * Kcur = ggml_view_3d(ctx0, qkv, n_embd_head, n_head_kv, n_tokens, ggml_row_size(qkv->type, n_embd_head), qkv->nb[1],
                    ggml_row_size(qkv->type, n_embd));
            ggml_tensor * Vcur = ggml_view_3d(ctx0, qkv, n_embd_head, n_head_kv, n_tokens, ggml_row_size(qkv->type, n_embd_head), qkv->nb[1],
                    ggml_row_size(qkv->type, n_embd + n_embd_k_gqa));

            Qcur = build_rope(Qcur, inp_pos, il);
            Kcur = build_rope(Kcur, inp_pos, il);

            cb(Qcur, "Qcur", il);
            cb(Kcur, "Kcur", il);
            cb(Vcur, "Vcur", il);

            cur = build_attn(inp->get_attn(),
                    layer.wo, NULL, layer.wo_s,
                    Qcur, Kcur, Vcur, nullptr, nullptr, nullptr, 1.0f/sqrtf(float(n_embd_head)), il);
        }

        if (il == n_layer - 1 && inp_out_ids) {
            cur  = ggml_get_rows(ctx0, cur,  inp_out_ids);
            inpL = ggml_get_rows(ctx0, inpL, inp_out_ids);
        }

        ggml_tensor * ffn_inp = ggml_add(ctx0, cur, inpL);
        cb(ffn_inp, "ffn_inp", il);

        // x = x + down(silu(gate) * up), gate and up being the two halves of one projection
        cur = build_norm(ffn_inp, layer.ffn_norm, NULL, LLM_NORM_RMS, il);
        cb(cur, "ffn_norm", il);

        cur = build_ffn(cur,
                layer.ffn_up,   NULL, NULL,
                NULL,           NULL, NULL,
                layer.ffn_down, NULL, NULL,
                NULL,
                LLM_FFN_SWIGLU, LLM_FFN_SEQ, il);
        cb(cur, "ffn_out", il);

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
