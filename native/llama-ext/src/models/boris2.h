// Boris-2: a pre-norm decoder with RMSNorm, a fused q/k/v projection, NeoX RoPE over the whole
// head, a fused SwiGLU MLP and a head tied to the embedding, plus two things no stock
// architecture has: Canon, a causal 3-tap depthwise convolution over the fused projection, and
// an n-gram embedding added to the residual stream before one block.
//
// This file is not part of upstream llama.cpp. It is copied next to models.h by
// native/tools/prepare-llama-src.sh and included from the end of models.h by the hook patch, so
// it relies on everything models.h has already included and must not be included on its own.
//
// Memory. Canon at a position needs the raw projections of the two positions before it, which
// no KV cache holds, so the architecture is "hybrid": every layer has a KV cache and one
// recurrent row with those two projections. Consequences a caller must know:
// - llama_memory_seq_rm() to a position inside the sequence FAILS (returns false) unless the
//   recurrent state of exactly that position was restored first, from a checkpoint taken with
//   llama_state_seq_get_data_ext(..., LLAMA_STATE_SEQ_FLAGS_PARTIAL_ONLY). Removing the whole
//   sequence always works.
// - The model is exact for one document per sequence: the end-of-text token is never an input.
//   The reference restarts positions, attention, Canon and the n-gram lookup after that token;
//   this graph does not, and refuses the token instead of computing something else.

struct llama_model_boris2 : public llama_model_base {
    llama_model_boris2(const struct llama_model_params & params) : llama_model_base(params) {}
    void load_arch_hparams(llama_model_loader & ml) override;
    void load_arch_tensors(llama_model_loader & ml) override;

    // About 60 graph nodes per layer against 7 tensors per layer: far above the default budget
    // of 8 nodes per tensor.
    uint32_t graph_nodes_per_tensor() const override { return 32; }

    // The n-gram lookup, from GGUF metadata: sorted keys with the table row of each. A bigram
    // key is t1 * n_vocab + t0 and a trigram key (t2 * n_vocab + t1) * n_vocab + t0, where t0 is
    // the token at a position and t1, t2 the tokens one and two positions before it. A file
    // without the arrays is valid: every token then gets the miss row.
    std::vector<uint64_t> ngram_bigram_keys;
    std::vector<int32_t>  ngram_bigram_rows;
    std::vector<uint64_t> ngram_trigram_keys;
    std::vector<int32_t>  ngram_trigram_rows;

    uint32_t ngram_n_rows  = 0; // rows of the table
    uint32_t ngram_n_embd  = 0; // width of a table row, before the projection to n_embd
    uint32_t ngram_layer   = 0; // the embedding is added to the input of this block
    uint32_t ngram_oov_row = 0; // the row of a token without a matching n-gram

    // The table row for token t0 preceded by t1 and, before that, t2. A negative id means the
    // sequence has no token there. The trigram wins when it is found.
    int32_t ngram_row(llama_token t2, llama_token t1, llama_token t0) const;

    // llm_build_delta_net_base for build_conv_state(), the helper that reads a convolution
    // history from a recurrent row, prepends it and stores the new tail.
    struct graph : public llm_build_delta_net_base {
        graph(const llm_graph_params & params);
    };

    std::unique_ptr<llm_graph_context> build_arch_graph(const llm_graph_params & params) const override;
};
