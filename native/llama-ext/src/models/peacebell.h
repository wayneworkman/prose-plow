// The peacebell family: a pre-norm decoder with RMSNorm, NeoX RoPE over the whole head, an
// ungated exact-erf GELU MLP, a ReZero gate on each sublayer and a head tied to the embedding.
//
// This file is not part of upstream llama.cpp. It is copied next to models.h by
// native/tools/prepare-llama-src.sh and included from the end of models.h by the hook patch, so
// it relies on everything models.h has already included and must not be included on its own.
//
// Nothing here is specific to one checkpoint: width, depth, heads, context, vocabulary and RoPE
// base all come from GGUF metadata through the generic loader.

struct llama_model_peacebell : public llama_model_base {
    llama_model_peacebell(const struct llama_model_params & params) : llama_model_base(params) {}
    void load_arch_hparams(llama_model_loader & ml) override;
    void load_arch_tensors(llama_model_loader & ml) override;

    // About 45 graph nodes per layer against 6 tensors per layer: the default budget of 8 nodes
    // per tensor is too tight for the smaller members of the family.
    uint32_t graph_nodes_per_tensor() const override { return 16; }

    // ReZero gates of the attention and MLP sublayers, one per layer, applied as an F32 multiply
    // in the graph. They are deliberately not folded into the output projections: folded, a fifth
    // of layer 0 would fall into the half-precision subnormal range of a 16-bit file. A file
    // without the arrays is treated as ungated (1.0).
    std::array<float, LLAMA_MAX_LAYERS> attn_out_scale;
    std::array<float, LLAMA_MAX_LAYERS> ffn_out_scale;

    struct graph : public llm_graph_context {
        graph(const llm_graph_params & params);
    };

    std::unique_ptr<llm_graph_context> build_arch_graph(const llm_graph_params & params) const override;
};
