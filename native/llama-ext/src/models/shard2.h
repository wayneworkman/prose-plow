// Shard-2: a decoder of "sandwich-normed" blocks (a norm before and after each sublayer, all of
// them RMSNorm with one epsilon), a fused q/k/v projection with an RMS norm over the head on Q
// and on K, a fused SwiGLU feed-forward and a head tied to the embedding. Its layers are of two
// kinds: LOCAL layers attend to a sliding window of keys and rotate Q and K by position; GLOBAL
// layers attend to the whole sequence and carry no position at all.
//
// This file is not part of upstream llama.cpp. It is copied next to models.h by
// native/tools/prepare-llama-src.sh and included from the end of models.h by the hook patch, so
// it relies on everything models.h has already included and must not be included on its own.
//
// Nothing here is specific to one checkpoint, and nothing is computed by code of ours: which
// layers are local, which rotate, the window, the epsilon and every dimension come from GGUF
// metadata, and the graph is built from upstream's helpers alone.
//
// Memory. A model with windowed layers gets upstream's interleaved sliding-window cache
// (llama_kv_cache_iswa): one cell store for the global layers and one for the local layers.
// Consequences a caller must know:
// - llama_memory_seq_rm() on this memory never refuses. A cut to n tokens is only RIGHT while the
//   local store still holds the window before position n, which a caller checks with
//       llama_memory_seq_pos_min(mem, seq) <= max(0, n - (n_swa - 1))
//   and which a partial sequence state (LLAMA_STATE_SEQ_FLAGS_PARTIAL_ONLY, the local store's
//   window) saved at n tokens re-establishes. See "The shard2 architecture" in native/README.md.
// - With llama_context_params.swa_full the local store keeps every cell and the test above only
//   fails after such a state was restored; without it the store is a little larger than the
//   window and the test fails for every cut that goes back further than the store reaches.

struct llama_model_shard2 : public llama_model_base {
    llama_model_shard2(const struct llama_model_params & params) : llama_model_base(params) {}
    void load_arch_hparams(llama_model_loader & ml) override;
    void load_arch_tensors(llama_model_loader & ml) override;

    // The graph of the published model has between 1,025 and 1,212 nodes without flash attention,
    // about 55 per layer against 10 tensors per layer, and fewer than 1,024 with it (measured by
    // lowering this value until llama.cpp gave up). The default budget of 8 nodes per tensor
    // would hold that with a third to spare; exceeding the budget ends the process, so the
    // margin is doubled against the day upstream's attention helper needs a few nodes more.
    uint32_t graph_nodes_per_tensor() const override { return 16; }

    struct graph : public llm_graph_context {
        graph(const llm_graph_params & params);
    };

    std::unique_ptr<llm_graph_context> build_arch_graph(const llm_graph_params & params) const override;
};
