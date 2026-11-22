// Smoke test of the converted Qwen3.5-0.8B file with the patched build.
//
//   test_qwen --gguf FILE
//
// The architecture is upstream's own (qwen35), so there is no port of ours to validate against
// a PyTorch reference here. What this test pins is the contract the app relies on: the file
// loads with our build, it is the architecture and the tokenizer the app's prompt format
// assumes, the markers the prompt format writes are single tokens, greedy generation from a
// ChatML prompt is repeatable, and the memory of this hybrid architecture rewinds the way the
// app will have to rewind it: through a checkpoint of its recurrent state.

#include "support/test_support.h"

#include "proseplow_native.h"

#include <algorithm>

namespace {

using pp_test::report;

// From tokenizer.json of the pinned revision; the app's prompt format for this model is built on them.
constexpr int32_t     k_n_vocab      = 248320;
constexpr llama_token k_endoftext    = 248044; // "<|endoftext|>", the padding token
constexpr llama_token k_im_start     = 248045;
constexpr llama_token k_im_end       = 248046; // ends a turn: the end-of-sequence token of the file

// The model's own context length is 262,144 tokens; a context of that size would allocate
// gigabytes. The app always sets the size, and so does this test.
constexpr uint32_t k_n_ctx = 512;
constexpr int k_n_new_tokens = 24;

// The prompt the chat template renders for one question with thinking switched off.
const char * const k_prompt =
    "<|im_start|>system\nYou are a helpful assistant.<|im_end|>\n"
    "<|im_start|>user\nWhat is the capital of Australia?<|im_end|>\n"
    "<|im_start|>assistant\n<think>\n\n</think>\n\n";

std::vector<llama_token> tokenize(const llama_vocab * vocab, const std::string & text, bool parse_special) {
    std::vector<llama_token> out(text.size() + 8);
    const int32_t n = llama_tokenize(vocab, text.data(), (int32_t) text.size(), out.data(), (int32_t) out.size(), false, parse_special);
    if (n < 0) {
        pp_test::die("llama_tokenize failed");
    }
    out.resize((size_t) n);
    return out;
}

std::string detokenize(const llama_vocab * vocab, const std::vector<llama_token> & tokens) {
    std::string text(tokens.size() * 16 + 16, '\0');
    const int32_t n = llama_detokenize(vocab, tokens.data(), (int32_t) tokens.size(), text.data(), (int32_t) text.size(), false, true);
    text.resize(n < 0 ? 0 : (size_t) n);
    return text;
}

// One context and the tokens it was given, fed the way the app feeds them.
class chat {
  public:
    chat(llama_model * model) : vocab(llama_model_get_vocab(model)) {
        llama_context_params params = llama_context_default_params();
        params.n_ctx           = k_n_ctx;
        params.n_batch         = k_n_ctx;
        params.n_threads       = pp_test::n_threads();
        params.n_threads_batch = pp_test::n_threads();
        params.no_perf         = true;
        ctx.reset(llama_init_from_model(model, params));
        if (!ctx) {
            pp_test::die("cannot create a context");
        }
        batch = llama_batch_ext_init(ctx.get());
        sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
        llama_sampler_chain_add(sampler, llama_sampler_init_greedy());
    }

    ~chat() {
        llama_sampler_free(sampler);
        llama_batch_ext_free(batch);
    }

    // Decodes ids[begin, end) at the positions offset + begin .., with logits for the last one.
    void decode(const std::vector<llama_token> & ids, size_t begin, size_t end, size_t offset = 0) {
        llama_batch_ext_clear(batch);
        for (size_t i = begin; i < end; i++) {
            const int32_t idx = llama_batch_ext_add_token(batch, 0, ids[i]);
            const llama_pos p = (llama_pos) (offset + i);
            if (idx < 0 || !llama_batch_ext_set_pos(batch, idx, &p) || !llama_batch_ext_set_output_logits(batch, idx, i + 1 == end)) {
                pp_test::die("cannot build a batch");
            }
        }
        const int32_t rc = llama_process(ctx.get(), LLAMA_PROCESS_TYPE_DECODE, batch);
        if (rc != 0) {
            pp_test::die("llama_process returned " + std::to_string(rc));
        }
    }

    // Greedy continuation of the n_prompt tokens in memory, one token per call, until an
    // end-of-generation token or k_n_new_tokens.
    std::vector<llama_token> generate(size_t n_prompt) {
        std::vector<llama_token> reply;
        const int64_t t0 = llama_time_us();
        while ((int) reply.size() < k_n_new_tokens) {
            const llama_token token = llama_sampler_sample(sampler, ctx.get(), -1);
            if (llama_vocab_is_eog(vocab, token)) {
                break;
            }
            reply.push_back(token);
            // Token i of the reply is at position n_prompt + i.
            decode(reply, reply.size() - 1, reply.size(), n_prompt);
        }
        seconds += (double) (llama_time_us() - t0) / 1e6;
        n_generated += reply.size();
        return reply;
    }

    llama_context * context() const { return ctx.get(); }
    llama_memory_t memory() const { return llama_get_memory(ctx.get()); }

    double seconds = 0.0;
    size_t n_generated = 0;

  private:
    const llama_vocab * vocab;
    pp_test::context_ptr ctx;
    llama_batch_ext * batch = nullptr;
    llama_sampler * sampler = nullptr;
};

} // namespace

int main(int argc, char ** argv) {
    std::string gguf;
    for (int i = 1; i + 1 < argc; i += 2) {
        if (std::string(argv[i]) == "--gguf") {
            gguf = argv[i + 1];
        }
    }
    if (gguf.empty()) {
        std::fprintf(stderr, "usage: %s --gguf FILE\n", argv[0]);
        return 2;
    }

    pp_test::quiet_logs();
    llama_backend_init();
    report r;
    {
        pp_test::model_ptr model = pp_test::load_model(gguf, false);
        const llama_vocab * vocab = llama_model_get_vocab(model.get());

        const std::string arch = pp_test::meta(model.get(), "general.architecture");
        r.check(arch == "qwen35", "architecture is '%s'", arch.c_str());

        const std::string tokenizer = pp_test::meta(model.get(), "tokenizer.ggml.model");
        const std::string pre = pp_test::meta(model.get(), "tokenizer.ggml.pre");
        r.check(tokenizer == "gpt2" && pre == "qwen35" && llama_vocab_type(vocab) == LLAMA_VOCAB_TYPE_BPE,
                "tokenizer is '%s' with pre-tokenizer '%s' (byte-level BPE)", tokenizer.c_str(), pre.c_str());
        r.check(llama_vocab_n_tokens(vocab) == k_n_vocab, "vocabulary has %d tokens", llama_vocab_n_tokens(vocab));

        // The file has no beginning-of-text token; llama.cpp then reports a default id that must never be added.
        r.check(llama_vocab_eos(vocab) == k_im_end && llama_vocab_pad(vocab) == k_endoftext && !llama_vocab_get_add_bos(vocab) && !llama_vocab_get_add_eos(vocab),
                "end of sequence %d, padding %d, no token added at either end", llama_vocab_eos(vocab), llama_vocab_pad(vocab));
        r.check(llama_vocab_is_eog(vocab, k_im_end) && llama_vocab_is_eog(vocab, k_endoftext),
                "<|im_end|> and <|endoftext|> both end a generation");

        // Every marker is one token when the application writes it (parse_special = true). Inside a
        // message (parse_special = false) the two kinds differ, and the app must know which is
        // which: the ChatML markers are control tokens and become plain characters, so a message
        // cannot close its own turn; the thinking and tool markers are user-defined tokens, which
        // llama.cpp (like the reference tokenizer) matches in any text, so a message that
        // contains "<think>" does produce the marker token.
        const struct { const char * text; llama_token id; bool control; } markers[] = {
            {"<|endoftext|>", k_endoftext, true}, {"<|im_start|>", k_im_start, true}, {"<|im_end|>", k_im_end, true},
            {"<think>", 248068, false}, {"</think>", 248069, false},
            {"<tool_call>", 248058, false}, {"</tool_call>", 248059, false},
            {"<tool_response>", 248066, false}, {"</tool_response>", 248067, false},
        };
        size_t n_marker_ok = 0, n_plain_ok = 0;
        for (const auto & m : markers) {
            const std::vector<llama_token> single = {m.id};
            const std::vector<llama_token> plain = tokenize(vocab, m.text, false);
            n_marker_ok += tokenize(vocab, m.text, true) == single && std::string(llama_vocab_get_text(vocab, m.id)) == m.text
                    && llama_vocab_is_control(vocab, m.id) == m.control ? 1 : 0;
            const bool stays_text = plain.size() > 1 && std::find(plain.begin(), plain.end(), m.id) == plain.end();
            n_plain_ok += (m.control ? stays_text : plain == single) ? 1 : 0;
        }
        r.check(n_marker_ok == std::size(markers), "%zu of %zu markers are the expected single token when parsed as special", n_marker_ok, std::size(markers));
        r.check(n_plain_ok == std::size(markers),
                "%zu of %zu markers behave as expected inside a message: ChatML markers are plain characters, thinking and tool markers are still tokens",
                n_plain_ok, std::size(markers));
        // Markers that are tokens rule out the plain-text ChatML encoder of our library for this model.
        r.check(!pp_chatml_text_supported(model.get()), "the plain-text ChatML encoder refuses this model");

        const std::vector<llama_token> prompt = tokenize(vocab, k_prompt, true);
        const size_t n = prompt.size();
        const auto count = [&](llama_token t) { return std::count(prompt.begin(), prompt.end(), t); };
        r.check(prompt.front() == k_im_start && count(k_im_start) == 3 && count(k_im_end) == 2 && count(248068) == 1 && count(248069) == 1,
                "the ChatML prompt is %zu tokens with 3 start, 2 end and 2 thinking markers", n);
        r.check(detokenize(vocab, prompt) == k_prompt, "the prompt tokens render back to the prompt text");

        // A reply, with a checkpoint of the recurrent state taken one token before the end of the
        // prompt: a checkpoint restores state, not logits, so the last prompt token is decoded
        // again after a rewind.
        chat first_chat(model.get());
        first_chat.decode(prompt, 0, n - 1);
        std::vector<uint8_t> saved(llama_state_seq_get_size_ext(first_chat.context(), 0, LLAMA_STATE_SEQ_FLAGS_PARTIAL_ONLY));
        const size_t n_saved = llama_state_seq_get_data_ext(first_chat.context(), saved.data(), saved.size(), 0, LLAMA_STATE_SEQ_FLAGS_PARTIAL_ONLY);
        first_chat.decode(prompt, n - 1, n);
        const std::vector<llama_token> first = first_chat.generate(n);

        // Regenerate. This architecture has recurrent layers: the memory cannot be cut inside the
        // sequence until the recurrent state of the cut position is restored.
        const bool refused = !llama_memory_seq_rm(first_chat.memory(), 0, (llama_pos) (n - 1), -1);
        const bool restored = n_saved == saved.size()
                && llama_state_seq_set_data_ext(first_chat.context(), saved.data(), saved.size(), 0, LLAMA_STATE_SEQ_FLAGS_PARTIAL_ONLY) == saved.size()
                && llama_memory_seq_rm(first_chat.memory(), 0, (llama_pos) (n - 1), -1);
        r.check(refused && restored, "llama_memory_seq_rm into the sequence is refused without the checkpoint and succeeds after restoring it (%zu bytes)", saved.size());
        first_chat.decode(prompt, n - 1, n);
        const std::vector<llama_token> again = first_chat.generate(n);

        // The same context after clearing its memory, and a new context: a greedy reply must
        // not depend on what the process did before.
        llama_memory_clear(first_chat.memory(), true);
        first_chat.decode(prompt, 0, n);
        const std::vector<llama_token> cleared = first_chat.generate(n);
        chat second_chat(model.get());
        second_chat.decode(prompt, 0, n);
        const std::vector<llama_token> fresh = second_chat.generate(n);
        r.check(!first.empty() && first == again && first == cleared && first == fresh,
                "greedy reply of %zu tokens identical after a rewind through the checkpoint, after clearing the memory and in a new context", first.size());

        std::string text = detokenize(vocab, first);
        std::replace(text.begin(), text.end(), '\n', ' ');
        r.check(text.find("Canberra") != std::string::npos, "the reply names the capital: \"%s\"", text.c_str());
        r.note("generation %.1f tokens/s (%zu tokens, %d thread(s))",
               (double) (first_chat.n_generated + second_chat.n_generated) / (first_chat.seconds + second_chat.seconds),
               first_chat.n_generated + second_chat.n_generated, pp_test::n_threads());
    }
    llama_backend_free();
    return r.finish();
}
