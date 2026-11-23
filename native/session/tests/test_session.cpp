// Conformance of the generation session to the contract of include/proseplow_session.h, against
// a real model file.
//
//   test_session --gguf FILE [--ref FILE] [--long-ref FILE] [--vectors FILE]
//                [--embedded FILE:OFFSET:LENGTH] [--embedded-unaligned FILE:OFFSET:LENGTH]
//
// The program asks the model file what kind of memory it has and expects the matching behaviour:
// an attention-only model (peacebell) must let the end of its memory be removed and must stop in
// the middle of a chunk when cancelled; a model with recurrent state (Qwen3.5) must refuse the
// removal, leave its memory untouched by the refusal, and come back exactly through a checkpoint.
// A model with windowed layers (Shard-2) is attention only, and has a third behaviour on top:
// a checkpoint of it is the window of those layers, and once one was restored the session must
// refuse the cuts that the restored window cannot serve.
//
//   --long-ref  for a model with windowed layers: a logit reference whose sequences are longer
//               than the window; only its token sequences are used. Required for such a model.
//
//   --ref       a logit reference of native/tests/refgen (PPLOGIT1): its prompts become the
//               test's prompts, and the greedy reply through the session must equal the
//               reference's greedy reply token for token. Required for peacebell, whose prompts
//               are marker ids around separately encoded text.
//   --vectors   the committed token vectors (PPTOKV01): tokenising through the session must
//               reproduce them, plain text the "safe" ids (special tokens spelled in the text
//               stay characters) and parse_special the "exact" ones (the reference as it is).
//   --embedded  a file that holds the model at a 16 KiB-aligned offset among other data, the way
//               an APK holds an asset: loading by descriptor, offset and length must give the
//               same model, read or mapped, and a mapping must cover the model's bytes only.
//   --embedded-unaligned  the same at an offset that is not a multiple of 32: reading still
//               works, mapping is refused cleanly.
//
// Every check prints one line with what was measured. Exit codes as in test_support.h.

#include "proseplow_session.h"
#include "support/logit_support.h"

#include <algorithm>
#include <cinttypes>
#include <limits>
#include <numeric>
#include <set>

#include <dirent.h>
#include <fcntl.h>
#include <sys/resource.h>
#include <unistd.h>

namespace {

using pp_test::report;

// While one of these exists the process cannot grow its address space by more than `room`
// bytes: an allocation beyond that fails instead of being attempted.
//
// It is there for the checks that hand the session a size no machine can serve (a context of
// two thousand million tokens is 104 GiB of bookkeeping alone). The session must refuse such a
// size before anything is allocated; if a change ever breaks that, the check has to fail, and
// fail by itself: without the limit it would take the machine it runs on with it, which is
// what the first version of these checks did. Never remove the limit from around them.
class address_space_limit {
  public:
    explicit address_space_limit(uint64_t room) {
        uint64_t mapped_kb = 0;
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
        // Counted from what is mapped now, which under a sanitizer is terabytes of reservation.
        if (mapped_kb != 0 && getrlimit(RLIMIT_AS, &before) == 0) {
            struct rlimit limited = before;
            limited.rlim_cur = (rlim_t) (mapped_kb * 1024 + room);
            if (before.rlim_max == RLIM_INFINITY || limited.rlim_cur <= before.rlim_max) {
                active = setrlimit(RLIMIT_AS, &limited) == 0;
            }
        }
    }
    ~address_space_limit() {
        if (active) {
            setrlimit(RLIMIT_AS, &before);
        }
    }
    address_space_limit(const address_space_limit &) = delete;
    address_space_limit & operator=(const address_space_limit &) = delete;

    // False where the limit could not be set: the checks it guards are then not run at all.
    bool in_force() const { return active; }

  private:
    struct rlimit before = {};
    bool active = false;
};

constexpr int32_t k_n_ctx   = 1024;
constexpr int32_t k_n_batch = 64;
// The engine's default chunk; the reference replies were cut at 96 tokens.
constexpr int32_t k_chunk          = 32;
constexpr int     k_max_new_tokens = 96;

struct session_closer {
    void operator()(pp_session * s) const { pp_session_close(s); }
};
using session_ptr = std::unique_ptr<pp_session, session_closer>;

pp_session_params default_params() {
    pp_session_params params = {};
    params.n_ctx     = k_n_ctx;
    params.n_batch   = k_n_batch;
    params.n_threads = pp_test::n_threads();
    params.load_mode = PP_LOAD_MODE_READ;
    return params;
}

// What the test's observer was told and what it answers.
struct watcher {
    int polls = 0;                 // calls of is_cancelled
    int cancel_at_poll = -1;       // answer true from this poll on (1-based); -1: never
    int cancel_at_progress = -1;   // answer true once this many tokens were reported; -1: never
    std::vector<int32_t> progress; // every value passed to on_progress
    int64_t cancelled_at_us = 0;   // when true was first answered

    static bool is_cancelled(void * data) {
        watcher * w = (watcher *) data;
        w->polls++;
        const bool by_poll = w->cancel_at_poll >= 0 && w->polls >= w->cancel_at_poll;
        const bool by_progress = w->cancel_at_progress >= 0 && !w->progress.empty() && w->progress.back() >= w->cancel_at_progress;
        if ((by_poll || by_progress) && w->cancelled_at_us == 0) {
            w->cancelled_at_us = llama_time_us();
        }
        return by_poll || by_progress;
    }

    static void on_progress(int32_t n, void * data) { ((watcher *) data)->progress.push_back(n); }

    pp_evaluation_observer observer() { return {is_cancelled, on_progress, this}; }
};

// The result of one pp_session_evaluate.
struct evaluation {
    int32_t status = PP_STATUS_OK;
    int32_t n_in_memory = 0;
    double  seconds = 0.0;
    int64_t finished_us = 0;
};

evaluation evaluate(pp_session * s, const std::vector<llama_token> & tokens, int32_t chunk, watcher * w = nullptr) {
    watcher quiet;
    pp_evaluation_observer observer = (w != nullptr ? w : &quiet)->observer();
    evaluation e;
    const int64_t t0 = llama_time_us();
    e.status = pp_session_evaluate(s, tokens.data(), (int32_t) tokens.size(), chunk, &observer, &e.n_in_memory);
    e.finished_us = llama_time_us();
    e.seconds = (double) (e.finished_us - t0) / 1e6;
    return e;
}

void must_evaluate(pp_session * s, const std::vector<llama_token> & tokens, int32_t chunk = k_chunk) {
    const evaluation e = evaluate(s, tokens, chunk);
    if (e.status != PP_STATUS_OK || e.n_in_memory != (int32_t) tokens.size()) {
        pp_test::die(std::string("evaluation failed: ") + pp_session_last_error(s));
    }
}

std::vector<llama_token> tokenize(pp_session * s, const std::string & text, bool parse_special) {
    const int32_t needed = pp_session_tokenize(s, text.data(), (int32_t) text.size(), parse_special, nullptr, 0);
    if (needed == PP_ERROR || needed > 0) {
        pp_test::die("pp_session_tokenize failed to measure");
    }
    std::vector<llama_token> out((size_t) -needed);
    if (pp_session_tokenize(s, text.data(), (int32_t) text.size(), parse_special, out.data(), (int32_t) out.size()) != -needed) {
        pp_test::die("pp_session_tokenize failed to fill");
    }
    return out;
}

std::string token_bytes(pp_session * s, llama_token token) {
    const int32_t needed = pp_session_token_to_bytes(s, token, nullptr, 0);
    if (needed == PP_ERROR || needed > 0) {
        pp_test::die("pp_session_token_to_bytes failed to measure");
    }
    std::string out((size_t) -needed, '\0');
    if (needed != 0 && pp_session_token_to_bytes(s, token, out.data(), (int32_t) out.size()) != -needed) {
        pp_test::die("pp_session_token_to_bytes failed to fill");
    }
    return out;
}

std::string detokenize(pp_session * s, const std::vector<llama_token> & tokens) {
    std::string text;
    for (const llama_token t : tokens) {
        text += token_bytes(s, t);
    }
    return text;
}

pp_sampler_config greedy_config(const std::vector<llama_token> & banned) {
    pp_sampler_config config = {};
    config.temperature        = 0.0f;
    config.top_p              = 1.0f;
    config.repetition_penalty = 1.0f;
    config.penalty_window     = k_n_ctx;
    config.banned_tokens      = banned.data();
    config.n_banned_tokens    = (int32_t) banned.size();
    return config;
}

void must_configure(pp_session * s, const pp_sampler_config & config) {
    if (pp_session_configure_sampler(s, &config) != PP_STATUS_OK) {
        pp_test::die(std::string("pp_session_configure_sampler failed: ") + pp_session_last_error(s));
    }
}

// Samples and evaluates token by token from a ready prediction, the way the engine does, until
// the stop token, an end-of-generation token (when stop is LLAMA_TOKEN_NULL) or n_max tokens.
// The ending token is not part of the result and is not evaluated.
std::vector<llama_token> continue_reply(pp_session * s, int n_max, llama_token stop, double * seconds = nullptr) {
    std::vector<llama_token> reply;
    const int64_t t0 = llama_time_us();
    for (int i = 0; i < n_max; i++) {
        llama_token token = LLAMA_TOKEN_NULL;
        if (pp_session_sample_next(s, &token) != PP_STATUS_OK) {
            pp_test::die(std::string("pp_session_sample_next failed: ") + pp_session_last_error(s));
        }
        if (stop != LLAMA_TOKEN_NULL ? token == stop : pp_session_is_end_of_generation(s, token)) {
            break;
        }
        reply.push_back(token);
        if (i + 1 < n_max) {
            must_evaluate(s, {token}, 1);
        }
    }
    if (seconds != nullptr) {
        *seconds += (double) (llama_time_us() - t0) / 1e6;
    }
    return reply;
}

// An empty memory, the prompt, a greedy reply.
std::vector<llama_token> fresh_reply(pp_session * s, const std::vector<llama_token> & prompt, int n_max, llama_token stop,
                                     const std::vector<llama_token> & banned, int32_t chunk = k_chunk) {
    pp_session_clear_memory(s);
    must_evaluate(s, prompt, chunk);
    must_configure(s, greedy_config(banned));
    return continue_reply(s, n_max, stop);
}

std::vector<llama_token> concat(std::initializer_list<const std::vector<llama_token> *> parts) {
    std::vector<llama_token> out;
    for (const auto * part : parts) {
        out.insert(out.end(), part->begin(), part->end());
    }
    return out;
}

struct text_vector {
    std::string text;
    std::vector<llama_token> exact;
    std::vector<llama_token> safe;
};

// The texts of a token vector file (native/tests/refgen/formats.py); its conversations are the
// business of the tokenizer's own test.
std::vector<text_vector> load_text_vectors(const std::string & path, std::string & tokenizer_sha256) {
    pp_test::reader in(path);
    in.expect_magic("PPTOKV01");
    tokenizer_sha256 = in.bytes(64);
    std::vector<text_vector> out(in.u32());
    for (text_vector & v : out) {
        v.text = in.sized_bytes();
        v.exact = in.ids_u16();
        v.safe = in.u8() != 0 ? in.ids_u16() : v.exact;
    }
    return out;
}

struct embedded {
    std::string path;
    int64_t offset = 0;
    int64_t length = 0;
    bool given() const { return !path.empty(); }
};

embedded parse_embedded(const std::string & arg) {
    embedded e;
    const size_t second = arg.rfind(':');
    const size_t first = second == std::string::npos || second == 0 ? std::string::npos : arg.rfind(':', second - 1);
    if (first == std::string::npos) {
        pp_test::die("expected FILE:OFFSET:LENGTH, got " + arg);
    }
    e.path = arg.substr(0, first);
    e.offset = std::atoll(arg.substr(first + 1, second - first - 1).c_str());
    e.length = std::atoll(arg.substr(second + 1).c_str());
    return e;
}

// Resident memory of this process and the part of it that is file-backed, in MiB.
struct resident {
    double total = 0.0;
    double file = 0.0;
};

resident resident_memory() {
    resident r;
    std::FILE * f = std::fopen("/proc/self/status", "r");
    if (f == nullptr) {
        return r;
    }
    char line[256];
    while (std::fgets(line, sizeof(line), f) != nullptr) {
        long kb = 0;
        if (std::sscanf(line, "VmRSS: %ld kB", &kb) == 1) {
            r.total = (double) kb / 1024.0;
        } else if (std::sscanf(line, "RssFile: %ld kB", &kb) == 1) {
            r.file = (double) kb / 1024.0;
        }
    }
    std::fclose(f);
    return r;
}

// The number of bytes of the named file that this process has mapped, from /proc/self/maps.
int64_t mapped_bytes_of(const std::string & path) {
    std::FILE * f = std::fopen("/proc/self/maps", "r");
    if (f == nullptr) {
        return -1;
    }
    int64_t total = 0;
    char line[4352];
    while (std::fgets(line, sizeof(line), f) != nullptr) {
        unsigned long long begin = 0, end = 0;
        if (std::strstr(line, path.c_str()) != nullptr && std::sscanf(line, "%llx-%llx", &begin, &end) == 2) {
            total += (int64_t) (end - begin);
        }
    }
    std::fclose(f);
    return total;
}

#if defined(__linux__)
// How many threads of this process are pinned to exactly the one given CPU.
int threads_pinned_to(int cpu) {
    int n = 0;
    DIR * tasks = opendir("/proc/self/task");
    if (tasks == nullptr) {
        return -1;
    }
    while (const dirent * entry = readdir(tasks)) {
        const pid_t tid = (pid_t) std::atoi(entry->d_name);
        cpu_set_t mask;
        if (tid > 0 && sched_getaffinity(tid, sizeof(mask), &mask) == 0 && CPU_COUNT(&mask) == 1 && CPU_ISSET(cpu, &mask)) {
            n++;
        }
    }
    closedir(tasks);
    return n;
}
#endif

} // namespace

int main(int argc, char ** argv) {
    std::string gguf, ref_path, long_ref_path, vectors_path;
    embedded aligned, unaligned;
    for (int i = 1; i + 1 < argc; i += 2) {
        const std::string key = argv[i];
        if (key == "--gguf") {
            gguf = argv[i + 1];
        } else if (key == "--ref") {
            ref_path = argv[i + 1];
        } else if (key == "--long-ref") {
            long_ref_path = argv[i + 1];
        } else if (key == "--vectors") {
            vectors_path = argv[i + 1];
        } else if (key == "--embedded") {
            aligned = parse_embedded(argv[i + 1]);
        } else if (key == "--embedded-unaligned") {
            unaligned = parse_embedded(argv[i + 1]);
        }
    }
    if (gguf.empty()) {
        std::fprintf(stderr, "usage: %s --gguf FILE [--ref FILE] [--long-ref FILE] [--vectors FILE] [--embedded FILE:OFFSET:LENGTH] [--embedded-unaligned FILE:OFFSET:LENGTH]\n", argv[0]);
        return 2;
    }

    report r;

    // --- The process ---

    const int32_t init_first = pp_runtime_init(nullptr, 0);
    const int32_t init_again = pp_runtime_init(nullptr, 0);
    r.check(init_first == PP_STATUS_OK && init_again == PP_STATUS_OK, "pp_runtime_init succeeds and can be repeated");
    r.note("runtime: %s", pp_runtime_description());

    // The model file's own description, read through libllama directly: the vocabulary only.
    pp_test::model_ptr vocab_model = pp_test::load_model(gguf, true);
    const llama_vocab * vocab = llama_model_get_vocab(vocab_model.get());
    const std::string arch = pp_test::meta(vocab_model.get(), "general.architecture");
    const bool peacebell_spm = arch == "peacebell" && pp_test::meta(vocab_model.get(), "tokenizer.ggml.model") == "llama";
    const bool recurrent = llama_model_is_hybrid(vocab_model.get()) || llama_model_is_recurrent(vocab_model.get());
    // The window of the model's local layers in tokens, 0 for a model without such layers. Read
    // from the metadata: a model loaded for its vocabulary alone has no hyper-parameters.
    const int32_t n_window = std::atoi(pp_test::meta(vocab_model.get(), arch + ".attention.sliding_window").c_str());
    r.note("model: architecture '%s', %s memory%s, %d tokens in the vocabulary", arch.c_str(),
           recurrent ? "attention plus recurrent state" : "attention-only", n_window > 0 ? " with windowed layers" : "", llama_vocab_n_tokens(vocab));

    // --- Opening: what must fail, and how ---

    {
        char error[256];
        pp_session * s = (pp_session *) 1;
        pp_session_params params = default_params();

        int32_t status = pp_session_open_path((gguf + ".does-not-exist").c_str(), &params, &s, error, sizeof(error));
        r.check(status == PP_STATUS_LOAD_FAILED && s == nullptr && error[0] != '\0', "a missing file: LOAD_FAILED (\"%s\")", error);

        status = pp_session_open_path(argv[0], &params, &s, error, sizeof(error));
        r.check(status == PP_STATUS_LOAD_FAILED && s == nullptr, "a file that is not a model: LOAD_FAILED (\"%s\")", error);

        pp_session_params bad = params;
        bad.n_ctx = 0;
        const int32_t bad_ctx = pp_session_open_path(gguf.c_str(), &bad, &s, error, sizeof(error));
        bad = params;
        bad.n_threads = 0;
        const int32_t bad_threads = pp_session_open_path(gguf.c_str(), &bad, &s, error, sizeof(error));
        bad = params;
        const int32_t twice[] = {1, 1};
        bad.pinned_cpus = twice;
        bad.n_pinned_cpus = 2;
        const int32_t bad_cpus = pp_session_open_path(gguf.c_str(), &bad, &s, error, sizeof(error));
        r.check(bad_ctx == PP_STATUS_INVALID_ARGUMENT && bad_threads == PP_STATUS_INVALID_ARGUMENT && bad_cpus == PP_STATUS_INVALID_ARGUMENT && s == nullptr,
                "a context of 0 tokens, 0 threads, a CPU listed twice: INVALID_ARGUMENT (%d, %d, %d)", bad_ctx, bad_threads, bad_cpus);

        // Sizes that no machine can serve. Each must come back as a status, at once and without
        // an allocation; see address_space_limit for why these run inside one.
        {
            const address_space_limit limit((uint64_t) 4 << 30);
            if (limit.in_force()) {
                const resident before = resident_memory();
                const int64_t t0 = llama_time_us();
                char why_ctx[256];
                bad = params;
                bad.n_ctx = std::numeric_limits<int32_t>::max();
                const int32_t huge_ctx = pp_session_open_path(gguf.c_str(), &bad, &s, why_ctx, sizeof(why_ctx));
                bad.n_ctx = PP_CONTEXT_TOKENS_MAX + 1;
                const int32_t over_ceiling = pp_session_open_path(gguf.c_str(), &bad, &s, error, sizeof(error));
                bad = params;
                bad.n_batch = std::numeric_limits<int32_t>::max();
                const int32_t huge_batch = pp_session_open_path(gguf.c_str(), &bad, &s, error, sizeof(error));
                const double seconds = (double) (llama_time_us() - t0) / 1e6;
                const resident after = resident_memory();
                r.check(huge_ctx == PP_STATUS_INVALID_ARGUMENT && over_ceiling == PP_STATUS_INVALID_ARGUMENT && huge_batch == PP_STATUS_INVALID_ARGUMENT &&
                        s == nullptr && seconds < 1.0 && after.total < before.total + 16.0,
                        "a context of %d tokens, one of %d (one above the ceiling), a batch of %d: INVALID_ARGUMENT (%d, %d, %d) in %.3f s, "
                        "resident memory %.0f MiB before and %.0f MiB after (\"%s\")",
                        std::numeric_limits<int32_t>::max(), PP_CONTEXT_TOKENS_MAX + 1, std::numeric_limits<int32_t>::max(), huge_ctx, over_ceiling, huge_batch,
                        seconds, before.total, after.total, why_ctx);

                // More than the model was trained for, where that is below the ceiling: refused
                // once the model is loaded and before a context exists.
                const int32_t n_ctx_train = std::atoi(pp_test::meta(vocab_model.get(), arch + ".context_length").c_str());
                if (n_ctx_train > 0 && n_ctx_train < PP_CONTEXT_TOKENS_MAX) {
                    bad = params;
                    bad.n_ctx = n_ctx_train + 1;
                    const int32_t over_trained = pp_session_open_path(gguf.c_str(), &bad, &s, error, sizeof(error));
                    r.check(over_trained == PP_STATUS_INVALID_ARGUMENT && s == nullptr, "a context of %d tokens for a model trained for %d: INVALID_ARGUMENT (\"%s\")",
                            bad.n_ctx, n_ctx_train, error);
                } else {
                    r.note("the model was trained for %d tokens, which is not below the ceiling of %d: only the ceiling can refuse a context here",
                           n_ctx_train, PP_CONTEXT_TOKENS_MAX);
                }
            } else {
                r.note("no address space limit can be set here: the checks with sizes beyond any machine are NOT run");
            }
        }

        const int fd = open(gguf.c_str(), O_RDONLY);
        const int64_t size = (int64_t) lseek(fd, 0, SEEK_END);
        const int32_t beyond = pp_session_open_fd(fd, 16, size, &params, &s, error, sizeof(error));
        const int32_t no_magic = pp_session_open_fd(fd, 16, size - 16, &params, &s, error, sizeof(error));
        r.check(beyond == PP_STATUS_INVALID_ARGUMENT && no_magic == PP_STATUS_LOAD_FAILED && s == nullptr,
                "a range beyond the file: INVALID_ARGUMENT (%d); an offset without a model: LOAD_FAILED (%d, \"%s\")", beyond, no_magic, error);
        close(fd);

        int n_progress = 0;
        params.progress = [](float, void * data) { return ++*(int *) data < 2; };
        params.progress_user_data = &n_progress;
        status = pp_session_open_path(gguf.c_str(), &params, &s, error, sizeof(error));
        r.check(status == PP_STATUS_LOAD_CANCELLED && s == nullptr && n_progress == 2,
                "a load abandoned by its progress callback: LOAD_CANCELLED after %d call(s) (\"%s\")", n_progress, error);
    }

    // --- The session every other check uses, loaded by path ---

    const resident before_open = resident_memory();
    std::vector<float> fractions;
    session_ptr session;
    {
        char error[256];
        pp_session * s = nullptr;
        pp_session_params params = default_params();
        params.progress = [](float fraction, void * data) {
            ((std::vector<float> *) data)->push_back(fraction);
            return true;
        };
        params.progress_user_data = &fractions;
        const int64_t t0 = llama_time_us();
        const int32_t status = pp_session_open_path(gguf.c_str(), &params, &s, error, sizeof(error));
        const double seconds = (double) (llama_time_us() - t0) / 1e6;
        r.check(status == PP_STATUS_OK && s != nullptr, "the model opens by path in %.2f s%s%s", seconds, status == PP_STATUS_OK ? "" : ": ", error);
        if (status != PP_STATUS_OK) {
            return r.finish();
        }
        session.reset(s);
    }
    pp_session * s = session.get();
    const resident after_open = resident_memory();

    r.check(!fractions.empty() && std::is_sorted(fractions.begin(), fractions.end()) && fractions.front() >= 0.0f && fractions.back() == 1.0f,
            "load progress rises from %.2f to %.2f in %zu report(s)", fractions.empty() ? -1.0f : fractions.front(),
            fractions.empty() ? -1.0f : fractions.back(), fractions.size());
    r.check(pp_session_context_size(s) == k_n_ctx && pp_session_memory_token_count(s) == 0 && pp_session_vocab_size(s) == llama_vocab_n_tokens(vocab),
            "context of %d tokens as requested, empty memory, %d tokens in the vocabulary", pp_session_context_size(s), pp_session_vocab_size(s));

    // --- The prompts: three or more token sequences, the first of them a whole chat prompt ---

    std::vector<std::vector<llama_token>> prompts;
    pp_test::reference ref;
    llama_token stop = LLAMA_TOKEN_NULL;  // LLAMA_TOKEN_NULL: any end-of-generation token
    std::vector<llama_token> banned;      // what the app bans for this model
    std::string expected_word;
    if (peacebell_spm && ref_path.empty()) {
        pp_test::die("a peacebell model needs --ref: its prompts are marker ids around separately encoded text");
    }
    if (!ref_path.empty()) {
        ref = pp_test::load_reference(ref_path);
        for (const pp_test::sequence & q : ref.seqs) {
            prompts.emplace_back(q.ids.begin(), q.ids.begin() + q.n_prompt);
        }
        // The token that ends a reply, where the file names one; a model whose end marker is
        // text has none, and its reference replies are compared at the reference's length.
        const std::string end_token = pp_test::meta(vocab_model.get(), arch + ".chat.end_token_id");
        stop = end_token.empty() ? LLAMA_TOKEN_NULL : (llama_token) std::atoi(end_token.c_str());
        int32_t n_suppress = 0;
        const llama_token * suppress = llama_vocab_get_suppress_tokens(vocab, &n_suppress);
        banned.assign(suppress, suppress + n_suppress);
    } else if (arch == "qwen35") {
        const char * const questions[] = {"What is the capital of Australia?", "Name three colours of the rainbow.", "How many legs does a spider have?"};
        for (const char * question : questions) {
            prompts.push_back(tokenize(s, std::string("<|im_start|>system\nYou are a helpful assistant.<|im_end|>\n<|im_start|>user\n") + question +
                                          "<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n", true));
        }
        expected_word = "Canberra";
    } else {
        const char * const questions[] = {"What is the capital of France?", "Name three colours of the rainbow.", "How many legs does a spider have?"};
        for (const char * question : questions) {
            prompts.push_back(tokenize(s, std::string("<|im_start|>user\n") + question + "<|im_end|>\n<|im_start|>assistant\n", false));
        }
    }
    if (prompts.size() < 3 || prompts[0].size() < 12) {
        pp_test::die("the test needs three prompts of some length");
    }
    const std::vector<llama_token> & a = prompts[0];
    const std::vector<llama_token> b(prompts[1].begin(), prompts[1].begin() + std::min<size_t>(prompts[1].size(), 24));
    const std::vector<llama_token> c(prompts[2].begin(), prompts[2].begin() + std::min<size_t>(prompts[2].size(), 12));
    constexpr int k_tail = 12; // tokens generated to compare two states of the memory

    // --- Tokens ---

    {
        const std::string text = "The war ended in 1945, didn't it?";
        const std::vector<llama_token> tokens = tokenize(s, text, false);
        std::vector<llama_token> small(tokens.size(), -7);
        const int32_t too_small = pp_session_tokenize(s, text.data(), (int32_t) text.size(), false, small.data(), (int32_t) tokens.size() - 1);
        r.check(pp_session_tokenize(s, text.data(), 0, false, nullptr, 0) == 0 && too_small == -(int32_t) tokens.size() &&
                std::all_of(small.begin(), small.end(), [](llama_token t) { return t == -7; }),
                "tokenising: nothing gives nothing; a buffer one token short answers %d and is left untouched", too_small);

        const std::string joined = detokenize(s, tokens);
        r.check(joined == text || joined == " " + text, "the bytes of %zu tokens join to the text they came from", tokens.size());
        r.check(pp_session_token_to_bytes(s, -1, nullptr, 0) == PP_ERROR && pp_session_token_to_bytes(s, pp_session_vocab_size(s), nullptr, 0) == PP_ERROR,
                "a token id outside the vocabulary has no bytes: PP_ERROR");

        // A chat marker that is a control token of this vocabulary, if it has one.
        const std::string marker = "<|im_end|>";
        llama_token marker_id = LLAMA_TOKEN_NULL;
        for (llama_token id = 0; id < llama_vocab_n_tokens(vocab); id++) {
            if (llama_vocab_is_control(vocab, id) && marker == llama_vocab_get_text(vocab, id)) {
                marker_id = id;
                break;
            }
        }
        if (marker_id != LLAMA_TOKEN_NULL) {
            const std::string in_text = "a " + marker + " b";
            const std::vector<llama_token> special = tokenize(s, in_text, true);
            const std::vector<llama_token> plain = tokenize(s, in_text, false);
            r.check(std::count(special.begin(), special.end(), marker_id) == 1 && std::count(plain.begin(), plain.end(), marker_id) == 0 && plain.size() > special.size(),
                    "\"%s\" in text is token %d with parse_special (%zu tokens) and ordinary characters without (%zu tokens)",
                    marker.c_str(), marker_id, special.size(), plain.size());
            r.check(token_bytes(s, marker_id).empty() && pp_session_is_end_of_generation(s, marker_id) == llama_vocab_is_eog(vocab, marker_id),
                    "the control token %d contributes no bytes; end of generation: %s", marker_id, pp_session_is_end_of_generation(s, marker_id) ? "yes" : "no");
        } else {
            r.note("the vocabulary has no \"%s\" control token: the marker checks do not apply", marker.c_str());
        }

        if (peacebell_spm) {
            const std::string bad_utf8 = "caf\xc3";
            r.check(pp_session_tokenize(s, bad_utf8.data(), (int32_t) bad_utf8.size(), false, nullptr, 0) == PP_ERROR,
                    "text that is not valid UTF-8 is refused: PP_ERROR (\"%s\")", pp_session_last_error(s));
        } else {
            // Everything but peacebell goes straight to llama_tokenize with add_special = false.
            const std::string samples[] = {"Hello, world!", " leading and trailing ", "na\xc3\xafve \xf0\x9f\x98\x80 caf\xc3\xa9", std::string("nul\0byte", 8),
                                           "<|im_start|>user\nHi<|im_end|>\n", "<think>\n\n</think>\n\n", "line\n\nbreaks\tand tabs"};
            size_t n_same = 0, n_cases = 0;
            for (const std::string & sample : samples) {
                for (const bool special : {false, true}) {
                    std::vector<llama_token> direct(sample.size() + 8);
                    const int32_t n = llama_tokenize(vocab, sample.data(), (int32_t) sample.size(), direct.data(), (int32_t) direct.size(), false, special);
                    direct.resize(n < 0 ? 0 : (size_t) n);
                    n_cases++;
                    n_same += n >= 0 && tokenize(s, sample, special) == direct ? 1 : 0;
                }
            }
            r.check(n_same == n_cases, "%zu of %zu texts tokenise as llama_tokenize does without added tokens, in both modes", n_same, n_cases);
        }

        if (!vectors_path.empty()) {
            std::string sha;
            const std::vector<text_vector> vectors = load_text_vectors(vectors_path, sha);
            r.check(sha == pp_test::meta(vocab_model.get(), arch + ".tokenizer.source_sha256"), "the token vectors were generated from this model's tokenizer file");
            size_t bad_plain = 0, bad_special = 0, n_tokens = 0;
            for (const text_vector & v : vectors) {
                bad_plain += tokenize(s, v.text, false) == v.safe ? 0 : 1;
                bad_special += tokenize(s, v.text, true) == v.exact ? 0 : 1;
                n_tokens += v.exact.size();
            }
            r.check(bad_plain == 0 && bad_special == 0, "%zu committed texts (%zu tokens): %zu differ as plain text, %zu with parse_special",
                    vectors.size(), n_tokens, bad_plain, bad_special);
        }

        // The longest text that is tokenised, and one byte more.
        {
            const std::string longest((size_t) PP_TOKENIZE_BYTES_MAX, 'a');
            const std::string too_long((size_t) PP_TOKENIZE_BYTES_MAX + 1, 'a');
            const int32_t at_limit = pp_session_tokenize(s, longest.data(), (int32_t) longest.size(), false, nullptr, 0);
            const int32_t beyond = pp_session_tokenize(s, too_long.data(), (int32_t) too_long.size(), false, nullptr, 0);
            r.check(at_limit != PP_ERROR && at_limit < 0 && beyond == PP_ERROR,
                    "a text of %d bytes is tokenised (%d tokens); one byte more is refused before it is looked at: PP_ERROR (\"%s\")", PP_TOKENIZE_BYTES_MAX,
                    at_limit == PP_ERROR ? 0 : -at_limit, pp_session_last_error(s));
        }

        // Tokenising cannot be interrupted, so its time must stay in proportion to the text
        // whatever the text is made of. Characters outside a SentencePiece vocabulary once took
        // time with the square of their number (4 seconds for 256 KiB of line breaks), which is
        // what the comparison of a text with a quarter of it finds: four times for linear work,
        // sixteen and more for quadratic. The quarter second is for the noise of short timings.
        {
            struct kind {
                const char * name;
                const char * unit;
            };
            const kind kinds[] = {{"line breaks", "\n"}, {"Chinese characters", "\xE4\xB8\xAD\xE6\x96\x87"}, {"emoji", "\xF0\x9F\x98\x80"}, {"words", "the quick brown fox "}};
            const auto seconds_for = [&](const char * unit, size_t bytes) {
                std::string text;
                while (text.size() + std::strlen(unit) <= bytes) {
                    text += unit;
                }
                const int64_t t0 = llama_time_us();
                if (pp_session_tokenize(s, text.data(), (int32_t) text.size(), false, nullptr, 0) == PP_ERROR) {
                    pp_test::die(std::string("a long text could not be tokenised: ") + pp_session_last_error(s));
                }
                return (double) (llama_time_us() - t0) / 1e6;
            };
            bool in_proportion = true;
            double slowest = 0.0;
            const char * slowest_name = "";
            for (const kind & k : kinds) {
                const double quarter = seconds_for(k.unit, (size_t) PP_TOKENIZE_BYTES_MAX / 4);
                const double whole = seconds_for(k.unit, (size_t) PP_TOKENIZE_BYTES_MAX);
                in_proportion = in_proportion && whole <= 8.0 * quarter + 0.25 && whole < 5.0;
                if (whole > slowest) {
                    slowest = whole;
                    slowest_name = k.name;
                }
            }
            r.check(in_proportion, "the longest text takes time in proportion to its length for %zu kinds of text; the slowest, %s, took %.3f s",
                    sizeof(kinds) / sizeof(kinds[0]), slowest_name, slowest);
        }
    }

    // --- Evaluating, progress, the calling contract ---

    {
        watcher w;
        const evaluation e = evaluate(s, a, 5, &w);
        std::vector<int32_t> expected;
        for (int32_t done = 5; done < (int32_t) a.size(); done += 5) {
            expected.push_back(done);
        }
        expected.push_back((int32_t) a.size());
        r.check(e.status == PP_STATUS_OK && e.n_in_memory == (int32_t) a.size() && pp_session_memory_token_count(s) == (int32_t) a.size(),
                "%zu tokens evaluated in chunks of 5: %d in memory", a.size(), pp_session_memory_token_count(s));
        r.check(w.progress == expected && w.polls >= (int) expected.size(), "progress after each of %zu chunks, cumulative; the cancel callback polled %d time(s)",
                expected.size(), w.polls);

        const int32_t count = pp_session_memory_token_count(s);
        const std::vector<llama_token> out_of_range = {a[0], pp_session_vocab_size(s)};
        watcher quiet;
        pp_evaluation_observer observer = quiet.observer();
        int32_t n = -1;
        const int32_t none = pp_session_evaluate(s, a.data(), 0, 8, &observer, &n);
        const int32_t no_chunk = pp_session_evaluate(s, a.data(), 4, 0, &observer, &n);
        const int32_t bad_token = evaluate(s, out_of_range, 8).status;
        r.check(none == PP_STATUS_INVALID_ARGUMENT && no_chunk == PP_STATUS_INVALID_ARGUMENT && bad_token == PP_STATUS_INVALID_ARGUMENT &&
                pp_session_memory_token_count(s) == count,
                "no tokens, a chunk size of 0, a token outside the vocabulary: INVALID_ARGUMENT, memory unchanged");
    }

    // --- A token the model cannot take ---

    if (arch == "boris2") {
        // Its graph asserts that the end-of-text token is never an input, and a failed assertion
        // ends the process. Through the session it must be an error and nothing else.
        const llama_token end_of_text = llama_vocab_eos(vocab);
        pp_session_clear_memory(s);
        must_evaluate(s, a);
        const int32_t count = pp_session_memory_token_count(s);
        const evaluation alone = evaluate(s, {end_of_text}, 1);
        const evaluation among = evaluate(s, {a[0], end_of_text, a[1]}, 8);
        r.check(alone.status == PP_STATUS_INVALID_ARGUMENT && among.status == PP_STATUS_INVALID_ARGUMENT && pp_session_memory_token_count(s) == count,
                "the end-of-text token %d as input: INVALID_ARGUMENT, memory unchanged, the process lives (\"%s\")", end_of_text, pp_session_last_error(s));
        r.check(evaluate(s, c, k_chunk).status == PP_STATUS_OK, "the session evaluates on afterwards");
    }

    // --- Sampling: only from a fresh prediction, only with a sampler ---

    {
        pp_session_clear_memory(s);
        must_evaluate(s, a);
        llama_token token = 0;
        const int32_t unconfigured = pp_session_sample_next(s, &token);
        must_configure(s, greedy_config(banned));
        const int32_t first = pp_session_sample_next(s, &token);
        llama_token again = 0;
        const int32_t second = pp_session_sample_next(s, &again);
        r.check(unconfigured == PP_STATUS_SAMPLING_FAILED && first == PP_STATUS_OK && token >= 0 && second == PP_STATUS_SAMPLING_FAILED,
                "sampling: SAMPLING_FAILED without a sampler, a token with one, SAMPLING_FAILED for a second token from the same prediction");

        pp_sampler_config bad = greedy_config(banned);
        bad.penalty_window = 0;
        const int32_t bad_window = pp_session_configure_sampler(s, &bad);
        bad = greedy_config(banned);
        bad.top_k = -1;
        const int32_t bad_top_k = pp_session_configure_sampler(s, &bad);
        const std::vector<llama_token> outside = {pp_session_vocab_size(s)};
        bad = greedy_config(outside);
        const int32_t bad_ban = pp_session_configure_sampler(s, &bad);
        r.check(bad_window == PP_STATUS_INVALID_ARGUMENT && bad_top_k == PP_STATUS_INVALID_ARGUMENT && bad_ban == PP_STATUS_INVALID_ARGUMENT,
                "a penalty window of 0, a negative top_k, a banned token outside the vocabulary: INVALID_ARGUMENT");

        // A penalty window that no machine can serve, which llama.cpp's own penalties would
        // allocate in full when the sampler is created. The session's penalties look at its
        // record of the memory and allocate nothing for a window, so it must cost next to
        // nothing; see address_space_limit for why this runs inside one.
        const int32_t n_vocab = pp_session_vocab_size(s);
        {
            const address_space_limit limit((uint64_t) 1 << 30);
            if (limit.in_force()) {
                pp_sampler_config wide = greedy_config(banned);
                wide.repetition_penalty = 1.1f;
                wide.penalty_window = std::numeric_limits<int32_t>::max();
                const resident before = resident_memory();
                const int32_t wide_status = pp_session_configure_sampler(s, &wide);
                const resident after = resident_memory();
                const std::vector<llama_token> too_many((size_t) n_vocab + 1, 0);
                const pp_sampler_config crowded = greedy_config(too_many);
                const int32_t crowded_status = pp_session_configure_sampler(s, &crowded);
                r.check(wide_status == PP_STATUS_OK && after.total < before.total + 16.0 && crowded_status == PP_STATUS_INVALID_ARGUMENT,
                        "a penalty window of %d tokens is no window and costs nothing: OK (%d), resident memory %.0f MiB before and %.0f MiB after; "
                        "more banned tokens than the vocabulary has: INVALID_ARGUMENT (%d)",
                        wide.penalty_window, wide_status, before.total, after.total, crowded_status);
            } else {
                r.note("no address space limit can be set here: the check with a penalty window beyond any machine is NOT run");
            }
        }

        // The model's own first choice after the prompt, to compare with below.
        const auto first_choice = [&](const pp_sampler_config & config, llama_token & chosen) {
            pp_session_clear_memory(s);
            must_evaluate(s, a);
            must_configure(s, config);
            chosen = 12345;
            return pp_session_sample_next(s, &chosen);
        };
        llama_token favourite = LLAMA_TOKEN_NULL;
        if (first_choice(greedy_config(banned), favourite) != PP_STATUS_OK) {
            pp_test::die(std::string("pp_session_sample_next failed: ") + pp_session_last_error(s));
        }

        // A ban lowers a score to minus infinity, and a chain that has nothing else to choose
        // from still names a token. That token must not come back as a success.
        std::vector<llama_token> all((size_t) n_vocab);
        std::iota(all.begin(), all.end(), 0);
        std::vector<llama_token> all_but_one = all;
        all_but_one.erase(all_but_one.begin() + favourite);
        int n_refused = 0, n_only_one = 0;
        for (const float temperature : {0.0f, 0.8f}) {
            pp_sampler_config nothing = greedy_config(all);
            nothing.temperature = temperature;
            nothing.top_k = temperature > 0.0f ? 40 : 0;
            nothing.top_p = temperature > 0.0f ? 0.9f : 1.0f;
            nothing.seed = 7;
            llama_token token = LLAMA_TOKEN_NULL;
            n_refused += first_choice(nothing, token) == PP_STATUS_SAMPLING_FAILED && token == LLAMA_TOKEN_NULL ? 1 : 0;

            pp_sampler_config one = nothing;
            one.banned_tokens = all_but_one.data();
            one.n_banned_tokens = (int32_t) all_but_one.size();
            n_only_one += first_choice(one, token) == PP_STATUS_OK && token == favourite ? 1 : 0;
        }
        llama_token ignored = LLAMA_TOKEN_NULL;
        first_choice(greedy_config(all), ignored);
        r.check(n_refused == 2 && n_only_one == 2,
                "with all %d tokens banned, greedy and at temperature 0.8: SAMPLING_FAILED and no token, %d of 2 (\"%s\"); with all but one banned: that one, %d of 2",
                n_vocab, n_refused, pp_session_last_error(s), n_only_one);

        // A temperature too small to divide the scores by is greedy decoding, not arithmetic on
        // infinities (a denormal once gave the last token of the vocabulary every time).
        int n_greedy = 0;
        const float tiny[] = {std::numeric_limits<float>::denorm_min(), 1e-40f, 0.5f * PP_TEMPERATURE_MIN};
        for (const float temperature : tiny) {
            pp_sampler_config cold = greedy_config(banned);
            cold.temperature = temperature;
            cold.seed = 7;
            llama_token token = LLAMA_TOKEN_NULL;
            n_greedy += first_choice(cold, token) == PP_STATUS_OK && token == favourite ? 1 : 0;
        }
        r.check(n_greedy == 3, "a temperature below %g (a denormal, 1e-40, half the threshold) gives the greedy token %d: %d of 3", (double) PP_TEMPERATURE_MIN,
                favourite, n_greedy);
    }

    // --- Greedy replies ---

    {
        double seconds_prompt = 0.0, seconds_reply = 0.0;
        size_t n_prompt = 0, n_reply = 0;
        if (!ref.seqs.empty()) {
            size_t n_same = 0;
            std::string first_divergence = "none";
            for (size_t k = 0; k < ref.seqs.size(); k++) {
                pp_session_clear_memory(s);
                seconds_prompt += evaluate(s, prompts[k], k_chunk).seconds;
                must_configure(s, greedy_config(banned));
                // With a stop token: until it comes, within the cap the reference used. Without:
                // exactly as many tokens as the reference generated.
                const int n_max = stop != LLAMA_TOKEN_NULL ? k_max_new_tokens : (int) ref.seqs[k].greedy.size();
                const std::vector<llama_token> reply = continue_reply(s, n_max, stop, &seconds_reply);
                n_prompt += prompts[k].size();
                n_reply += reply.size();
                if (reply == ref.seqs[k].greedy) {
                    n_same++;
                } else if (first_divergence == "none") {
                    first_divergence = "conversation " + std::to_string(k) + ", reply token " + std::to_string(pp_test::common_prefix(reply, ref.seqs[k].greedy));
                }
            }
            r.check(n_same == ref.seqs.size(), "greedy replies through the session equal the reference's: %zu of %zu, first divergence: %s",
                    n_same, ref.seqs.size(), first_divergence.c_str());
        } else {
            pp_session_clear_memory(s);
            seconds_prompt += evaluate(s, a, k_chunk).seconds;
            must_configure(s, greedy_config(banned));
            const std::vector<llama_token> first = continue_reply(s, 24, stop, &seconds_reply);
            const std::vector<llama_token> second = fresh_reply(s, a, 24, stop, banned);
            n_prompt += a.size();
            n_reply += first.size();
            std::string text = detokenize(s, first);
            std::replace(text.begin(), text.end(), '\n', ' ');
            r.check(!first.empty() && first == second, "a greedy reply of %zu tokens, identical in two runs: \"%s\"", first.size(), text.c_str());
            if (!expected_word.empty()) {
                r.check(text.find(expected_word) != std::string::npos, "the reply contains \"%s\"", expected_word.c_str());
            }
        }
        int32_t n_threads = 0;
        bool pinned = false;
        pp_session_thread_setup(s, &n_threads, &pinned);
        r.note("speed: prompt %.0f tokens/s (%zu tokens in chunks of %d), generation %.1f tokens/s (%zu tokens), %d thread(s)%s",
               (double) n_prompt / seconds_prompt, n_prompt, k_chunk, (double) n_reply / seconds_reply, n_reply, n_threads, pinned ? ", pinned" : "");

        const std::vector<llama_token> by_32 = fresh_reply(s, a, k_tail, stop, banned, k_chunk);
        const std::vector<llama_token> by_7 = fresh_reply(s, a, k_tail, stop, banned, 7);
        const std::vector<llama_token> by_1 = fresh_reply(s, a, k_tail, stop, banned, 1);
        r.check(by_32 == by_7 && by_32 == by_1, "the reply does not depend on the chunk size (32, 7 and 1 token per chunk)");
    }

    // --- Shortening the memory, and checkpoints ---

    {
        const std::vector<llama_token> ac = concat({&a, &c});
        const std::vector<llama_token> abc = concat({&a, &b, &c});
        const std::vector<llama_token> reply_ac = fresh_reply(s, ac, k_tail, stop, banned);
        const std::vector<llama_token> reply_abc = fresh_reply(s, abc, k_tail, stop, banned);
        const int32_t n_a = (int32_t) a.size(), n_ab = (int32_t) (a.size() + b.size());

        pp_session_clear_memory(s);
        must_evaluate(s, a);
        const int32_t checkpoint = pp_session_save_checkpoint(s);
        must_evaluate(s, b);
        r.check(checkpoint >= 0, "a checkpoint at %d tokens: handle %d, %" PRId64 " bytes of saved state", n_a, checkpoint, pp_session_checkpoint_size(s, checkpoint));

        bool removed = true;
        const int32_t below = pp_session_rewind_to(s, -1, &removed);
        const int32_t above = pp_session_rewind_to(s, n_ab + 1, &removed);
        r.check(below == PP_STATUS_INVALID_ARGUMENT && above == PP_STATUS_INVALID_ARGUMENT && pp_session_memory_token_count(s) == n_ab,
                "shortening to -1 or beyond the end: INVALID_ARGUMENT, memory unchanged");

        // To the present length: nothing is removed and the prediction survives.
        removed = false;
        must_configure(s, greedy_config(banned));
        llama_token kept = LLAMA_TOKEN_NULL;
        const int32_t same_length = pp_session_rewind_to(s, n_ab, &removed);
        r.check(same_length == PP_STATUS_OK && removed && pp_session_sample_next(s, &kept) == PP_STATUS_OK,
                "shortening to the present length removes nothing, answers true and keeps the prediction");

        removed = !recurrent;
        const int32_t status = pp_session_rewind_to(s, n_a, &removed);
        if (recurrent) {
            r.check(status == PP_STATUS_OK && !removed && pp_session_memory_token_count(s) == n_ab,
                    "this model refuses to give up the end of its memory: false, and still %d tokens", pp_session_memory_token_count(s));
            // "Exactly as it was": what follows must be what follows an undisturbed memory.
            must_evaluate(s, c);
            r.check(continue_reply(s, k_tail, stop) == reply_abc, "after the refusal the memory continues exactly as an undisturbed one does");

            const bool restored = pp_session_restore_checkpoint(s, checkpoint);
            r.check(restored && pp_session_memory_token_count(s) == n_a, "the checkpoint brings the memory back to %d tokens", pp_session_memory_token_count(s));
            llama_token none = LLAMA_TOKEN_NULL;
            r.check(pp_session_sample_next(s, &none) == PP_STATUS_SAMPLING_FAILED, "the prediction is gone after a restore: SAMPLING_FAILED");
            must_evaluate(s, c);
            r.check(continue_reply(s, k_tail, stop) == reply_ac, "the continuation after the restore equals a fresh evaluation, token for token");

            // The checkpoint outlives its use.
            const bool again = pp_session_restore_checkpoint(s, checkpoint);
            must_evaluate(s, c);
            r.check(again && continue_reply(s, k_tail, stop) == reply_ac, "the same checkpoint restores a second time with the same continuation");
        } else {
            r.check(status == PP_STATUS_OK && removed && pp_session_memory_token_count(s) == n_a,
                    "the end of the memory is removed: true, %d tokens left", pp_session_memory_token_count(s));
            llama_token none = LLAMA_TOKEN_NULL;
            r.check(pp_session_sample_next(s, &none) == PP_STATUS_SAMPLING_FAILED, "the prediction is gone after a removal: SAMPLING_FAILED");
            must_evaluate(s, c);
            r.check(continue_reply(s, k_tail, stop) == reply_ac, "the continuation after the removal equals a fresh evaluation, token for token");

            // A checkpoint costs such a model next to nothing and works all the same.
            const bool restored = pp_session_restore_checkpoint(s, checkpoint);
            must_evaluate(s, c);
            r.check(restored && continue_reply(s, k_tail, stop) == reply_ac, "the checkpoint restores the same length with the same continuation");
        }

        // A checkpoint taken further on than the memory now reaches cannot be restored.
        pp_session_clear_memory(s);
        must_evaluate(s, abc);
        const int32_t late = pp_session_save_checkpoint(s);
        bool whole = false;
        const int32_t to_zero = pp_session_rewind_to(s, 0, &whole);
        r.check(to_zero == PP_STATUS_OK && whole && pp_session_memory_token_count(s) == 0, "the whole memory can always be removed: true, 0 tokens");
        must_evaluate(s, a);
        const bool too_short = pp_session_restore_checkpoint(s, late);
        r.check(!too_short && pp_session_memory_token_count(s) == n_a, "a checkpoint beyond the end of the memory is refused and changes nothing");

        pp_session_drop_checkpoint(s, late);
        pp_session_drop_checkpoint(s, late);
        pp_session_drop_checkpoint(s, 123456);
        r.check(!pp_session_restore_checkpoint(s, late) && pp_session_checkpoint_size(s, late) == -1 && late > checkpoint,
                "a dropped checkpoint is unknown, dropping it again or dropping an unknown one is harmless; handles rise (%d, %d)", checkpoint, late);

        // A session holds no more than PP_CHECKPOINTS_MAX at a time: one is megabytes for a
        // model with state, and nothing else would stop a caller that forgot to drop them.
        // (None is held at this point: the clear above freed the first, and the late one was dropped.)
        std::vector<int32_t> held;
        int64_t bytes = 0;
        while ((int) held.size() < PP_CHECKPOINTS_MAX) {
            const int32_t handle = pp_session_save_checkpoint(s);
            if (handle < 0) {
                break;
            }
            held.push_back(handle);
            bytes += pp_session_checkpoint_size(s, handle);
        }
        const int32_t one_too_many = pp_session_save_checkpoint(s);
        const std::string why = pp_session_last_error(s);
        pp_session_drop_checkpoint(s, held.front());
        const int32_t after_drop = pp_session_save_checkpoint(s);
        r.check((int) held.size() == PP_CHECKPOINTS_MAX && one_too_many == -1 && after_drop > held.back() && pp_session_memory_token_count(s) == n_a,
                "%zu checkpoints are held at once (%" PRId64 " bytes in all); one more is refused: %d (\"%s\"); after one is dropped the next is saved (handle %d)",
                held.size(), bytes, one_too_many, why.c_str(), after_drop);
    }

    // --- A model with windowed layers: far cuts, checkpoints, and the cuts a restored window cannot serve ---

    if (n_window > 0) {
        if (long_ref_path.empty()) {
            pp_test::die("a model with windowed layers needs --long-ref: sequences longer than its window");
        }
        const pp_test::reference long_ref = pp_test::load_reference(long_ref_path);
        // The longest sequence is the text in memory; a stretch of the second longest is what follows a cut.
        const std::vector<llama_token> * text = nullptr;
        const std::vector<llama_token> * second = nullptr;
        for (const pp_test::sequence & q : long_ref.seqs) {
            if (text == nullptr || q.ids.size() > text->size()) {
                second = text;
                text = &q.ids;
            } else if (second == nullptr || q.ids.size() > second->size()) {
                second = &q.ids;
            }
        }
        const size_t window = (size_t) n_window;
        if (text == nullptr || second == nullptr || text->size() < 5 * window || second->size() < 2 * window) {
            pp_test::die("the sequences of --long-ref are too short for a window of " + std::to_string(n_window) + " tokens");
        }
        const std::vector<llama_token> next(second->begin() + n_window, second->begin() + n_window + 48);
        constexpr int k_reply = 24;
        // Lengths, in windows: a checkpoint where the window is long full, a cut far below it,
        // and a cut below the checkpoint by most of a window.
        const int32_t n_all = (int32_t) text->size(), n_saved = (int32_t) (3 * window + window / 2), n_far = (int32_t) (window + window / 6);
        const int32_t n_lost = n_saved - (int32_t) (window - window / 16), n_near = n_saved - 1;
        const auto first = [&](int32_t n) { return std::vector<llama_token>(text->begin(), text->begin() + n); };
        const auto then_next = [&](int32_t n) {
            std::vector<llama_token> out = first(n);
            out.insert(out.end(), next.begin(), next.end());
            return out;
        };

        // A session of its own: the text is longer than the context of the session above.
        char error[256];
        pp_session * opened = nullptr;
        pp_session_params params = default_params();
        params.n_ctx = (int32_t) (text->size() + 2 * window);
        if (pp_session_open_path(gguf.c_str(), &params, &opened, error, sizeof(error)) != PP_STATUS_OK) {
            pp_test::die(std::string("cannot open the model with a longer context: ") + error);
        }
        session_ptr owner(opened);
        pp_session * w = opened;

        // What must come after each state of the memory, from fresh evaluations.
        const std::vector<llama_token> after_all = fresh_reply(w, then_next(n_all), k_reply, stop, banned);
        const std::vector<llama_token> after_far = fresh_reply(w, then_next(n_far), k_reply, stop, banned);
        const std::vector<llama_token> after_saved = fresh_reply(w, then_next(n_saved), k_reply, stop, banned);
        const std::vector<llama_token> after_near = fresh_reply(w, then_next(n_near), k_reply, stop, banned);
        const auto reply_after_next = [&]() {
            must_evaluate(w, next);
            must_configure(w, greedy_config(banned));
            return continue_reply(w, k_reply, stop);
        };
        // The memory holds the whole text, with a checkpoint taken on the way.
        const auto fill = [&]() {
            pp_session_clear_memory(w);
            must_evaluate(w, first(n_saved));
            const int32_t handle = pp_session_save_checkpoint(w);
            must_evaluate(w, std::vector<llama_token>(text->begin() + n_saved, text->end()));
            return handle;
        };

        // A cut far back, many windows from the end. Whatever the session answers must be true:
        // "removed" and the continuation of a fresh evaluation, or "not removed" and a memory
        // that goes on as if nothing had been asked.
        int32_t checkpoint = fill();
        r.check(checkpoint >= 0 && pp_session_checkpoint_size(w, checkpoint) > 0, "windowed layers (window %d): a checkpoint at %d of %d tokens is %" PRId64 " bytes",
                n_window, n_saved, n_all, pp_session_checkpoint_size(w, checkpoint));
        bool removed = false;
        int32_t status = pp_session_rewind_to(w, n_far, &removed);
        const int32_t count_far = pp_session_memory_token_count(w);
        const std::vector<llama_token> reply_far = reply_after_next();
        r.check(status == PP_STATUS_OK && (removed ? count_far == n_far && reply_far == after_far : count_far == n_all && reply_far == after_all),
                "a cut from %d to %d tokens: %s", n_all, n_far,
                removed ? "removed, and the continuation equals a fresh evaluation" : "refused, and the memory continues as an undisturbed one does");

        // Through the checkpoint: always possible, always exact.
        checkpoint = fill();
        const bool restored = pp_session_restore_checkpoint(w, checkpoint);
        const int32_t count_restored = pp_session_memory_token_count(w);
        r.check(restored && count_restored == n_saved && reply_after_next() == after_saved,
                "the checkpoint brings the memory back from %d to %d tokens, and the continuation equals a fresh evaluation", n_all, count_restored);

        // The restored state is the window of the local layers at the checkpoint and nothing
        // older. A cut that needs keys from before that window must be refused, not served
        // wrongly: llama.cpp itself would make it and answer "done".
        const bool again = pp_session_restore_checkpoint(w, checkpoint);
        removed = true;
        status = pp_session_rewind_to(w, n_lost, &removed);
        const int32_t count_lost = pp_session_memory_token_count(w);
        const std::vector<llama_token> reply_lost = reply_after_next();
        r.check(again && status == PP_STATUS_OK && !removed && count_lost == n_saved && reply_lost == after_saved,
                "after the restore a cut to %d tokens, below what the restored window reaches: %s, %d tokens in memory, %s", n_lost,
                removed ? "REMOVED" : "refused", count_lost,
                reply_lost == after_saved ? "and the memory continues as an undisturbed one does"
                                          : removed ? "and the continuation is NOT that of a fresh evaluation" : "but the memory does NOT continue as before");

        // A cut that the restored window does cover is made, and is exact.
        const bool once_more = pp_session_restore_checkpoint(w, checkpoint);
        removed = false;
        status = pp_session_rewind_to(w, n_near, &removed);
        r.check(once_more && status == PP_STATUS_OK && removed && pp_session_memory_token_count(w) == n_near && reply_after_next() == after_near,
                "after the restore a cut to %d tokens, within the restored window: removed, and the continuation equals a fresh evaluation", n_near);
    }

    // --- Forgetting ---

    {
        pp_session_clear_memory(s);
        must_evaluate(s, a);
        const int32_t old_handle = pp_session_save_checkpoint(s);

        pp_sampler_config sampled = greedy_config(banned);
        sampled.temperature        = 0.9f;
        sampled.top_k              = 40;
        sampled.top_p              = 0.95f;
        sampled.repetition_penalty = 1.1f;
        sampled.presence_penalty   = 1.5f;
        sampled.seed               = 1234;
        must_configure(s, sampled);
        const std::vector<llama_token> first = continue_reply(s, k_tail, stop);

        const int64_t t0 = llama_time_us();
        const int32_t cleared = pp_session_clear_memory(s);
        const double clear_ms = (double) (llama_time_us() - t0) / 1e3;
        const int32_t new_handle = pp_session_save_checkpoint(s);
        r.check(cleared == PP_STATUS_OK && pp_session_context_size(s) == k_n_ctx && pp_session_memory_token_count(s) == 0 &&
                !pp_session_restore_checkpoint(s, old_handle) && new_handle > old_handle,
                "clearing answers OK in %.1f ms, empties the memory, leaves a context of the same %d tokens and frees every checkpoint; "
                "a new handle (%d) does not reuse an old one (%d)", clear_ms, pp_session_context_size(s), new_handle, old_handle);
        llama_token none = LLAMA_TOKEN_NULL;
        r.check(pp_session_sample_next(s, &none) == PP_STATUS_SAMPLING_FAILED, "no prediction survives a clear: SAMPLING_FAILED");

        // Not reconfigured: the sampler must still be there, and the first run must be forgotten
        // (the tokens its penalties were computed from, and its random state), or the second run
        // would differ.
        must_evaluate(s, a);
        const std::vector<llama_token> second = continue_reply(s, k_tail, stop);
        r.check(!first.empty() && first == second, "the sampler stays configured across a clear and starts afresh: the same %zu sampled tokens again", first.size());

        sampled.seed = 99;
        must_configure(s, sampled);
        pp_session_clear_memory(s);
        must_evaluate(s, a);
        r.note("another seed gives %s tokens", continue_reply(s, k_tail, stop) == first ? "the same" : "other");

        // Forgetting in the middle of a prompt, which is what a lock during a long evaluation
        // is: a complete evaluation (so a prediction exists), then one that is stopped after a
        // chunk that asked for no output. llama.cpp has no prediction to hand out at that
        // point, and a build of it with assertions ends the process when asked for one, so the
        // session must not ask: here, or at the next evaluation.
        pp_session_clear_memory(s);
        must_evaluate(s, a);
        const std::vector<llama_token> bc = concat({&b, &c});
        watcher stopped;
        stopped.cancel_at_progress = 8;
        const evaluation partial = evaluate(s, bc, 8, &stopped);
        const int32_t cleared_midway = pp_session_clear_memory(s);
        const evaluation afterwards = evaluate(s, a, k_chunk);
        r.check(partial.status == PP_STATUS_OK && partial.n_in_memory == 8 && cleared_midway == PP_STATUS_OK && afterwards.status == PP_STATUS_OK &&
                pp_session_memory_token_count(s) == (int32_t) a.size(),
                "forgetting after an evaluation that was stopped %d tokens into %zu: OK (%d), and the session evaluates again (%d tokens in memory)",
                partial.n_in_memory, bc.size(), cleared_midway, pp_session_memory_token_count(s));
    }

    // --- Banned tokens ---

    {
        std::vector<llama_token> ban = banned;
        std::set<llama_token> seen;
        bool leaked = false;
        for (int round = 0; round < 5; round++) {
            pp_session_clear_memory(s);
            must_evaluate(s, a);
            must_configure(s, greedy_config(ban));
            llama_token token = LLAMA_TOKEN_NULL;
            if (pp_session_sample_next(s, &token) != PP_STATUS_OK) {
                pp_test::die("sampling failed");
            }
            leaked = leaked || std::find(ban.begin(), ban.end(), token) != ban.end();
            seen.insert(token);
            ban.push_back(token); // the model's first choice is banned next time round
        }
        r.check(!leaked && seen.size() == 5, "banning the model's first choice five times over gives five different tokens, never a banned one");

        pp_sampler_config hot = greedy_config(ban);
        hot.temperature = 2.0f;
        hot.seed = 7;
        pp_session_clear_memory(s);
        must_evaluate(s, a);
        must_configure(s, hot);
        const std::vector<llama_token> sampled = continue_reply(s, 48, stop);
        const bool any = std::any_of(sampled.begin(), sampled.end(), [&](llama_token t) { return std::find(ban.begin(), ban.end(), t) != ban.end(); });
        r.check(!any && !sampled.empty(), "%zu tokens sampled at temperature 2 with %zu tokens banned: none of them banned", sampled.size(), ban.size());
    }

    // --- Cancelling ---

    {
        const std::vector<llama_token> abc = concat({&a, &b, &c});
        const std::vector<llama_token> reply_abc = fresh_reply(s, abc, k_tail, stop, banned);

        // Between chunks: stop once two chunks of 8 are done.
        pp_session_clear_memory(s);
        watcher between;
        between.cancel_at_progress = 16;
        evaluation e = evaluate(s, abc, 8, &between);
        llama_token none = LLAMA_TOKEN_NULL;
        r.check(e.status == PP_STATUS_OK && e.n_in_memory == 16 && pp_session_memory_token_count(s) == 16 && pp_session_sample_next(s, &none) == PP_STATUS_SAMPLING_FAILED,
                "cancelled after two chunks of 8: not a failure, %d of %zu tokens in memory, no prediction", e.n_in_memory, abc.size());
        must_evaluate(s, std::vector<llama_token>(abc.begin() + e.n_in_memory, abc.end()));
        r.check(continue_reply(s, k_tail, stop) == reply_abc, "evaluating the rest afterwards continues as an uninterrupted evaluation does");

        // In the middle of a chunk: the answer changes to "stop" on the third poll, which for an
        // attention-only model is asked from inside llama.cpp while the first (and only) chunk
        // is being computed. A model with recurrent state is only asked between chunks.
        pp_session_clear_memory(s);
        watcher inside;
        inside.cancel_at_poll = 3;
        const int32_t chunk = recurrent ? 8 : k_n_batch;
        const std::vector<llama_token> first_chunk(abc.begin(), abc.begin() + std::min<size_t>(abc.size(), (size_t) k_n_batch));
        e = evaluate(s, first_chunk, chunk, &inside);
        const int32_t count = pp_session_memory_token_count(s);
        if (recurrent) {
            r.check(e.status == PP_STATUS_OK && e.n_in_memory == 16 && count == 16 && inside.polls == 3,
                    "cancelled on the third poll: this model is only asked between chunks, %d tokens in memory", count);
        } else {
            r.check(e.status == PP_STATUS_OK && e.n_in_memory == 0 && count == 0 && inside.polls == 3,
                    "cancelled on the third poll, inside a chunk of %zu tokens: the chunk is abandoned and taken out of the memory again (%d tokens in memory)",
                    first_chunk.size(), count);
        }
        r.note("the evaluation returned %.2f ms after its callback first answered \"stop\" (%.1f ms after it began)",
               (double) (e.finished_us - inside.cancelled_at_us) / 1e3, e.seconds * 1e3);
        must_evaluate(s, std::vector<llama_token>(abc.begin() + count, abc.end()));
        r.check(continue_reply(s, k_tail, stop) == reply_abc, "the memory is intact after the cancellation: the same continuation");
    }

    // --- Threads: pinned when the system allows it, the plain count when it does not ---

#if defined(__linux__)
    {
        const std::vector<llama_token> reply = fresh_reply(s, a, k_tail, stop, banned);
        session.reset();
        s = nullptr;

        cpu_set_t allowed;
        sched_getaffinity(0, sizeof(allowed), &allowed);
        std::vector<int32_t> cpus;
        for (int cpu = 0; cpu < CPU_SETSIZE && cpus.size() < 2; cpu++) {
            if (CPU_ISSET(cpu, &allowed)) {
                cpus.push_back(cpu);
            }
        }
        // Whether, at the end of the latest run, each listed CPU had exactly one thread on it.
        bool one_thread_per_cpu = false;
        // disturb: what Android does when it moves an app between sets of cores, in the middle
        // of a reply: the affinity of every thread is replaced behind the session's back.
        const auto run = [&](const std::vector<int32_t> & pinned_cpus, int32_t & n_threads, bool & pinned, bool disturb = false) {
            char error[256];
            pp_session * opened = nullptr;
            pp_session_params params = default_params();
            params.n_threads = 3;
            params.pinned_cpus = pinned_cpus.data();
            params.n_pinned_cpus = (int32_t) pinned_cpus.size();
            if (pp_session_open_path(gguf.c_str(), &params, &opened, error, sizeof(error)) != PP_STATUS_OK) {
                pp_test::die(std::string("cannot open the model again: ") + error);
            }
            session_ptr owner(opened);
            must_evaluate(opened, a);
            must_configure(opened, greedy_config(banned));
            std::vector<llama_token> out = continue_reply(opened, disturb ? k_tail / 2 : k_tail, stop);
            if (disturb && (int) out.size() == k_tail / 2) {
                sched_setaffinity(0, sizeof(allowed), &allowed);
                // The session must notice, build a new pool and go on; the old pool is still
                // known to the backend at this point and must survive until it has let go.
                must_evaluate(opened, {out.back()}, 1);
                const std::vector<llama_token> rest = continue_reply(opened, k_tail - k_tail / 2, stop);
                out.insert(out.end(), rest.begin(), rest.end());
            }
            pp_session_thread_setup(opened, &n_threads, &pinned);
            one_thread_per_cpu = true;
            for (const int32_t cpu : pinned_cpus) {
                one_thread_per_cpu = one_thread_per_cpu && threads_pinned_to(cpu) == 1;
            }
            return out;
        };

        int32_t n_threads = 0;
        bool pinned = false;
        const std::vector<llama_token> on_two = run(cpus, n_threads, pinned);
        r.check(on_two == reply && pinned && n_threads == (int32_t) cpus.size() && one_thread_per_cpu,
                "pinned to CPUs %d and %d: %d thread(s), %s, the same reply", cpus.front(), cpus.back(), n_threads,
                one_thread_per_cpu ? "exactly one on each" : "NOT one on each");
        cpu_set_t afterwards;
        sched_getaffinity(0, sizeof(afterwards), &afterwards);
        r.check(CPU_COUNT(&afterwards) >= CPU_COUNT(&allowed), "closing the session lifts the pin from the calling thread again (%d CPUs allowed)", CPU_COUNT(&afterwards));

        const std::vector<llama_token> disturbed = run(cpus, n_threads, pinned, true);
        r.check(disturbed == reply && pinned && one_thread_per_cpu,
                "the affinities replaced from outside in the middle of a reply: a new pinned pool, %s, the same reply",
                one_thread_per_cpu ? "one thread on each CPU again" : "NOT one thread on each CPU");

        // The same in the middle of one evaluation call, which is how a long prompt is evaluated
        // and where a session that only looked at the start of a call would compute the rest of
        // it with two threads on one core. The affinities are replaced after the call's first
        // chunk, from its progress callback; when the call returns, each listed CPU must have
        // its one thread again. Forgetting the conversation afterwards must keep that pool.
        {
            char error[256];
            pp_session * opened = nullptr;
            pp_session_params params = default_params();
            params.n_threads = 3;
            params.pinned_cpus = cpus.data();
            params.n_pinned_cpus = (int32_t) cpus.size();
            if (pp_session_open_path(gguf.c_str(), &params, &opened, error, sizeof(error)) != PP_STATUS_OK) {
                pp_test::die(std::string("cannot open the model again: ") + error);
            }
            session_ptr owner(opened);
            struct mover {
                const cpu_set_t * to;
                int chunks;
            };
            mover moved = {&allowed, 0};
            const pp_evaluation_observer observer = {
                [](void *) { return false; },
                [](int32_t, void * data) {
                    mover * m = (mover *) data;
                    if (++m->chunks == 1) {
                        sched_setaffinity(0, sizeof(cpu_set_t), m->to);
                    }
                },
                &moved};
            const auto one_thread_each = [&]() {
                bool one = true;
                for (const int32_t cpu : cpus) {
                    one = one && threads_pinned_to(cpu) == 1;
                }
                return one;
            };
            int32_t in_memory = 0;
            const int32_t status = pp_session_evaluate(opened, a.data(), (int32_t) a.size(), 4, &observer, &in_memory);
            const bool pinned_after_move = one_thread_each();
            pp_session_thread_setup(opened, &n_threads, &pinned);
            must_configure(opened, greedy_config(banned));
            const std::vector<llama_token> moved_reply = continue_reply(opened, k_tail, stop);
            r.check(status == PP_STATUS_OK && in_memory == (int32_t) a.size() && moved.chunks >= 3 && pinned && pinned_after_move && moved_reply == reply,
                    "the affinities replaced after the first of %d chunks of one evaluation: %s when the call returns, the same reply", moved.chunks,
                    pinned_after_move ? "one thread on each CPU again" : "NOT one thread on each CPU");

            const int32_t cleared = pp_session_clear_memory(opened);
            must_evaluate(opened, a);
            const std::vector<llama_token> after_clear = continue_reply(opened, k_tail, stop);
            const bool pinned_after_clear = one_thread_each();
            pp_session_thread_setup(opened, &n_threads, &pinned);
            r.check(cleared == PP_STATUS_OK && pinned && pinned_after_clear && n_threads == (int32_t) cpus.size() && after_clear == reply,
                    "forgetting the conversation keeps the pinned threads for the new context: %d thread(s), %s, the same reply", n_threads,
                    pinned_after_clear ? "one on each CPU" : "NOT one on each CPU");
        }

        // A CPU this process may not use (it does not exist): pinning is not possible, so the
        // session must run its unpinned thread count instead of a pool that only looks pinned.
        std::vector<int32_t> impossible = cpus;
        impossible.push_back(GGML_MAX_N_THREADS - 1);
        const std::vector<llama_token> fallback = run(impossible, n_threads, pinned);
        r.check(fallback == reply && !pinned && n_threads == 3, "one listed CPU not available: %d unpinned thread(s) instead, the same reply", n_threads);
    }
#else
    session.reset();
    s = nullptr;
#endif

    // --- Closing ---

    {
        const resident after_close = resident_memory();
        const double grown = after_open.total - before_open.total;
        const double left = after_close.total - before_open.total;
        r.check(grown > 0.0 && left < 0.1 * grown + 16.0, "resident memory: %.0f MiB before opening, %.0f MiB with the model, %.0f MiB after closing",
                before_open.total, after_open.total, after_close.total);
        pp_session_close(nullptr);
    }

    // --- A model embedded in a larger file, by descriptor, offset and length ---

    if (aligned.given()) {
        const auto open_embedded = [&](const embedded & where, pp_load_mode mode, int32_t n_ctx, int64_t length, char * error, size_t error_size, int32_t & status) {
            pp_session * opened = nullptr;
            pp_session_params params = default_params();
            params.n_ctx = n_ctx;
            params.load_mode = mode;
            const int fd = open(where.path.c_str(), O_RDONLY);
            if (fd < 0) {
                pp_test::die("cannot open " + where.path);
            }
            status = pp_session_open_fd(fd, where.offset, length, &params, &opened, error, error_size);
            // The caller's descriptor is the caller's: the session must not need it any more.
            close(fd);
            return session_ptr(opened);
        };
        // The reply of the session loaded by path, to compare with.
        std::vector<llama_token> expected;
        {
            char error[256];
            pp_session * opened = nullptr;
            pp_session_params params = default_params();
            if (pp_session_open_path(gguf.c_str(), &params, &opened, error, sizeof(error)) != PP_STATUS_OK) {
                pp_test::die(std::string("cannot open the model again: ") + error);
            }
            session_ptr owner(opened);
            expected = fresh_reply(opened, a, k_tail, stop, banned);
        }

        char error[256];
        int32_t status = 0;
        for (const pp_load_mode mode : {PP_LOAD_MODE_READ, PP_LOAD_MODE_MMAP}) {
            const char * name = mode == PP_LOAD_MODE_READ ? "read" : "mapped";
            const resident before = resident_memory();
            const int64_t t0 = llama_time_us();
            session_ptr embedded_session = open_embedded(aligned, mode, k_n_ctx, aligned.length, error, sizeof(error), status);
            const double seconds = (double) (llama_time_us() - t0) / 1e6;
            r.check(status == PP_STATUS_OK, "embedded at offset %" PRId64 " of a larger file, %s: opens in %.2f s%s%s", aligned.offset, name, seconds,
                    status == PP_STATUS_OK ? "" : ": ", error);
            if (status != PP_STATUS_OK) {
                continue;
            }
            const resident loaded = resident_memory();
            const int64_t mapped = mapped_bytes_of(aligned.path);
            r.check(fresh_reply(embedded_session.get(), a, k_tail, stop, banned) == expected, "  the embedded model, %s, gives the reply of the model loaded by path", name);
            if (mode == PP_LOAD_MODE_MMAP) {
                // The point of the window: the file holds other data before and after the model,
                // and none of it may be mapped (it would be read ahead with the model).
                r.check(mapped > 0 && mapped <= aligned.length + 65536, "  the mapping covers %" PRId64 " bytes of the file for a model of %" PRId64 " bytes, not the whole file",
                        mapped, aligned.length);
                r.note("  resident memory grew by %.0f MiB, %.0f MiB of it file-backed", loaded.total - before.total, loaded.file - before.file);
            } else {
                r.check(mapped == 0, "  nothing of the file is mapped when the model is read");
                r.note("  resident memory grew by %.0f MiB, %.0f MiB of it file-backed", loaded.total - before.total, loaded.file - before.file);
            }
        }

        open_embedded(aligned, PP_LOAD_MODE_READ, k_n_ctx, aligned.length / 2, error, sizeof(error), status);
        r.check(status == PP_STATUS_LOAD_FAILED, "a length that cuts the model short: LOAD_FAILED (\"%s\")", error);

        // A length that cuts the model short by less than its header. Other data follows the
        // model in this file, so every tensor can still be read from where the header says it
        // is: the cut is only found by holding the header's account of where the tensors end
        // against the length. A session that does not would load the model and answer nonsense.
        {
            struct gguf_init_params header_only = {/* no_alloc */ true, /* ctx */ nullptr};
            struct gguf_context * header = gguf_init_from_file(gguf.c_str(), header_only);
            const int64_t header_bytes = header != nullptr ? (int64_t) gguf_get_data_offset(header) : 0;
            gguf_free(header);
            int n_refused = 0, n_cases = 0;
            for (const int64_t cut : {(int64_t) 4096, header_bytes / 2, header_bytes - 64}) {
                for (const pp_load_mode mode : {PP_LOAD_MODE_READ, PP_LOAD_MODE_MMAP}) {
                    const session_ptr cut_short = open_embedded(aligned, mode, k_n_ctx, aligned.length - cut, error, sizeof(error), status);
                    n_cases++;
                    n_refused += status == PP_STATUS_LOAD_FAILED && cut_short == nullptr ? 1 : 0;
                }
            }
            r.check(header_bytes > 4096 && n_refused == n_cases,
                    "a length that cuts the model short by 4096 bytes, by half its header and by all but 64 bytes of it (the header is %" PRId64
                    " bytes), read and mapped: LOAD_FAILED, %d of %d (\"%s\")", header_bytes, n_refused, n_cases, error);
        }

        // A context too small for what is evaluated.
        {
            session_ptr small = open_embedded(aligned, PP_LOAD_MODE_MMAP, 64, aligned.length, error, sizeof(error), status);
            if (status != PP_STATUS_OK) {
                pp_test::die(std::string("cannot open the embedded model with a small context: ") + error);
            }
            // llama.cpp rounds a context up to its own granularity, so the size is read back.
            const int32_t n_ctx = pp_session_context_size(small.get());
            std::vector<llama_token> long_prompt;
            while ((int32_t) long_prompt.size() < n_ctx + 40) {
                long_prompt.insert(long_prompt.end(), a.begin(), a.end());
            }
            long_prompt.resize((size_t) n_ctx + 40);
            const int32_t fitting = n_ctx / 32 * 32; // whole chunks only: the chunk that does not fit is not begun
            const evaluation e = evaluate(small.get(), long_prompt, 32);
            r.check(n_ctx >= 64 && e.status == PP_STATUS_CONTEXT_FULL && e.n_in_memory == fitting && pp_session_memory_token_count(small.get()) == fitting,
                    "%zu tokens into a context of %d (64 requested): CONTEXT_FULL with %d in memory (\"%s\")", long_prompt.size(), n_ctx, e.n_in_memory,
                    pp_session_last_error(small.get()));
            pp_session_clear_memory(small.get());
            const evaluation after = evaluate(small.get(), std::vector<llama_token>(a.begin(), a.begin() + 10), 32);
            r.check(after.status == PP_STATUS_OK && after.n_in_memory == 10, "the session is usable again after a clear");
        }
    }

    if (unaligned.given()) {
        char error[256];
        pp_session_params params = default_params();
        const int fd = open(unaligned.path.c_str(), O_RDONLY);
        if (fd < 0) {
            pp_test::die("cannot open " + unaligned.path);
        }
        pp_session * opened = nullptr;
        int32_t status = pp_session_open_fd(fd, unaligned.offset, unaligned.length, &params, &opened, error, sizeof(error));
        session_ptr owner(opened);
        r.check(status == PP_STATUS_OK, "embedded at the unaligned offset %" PRId64 ", read: opens%s%s", unaligned.offset, status == PP_STATUS_OK ? "" : ": ", error);
        if (status == PP_STATUS_OK) {
            const std::vector<llama_token> tokens = tokenize(opened, "The war ended in 1945.", false);
            r.check(!tokens.empty() && evaluate(opened, a, k_chunk).status == PP_STATUS_OK, "  and tokenises and evaluates");
        }
        owner.reset();
        params.load_mode = PP_LOAD_MODE_MMAP;
        opened = nullptr;
        status = pp_session_open_fd(fd, unaligned.offset, unaligned.length, &params, &opened, error, sizeof(error));
        r.check(status == PP_STATUS_LOAD_FAILED && opened == nullptr, "embedded at the unaligned offset, mapped: LOAD_FAILED (\"%s\")", error);
        pp_session_close(opened);
        close(fd);
    }

    return r.finish();
}
