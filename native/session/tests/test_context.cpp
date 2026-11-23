// The size of a session's context: what a size costs, how it is changed, and what happens when
// it cannot be had. Against a real model file.
//
//   test_context --gguf FILE --trained-context N --bytes-per-token B [--context-at-most 1]
//
// The two numbers are the model's entry in models/manifest.json (trained_context and
// context_bytes_per_token), which the app shows before a model is loaded: they are held to the
// file here. --context-at-most says that the file's own context may be larger than the
// manifest's (a model whose card states less than its positions reach).
//
// Nothing here allocates more than a few hundred MiB for real. The costs of the largest
// contexts are compared with what llama.cpp itself computes for them from a model loaded
// without allocating (no_alloc), which allocates none of it; a size that must not fit is made
// not to fit by a limit on the address space of this process, never by using memory up; and a
// budget is a number that is stated.
//
// Every check prints one line with what was measured. Exit codes as in test_support.h.

#include "proseplow_session.h"
#include "support/test_support.h"

#include <algorithm>
#include <cinttypes>
#include <cmath>
#include <limits>

#include <malloc.h>
#include <sys/resource.h>

namespace {

using pp_test::report;

constexpr int32_t k_batch  = 32;                // small, so that a context of 256 already has a first, smaller context before it
constexpr int32_t k_open   = 256;               // the size most sessions here are opened with
constexpr int64_t k_budget = (int64_t) 8 << 30; // a limit that is stated, not memory that is taken
constexpr double  k_mib    = 1048576.0;

#if defined(__SANITIZE_ADDRESS__)
constexpr bool k_sanitized = true;
#elif defined(__has_feature)
#if __has_feature(address_sanitizer)
constexpr bool k_sanitized = true;
#else
constexpr bool k_sanitized = false;
#endif
#else
constexpr bool k_sanitized = false;
#endif

struct session_closer {
    void operator()(pp_session * s) const { pp_session_close(s); }
};
using session_ptr = std::unique_ptr<pp_session, session_closer>;

// What the allocator has handed out, and what llama.cpp said while a context was created.
int64_t allocated() {
    const struct mallinfo2 info = mallinfo2();
    return (int64_t) info.uordblks + (int64_t) info.hblkhd;
}

std::string g_lines;
void collect(enum ggml_log_level level, const char * text, void *) {
    if (level == GGML_LOG_LEVEL_ERROR) {
        std::fputs(text, stderr);
    }
    if (std::strstr(text, "buffer size =") != nullptr) {
        g_lines += text;
    }
}

// The sum of the "<name> buffer size = <number> MiB" lines in g_lines, in bytes; -1 without one.
int64_t reported(const char * name) {
    int64_t sum = -1;
    const std::string needle = std::string(name) + " buffer size =";
    for (size_t at = g_lines.find(needle); at != std::string::npos; at = g_lines.find(needle, at + 1)) {
        sum = std::max<int64_t>(sum, 0) + (int64_t) std::llround(std::strtod(g_lines.c_str() + at + needle.size(), nullptr) * k_mib);
    }
    return sum;
}

// A context straight from llama.cpp, with the parameters a session gives its own: what it
// really allocates, by the allocator's count, and what it reports.
struct real_context {
    int64_t allocated = 0;
    int64_t kv = -1, compute = -1;
};

real_context measure(llama_model * model, int32_t n_ctx) {
    llama_context_params p = llama_context_default_params();
    p.n_ctx = (uint32_t) n_ctx;
    p.n_batch = (uint32_t) std::min(k_batch, n_ctx);
    p.n_ubatch = p.n_batch;
    p.n_seq_max = 1;
    p.n_threads = 2;
    p.n_threads_batch = 2;
    p.no_perf = true;
    g_lines.clear();
    real_context out;
    const int64_t before = allocated();
    llama_context * ctx = llama_init_from_model(model, p);
    if (ctx == nullptr) {
        pp_test::die("llama.cpp could not create a context of " + std::to_string(n_ctx) + " tokens");
    }
    out.allocated = allocated() - before;
    out.kv = reported("KV");
    out.compute = reported("compute");
    llama_free(ctx);
    return out;
}

// While one of these exists the process can map `room` bytes more than it has mapped now, or,
// with a negative room, that many fewer: nothing new can then be mapped until something as
// large has been unmapped. Nothing is allocated to bring this about.
class address_space {
  public:
    explicit address_space(int64_t room) {
        unsigned long long mapped_kb = 0;
        if (std::FILE * f = std::fopen("/proc/self/status", "r")) {
            char line[256];
            while (std::fgets(line, sizeof(line), f) != nullptr) {
                unsigned long long kb = 0;
                if (std::sscanf(line, "VmSize: %llu kB", &kb) == 1) {
                    mapped_kb = kb;
                }
            }
            std::fclose(f);
        }
        // Never below one page: a limit of nothing at all is "unlimited" to some systems.
        const int64_t limit = std::max<int64_t>(4096, (int64_t) mapped_kb * 1024 + room);
        if (mapped_kb != 0 && getrlimit(RLIMIT_AS, &before) == 0) {
            struct rlimit limited = before;
            limited.rlim_cur = (rlim_t) limit;
            if (before.rlim_max == RLIM_INFINITY || limited.rlim_cur <= before.rlim_max) {
                active = setrlimit(RLIMIT_AS, &limited) == 0;
            }
        }
    }
    ~address_space() {
        if (active) {
            setrlimit(RLIMIT_AS, &before);
        }
    }
    address_space(const address_space &) = delete;
    address_space & operator=(const address_space &) = delete;
    bool in_force() const { return active; }

  private:
    struct rlimit before = {};
    bool active = false;
};

// A session and what the test knows of its memory.
struct talk {
    session_ptr owner;
    pp_session * s = nullptr;
    std::vector<llama_token> banned; // every token that ends a reply: a sampled token can then always be evaluated
    std::vector<llama_token> memory;

    void evaluate(const std::vector<llama_token> & tokens) {
        pp_evaluation_observer observer = {[](void *) { return false; }, [](int32_t, void *) {}, nullptr};
        int32_t n = 0;
        if (pp_session_evaluate(s, tokens.data(), (int32_t) tokens.size(), k_batch, &observer, &n) != PP_STATUS_OK || n != (int32_t) tokens.size()) {
            pp_test::die(std::string("pp_session_evaluate failed: ") + pp_session_last_error(s));
        }
        memory.insert(memory.end(), tokens.begin(), tokens.end());
    }

    // Greedy with a repetition penalty over the whole memory: what is chosen depends on the
    // record of the memory's tokens as much as on the memory.
    void configure() {
        pp_sampler_config config = {};
        config.top_p = 1.0f;
        config.repetition_penalty = 1.6f;
        config.penalty_window = PP_PENALTY_NO_WINDOW;
        config.banned_tokens = banned.data();
        config.n_banned_tokens = (int32_t) banned.size();
        if (pp_session_configure_sampler(s, &config) != PP_STATUS_OK) {
            pp_test::die(std::string("pp_session_configure_sampler failed: ") + pp_session_last_error(s));
        }
    }

    std::vector<llama_token> reply(int n) {
        std::vector<llama_token> out;
        for (int i = 0; i < n; i++) {
            llama_token token = LLAMA_TOKEN_NULL;
            if (pp_session_sample_next(s, &token) != PP_STATUS_OK) {
                pp_test::die(std::string("pp_session_sample_next failed: ") + pp_session_last_error(s));
            }
            out.push_back(token);
            evaluate({token});
        }
        return out;
    }
};

talk open(const std::string & gguf, int32_t n_ctx, int64_t budget = k_budget, int32_t * status = nullptr, std::string * why = nullptr) {
    talk t;
    char error[256] = "";
    pp_session * opened = nullptr;
    pp_session_params params = {};
    params.n_ctx = n_ctx;
    params.context_budget_bytes = budget;
    params.n_batch = k_batch;
    params.n_threads = pp_test::n_threads();
    params.load_mode = PP_LOAD_MODE_MMAP;
    const int32_t result = pp_session_open_path(gguf.c_str(), &params, &opened, error, sizeof(error));
    if (status != nullptr) {
        *status = result;
    } else if (result != PP_STATUS_OK) {
        pp_test::die(std::string("the model does not open: ") + error);
    }
    if (why != nullptr) {
        *why = error;
    }
    t.owner.reset(opened);
    t.s = opened;
    if (opened != nullptr) {
        for (llama_token id = 0; id < pp_session_vocab_size(opened); id++) {
            if (pp_session_is_end_of_generation(opened, id)) {
                t.banned.push_back(id);
            }
        }
    }
    return t;
}

} // namespace

int main(int argc, char ** argv) {
    std::string gguf;
    int32_t trained = 0;
    int64_t per_token = 0;
    bool at_most = false;
    for (int i = 1; i + 1 < argc; i += 2) {
        const std::string key = argv[i];
        if (key == "--gguf") {
            gguf = argv[i + 1];
        } else if (key == "--trained-context") {
            trained = std::atoi(argv[i + 1]);
        } else if (key == "--bytes-per-token") {
            per_token = std::atoll(argv[i + 1]);
        } else if (key == "--context-at-most") {
            at_most = std::atoi(argv[i + 1]) != 0;
        }
    }
    if (gguf.empty() || trained < 1 || per_token < 1) {
        std::fprintf(stderr, "usage: %s --gguf FILE --trained-context N --bytes-per-token B [--context-at-most 1]\n", argv[0]);
        return 2;
    }
    // Every block of any size its own mapping, given back when it is freed: the address space
    // then follows what is allocated exactly, which the checks with a limit on it rely on.
    mallopt(M_MMAP_THRESHOLD, 128 * 1024);

    report r;

    // --- llama.cpp alone, before there is a session and its log sink ---

    // Contexts created directly, with the parameters a session gives its own: what the
    // allocator hands out for each and what llama.cpp reports of it. And the same from a model
    // loaded without allocating, which is how llama.cpp sizes a context it does not create.
    llama_log_set(collect, nullptr);
    llama_backend_init();
    const std::vector<int32_t> sizes = {k_open, 2 * k_open, 4 * k_open};
    std::vector<real_context> real;
    real_context sized_small, sized_large;
    {
        pp_test::model_ptr model = pp_test::load_model(gguf, false);
        for (const int32_t n : sizes) {
            real.push_back(measure(model.get(), n));
        }
        llama_model_params mp = llama_model_default_params();
        mp.n_gpu_layers = 0;
        mp.no_alloc = true;
        mp.load_mode = LLAMA_LOAD_MODE_NONE;
        pp_test::model_ptr sized(llama_model_load_from_file(gguf.c_str(), mp));
        if (!sized) {
            pp_test::die("the model does not load for sizing");
        }
        sized_small = measure(sized.get(), k_open);
        sized_large = measure(sized.get(), llama_model_n_ctx_train(model.get()));
    }
    if (pp_runtime_init(nullptr, 0) != PP_STATUS_OK) {
        pp_test::die("pp_runtime_init failed");
    }

    // --- The model's two facts, and the report the session reads ---

    talk t = open(gguf, k_open);
    pp_session * s = t.s;
    const int32_t n_max = pp_session_max_context_size(s);
    r.check(at_most ? n_max >= trained : n_max == trained, "the file says the model was trained for %d tokens; models/manifest.json says %d%s",
            n_max, trained, at_most ? " and that the file reaches further" : "");

    // The session reads llama.cpp's own report of the buffers of a context; these are the same
    // lines, read above from contexts created directly.
    const int64_t file_per_token = real.back().kv < 0 ? -1 : (int64_t) std::llround((double) real.back().kv / (double) sizes.back());
    r.check(real.back().kv > 0 && real.back().compute > 0,
            "llama.cpp reports the buffers of a context in the form the session reads (\"... KV buffer size = %.2f MiB\", \"... compute buffer size = %.2f MiB\"). "
            "IF THIS FAILS the form has changed, the session no longer knows what a context costs and refuses every change of size: "
            "see \"Moving the upstream pin\" in native/README.md", (double) real.back().kv / k_mib, (double) real.back().compute / k_mib);
    r.check(std::llabs(file_per_token - per_token) <= 12, "the key-value buffers are %" PRId64 " bytes per token of context; models/manifest.json says %" PRId64
            " (the report is rounded to 0.01 MiB, which is 10 bytes per token at %d tokens)", file_per_token, per_token, sizes.back());

    // --- What a context costs, against what llama.cpp really allocates ---

    const int64_t known = pp_session_context_bytes(s, k_open);
    r.check(known > 0 && pp_session_context_bytes(s, 0) == -1 && pp_session_context_bytes(s, n_max) > 0 && (n_max == std::numeric_limits<int32_t>::max() || pp_session_context_bytes(s, n_max + 1) == -1),
            "the session knows what a context costs: %" PRId64 " bytes for %d tokens, %" PRId64 " for the model's %d; none for 0 tokens or for one more than that",
            known, k_open, pp_session_context_bytes(s, n_max), n_max);
    if (k_sanitized || real.front().allocated <= 0) {
        r.note("the allocator cannot be asked what it handed out here (a sanitizer's allocator): the costs are NOT compared with real allocations");
    } else {
        double worst = 0.0;
        std::string table;
        for (size_t i = 0; i < sizes.size(); i++) {
            const int64_t cost = pp_session_context_bytes(s, sizes[i]) - (int64_t) sizes[i] * (int64_t) sizeof(llama_token); // less the session's own record
            worst = std::max(worst, std::fabs((double) (cost - real[i].allocated)) / (double) real[i].allocated);
            table += (i ? ", " : "") + std::to_string(sizes[i]) + ": " + std::to_string(cost) + " against " + std::to_string(real[i].allocated);
        }
        const double step_real = (double) (real[2].allocated - real[0].allocated) / (double) (sizes[2] - sizes[0]);
        const double step_cost = (double) (pp_session_context_bytes(s, sizes[2]) - pp_session_context_bytes(s, sizes[0])) / (double) (sizes[2] - sizes[0]);
        r.check(worst <= 0.01, "the cost of a context against what the allocator handed llama.cpp for one, at most %.2f%% apart (limit 1%%): %s bytes", 100.0 * worst, table.c_str());
        r.check(std::fabs(step_cost - step_real) <= 0.01 * step_real, "the difference between two sizes, which is what a caller budgets with: %.0f bytes per token costed, %.0f allocated",
                step_cost, step_real);
    }

    // At the model's largest context, against llama.cpp's own sizing of it from the model loaded
    // without allocating: the compute reservation it would make and the bookkeeping it really
    // allocates. The key-value buffers are rows of one size and need no measuring.
    {
        const real_context & small = sized_small, & large = sized_large;
        const int64_t cost_small = pp_session_context_bytes(s, k_open), cost_large = pp_session_context_bytes(s, n_max);
        const int64_t expected = cost_small + (int64_t) (n_max - k_open) * (file_per_token + (int64_t) sizeof(llama_token)) + (large.compute - small.compute) +
                                 (k_sanitized ? 0 : large.allocated - small.allocated);
        const double apart = (double) (cost_large - expected) / (double) expected;
        r.check(small.compute > 0 && large.compute > 0 && std::fabs(apart) <= 0.03,
                "the model's largest context, %d tokens: costed at %.1f MiB, and %.1f MiB by llama.cpp's own sizing of it without allocating "
                "(compute reservation %.2f MiB there against %.2f MiB at %d tokens): %+.2f%% apart (limit 3%%)",
                n_max, (double) cost_large / k_mib, (double) expected / k_mib, (double) large.compute / k_mib, (double) small.compute / k_mib, k_open, 100.0 * apart);
    }

    // --- Changing the size: what is refused, with nothing touched ---

    std::vector<llama_token> prompt;
    for (int i = 0; (int) prompt.size() < 64; i++) {
        const llama_token id = (llama_token) (5 + ((int64_t) i * 7919) % (pp_session_vocab_size(s) - 10));
        if (!pp_session_is_end_of_generation(s, id)) {
            prompt.push_back(id);
        }
    }
    const int32_t n_grown = 2 * k_open;
    t.evaluate(prompt);
    const int32_t mark = pp_session_save_checkpoint(s);
    t.configure();
    const std::vector<llama_token> said = t.reply(8);
    {
        const int32_t count = pp_session_memory_token_count(s);
        const int32_t too_many = n_max == std::numeric_limits<int32_t>::max() ? PP_STATUS_CONTEXT_REFUSED : pp_session_resize_context(s, n_max + 1, k_budget);
        const int32_t too_dear = pp_session_resize_context(s, n_grown, pp_session_context_bytes(s, n_grown) - 1);
        const std::string why = pp_session_last_error(s);
        const int32_t none = pp_session_resize_context(s, 0, k_budget);
        const int32_t no_budget = pp_session_resize_context(s, n_grown, 0);
        const int32_t same = pp_session_resize_context(s, k_open, k_budget);
        llama_token token = LLAMA_TOKEN_NULL;
        const int32_t sampled = pp_session_sample_next(s, &token);
        r.check(too_many == PP_STATUS_CONTEXT_REFUSED && too_dear == PP_STATUS_CONTEXT_REFUSED && none == PP_STATUS_INVALID_ARGUMENT && no_budget == PP_STATUS_INVALID_ARGUMENT &&
                same == PP_STATUS_OK && pp_session_context_size(s) == k_open && pp_session_memory_token_count(s) == count &&
                pp_session_checkpoint_size(s, mark) > 0 && sampled == PP_STATUS_OK,
                "one token more than the model was trained for, and a size whose cost is one byte above the budget: CONTEXT_REFUSED (%d, %d: \"%s\"); 0 tokens, a budget of 0: "
                "INVALID_ARGUMENT (%d, %d); the size it has: OK (%d). After all five the context is %d tokens with the same %d in memory, the checkpoint is valid and "
                "the prediction that was ready is sampled from (%d)", too_many, too_dear, why.c_str(), none, no_budget, same, pp_session_context_size(s), count, sampled);
        t.evaluate({token});
    }

    // --- Changing the size: the memory carried over, or not, by the budget ---

    // What a fresh session of the larger size chooses after the same tokens.
    const auto fresh = [&](int32_t n_ctx, const std::vector<llama_token> & tokens, int n) {
        talk other = open(gguf, n_ctx);
        other.evaluate(tokens);
        other.configure();
        return other.reply(n);
    };
    {
        const std::vector<llama_token> held = t.memory;
        const int32_t count = (int32_t) held.size();
        const int64_t block = pp_session_carry_bytes(s);
        const int64_t peak = std::max(pp_session_context_bytes(s, k_open), pp_session_context_bytes(s, n_grown)) + block;

        // One byte short of the peak: the size fits and the block beside it does not.
        const int32_t emptied = pp_session_resize_context(s, n_grown, peak - 1);
        const int32_t count_emptied = pp_session_memory_token_count(s);
        const bool mark_gone = pp_session_checkpoint_size(s, mark) < 0;
        r.check(block > 0 && emptied == PP_STATUS_OK && pp_session_context_size(s) == n_grown && count_emptied == 0 && mark_gone,
                "from %d to %d tokens with a budget one byte below the peak of a carry-over (the larger context and a block of %" PRId64 " bytes for %d tokens in memory, "
                "%" PRId64 " bytes in all): OK (%d), a context of %d with an empty memory, the checkpoint gone", k_open, n_grown, block, count, peak, emptied, pp_session_context_size(s));

        // Back, the conversation read again, and the same change with the peak as the budget.
        pp_session_clear_memory(s);
        const bool back = pp_session_context_size(s) == k_open;
        t.memory.clear();
        t.evaluate(held);
        const int32_t again = pp_session_save_checkpoint(s);
        const int64_t peak_again = std::max(pp_session_context_bytes(s, k_open), pp_session_context_bytes(s, n_grown)) + pp_session_carry_bytes(s);
        const int32_t carried = pp_session_resize_context(s, n_grown, peak_again);
        llama_token token = LLAMA_TOKEN_NULL;
        const int32_t no_prediction = pp_session_sample_next(s, &token);
        r.check(back && carried == PP_STATUS_OK && pp_session_context_size(s) == n_grown && pp_session_memory_token_count(s) == count &&
                pp_session_checkpoint_size(s, again) > 0 && no_prediction == PP_STATUS_SAMPLING_FAILED,
                "a clear gives the opening size back (%s); with exactly the peak as the budget: OK (%d), a context of %d with the same %d tokens in memory, "
                "the checkpoint valid, and no prediction ready (SAMPLING_FAILED)", back ? "yes" : "NO", carried, pp_session_context_size(s), pp_session_memory_token_count(s));

        // The memory that was carried over goes on exactly as one that was evaluated there:
        // the next tokens, chosen with a penalty over everything in memory, are the same.
        std::vector<llama_token> more = held;
        more.push_back(prompt[1]);
        t.evaluate({prompt[1]});
        t.configure();
        const std::vector<llama_token> here = t.reply(8);
        const std::vector<llama_token> there = fresh(n_grown, more, 8);
        r.check(here == there, "the carried memory continues as a memory evaluated in a context of that size does: the same 8 tokens, chosen with a repetition penalty "
                "over all %zu tokens in memory", more.size());

        // A checkpoint saved before the change of size brings the memory back in the new context.
        const bool restored = pp_session_restore_checkpoint(s, again) && pp_session_memory_token_count(s) == count;
        t.memory = held;
        t.evaluate({prompt[1]});
        t.configure();
        r.check(restored && t.reply(8) == there, "the checkpoint saved before the change of size is restored after it, and the same 8 tokens follow again");

        // Smaller again with the memory in it; a size that is not a multiple of 256 is the
        // next one that is; and smaller than what the memory holds, which empties it.
        const int32_t in_memory = pp_session_memory_token_count(s);
        const int32_t shrunk = pp_session_resize_context(s, k_open, k_budget);
        const int32_t kept = pp_session_memory_token_count(s);
        const int32_t uneven = pp_session_resize_context(s, k_open + 1, k_budget);
        const int32_t uneven_size = pp_session_context_size(s);
        while (pp_session_memory_token_count(s) <= k_open) {
            t.evaluate(prompt);
        }
        const int32_t longer = pp_session_memory_token_count(s);
        const int32_t below = pp_session_resize_context(s, k_open, k_budget);
        r.check(shrunk == PP_STATUS_OK && kept == in_memory && uneven == PP_STATUS_OK && uneven_size == n_grown && below == PP_STATUS_OK &&
                pp_session_context_size(s) == k_open && pp_session_memory_token_count(s) == 0,
                "back to %d tokens: OK (%d), the %d tokens in memory kept; to %d tokens: OK (%d), a context of %d; to %d tokens with %d in memory: OK (%d) with an empty memory",
                k_open, shrunk, kept, k_open + 1, uneven, uneven_size, k_open, longer, below);
        t.memory.clear();
    }

    // --- A size that cannot be had: the address space has no room for it ---

    if (k_sanitized) {
        r.note("a sanitizer's allocator ends the process when it cannot map: the checks with a limit on the address space are NOT run");
    } else {
        // Sizes whose key-value buffers are a hundred MiB or more where the model has such
        // sizes, so that a half is well apart from a whole: the session asks the address space
        // for a little more than a context will take (the allocator keeps tens of MiB of what
        // the context before it freed), and the rooms below have to be clear of that.
        int32_t n_old = k_open;
        while ((int64_t) n_old * per_token < ((int64_t) 128 << 20) && n_old * 4 <= n_max) {
            n_old *= 2;
        }
        const int32_t n_new = 2 * n_old;
        t = talk();
        t = open(gguf, n_old);
        s = t.s;
        t.evaluate(prompt);
        const int32_t kept_mark = pp_session_save_checkpoint(s);
        const int64_t cost_old = pp_session_context_bytes(s, n_old), cost_new = pp_session_context_bytes(s, n_new), cost_half = pp_session_context_bytes(s, n_old / 2);
        int32_t status = PP_STATUS_OK;
        bool limited = false;
        {
            // Room for the block the memory is carried in and for half of what the larger size
            // needs beyond the present one.
            const address_space limit(pp_session_carry_bytes(s) + (cost_new - cost_old) / 2);
            limited = limit.in_force();
            status = pp_session_resize_context(s, n_new, k_budget);
        }
        if (!limited) {
            r.note("no address space limit can be set here: the checks of a context that cannot be created are NOT run");
        } else {
            const std::string why = pp_session_last_error(s);
            r.check(status == PP_STATUS_CONTEXT_NOT_CREATED && pp_session_context_size(s) == n_old && pp_session_memory_token_count(s) == (int32_t) prompt.size() &&
                    pp_session_checkpoint_size(s, kept_mark) > 0,
                    "from %d to %d tokens (%.0f MiB more) with room in the address space for half of that and the carried block: CONTEXT_NOT_CREATED (%d), the context of %d again with its %d tokens "
                    "in memory and its checkpoint (\"%s\")", n_old, n_new, (double) (cost_new - cost_old) / k_mib, status, pp_session_context_size(s), pp_session_memory_token_count(s), why.c_str());

            // Nothing in memory, and so little room that the present size cannot be created
            // again once it is freed, while half of it can.
            pp_session_resize_context(s, n_old, k_budget);
            pp_session_clear_memory(s);
            {
                const address_space limit(-(cost_old - cost_half) / 4);
                status = pp_session_resize_context(s, n_new, k_budget);
            }
            r.check(status == PP_STATUS_CONTEXT_NOT_CREATED && pp_session_context_size(s) == n_old / 2 && pp_session_memory_token_count(s) == 0,
                    "the same with %.0f MiB less address space than was mapped: CONTEXT_NOT_CREATED (%d), neither %d nor %d could be had, and the session has a context of %d (\"%s\")",
                    (double) (cost_old - cost_half) / 4.0 / k_mib, status, n_new, n_old, pp_session_context_size(s), pp_session_last_error(s));

            // No room for any size at all.
            {
                const address_space limit(std::numeric_limits<int64_t>::min() / 2);
                status = pp_session_resize_context(s, n_new, k_budget);
            }
            int32_t n_in = 0;
            pp_evaluation_observer observer = {[](void *) { return false; }, [](int32_t, void *) {}, nullptr};
            const int32_t evaluated = pp_session_evaluate(s, prompt.data(), 1, 1, &observer, &n_in);
            const int32_t cleared = pp_session_clear_memory(s);
            r.check(status == PP_STATUS_CONTEXT_NOT_CREATED && evaluated == PP_STATUS_OUT_OF_MEMORY && cleared == PP_STATUS_OK && pp_session_context_size(s) == n_old,
                    "with no address space to be had at all: CONTEXT_NOT_CREATED (%d) and no context, an evaluation answers OUT_OF_MEMORY (%d); with the limit lifted "
                    "a clear creates the opening context of %d again (%d)", status, evaluated, pp_session_context_size(s), cleared);
            t.memory.clear();
            t.evaluate(prompt);
            t.configure();
            r.check(t.reply(4).size() == 4, "and the session evaluates and samples as before");
        }

        // Opening is held to the same budget from nothing.
        int32_t opened = PP_STATUS_OK;
        std::string why;
        talk refused = open(gguf, n_old, cost_old - 1, &opened, &why);
        r.check(opened == PP_STATUS_CONTEXT_REFUSED && refused.s == nullptr, "opening at %d tokens with a budget one byte below their cost: CONTEXT_REFUSED (%d), no session (\"%s\")",
                n_old, opened, why.c_str());
    }

    return r.finish();
}
