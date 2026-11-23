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
#include <atomic>
#include <cinttypes>
#include <cmath>
#include <cstdlib>
#include <limits>
#include <thread>

#include <malloc.h>
#include <sys/resource.h>

namespace {

using pp_test::report;

constexpr int32_t k_batch  = 32;                // small, so that a session opened at 512 tokens first creates a context of 256
constexpr int32_t k_open   = 256;               // the size most sessions here are opened with
// A session whose batch is larger than a context it creates: opened at k_wide_open with a batch
// of k_wide_batch (it creates a first context of one batch), then given k_wide_small tokens,
// whose batch is that size; k_wide_far is the larger size it then costs.
constexpr int32_t k_wide_batch = 512;
constexpr int32_t k_wide_open  = 1024;
constexpr int32_t k_wide_small = 256;
constexpr int32_t k_wide_far   = 2048;
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
    int64_t reported = 0; // every buffer in the report
};

real_context measure(llama_model * model, int32_t n_ctx, int32_t batch = k_batch) {
    llama_context_params p = llama_context_default_params();
    p.n_ctx = (uint32_t) n_ctx;
    p.n_batch = (uint32_t) std::min(batch, n_ctx);
    p.n_ubatch = p.n_batch;
    p.n_seq_max = 1;
    // As a session's: the threads change what the backend allocates for a context.
    p.n_threads = pp_test::n_threads();
    p.n_threads_batch = pp_test::n_threads();
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
    out.reported = std::max<int64_t>(0, reported(""));
    llama_free(ctx);
    return out;
}

// The bytes this process has mapped (VmSize), or 0 when it cannot be read.
int64_t mapped_bytes() {
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
    return (int64_t) mapped_kb * 1024;
}

// Things the test does in the middle of a session's call, through llama.cpp's log: the one
// place where the test is called back while the session creates or frees a context. The
// session's own log callback is installed by pp_runtime_init and is handed every line on, so
// its reading of the report is what it would be without the test; only `garble` changes what
// it is handed.
struct hooks {
    ggml_log_callback next = nullptr;
    void *            next_data = nullptr;
    // Every "buffer size =" line handed on with its unit spoilt, so that the session cannot
    // read the report: the form llama.cpp would have after an upstream change of it.
    bool   garble = false;
    // A block to be freed on another thread while a context is being created (at its first
    // "buffer size =" line): memory that the process gives back during the creation, as an
    // app's other threads do, which the allocator's process-wide count shows.
    void * free_during_creation = nullptr;
    // A limit on the address space to be set when the next context is freed (at the line its
    // destructor logs, while its buffers are still mapped): this many bytes less than is
    // mapped then, so that once it is gone the room is its size less these bytes.
    bool    limit_at_free = false;
    int64_t limit_less = 0;
    struct rlimit saved = {};
    bool    limited = false;
    int     fired = 0;
};
hooks g_hooks;

void hooked_sink(enum ggml_log_level level, const char * text, void * data) {
    hooks & h = g_hooks;
    if (text != nullptr && std::strstr(text, "buffer size =") != nullptr) {
        if (h.free_during_creation != nullptr) {
            void * block = h.free_during_creation;
            h.free_during_creation = nullptr;
            std::thread([block] { std::free(block); }).join();
            h.fired++;
        }
        if (h.garble) {
            std::string spoilt = text;
            for (size_t at = spoilt.find(" MiB"); at != std::string::npos; at = spoilt.find(" MiB", at)) {
                spoilt.replace(at, 4, " M?B");
            }
            h.next(level, spoilt.c_str(), data);
            return;
        }
    }
    if (text != nullptr && h.limit_at_free && std::strstr(text, "~llama_context") != nullptr) {
        h.limit_at_free = false;
        const int64_t mapped = mapped_bytes();
        if (mapped > 0 && getrlimit(RLIMIT_AS, &h.saved) == 0) {
            struct rlimit limited = h.saved;
            limited.rlim_cur = (rlim_t) std::max<int64_t>(4096, mapped - h.limit_less);
            if (h.saved.rlim_max == RLIM_INFINITY || limited.rlim_cur <= h.saved.rlim_max) {
                h.limited = setrlimit(RLIMIT_AS, &limited) == 0;
            }
        }
        h.fired++;
    }
    h.next(level, text, data);
}

// Lifts a limit that hooked_sink set.
void lift_hooked_limit() {
    if (g_hooks.limited) {
        setrlimit(RLIMIT_AS, &g_hooks.saved);
        g_hooks.limited = false;
    }
    g_hooks.limit_at_free = false;
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
        config.reply_start = PP_REPLY_START_AT_SAMPLING;
        config.banned_tokens = banned.data();
        config.n_banned_tokens = (int32_t) banned.size();
        if (pp_session_configure_sampler(s, &config) != PP_STATUS_OK) {
            pp_test::die(std::string("pp_session_configure_sampler failed: ") + pp_session_last_error(s));
        }
    }

    // Greedy with presence and frequency penalties, which count the reply alone: what is
    // chosen depends on where the session holds the reply to have begun.
    void configure_presence() {
        pp_sampler_config config = {};
        config.top_p = 1.0f;
        config.repetition_penalty = 1.0f;
        config.presence_penalty = 0.5f;
        config.frequency_penalty = 1.5f;
        config.penalty_window = PP_PENALTY_NO_WINDOW;
        config.reply_start = PP_REPLY_START_AT_SAMPLING;
        config.banned_tokens = banned.data();
        config.n_banned_tokens = (int32_t) banned.size();
        if (pp_session_configure_sampler(s, &config) != PP_STATUS_OK) {
            pp_test::die(std::string("pp_session_configure_sampler failed: ") + pp_session_last_error(s));
        }
    }

    // A token sampled and not evaluated, as the last token of a reply is left.
    llama_token sample_only() {
        llama_token token = LLAMA_TOKEN_NULL;
        if (pp_session_sample_next(s, &token) != PP_STATUS_OK) {
            pp_test::die(std::string("pp_session_sample_next failed: ") + pp_session_last_error(s));
        }
        return token;
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

talk open(const std::string & gguf, int32_t n_ctx, int64_t budget = k_budget, int32_t * status = nullptr, std::string * why = nullptr, int32_t batch = k_batch) {
    talk t;
    char error[256] = "";
    pp_session * opened = nullptr;
    pp_session_params params = {};
    params.n_ctx = n_ctx;
    params.context_budget_bytes = budget;
    params.n_batch = batch;
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
    real_context sized_small, sized_large, wide_real;
    {
        pp_test::model_ptr model = pp_test::load_model(gguf, false);
        // The first context of a process allocates a few things once and keeps them (tens of
        // KiB): not part of what a context costs, so it is not one of the measured.
        measure(model.get(), k_open);
        for (const int32_t n : sizes) {
            real.push_back(measure(model.get(), n));
        }
        wide_real = measure(model.get(), k_wide_far, k_wide_batch);
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
    // The session's log callback, with the test's hooks in front of it.
    llama_log_get(&g_hooks.next, &g_hooks.next_data);
    if (g_hooks.next == nullptr) {
        pp_test::die("pp_runtime_init installed no log callback");
    }
    llama_log_set(hooked_sink, g_hooks.next_data);
    const bool can_measure = !k_sanitized && real.front().allocated > 0;

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
    // The promise is one-sided: a cost is at least what a context of the size takes, so that a
    // budget the cost fits is a budget the context fits. "Not absurdly more" is the 1% here.
    // A session opened at 512 tokens creates one of 256 first and so knows two sizes; the
    // session t, opened at 256, knows one and costs every larger size as if the compute
    // reservation and the bookkeeping grew in proportion to it.
    talk two = open(gguf, 2 * k_open);
    if (!can_measure) {
        r.note("the allocator cannot be asked what it handed out here (a sanitizer's allocator): the costs are NOT compared with real allocations");
    } else {
        double worst = -1.0;
        bool under = false, bound_under = false;
        std::string table, bound_table;
        for (size_t i = 0; i < sizes.size(); i++) {
            const int64_t record = (int64_t) sizes[i] * (int64_t) sizeof(llama_token); // the session's own record of the tokens, not llama.cpp's
            const int64_t cost = pp_session_context_bytes(two.s, sizes[i]) - record;
            const int64_t bound = pp_session_context_bytes(s, sizes[i]) - record;
            // The allocator's count for one creation differs by a few KiB between two states of
            // the process (here: the test's own context and the session's): that much below is
            // the same.
            const int64_t noise = real[i].allocated / 10000;
            under = under || cost < real[i].allocated - noise;
            bound_under = bound_under || bound < real[i].allocated - noise;
            worst = std::max(worst, (double) (cost - real[i].allocated) / (double) real[i].allocated);
            table += (i ? ", " : "") + std::to_string(sizes[i]) + ": " + std::to_string(cost) + " against " + std::to_string(real[i].allocated);
            bound_table += (i ? ", " : "") + std::to_string(sizes[i]) + ": " + std::to_string(bound);
        }
        const double step_real = (double) (real[2].allocated - real[0].allocated) / (double) (sizes[2] - sizes[0]);
        const double step_cost = (double) (pp_session_context_bytes(two.s, sizes[2]) - pp_session_context_bytes(two.s, sizes[0])) / (double) (sizes[2] - sizes[0]);
        r.check(!under && worst <= 0.01, "the cost of a context is at least what the allocator handed llama.cpp for one (less 0.01%%, the count's noise), and at most 1%% more (%.3f%% at most) "
                "in a session that knows two sizes: %s bytes", 100.0 * worst, table.c_str());
        // Beyond the larger of its two sizes a cost has a margin of 2% of what the size adds
        // (see context_cost), so the costed difference may be that much more.
        r.check(step_cost >= 0.99 * step_real && step_cost <= 1.03 * step_real,
                "the difference between two sizes, which is what a caller budgets with: %.0f bytes per token costed, %.0f allocated (at least 99%%, at most 103%%)",
                step_cost, step_real);
        r.check(!bound_under, "a session that knows one size costs the larger ones as if what grows grew in proportion to the size: never below the allocation, less 0.01%% (%s bytes)",
                bound_table.c_str());
    }

    // At the model's largest context, against llama.cpp's own sizing of it from the model loaded
    // without allocating: the compute reservation it would make and the bookkeeping it really
    // allocates. The key-value buffers are rows of one size and need no measuring.
    {
        const real_context & small = sized_small, & large = sized_large;
        const int64_t cost_small = pp_session_context_bytes(two.s, k_open), cost_large = pp_session_context_bytes(two.s, n_max);
        const int64_t expected = cost_small + (int64_t) (n_max - k_open) * (file_per_token + (int64_t) sizeof(llama_token)) + (large.compute - small.compute) +
                                 (k_sanitized ? 0 : large.allocated - small.allocated);
        const double apart = (double) (cost_large - expected) / (double) expected;
        r.check(small.compute > 0 && large.compute > 0 && apart >= 0.0 && apart <= 0.03,
                "the model's largest context, %d tokens: costed at %.1f MiB, and %.1f MiB by llama.cpp's own sizing of it without allocating "
                "(compute reservation %.2f MiB there against %.2f MiB at %d tokens): %+.2f%% more (at least 0, at most 3%%)",
                n_max, (double) cost_large / k_mib, (double) expected / k_mib, (double) large.compute / k_mib, (double) small.compute / k_mib, k_open, 100.0 * apart);
    }

    // --- A context smaller than the session's batch ---

    // llama.cpp gives a context no batch larger than itself, and the compute reservation is
    // mostly the scores of one batch. A session that took the figures of such a context for
    // its own would cost every larger size too little (by the scores of the missing part of
    // the batch, and its growth with the context); its figures come from contexts at its batch
    // only, so the cost of a larger size is the same before and after.
    {
        talk w = open(gguf, k_wide_open, k_budget, nullptr, nullptr, k_wide_batch);
        const int64_t before = pp_session_context_bytes(w.s, k_wide_far);
        const int32_t down = pp_session_resize_context(w.s, k_wide_small, k_budget);
        const int64_t after = pp_session_context_bytes(w.s, k_wide_far);
        const int64_t allocated = wide_real.allocated + (int64_t) k_wide_far * (int64_t) sizeof(llama_token);
        const bool compared = can_measure && wide_real.allocated > 0;
        r.check(down == PP_STATUS_OK && pp_session_context_size(w.s) == k_wide_small && after == before && (!compared || (after >= allocated && after <= allocated + allocated / 100)),
                "a session with a batch of %d, opened at %d tokens and then given %d (a context whose batch is %d): a context of %d tokens costs %" PRId64 " bytes "
                "before and %" PRId64 " after; llama.cpp allocates %s for one at that batch",
                k_wide_batch, k_wide_open, k_wide_small, k_wide_small, k_wide_far, before, after, compared ? std::to_string(allocated).c_str() : "(not measured here)");
    }

    // --- Memory freed elsewhere while a context is created ---

    // The unreported part of a cost is measured with the allocator's count, which is the whole
    // process's. Memory that another thread frees during a creation makes that measurement too
    // small; a figure that took the smallest measurement (as the session once did) would then
    // cost every size less by as much, for the rest of the session.
    if (!can_measure) {
        r.note("the allocator cannot be asked here: memory freed during a creation is NOT tested");
    } else {
        talk m = open(gguf, 2 * k_open); // two sizes known: 256 and 512
        const int64_t unreported = real[1].allocated - real[1].reported;
        const size_t freed = (size_t) std::max<int64_t>((int64_t) 1 << 20, unreported * 3 / 4);
        void * block = std::malloc(freed); // well above the mapping threshold: its free shows in the count at once
        if (block == nullptr) {
            pp_test::die("no memory for the block to be freed");
        }
        const int64_t before_near = pp_session_context_bytes(m.s, 2 * k_open), before_far = pp_session_context_bytes(m.s, n_max);
        const int fired = g_hooks.fired;
        g_hooks.free_during_creation = block;
        // A size already measured, so that nothing but the unreported part can change.
        const int32_t status = pp_session_resize_context(m.s, k_open, k_budget);
        const bool freed_then = g_hooks.fired > fired;
        if (!freed_then) {
            std::free(g_hooks.free_during_creation);
            g_hooks.free_during_creation = nullptr;
        }
        const int64_t after_near = pp_session_context_bytes(m.s, 2 * k_open), after_far = pp_session_context_bytes(m.s, n_max);
        r.check(status == PP_STATUS_OK && freed_then && after_near >= before_near && after_far >= before_far,
                "%.1f MiB freed on another thread while a context of %d tokens was created (llama.cpp allocates %.1f MiB for a context without reporting it): "
                "a context of %d tokens costs %" PRId64 " bytes before and %" PRId64 " after, one of %d %" PRId64 " and %" PRId64 "; a figure that took the "
                "smaller measurement would cost each about %.1f MiB less",
                (double) freed / k_mib, k_open, (double) unreported / k_mib, 2 * k_open, before_near, after_near, n_max, before_far, after_far, (double) freed / k_mib);
    }

    // --- A report that cannot be read ---

    // The form of llama.cpp's report is upstream's to change. When the session cannot read it,
    // no cost is known: an opening with a budget is refused with nothing left, an opening that
    // states no limit (INT64_MAX) succeeds, and no change of size is allowed.
    {
        g_hooks.garble = true;
        int32_t refused_status = PP_STATUS_OK, opened_status = PP_STATUS_OK;
        std::string why;
        talk refused = open(gguf, 2 * k_open, k_budget, &refused_status, &why);
        talk unlimited = open(gguf, 2 * k_open, std::numeric_limits<int64_t>::max(), &opened_status);
        g_hooks.garble = false;
        bool unknown = false;
        int32_t grow = PP_STATUS_OK, grow_unlimited = PP_STATUS_OK;
        size_t replied = 0;
        if (unlimited.s != nullptr) {
            unknown = pp_session_context_bytes(unlimited.s, k_open) == -1 && pp_session_context_bytes(unlimited.s, 2 * k_open) == -1;
            grow = pp_session_resize_context(unlimited.s, 4 * k_open, k_budget);
            grow_unlimited = pp_session_resize_context(unlimited.s, 4 * k_open, std::numeric_limits<int64_t>::max());
            std::vector<llama_token> some;
            for (llama_token id = 5; some.size() < 4; id++) {
                if (!pp_session_is_end_of_generation(unlimited.s, id)) {
                    some.push_back(id);
                }
            }
            unlimited.evaluate(some);
            unlimited.configure();
            replied = unlimited.reply(2).size();
        }
        r.check(refused_status == PP_STATUS_CONTEXT_REFUSED && refused.s == nullptr && why.find("not known") != std::string::npos &&
                opened_status == PP_STATUS_OK && unknown && grow == PP_STATUS_CONTEXT_REFUSED && grow_unlimited == PP_STATUS_CONTEXT_REFUSED &&
                pp_session_context_size(unlimited.s) == 2 * k_open && replied == 2,
                "with llama.cpp's report unreadable: an opening with a budget is refused (%d, \"%s\"); one with a budget of INT64_MAX opens (%d), costs nothing it "
                "could know (-1), refuses a change of size with a budget and without one (%d, %d) and evaluates and samples as any session",
                refused_status, why.c_str(), opened_status, grow, grow_unlimited);
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
        // The order of the checks: a budget below the present size's cost is refused before
        // "it is that size already" is answered.
        const int32_t same_too_dear = pp_session_resize_context(s, k_open, pp_session_context_bytes(s, k_open) - 1);
        llama_token token = LLAMA_TOKEN_NULL;
        const int32_t sampled = pp_session_sample_next(s, &token);
        r.check(too_many == PP_STATUS_CONTEXT_REFUSED && too_dear == PP_STATUS_CONTEXT_REFUSED && none == PP_STATUS_INVALID_ARGUMENT && no_budget == PP_STATUS_INVALID_ARGUMENT &&
                same == PP_STATUS_OK && same_too_dear == PP_STATUS_CONTEXT_REFUSED && pp_session_context_size(s) == k_open && pp_session_memory_token_count(s) == count &&
                pp_session_checkpoint_size(s, mark) > 0 && sampled == PP_STATUS_OK,
                "one token more than the model was trained for, and a size whose cost is one byte above the budget: CONTEXT_REFUSED (%d, %d: \"%s\"); 0 tokens, a budget of 0: "
                "INVALID_ARGUMENT (%d, %d); the size it has: OK (%d), and CONTEXT_REFUSED with a budget one byte below its cost (%d). After all six the context is %d tokens "
                "with the same %d in memory, the checkpoint is valid and the prediction that was ready is sampled from (%d)",
                too_many, too_dear, why.c_str(), none, no_budget, same, same_too_dear, pp_session_context_size(s), count, sampled);
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

    // --- Where the reply began, across a carried change of size ---

    // A reply of 16 tokens with the presence and frequency penalties, the last of them sampled
    // and not yet evaluated, as an app leaves it; then the size is changed with the memory
    // carried over; then 16 more, which must be chosen with the penalties counting the whole
    // reply. The mistake, a reply begun anew at the change (the session's place of the reply's
    // beginning lost on the way), is what `restart` does in a session that never changes size:
    // the sampler configured again at that point.
    // A prompt that repeats a few tokens over and over, which a model goes on repeating: so
    // that whether a token of the reply's first part still counts decides what comes next.
    std::vector<llama_token> pattern;
    while (pattern.size() < 64) {
        pattern.push_back(prompt[pattern.size() % 6]);
    }
    const auto begin_reply = [&](talk & x) {
        x.configure_presence();
        x.reply(15);
        return x.sample_only();
    };
    const auto go_on = [&](talk & x, llama_token pending, bool restart) {
        x.evaluate({pending});
        if (restart) {
            x.configure_presence();
        }
        return x.reply(16);
    };
    const auto continued_fresh = [&](int32_t n_ctx, bool restart) {
        talk x = open(gguf, n_ctx);
        x.evaluate(pattern);
        const llama_token pending = begin_reply(x);
        return go_on(x, pending, restart);
    };
    {
        talk x = open(gguf, k_open);
        x.evaluate(pattern);
        const llama_token pending = begin_reply(x);
        const int32_t status = pp_session_resize_context(x.s, n_grown, k_budget);
        const bool held = pp_session_memory_token_count(x.s) == (int32_t) pattern.size() + 15 && pp_session_context_size(x.s) == n_grown;
        const std::vector<llama_token> here = go_on(x, pending, false);
        const std::vector<llama_token> right = continued_fresh(n_grown, false), wrong = continued_fresh(n_grown, true);
        r.check(status == PP_STATUS_OK && held && here == right && right != wrong,
                "a reply continued after its memory was carried from %d to %d tokens (%d): the 16 tokens after the change are those of a session that never changed "
                "size (%s), and a reply begun anew at the change would have given others (%s)",
                k_open, n_grown, status, here == right ? "the same" : "NOT the same", right != wrong ? "it would" : "NO: nothing here tells the two apart");
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
        t.evaluate(pattern);
        const int32_t kept_mark = pp_session_save_checkpoint(s);
        const llama_token pending = begin_reply(t);
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
            // The fall-back into the size there was, with the memory carried into it.
            const std::string why = pp_session_last_error(s);
            const int32_t count = pp_session_memory_token_count(s);
            r.check(status == PP_STATUS_CONTEXT_NOT_CREATED && pp_session_context_size(s) == n_old && count == (int32_t) pattern.size() + 15 &&
                    pp_session_checkpoint_size(s, kept_mark) > 0,
                    "from %d to %d tokens (%.0f MiB more) with room in the address space for half of that and the carried block: CONTEXT_NOT_CREATED (%d), the context of %d again with its %d tokens "
                    "in memory and its checkpoint (\"%s\")", n_old, n_new, (double) (cost_new - cost_old) / k_mib, status, pp_session_context_size(s), count, why.c_str());
            const std::vector<llama_token> here = go_on(t, pending, false);
            const std::vector<llama_token> right = continued_fresh(n_old, false), wrong = continued_fresh(n_old, true);
            r.check(here == right && right != wrong,
                    "and the reply goes on as in a session that never tried another size (%s); a reply begun anew there would have differed (%s)",
                    here == right ? "the same 16 tokens" : "NOT the same tokens", right != wrong ? "it would" : "NO: nothing here tells the two apart");

            // The fall-back into a half, with the memory carried into it: so little room once the
            // present context is freed (the limit is set at that moment, after the block was
            // written) that it cannot be created again, while half of it can.
            const int32_t count_half = pp_session_memory_token_count(s);
            int fired = g_hooks.fired;
            // The room is what freeing the old context gives back, less a MiB: never enough for
            // that context again (its cost is at least what it took), and enough for a half as
            // long as the allocator keeps less of what was freed than the half saves (it keeps
            // tens of MiB of heap that are not unmapped).
            g_hooks.limit_less = (int64_t) 1 << 20;
            g_hooks.limit_at_free = true;
            status = pp_session_resize_context(s, n_new, k_budget);
            bool hooked = g_hooks.fired > fired && g_hooks.limited;
            lift_hooked_limit();
            r.check(hooked && status == PP_STATUS_CONTEXT_NOT_CREATED && pp_session_context_size(s) == n_old / 2 && pp_session_memory_token_count(s) == count_half &&
                    pp_session_checkpoint_size(s, kept_mark) > 0,
                    "the same with %.0f MiB less address space than freeing the old context gave back: CONTEXT_NOT_CREATED (%d), neither %d nor %d could be had, and the session "
                    "has a context of %d (costed at %.0f MiB) with the %d tokens in memory carried into it and its checkpoint (\"%s\")",
                    (double) g_hooks.limit_less / k_mib, status, n_new, n_old, pp_session_context_size(s), (double) cost_half / k_mib, pp_session_memory_token_count(s), pp_session_last_error(s));

            // The memory lost: no room for any size at all once the context is freed.
            fired = g_hooks.fired;
            g_hooks.limit_less = std::numeric_limits<int64_t>::max() / 4;
            g_hooks.limit_at_free = true;
            status = pp_session_resize_context(s, n_new, k_budget);
            hooked = g_hooks.fired > fired && g_hooks.limited;
            lift_hooked_limit();
            const int32_t size_then = pp_session_context_size(s), count_then = pp_session_memory_token_count(s);
            const bool mark_gone = pp_session_checkpoint_size(s, kept_mark) < 0;
            int32_t n_in = 0;
            pp_evaluation_observer observer = {[](void *) { return false; }, [](int32_t, void *) {}, nullptr};
            const int32_t evaluated = pp_session_evaluate(s, prompt.data(), 1, 1, &observer, &n_in);
            const int32_t cleared = pp_session_clear_memory(s);
            r.check(hooked && status == PP_STATUS_CONTEXT_NOT_CREATED && size_then == 0 && count_then == 0 && mark_gone && evaluated == PP_STATUS_OUT_OF_MEMORY &&
                    cleared == PP_STATUS_OK && pp_session_context_size(s) == n_old,
                    "with no address space to be had at all once the context is freed: CONTEXT_NOT_CREATED (%d), no context, the carried memory and its checkpoint gone; "
                    "an evaluation answers OUT_OF_MEMORY (%d); with the limit lifted a clear creates the opening context of %d again (%d)",
                    status, evaluated, pp_session_context_size(s), cleared);
            t.memory.clear();
            t.evaluate(prompt);
            t.configure();
            r.check(t.reply(4).size() == 4, "and the session evaluates and samples as before");
        }

        // Opening is held to the same budget from nothing, by two checks (see
        // pp_session_open_path): an estimate from the first, small context before the opening
        // context is allocated, and its own report after. Each is named by its refusal.
        // The cost is the one a session that has just opened at that size states: one with
        // another history has measured other sizes. 32 KiB below it is clear of the allocator's
        // noise between two openings (a few KiB) and above the estimate before the allocation,
        // which leaves out the growth from the first, small context (more than 100 KiB for each
        // model here).
        int64_t cost_open = 0;
        {
            const talk fresh_open = open(gguf, n_old);
            cost_open = pp_session_context_bytes(fresh_open.s, n_old);
        }
        int32_t opened = PP_STATUS_OK, opened_early = PP_STATUS_OK;
        std::string why, why_early;
        talk refused = open(gguf, n_old, cost_open - 32768, &opened, &why);
        talk refused_early = open(gguf, n_old, cost_open / 2, &opened_early, &why_early);
        r.check(opened == PP_STATUS_CONTEXT_REFUSED && refused.s == nullptr && why.find("once it existed") != std::string::npos,
                "opening at %d tokens with a budget 32 KiB below their cost: CONTEXT_REFUSED (%d), no session, by the check after the allocation, which the "
                "estimate before it let through (\"%s\")", n_old, opened, why.c_str());
        r.check(opened_early == PP_STATUS_CONTEXT_REFUSED && refused_early.s == nullptr && why_early.find("before it was allocated") != std::string::npos,
                "opening at %d tokens with half their cost as the budget: CONTEXT_REFUSED (%d), no session, by the estimate before the allocation (\"%s\")",
                n_old, opened_early, why_early.c_str());
    }

    return r.finish();
}
