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
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>

#if defined(__ANDROID__)
#include <malloc.h>
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

//
// Logging
//

// What the log callback keeps. The mutex is only taken while something is being captured, which
// is during pp_runtime_init and during the opening of a session; at every other time the
// callback returns after one atomic load.
std::atomic<bool> g_capturing{false};
std::mutex        g_capture_mutex;
std::string *     g_capture = nullptr;
bool              g_capture_info = false; // also informational lines (the start-up), not only errors

constexpr size_t k_capture_limit = 4096;

void log_sink(enum ggml_log_level level, const char * text, void *) {
    if (!g_capturing.load(std::memory_order_relaxed) || text == nullptr) {
        return;
    }
    std::lock_guard<std::mutex> lock(g_capture_mutex);
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
    llama_sampler *          sampler = nullptr;
    pp_peacebell_tokenizer * peacebell = nullptr; // set when the model needs the front-end

    int32_t n_vocab = 0;
    int32_t n_batch = 0;

    // A token the model must never be given as input, or LLAMA_TOKEN_NULL. Boris-2 treats one
    // sequence as one document: its graph asserts that the end-of-text token is not an input,
    // and a failed assertion ends the process. The session answers with an error instead.
    llama_token refused_input = LLAMA_TOKEN_NULL;

    // True when llama.cpp can take an interrupted micro-batch out of the memory again, which is
    // the case for attention-only memory. Only then is the abort callback installed.
    bool can_abort = false;

    // A prediction is ready from the end of a complete evaluation until it is sampled or the
    // memory changes. has_prediction_data says that the context's output buffer holds a
    // prediction at all, fresh or stale: clearing overwrites it.
    bool prediction_ready = false;
    bool has_prediction_data = false;

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

int32_t memory_count(const pp_session * s) {
    return (int32_t) llama_memory_seq_pos_max(llama_get_memory(s->ctx), k_seq) + 1;
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

bool valid_params(const pp_session_params * params) {
    if (params == nullptr || params->n_ctx < 1 || params->n_batch < 1 || params->n_threads < 1 ||
        params->n_threads > GGML_MAX_N_THREADS || params->n_pinned_cpus < 0 || params->n_pinned_cpus > GGML_MAX_N_THREADS ||
        (params->pinned_cpus == nullptr && params->n_pinned_cpus > 0) ||
        (params->load_mode != PP_LOAD_MODE_READ && params->load_mode != PP_LOAD_MODE_MMAP)) {
        return false;
    }
    for (int32_t i = 0; i < params->n_pinned_cpus; i++) {
        // ggml's CPU mask has GGML_MAX_N_THREADS entries; a CPU beyond it cannot be named.
        if (params->pinned_cpus[i] < 0 || params->pinned_cpus[i] >= GGML_MAX_N_THREADS) {
            return false;
        }
        for (int32_t j = 0; j < i; j++) {
            if (params->pinned_cpus[j] == params->pinned_cpus[i]) {
                return false;
            }
        }
    }
    return true;
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

// Makes the thread pool fit what the system allows at this moment. Called at the start of every
// evaluation, on the evaluating thread; it returns at once while nothing has changed.
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

// Loads the model through load(), then builds everything else a session needs. model_limit is
// the number of bytes the model may occupy in its file, or -1 when the file is the model.
template <typename Load>
int32_t open_session(const pp_session_params * params, int64_t model_limit, pp_session ** session, char * error, size_t error_size, Load && load) {
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
        if (model_limit >= 0 && llama_model_size(s->model) > (uint64_t) model_limit) {
            put_error(error, error_size, "the model's tensors do not fit in the given length of the file");
            return PP_STATUS_LOAD_FAILED;
        }

        llama_context_params cparams = llama_context_default_params();
        cparams.n_ctx           = (uint32_t) params->n_ctx;
        // One llama_process call is one micro-batch, so that "a chunk" means the same thing to
        // the caller, to the abort callback and to llama.cpp's own roll-back of a failed batch.
        cparams.n_batch         = (uint32_t) params->n_batch;
        cparams.n_ubatch        = (uint32_t) params->n_batch;
        cparams.n_seq_max       = 1;
        cparams.n_threads       = params->n_threads;
        cparams.n_threads_batch = params->n_threads;
        cparams.no_perf         = true;

        log.clear();
        s->ctx = llama_init_from_model(s->model, cparams);
        if (s->ctx == nullptr) {
            put_error(error, error_size, log.empty() ? std::string("llama.cpp could not create the context") : tidy(log));
            return mentions_memory(log) ? PP_STATUS_OUT_OF_MEMORY : PP_STATUS_LOAD_FAILED;
        }

        s->vocab   = llama_model_get_vocab(s->model);
        s->n_vocab = llama_vocab_n_tokens(s->vocab);
        s->n_batch = (int32_t) llama_n_batch(s->ctx);
        s->batch   = llama_batch_ext_init(s->ctx);
        if (s->batch == nullptr || s->n_vocab <= 0 || s->n_batch <= 0) {
            put_error(error, error_size, "llama.cpp could not create the batch");
            return PP_STATUS_LOAD_FAILED;
        }

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

        s->can_abort = !llama_model_is_hybrid(s->model) && !llama_model_is_recurrent(s->model);
        if (s->can_abort) {
            llama_set_abort_callback(s->ctx, abort_callback, s);
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
    if (path == nullptr || !valid_params(params)) {
        put_error(error, error_size, "invalid arguments to pp_session_open_path");
        return PP_STATUS_INVALID_ARGUMENT;
    }
    return open_session(params, -1, session, error, error_size, [&](const llama_model_params & mparams) {
        return llama_model_load_from_file(path, mparams);
    });
}

int32_t pp_session_open_fd(int fd, int64_t offset, int64_t length, const struct pp_session_params * params, struct pp_session ** session, char * error, size_t error_size) {
    put_error(error, error_size, "");
    if (session == nullptr) {
        return PP_STATUS_INVALID_ARGUMENT;
    }
    *session = nullptr;
    struct stat info;
    if (fd < 0 || !valid_params(params) || offset < 0 || length < 4 || fstat(fd, &info) != 0 || !S_ISREG(info.st_mode) ||
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
    const int32_t status = open_session(params, length, session, error, error_size, [&](const llama_model_params & mparams) -> llama_model * {
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
    try {
        if (session->sampler != nullptr) {
            llama_sampler_free(session->sampler);
        }
        free_all_checkpoints(session);
        if (session->batch != nullptr) {
            llama_batch_ext_free(session->batch);
        }
        if (session->peacebell != nullptr) {
            pp_peacebell_tokenizer_free(session->peacebell);
        }
        if (session->ctx != nullptr) {
            llama_free(session->ctx);
            session->ctx = nullptr;
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
#if defined(__ANDROID__)
    // Android's allocator keeps the pages of freed blocks for reuse and only gives them back to
    // the system some time later, when it is next used. After a model that is hundreds of
    // megabytes which the phone needs now, so they are given back at once.
    mallopt(M_PURGE, 0);
#endif
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
    return session == nullptr ? 0 : (int32_t) llama_n_ctx(session->ctx);
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

    // Whatever happens below, the old prediction no longer belongs to the end of the memory.
    s->prediction_ready = false;
    int32_t status = PP_STATUS_OK;
    int32_t start = 0;
    try {
        prepare_threads(s);
        start = memory_count(s);
        const int32_t chunk = std::min(chunk_size, s->n_batch);
        s->observer = observer;
        s->cancelled = false;

        int32_t done = 0;
        while (done < n_tokens) {
            if (poll_cancelled(s)) {
                break;
            }
            const int32_t n_chunk = std::min(chunk, n_tokens - done);
            const int32_t before = memory_count(s);

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
            s->has_prediction_data = true;
        }
    } catch (const std::bad_alloc &) {
        status = fail(s, PP_STATUS_OUT_OF_MEMORY, "out of memory during evaluation");
    } catch (...) {
        status = fail(s, PP_STATUS_EVALUATION_FAILED, "llama.cpp failed during evaluation");
    }
    s->observer = nullptr;
    // The token ids of the last chunk stay in the batch until the next call or a clear.
    try {
        *n_in_memory = std::max(0, memory_count(s) - start);
    } catch (...) {
        *n_in_memory = 0;
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
    try {
        const int32_t count = memory_count(s);
        if (removed == nullptr || n_tokens < 0 || n_tokens > count) {
            return fail(s, PP_STATUS_INVALID_ARGUMENT, "pp_session_rewind_to(" + std::to_string(n_tokens) + ") with " + std::to_string(count) + " token(s) in memory");
        }
        if (n_tokens == count) {
            *removed = true;
            return PP_STATUS_OK;
        }
        if (llama_memory_seq_rm(llama_get_memory(s->ctx), k_seq, (llama_pos) n_tokens, -1)) {
            s->prediction_ready = false;
            *removed = true;
            return PP_STATUS_OK;
        }
        // Refused. The contract promises the caller an untouched memory; llama.cpp promises the
        // same of a refusal, and this holds it to that.
        if (memory_count(s) != count) {
            s->prediction_ready = false;
            return fail(s, PP_STATUS_EVALUATION_FAILED, "a refused removal changed the memory");
        }
        return PP_STATUS_OK;
    } catch (...) {
        s->prediction_ready = false;
        return fail(s, PP_STATUS_EVALUATION_FAILED, "llama_memory_seq_rm failed");
    }
}

int32_t pp_session_save_checkpoint(struct pp_session * session) {
    if (session == nullptr) {
        return -1;
    }
    pp_session * s = session;
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
        if (llama_state_seq_set_data_ext(s->ctx, saved.data, saved.size, k_seq, LLAMA_STATE_SEQ_FLAGS_PARTIAL_ONLY) != saved.size) {
            return false;
        }
        // The state above only covers what is not attention; the attention part still holds
        // everything that was evaluated since and is cut back to the same length here.
        if (!llama_memory_seq_rm(llama_get_memory(s->ctx), k_seq, (llama_pos) saved.n_tokens, -1)) {
            return false;
        }
        return memory_count(s) == saved.n_tokens;
    } catch (...) {
        s->prediction_ready = false;
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

void pp_session_clear_memory(struct pp_session * session) {
    if (session == nullptr) {
        return;
    }
    pp_session * s = session;
    s->prediction_ready = false;
    free_all_checkpoints(s);
    try {
        // true: the key-value and state buffers themselves, not only the bookkeeping.
        llama_memory_clear(llama_get_memory(s->ctx), true);
        if (s->sampler != nullptr) {
            llama_sampler_reset(s->sampler);
        }
        // Two things outside the memory are still derived from the conversation: the scores of
        // the last prediction and the token ids of the last chunk. Neither has a clearing call,
        // so both are overwritten in place.
        if (s->has_prediction_data) {
            float * logits = llama_get_logits_ith(s->ctx, -1);
            if (logits != nullptr) {
                wipe(logits, (size_t) s->n_vocab * sizeof(float));
            }
            s->has_prediction_data = false;
        }
        llama_batch_ext_clear(s->batch);
        for (int32_t i = 0; i < s->n_batch; i++) {
            if (llama_batch_ext_add_token(s->batch, k_seq, 0) < 0) {
                break;
            }
        }
        llama_batch_ext_clear(s->batch);
    } catch (...) {
        // The memory's own clear cannot fail; whatever threw was one of the extras above.
    }
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
        !finite(config->frequency_penalty) || config->penalty_window < 1 || config->n_banned_tokens < 0 ||
        (config->banned_tokens == nullptr && config->n_banned_tokens > 0)) {
        return fail(s, PP_STATUS_INVALID_ARGUMENT, "a sampler setting is outside its documented range");
    }
    for (int32_t i = 0; i < config->n_banned_tokens; i++) {
        if (config->banned_tokens[i] < 0 || config->banned_tokens[i] >= s->n_vocab) {
            return fail(s, PP_STATUS_INVALID_ARGUMENT, "a banned token id is outside the vocabulary");
        }
    }

    llama_sampler * chain = nullptr;
    try {
        chain = llama_sampler_chain_init(llama_sampler_chain_default_params());
        if (chain == nullptr) {
            return fail(s, PP_STATUS_OUT_OF_MEMORY, "the sampler could not be created");
        }
        // The chain owns what is added to it; freeing the chain frees the whole of it.
        const auto add = [&](llama_sampler * sampler) {
            if (sampler == nullptr) {
                throw std::bad_alloc();
            }
            llama_sampler_chain_add(chain, sampler);
        };

        if (config->n_banned_tokens > 0) {
            std::vector<llama_logit_bias> biases;
            biases.reserve((size_t) config->n_banned_tokens);
            for (int32_t i = 0; i < config->n_banned_tokens; i++) {
                biases.push_back({config->banned_tokens[i], -std::numeric_limits<float>::infinity()});
            }
            add(llama_sampler_init_logit_bias(s->n_vocab, (int32_t) biases.size(), biases.data()));
        }
        if (config->repetition_penalty != 1.0f || config->frequency_penalty != 0.0f || config->presence_penalty != 0.0f) {
            add(llama_sampler_init_penalties(s->n_vocab, config->penalty_window, config->repetition_penalty,
                                             config->frequency_penalty, config->presence_penalty));
        }
        if (config->temperature > 0.0f) {
            add(llama_sampler_init_temp(config->temperature));
            if (config->top_k != 0) {
                add(llama_sampler_init_top_k(config->top_k));
            }
            if (config->top_p < 1.0f) {
                add(llama_sampler_init_top_p(config->top_p, 1));
            }
            if (config->min_p > 0.0f) {
                add(llama_sampler_init_min_p(config->min_p, 1));
            }
            add(llama_sampler_init_dist(config->seed));
        } else {
            add(llama_sampler_init_greedy());
        }
    } catch (...) {
        if (chain != nullptr) {
            llama_sampler_free(chain);
        }
        return fail(s, PP_STATUS_OUT_OF_MEMORY, "the sampler could not be created");
    }

    if (s->sampler != nullptr) {
        llama_sampler_free(s->sampler);
    }
    s->sampler = chain;
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
    if (s->sampler == nullptr) {
        return fail(s, PP_STATUS_SAMPLING_FAILED, "no sampler is configured");
    }
    if (!s->prediction_ready) {
        return fail(s, PP_STATUS_SAMPLING_FAILED, "no prediction is ready: sampling needs a complete evaluation straight before it");
    }
    // One prediction, one token: sampling it again would record the token twice in the sampler.
    s->prediction_ready = false;
    try {
        const llama_token sampled = llama_sampler_sample(s->sampler, s->ctx, -1);
        if (sampled < 0 || sampled >= s->n_vocab) {
            return fail(s, PP_STATUS_SAMPLING_FAILED, "the sampler produced no token");
        }
        *token = sampled;
        return PP_STATUS_OK;
    } catch (const std::bad_alloc &) {
        return fail(s, PP_STATUS_OUT_OF_MEMORY, "out of memory while sampling");
    } catch (...) {
        return fail(s, PP_STATUS_SAMPLING_FAILED, "the sampler failed");
    }
}
