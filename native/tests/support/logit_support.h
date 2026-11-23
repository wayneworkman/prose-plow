// Shared by the test programs that compare a converted model with logits of its PyTorch
// reference: reading the logit reference written by native/tests/refgen/formats.py (the two
// must stay in step), feeding token sequences through the calls the app uses, and the
// statistics the verdicts are made of. Header-only, like test_support.h.

#pragma once

#include "test_support.h"

#include <algorithm>
#include <cmath>
#include <limits>
#include <numeric>

namespace pp_test {

constexpr uint32_t k_n_ctx   = 1024;
constexpr uint32_t k_n_batch = 512;

// The limits a model is held to, from the validation plan of the design each port implements.
struct logit_limits {
    double f32_max_abs  = 5e-3;  // F32 file: logits against the reference and against each other
    double f32_rank_gap = 1e-2;  // a reference rank is compared only when this far from its neighbours
    double f16_mean_kl  = 1e-5;  // F16 file: KL divergence from the fp32 reference, mean over positions
    double f16_max_kl   = 5e-3;  // and its largest value
    double f16_top1     = 0.995; // share of positions with the reference's first choice
    // Positions whose two best reference logits are closer than this are left out of the top-1
    // count. Zero for trained checkpoints, where the plain count is the criterion. A random
    // checkpoint has nearly flat distributions with ties far below the 16-bit rounding error,
    // and there a flipped tie says nothing about the port.
    double top1_margin  = 0.0;
};

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
        // A file with the vector at every position lists the positions in order: no search then,
        // which matters for a sequence of thousands of tokens.
        if (pos < s.full_pos.size() && (size_t) s.full_pos[pos] == pos) {
            return s.full.data() + pos * n_vocab;
        }
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

inline reference load_reference(const std::string & path) {
    reader in(path);
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
        die(path + " has trailing bytes");
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

    // What open() asks of the next context besides its arguments. The defaults are what every
    // test used before these existed: a context of k_n_ctx tokens per sequence, and llama.cpp's
    // own default for a model with windowed layers (they keep every cell, like the other layers).
    uint32_t n_ctx    = k_n_ctx;
    bool     swa_full = true;
    // Threads for a call that feeds a single token; 0 means as many as for a batch. The graph
    // of one token of a small model is over before a large pool has woken up.
    int      n_threads_single = 0;

    void open(uint32_t n_ubatch, uint32_t n_seq_max = 1) {
        close();
        llama_context_params params = llama_context_default_params();
        params.n_ctx           = n_ctx * n_seq_max;
        params.swa_full        = swa_full;
        params.n_batch         = k_n_batch;
        params.n_ubatch        = n_ubatch;
        params.n_seq_max       = n_seq_max;
        params.n_threads       = n_threads_single > 0 ? n_threads_single : n_threads();
        params.n_threads_batch = n_threads();
        params.no_perf         = true;
        if (!f16) {
            // The validation configuration: nothing but the weights' own precision in the way.
            params.type_k          = GGML_TYPE_F32;
            params.type_v          = GGML_TYPE_F32;
            params.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_DISABLED;
        }
        ctx.reset(llama_init_from_model(model, params));
        if (!ctx) {
            die("cannot create a context");
        }
        batch = llama_batch_ext_init(ctx.get());
    }

    void clear() { llama_memory_clear(llama_get_memory(ctx.get()), true); }

    bool rewind(size_t n_keep, llama_seq_id seq = 0) { return llama_memory_seq_rm(llama_get_memory(ctx.get()), seq, (llama_pos) n_keep, -1); }

    // Feeds ids[begin, end) at positions begin.. in one call and returns what llama_process
    // returned: 0 when the tokens were decoded. Every position gets logits.
    int32_t process(const std::vector<llama_token> & ids, size_t begin, size_t end, llama_seq_id seq = 0) {
        llama_batch_ext_clear(batch);
        for (size_t pos = begin; pos < end; pos++) {
            const int32_t idx = llama_batch_ext_add_token(batch, seq, ids[pos]);
            const llama_pos p = (llama_pos) pos;
            if (idx < 0 || !llama_batch_ext_set_pos(batch, idx, &p) || !llama_batch_ext_set_output_logits(batch, idx, true)) {
                die("cannot build a batch");
            }
        }
        return llama_process(ctx.get(), LLAMA_PROCESS_TYPE_DECODE, batch);
    }

    // Feeds ids[begin, end) at positions begin.., cut into the given chunk sizes (the last size
    // repeats), and returns the logits of every one of those positions.
    logit_rows decode(const std::vector<llama_token> & ids, size_t begin, size_t end, const std::vector<size_t> & chunks, llama_seq_id seq = 0) {
        logit_rows out;
        out.first_pos = begin;
        out.n_vocab = (size_t) n_vocab;
        out.data.reserve((end - begin) * out.n_vocab);
        size_t pos = begin;
        for (size_t c = 0; pos < end; c++) {
            const size_t want = chunks[std::min(c, chunks.size() - 1)];
            const size_t n = std::min({want, end - pos, (size_t) k_n_batch});
            const int32_t rc = process(ids, pos, pos + n, seq);
            if (rc != 0) {
                die("llama_process returned " + std::to_string(rc));
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
    context_ptr ctx;
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

// log-softmax in double: the KL limits are far below float32 resolution of the logits.
inline void log_softmax(const float * logits, size_t n, std::vector<double> & out) {
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
inline void compare_full(const float * full, const float * got, size_t n_vocab, const logit_limits & limits, agreement & a) {
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

    if ((double) (full[best_ref] - second_ref) >= limits.top1_margin) {
        a.n_top1++;
        a.n_top1_same += best_ref == best_got ? 1 : 0;
    }
}

// Adds the positions [first, last) of sequence s, whose port logits are in rows.
inline void compare(const reference & ref, const sequence & s, const logit_rows & rows, size_t first, size_t last,
                    const logit_limits & limits, agreement & a) {
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
        // logit is within the gap of a neighbour may legitimately swap under a small perturbation.
        std::iota(order.begin(), order.end(), 0);
        std::partial_sort(order.begin(), order.begin() + (long) top_k, order.end(), [&](int32_t x, int32_t y) {
            return got[x] > got[y] || (got[x] == got[y] && x < y);
        });
        for (size_t j = 0; j + 1 < top_k; j++) {
            const bool clear_above = j == 0 || top_val[j - 1] - top_val[j] > limits.f32_rank_gap;
            const bool clear_below = top_val[j] - top_val[j + 1] > limits.f32_rank_gap;
            if (clear_above && clear_below) {
                a.n_rank_checked++;
                if (order[j] != top_idx[j]) {
                    a.n_rank_wrong++;
                }
            }
        }

        if (const float * full = ref.full_row(s, pos)) {
            compare_full(full, got, n_vocab, limits, a);
        }
    }
}

// The same statistics between two runs of the port, the first taken as the reference.
inline void compare_rows(const logit_rows & expected, const logit_rows & got, size_t first, size_t last, const logit_limits & limits, agreement & a) {
    for (size_t pos = first; pos < last; pos++) {
        compare_full(expected.row(pos), got.row(pos), expected.n_vocab, limits, a);
    }
}

inline double max_abs_between(const logit_rows & x, const logit_rows & y, size_t first, size_t last) {
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
inline void judge(report & r, bool f16, const char * what, const agreement & a, const logit_limits & limits) {
    if (f16) {
        r.check(a.mean_kl() <= limits.f16_mean_kl && a.max_kl <= limits.f16_max_kl && a.top1() >= limits.f16_top1,
                "%-34s mean KL %.3g (limit %.0e), max KL %.3g (limit %.0e), top-1 %.2f%% of %zu (limit %.1f%%), max |dlogit| %.3g, %zu positions",
                what, a.mean_kl(), limits.f16_mean_kl, a.max_kl, limits.f16_max_kl, 100.0 * a.top1(), a.n_top1, 100.0 * limits.f16_top1, a.max_abs, a.n_full);
    } else {
        r.check(a.max_abs <= limits.f32_max_abs && a.n_rank_wrong == 0,
                "%-34s max |dlogit| %.3g (limit %.0e) over %zu values, %zu of %zu clear ranks differ",
                what, a.max_abs, limits.f32_max_abs, a.n_values, a.n_rank_wrong, a.n_rank_checked);
    }
}

inline size_t common_prefix(const std::vector<llama_token> & a, const std::vector<llama_token> & b) {
    size_t n = 0;
    while (n < a.size() && n < b.size() && a[n] == b[n]) {
        n++;
    }
    return n;
}

} // namespace pp_test
