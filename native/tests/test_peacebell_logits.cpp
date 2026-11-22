// Validates a converted peacebell GGUF against logits of the PyTorch reference model.
//
//   test_peacebell_logits --gguf FILE --ref FILE --precision f32|f16 [--max-new-tokens N] [--top1-margin X]
//
// The reference file (native/tests/refgen/formats.py) holds token sequences made of a ChatML
// prompt, the reference's greedy reply and the end marker, with the reference's logits.
//
// --precision f32 is the gate for the port itself. It runs the F32 file with an F32 KV cache
// and without flash attention, so that the only differences left are summation order and the
// float32 RoPE angle schedule, and requires every way of feeding the same tokens (one batch,
// token by token, uneven batches, every micro-batch size, after a rewind) to reproduce the
// reference logits within 5e-3 and the greedy reply exactly.
//
// --precision f16 is the gate for the file that ships. It runs the F16 file as the app will
// (F16 KV cache, default attention path) and requires the distributions to stay within the KL
// and top-1 limits measured for the 16-bit format, for the same ways of feeding the tokens.
// It needs a reference with the whole logit vector at every position.

#include "support/test_support.h"

#include <algorithm>
#include <cmath>
#include <cstdarg>
#include <limits>
#include <numeric>

namespace {

using pp_test::report;

// Limits from the validation plan of the design this port implements.
constexpr double k_f32_max_abs       = 5e-3; // logits against the reference and against each other
constexpr double k_f32_rank_gap      = 1e-2; // a reference rank is compared only when this far from its neighbours
constexpr double k_f16_mean_kl       = 1e-5;
constexpr double k_f16_max_kl        = 5e-3;
constexpr double k_f16_top1          = 0.995;
constexpr uint32_t k_n_ctx           = 1024;
constexpr uint32_t k_n_batch         = 512;

struct sequence {
    std::vector<llama_token> ids;
    uint32_t n_prompt = 0;
    std::vector<llama_token> greedy;
    std::vector<int32_t> top_idx; // [n_tokens * top_k]
    std::vector<float>   top_val;
    std::vector<int32_t> full_pos;
    std::vector<float>   full;    // [n_full * n_vocab]
};

struct reference {
    uint32_t n_vocab = 0;
    uint32_t top_k = 0;
    std::vector<sequence> seqs;

    // The whole logit vector of a position, or nullptr when the file does not carry it.
    const float * full_row(const sequence & s, size_t pos) const {
        for (size_t i = 0; i < s.full_pos.size(); i++) {
            if ((size_t) s.full_pos[i] == pos) {
                return s.full.data() + i * n_vocab;
            }
        }
        return nullptr;
    }

    bool full_everywhere() const {
        return std::all_of(seqs.begin(), seqs.end(), [](const sequence & s) { return s.full_pos.size() == s.ids.size(); });
    }
};

reference load_reference(const std::string & path) {
    pp_test::reader in(path);
    in.expect_magic("PPLOGIT1");
    reference ref;
    ref.n_vocab = in.u32();
    const uint32_t n_seq = in.u32();
    ref.top_k = in.u32();
    for (uint32_t k = 0; k < n_seq; k++) {
        sequence s;
        const uint32_t n = in.u32();
        s.n_prompt = in.u32();
        const uint32_t n_greedy = in.u32();
        const uint32_t n_full = in.u32();
        s.ids      = in.array<llama_token>(n);
        s.greedy   = in.array<llama_token>(n_greedy);
        s.top_idx  = in.array<int32_t>((size_t) n * ref.top_k);
        s.top_val  = in.array<float>((size_t) n * ref.top_k);
        s.full_pos = in.array<int32_t>(n_full);
        s.full     = in.array<float>((size_t) n_full * ref.n_vocab);
        ref.seqs.push_back(std::move(s));
    }
    if (!in.at_end()) {
        pp_test::die(path + " has trailing bytes");
    }
    return ref;
}

// Logits of consecutive positions of one sequence, one row per position.
struct logit_rows {
    size_t first_pos = 0;
    size_t n_vocab = 0;
    std::vector<float> data;

    size_t n_rows() const { return n_vocab == 0 ? 0 : data.size() / n_vocab; }
    const float * row(size_t pos) const { return data.data() + (pos - first_pos) * n_vocab; }
};

// One model with one context at a time, fed through the same calls the app will use.
class evaluator {
  public:
    evaluator(llama_model * model, bool f16) : model(model), f16(f16) {
        n_vocab = llama_vocab_n_tokens(llama_model_get_vocab(model));
    }

    ~evaluator() { close(); }

    void open(uint32_t n_ubatch) {
        close();
        llama_context_params params = llama_context_default_params();
        params.n_ctx           = k_n_ctx;
        params.n_batch         = k_n_batch;
        params.n_ubatch        = n_ubatch;
        params.n_threads       = pp_test::n_threads();
        params.n_threads_batch = pp_test::n_threads();
        params.no_perf         = true;
        if (!f16) {
            // The validation configuration: nothing but the weights' own precision in the way.
            params.type_k          = GGML_TYPE_F32;
            params.type_v          = GGML_TYPE_F32;
            params.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_DISABLED;
        }
        ctx.reset(llama_init_from_model(model, params));
        if (!ctx) {
            pp_test::die("cannot create a context");
        }
        batch = llama_batch_ext_init(ctx.get());
    }

    void clear() { llama_memory_clear(llama_get_memory(ctx.get()), true); }

    bool rewind(size_t n_keep) { return llama_memory_seq_rm(llama_get_memory(ctx.get()), 0, (llama_pos) n_keep, -1); }

    // Feeds ids[begin, end) at positions begin.., cut into the given chunk sizes (the last size
    // repeats), and returns the logits of every one of those positions.
    logit_rows decode(const std::vector<llama_token> & ids, size_t begin, size_t end, const std::vector<size_t> & chunks) {
        logit_rows out;
        out.first_pos = begin;
        out.n_vocab = (size_t) n_vocab;
        out.data.reserve((end - begin) * out.n_vocab);
        size_t pos = begin;
        for (size_t c = 0; pos < end; c++) {
            const size_t want = chunks[std::min(c, chunks.size() - 1)];
            const size_t n = std::min({want, end - pos, (size_t) k_n_batch});
            llama_batch_ext_clear(batch);
            for (size_t i = 0; i < n; i++) {
                const int32_t idx = llama_batch_ext_add_token(batch, 0, ids[pos + i]);
                const llama_pos p = (llama_pos) (pos + i);
                if (idx < 0 || !llama_batch_ext_set_pos(batch, idx, &p) || !llama_batch_ext_set_output_logits(batch, idx, true)) {
                    pp_test::die("cannot build a batch");
                }
            }
            const int32_t rc = llama_process(ctx.get(), LLAMA_PROCESS_TYPE_DECODE, batch);
            if (rc != 0) {
                pp_test::die("llama_process returned " + std::to_string(rc));
            }
            for (size_t i = 0; i < n; i++) {
                const float * logits = llama_get_logits_ith(ctx.get(), (int32_t) i);
                out.data.insert(out.data.end(), logits, logits + n_vocab);
            }
            pos += n;
        }
        return out;
    }

    llama_context * context() const { return ctx.get(); }
    int32_t vocab_size() const { return n_vocab; }

  private:
    void close() {
        if (batch != nullptr) {
            llama_batch_ext_free(batch);
            batch = nullptr;
        }
        ctx.reset();
    }

    llama_model * model;
    bool f16;
    int32_t n_vocab = 0;
    pp_test::context_ptr ctx;
    llama_batch_ext * batch = nullptr;
};

// Running comparison of port logits with reference logits over many positions.
struct agreement {
    double max_abs = 0.0;      // over every stored reference value
    size_t n_values = 0;
    size_t n_rank_checked = 0; // reference ranks far enough from their neighbours to be compared
    size_t n_rank_wrong = 0;
    // The three below only where the reference carries the whole vector.
    double sum_kl = 0.0;
    double max_kl = 0.0;
    size_t n_top1_same = 0;
    size_t n_top1 = 0;         // positions where the reference's first choice is clear enough to compare
    size_t n_full = 0;

    double mean_kl() const { return n_full == 0 ? 0.0 : sum_kl / (double) n_full; }
    double top1() const { return n_top1 == 0 ? 1.0 : (double) n_top1_same / (double) n_top1; }
};

// Positions whose two best reference logits are closer than this are left out of the top-1
// count. Zero for trained checkpoints, where the plain count is the criterion. A random
// checkpoint has nearly flat distributions with ties far below the 16-bit rounding error, and
// there a flipped tie says nothing about the port.
double g_top1_margin = 0.0;

// log-softmax in double: the KL limits are far below float32 resolution of the logits.
void log_softmax(const float * logits, size_t n, std::vector<double> & out) {
    double max = -std::numeric_limits<double>::infinity();
    for (size_t i = 0; i < n; i++) {
        max = std::max(max, (double) logits[i]);
    }
    double sum = 0.0;
    for (size_t i = 0; i < n; i++) {
        sum += std::exp((double) logits[i] - max);
    }
    const double log_z = max + std::log(sum);
    out.resize(n);
    for (size_t i = 0; i < n; i++) {
        out[i] = (double) logits[i] - log_z;
    }
}

// Adds one position where the whole reference vector is known: largest difference, KL and first choice.
void compare_full(const float * full, const float * got, size_t n_vocab, agreement & a) {
    static thread_local std::vector<double> log_p, log_q;
    size_t best_ref = 0, best_got = 0;
    float second_ref = -std::numeric_limits<float>::infinity();
    for (size_t v = 0; v < n_vocab; v++) {
        a.max_abs = std::max(a.max_abs, (double) std::fabs(got[v] - full[v]));
        if (full[v] > full[best_ref]) {
            second_ref = full[best_ref];
            best_ref = v;
        } else if (v != best_ref && full[v] > second_ref) {
            second_ref = full[v];
        }
        if (got[v] > got[best_got]) {
            best_got = v;
        }
    }
    a.n_values += n_vocab;

    // KL(reference || port): what the reference would sample that the port makes less likely.
    log_softmax(full, n_vocab, log_p);
    log_softmax(got, n_vocab, log_q);
    double kl = 0.0;
    for (size_t v = 0; v < n_vocab; v++) {
        kl += std::exp(log_p[v]) * (log_p[v] - log_q[v]);
    }
    kl = std::max(kl, 0.0);
    a.sum_kl += kl;
    a.max_kl = std::max(a.max_kl, kl);
    a.n_full++;

    if ((double) (full[best_ref] - second_ref) >= g_top1_margin) {
        a.n_top1++;
        a.n_top1_same += best_ref == best_got ? 1 : 0;
    }
}

// Adds the positions [first, last) of sequence s, whose port logits are in rows.
void compare(const reference & ref, const sequence & s, const logit_rows & rows, size_t first, size_t last, agreement & a) {
    const size_t n_vocab = ref.n_vocab;
    const size_t top_k = ref.top_k;
    std::vector<int32_t> order(n_vocab);
    for (size_t pos = first; pos < last; pos++) {
        const float * got = rows.row(pos);
        const int32_t * top_idx = s.top_idx.data() + pos * top_k;
        const float *   top_val = s.top_val.data() + pos * top_k;

        for (size_t j = 0; j < top_k; j++) {
            a.max_abs = std::max(a.max_abs, (double) std::fabs(got[top_idx[j]] - top_val[j]));
            a.n_values++;
        }

        // The port's own ranking, compared where the reference ranking is unambiguous: a rank whose
        // logit is within the gap of a neighbour may legitimately swap under a 5e-3 perturbation.
        std::iota(order.begin(), order.end(), 0);
        std::partial_sort(order.begin(), order.begin() + (long) top_k, order.end(), [&](int32_t x, int32_t y) {
            return got[x] > got[y] || (got[x] == got[y] && x < y);
        });
        for (size_t j = 0; j + 1 < top_k; j++) {
            const bool clear_above = j == 0 || top_val[j - 1] - top_val[j] > k_f32_rank_gap;
            const bool clear_below = top_val[j] - top_val[j + 1] > k_f32_rank_gap;
            if (clear_above && clear_below) {
                a.n_rank_checked++;
                if (order[j] != top_idx[j]) {
                    a.n_rank_wrong++;
                }
            }
        }

        if (const float * full = ref.full_row(s, pos)) {
            compare_full(full, got, n_vocab, a);
        }
    }
}

// The same statistics between two runs of the port, the first taken as the reference.
void compare_rows(const logit_rows & expected, const logit_rows & got, size_t first, size_t last, agreement & a) {
    for (size_t pos = first; pos < last; pos++) {
        compare_full(expected.row(pos), got.row(pos), expected.n_vocab, a);
    }
}

double max_abs_between(const logit_rows & x, const logit_rows & y, size_t first, size_t last) {
    double max_abs = 0.0;
    for (size_t pos = first; pos < last; pos++) {
        const float * a = x.row(pos);
        const float * b = y.row(pos);
        for (size_t v = 0; v < x.n_vocab; v++) {
            max_abs = std::max(max_abs, (double) std::fabs(a[v] - b[v]));
        }
    }
    return max_abs;
}

// One verdict line for a way of feeding the tokens, with the limits of the precision under test.
void judge(report & r, bool f16, const char * what, const agreement & a) {
    if (f16) {
        r.check(a.mean_kl() <= k_f16_mean_kl && a.max_kl <= k_f16_max_kl && a.top1() >= k_f16_top1,
                "%-34s mean KL %.3g (limit %.0e), max KL %.3g (limit %.0e), top-1 %.2f%% of %zu (limit %.1f%%), max |dlogit| %.3g, %zu positions",
                what, a.mean_kl(), k_f16_mean_kl, a.max_kl, k_f16_max_kl, 100.0 * a.top1(), a.n_top1, 100.0 * k_f16_top1, a.max_abs, a.n_full);
    } else {
        r.check(a.max_abs <= k_f32_max_abs && a.n_rank_wrong == 0,
                "%-34s max |dlogit| %.3g (limit %.0e) over %zu values, %zu of %zu clear ranks differ",
                what, a.max_abs, k_f32_max_abs, a.n_values, a.n_rank_wrong, a.n_rank_checked);
    }
}

size_t common_prefix(const std::vector<llama_token> & a, const std::vector<llama_token> & b) {
    size_t n = 0;
    while (n < a.size() && n < b.size() && a[n] == b[n]) {
        n++;
    }
    return n;
}

// The sampler the app will run for this family: the tokens the file marks as never to be
// sampled are masked, then the most likely token is taken. extra_bias simulates a caller that
// pushes one of the masked tokens up: the mask must still win.
llama_sampler * make_greedy_sampler(const llama_vocab * vocab, int32_t n_vocab, float extra_bias) {
    int32_t n_suppress = 0;
    const llama_token * suppress = llama_vocab_get_suppress_tokens(vocab, &n_suppress);
    std::vector<llama_logit_bias> boost, mask;
    for (int32_t i = 0; i < n_suppress; i++) {
        boost.push_back({suppress[i], extra_bias});
        mask.push_back({suppress[i], -std::numeric_limits<float>::infinity()});
    }
    llama_sampler * chain = llama_sampler_chain_init(llama_sampler_chain_default_params());
    if (extra_bias != 0.0f) {
        llama_sampler_chain_add(chain, llama_sampler_init_logit_bias(n_vocab, (int32_t) boost.size(), boost.data()));
    }
    llama_sampler_chain_add(chain, llama_sampler_init_logit_bias(n_vocab, (int32_t) mask.size(), mask.data()));
    llama_sampler_chain_add(chain, llama_sampler_init_greedy());
    return chain;
}

struct greedy_result {
    std::vector<llama_token> tokens;
    double seconds_prompt = 0.0;
    double seconds_reply = 0.0;
};

// Decodes the prompt in one batch, then generates greedily one token at a time until stop_token
// or n_max tokens. The stop token is not part of the result and is not fed back.
greedy_result generate(evaluator & ev, const llama_vocab * vocab, const std::vector<llama_token> & prompt,
                       llama_token stop_token, int n_max, float extra_bias) {
    greedy_result result;
    llama_sampler * sampler = make_greedy_sampler(vocab, ev.vocab_size(), extra_bias);
    llama_batch_ext * batch = llama_batch_ext_init(ev.context());
    ev.clear();

    std::vector<llama_token> pending = prompt;
    size_t pos = 0;
    for (int step = 0; step <= n_max; step++) {
        llama_batch_ext_clear(batch);
        for (size_t i = 0; i < pending.size(); i++) {
            const int32_t idx = llama_batch_ext_add_token(batch, 0, pending[i]);
            const llama_pos p = (llama_pos) (pos + i);
            llama_batch_ext_set_pos(batch, idx, &p);
            llama_batch_ext_set_output_logits(batch, idx, i + 1 == pending.size());
        }
        const int64_t t0 = llama_time_us();
        const int32_t rc = llama_process(ev.context(), LLAMA_PROCESS_TYPE_DECODE, batch);
        const double seconds = (double) (llama_time_us() - t0) / 1e6;
        if (rc != 0) {
            pp_test::die("llama_process returned " + std::to_string(rc));
        }
        (step == 0 ? result.seconds_prompt : result.seconds_reply) += seconds;
        pos += pending.size();
        if (step == n_max) {
            break;
        }
        const llama_token token = llama_sampler_sample(sampler, ev.context(), -1);
        if (token == stop_token) {
            break;
        }
        result.tokens.push_back(token);
        pending.assign(1, token);
    }

    llama_batch_ext_free(batch);
    llama_sampler_free(sampler);
    return result;
}

} // namespace

int main(int argc, char ** argv) {
    std::string gguf, ref_path, precision;
    // The cap the reference's greedy replies were generated with: a reply that reached it has no
    // stop token, and the port must be cut at the same length to be comparable.
    int max_new_tokens = 96;
    for (int i = 1; i + 1 < argc; i += 2) {
        const std::string key = argv[i];
        if (key == "--gguf") {
            gguf = argv[i + 1];
        } else if (key == "--ref") {
            ref_path = argv[i + 1];
        } else if (key == "--precision") {
            precision = argv[i + 1];
        } else if (key == "--max-new-tokens") {
            max_new_tokens = std::atoi(argv[i + 1]);
        } else if (key == "--top1-margin") {
            g_top1_margin = std::atof(argv[i + 1]);
        }
    }
    if (gguf.empty() || ref_path.empty() || (precision != "f32" && precision != "f16") || max_new_tokens < 1) {
        std::fprintf(stderr, "usage: %s --gguf FILE --ref FILE --precision f32|f16 [--max-new-tokens N] [--top1-margin X]\n", argv[0]);
        return 2;
    }
    const bool f16 = precision == "f16";

    pp_test::quiet_logs();
    llama_backend_init();
    report r;
    {
        const reference ref = load_reference(ref_path);
        pp_test::model_ptr model = pp_test::load_model(gguf, false);
        const llama_vocab * vocab = llama_model_get_vocab(model.get());
        if ((uint32_t) llama_vocab_n_tokens(vocab) != ref.n_vocab) {
            pp_test::die("the reference was made for a vocabulary of another size");
        }
        if (f16 && !ref.full_everywhere()) {
            pp_test::die("--precision f16 needs a reference with the whole logit vector at every position");
        }
        const std::string arch = pp_test::meta(model.get(), "general.architecture");
        r.check(arch == "peacebell", "architecture is '%s'", arch.c_str());
        r.note("%s, %s weights, %s KV cache, %d thread(s), %zu sequences", gguf.substr(gguf.find_last_of('/') + 1).c_str(),
               precision.c_str(), f16 ? "F16" : "F32", pp_test::n_threads(), ref.seqs.size());

        evaluator ev(model.get(), f16);
        const std::vector<size_t> one_batch = {k_n_batch};
        const std::vector<size_t> one_token = {1};
        // Uneven on purpose: single tokens between larger batches, as a chat produces them.
        const std::vector<size_t> mixed = {3, 1, 1, 8, 2, 1, 17, k_n_batch};

        // One batch, token by token and uneven batches against the reference; and against each
        // other, which catches a cache or position error even where the reference is sparse.
        ev.open(k_n_batch);
        std::vector<logit_rows> baseline;
        agreement a_batch, a_token, a_mixed;
        double d_token = 0.0, d_mixed = 0.0;
        for (const sequence & s : ref.seqs) {
            const size_t n = s.ids.size();
            ev.clear();
            baseline.push_back(ev.decode(s.ids, 0, n, one_batch));
            compare(ref, s, baseline.back(), 0, n, a_batch);

            ev.clear();
            const logit_rows by_token = ev.decode(s.ids, 0, n, one_token);
            compare(ref, s, by_token, 0, n, a_token);
            d_token = std::max(d_token, max_abs_between(baseline.back(), by_token, 0, n));

            ev.clear();
            const logit_rows by_mixed = ev.decode(s.ids, 0, n, mixed);
            compare(ref, s, by_mixed, 0, n, a_mixed);
            d_mixed = std::max(d_mixed, max_abs_between(baseline.back(), by_mixed, 0, n));
        }
        judge(r, f16, "one batch vs reference:", a_batch);
        judge(r, f16, "token by token vs reference:", a_token);
        judge(r, f16, "uneven batches vs reference:", a_mixed);
        if (!f16) {
            r.check(d_token <= k_f32_max_abs, "token by token vs one batch:        max |dlogit| %.3g (limit %.0e), whole vocabulary", d_token, k_f32_max_abs);
            r.check(d_mixed <= k_f32_max_abs, "uneven batches vs one batch:        max |dlogit| %.3g (limit %.0e), whole vocabulary", d_mixed, k_f32_max_abs);
        }

        // Rewind against the reference: decode one conversation, cut the memory back to what it
        // shares with another one, decode the other one's tail.
        {
            agreement a_rewind;
            size_t n_rewinds = 0;
            bool all_removed = true;
            for (size_t i = 0; i < ref.seqs.size(); i++) {
                const sequence & from = ref.seqs[i];
                const sequence & to = ref.seqs[(i + 1) % ref.seqs.size()];
                const size_t keep = common_prefix(from.ids, to.ids);
                if (keep == 0 || keep == to.ids.size()) {
                    continue;
                }
                ev.clear();
                ev.decode(from.ids, 0, from.ids.size(), one_batch);
                all_removed = ev.rewind(keep) && all_removed;
                const logit_rows tail = ev.decode(to.ids, keep, to.ids.size(), one_batch);
                compare(ref, to, tail, keep, to.ids.size(), a_rewind);
                n_rewinds++;
            }
            r.check(all_removed && n_rewinds > 0, "llama_memory_seq_rm succeeded for %zu rewind(s) to a shared prefix", n_rewinds);
            judge(r, f16, "after a rewind vs reference:", a_rewind);
        }

        // Rewind at arbitrary positions: any cut followed by a different tail must equal a fresh
        // evaluation of the spliced sequence. The positions come from a fixed generator.
        {
            double d_splice = 0.0;
            agreement a_splice;
            double d_control = std::numeric_limits<double>::infinity();
            bool all_removed = true;
            uint32_t state = 12345;
            const auto next = [&](uint32_t bound) {
                state = state * 1664525u + 1013904223u;
                return (state >> 8) % bound;
            };
            const int n_cuts = 20;
            for (int c = 0; c < n_cuts; c++) {
                const sequence & head = ref.seqs[next((uint32_t) ref.seqs.size())];
                const sequence & tail = ref.seqs[next((uint32_t) ref.seqs.size())];
                const size_t limit = std::min(head.ids.size(), tail.ids.size()) - 1;
                const size_t cut = 1 + next((uint32_t) (limit - 1));
                std::vector<llama_token> spliced(head.ids.begin(), head.ids.begin() + (long) cut);
                spliced.insert(spliced.end(), tail.ids.begin() + (long) cut, tail.ids.end());

                ev.clear();
                ev.decode(head.ids, 0, head.ids.size(), one_batch);
                all_removed = ev.rewind(cut) && all_removed;
                const logit_rows after_cut = ev.decode(spliced, cut, spliced.size(), one_batch);

                ev.clear();
                const logit_rows fresh = ev.decode(spliced, 0, spliced.size(), one_batch);
                d_splice = std::max(d_splice, max_abs_between(after_cut, fresh, cut, spliced.size()));
                compare_rows(fresh, after_cut, cut, spliced.size(), a_splice);

                // The control: the same tail after the wrong beginning. If this did not move the
                // logits, the comparison above would prove nothing about what the memory holds.
                if (!std::equal(head.ids.begin(), head.ids.begin() + (long) cut, tail.ids.begin())) {
                    ev.clear();
                    ev.decode(tail.ids, 0, cut, one_batch);
                    const logit_rows wrong = ev.decode(spliced, cut, spliced.size(), one_batch);
                    d_control = std::min(d_control, max_abs_between(wrong, fresh, cut, spliced.size()));
                }
            }
            r.check(all_removed, "llama_memory_seq_rm succeeded for %d rewinds at arbitrary positions", n_cuts);
            if (f16) {
                // With an F16 cache the kept cells were rounded from values computed in another batch
                // shape than in the fresh run, so the two differ by rounding and are held to the
                // limits of the 16-bit format rather than to the F32 one.
                judge(r, true, "arbitrary rewinds vs fresh run:", a_splice);
            } else {
                r.check(d_splice <= k_f32_max_abs, "arbitrary rewinds vs fresh run:     max |dlogit| %.3g (limit %.0e), whole vocabulary", d_splice, k_f32_max_abs);
            }
            r.check(d_control >= 0.5 && std::isfinite(d_control),
                    "control, the wrong beginning:       max |dlogit| at least %.3g in every case (must exceed 0.5)", d_control);
        }

        // Greedy generation through the sampler chain, and the speed of it as a sanity figure.
        {
            const std::string end_key = arch + ".chat.end_token_id";
            const std::string end_value = pp_test::meta(model.get(), end_key);
            // A file without chat markers (plain-text ChatML) ends on the end-of-text token instead.
            const llama_token stop = end_value.empty() ? llama_vocab_eos(vocab) : (llama_token) std::atoi(end_value.c_str());
            r.check(llama_vocab_is_eog(vocab, stop), "the stop token %d is an end-of-generation token for llama.cpp", stop);

            size_t n_same = 0, n_prompt_tokens = 0, n_reply_tokens = 0;
            double seconds_prompt = 0.0, seconds_reply = 0.0;
            std::string first_divergence = "none";
            for (size_t k = 0; k < ref.seqs.size(); k++) {
                const sequence & s = ref.seqs[k];
                const std::vector<llama_token> prompt(s.ids.begin(), s.ids.begin() + s.n_prompt);
                const greedy_result g = generate(ev, vocab, prompt, stop, max_new_tokens, 0.0f);
                if (g.tokens == s.greedy) {
                    n_same++;
                } else if (first_divergence == "none") {
                    first_divergence = "sequence " + std::to_string(k) + ", reply token " + std::to_string(common_prefix(g.tokens, s.greedy));
                }
                n_prompt_tokens += prompt.size();
                n_reply_tokens += g.tokens.size();
                seconds_prompt += g.seconds_prompt;
                seconds_reply += g.seconds_reply;
            }
            if (f16) {
                // Reported, not gated: the 16-bit limits are statistical (top-1 above), and one
                // near-tie flipping would be within them.
                r.note("greedy replies identical to the reference: %zu of %zu, first divergence: %s", n_same, ref.seqs.size(), first_divergence.c_str());
            } else {
                r.check(n_same == ref.seqs.size(), "greedy replies identical to the reference: %zu of %zu, first divergence: %s",
                        n_same, ref.seqs.size(), first_divergence.c_str());
            }
            r.note("speed: prompt %.0f tokens/s (%zu tokens in one batch each), generation %.1f tokens/s (%zu tokens)",
                   (double) n_prompt_tokens / seconds_prompt, n_prompt_tokens, (double) n_reply_tokens / seconds_reply, n_reply_tokens);

            // A token the file marks as never to be sampled must stay unsampled even when a
            // caller's bias pushes it far above everything else.
            int32_t n_suppress = 0;
            const llama_token * suppress = llama_vocab_get_suppress_tokens(vocab, &n_suppress);
            const sequence & s = ref.seqs[0];
            const std::vector<llama_token> prompt(s.ids.begin(), s.ids.begin() + s.n_prompt);
            const greedy_result boosted = generate(ev, vocab, prompt, stop, 16, 1e9f);
            const bool leaked = std::any_of(boosted.tokens.begin(), boosted.tokens.end(), [&](llama_token t) {
                return std::find(suppress, suppress + n_suppress, t) != suppress + n_suppress;
            });
            r.check(n_suppress > 0 && !leaked, "%d suppressed token(s) (first: %d) never sampled, even with a +1e9 bias",
                    n_suppress, n_suppress > 0 ? suppress[0] : -1);
        }

        // Every micro-batch size must give the same logits: the cache and the positions are then
        // right however llama.cpp cuts a batch internally.
        {
            double d_ubatch = 0.0;
            agreement a_ubatch;
            const uint32_t sizes[] = {1, 2, 7, 32, 512};
            for (const uint32_t n_ubatch : sizes) {
                ev.open(n_ubatch);
                for (size_t k = 0; k < ref.seqs.size(); k++) {
                    const sequence & s = ref.seqs[k];
                    ev.clear();
                    const logit_rows rows = ev.decode(s.ids, 0, s.ids.size(), one_batch);
                    compare(ref, s, rows, 0, s.ids.size(), a_ubatch);
                    d_ubatch = std::max(d_ubatch, max_abs_between(baseline[k], rows, 0, s.ids.size()));
                }
            }
            judge(r, f16, "n_ubatch 1, 2, 7, 32, 512 vs ref.:", a_ubatch);
            if (!f16) {
                r.check(d_ubatch <= k_f32_max_abs, "n_ubatch 1, 2, 7, 32, 512 vs default:   max |dlogit| %.3g (limit %.0e), whole vocabulary", d_ubatch, k_f32_max_abs);
            }
        }
    }
    llama_backend_free();
    return r.finish();
}
