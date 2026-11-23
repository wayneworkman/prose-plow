// The generation session: see include/proseplow_session.h for the contract of every function.
//
// Invariants of this file:
// - No llama.cpp or ggml log line is ever written anywhere. The log callback installed by
//   pp_runtime_init drops everything, except that the error lines of a model load and the
//   backend names of the start-up are kept in memory for the caller; both happen before any
//   prompt exists.
// - No exception leaves a function declared in the header: llama.cpp reports some failures by
//   throwing, and the callers are C and JNI.
// - Everything a session allocates is owned by its struct and released by pp_session_close.
// - A buffer of this file that held text, tokens, scores or model state is overwritten before it
//   is freed. llama.cpp's buffers are not this file's to overwrite, with the exceptions that
//   destroy_context and destroy_sampler name; see pp_session_clear_memory in the header for what
//   that leaves behind.
// - No size from outside reaches llama.cpp unchecked: llama.cpp allocates for whatever it is
//   given, and an allocation that cannot be met ends the process on a phone instead of failing.

#include "proseplow_session.h"

#include <algorithm>
#include <atomic>
#include <cerrno>
#include <clocale>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <limits>
#include <memory>
#include <mutex>
#include <new>
#include <string>
#include <unordered_map>
#include <vector>

#include <dlfcn.h>
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>

// Giving free pages back to the system has a call of its own on these two C libraries.
#if defined(__ANDROID__) || defined(__GLIBC__)
#include <malloc.h>
#define PP_HAVE_MALLOC_H 1
#endif

// Thread affinity exists in this form on Linux and Android only. Elsewhere a session runs its
// unpinned thread count and never looks at affinities.
#if defined(__linux__)
#include <sched.h>
#define PP_HAVE_AFFINITY 1
#endif

namespace {

// The conversation's only sequence.
constexpr llama_seq_id k_seq = 0;

// The abort callback runs between the nodes of a graph, thousands of times per chunk. The
// caller's is_cancelled is "cheap" but may be a call into a Java VM, so it is asked at most this
// often; a cancellation is therefore noticed within about a millisecond plus one graph node.
constexpr int64_t k_cancel_poll_interval_us = 1000;

// Overwrites memory in a way the compiler may not remove although the buffer is freed next.
void wipe(void * data, size_t size) {
    if (data == nullptr || size == 0) {
        return;
    }
    std::memset(data, 0, size);
    __asm__ __volatile__("" : : "r"(data) : "memory");
}

// Asks the allocator to give the pages it holds free back to the system, which discards what
// was in them. Two reasons to ask: after a model, hundreds of megabytes which a phone needs
// now and which Android's allocator would otherwise keep for some time; and after a
// conversation, because a freed block that llama.cpp did not overwrite is readable for as long
// as its page stays with the process. It only helps for pages that are free as a whole: a block
// that shares its page with a live one stays as it is.
void release_free_pages() {
#if defined(__ANDROID__)
    // M_PURGE_ALL (Android 14) also empties the per-thread caches; M_PURGE is what came before.
    if (mallopt(M_PURGE_ALL, 0) == 0) {
        mallopt(M_PURGE, 0);
    }
#elif defined(__GLIBC__)
    malloc_trim(0);
#endif
}

//
// Logging
//

// What the log callback keeps. The mutex is only taken while something is being captured, which
// is during pp_runtime_init and during the opening of a session; at every other time the
// callback returns after one atomic load.
std::atomic<bool> g_capturing{false};
// A second, independent capture: the lines in which llama.cpp reports the size of a buffer it
// allocated for a context ("... buffer size = 12.00 MiB"), whatever their level. See report_capture.
std::atomic<bool> g_reporting{false};
std::string *     g_report = nullptr;
constexpr char    k_report_mark[] = "buffer size =";
std::mutex        g_capture_mutex;
std::string *     g_capture = nullptr;
bool              g_capture_info = false; // also informational lines (the start-up), not only errors

constexpr size_t k_capture_limit = 4096;

void log_sink(enum ggml_log_level level, const char * text, void *) {
    if (text == nullptr || (!g_capturing.load(std::memory_order_relaxed) && !g_reporting.load(std::memory_order_relaxed))) {
        return;
    }
    std::lock_guard<std::mutex> lock(g_capture_mutex);
    if (g_report != nullptr && g_report->size() < k_capture_limit && std::strstr(text, k_report_mark) != nullptr) {
        g_report->append(text, std::min(std::strlen(text), k_capture_limit - g_report->size()));
    }
    if (g_capture == nullptr) {
        return;
    }
    const bool wanted = level == GGML_LOG_LEVEL_ERROR || (g_capture_info && level == GGML_LOG_LEVEL_INFO);
    if (wanted && g_capture->size() < k_capture_limit) {
        g_capture->append(text, std::min(std::strlen(text), k_capture_limit - g_capture->size()));
    }
}

// Captures log lines into a string for the lifetime of the object.
class log_capture {
  public:
    log_capture(std::string & target, bool info) {
        std::lock_guard<std::mutex> lock(g_capture_mutex);
        g_capture = &target;
        g_capture_info = info;
        g_capturing.store(true);
    }
    ~log_capture() {
        std::lock_guard<std::mutex> lock(g_capture_mutex);
        g_capturing.store(false);
        g_capture = nullptr;
    }
    log_capture(const log_capture &) = delete;
    log_capture & operator=(const log_capture &) = delete;
};

// Collects llama.cpp's own report of the buffers of a context for the lifetime of the object,
// which is one llama_init_from_model. It works beside a log_capture, not instead of one.
class report_capture {
  public:
    explicit report_capture(std::string & target) {
        std::lock_guard<std::mutex> lock(g_capture_mutex);
        g_report = &target;
        g_reporting.store(true);
    }
    ~report_capture() {
        std::lock_guard<std::mutex> lock(g_capture_mutex);
        g_reporting.store(false);
        g_report = nullptr;
    }
    report_capture(const report_capture &) = delete;
    report_capture & operator=(const report_capture &) = delete;
};

// A message for a person reading an error screen: one line of printable ASCII, bounded.
std::string tidy(const std::string & text, size_t limit = 200) {
    std::string out;
    for (const char c : text) {
        if (c == '\n' || c == '\r') {
            if (!out.empty()) {
                break; // the first line says what went wrong; later ones repeat it from further out
            }
            continue;
        }
        out.push_back(c >= 0x20 && c < 0x7f ? c : '?');
        if (out.size() >= limit) {
            break;
        }
    }
    while (!out.empty() && out.back() == ' ') {
        out.pop_back();
    }
    return out;
}

bool mentions_memory(const std::string & text) {
    for (const char * hint : {"bad_alloc", "failed to allocate", "unable to allocate", "out of memory", "Cannot allocate memory"}) {
        if (text.find(hint) != std::string::npos) {
            return true;
        }
    }
    return false;
}

// Two overloads on purpose: the one for literals allocates nothing, so it is safe to call while
// handling an out-of-memory condition.
void put_error(char * error, size_t error_size, const char * message) {
    if (error == nullptr || error_size == 0) {
        return;
    }
    const size_t n = std::min(std::strlen(message), error_size - 1);
    std::memcpy(error, message, n);
    error[n] = '\0';
}

void put_error(char * error, size_t error_size, const std::string & message) {
    put_error(error, error_size, message.c_str());
}

//
// The process
//

std::once_flag g_runtime_once;
int32_t        g_runtime_status = PP_STATUS_LOAD_FAILED;
bool           g_runtime_ready = false;
std::string    g_runtime_description;

// "load_backend: loaded CPU backend from /some/dir/libggml-cpu-android_armv8.6_1.so" becomes
// "CPU (libggml-cpu-android_armv8.6_1.so)". The directory is dropped: it names the installation.
std::string backends_from_log(const std::string & log) {
    static const std::string k_loaded = "loaded ";
    static const std::string k_from = " backend from ";
    std::string out;
    size_t line_start = 0;
    while (line_start < log.size()) {
        size_t line_end = log.find('\n', line_start);
        if (line_end == std::string::npos) {
            line_end = log.size();
        }
        const std::string line = log.substr(line_start, line_end - line_start);
        const size_t loaded = line.find(k_loaded);
        const size_t from = line.find(k_from);
        if (loaded != std::string::npos && from != std::string::npos && from > loaded) {
            const std::string name = line.substr(loaded + k_loaded.size(), from - loaded - k_loaded.size());
            std::string file = line.substr(from + k_from.size());
            const size_t slash = file.find_last_of('/');
            if (slash != std::string::npos) {
                file = file.substr(slash + 1);
            }
            out += (out.empty() ? "" : ", ") + name + " (" + file + ")";
        }
        line_start = line_end + 1;
    }
    return out;
}

// Loads the candidate that suits this processor best: what ggml_backend_load_best does for the
// files of a directory, for libraries that are only known by name. A library that cannot be
// loaded, or that has no score function, is not a candidate.
void load_best_backend(const char * const * libraries, int32_t n_libraries) {
    std::vector<std::pair<int, std::string>> scored;
    for (int32_t i = 0; i < n_libraries; i++) {
        if (libraries[i] == nullptr) {
            continue;
        }
        void * handle = dlopen(libraries[i], RTLD_NOW | RTLD_LOCAL);
        if (handle == nullptr) {
            continue;
        }
        const auto score = (int (*)(void)) dlsym(handle, "ggml_backend_score");
        const int value = score != nullptr ? score() : 0;
        dlclose(handle);
        if (value > 0) {
            scored.emplace_back(value, libraries[i]);
        }
    }
    // Best first; among equals the name decides, so that the choice does not depend on the order
    // in which the caller happened to list them.
    std::sort(scored.begin(), scored.end(), [](const auto & a, const auto & b) {
        return a.first != b.first ? a.first > b.first : a.second < b.second;
    });
    for (const auto & candidate : scored) {
        if (ggml_backend_load(candidate.second.c_str()) != nullptr) {
            return;
        }
    }
}

void runtime_init(const char * const * backend_libraries, int32_t n_backend_libraries) {
    try {
        std::string log;
        {
            log_capture capture(log, true);
            llama_log_set(log_sink, nullptr);
            std::setlocale(LC_NUMERIC, "C");
            if (backend_libraries != nullptr && n_backend_libraries > 0) {
                // Must come before llama_backend_init, which would otherwise look for backend
                // libraries next to the executable and in the current directory.
                load_best_backend(backend_libraries, n_backend_libraries);
            }
            llama_backend_init();
        }
        if (ggml_backend_reg_count() == 0) {
            g_runtime_description = "no compute backend could be loaded";
            return;
        }
        const std::string backends = backends_from_log(log);
        const char * features = llama_print_system_info();
        g_runtime_description = tidy((backends.empty() ? std::string("backends linked in") : "backends: " + backends) + "; " +
                                     (features != nullptr ? features : ""), 1000);
        g_runtime_ready = true;
        g_runtime_status = PP_STATUS_OK;
    } catch (...) {
        g_runtime_description = "llama.cpp could not be initialised";
    }
}

} // namespace

//
// The session
//

struct pp_checkpoint {
    uint8_t * data = nullptr;
    size_t    size = 0;
    int32_t   n_tokens = 0; // the memory's length when the state was saved
};

// What a context of this model costs, as far as it was learnt from the contexts created so far.
//
// The numbers are llama.cpp's own: at every llama_init_from_model it logs the size of each
// buffer it allocated (key-value cells, recurrent state, the compute reservation, the output),
// and those lines are read (see read_report). The key-value buffers are exactly as many rows
// as the context has tokens, so their bytes per token are known from one context. The compute
// reservation grows with the context too, by the attention mask, and its growth is taken from
// two contexts of different sizes: the small one a session creates first for this purpose,
// and the largest created since. What llama.cpp allocates without reporting it (the
// scheduler's and the cells' bookkeeping) is measured through the allocator's statistics where
// they can be asked.
//
// Every figure comes from contexts created at the session's batch (n_batch_max) only. A context
// smaller than the batch has a batch of its own size and a compute reservation to match (the
// scores of a whole batch are most of it), so its report would make every larger size cost too
// little; it is not used at all, and a size below the batch is costed at the whole batch, too
// high and never too low. Every figure errs towards costing more, so that a budget that a cost
// fits is a budget the context fits: see pp_session_context_bytes in the header.
struct pp_context_cost {
    bool    known = false;       // false until a report was understood: no size can be costed then
    double  kv_per_token = 0.0;  // bytes of the key-value buffers per token of context
    int32_t kv_from = 0;         // the size of the context that figure was taken from (the largest)
    int64_t fixed = 0;           // the buffers whose size does not depend on the context: the largest seen
    int32_t n_low = 0;           // the smaller context whose compute reservation is known
    int64_t compute_low = 0;
    int32_t n_high = 0;          // and the larger; the same as n_low while only one is known
    int64_t compute_high = 0;
    // What llama.cpp allocated and did not report: max(unreported_fixed + unreported_per_token
    // * n, unreported_max) once two sizes were measured, unreported_max before. Each figure only
    // ever rises, so no later measurement can lower a cost (see learn_cost).
    bool    unreported_known = false;
    int32_t unreported_n0 = 0;          // the size of the first measurement
    int64_t unreported_first = 0;       // the largest measurement at that size
    bool    unreported_slope_known = false;
    double  unreported_per_token = 0.0; // from the first two sizes measured; set once
    double  unreported_fixed = 0.0;     // the largest (measurement - per token * size) since
    int64_t unreported_max = 0;         // the largest measurement of all
};

// The caller's load progress callback, and whether it asked for the load to be abandoned.
struct pp_load_progress {
    pp_load_progress_fn fn = nullptr;
    void *              user_data = nullptr;
    bool                cancelled = false;
};

struct pp_session {
    llama_model *            model = nullptr;
    llama_context *          ctx = nullptr;
    const llama_vocab *      vocab = nullptr;
    llama_batch_ext *        batch = nullptr;
    // The sampler, in two parts that are always there together or not at all: the chain that
    // changes and narrows the scores (bans, temperature, truncations), and the sampler that
    // chooses among what is left (random or greedy). They are applied one after the other
    // where llama.cpp would apply one chain, so that the session can look at the scores in
    // between: see pp_session_sample_next. Neither holds a token of the conversation: the
    // penalties, which need them, are the session's own (see apply_penalties).
    llama_sampler *          sampler = nullptr;
    llama_sampler *          chooser = nullptr;
    pp_peacebell_tokenizer * peacebell = nullptr; // set when the model needs the front-end

    int32_t n_vocab = 0;
    int32_t n_batch = 0;
    int32_t n_ctx = 0; // of the context as created; kept when there is no context

    // The sizes a context of this session can have: the most the model was trained for, the
    // size the session was opened with (a forgotten conversation gives the memory back down
    // to it), and the batch asked for at open (a context's batch is the smaller of that and
    // its own size).
    int32_t n_ctx_max = 0;
    int32_t n_ctx_open = 0;
    int32_t n_batch_max = 0;
    pp_context_cost cost;

    // How the context is created. Kept because forgetting a conversation frees the context and
    // creates it again (see destroy_context); the pointers in it are NULL or refer to the model.
    llama_context_params cparams;

    // A token the model must never be given as input, or LLAMA_TOKEN_NULL. Boris-2 treats one
    // sequence as one document: its graph asserts that the end-of-text token is not an input,
    // and a failed assertion ends the process. The session answers with an error instead.
    llama_token refused_input = LLAMA_TOKEN_NULL;

    // True when llama.cpp can take an interrupted micro-batch out of the memory again, which is
    // the case for attention-only memory. Only then is the abort callback installed.
    bool can_abort = false;

    // The window of the model's windowed layers in tokens (Shard-2: 256), 0 for a model without
    // such layers. Those layers keep their keys from some position on, and a cut of the memory
    // is only right while they still hold the window before it: see pp_session_rewind_to.
    int32_t n_window = 0;

    // A prediction is ready from the end of a complete evaluation until it is sampled or the
    // memory changes. has_prediction_data says that the context's output buffer holds scores
    // that were not overwritten yet, which is the case exactly from a llama_process call that
    // computed an output until wipe_prediction. Only while it is set may llama.cpp be asked for
    // the buffer: after a call without output it has none to give, and a build of llama.cpp
    // with assertions ends the process over the question.
    bool prediction_ready = false;
    bool has_prediction_data = false;

    // The sampler as it was configured, kept so that the chain can be built again after a
    // conversation is forgotten (banned_tokens in it points into banned_list) and because the
    // penalties are computed from it, and what the session checks itself: the banned tokens as
    // one flag per token of the vocabulary (empty when none is banned).
    bool                     sampler_configured = false;
    pp_sampler_config        sampler_config = {};
    std::vector<llama_token> banned_list;
    std::vector<uint8_t>     banned;

    // The ids of the tokens in memory, in order: the entry at an index is the token at that
    // position, and there are always exactly as many as the memory has tokens. llama.cpp keeps
    // no such list. The penalties are computed from this one at every sampling, which is what
    // lets them cover the prompt and follow the memory when its end is removed, a checkpoint
    // is restored or the conversation is forgotten. Its storage is never left behind with ids
    // in it: it grows through reserve_history only, and what is cut off is overwritten.
    std::vector<llama_token> history;
    // Where the reply begins in the memory, for the penalties that count the reply alone: the
    // position the configuration named (pp_sampler_config.reply_start), or else the length of
    // the memory when the first token was sampled after the sampler was configured or the
    // conversation forgotten, -1 until then. It may lie beyond the memory (apply_penalties
    // clamps it).
    int32_t reply_start = -1;

    // The candidate list a token is sampled from: every token with its score. The session's own,
    // one per vocabulary and allocated when the session opens, so that sampling allocates nothing
    // and the list can be overwritten as soon as the token is chosen.
    std::vector<llama_token_data> candidates;

    std::unordered_map<int32_t, pp_checkpoint> checkpoints;
    int32_t next_checkpoint = 0;

    // llama.cpp keeps a copy of the model parameters, pointers included, for as long as the
    // model lives; what those pointers refer to therefore lives here and not on a stack.
    pp_load_progress progress;

    // Threads (see pp_session_params). The pool is created by the first evaluation, on the
    // thread that evaluates: ggml pins the thread that creates a pinned pool.
    int32_t              n_threads_unpinned = 1;
    std::vector<int32_t> pinned_cpus; // ascending
    ggml_threadpool_t (*threadpool_new)(struct ggml_threadpool_params *) = nullptr;
    void              (*threadpool_free)(ggml_threadpool_t) = nullptr;
    ggml_threadpool_t    threadpool = nullptr;
    // Pools that were replaced. ggml's CPU backend remembers the pool it last computed with and
    // pauses it when it is handed another one, at the next computation; until that computation
    // has happened a replaced pool must stay alive, or the pause would touch freed memory.
    std::vector<ggml_threadpool_t> retired_threadpools;
    bool                 threads_ready = false;
    bool                 pinned = false;
    int32_t              n_threads_now = 1;
#ifdef PP_HAVE_AFFINITY
    // The affinity the session left on the calling thread. While the thread still has it,
    // nothing has moved the process to another set of cores since the pool was built.
    cpu_set_t            caller_mask;
    bool                 caller_mask_known = false;
#endif

    // The evaluation in progress, for the abort callback.
    const pp_evaluation_observer * observer = nullptr;
    bool    cancelled = false;
    int64_t last_poll_us = 0;

    std::string last_error;
};

namespace {

// Records what went wrong and passes the status through. Two overloads on purpose: a literal
// is not turned into a std::string at the call site, where a failed allocation would be an
// exception outside any handler; the one for composed messages is only used inside one.
int32_t fail(pp_session * s, int32_t status, const char * message) {
    try {
        s->last_error = message;
    } catch (...) {
        s->last_error.clear();
    }
    return status;
}

int32_t fail(pp_session * s, int32_t status, const std::string & message) {
    return fail(s, status, message.c_str());
}

// The number of tokens in memory; none for a session whose context could not be created again.
int32_t memory_count(const pp_session * s) {
    return s->ctx == nullptr ? 0 : (int32_t) llama_memory_seq_pos_max(llama_get_memory(s->ctx), k_seq) + 1;
}

// The answer of every call that needs a context when there is none (see pp_session_clear_memory).
int32_t fail_no_context(pp_session * s) {
    return fail(s, PP_STATUS_OUT_OF_MEMORY, "the session has no context: it could not be created again after the conversation was forgotten");
}

// Overwrites the scores of the latest prediction where llama.cpp keeps them.
void wipe_prediction(pp_session * s) {
    if (s->has_prediction_data && s->ctx != nullptr) {
        float * logits = llama_get_logits_ith(s->ctx, -1);
        if (logits != nullptr) {
            wipe(logits, (size_t) s->n_vocab * sizeof(float));
        }
    }
    s->has_prediction_data = false;
}

// The bytes the process's allocator has handed out and not got back, or -1 where it cannot be
// asked. It counts the whole process, so a difference taken around one call is only as good as
// the other threads were quiet meanwhile: used for one figure that tolerates that.
int64_t allocated_bytes() {
#if defined(__GLIBC__)
    const struct mallinfo2 info = mallinfo2();
    return (int64_t) info.uordblks + (int64_t) info.hblkhd;
#elif defined(__ANDROID__)
    const struct mallinfo info = mallinfo();
    return (int64_t) info.uordblks;
#else
    return -1;
#endif
}

// Whether the address space has room for one mapping of this many bytes: it is mapped, never
// touched, and unmapped again. llama.cpp is only asked for a context after this has answered
// yes, because not every allocation of its own that fails is reported: ggml ends the process
// when one of its bookkeeping buffers cannot be had (GGML_ASSERT in ggml_init). A size that
// cannot be mapped as a whole therefore never reaches it. The test asks for a little more
// than a context needs: the allocator keeps some of what an earlier context freed and serves
// the next one from it, which a new mapping cannot count on (tens of MiB were measured). So
// it errs towards "no room" when the address space is within that of its end, and never the
// other way.
bool can_reserve(int64_t bytes) {
    if (bytes <= 0) {
        return true;
    }
    void * probe = mmap(nullptr, (size_t) bytes, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (probe == MAP_FAILED) {
        return false;
    }
    munmap(probe, (size_t) bytes);
    return true;
}

// The buffers of one context as llama.cpp reported them when it created it, in bytes.
struct context_report {
    int64_t kv = 0;      // every "KV buffer size" line (a model with windowed layers has two)
    int64_t compute = 0; // "compute buffer size"
    int64_t fixed = 0;   // every other buffer: the recurrent state, the output
    bool    complete = false;
};

// Reads the lines a report_capture collected. Each is "<who>: <device> <name> buffer size =
// <number> MiB"; the number is all that is taken from it, and the name only to tell the two
// buffers that grow with the context from the rest. A report without a key-value buffer or
// without a compute buffer is not one: the form of the lines has changed, and nothing is
// guessed from what is left (see "Moving the upstream pin" in native/README.md).
context_report read_report(const std::string & text) {
    context_report report;
    bool has_kv = false, has_compute = false;
    for (size_t at = 0; at < text.size();) {
        size_t end = text.find('\n', at);
        end = end == std::string::npos ? text.size() : end;
        const std::string line = text.substr(at, end - at);
        at = end + 1;
        const size_t mark = line.find(k_report_mark);
        if (mark == std::string::npos) {
            continue;
        }
        const char * number = line.c_str() + mark + sizeof(k_report_mark) - 1;
        char * after = nullptr;
        const double mib = std::strtod(number, &after);
        if (after == number || std::strncmp(after, " MiB", 4) != 0 || !(mib >= 0.0) || mib > 1e9) {
            return context_report();
        }
        const int64_t bytes = (int64_t) std::llround(mib * 1048576.0);
        if (line.find("KV buffer size") != std::string::npos) {
            report.kv += bytes;
            has_kv = true;
        } else if (line.find("compute buffer size") != std::string::npos) {
            report.compute += bytes;
            has_compute = true;
        } else {
            report.fixed += bytes;
        }
    }
    report.complete = has_kv && has_compute;
    return report;
}

// Takes what the creation of a context of n tokens has shown into the session's knowledge of
// what a context costs. `allocated` is what the allocator handed out during the creation, or
// a negative number when that is not known.
void learn_cost(pp_session * s, int32_t n, int32_t batch, const std::string & text, int64_t allocated) {
    const context_report report = read_report(text);
    pp_context_cost & cost = s->cost;
    // A context below the session's batch tells nothing about the contexts at the batch, which
    // every larger size has (see pp_context_cost): its report neither replaces nor resets what
    // is known.
    if (!report.complete || n < 1 || batch != s->n_batch_max) {
        return;
    }
    if (n >= cost.kv_from) {
        cost.kv_per_token = (double) report.kv / (double) n;
        cost.kv_from = n;
    }
    cost.fixed = std::max(cost.fixed, report.fixed);
    if (cost.n_high == 0) {
        cost.n_low = cost.n_high = n;
        cost.compute_low = cost.compute_high = report.compute;
    } else if (n >= cost.n_high) {
        if (cost.n_low == cost.n_high && n > cost.n_high) {
            cost.n_low = cost.n_high;
            cost.compute_low = cost.compute_high;
        }
        cost.n_high = n;
        cost.compute_high = report.compute;
    } else if (n <= cost.n_low) {
        if (cost.n_low == cost.n_high) {
            cost.n_high = cost.n_low;
            cost.compute_high = cost.compute_low;
        }
        cost.n_low = n;
        cost.compute_low = report.compute;
    }
    // The rest of what the allocator handed out: a fixed part (the scheduler's bookkeeping)
    // and a part per token (the cells' bookkeeping). The allocator's count is the whole
    // process's, so another thread that frees memory during the creation makes a measurement
    // too small and one that allocates makes it too large. The figures therefore only ever
    // rise: a measurement that is too small is outweighed by the earlier ones, one that is too
    // large costs more than needed, and no measurement lowers a cost that was given before.
    // The part per token is fitted once, from the first two sizes, which a session creates one
    // straight after the other when it opens; fitting it again from later pairs would let one
    // disturbed measurement lower it, or multiply a disturbance by the context's size.
    const int64_t rest = allocated - (report.kv + report.compute + report.fixed);
    if (allocated > 0 && rest >= 0 && rest < ((int64_t) 1 << 32)) {
        if (!cost.unreported_known) {
            cost.unreported_known = true;
            cost.unreported_n0 = n;
            cost.unreported_first = rest;
        } else if (!cost.unreported_slope_known && n == cost.unreported_n0) {
            cost.unreported_first = std::max(cost.unreported_first, rest);
        } else if (!cost.unreported_slope_known) {
            cost.unreported_per_token = std::max(0.0, (double) (rest - cost.unreported_first) / (double) (n - cost.unreported_n0));
            cost.unreported_slope_known = true;
            cost.unreported_fixed = std::max((double) cost.unreported_first - cost.unreported_per_token * (double) cost.unreported_n0,
                                             (double) rest - cost.unreported_per_token * (double) n);
        } else {
            cost.unreported_fixed = std::max(cost.unreported_fixed, (double) rest - cost.unreported_per_token * (double) n);
        }
        cost.unreported_max = std::max(cost.unreported_max, rest);
    }
    cost.known = true;
}

// llama.cpp makes every context a whole number of these many tokens: it rounds the size it is
// asked for up (GGML_PAD(n_ctx, 256) where llama_context settles its parameters). The session
// rounds the same way before it costs a size or compares two, so that the size it reasons
// about is the size that will exist; what exists is read back from llama.cpp all the same.
constexpr int32_t k_ctx_step = 256;

// What is added, in proportion, to the part of a cost that lies beyond the largest context
// created (see context_cost).
constexpr double k_extrapolation_margin = 0.02;

int32_t rounded_ctx(int32_t n) {
    return n > std::numeric_limits<int32_t>::max() - k_ctx_step ? n : (n + k_ctx_step - 1) / k_ctx_step * k_ctx_step;
}

// What a context of n tokens costs in bytes: what llama.cpp allocates for it at this session's
// batch, with the session's own record of the tokens. -1 when it is not known.
//
// The compute reservation and the unreported bookkeeping are each at most proportional to the
// size beyond a size measured (every buffer in them is a fixed part and a part that grows with
// the context, neither negative), and never more than the larger of two sizes below it costs. So:
// - with one size measured, a larger size is costed as if both grew in proportion to it. That
//   is far too high (the fixed part of each is most of it), and it is meant to be: what they
//   grow by is not known until a second size was created, and a cost must not be too low. With
//   `estimate` set they are taken as not growing at all instead, which can be too low: only
//   the first of the two checks of an opening uses that (see pp_session_open_path);
// - with two, a size between them is costed on the line through them, a size beyond the larger
//   on that line continued (the growth is the attention mask's, which grows in a straight line),
//   and a size below the smaller at the smaller's figure.
int64_t context_cost(const pp_session * s, int32_t n, bool estimate = false) {
    const pp_context_cost & cost = s->cost;
    if (!cost.known || n < 1) {
        return -1;
    }
    n = rounded_ctx(n);
    const auto line = [&](int32_t m) {
        const double beyond = m > cost.n_high && !estimate ? (double) m / (double) cost.n_high : 1.0; // the proportion while one size is known
        double compute = (double) cost.compute_high;
        if (cost.n_high > cost.n_low) {
            const double per_token = std::max(0.0, (double) (cost.compute_high - cost.compute_low) / (double) (cost.n_high - cost.n_low));
            compute = m <= cost.n_low ? (double) cost.compute_low : compute + per_token * (double) (m - cost.n_high);
        } else {
            compute *= beyond;
        }
        // Before two sizes are measured the largest measurement, in proportion as above;
        // afterwards the line through them, and never below the largest measurement: a size
        // below one that was measured is costed at least at that measurement, too high by its
        // few bytes per token.
        double unreported = 0.0;
        if (cost.unreported_known) {
            unreported = (double) cost.unreported_max;
            if (cost.unreported_slope_known) {
                unreported = std::max(unreported, cost.unreported_fixed + cost.unreported_per_token * (double) m);
            } else if (m > cost.unreported_n0 && !estimate) {
                unreported *= (double) m / (double) cost.unreported_n0;
            }
        }
        return cost.kv_per_token * (double) m + compute + (double) cost.fixed + unreported + (double) m * sizeof(llama_token);
    };
    double bytes = line(n);
    // Beyond the largest size created the line is continued, and what grows can grow faster
    // there than between the sizes measured: the compute reservation is the largest of several
    // buffers, and a faster growing one can take over (measured: up to 0.3% short at a model's
    // largest context, from contexts of 256 and 512 tokens). What a size beyond adds to the cost
    // is therefore counted k_extrapolation_margin higher. A margin, measured, not a bound.
    if (!estimate && n > cost.n_high) {
        bytes += k_extrapolation_margin * (bytes - line(cost.n_high));
    }
    return bytes >= 9.0e18 ? std::numeric_limits<int64_t>::max() : (int64_t) std::llround(bytes);
}

// Makes room in the record of the memory's tokens for n of them in all. A vector that grew by
// itself would free its old storage with the ids still in it; this overwrites it first. Throws
// std::bad_alloc, with the record as it was.
void reserve_history(pp_session * s, size_t n) {
    if (n <= s->history.capacity()) {
        return;
    }
    std::vector<llama_token> larger;
    larger.reserve(std::max(n, 2 * s->history.capacity()));
    larger.assign(s->history.begin(), s->history.end());
    wipe(s->history.data(), s->history.size() * sizeof(llama_token));
    s->history.swap(larger);
}

// Shortens the record of the memory's tokens to its first n, overwriting the rest.
void cut_history(pp_session * s, size_t n) {
    if (n < s->history.size()) {
        wipe(s->history.data() + n, (s->history.size() - n) * sizeof(llama_token));
        s->history.resize(n);
    }
}

// For after a call that can only have shortened the memory: shortens the record with it.
// Never throws.
void follow_memory(pp_session * s) {
    try {
        cut_history(s, (size_t) std::max(0, memory_count(s)));
    } catch (...) {
        // The memory cannot be asked. With no record at all, sampling says so (see
        // pp_session_sample_next) where a record of the wrong length would be believed.
        cut_history(s, 0);
    }
}

void free_checkpoint(pp_checkpoint & checkpoint) {
    wipe(checkpoint.data, checkpoint.size);
    std::free(checkpoint.data);
    checkpoint = pp_checkpoint();
}

void free_all_checkpoints(pp_session * s) {
    for (auto & entry : s->checkpoints) {
        free_checkpoint(entry.second);
    }
    s->checkpoints.clear();
}

// Asks the caller whether to stop, at most once per evaluation with the answer "yes".
bool poll_cancelled(pp_session * s) {
    if (!s->cancelled && s->observer->is_cancelled(s->observer->user_data)) {
        s->cancelled = true;
    }
    return s->cancelled;
}

// llama.cpp's abort callback. It runs on the thread that called llama_process (ggml polls it
// from the first compute thread, which is the caller's), so the caller's callback is made on
// the calling thread as the contract requires.
bool abort_callback(void * data) {
    pp_session * s = (pp_session *) data;
    if (s->observer == nullptr) {
        return false;
    }
    if (s->cancelled) {
        return true;
    }
    const int64_t now = llama_time_us();
    if (now - s->last_poll_us < k_cancel_poll_interval_us) {
        return false;
    }
    s->last_poll_us = now;
    return poll_cancelled(s);
}

bool progress_trampoline(float fraction, void * data) {
    pp_load_progress * progress = (pp_load_progress *) data;
    if (progress->cancelled) {
        return false;
    }
    if (progress->fn != nullptr && !progress->fn(fraction, progress->user_data)) {
        progress->cancelled = true;
        return false;
    }
    return true;
}

// An empty device list. Static for the reason given at pp_session::progress.
ggml_backend_dev_t g_no_devices[] = {nullptr};

#define PP_STRINGIFY_VALUE(value) #value
#define PP_STRINGIFY(value) PP_STRINGIFY_VALUE(value)

// What is wrong with the parameters of a session, or NULL when nothing is. This runs before a
// file is opened, and it is where the batch is held to its limit. The context has no limit
// here: what it may be is the model's to say and the budget's (see open_session and
// pp_session_resize_context), and both are asked before anything is allocated. llama.cpp would
// take a context of two thousand million tokens as given and allocate for it until the system
// ends the process.
const char * params_problem(const pp_session_params * params) {
    if (params == nullptr) {
        return "no session parameters";
    }
    if (params->n_ctx < 1) {
        return "the context size is below 1 token";
    }
    if (params->context_budget_bytes < 1) {
        return "the budget for the context is below 1 byte";
    }
    if (params->n_batch < 1 || params->n_batch > PP_BATCH_TOKENS_MAX) {
        return "the batch size is outside 1 to " PP_STRINGIFY(PP_BATCH_TOKENS_MAX) " tokens";
    }
    if (params->n_threads < 1 || params->n_threads > GGML_MAX_N_THREADS) {
        return "the number of threads is outside 1 to " PP_STRINGIFY(GGML_MAX_N_THREADS);
    }
    if (params->n_pinned_cpus < 0 || params->n_pinned_cpus > GGML_MAX_N_THREADS || (params->pinned_cpus == nullptr && params->n_pinned_cpus > 0)) {
        return "the list of CPUs to pin to is missing or too long";
    }
    if (params->load_mode != PP_LOAD_MODE_READ && params->load_mode != PP_LOAD_MODE_MMAP) {
        return "the load mode is not one of pp_load_mode";
    }
    for (int32_t i = 0; i < params->n_pinned_cpus; i++) {
        // ggml's CPU mask has GGML_MAX_N_THREADS entries; a CPU beyond it cannot be named.
        if (params->pinned_cpus[i] < 0 || params->pinned_cpus[i] >= GGML_MAX_N_THREADS) {
            return "a CPU to pin to is outside the numbers ggml can name";
        }
        for (int32_t j = 0; j < i; j++) {
            if (params->pinned_cpus[j] == params->pinned_cpus[i]) {
                return "a CPU to pin to is listed twice";
            }
        }
    }
    return nullptr;
}

#ifdef PP_HAVE_AFFINITY
// Lifts any affinity from the calling thread: asks for every CPU, of which the kernel grants
// those the process is allowed at present. Returns false when affinities cannot be used here.
bool allowed_cpus(cpu_set_t & allowed) {
    cpu_set_t all;
    CPU_ZERO(&all);
    for (int cpu = 0; cpu < CPU_SETSIZE; cpu++) {
        CPU_SET(cpu, &all);
    }
    return sched_setaffinity(0, sizeof(all), &all) == 0 && sched_getaffinity(0, sizeof(allowed), &allowed) == 0;
}
#endif

// Replaces the session's thread pool with a new one: one thread on each listed CPU when pin is
// set, the unpinned thread count otherwise. The pool that was in use is retired, not freed (see
// pp_session::retired_threadpools). Returns whether the new pool is a pinned one.
bool install_threadpool(pp_session * s, bool pin) {
    int32_t n_threads = pin ? (int32_t) s->pinned_cpus.size() : s->n_threads_unpinned;
    ggml_threadpool_t pool = nullptr;
    if (s->threadpool_new != nullptr) {
        struct ggml_threadpool_params tpp = ggml_threadpool_params_default(n_threads);
        if (pin) {
            for (const int32_t cpu : s->pinned_cpus) {
                tpp.cpumask[cpu] = true;
            }
            // Strict: every thread gets one of the listed CPUs to itself, instead of all threads
            // sharing the whole set. ggml gives the last one to the thread that creates the pool.
            tpp.strict_cpu = true;
        }
        pool = s->threadpool_new(&tpp);
    }
    if (pool == nullptr) {
        // Without a pool of ours llama.cpp makes its own for every graph; it cannot be pinned.
        pin = false;
        n_threads = s->n_threads_unpinned;
        llama_detach_threadpool(s->ctx);
    } else {
        llama_attach_threadpool(s->ctx, pool, pool);
    }
    llama_set_n_threads(s->ctx, n_threads, n_threads);
    if (s->threadpool != nullptr) {
        s->retired_threadpools.push_back(s->threadpool);
    }
    s->threadpool = pool;
    s->pinned = pin;
    s->n_threads_now = n_threads;
    return pin;
}

// Makes the thread pool fit what the system allows at this moment. Called before every chunk
// of an evaluation, on the evaluating thread; it returns after one system call while nothing
// has changed.
void prepare_threads(pp_session * s) {
#ifdef PP_HAVE_AFFINITY
    if (s->threads_ready) {
        if (!s->caller_mask_known) {
            return; // nothing is pinned and nothing can be: the pool built the first time stays
        }
        cpu_set_t current;
        if (sched_getaffinity(0, sizeof(current), &current) == 0 && CPU_EQUAL(&current, &s->caller_mask)) {
            return;
        }
    }
    // A session that was given no CPUs to pin to never touches an affinity.
    bool usable = false;
    bool pin = false;
    if (!s->pinned_cpus.empty() && s->threadpool_new != nullptr) {
        cpu_set_t allowed;
        usable = allowed_cpus(allowed);
        pin = usable;
        for (const int32_t cpu : s->pinned_cpus) {
            pin = pin && cpu < CPU_SETSIZE && CPU_ISSET(cpu, &allowed);
        }
    }
    install_threadpool(s, pin);

    // The calling thread is the pool's first thread, and ggml pinned it when it created the
    // pool. Its mask is only read here, never set: a second opinion about which CPU is the
    // caller's would put two threads on one core, which is the very stall that pinning is there
    // to prevent.
    cpu_set_t mask;
    bool known = usable && sched_getaffinity(0, sizeof(mask), &mask) == 0;
    if (s->pinned && !(known && CPU_COUNT(&mask) == 1)) {
        // ggml did not manage to pin the calling thread after all. A pool with one thread adrift
        // among pinned ones is the worst of both, so it is replaced by free threads at once.
        cpu_set_t allowed;
        usable = allowed_cpus(allowed);
        install_threadpool(s, false);
        known = usable && sched_getaffinity(0, sizeof(mask), &mask) == 0;
    }
    s->caller_mask_known = known;
    if (known) {
        s->caller_mask = mask;
    }
#else
    if (s->threads_ready) {
        return;
    }
    install_threadpool(s, false);
#endif
    s->threads_ready = true;
}

// Frees the pools that were replaced. Only to be called when the CPU backend can no longer
// refer to them: after a computation with their successor, or after the context is gone.
void free_retired_threadpools(pp_session * s) {
    for (const ggml_threadpool_t pool : s->retired_threadpools) {
        s->threadpool_free(pool);
    }
    s->retired_threadpools.clear();
}

// Tells a context that was just created about the thread pool in use. The pool outlives a
// context: it holds nothing of a conversation, and its threads are where they should be.
void attach_threads(pp_session * s) {
    if (!s->threads_ready) {
        return; // no evaluation yet: the first one sets the threads up
    }
    if (s->threadpool != nullptr) {
        llama_attach_threadpool(s->ctx, s->threadpool, s->threadpool);
    }
    llama_set_n_threads(s->ctx, s->n_threads_now, s->n_threads_now);
}

// Frees the batch and the context, after overwriting what they hold of a conversation and can
// be reached: the token ids in the batch (a batch has no call for that, so it is refilled with
// zeros), the scores of the last prediction, and the key-value and state buffers of the memory
// (the true of llama_memory_clear). Everything else the context owns is freed as it is: the
// copies of the tokens of the chunks it evaluated and the activations of the last chunk among
// them. The session's own record of the memory's tokens is overwritten and emptied with it.
// Never throws.
void destroy_context(pp_session * s, bool keep_record = false) {
    try {
        if (s->batch != nullptr) {
            llama_batch_ext_clear(s->batch);
            for (int32_t i = 0; i < s->n_batch; i++) {
                if (llama_batch_ext_add_token(s->batch, k_seq, 0) < 0) {
                    break;
                }
            }
            llama_batch_ext_clear(s->batch);
        }
    } catch (...) {
        // The batch is freed below either way.
    }
    try {
        if (s->batch != nullptr) {
            llama_batch_ext_free(s->batch);
        }
        s->batch = nullptr;
        if (s->ctx != nullptr) {
            wipe_prediction(s);
            llama_memory_clear(llama_get_memory(s->ctx), true);
            llama_free(s->ctx);
        }
    } catch (...) {
        // Nothing more can be done for whatever threw; the session no longer refers to it.
    }
    s->batch = nullptr;
    s->ctx = nullptr;
    s->prediction_ready = false;
    s->has_prediction_data = false;
    // The memory is gone, and so is the record of what it held and of where a reply began,
    // unless the memory is about to be put into another context as it is (see
    // pp_session_resize_context): then the record goes on describing it.
    if (!keep_record) {
        cut_history(s, 0);
        s->reply_start = -1;
    }
    // The backend that knew the replaced pools went with the context.
    if (s->threadpool_free != nullptr) {
        free_retired_threadpools(s);
    }
}

// Creates a context of n_ctx tokens from the kept parameters, and the batch that belongs to it,
// and learns from llama.cpp's report what such a context costs. Returns false, with nothing
// created, when llama.cpp cannot. The record of the memory's tokens and where the reply began
// are left alone either way: a caller with no memory to keep has emptied them already, and one
// that carries a memory over (pp_session_resize_context) needs them through a failed attempt.
bool create_context(pp_session * s, int32_t n_ctx) {
    try {
        s->cparams.n_ctx    = (uint32_t) n_ctx;
        // One llama_process call is one micro-batch, so that "a chunk" means the same thing to
        // the caller, to the abort callback and to llama.cpp's own roll-back of a failed batch.
        s->cparams.n_batch  = (uint32_t) std::min(s->n_batch_max, n_ctx);
        s->cparams.n_ubatch = s->cparams.n_batch;
        std::string report;
        const int64_t before = allocated_bytes();
        {
            report_capture capture(report);
            s->ctx = llama_init_from_model(s->model, s->cparams);
        }
        if (s->ctx != nullptr) {
            const int64_t after = allocated_bytes();
            // The size as llama.cpp made it, which may be rounded up from the one asked for.
            learn_cost(s, (int32_t) llama_n_ctx(s->ctx), (int32_t) llama_n_batch(s->ctx), report, before < 0 || after < 0 ? -1 : after - before);
            s->n_ctx   = (int32_t) llama_n_ctx(s->ctx);
            s->n_batch = (int32_t) llama_n_batch(s->ctx);
            s->batch   = llama_batch_ext_init(s->ctx);
        }
        if (s->ctx != nullptr && s->batch != nullptr && s->n_batch > 0) {
            // Room for the ids of a full memory, so that the record does not have to move
            // while a conversation is in it.
            reserve_history(s, (size_t) s->n_ctx);
            // The batch is brought to its full size now, with zeros. One that grew while a
            // prompt was put into it would move the tokens it already holds and free their old
            // place as it is.
            for (int32_t i = 0; i < s->n_batch; i++) {
                if (llama_batch_ext_add_token(s->batch, k_seq, 0) < 0) {
                    break;
                }
            }
            llama_batch_ext_clear(s->batch);
            if (s->can_abort) {
                llama_set_abort_callback(s->ctx, abort_callback, s);
            }
            attach_threads(s);
            return true;
        }
    } catch (...) {
        // Whatever exists of the two is freed below.
    }
    destroy_context(s, true);
    return false;
}

// The two parts of a sampler (see pp_session::sampler), owned until they are released.
struct sampler_parts {
    llama_sampler * chain = nullptr;
    llama_sampler * chooser = nullptr;

    sampler_parts() = default;
    sampler_parts(const sampler_parts &) = delete;
    sampler_parts & operator=(const sampler_parts &) = delete;
    ~sampler_parts() {
        if (chain != nullptr) {
            llama_sampler_free(chain);
        }
        if (chooser != nullptr) {
            llama_sampler_free(chooser);
        }
    }
};

// Builds a sampler: see pp_session_configure_sampler in the header for its order. Throws
// std::bad_alloc when llama.cpp cannot create a part of it; nothing stays allocated then.
void build_sampler(int32_t n_vocab, const pp_sampler_config & config, sampler_parts & parts) {
    parts.chain = llama_sampler_chain_init(llama_sampler_chain_default_params());
    if (parts.chain == nullptr) {
        throw std::bad_alloc();
    }
    // The chain owns what is added to it; freeing the chain frees the whole of it.
    const auto add = [&](llama_sampler * sampler) {
        if (sampler == nullptr) {
            throw std::bad_alloc();
        }
        llama_sampler_chain_add(parts.chain, sampler);
    };

    if (config.n_banned_tokens > 0) {
        std::vector<llama_logit_bias> biases;
        biases.reserve((size_t) config.n_banned_tokens);
        for (int32_t i = 0; i < config.n_banned_tokens; i++) {
            biases.push_back({config.banned_tokens[i], -std::numeric_limits<float>::infinity()});
        }
        add(llama_sampler_init_logit_bias(n_vocab, (int32_t) biases.size(), biases.data()));
    }
    if (config.temperature >= PP_TEMPERATURE_MIN) {
        add(llama_sampler_init_temp(config.temperature));
        if (config.top_k != 0) {
            add(llama_sampler_init_top_k(config.top_k));
        }
        if (config.top_p < 1.0f) {
            add(llama_sampler_init_top_p(config.top_p, 1));
        }
        if (config.min_p > 0.0f) {
            add(llama_sampler_init_min_p(config.min_p, 1));
        }
        parts.chooser = llama_sampler_init_dist(config.seed);
    } else {
        parts.chooser = llama_sampler_init_greedy();
    }
    if (parts.chooser == nullptr) {
        throw std::bad_alloc();
    }
}

// Hands the parts of a new sampler to the session, which has none at this point. A reply
// begins anew for the penalties with it: at reply_start, a position in the memory as it is now,
// or where sampling begins for PP_REPLY_START_AT_SAMPLING. A sampler that is built again after
// the memory was emptied passes the latter whatever was configured: the position was one in
// the memory that is gone.
void install_sampler(pp_session * s, sampler_parts & parts, int32_t reply_start) {
    s->sampler = parts.chain;
    s->chooser = parts.chooser;
    s->reply_start = reply_start;
    parts.chain = nullptr;
    parts.chooser = nullptr;
}

// Frees the sampler. There is nothing of a conversation in it to overwrite first: the samplers
// it is made of keep no token (llama.cpp's own penalties, which keep the sampled ones in a ring
// buffer and a table that no call overwrites, are not among them), only the settings and the
// state of the random numbers. Never throws.
void destroy_sampler(pp_session * s) {
    try {
        if (s->sampler != nullptr) {
            llama_sampler_free(s->sampler);
        }
        if (s->chooser != nullptr) {
            llama_sampler_free(s->chooser);
        }
    } catch (...) {
    }
    s->sampler = nullptr;
    s->chooser = nullptr;
}

// The penalties, applied to the candidate list while it still holds every token at the index
// of its id and its score as the model gave it. Each covers what it covers where the models'
// settings come from (see pp_session_configure_sampler in the header):
// - the repetition penalty every token in memory, prompt and reply alike, once for a token
//   however often it occurs: a score below zero is multiplied by the penalty, any other
//   divided by it;
// - the frequency and presence penalties the tokens in memory from where the reply began: the
//   score less the frequency penalty times the token's count, less the presence penalty.
// The window, when it is shorter than the memory, limits both to the latest tokens.
//
// llama.cpp's own penalties are not used: they are computed from the tokens the sampler was
// told of, so all three cover the same tokens, and they keep a list of their own that follows
// neither a removal from the memory nor a restored checkpoint and cannot be overwritten. The
// arithmetic is theirs. The `p` of a candidate, which nothing reads before a later sampler
// computes it, serves as the mark and the count, and is zero again afterwards.
void apply_penalties(pp_session * s) {
    const pp_sampler_config & config = s->sampler_config;
    const bool repetition = config.repetition_penalty != 1.0f;
    const bool additive = config.frequency_penalty != 0.0f || config.presence_penalty != 0.0f;
    if (!repetition && !additive) {
        return;
    }
    const size_t count = s->history.size();
    const size_t first = config.penalty_window != PP_PENALTY_NO_WINDOW && count > (size_t) config.penalty_window ? count - (size_t) config.penalty_window : 0;
    const llama_token * ids = s->history.data();
    llama_token_data * candidates = s->candidates.data();
    if (repetition) {
        for (size_t i = first; i < count; i++) {
            llama_token_data & candidate = candidates[ids[i]];
            if (candidate.p == 0.0f) {
                candidate.p = 1.0f;
                candidate.logit = candidate.logit <= 0.0f ? candidate.logit * config.repetition_penalty : candidate.logit / config.repetition_penalty;
            }
        }
        for (size_t i = first; i < count; i++) {
            candidates[ids[i]].p = 0.0f;
        }
    }
    if (additive) {
        const size_t reply = std::min(count, std::max(first, (size_t) std::max(0, s->reply_start)));
        for (size_t i = reply; i < count; i++) {
            candidates[ids[i]].p += 1.0f;
        }
        for (size_t i = reply; i < count; i++) {
            llama_token_data & candidate = candidates[ids[i]];
            if (candidate.p != 0.0f) {
                candidate.logit -= candidate.p * config.frequency_penalty + config.presence_penalty;
                candidate.p = 0.0f;
            }
        }
    }
}

// Undoes prepare_threads: frees the pools and, if the calling thread was pinned, lets it go.
// The context must already be freed: its backend still knows the pool it last computed with.
void release_threads(pp_session * s) {
    free_retired_threadpools(s);
    if (s->threadpool != nullptr) {
        s->threadpool_free(s->threadpool);
    }
    s->threadpool = nullptr;
#ifdef PP_HAVE_AFFINITY
    if (s->pinned) {
        cpu_set_t allowed;
        allowed_cpus(allowed);
    }
#endif
    s->pinned = false;
    s->threads_ready = false;
}

std::string meta(const llama_model * model, const char * key) {
    char buffer[128];
    const int32_t n = llama_model_meta_val_str(model, key, buffer, sizeof(buffer));
    return n < 0 ? std::string() : std::string(buffer);
}

struct session_closer {
    void operator()(pp_session * s) const { pp_session_close(s); }
};

// Loads the model through load(), then builds everything else a session needs.
template <typename Load>
int32_t open_session(const pp_session_params * params, pp_session ** session, char * error, size_t error_size, Load && load) {
    try {
        if (!g_runtime_ready) {
            put_error(error, error_size, "pp_runtime_init has not succeeded: " + g_runtime_description);
            return PP_STATUS_LOAD_FAILED;
        }
        // Whatever path leaves this function without handing the session over closes it.
        std::unique_ptr<pp_session, session_closer> owner(new pp_session());
        pp_session * s = owner.get();
        std::string log;
        log_capture capture(log, false);

        s->progress.fn = params->progress;
        s->progress.user_data = params->progress_user_data;
        s->n_threads_unpinned = params->n_threads;
        s->n_threads_now = params->n_threads;
        s->pinned_cpus.assign(params->pinned_cpus, params->pinned_cpus + params->n_pinned_cpus);
        std::sort(s->pinned_cpus.begin(), s->pinned_cpus.end());

        // The CPU only, stated rather than left to defaults: the default of n_gpu_layers is "all
        // layers" and a NULL device list means "every device", which would move the model to a
        // GPU backend the moment one is compiled in.
        llama_model_params mparams = llama_model_default_params();
        mparams.devices      = g_no_devices;
        mparams.n_gpu_layers = 0;
        mparams.load_mode    = params->load_mode == PP_LOAD_MODE_MMAP ? LLAMA_LOAD_MODE_MMAP : LLAMA_LOAD_MODE_NONE;
        mparams.progress_callback           = progress_trampoline;
        mparams.progress_callback_user_data = &s->progress;

        s->model = load(mparams);
        // The caller's callback is only good for the duration of this call.
        s->progress.fn = nullptr;
        s->progress.user_data = nullptr;
        if (s->model == nullptr) {
            if (s->progress.cancelled) {
                put_error(error, error_size, "the load was cancelled");
                return PP_STATUS_LOAD_CANCELLED;
            }
            put_error(error, error_size, log.empty() ? std::string("llama.cpp could not load the model") : tidy(log));
            return mentions_memory(log) ? PP_STATUS_OUT_OF_MEMORY : PP_STATUS_LOAD_FAILED;
        }

        // The limit on the context that only the model can state: more than it was trained
        // for is taken as that. Settled before any context exists: llama.cpp would create one
        // of any size.
        const int32_t n_ctx_train = llama_model_n_ctx_train(s->model);
        if (n_ctx_train < 1) {
            put_error(error, error_size, "the model does not say what context it was trained for");
            return PP_STATUS_LOAD_FAILED;
        }
        s->n_ctx_max   = n_ctx_train;
        s->n_ctx_open  = std::min(params->n_ctx, n_ctx_train);
        // A batch above the opening size is that size, and stays so when the context grows:
        // llama.cpp gives a context no batch above its own size, and the costs are learnt from
        // contexts at the session's batch only (see pp_context_cost), so the opening context
        // has to be one of them.
        s->n_batch_max = std::min(params->n_batch, s->n_ctx_open);

        s->cparams = llama_context_default_params();
        s->cparams.n_seq_max       = 1;
        s->cparams.n_threads       = params->n_threads;
        s->cparams.n_threads_batch = params->n_threads;
        s->cparams.no_perf         = true;

        s->vocab     = llama_model_get_vocab(s->model);
        s->n_vocab   = llama_vocab_n_tokens(s->vocab);
        s->can_abort = !llama_model_is_hybrid(s->model) && !llama_model_is_recurrent(s->model);

        log.clear();
        // What a context costs is only known from llama.cpp's report of one. So that the
        // opening size can be held to the budget before it is allocated, and so that the
        // growth of the compute reservation is known from two sizes, a small context comes
        // first and is freed again: an eighth of the opening size and at most four batches,
        // and never less than a batch, whose figures are the only ones kept (see
        // pp_context_cost). It is the one allocation made before any cost is known, and never
        // larger than the opening context. A session opened at about one batch learns from its
        // own context alone.
        // A budget of INT64_MAX is the caller's statement that there is no limit: only then may
        // a context whose cost cannot be known be opened.
        const int64_t budget = params->context_budget_bytes;
        const bool unlimited = budget == std::numeric_limits<int64_t>::max();
        const auto refuse = [&](int64_t cost, const char * when) {
            put_error(error, error_size, cost < 0
                    ? std::string("what a context costs is not known (llama.cpp's report of its buffers was not understood), and the budget is ") + std::to_string(budget)
                    : "a context of " + std::to_string(s->n_ctx_open) + " tokens needs " + std::to_string(cost) + " bytes and the budget is " +
                      std::to_string(budget) + " (" + when + ")");
            return PP_STATUS_CONTEXT_REFUSED;
        };
        const int32_t n_first = rounded_ctx(std::max(s->n_batch_max, std::min(s->n_ctx_open / 8, (int32_t) std::min<int64_t>((int64_t) 4 * s->n_batch_max, std::numeric_limits<int32_t>::max()))));
        bool estimated = false;
        if (s->n_vocab > 0 && n_first < rounded_ctx(s->n_ctx_open)) {
            estimated = true;
            if (!create_context(s, n_first)) {
                put_error(error, error_size, log.empty() ? std::string("llama.cpp could not create a context") : tidy(log));
                return mentions_memory(log) ? PP_STATUS_OUT_OF_MEMORY : PP_STATUS_LOAD_FAILED;
            }
            destroy_context(s);
            // A new context must not inherit the resident pages of the one it replaces: they
            // would stay resident under its untouched reservations, and they hold what was not
            // overwritten. So the free pages go back before the next context is created.
            release_free_pages();
            // The estimate: with the small context the only one measured, what the compute
            // reservation and the bookkeeping grow by up to the opening size is counted as
            // nothing; the check after the allocation makes up for that.
            const int64_t cost = context_cost(s, s->n_ctx_open, true);
            if (cost > budget || (cost < 0 && !unlimited)) {
                return refuse(cost, "refused before it was allocated");
            }
            if (cost >= 0 && !can_reserve(cost)) {
                put_error(error, error_size, "no room in the address space for a context of " + std::to_string(s->n_ctx_open) + " tokens (" + std::to_string(cost) + " bytes)");
                return PP_STATUS_OUT_OF_MEMORY;
            }
        }
        if (s->n_vocab <= 0 || !create_context(s, s->n_ctx_open)) {
            put_error(error, error_size, log.empty() ? std::string("llama.cpp could not create the context") : tidy(log));
            return mentions_memory(log) ? PP_STATUS_OUT_OF_MEMORY : PP_STATUS_LOAD_FAILED;
        }
        // The second check, from the opening context's own report. It refuses what the
        // estimate from the small context let through (see pp_session_open_path in the
        // header), and is the only check of a session too small for that context. The owner
        // closes the session on the way out, which frees the context again.
        const int64_t cost = context_cost(s, s->n_ctx_open);
        if (cost > budget || (cost < 0 && !unlimited)) {
            return refuse(cost, estimated ? "refused once it existed: the estimate before its allocation was lower" : "refused once it existed: no estimate could be made before");
        }
        s->candidates.resize((size_t) s->n_vocab);

        // A peacebell model with the SentencePiece tokenizer is only tokenised correctly by the
        // front-end. If the front-end refuses the file, the model must not be run through
        // llama_tokenize instead: it would produce tokens the model was not trained on.
        if (meta(s->model, "general.architecture") == "peacebell" && meta(s->model, "tokenizer.ggml.model") == "llama") {
            const char * reason = nullptr;
            s->peacebell = pp_peacebell_tokenizer_init(s->model, &reason);
            if (s->peacebell == nullptr) {
                put_error(error, error_size, std::string("the tokenizer front-end refused the model: ") + (reason != nullptr ? reason : "unknown reason"));
                return PP_STATUS_LOAD_FAILED;
            }
        }

        if (meta(s->model, "general.architecture") == "boris2") {
            s->refused_input = llama_vocab_eos(s->vocab);
        }
        s->n_window = llama_model_n_swa(s->model);

        // With the backends built as modules, the thread pool functions are only reachable
        // through the CPU backend that was loaded; the same lookup works when it is linked in.
        ggml_backend_dev_t cpu = ggml_backend_dev_by_type(GGML_BACKEND_DEVICE_TYPE_CPU);
        if (cpu != nullptr) {
            ggml_backend_reg_t reg = ggml_backend_dev_backend_reg(cpu);
            s->threadpool_new  = (decltype(s->threadpool_new)) ggml_backend_reg_get_proc_address(reg, "ggml_threadpool_new");
            s->threadpool_free = (decltype(s->threadpool_free)) ggml_backend_reg_get_proc_address(reg, "ggml_threadpool_free");
            if (s->threadpool_new == nullptr || s->threadpool_free == nullptr) {
                s->threadpool_new = nullptr;
                s->threadpool_free = nullptr;
            }
        }

        *session = owner.release();
        return PP_STATUS_OK;
    } catch (const std::bad_alloc &) {
        put_error(error, error_size, "out of memory while opening the model");
        return PP_STATUS_OUT_OF_MEMORY;
    } catch (...) {
        put_error(error, error_size, "llama.cpp failed while opening the model");
        return PP_STATUS_LOAD_FAILED;
    }
}

// Reads the GGUF header that begins at offset of the stream and answers whether the model it
// describes lies within length bytes from there: NULL when it does, otherwise what is wrong.
// The header is read a second time by the load that follows; it is the metadata only, and the
// alternative is to find out from a model that answers with nonsense.
const char * model_extent_problem(FILE * file, int64_t offset, int64_t length) {
    try {
        if (fseeko(file, (off_t) offset, SEEK_SET) != 0) {
            return "the file cannot be positioned at the given offset";
        }
        struct gguf_init_params header_only = {/* no_alloc */ true, /* ctx */ nullptr};
        struct gguf_context * header = gguf_init_from_file_ptr(file, header_only);
        if (header == nullptr) {
            return "the file holds no complete GGUF header at the given offset";
        }
        // Absolute in the file: the reader counts from where the stream stood.
        const uint64_t data_begin = (uint64_t) gguf_get_data_offset(header);
        const uint64_t limit = (uint64_t) offset + (uint64_t) length;
        bool fits = data_begin <= limit;
        const int64_t n_tensors = gguf_get_n_tensors(header);
        for (int64_t i = 0; i < n_tensors && fits; i++) {
            const uint64_t begin = (uint64_t) gguf_get_tensor_offset(header, i);
            const uint64_t size = (uint64_t) gguf_get_tensor_size(header, i);
            // In this order: none of the three subtractions can wrap.
            fits = begin <= limit - data_begin && size <= limit - data_begin - begin;
        }
        gguf_free(header);
        return fits ? nullptr : "the model's header and tensors do not fit in the given length of the file";
    } catch (...) {
        return "the GGUF header at the given offset could not be read";
    }
}

bool finite(float value) {
    return std::isfinite(value);
}

} // namespace

//
// The process
//

int32_t pp_runtime_init(const char * const * backend_libraries, int32_t n_backend_libraries) {
    std::call_once(g_runtime_once, runtime_init, backend_libraries, n_backend_libraries);
    return g_runtime_status;
}

const char * pp_runtime_description(void) {
    return g_runtime_description.c_str();
}

//
// Opening and closing
//

int32_t pp_session_open_path(const char * path, const struct pp_session_params * params, struct pp_session ** session, char * error, size_t error_size) {
    put_error(error, error_size, "");
    if (session == nullptr) {
        return PP_STATUS_INVALID_ARGUMENT;
    }
    *session = nullptr;
    const char * problem = path == nullptr ? "no path" : params_problem(params);
    if (problem != nullptr) {
        put_error(error, error_size, problem);
        return PP_STATUS_INVALID_ARGUMENT;
    }
    return open_session(params, session, error, error_size, [&](const llama_model_params & mparams) {
        return llama_model_load_from_file(path, mparams);
    });
}

int32_t pp_session_open_fd(int fd, int64_t offset, int64_t length, const struct pp_session_params * params, struct pp_session ** session, char * error, size_t error_size) {
    put_error(error, error_size, "");
    if (session == nullptr) {
        return PP_STATUS_INVALID_ARGUMENT;
    }
    *session = nullptr;
    if (const char * problem = params_problem(params)) {
        put_error(error, error_size, problem);
        return PP_STATUS_INVALID_ARGUMENT;
    }
    struct stat info;
    if (fd < 0 || offset < 0 || length < 4 || fstat(fd, &info) != 0 || !S_ISREG(info.st_mode) ||
        offset > (int64_t) info.st_size || length > (int64_t) info.st_size - offset) {
        put_error(error, error_size, "invalid arguments to pp_session_open_fd: not a regular file, or the range lies outside it");
        return PP_STATUS_INVALID_ARGUMENT;
    }
    // Checked here for a plain message; llama.cpp would only say that it could not load.
    char magic[4];
    if (pread(fd, magic, sizeof(magic), (off_t) offset) != (ssize_t) sizeof(magic) || std::memcmp(magic, "GGUF", 4) != 0) {
        put_error(error, error_size, "there is no GGUF model at the given offset of the file");
        return PP_STATUS_LOAD_FAILED;
    }
    // A descriptor of our own, so that the caller's can be closed whenever the caller likes and
    // closing the stream below does not close it underneath the caller.
    const int own = fcntl(fd, F_DUPFD_CLOEXEC, 0);
    FILE * file = own >= 0 ? fdopen(own, "rb") : nullptr;
    if (file == nullptr) {
        if (own >= 0) {
            close(own);
        }
        put_error(error, error_size, "the file descriptor could not be duplicated");
        return PP_STATUS_LOAD_FAILED;
    }
    // The stated length is all of the file that is the model's. llama.cpp is not told the length:
    // it reads each tensor from where the header says it is.
    const char * extent_problem = model_extent_problem(file, offset, length);
    if (extent_problem != nullptr) {
        fclose(file);
        put_error(error, error_size, extent_problem);
        return PP_STATUS_LOAD_FAILED;
    }
    const int32_t status = open_session(params, session, error, error_size, [&](const llama_model_params & mparams) -> llama_model * {
        // llama.cpp reads the GGUF from the stream's current position and addresses the tensors
        // by absolute file offset from there on.
        if (fseeko(file, (off_t) offset, SEEK_SET) != 0) {
            return nullptr;
        }
        return llama_model_load_from_file_ptr(file, mparams);
    });
    // llama.cpp does not take the stream over. A mapping, when there is one, outlives it.
    fclose(file);
    return status;
}

void pp_session_close(struct pp_session * session) {
    if (session == nullptr) {
        return;
    }
    destroy_sampler(session);
    free_all_checkpoints(session);
    wipe(session->candidates.data(), session->candidates.size() * sizeof(llama_token_data));
    destroy_context(session);
    try {
        if (session->peacebell != nullptr) {
            pp_peacebell_tokenizer_free(session->peacebell);
        }
        // After the context, whose backend refers to the thread pool it last used.
        release_threads(session);
        if (session->model != nullptr) {
            llama_model_free(session->model);
        }
    } catch (...) {
        // Nothing more can be done for whatever threw; the struct is released regardless.
    }
    delete session;
    release_free_pages();
}

const char * pp_session_last_error(const struct pp_session * session) {
    return session == nullptr ? "" : session->last_error.c_str();
}

void pp_session_thread_setup(const struct pp_session * session, int32_t * n_threads, bool * pinned) {
    if (n_threads != nullptr) {
        *n_threads = session == nullptr ? 0 : session->n_threads_now;
    }
    if (pinned != nullptr) {
        *pinned = session != nullptr && session->pinned;
    }
}

//
// Tokens
//

int32_t pp_session_tokenize(struct pp_session * session, const char * text, int32_t text_len, bool parse_special, llama_token * tokens, int32_t n_tokens_max) {
    if (session == nullptr) {
        return PP_ERROR;
    }
    if (text_len < 0 || (text == nullptr && text_len > 0) || n_tokens_max < 0 || (tokens == nullptr && n_tokens_max > 0)) {
        fail(session, PP_STATUS_INVALID_ARGUMENT, "invalid arguments to pp_session_tokenize");
        return PP_ERROR;
    }
    if (text_len == 0) {
        return 0;
    }
    if (text_len > PP_TOKENIZE_BYTES_MAX) {
        fail(session, PP_STATUS_TOKENIZE_FAILED, "the text is longer than " PP_STRINGIFY(PP_TOKENIZE_BYTES_MAX) " bytes, the most that is tokenised in one call");
        return PP_ERROR;
    }
    try {
        const int32_t n = session->peacebell != nullptr
            ? pp_peacebell_tokenize(session->peacebell, text, text_len, tokens, n_tokens_max, parse_special ? PP_MARKER_MODE_EXACT : PP_MARKER_MODE_SAFE)
            : llama_tokenize(session->vocab, text, text_len, tokens, n_tokens_max, false, parse_special);
        // PP_ERROR and llama_tokenize's overflow answer are the same value.
        if (n == PP_ERROR) {
            fail(session, PP_STATUS_TOKENIZE_FAILED, "the text could not be tokenised (not valid UTF-8, or too long)");
        }
        return n;
    } catch (...) {
        fail(session, PP_STATUS_TOKENIZE_FAILED, "the tokenizer failed");
        return PP_ERROR;
    }
}

int32_t pp_session_token_to_bytes(struct pp_session * session, llama_token token, char * buffer, int32_t buffer_size) {
    if (session == nullptr) {
        return PP_ERROR;
    }
    if (token < 0 || token >= session->n_vocab || buffer_size < 0 || (buffer == nullptr && buffer_size > 0)) {
        fail(session, PP_STATUS_INVALID_ARGUMENT, "invalid arguments to pp_session_token_to_bytes: a token outside the vocabulary, or no buffer");
        return PP_ERROR;
    }
    try {
        return llama_token_to_piece(session->vocab, token, buffer, buffer_size, 0, false);
    } catch (...) {
        fail(session, PP_STATUS_INVALID_ARGUMENT, "llama_token_to_piece failed");
        return PP_ERROR;
    }
}

bool pp_session_is_end_of_generation(const struct pp_session * session, llama_token token) {
    return session != nullptr && token >= 0 && token < session->n_vocab && llama_vocab_is_eog(session->vocab, token);
}

int32_t pp_session_vocab_size(const struct pp_session * session) {
    return session == nullptr ? 0 : session->n_vocab;
}

//
// Memory
//

int32_t pp_session_context_size(const struct pp_session * session) {
    return session == nullptr || session->ctx == nullptr ? 0 : (int32_t) llama_n_ctx(session->ctx);
}

int32_t pp_session_memory_token_count(const struct pp_session * session) {
    return session == nullptr ? 0 : memory_count(session);
}

int32_t pp_session_evaluate(struct pp_session * session, const llama_token * tokens, int32_t n_tokens, int32_t chunk_size,
                            const struct pp_evaluation_observer * observer, int32_t * n_in_memory) {
    if (session == nullptr) {
        return PP_STATUS_INVALID_ARGUMENT;
    }
    pp_session * s = session;
    if (n_in_memory != nullptr) {
        *n_in_memory = 0;
    }
    if (tokens == nullptr || n_tokens < 1 || chunk_size < 1 || n_in_memory == nullptr ||
        observer == nullptr || observer->is_cancelled == nullptr || observer->on_progress == nullptr) {
        return fail(s, PP_STATUS_INVALID_ARGUMENT, "invalid arguments to pp_session_evaluate");
    }
    for (int32_t i = 0; i < n_tokens; i++) {
        if (tokens[i] < 0 || tokens[i] >= s->n_vocab) {
            return fail(s, PP_STATUS_INVALID_ARGUMENT, "a token id passed to pp_session_evaluate is outside the vocabulary");
        }
        if (tokens[i] == s->refused_input) {
            return fail(s, PP_STATUS_INVALID_ARGUMENT, "this model cannot take its end-of-text token as input");
        }
    }

    if (s->ctx == nullptr) {
        return fail_no_context(s);
    }

    // Whatever happens below, the old prediction no longer belongs to the end of the memory. Its
    // scores are overwritten here and not later: this is the last moment at which llama.cpp is
    // certain to still have the buffer they are in (see pp_session::has_prediction_data).
    s->prediction_ready = false;
    int32_t status = PP_STATUS_OK;
    int32_t start = 0;
    try {
        wipe_prediction(s);
        start = memory_count(s);
        const int32_t chunk = std::min(chunk_size, s->n_batch);
        s->observer = observer;
        s->cancelled = false;

        int32_t done = 0;
        while (done < n_tokens) {
            if (poll_cancelled(s)) {
                break;
            }
            // Before every chunk, not once per call: a long prompt is one call of many seconds,
            // and when the system moves the process to other cores during it, pinned threads
            // would share cores for the rest of the call. It costs one system call when nothing
            // has changed.
            prepare_threads(s);
            const int32_t n_chunk = std::min(chunk, n_tokens - done);
            const int32_t before = memory_count(s);
            // Before the chunk is computed and not after: once its tokens are in the memory
            // they have to be in the record, and then nothing may be left that can fail.
            reserve_history(s, (size_t) before + (size_t) n_chunk);

            llama_batch_ext_clear(s->batch);
            bool built = true;
            for (int32_t i = 0; i < n_chunk && built; i++) {
                const int32_t idx = llama_batch_ext_add_token(s->batch, k_seq, tokens[done + i]);
                const llama_pos pos = (llama_pos) (before + i);
                // Output for the very last token of the call only: nothing else is ever sampled from.
                built = idx >= 0 && llama_batch_ext_set_pos(s->batch, idx, &pos) &&
                        llama_batch_ext_set_output_logits(s->batch, idx, done + i + 1 == n_tokens);
            }
            if (!built) {
                status = fail(s, PP_STATUS_EVALUATION_FAILED, "the batch could not be built");
                break;
            }

            s->last_poll_us = llama_time_us();
            const int32_t rc = llama_process(s->ctx, LLAMA_PROCESS_TYPE_DECODE, s->batch);
            if (rc == 0) {
                // The backend has computed with the present pool and let go of any earlier one.
                free_retired_threadpools(s);
                done += n_chunk;
                // The chunk with the call's last token is the one that asked for an output.
                s->has_prediction_data = done == n_tokens;
                observer->on_progress(done, observer->user_data);
                continue;
            }
            if (rc == 1) {
                status = fail(s, PP_STATUS_CONTEXT_FULL, "no room in the context for " + std::to_string(n_chunk) + " more token(s) after " + std::to_string(before));
            } else if (rc == 2) {
                // Aborted through the callback, so a cancellation. llama.cpp has taken the
                // interrupted micro-batch out of the memory again. Should it ever fail to (the
                // callback is only installed where it can), the memory would claim tokens whose
                // state is half computed: nothing short of emptying it makes it usable again,
                // and the length reported to the caller then says so.
                if (memory_count(s) != before) {
                    llama_memory_clear(llama_get_memory(s->ctx), true);
                }
            } else {
                status = fail(s, PP_STATUS_EVALUATION_FAILED, "llama_process returned " + std::to_string(rc));
            }
            break;
        }

        if (status == PP_STATUS_OK && done == n_tokens) {
            s->prediction_ready = true;
        }
    } catch (const std::bad_alloc &) {
        status = fail(s, PP_STATUS_OUT_OF_MEMORY, "out of memory during evaluation");
    } catch (...) {
        status = fail(s, PP_STATUS_EVALUATION_FAILED, "llama.cpp failed during evaluation");
    }
    s->observer = nullptr;
    // The token ids of the last chunk stay in the batch until the next call or a clear.
    try {
        const int32_t count = memory_count(s);
        *n_in_memory = std::max(0, count - start);
        // The record follows the memory, whatever became of the call: the tokens of this call
        // that are in it now stand at the positions from `start` on, in their order (room for
        // them was made before their chunk was computed), and a memory that was emptied
        // because an interrupted chunk could not be taken out of it empties the record.
        cut_history(s, (size_t) std::max(0, count));
        for (int32_t position = (int32_t) s->history.size();
             position >= start && position - start < n_tokens && position < count && s->history.size() < s->history.capacity(); position++) {
            s->history.push_back(tokens[position - start]);
        }
    } catch (...) {
        *n_in_memory = 0;
        follow_memory(s);
    }
    return status;
}

int32_t pp_session_rewind_to(struct pp_session * session, int32_t n_tokens, bool * removed) {
    if (session == nullptr) {
        return PP_STATUS_INVALID_ARGUMENT;
    }
    pp_session * s = session;
    if (removed != nullptr) {
        *removed = false;
    }
    if (s->ctx == nullptr) {
        return fail_no_context(s);
    }
    try {
        const int32_t count = memory_count(s);
        if (removed == nullptr || n_tokens < 0 || n_tokens > count) {
            return fail(s, PP_STATUS_INVALID_ARGUMENT, "pp_session_rewind_to(" + std::to_string(n_tokens) + ") with " + std::to_string(count) + " token(s) in memory");
        }
        if (n_tokens == count) {
            *removed = true;
            return PP_STATUS_OK;
        }
        // Windowed layers hold their keys from some position on: from the first, until a
        // checkpoint is restored, which brings back the window before its own end and nothing
        // older. llama.cpp cuts such a memory wherever it is asked to and answers true, and the
        // tokens evaluated next would then attend to keys that are not there: every prediction
        // after the cut would be wrong and nothing would say so. The token at position n_tokens
        // looks at the n_window - 1 positions before it; when the windowed layers no longer
        // hold all of them the cut is refused here, the way llama.cpp refuses to cut a
        // recurrent state, and the caller goes back to a checkpoint or starts over.
        if (s->n_window > 0 && n_tokens > 0) {
            const llama_pos first_held = llama_memory_seq_pos_min(llama_get_memory(s->ctx), k_seq);
            if (first_held > std::max<llama_pos>(0, (llama_pos) n_tokens - (s->n_window - 1))) {
                return PP_STATUS_OK;
            }
        }
        if (llama_memory_seq_rm(llama_get_memory(s->ctx), k_seq, (llama_pos) n_tokens, -1)) {
            s->prediction_ready = false;
            follow_memory(s);
            *removed = true;
            return PP_STATUS_OK;
        }
        // Refused. The contract promises the caller an untouched memory; llama.cpp promises the
        // same of a refusal, and this holds it to that.
        if (memory_count(s) != count) {
            s->prediction_ready = false;
            follow_memory(s);
            return fail(s, PP_STATUS_EVALUATION_FAILED, "a refused removal changed the memory");
        }
        return PP_STATUS_OK;
    } catch (...) {
        s->prediction_ready = false;
        follow_memory(s);
        return fail(s, PP_STATUS_EVALUATION_FAILED, "llama_memory_seq_rm failed");
    }
}

int32_t pp_session_save_checkpoint(struct pp_session * session) {
    if (session == nullptr) {
        return -1;
    }
    pp_session * s = session;
    if (s->ctx == nullptr) {
        fail_no_context(s);
        return -1;
    }
    if (s->checkpoints.size() >= (size_t) PP_CHECKPOINTS_MAX) {
        fail(s, PP_STATUS_INVALID_ARGUMENT, "the session already holds " PP_STRINGIFY(PP_CHECKPOINTS_MAX) " checkpoints, the most it keeps: drop one first");
        return -1;
    }
    pp_checkpoint checkpoint;
    try {
        if (s->next_checkpoint == std::numeric_limits<int32_t>::max()) {
            return -1;
        }
        checkpoint.size = llama_state_seq_get_size_ext(s->ctx, k_seq, LLAMA_STATE_SEQ_FLAGS_PARTIAL_ONLY);
        if (checkpoint.size == 0) {
            return -1;
        }
        checkpoint.data = (uint8_t *) std::malloc(checkpoint.size);
        if (checkpoint.data == nullptr) {
            return -1;
        }
        if (llama_state_seq_get_data_ext(s->ctx, checkpoint.data, checkpoint.size, k_seq, LLAMA_STATE_SEQ_FLAGS_PARTIAL_ONLY) != checkpoint.size) {
            free_checkpoint(checkpoint);
            return -1;
        }
        checkpoint.n_tokens = memory_count(s);
        const int32_t handle = s->next_checkpoint;
        s->checkpoints.emplace(handle, checkpoint);
        s->next_checkpoint++;
        return handle;
    } catch (...) {
        free_checkpoint(checkpoint);
        return -1;
    }
}

bool pp_session_restore_checkpoint(struct pp_session * session, int32_t checkpoint) {
    if (session == nullptr) {
        return false;
    }
    pp_session * s = session;
    if (s->ctx == nullptr) {
        fail_no_context(s);
        return false;
    }
    try {
        const auto it = s->checkpoints.find(checkpoint);
        if (it == s->checkpoints.end()) {
            return false;
        }
        const pp_checkpoint & saved = it->second;
        // A memory shorter than the checkpoint cannot begin with the tokens it was saved at.
        if (memory_count(s) < saved.n_tokens) {
            return false;
        }
        s->prediction_ready = false;
        // The state below only covers what is not attention; the attention part still holds
        // everything that was evaluated since and is cut back to the same length after it.
        const bool restored = llama_state_seq_set_data_ext(s->ctx, saved.data, saved.size, k_seq, LLAMA_STATE_SEQ_FLAGS_PARTIAL_ONLY) == saved.size &&
                              llama_memory_seq_rm(llama_get_memory(s->ctx), k_seq, (llama_pos) saved.n_tokens, -1);
        // The record of the memory's tokens is cut to the memory's length, whichever that is
        // now. The tokens before it are the ones the checkpoint was saved at: that is the
        // caller's guarantee, on which the restored state rests as much as the record does.
        follow_memory(s);
        return restored && memory_count(s) == saved.n_tokens;
    } catch (...) {
        s->prediction_ready = false;
        follow_memory(s);
        return false;
    }
}

void pp_session_drop_checkpoint(struct pp_session * session, int32_t checkpoint) {
    if (session == nullptr) {
        return;
    }
    const auto it = session->checkpoints.find(checkpoint);
    if (it != session->checkpoints.end()) {
        free_checkpoint(it->second);
        session->checkpoints.erase(it);
    }
}

int64_t pp_session_checkpoint_size(const struct pp_session * session, int32_t checkpoint) {
    if (session == nullptr) {
        return -1;
    }
    const auto it = session->checkpoints.find(checkpoint);
    return it == session->checkpoints.end() ? -1 : (int64_t) it->second.size;
}

int32_t pp_session_clear_memory(struct pp_session * session) {
    if (session == nullptr) {
        return PP_STATUS_INVALID_ARGUMENT;
    }
    pp_session * s = session;
    s->prediction_ready = false;
    free_all_checkpoints(s);
    wipe(s->candidates.data(), s->candidates.size() * sizeof(llama_token_data));
    // Freed and created again, not reset: a context that is only cleared keeps its copies of
    // the tokens it evaluated. Freeing does not overwrite them either (see the header for what
    // remains), but it ends their being part of a live object, and it is the only way to the
    // buffers that destroy_context does overwrite; the session's own record of the memory's
    // tokens is overwritten there too. The sampler holds no token, and is created again so
    // that its random numbers start where they started.
    destroy_sampler(s);
    destroy_context(s);
    // A new context must not inherit the resident pages of the one it replaces: they would stay
    // resident under its untouched reservations, and they hold what was not overwritten.
    release_free_pages();

    int32_t status = PP_STATUS_OK;
    try {
        // At the size the session was opened with, whatever the context had grown to: the
        // conversation that needed more is gone, and so is the reason to hold its memory.
        const int64_t cost = context_cost(s, s->n_ctx_open);
        if ((cost >= 0 && !can_reserve(cost)) || !create_context(s, s->n_ctx_open)) {
            status = fail(s, PP_STATUS_OUT_OF_MEMORY, "the context could not be created again after the conversation was forgotten");
        }
        if (s->sampler_configured) {
            sampler_parts parts;
            build_sampler(s->n_vocab, s->sampler_config, parts);
            install_sampler(s, parts, PP_REPLY_START_AT_SAMPLING);
        }
    } catch (...) {
        status = fail(s, PP_STATUS_OUT_OF_MEMORY, "out of memory while creating the context and the sampler again");
    }
    release_free_pages();
    return status;
}

int32_t pp_session_max_context_size(const struct pp_session * session) {
    return session == nullptr ? 0 : session->n_ctx_max;
}

int64_t pp_session_context_bytes(const struct pp_session * session, int32_t n_ctx) {
    return session == nullptr || n_ctx > session->n_ctx_max ? -1 : context_cost(session, n_ctx);
}

int64_t pp_session_carry_bytes(const struct pp_session * session) {
    try {
        if (session == nullptr || session->ctx == nullptr || memory_count(session) < 1) {
            return 0;
        }
        return (int64_t) llama_state_seq_get_size_ext(session->ctx, k_seq, LLAMA_STATE_SEQ_FLAGS_NONE);
    } catch (...) {
        return 0;
    }
}

int32_t pp_session_resize_context(struct pp_session * session, int32_t n_ctx, int64_t budget_bytes) {
    if (session == nullptr) {
        return PP_STATUS_INVALID_ARGUMENT;
    }
    pp_session * s = session;
    if (n_ctx < 1 || budget_bytes < 1) {
        return fail(s, PP_STATUS_INVALID_ARGUMENT, "pp_session_resize_context needs a context of at least 1 token and a budget of at least 1 byte");
    }

    // --- What is refused is refused here, before anything is touched ---
    uint8_t * state = nullptr;
    size_t state_size = 0;
    int32_t n_old = 0, count = 0;
    int64_t cost_new = -1;
    try {
        if (n_ctx > s->n_ctx_max) {
            return fail(s, PP_STATUS_CONTEXT_REFUSED, "a context of " + std::to_string(n_ctx) + " tokens was asked for, and the model was trained for " + std::to_string(s->n_ctx_max));
        }
        cost_new = context_cost(s, n_ctx);
        if (cost_new < 0) {
            return fail(s, PP_STATUS_CONTEXT_REFUSED, "what a context costs is not known: llama.cpp's report of the buffers it allocates was not understood");
        }
        if (cost_new > budget_bytes) {
            return fail(s, PP_STATUS_CONTEXT_REFUSED, "a context of " + std::to_string(n_ctx) + " tokens needs " + std::to_string(cost_new) +
                                                      " bytes and the budget is " + std::to_string(budget_bytes));
        }
        n_old = s->ctx == nullptr ? 0 : s->n_ctx;
        n_ctx = rounded_ctx(n_ctx);
        if (n_old == n_ctx) {
            return PP_STATUS_OK; // it is that size: nothing is done, and nothing is lost
        }

        // --- The memory, to be put into the new context as it is, when that fits ---
        // llama.cpp writes the state of the sequence into one block and reads it from one, so
        // carrying it over means holding that block beside a context: beside the old one while
        // it is written, beside the new one while it is read. It is taken only when both fit
        // the budget. The old context is freed before the new one is created, never the other
        // way round: the two together would be the highest peak of all.
        count = memory_count(s);
        const int64_t cost_old = context_cost(s, n_old);
        if (count > 0 && count <= n_ctx && cost_old >= 0) {
            const size_t size = llama_state_seq_get_size_ext(s->ctx, k_seq, LLAMA_STATE_SEQ_FLAGS_NONE);
            if (size > 0 && size < (size_t) std::numeric_limits<int64_t>::max() && (int64_t) size <= budget_bytes - std::max(cost_old, cost_new)) {
                state = (uint8_t *) std::malloc(size);
                if (state != nullptr && llama_state_seq_get_data_ext(s->ctx, state, size, k_seq, LLAMA_STATE_SEQ_FLAGS_NONE) == size) {
                    state_size = size;
                } else if (state != nullptr) {
                    wipe(state, size);
                    std::free(state);
                    state = nullptr;
                }
            }
        }
    } catch (...) {
        if (state != nullptr) {
            wipe(state, state_size);
            std::free(state);
        }
        return fail(s, PP_STATUS_CONTEXT_REFUSED, "the context could not be measured for the change of size; it is as it was");
    }

    // --- From here on the old context goes, as at a clear ---
    bool carried = state != nullptr;
    s->prediction_ready = false;
    wipe(s->candidates.data(), s->candidates.size() * sizeof(llama_token_data));
    if (!carried) {
        free_all_checkpoints(s);
    }
    destroy_context(s, carried);
    // A new context must not inherit the resident pages of the one it replaces: they would stay
    // resident under its untouched reservations, and they hold what was not overwritten.
    release_free_pages();

    // The size that was asked for; if it cannot be had, the size there was; then halves of
    // that, down to one token. Each only after the address space was seen to have room for it.
    bool created = false, as_asked = false;
    const auto attempt = [&](int32_t n) {
        try {
            const int64_t cost = context_cost(s, n);
            // A failed attempt leaves the record of the memory's tokens and where the reply
            // began as they are (see create_context): a memory being carried over keeps them
            // through every attempt until it is read into a context or given up below.
            return cost >= 0 && cost <= budget_bytes && can_reserve(cost) && create_context(s, n);
        } catch (...) {
            return false;
        }
    };
    created = as_asked = attempt(n_ctx);
    // Down to the smallest context llama.cpp makes: below it every size is that one.
    for (int32_t n = n_old > 0 ? n_old : n_ctx / 2; !created && n >= 1; n = n > k_ctx_step ? std::max(k_ctx_step, n / 2) : 0) {
        // The failed attempt before may have allocated and freed part of a context; the next
        // must not inherit those pages either, for the same two reasons.
        release_free_pages();
        created = attempt(n);
    }

    // --- The memory again, or none ---
    if (carried) {
        try {
            carried = created && count <= s->n_ctx &&
                      llama_state_seq_set_data_ext(s->ctx, state, state_size, k_seq, LLAMA_STATE_SEQ_FLAGS_NONE) == state_size &&
                      memory_count(s) == count && (int32_t) s->history.size() == count;
        } catch (...) {
            carried = false;
        }
        wipe(state, state_size);
        std::free(state);
        if (!carried) {
            // The block could not be read into the context there is now (it may be a smaller
            // one than was asked for): an empty memory, and nothing that described the old one.
            try {
                if (s->ctx != nullptr) {
                    llama_memory_clear(llama_get_memory(s->ctx), true);
                }
            } catch (...) {
            }
            free_all_checkpoints(s);
            cut_history(s, 0);
            s->reply_start = -1;
        }
    }
    bool sampler_lost = false;
    if (!carried) {
        // As after a clear: the sampler begins again where it began, and a reply position that
        // was configured named a place in the memory that is gone.
        destroy_sampler(s);
        try {
            if (s->sampler_configured) {
                sampler_parts parts;
                build_sampler(s->n_vocab, s->sampler_config, parts);
                install_sampler(s, parts, PP_REPLY_START_AT_SAMPLING);
            }
        } catch (...) {
            sampler_lost = true;
        }
    }
    release_free_pages();
    // A missing sampler outranks a size other than the one asked for: the session cannot
    // sample until it is configured again, and that is what the caller must hear. Each message
    // is said without an allocation when there is no memory even for the sentence.
    if (sampler_lost) {
        try {
            return fail(s, PP_STATUS_OUT_OF_MEMORY, as_asked
                    ? std::string("out of memory while creating the sampler again")
                    : "out of memory while creating the sampler again; also, a context of " + std::to_string(n_ctx) + " tokens could not be created and the session has " +
                      (created ? "one of " + std::to_string(s->n_ctx) : std::string("none")));
        } catch (...) {
            return fail(s, PP_STATUS_OUT_OF_MEMORY, "out of memory while creating the sampler again");
        }
    }
    if (!as_asked) {
        try {
            return fail(s, PP_STATUS_CONTEXT_NOT_CREATED, created
                    ? "a context of " + std::to_string(n_ctx) + " tokens could not be created; the session has one of " + std::to_string(s->n_ctx) + " now"
                    : "a context of " + std::to_string(n_ctx) + " tokens could not be created, and no smaller one either: the session has none");
        } catch (...) {
            return fail(s, PP_STATUS_CONTEXT_NOT_CREATED, "a context of the size asked for could not be created");
        }
    }
    return PP_STATUS_OK;
}

//
// Sampling
//

int32_t pp_session_configure_sampler(struct pp_session * session, const struct pp_sampler_config * config) {
    if (session == nullptr) {
        return PP_STATUS_INVALID_ARGUMENT;
    }
    pp_session * s = session;
    if (config == nullptr || !finite(config->temperature) || config->top_k < 0 || !finite(config->top_p) || !finite(config->min_p) ||
        !finite(config->repetition_penalty) || config->repetition_penalty <= 0.0f || !finite(config->presence_penalty) ||
        !finite(config->frequency_penalty) || (config->penalty_window < 1 && config->penalty_window != PP_PENALTY_NO_WINDOW) ||
        config->reply_start < PP_REPLY_START_AT_SAMPLING || config->n_banned_tokens < 0 || config->n_banned_tokens > s->n_vocab ||
        (config->banned_tokens == nullptr && config->n_banned_tokens > 0)) {
        return fail(s, PP_STATUS_INVALID_ARGUMENT, "a sampler setting is outside its documented range");
    }
    for (int32_t i = 0; i < config->n_banned_tokens; i++) {
        if (config->banned_tokens[i] < 0 || config->banned_tokens[i] >= s->n_vocab) {
            return fail(s, PP_STATUS_INVALID_ARGUMENT, "a banned token id is outside the vocabulary");
        }
    }

    sampler_parts parts;
    pp_sampler_config kept = *config;
    std::vector<llama_token> list;
    std::vector<uint8_t> flags;
    try {
        list.assign(config->banned_tokens, config->banned_tokens + config->n_banned_tokens);
        if (!list.empty()) {
            flags.assign((size_t) s->n_vocab, 0);
            for (const llama_token token : list) {
                flags[(size_t) token] = 1;
            }
        }
        kept.banned_tokens = list.data();
        build_sampler(s->n_vocab, kept, parts);
    } catch (...) {
        return fail(s, PP_STATUS_OUT_OF_MEMORY, "the sampler could not be created");
    }

    // Nothing below can fail: the previous sampler is only given up for a complete new one.
    destroy_sampler(s);
    install_sampler(s, parts, config->reply_start);
    s->banned_list.swap(list);
    s->banned.swap(flags);
    s->sampler_config = kept;
    s->sampler_config.banned_tokens = s->banned_list.data();
    s->sampler_configured = true;
    return PP_STATUS_OK;
}

int32_t pp_session_sample_next(struct pp_session * session, llama_token * token) {
    if (session == nullptr) {
        return PP_STATUS_INVALID_ARGUMENT;
    }
    pp_session * s = session;
    if (token == nullptr) {
        return fail(s, PP_STATUS_INVALID_ARGUMENT, "invalid arguments to pp_session_sample_next");
    }
    *token = LLAMA_TOKEN_NULL;
    if (s->sampler == nullptr || s->chooser == nullptr) {
        return fail(s, PP_STATUS_SAMPLING_FAILED, "no sampler is configured");
    }
    if (!s->prediction_ready) {
        return fail(s, PP_STATUS_SAMPLING_FAILED, "no prediction is ready: sampling needs a complete evaluation straight before it");
    }
    // One prediction, one token.
    s->prediction_ready = false;
    int32_t status = PP_STATUS_OK;
    try {
        // What llama_sampler_sample does, with two differences. The candidate list is the
        // session's own, in place of the one inside the chain, which nothing outside the chain
        // can overwrite afterwards. And the scores are looked at before a token is chosen among
        // them: llama.cpp's samplers assume that there is always something to choose from, and
        // where there is not (every token banned, or scores that have stopped being numbers)
        // they name a token all the same, or end the process on an assertion, depending on how
        // llama.cpp was built.
        const float * logits = llama_get_logits_ith(s->ctx, -1);
        if (logits == nullptr || s->candidates.size() != (size_t) s->n_vocab) {
            status = fail(s, PP_STATUS_SAMPLING_FAILED, "the context holds no prediction");
        } else if ((int32_t) s->history.size() != memory_count(s)) {
            // Every call that changes the memory keeps the record in step with it. Penalties
            // from a record that is not would be wrong and look right, so this is asked.
            status = fail(s, PP_STATUS_SAMPLING_FAILED, "the record of the tokens in memory does not have the memory's length");
        } else {
            for (llama_token id = 0; id < s->n_vocab; id++) {
                s->candidates[(size_t) id] = llama_token_data{id, logits[id], 0.0f};
            }
            // This is where the reply begins, if nothing was sampled since the sampler was
            // configured: the prompt is in memory, and what follows it is the reply.
            if (s->reply_start < 0) {
                s->reply_start = (int32_t) s->history.size();
            }
            apply_penalties(s);
            llama_token_data_array array = {s->candidates.data(), s->candidates.size(), -1, false};
            llama_sampler_apply(s->sampler, &array);
            // The samplers may have moved the array's start and shortened it; what follows
            // looks at the array as it is now.
            bool any_finite = false, all_usable = array.data != nullptr && array.size > 0;
            for (size_t i = 0; all_usable && i < array.size; i++) {
                const float score = array.data[i].logit;
                any_finite = any_finite || std::isfinite(score);
                // Minus infinity is a token that is ruled out; the other two are not scores.
                all_usable = !std::isnan(score) && score != std::numeric_limits<float>::infinity();
            }
            llama_token chosen = LLAMA_TOKEN_NULL;
            if (!all_usable) {
                status = fail(s, PP_STATUS_SAMPLING_FAILED, "the scores are not numbers a token can be chosen by");
            } else if (!any_finite) {
                status = fail(s, PP_STATUS_SAMPLING_FAILED, "every token is ruled out: nothing is left to choose from");
            } else {
                llama_sampler_apply(s->chooser, &array);
                if (array.selected >= 0 && (size_t) array.selected < array.size) {
                    chosen = array.data[array.selected].id;
                }
                if (chosen < 0 || chosen >= s->n_vocab) {
                    status = fail(s, PP_STATUS_SAMPLING_FAILED, "the sampler produced no token");
                } else if (!s->banned.empty() && s->banned[(size_t) chosen] != 0) {
                    // The random choice can land on a token of probability zero when it draws
                    // exactly 0, once in 2^53 draws; a banned token is never returned for it.
                    status = fail(s, PP_STATUS_SAMPLING_FAILED, "the sampler chose a banned token");
                } else {
                    // For the samplers' own state. The penalties learn of the token when it is
                    // evaluated: what they cover is what is in memory.
                    llama_sampler_accept(s->sampler, chosen);
                    llama_sampler_accept(s->chooser, chosen);
                    *token = chosen;
                }
            }
        }
    } catch (const std::bad_alloc &) {
        status = fail(s, PP_STATUS_OUT_OF_MEMORY, "out of memory while sampling");
    } catch (...) {
        status = fail(s, PP_STATUS_SAMPLING_FAILED, "the sampler failed");
    }
    // The prediction is used up either way: its scores go, here and where llama.cpp keeps them.
    wipe(s->candidates.data(), s->candidates.size() * sizeof(llama_token_data));
    try {
        wipe_prediction(s);
    } catch (...) {
        s->has_prediction_data = false;
    }
    return status;
}
