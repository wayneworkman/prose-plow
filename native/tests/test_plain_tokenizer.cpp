// Token-exactness of llama.cpp's own tokenizer for a model whose reference tokenizer it is
// expected to reproduce without a front-end (the byte-level BPE members of the family).
//
//   test_plain_tokenizer --gguf FILE --vectors FILE
//
// The vector file has the same format as the one for the peacebell front-end. Here "exact" is
// the reference with its special tokens recognised in the text, which llama_tokenize does with
// parse_special = true, and "safe" is the reference with them treated as plain characters,
// which is parse_special = false and what message content must go through.

#include "support/test_support.h"

namespace {

std::vector<llama_token> tokenize(const llama_vocab * vocab, const std::string & text, bool parse_special) {
    std::vector<llama_token> out(text.size() + 8);
    int32_t n = llama_tokenize(vocab, text.data(), (int32_t) text.size(), out.data(), (int32_t) out.size(), false, parse_special);
    if (n < 0) {
        out.resize((size_t) -n);
        n = llama_tokenize(vocab, text.data(), (int32_t) text.size(), out.data(), (int32_t) out.size(), false, parse_special);
    }
    out.resize(n < 0 ? 0 : (size_t) n);
    return out;
}

} // namespace

int main(int argc, char ** argv) {
    std::string gguf, vectors;
    for (int i = 1; i + 1 < argc; i += 2) {
        const std::string key = argv[i];
        if (key == "--gguf") {
            gguf = argv[i + 1];
        } else if (key == "--vectors") {
            vectors = argv[i + 1];
        }
    }
    if (gguf.empty() || vectors.empty()) {
        std::fprintf(stderr, "usage: %s --gguf FILE --vectors FILE\n", argv[0]);
        return 2;
    }

    pp_test::quiet_logs();
    llama_backend_init();
    pp_test::report r;
    {
        pp_test::model_ptr model = pp_test::load_model(gguf, true);
        const llama_vocab * vocab = llama_model_get_vocab(model.get());

        pp_test::reader in(vectors);
        in.expect_magic("PPTOKV01");
        const std::string sha = in.bytes(64);
        const std::string arch = pp_test::meta(model.get(), "general.architecture");
        r.check(pp_test::meta(model.get(), arch + ".tokenizer.source_sha256") == sha, "the vectors were generated from the model's tokenizer file (%.12s...)", sha.c_str());

        const uint32_t n_text = in.u32();
        size_t bad_special = 0, bad_plain = 0, n_tokens = 0, n_differ = 0;
        for (uint32_t i = 0; i < n_text; i++) {
            const std::string text = in.sized_bytes();
            const std::vector<llama_token> exact = in.ids_u16();
            const std::vector<llama_token> safe = in.u8() != 0 ? in.ids_u16() : exact;
            bad_special += tokenize(vocab, text, true) != exact ? 1 : 0;
            bad_plain += tokenize(vocab, text, false) != safe ? 1 : 0;
            n_tokens += exact.size();
            n_differ += exact != safe ? 1 : 0;
        }
        r.check(bad_special == 0, "special tokens parsed: %zu mismatches out of %u texts (%zu tokens)", bad_special, n_text, n_tokens);
        r.check(bad_plain == 0, "special tokens as plain text: %zu mismatches out of %u texts (%zu texts differ between the two)", bad_plain, n_text, n_differ);
    }
    llama_backend_free();
    return r.finish();
}
