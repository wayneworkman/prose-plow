// Validates a converted Shard-2 GGUF against logits of the PyTorch reference model.
//
//   test_shard2_logits --gguf FILE --precision f32|f16 --ref FILE --long-ref FILE
//                      [--context-ref FILE] [--sample N]
//   test_shard2_logits --gguf FILE --mode refuse
//
// The reference files (native/tests/refgen/formats.py) carry the whole logit vector at every
// position, which for a vocabulary of 4,097 tokens costs little:
//   --ref          short prompts, each with the reference's greedy continuation;
//   --long-ref     sequences longer than the attention window of the local layers (300, 600 and
//                  1,500 tokens against a window of 256): a prompt and what the reference
//                  sampled after it, deletion tokens included, and one sequence in which each
//                  of the four special ids is an input;
//   --context-ref  one sequence as long as the context to be validated, beyond the 4,096
//                  tokens the model was trained on.
//
// --precision f32 is the gate for the port itself: F32 file, F32 KV cache, no flash attention.
// Every way of feeding the same tokens must reproduce the reference within 5e-3 over the whole
// vocabulary, the special ids and the deletion token included, and the greedy continuations
// exactly.
//
// --precision f16 is the gate for the file that ships (F16 KV cache, default attention path):
// the distributions must stay within the KL and top-1 limits of the 16-bit format.
//
// Both precisions run the memory checks that are specific to this architecture. Its local
// layers keep their keys and values in a cell store of their own (llama_kv_cache_iswa), which
// is either as large as the context (llama_context_params.swa_full, the default) or a little
// larger than the window. llama_memory_seq_rm() never refuses a cut of this memory, so the
// tests pin when a cut is right, how a caller can tell beforehand, and that a cut made against
// that rule is visibly wrong:
// - with the full-size store every cut equals a fresh evaluation;
// - with the pruned store a cut is right exactly while
//       llama_memory_seq_pos_min(mem, seq) <= max(0, n - (n_swa - 1))
//   holds; a cut that fails the test is accepted all the same and changes the logits;
// - a partial sequence state (LLAMA_STATE_SEQ_FLAGS_PARTIAL_ONLY) restored before the cut
//   makes it exact, and leaves the local store with that window only, whatever its size;
// - a full sequence state restores into a new context bit for bit.
//
// --sample N (a measurement, with a loose sanity gate): N tokens generated with the sampling
// settings of the reference script, counting the deletion token and each special id.
//
// --mode refuse is a second, short run on a file that lacks something the architecture needs
// (the window, or one of the two per-layer flag arrays): it passes when the load fails. Such a
// file must not be run as some other model, and must not end the process either.

#include "support/logit_support.h"

#include "gguf.h"

#include <cstring>

namespace {

using namespace pp_test;

using blob = std::vector<uint8_t>;

// The window of the local layers as a caller saves it between requests.
blob checkpoint(llama_context * ctx) {
    blob data(llama_state_seq_get_size_ext(ctx, 0, LLAMA_STATE_SEQ_FLAGS_PARTIAL_ONLY));
    if (llama_state_seq_get_data_ext(ctx, data.data(), data.size(), 0, LLAMA_STATE_SEQ_FLAGS_PARTIAL_ONLY) != data.size()) {
        die("cannot take a checkpoint");
    }
    return data;
}

bool restore(llama_context * ctx, const blob & data) {
    return llama_state_seq_set_data_ext(ctx, data.data(), data.size(), 0, LLAMA_STATE_SEQ_FLAGS_PARTIAL_ONLY) == data.size();
}

// The discipline: a cut to n tokens is right only while the local store still holds every key
// the token at position n will look at, the n_swa - 1 positions before it.
bool cut_is_safe(llama_context * ctx, size_t n, int32_t n_swa) {
    const llama_pos pos_min = llama_memory_seq_pos_min(llama_get_memory(ctx), 0);
    return n == 0 || pos_min <= std::max<llama_pos>(0, (llama_pos) n - (n_swa - 1));
}

// Two runs of the port that must agree: within the F32 limit over the whole vocabulary, or,
// with an F16 cache (whose cells were rounded from values computed in another batch shape),
// within the limits of the 16-bit format.
void same(report & r, bool f16, const logit_limits & limits, const char * what, double max_abs, const agreement & a) {
    if (f16) {
        judge(r, true, what, a, limits);
    } else {
        r.check(max_abs <= limits.f32_max_abs, "%-36s max |dlogit| %.3g (limit %.0e), whole vocabulary", what, max_abs, limits.f32_max_abs);
    }
}

// The first cut tokens of head followed by tail: what a text becomes when it is cut and continued otherwise.
std::vector<llama_token> splice(const std::vector<llama_token> & head, size_t cut, const std::vector<llama_token> & tail) {
    std::vector<llama_token> out(head.begin(), head.begin() + (long) cut);
    out.insert(out.end(), tail.begin(), tail.end());
    return out;
}

// How many layers the file marks as local (windowed): the layers a checkpoint holds cells of.
size_t count_local_layers(const std::string & path, const std::string & arch) {
    gguf_init_params params = {true, nullptr};
    gguf_context * ctx = gguf_init_from_file(path.c_str(), params);
    if (ctx == nullptr) {
        die("cannot read the metadata of " + path);
    }
    size_t n = 0;
    const int64_t id = gguf_find_key(ctx, (arch + ".attention.sliding_window_pattern").c_str());
    if (id >= 0 && gguf_get_kv_type(ctx, id) == GGUF_TYPE_ARRAY && gguf_get_arr_type(ctx, id) == GGUF_TYPE_BOOL) {
        const int8_t * flags = (const int8_t *) gguf_get_arr_data(ctx, id);
        for (size_t i = 0; i < gguf_get_arr_n(ctx, id); i++) {
            n += flags[i] != 0 ? 1 : 0;
        }
    }
    gguf_free(ctx);
    return n;
}

} // namespace

int main(int argc, char ** argv) {
    std::string gguf, ref_path, long_path, context_path, precision, mode;
    int n_sample = 0;
    for (int i = 1; i + 1 < argc; i += 2) {
        const std::string key = argv[i];
        if (key == "--gguf") {
            gguf = argv[i + 1];
        } else if (key == "--ref") {
            ref_path = argv[i + 1];
        } else if (key == "--long-ref") {
            long_path = argv[i + 1];
        } else if (key == "--context-ref") {
            context_path = argv[i + 1];
        } else if (key == "--precision") {
            precision = argv[i + 1];
        } else if (key == "--sample") {
            n_sample = std::atoi(argv[i + 1]);
        } else if (key == "--mode") {
            mode = argv[i + 1];
        }
    }
    if (mode == "refuse" && !gguf.empty()) {
        // The loader reports the reason as an error; here the refusal is the expected outcome.
        llama_log_set([](enum ggml_log_level, const char *, void *) {}, nullptr);
        llama_backend_init();
        llama_model_params params = llama_model_default_params();
        params.n_gpu_layers = 0;
        llama_model * loaded = llama_model_load_from_file(gguf.c_str(), params);
        report r;
        r.check(loaded == nullptr, "%s %s", gguf.substr(gguf.find_last_of('/') + 1).c_str(), loaded == nullptr ? "is refused when it is loaded" : "was LOADED");
        llama_model_free(loaded);
        llama_backend_free();
        return r.finish();
    }
    if (gguf.empty() || ref_path.empty() || long_path.empty() || (precision != "f32" && precision != "f16") || !mode.empty()) {
        std::fprintf(stderr, "usage: %s --gguf FILE --precision f32|f16 --ref FILE --long-ref FILE [--context-ref FILE] [--sample N]\n"
                             "       %s --gguf FILE --mode refuse\n", argv[0], argv[0]);
        return 2;
    }
    const bool f16 = precision == "f16";

    // The limits of the other ports: 5e-3 for the F32 file, and for the 16-bit file the mean KL
    // of the peacebell family (1e-5). Nothing about this model needed a wider one.
    logit_limits limits;

    quiet_logs();
    llama_backend_init();
    report r;
    {
        // One list of sequences: the short prompts first, then the long ones.
        reference ref = load_reference(ref_path);
        const size_t n_short = ref.seqs.size();
        {
            reference longer = load_reference(long_path);
            if (longer.n_vocab != ref.n_vocab || longer.top_k != ref.top_k) {
                die("the two reference files do not belong together");
            }
            for (sequence & s : longer.seqs) {
                ref.seqs.push_back(std::move(s));
            }
        }
        if (!ref.full_everywhere()) {
            die("the reference files must carry the whole logit vector at every position");
        }

        model_ptr model = load_model(gguf, false);
        const llama_vocab * vocab = llama_model_get_vocab(model.get());
        const size_t n_vocab = (size_t) llama_vocab_n_tokens(vocab);
        if (n_vocab != ref.n_vocab) {
            die("the reference was made for a vocabulary of another size");
        }
        const std::string arch = meta(model.get(), "general.architecture");
        const int32_t n_swa = llama_model_n_swa(model.get());
        const llama_token bs = (llama_token) std::atoi(meta(model.get(), arch + ".backspace_token_id").c_str());
        const llama_token eos = llama_vocab_eos(vocab);
        r.check(arch == "shard2" && n_swa > 1, "architecture '%s', the local layers see a window of %d keys", arch.c_str(), n_swa);
        r.note("%s, %s weights, %s KV cache, %d thread(s), %zu short and %zu long sequences", gguf.substr(gguf.find_last_of('/') + 1).c_str(),
               precision.c_str(), f16 ? "F16" : "F32", n_threads(), n_short, ref.seqs.size() - n_short);

        // The control tokens of the vocabulary: the tokenizer's three specials and the deletion token.
        std::vector<llama_token> specials;
        for (llama_token id = 0; id < (llama_token) n_vocab; id++) {
            if (llama_vocab_is_control(vocab, id)) {
                specials.push_back(id);
            }
        }
        // What the long sequences must contain to be a test of this model: more tokens than the
        // window, more than twice the window, and every special id among the inputs.
        size_t longest = 0, n_beyond = 0, n_beyond_twice = 0, n_positions = 0;
        std::vector<size_t> n_fed(specials.size(), 0);
        for (const sequence & s : ref.seqs) {
            longest = std::max(longest, s.ids.size());
            n_beyond += s.ids.size() > (size_t) n_swa ? 1 : 0;
            n_beyond_twice += s.ids.size() > 2 * (size_t) n_swa ? 1 : 0;
            n_positions += s.ids.size();
            for (size_t k = 0; k < specials.size(); k++) {
                n_fed[k] += (size_t) std::count(s.ids.begin(), s.ids.end(), specials[k]);
            }
        }
        const bool all_fed = std::all_of(n_fed.begin(), n_fed.end(), [](size_t n) { return n > 0; });
        r.check(specials.size() == 4 && specials.back() == bs && bs == (llama_token) n_vocab - 1 && llama_vocab_is_eog(vocab, eos) && !llama_vocab_is_eog(vocab, bs),
                "control tokens: %zu, the deletion token %d is the last id and does not end a generation, the end-of-text token is %d",
                specials.size(), bs, eos);
        if (specials.size() != 4) {
            return r.finish(); // everything below names the four of them
        }
        r.check(n_beyond >= 3 && n_beyond_twice >= 2 && all_fed,
                "%zu positions; %zu sequences are longer than the window and %zu longer than twice the window (longest %zu); the special ids occur %zu, %zu, %zu and %zu times as inputs",
                n_positions, n_beyond, n_beyond_twice, longest, n_fed.size() > 0 ? n_fed[0] : 0, n_fed.size() > 1 ? n_fed[1] : 0,
                n_fed.size() > 2 ? n_fed[2] : 0, n_fed.size() > 3 ? n_fed[3] : 0);

        evaluator ev(model.get(), f16);
        ev.n_ctx = 2048;
        ev.n_threads_single = std::min(2, n_threads());
        if (longest + 256 > ev.n_ctx) {
            die("the long sequences do not fit the test's context");
        }
        // A second context for the evaluations a result is compared with, so that the memory
        // under test is not disturbed by them. Full-size store: the plain case.
        evaluator ev_fresh(model.get(), f16);
        ev_fresh.n_ctx = ev.n_ctx;
        ev_fresh.n_threads_single = ev.n_threads_single;
        ev_fresh.open(k_n_batch);
        size_t i_longest = 0;
        for (size_t k = 0; k < ref.seqs.size(); k++) {
            if (ref.seqs[k].ids.size() > ref.seqs[i_longest].ids.size()) {
                i_longest = k;
            }
        }
        const std::vector<size_t> one_batch = {k_n_batch};
        const std::vector<size_t> one_token = {1};
        // Uneven on purpose: single tokens between larger batches, as an application produces them.
        const std::vector<size_t> mixed = {3, 1, 1, 8, 2, 1, 17, 300, 1, 1, 64, 5, k_n_batch};

        // Decodes every sequence from an empty memory with the given call sizes and adds the
        // comparison with the reference to a. When out is given the rows are kept; when base is
        // given, d becomes the largest difference to those rows. The ways of feeding that cost
        // one graph per token leave the longest sequence out where another way already covers
        // it: the others are still beyond the window and beyond twice the window.
        const auto pass = [&](const std::vector<size_t> & chunks, agreement & a, std::vector<logit_rows> * out, const std::vector<logit_rows> * base, double * d,
                              bool with_longest = true) {
            for (size_t k = 0; k < ref.seqs.size(); k++) {
                if (!with_longest && k == i_longest) {
                    continue;
                }
                const sequence & s = ref.seqs[k];
                ev.clear();
                logit_rows rows = ev.decode(s.ids, 0, s.ids.size(), chunks);
                compare(ref, s, rows, 0, s.ids.size(), limits, a);
                if (base != nullptr) {
                    *d = std::max(*d, max_abs_between((*base)[k], rows, 0, s.ids.size()));
                }
                if (out != nullptr) {
                    out->push_back(std::move(rows));
                }
            }
        };

        // --- Every way of feeding the tokens, with the full-size local store and the pruned one ---

        std::vector<logit_rows> baseline;
        for (const bool swa_full : {true, false}) {
            const char * store = swa_full ? "full-size store" : "pruned store";
            ev.swa_full = swa_full;
            ev.open(k_n_batch);
            agreement a_batch, a_token, a_mixed;
            double d_batch = 0.0, d_token = 0.0, d_mixed = 0.0;
            if (swa_full) {
                pass(one_batch, a_batch, &baseline, nullptr, nullptr);
            } else {
                pass(one_batch, a_batch, nullptr, &baseline, &d_batch);
            }
            pass(one_token, a_token, nullptr, &baseline, &d_token, swa_full);
            pass(mixed, a_mixed, nullptr, &baseline, &d_mixed);
            judge(r, f16, (std::string(store) + ", one batch vs reference:").c_str(), a_batch, limits);
            judge(r, f16, (std::string(store) + ", token by token vs ref.:").c_str(), a_token, limits);
            judge(r, f16, (std::string(store) + ", uneven batches vs ref.:").c_str(), a_mixed, limits);
            if (!f16) {
                r.check(std::max({d_batch, d_token, d_mixed}) <= limits.f32_max_abs,
                        "%s, the three ways against the full-size one batch: max |dlogit| %.3g, %.3g, %.3g (limit %.0e), whole vocabulary",
                        store, d_batch, d_token, d_mixed, limits.f32_max_abs);
            }
        }

        // --- The deletion token and the special ids, one by one, at every position ---

        {
            double sum_p_bs = 0.0, max_log_bs = 0.0;
            std::vector<double> worst(specials.size(), 0.0);
            std::vector<double> log_p, log_q;
            size_t n = 0;
            for (size_t k = 0; k < ref.seqs.size(); k++) {
                const sequence & s = ref.seqs[k];
                for (size_t pos = 0; pos < s.ids.size(); pos++) {
                    const float * want = ref.full_row(s, pos);
                    const float * got = baseline[k].row(pos);
                    for (size_t j = 0; j < specials.size(); j++) {
                        worst[j] = std::max(worst[j], (double) std::fabs(got[specials[j]] - want[specials[j]]));
                    }
                    log_softmax(want, n_vocab, log_p);
                    log_softmax(got, n_vocab, log_q);
                    sum_p_bs += std::exp(log_p[bs]);
                    max_log_bs = std::max(max_log_bs, std::fabs(log_p[bs] - log_q[bs]));
                    n++;
                }
            }
            const double worst_all = *std::max_element(worst.begin(), worst.end());
            const char * format = "logits of the ids %d, %d, %d and of the deletion token %d against the reference at %zu positions: max |dlogit| %.3g, %.3g, %.3g, %.3g%s";
            if (f16) {
                r.note(format, specials[0], specials[1], specials[2], specials[3], n, worst[0], worst[1], worst[2], worst[3], "");
            } else {
                r.check(worst_all <= limits.f32_max_abs, format, specials[0], specials[1], specials[2], specials[3], n, worst[0], worst[1], worst[2], worst[3], " (limit 5e-03)");
            }
            // The 16-bit limit is this test's own, the harness has none for a single token: the
            // probability of a deletion may be off by a twentieth of itself, nowhere more.
            r.check(max_log_bs <= (f16 ? 0.05 : 5e-3),
                    "the deletion token's log-probability: max difference %.3g (limit %.0e); the reference gives it %.2f%% on average over these texts",
                    max_log_bs, f16 ? 0.05 : 5e-3, 100.0 * sum_p_bs / (double) n);
        }

        // --- Micro-batch sizes: however llama.cpp cuts a batch, the logits are the same ---

        {
            double d_ubatch = 0.0;
            agreement a_ubatch;
            // 512 is the micro-batch of every pass above. The pruned store's size depends on the
            // micro-batch (the window plus one micro-batch, in steps of 256 cells), so 7 and 32
            // run with it as well.
            const uint32_t sizes[] = {1, 2, 7, 32};
            for (const bool swa_full : {true, false}) {
                ev.swa_full = swa_full;
                for (const uint32_t n_ubatch : sizes) {
                    if (!swa_full && n_ubatch < 7) {
                        continue;
                    }
                    ev.open(n_ubatch);
                    pass(one_batch, a_ubatch, nullptr, &baseline, &d_ubatch, n_ubatch >= 7);
                }
            }
            judge(r, f16, "n_ubatch 1, 2, 7, 32 vs reference:", a_ubatch, limits);
            if (!f16) {
                r.check(d_ubatch <= limits.f32_max_abs, "n_ubatch 1, 2, 7, 32 (7 and 32 also pruned) vs 512: max |dlogit| %.3g (limit %.0e), whole vocabulary",
                        d_ubatch, limits.f32_max_abs);
            }
        }

        // --- Greedy continuation as an application runs it: the prompt in one batch, then one token per call ---

        {
            size_t n_same = 0, n_prompt_tokens = 0, n_reply_tokens = 0;
            double seconds_reply = 0.0;
            std::string first_divergence = "none";
            llama_sampler * sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
            llama_sampler_chain_add(sampler, llama_sampler_init_greedy());
            ev.swa_full = true;
            ev.open(k_n_batch);
            for (size_t k = 0; k < n_short; k++) {
                const sequence & s = ref.seqs[k];
                std::vector<llama_token> ids(s.ids.begin(), s.ids.begin() + s.n_prompt);
                ev.clear();
                if (ev.process(ids, 0, ids.size()) != 0) {
                    die("cannot decode a prompt");
                }
                // The model has no token that ends a reply: like the reference, the loop runs for
                // the reference's number of tokens. It would stop at the end-of-text token, which
                // the generator made sure no reference reply contains.
                std::vector<llama_token> reply;
                while (reply.size() < s.greedy.size()) {
                    const llama_token token = llama_sampler_sample(sampler, ev.context(), -1);
                    if (llama_vocab_is_eog(vocab, token)) {
                        break;
                    }
                    reply.push_back(token);
                    ids.push_back(token);
                    if (reply.size() == s.greedy.size()) {
                        break;
                    }
                    const int64_t t0 = llama_time_us();
                    if (ev.process(ids, ids.size() - 1, ids.size()) != 0) {
                        die("cannot decode a generated token");
                    }
                    seconds_reply += (double) (llama_time_us() - t0) / 1e6;
                    n_reply_tokens++;
                }
                if (reply == s.greedy) {
                    n_same++;
                } else if (first_divergence == "none") {
                    first_divergence = "sequence " + std::to_string(k) + ", token " + std::to_string(common_prefix(reply, s.greedy));
                }
                n_prompt_tokens += s.n_prompt;
            }
            llama_sampler_free(sampler);
            if (f16) {
                // Reported, not gated: the 16-bit limits are statistical (top-1 above), and one
                // near-tie flipping would be within them.
                r.note("greedy continuations identical to the reference: %zu of %zu, first divergence: %s", n_same, n_short, first_divergence.c_str());
            } else {
                r.check(n_same == n_short && n_short > 0, "greedy continuations identical to the reference's full-prefix recomputation: %zu of %zu, first divergence: %s",
                        n_same, n_short, first_divergence.c_str());
            }
            // A longer prompt for the speed of a batch: the short prompts are a dozen tokens each.
            const sequence & l = ref.seqs[i_longest];
            ev.clear();
            const int64_t t0 = llama_time_us();
            ev.decode(l.ids, 0, l.ids.size(), one_batch);
            const double seconds_long = (double) (llama_time_us() - t0) / 1e6;
            r.note("speed: generation %.1f tokens/s (%zu tokens, one per call, after prompts of %zu tokens in all); a prompt of %zu tokens in batches of %u: %.0f tokens/s",
                   (double) n_reply_tokens / seconds_reply, n_reply_tokens, n_prompt_tokens, l.ids.size(), k_n_batch, (double) l.ids.size() / seconds_long);
        }

        // The sequences the memory checks are made of: the longest and the second longest.
        size_t i_other = i_longest == 0 ? 1 : 0;
        for (size_t k = 0; k < ref.seqs.size(); k++) {
            if (k != i_longest && ref.seqs[k].ids.size() > ref.seqs[i_other].ids.size()) {
                i_other = k;
            }
        }
        const std::vector<llama_token> & head = ref.seqs[i_longest].ids; // 1,500 tokens
        const std::vector<llama_token> & other = ref.seqs[i_other].ids;  // 600 tokens
        const size_t n_head = head.size();
        if (other.size() < 2 * (size_t) n_swa || n_head < 5 * (size_t) n_swa) {
            die("the long reference sequences are too short for the memory checks");
        }
        // What is decoded after every cut: a stretch of the other text, 96 tokens from its middle.
        const std::vector<llama_token> tail(other.begin() + n_swa, other.begin() + n_swa + 96);
        llama_memory_t mem = nullptr;

        // The logits of spliced[cut, ...) from an empty memory: what a cut and a new tail must reproduce.
        const auto fresh = [&](const std::vector<llama_token> & spliced, size_t cut) {
            ev_fresh.clear();
            ev_fresh.decode(spliced, 0, cut, one_batch);
            return ev_fresh.decode(spliced, cut, spliced.size(), one_batch);
        };

        // --- Rewind with the full-size store: any cut, however far back, equals a fresh evaluation ---

        {
            ev.swa_full = true;
            ev.open(k_n_batch);
            mem = llama_get_memory(ev.context());
            double d_cut = 0.0, d_control = std::numeric_limits<double>::infinity();
            agreement a_cut;
            bool all_ok = true;
            size_t n_far = 0, n_controls = 0;
            uint32_t state = 12345;
            const auto next = [&](uint32_t bound) {
                state = state * 1664525u + 1013904223u;
                return (state >> 8) % bound;
            };
            // Arbitrary positions, among them cuts that take away many times the window. They are
            // made one after the other on the same memory, the longest first, the way a text is
            // cut back again and again: each cut also removes the tail the cut before it left.
            std::vector<size_t> cuts;
            while (cuts.size() < 20) {
                const size_t cut = 1 + next((uint32_t) (n_head - 2));
                if (std::find(cuts.begin(), cuts.end(), cut) == cuts.end()) {
                    cuts.push_back(cut);
                }
            }
            std::sort(cuts.rbegin(), cuts.rend());
            ev.decode(head, 0, n_head, one_batch);
            for (size_t c = 0; c < cuts.size(); c++) {
                const size_t cut = cuts[c];
                const std::vector<llama_token> spliced = splice(head, cut, tail);
                all_ok = cut_is_safe(ev.context(), cut, n_swa) && ev.rewind(cut) && llama_memory_seq_pos_max(mem, 0) == (llama_pos) cut - 1 && all_ok;
                const logit_rows after = ev.decode(spliced, cut, spliced.size(), one_batch);
                const logit_rows expected = fresh(spliced, cut);
                d_cut = std::max(d_cut, max_abs_between(after, expected, cut, spliced.size()));
                compare_rows(expected, after, cut, spliced.size(), limits, a_cut);
                n_far += n_head - cut > 2 * (size_t) n_swa && cut > (size_t) n_swa ? 1 : 0;

                // The control, for every fifth cut: the same tail after a wrong beginning of the
                // same length (the right one read backwards). If this did not move the logits,
                // the comparison above would prove nothing about what the memory holds.
                if (c % 5 == 0) {
                    const std::vector<llama_token> backwards(head.rend() - (long) cut, head.rend());
                    ev_fresh.clear();
                    ev_fresh.decode(backwards, 0, cut, one_batch);
                    const logit_rows wrong = ev_fresh.decode(spliced, cut, spliced.size(), one_batch);
                    d_control = std::min(d_control, max_abs_between(wrong, expected, cut, spliced.size()));
                    n_controls++;
                }
            }
            r.check(all_ok && n_far > 0, "full-size store: llama_memory_seq_rm accepted %zu successive cuts of a memory of %zu tokens (from %zu down to %zu kept; %zu of them beyond the window on both sides), and the pos_min test allowed each",
                    cuts.size(), n_head, cuts.front(), cuts.back(), n_far);
            same(r, f16, limits, "full-size store, cuts vs fresh run:", d_cut, a_cut);
            r.check(d_control >= 0.5 && std::isfinite(d_control), "control, the wrong beginning: max |dlogit| at least %.3g in each of %zu cases (must exceed 0.5)", d_control, n_controls);
        }

        // --- Rewind with the pruned store: what is accepted, what is right, and how to tell ---

        {
            ev.swa_full = false;
            ev.open(k_n_batch);
            mem = llama_get_memory(ev.context());
            ev.clear();
            ev.decode(head, 0, n_head, one_batch);
            const llama_pos pos_min = llama_memory_seq_pos_min(mem, 0);
            const size_t first_safe = (size_t) pos_min + (size_t) n_swa - 1; // the shortest length the store can be cut to
            r.check(pos_min > 0 && first_safe < n_head, "pruned store: after %zu tokens the local layers hold positions %d to %d, so a cut is safe down to %zu tokens",
                    n_head, pos_min, llama_memory_seq_pos_max(mem, 0), first_safe);

            // Cuts on both sides of that length. Each starts from the same full memory.
            const size_t cuts[] = {n_head - 1, first_safe + 40, first_safe, first_safe - 1, first_safe - (size_t) n_swa / 2, (size_t) n_swa + 14, 40};
            size_t n_accepted = 0, n_safe = 0, n_unsafe = 0, n_right_as_told = 0, n_wrong_as_told = 0;
            double d_safe = 0.0, d_unsafe = std::numeric_limits<double>::infinity(), d_one_short = 0.0;
            agreement a_safe;
            for (const size_t cut : cuts) {
                const std::vector<llama_token> spliced = splice(head, cut, tail);
                ev.clear();
                ev.decode(head, 0, n_head, one_batch);
                const bool safe = cut_is_safe(ev.context(), cut, n_swa);
                n_accepted += ev.rewind(cut) ? 1 : 0;
                const logit_rows after = ev.decode(spliced, cut, spliced.size(), one_batch);
                const logit_rows expected = fresh(spliced, cut);
                const double d = max_abs_between(after, expected, cut, spliced.size());
                if (safe) {
                    n_safe++;
                    d_safe = std::max(d_safe, d);
                    compare_rows(expected, after, cut, spliced.size(), limits, a_safe);
                    n_right_as_told += f16 || d <= limits.f32_max_abs ? 1 : 0;
                } else if (cut == first_safe - 1) {
                    // One key short of the window: wrong, but by one distant key out of 256.
                    d_one_short = d;
                    n_unsafe++;
                    n_wrong_as_told++;
                } else {
                    n_unsafe++;
                    d_unsafe = std::min(d_unsafe, d);
                    n_wrong_as_told += d >= 0.5 ? 1 : 0;
                }
            }
            r.check(n_accepted == std::size(cuts), "pruned store: llama_memory_seq_rm accepted %zu of %zu cuts, the %zu that the pos_min test allows and the %zu that it does not",
                    n_accepted, std::size(cuts), n_safe, n_unsafe);
            same(r, f16, limits, "pruned store, allowed cuts vs fresh:", d_safe, a_safe);
            r.check(n_safe >= 3 && n_unsafe >= 4 && n_right_as_told == n_safe && n_wrong_as_told == n_unsafe && d_unsafe >= 0.5 && std::isfinite(d_unsafe),
                    "control, cuts the pos_min test forbids: accepted, and wrong by max |dlogit| at least %.3g (must exceed 0.5); one key short of the window: %.3g",
                    d_unsafe, d_one_short);

            // The discipline: a partial state saved at a boundary, restored before the cut. The
            // state is taken where the window is full, the memory then goes on to the end.
            const size_t saved_at = 2 * (size_t) n_swa + 38;
            ev.clear();
            ev.decode(head, 0, saved_at, one_batch);
            const blob saved = checkpoint(ev.context());
            const size_t n_layer = (size_t) llama_model_n_layer(model.get());
            const size_t n_local = count_local_layers(gguf, arch);
            const size_t n_embd = (size_t) llama_model_n_embd(model.get());
            const size_t cell = n_local * 2 * n_embd * (f16 ? 2 : 4);
            r.check(n_local > 0 && n_local < n_layer && saved.size() >= (size_t) n_swa * cell && saved.size() < (size_t) n_swa * cell + 65536,
                    "a checkpoint at %zu tokens is %zu bytes: %d cells x %zu local layers x (K + V) x %zu values in %s, and %zu bytes of framing",
                    saved_at, saved.size(), n_swa, n_local, n_embd, f16 ? "F16" : "F32", saved.size() - (size_t) n_swa * cell);

            double d_restored = 0.0;
            agreement a_restored;
            bool all_ok = true, needed = true;
            const size_t later[] = {saved_at, saved_at + 3, saved_at + 70};
            for (const size_t cut : later) {
                const std::vector<llama_token> spliced = splice(head, cut, tail);
                ev.clear();
                ev.decode(head, 0, n_head, one_batch);
                needed = needed && !cut_is_safe(ev.context(), cut, n_swa);
                // state first, then the cut at the state's length, then forward again to the wanted length
                all_ok = restore(ev.context(), saved) && ev.rewind(saved_at) && cut_is_safe(ev.context(), saved_at, n_swa) && all_ok;
                if (cut > saved_at) {
                    ev.decode(spliced, saved_at, cut, one_batch);
                }
                const logit_rows after = ev.decode(spliced, cut, spliced.size(), one_batch);
                const logit_rows expected = fresh(spliced, cut);
                d_restored = std::max(d_restored, max_abs_between(after, expected, cut, spliced.size()));
                compare_rows(expected, after, cut, spliced.size(), limits, a_restored);
            }
            r.check(all_ok && needed, "pruned store: %zu cuts that the pos_min test forbids were made through the checkpoint instead (restore, cut at its %zu tokens, decode forward)",
                    std::size(later), saved_at);
            same(r, f16, limits, "through a checkpoint vs fresh run:", d_restored, a_restored);

            // No checkpoint early enough: the whole sequence can always go.
            ev.clear();
            ev.decode(head, 0, n_head, one_batch);
            const bool whole = llama_memory_seq_rm(mem, 0, -1, -1);
            r.check(whole && llama_memory_seq_pos_max(mem, 0) == -1 && llama_memory_seq_pos_min(mem, 0) == -1, "the removal of the whole sequence succeeds and leaves nothing");
        }

        // --- After a checkpoint was restored the local store holds that window only, whatever its size ---

        {
            ev.swa_full = true;
            ev.open(k_n_batch);
            mem = llama_get_memory(ev.context());
            const size_t saved_at = 3 * (size_t) n_swa + 132;
            const size_t earlier = saved_at - (size_t) n_swa / 2;
            ev.clear();
            ev.decode(head, 0, saved_at, one_batch);
            const blob saved = checkpoint(ev.context());
            ev.decode(head, saved_at, n_head, one_batch);
            const bool safe_before = cut_is_safe(ev.context(), earlier, n_swa);
            const bool restored = restore(ev.context(), saved) && ev.rewind(saved_at);
            const llama_pos pos_min = llama_memory_seq_pos_min(mem, 0);
            const bool safe_after = cut_is_safe(ev.context(), earlier, n_swa);
            const std::vector<llama_token> spliced = splice(head, earlier, tail);
            const bool accepted = ev.rewind(earlier);
            const logit_rows after = ev.decode(spliced, earlier, spliced.size(), one_batch);
            const double d = max_abs_between(after, fresh(spliced, earlier), earlier, spliced.size());
            r.check(safe_before && restored && !safe_after && accepted && pos_min == (llama_pos) (saved_at - (size_t) n_swa) && d >= 0.5,
                    "full-size store: a cut to %zu tokens was safe; after a checkpoint of %zu tokens was restored the store begins at position %d, the pos_min test forbids the same cut, llama_memory_seq_rm accepts it, and it is wrong by %.3g (must exceed 0.5)",
                    earlier, saved_at, pos_min, d);
        }

        // --- Full state save and restore into a new context ---

        // A full sequence state holds every cell of the global layers and, of the local layers,
        // the window of the last position: enough to go on, in a new context too. The restored
        // cells of the local layers land in other slots than they had, so the sums of an
        // attention run in another order and the continuation is equal within the limit, not
        // bit for bit. The local store then begins where that window begins, like after a checkpoint.
        for (const bool swa_full : {true, false}) {
            ev.swa_full = swa_full;
            ev.open(k_n_batch);
            const size_t split = 2 * (size_t) n_swa + 188;
            const size_t n_next = 32;
            ev.decode(head, 0, split, one_batch);
            blob state(llama_state_seq_get_size(ev.context(), 0));
            const size_t n_state = llama_state_seq_get_data(ev.context(), state.data(), state.size(), 0);
            const logit_rows before = ev.decode(head, split, split + n_next, one_token);

            ev.open(k_n_batch);
            const size_t n_read = llama_state_seq_set_data(ev.context(), state.data(), n_state, 0);
            const llama_pos pos_min = llama_memory_seq_pos_min(llama_get_memory(ev.context()), 0);
            const llama_pos pos_max = llama_memory_seq_pos_max(llama_get_memory(ev.context()), 0);
            const logit_rows after = ev.decode(head, split, split + n_next, one_token);
            agreement a_state;
            compare_rows(before, after, split, split + n_next, limits, a_state);
            const size_t n_global = (size_t) llama_model_n_layer(model.get()) - count_local_layers(gguf, arch);
            r.check(n_state > 0 && n_read == n_state && pos_max == (llama_pos) split - 1 && pos_min == (llama_pos) (split - (size_t) n_swa),
                    "%s: a full sequence state of %zu tokens is %zu bytes (%zu cells of %zu global layers, %d cells of the local layers) and restores into a new context, whose local store then begins at position %d",
                    swa_full ? "full-size store" : "pruned store", split, n_state, split, n_global, n_swa, pos_min);
            same(r, f16, limits, "the next tokens after the restore:", max_abs_between(before, after, split, split + n_next), a_state);
        }

        // --- The context: at and beyond the length the model was trained on ---

        if (!context_path.empty()) {
            const reference context = load_reference(context_path);
            if (context.n_vocab != ref.n_vocab || context.seqs.size() != 1 || !context.full_everywhere()) {
                die("the context reference must be one sequence with the whole logit vector everywhere");
            }
            const sequence & s = context.seqs[0];
            const size_t n = s.ids.size();
            const size_t n_trained = (size_t) std::atoi(meta(model.get(), arch + ".trained_sequence_length").c_str());
            r.check(n > n_trained && n <= (size_t) llama_model_n_ctx_train(model.get()),
                    "context test: one sequence of %zu tokens; the model was trained on %zu and its positions reach %d", n, n_trained, llama_model_n_ctx_train(model.get()));

            ev.n_ctx = (uint32_t) n;
            ev.swa_full = true;
            ev.open(k_n_batch);
            mem = llama_get_memory(ev.context());
            const int64_t t0 = llama_time_us();
            const logit_rows rows = ev.decode(s.ids, 0, n, one_batch);
            const double seconds = (double) (llama_time_us() - t0) / 1e6;
            // In four stretches, so that a drift with the position shows.
            agreement a_all;
            for (size_t first = 0; first < n; first += (n + 3) / 4) {
                const size_t last = std::min(n, first + (n + 3) / 4);
                agreement a;
                compare(context, s, rows, first, last, limits, a);
                compare(context, s, rows, first, last, limits, a_all);
                if (f16) {
                    r.note("  positions %5zu to %5zu: mean KL %.3g, max KL %.3g, top-1 %.2f%%, max |dlogit| %.3g", first, last - 1, a.mean_kl(), a.max_kl, 100.0 * a.top1(), a.max_abs);
                } else {
                    r.note("  positions %5zu to %5zu: max |dlogit| %.3g over the whole vocabulary", first, last - 1, a.max_abs);
                }
            }
            judge(r, f16, "the whole context vs reference:", a_all, limits);
            r.note("  %zu tokens in batches of %u: %.0f tokens/s", n, k_n_batch, (double) n / seconds);

            // The memory is full: one more token is refused, and nothing is lost by the refusal.
            llama_log_set([](enum ggml_log_level, const char *, void *) {}, nullptr);
            std::vector<llama_token> beyond(s.ids);
            beyond.push_back(s.ids[0]);
            const int32_t refused = ev.process(beyond, n, n + 1);
            quiet_logs();
            r.check(refused == 1 && llama_memory_seq_pos_max(mem, 0) == (llama_pos) n - 1,
                    "a token beyond the context is refused (llama_process returned %d) and the memory still holds %d tokens", refused, llama_memory_seq_pos_max(mem, 0) + 1);

            // Token by token near the end, after a cut: the positions furthest from anything the model was trained on.
            const size_t back = n - 128;
            const bool cut = cut_is_safe(ev.context(), back, n_swa) && ev.rewind(back);
            const logit_rows one_by_one = ev.decode(s.ids, back, n, one_token);
            agreement a_one;
            compare_rows(rows, one_by_one, back, n, limits, a_one);
            r.check(cut, "the memory is cut back to %zu tokens and the last %zu are decoded again one at a time", back, n - back);
            same(r, f16, limits, "one at a time vs the batches:", max_abs_between(rows, one_by_one, back, n), a_one);

            // The pruned store over the same length.
            ev.swa_full = false;
            ev.open(k_n_batch);
            const logit_rows pruned = ev.decode(s.ids, 0, n, one_batch);
            agreement a_pruned;
            compare_rows(rows, pruned, 0, n, limits, a_pruned);
            same(r, f16, limits, "pruned store vs full-size store:", max_abs_between(rows, pruned, 0, n), a_pruned);
            ev.n_ctx = 2048;
        }

        // --- Sampling as the reference script does it: how often the deletion token and the specials come ---

        if (n_sample > 0) {
            const int n_per_run = 80; // the script's default length
            const int n_runs = std::max(1, n_sample / n_per_run);
            std::vector<size_t> counts(specials.size(), 0);
            size_t n_tokens = 0, n_runs_with_bs = 0;
            ev.swa_full = true;
            ev.open(k_n_batch);
            const int64_t t0 = llama_time_us();
            for (int run = 0; run < n_runs; run++) {
                const sequence & s = ref.seqs[(size_t) run % n_short];
                std::vector<llama_token> ids(s.ids.begin(), s.ids.begin() + s.n_prompt);
                // temperature 0.9 over the 100 best, as infer.py: the order of the two steps does not change the result
                llama_sampler * sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
                llama_sampler_chain_add(sampler, llama_sampler_init_top_k(100));
                llama_sampler_chain_add(sampler, llama_sampler_init_temp(0.9f));
                llama_sampler_chain_add(sampler, llama_sampler_init_dist(1000u + (uint32_t) run));
                ev.clear();
                if (ev.process(ids, 0, ids.size()) != 0) {
                    die("cannot decode a prompt");
                }
                size_t n_bs = 0;
                for (int i = 0; i < n_per_run; i++) {
                    // Like the script, nothing ends a run early: every sampled token is fed back.
                    const llama_token token = llama_sampler_sample(sampler, ev.context(), -1);
                    for (size_t j = 0; j < specials.size(); j++) {
                        counts[j] += token == specials[j] ? 1 : 0;
                    }
                    n_bs += token == bs ? 1 : 0;
                    n_tokens++;
                    ids.push_back(token);
                    if (ev.process(ids, ids.size() - 1, ids.size()) != 0) {
                        die("cannot decode a sampled token");
                    }
                }
                n_runs_with_bs += n_bs > 0 ? 1 : 0;
                llama_sampler_free(sampler);
            }
            const double seconds = (double) (llama_time_us() - t0) / 1e6;
            const double rate_bs = 100.0 * (double) counts.back() / (double) n_tokens;
            // The model card reports 5.4% for these settings over 14 runs; a few thousand tokens
            // of other prompts cannot pin that to a decimal, only show that it is the same habit.
            r.check(rate_bs >= 2.0 && rate_bs <= 9.0,
                    "sampled %zu tokens (temperature 0.9, top-k 100, %d runs of %d): the deletion token %.2f%% of them (%zu, in %zu of %d runs; the model card says 5.4%%; sanity range 2%% to 9%%)",
                    n_tokens, n_runs, n_per_run, rate_bs, counts.back(), n_runs_with_bs, n_runs);
            r.note("  the ids %d, %d and %d were sampled %zu, %zu and %zu times (%.3f%%, %.3f%%, %.3f%%); %.0f tokens/s including the prompts",
                   specials[0], specials[1], specials[2], counts[0], counts[1], counts[2], 100.0 * (double) counts[0] / (double) n_tokens,
                   100.0 * (double) counts[1] / (double) n_tokens, 100.0 * (double) counts[2] / (double) n_tokens, (double) n_tokens / seconds);
        }
    }
    llama_backend_free();
    return r.finish();
}
