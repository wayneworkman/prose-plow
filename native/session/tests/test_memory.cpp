// The memory of a session written out as an image and read back: pp_session_export_memory,
// pp_session_import_memory, pp_session_memory_state_bytes and pp_session_memory_tokens. Against
// a real model file.
//
//   test_memory --gguf FILE [--bytes-per-token B]
//
// B is the model's context_bytes_per_token of models/manifest.json, the key-value bytes a
// token takes, which the app shows as what a conversation costs: what an image grows by with
// a token is held to it.
//
// Two sessions on the same file do the same things, call for call. One of them, the subject,
// has its memory written out, forgotten and read back in between; the other, the control, is
// never interrupted. Everything the subject does afterwards is held to the control:
// - the state of the sequence as llama.cpp writes it in one block, byte for byte;
// - the prediction after the next token, bit for bit over the whole vocabulary, and again at
//   every step of a greedy reply;
// - replies chosen with the penalties, which are computed from the record of the tokens;
// - the predictions after going back through a checkpoint that came out of the image.
// Then what must not happen: an import that ends at any piece leaves an empty memory, no
// checkpoint, no record, zeros in the caller's buffer and a session that predicts as a new one
// does; an export that the sink stops at any piece leaves the memory and the prediction that
// was ready as they were; a changed image is refused wherever the session can tell.
//
// "Bit for bit" needs the scores themselves, and a session shows only the token it chose. The
// test therefore learns which llama_context a session works with: the program is linked with
// --wrap=llama_init_from_model, so every context the session library creates passes through
// the function below, and the test reads the scores and the state of that context with
// llama.cpp's public calls. The session's code is the code that ships; nothing in it knows.
//
// Nothing here allocates more than the image of a few hundred tokens (tens of MiB): every
// store is sized from pp_session_memory_state_bytes and refused above k_store_limit, and the
// checks that say "no block of the state's size" run inside a limit on the address space of
// the process (address_space), never by using memory up.
//
// Every check prints one line with what was measured. Exit codes as in test_support.h.

#include "proseplow_session.h"
#include "support/test_support.h"

#include <algorithm>
#include <cinttypes>
#include <cmath>
#include <cstdlib>
#include <cstring>
#include <limits>

#include <malloc.h>
#include <sys/resource.h>

extern "C" {
llama_context * __real_llama_init_from_model(llama_model * model, llama_context_params params);
llama_context * __wrap_llama_init_from_model(llama_model * model, llama_context_params params);
}

namespace {
// The context created last, by whoever created it: a session's own, straight after a call
// that gives the session a context (opening, forgetting, a change of size).
llama_context * g_latest_context = nullptr;
} // namespace

llama_context * __wrap_llama_init_from_model(llama_model * model, llama_context_params params) {
    llama_context * ctx = __real_llama_init_from_model(model, params);
    if (ctx != nullptr) {
        g_latest_context = ctx;
    }
    return ctx;
}

namespace {

using pp_test::report;

constexpr int32_t k_batch   = 32;   // small: the compute reservation of a context is the scores of one batch
constexpr int32_t k_open    = 512;  // the size the sessions are opened with
constexpr int32_t k_first   = 120;  // the memory's length at the first checkpoint
constexpr int32_t k_prompt  = 200;  // and at the second
constexpr int     k_said    = 8;    // tokens of a reply that are in memory when it is written out
constexpr int64_t k_budget  = (int64_t) 8 << 30; // a limit that is stated, not memory that is taken
constexpr size_t  k_store_limit = (size_t) 1 << 30; // no image of this test comes near it
constexpr double  k_mib = 1048576.0;

// The checks that say "no block of the state's size" need an allocator that answers a request
// it cannot map with a null pointer, and that gives every large block a mapping of its own.
// glibc's does both. A sanitizer's allocator and Android's end the process over a mapping
// that fails, so there the checks are not run; everything else in this file is the same on a
// phone.
#if !defined(__GLIBC__) || defined(__SANITIZE_ADDRESS__)
constexpr bool k_limits_address_space = false;
#elif defined(__has_feature)
#if __has_feature(address_sanitizer)
constexpr bool k_limits_address_space = false;
#else
constexpr bool k_limits_address_space = true;
#endif
#else
constexpr bool k_limits_address_space = true;
#endif

struct session_closer {
    void operator()(pp_session * s) const { pp_session_close(s); }
};
using session_ptr = std::unique_ptr<pp_session, session_closer>;

// While one of these exists the process can map `room` bytes more than it has mapped now.
// Nothing is allocated to bring this about. As in test_context.cpp.
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

double seconds() {
    return (double) llama_time_us() / 1e6;
}

uint64_t hash_of(const void * data, size_t size) {
    uint64_t hash = 0xcbf29ce484222325ull;
    for (size_t i = 0; i < size; i++) {
        hash = (hash ^ ((const uint8_t *) data)[i]) * 0x100000001b3ull;
    }
    return hash;
}

bool all_zero(const std::vector<uint8_t> & bytes) {
    return std::all_of(bytes.begin(), bytes.end(), [](uint8_t b) { return b == 0; });
}

// The largest difference between two predictions, for the line of a check that failed.
double apart(const std::vector<float> & a, const std::vector<float> & b) {
    double worst = a.size() == b.size() ? 0.0 : std::numeric_limits<double>::infinity();
    for (size_t i = 0; i < a.size() && i < b.size(); i++) {
        worst = std::max(worst, std::fabs((double) a[i] - (double) b[i]));
    }
    return worst;
}

bool same_bits(const std::vector<float> & a, const std::vector<float> & b) {
    return a.size() == b.size() && !a.empty() && std::memcmp(a.data(), b.data(), a.size() * sizeof(float)) == 0;
}

// A session, the context it works with, and what the test knows of its memory.
struct talk {
    session_ptr owner;
    pp_session * s = nullptr;
    llama_context * ctx = nullptr;
    std::vector<llama_token> banned; // every token that ends a reply: a sampled token can then always be evaluated
    std::vector<llama_token> memory;

    // After a call that gave the session a context: that context is the one created last.
    void attach() {
        ctx = g_latest_context;
        if (ctx == nullptr || (int32_t) llama_n_ctx(ctx) != pp_session_context_size(s)) {
            pp_test::die("the test lost track of the session's context");
        }
    }

    void evaluate(const std::vector<llama_token> & tokens) {
        pp_evaluation_observer observer = {[](void *) { return false; }, [](int32_t, void *) {}, nullptr};
        int32_t n = 0;
        if (pp_session_evaluate(s, tokens.data(), (int32_t) tokens.size(), k_batch, &observer, &n) != PP_STATUS_OK || n != (int32_t) tokens.size()) {
            pp_test::die(std::string("pp_session_evaluate failed: ") + pp_session_last_error(s));
        }
        memory.insert(memory.end(), tokens.begin(), tokens.end());
    }

    // The scores of the prediction that is ready: only between a complete evaluation and the
    // sampling that uses it up, which is when llama.cpp has them.
    std::vector<float> scores() const {
        const float * logits = llama_get_logits_ith(ctx, -1);
        if (logits == nullptr) {
            pp_test::die("the context holds no prediction where the test expects one");
        }
        return std::vector<float>(logits, logits + pp_session_vocab_size(s));
    }

    // The state of the sequence in one block, as llama.cpp's public call writes it.
    std::vector<uint8_t> block() const {
        const size_t size = llama_state_seq_get_size_ext(ctx, 0, LLAMA_STATE_SEQ_FLAGS_NONE);
        if (size == 0 || size > k_store_limit) {
            pp_test::die("the state of the sequence has no size, or one beyond what this test holds");
        }
        std::vector<uint8_t> out(size);
        if (llama_state_seq_get_data_ext(ctx, out.data(), out.size(), 0, LLAMA_STATE_SEQ_FLAGS_NONE) != size) {
            pp_test::die("llama.cpp did not write the state of the sequence");
        }
        return out;
    }

    void configure(float repetition, float presence, float frequency) {
        pp_sampler_config config = {};
        config.top_p = 1.0f;
        config.repetition_penalty = repetition;
        config.presence_penalty = presence;
        config.frequency_penalty = frequency;
        config.penalty_window = PP_PENALTY_NO_WINDOW;
        config.reply_start = PP_REPLY_START_AT_SAMPLING;
        config.banned_tokens = banned.data();
        config.n_banned_tokens = (int32_t) banned.size();
        if (pp_session_configure_sampler(s, &config) != PP_STATUS_OK) {
            pp_test::die(std::string("pp_session_configure_sampler failed: ") + pp_session_last_error(s));
        }
    }

    llama_token sample() {
        llama_token token = LLAMA_TOKEN_NULL;
        if (pp_session_sample_next(s, &token) != PP_STATUS_OK) {
            pp_test::die(std::string("pp_session_sample_next failed: ") + pp_session_last_error(s));
        }
        return token;
    }

    // n tokens, each sampled and evaluated. With `seen`, a digest of the scores each was
    // sampled from, taken before the sampling.
    std::vector<llama_token> reply(int n, std::vector<uint64_t> * seen = nullptr) {
        std::vector<llama_token> out;
        for (int i = 0; i < n; i++) {
            if (seen != nullptr) {
                const std::vector<float> now = scores();
                seen->push_back(hash_of(now.data(), now.size() * sizeof(float)));
            }
            const llama_token token = sample();
            out.push_back(token);
            evaluate({token});
        }
        return out;
    }

    void clear() {
        if (pp_session_clear_memory(s) != PP_STATUS_OK) {
            pp_test::die(std::string("pp_session_clear_memory failed: ") + pp_session_last_error(s));
        }
        memory.clear();
        attach();
    }

    void resize(int32_t n_ctx) {
        if (pp_session_resize_context(s, n_ctx, k_budget) != PP_STATUS_OK) {
            pp_test::die(std::string("pp_session_resize_context failed: ") + pp_session_last_error(s));
        }
        attach();
    }

    std::vector<llama_token> record() const {
        const int32_t n = -pp_session_memory_tokens(s, nullptr, 0);
        std::vector<llama_token> out((size_t) std::max(0, n));
        if (n > 0 && pp_session_memory_tokens(s, out.data(), n) != n) {
            pp_test::die("pp_session_memory_tokens did not return the number it announced");
        }
        return out;
    }
};

talk open(const std::string & gguf, int32_t n_ctx) {
    talk t;
    char error[256] = "";
    pp_session * opened = nullptr;
    pp_session_params params = {};
    params.n_ctx = n_ctx;
    params.context_budget_bytes = k_budget;
    params.n_batch = k_batch;
    params.n_threads = pp_test::n_threads();
    params.load_mode = PP_LOAD_MODE_MMAP;
    if (pp_session_open_path(gguf.c_str(), &params, &opened, error, sizeof(error)) != PP_STATUS_OK) {
        pp_test::die(std::string("the model does not open: ") + error);
    }
    t.owner.reset(opened);
    t.s = opened;
    t.attach();
    for (llama_token id = 0; id < pp_session_vocab_size(opened); id++) {
        if (pp_session_is_end_of_generation(opened, id)) {
            t.banned.push_back(id);
        }
    }
    return t;
}

// Where an exported image goes: a store with room for exactly the bytes that were announced.
struct taker {
    std::vector<uint8_t> image;   // the bytes of every piece the sink accepted, in order, the caller's own included
    std::vector<uint8_t> buffer;  // the piece buffer
    std::vector<size_t> pieces;   // the size of every piece handed over
    size_t room = 0;              // the most bytes `image` takes
    int n_last = 0;               // how often "last" was said
    bool last_at_end = false;     // and whether the piece it was said for was the final one
    bool overflowed = false;      // more bytes came than were announced
    int stop_at = 0;              // the piece (from 1) at which to return false; 0 for never

    static bool write(size_t n, bool last, void * user) {
        taker & t = *(taker *) user;
        t.pieces.push_back(n);
        if (t.stop_at != 0 && (int) t.pieces.size() == t.stop_at) {
            return false;
        }
        if (n > t.buffer.size() || t.image.size() + n > t.room) {
            t.overflowed = true;
            return false;
        }
        t.image.insert(t.image.end(), t.buffer.begin(), t.buffer.begin() + (ptrdiff_t) n);
        t.n_last += last ? 1 : 0;
        t.last_at_end = last;
        return true;
    }
};

struct exported {
    int32_t status = PP_STATUS_OK;
    int64_t written = -1;
    taker to;
};

// One export. The store is allocated before the call, for the size the session announces.
exported export_image(talk & t, const std::vector<int32_t> & handles, size_t piece, int stop_at = 0, size_t own = 0) {
    exported out;
    const int64_t expected = pp_session_memory_state_bytes(t.s, handles.data(), (int32_t) handles.size());
    if (expected < 0 || (size_t) expected + own > k_store_limit) {
        pp_test::die("pp_session_memory_state_bytes gave no size, or one beyond what this test holds");
    }
    out.to.room = (size_t) expected + own;
    out.to.image.reserve(out.to.room);
    out.to.pieces.reserve(out.to.room / piece + 2);
    out.to.buffer.assign(piece, 0xA5);
    out.to.stop_at = stop_at;
    pp_memory_sink sink = {};
    sink.buffer = out.to.buffer.data();
    sink.buffer_size = out.to.buffer.size();
    sink.first_piece_offset = own;
    sink.write = taker::write;
    sink.user_data = &out.to;
    out.status = pp_session_export_memory(t.s, handles.data(), (int32_t) handles.size(), &sink, &out.written);
    return out;
}

// Where an image to import comes from, and the ways in which it can go wrong.
struct giver {
    const std::vector<uint8_t> * image = nullptr;
    std::vector<uint8_t> buffer;
    size_t piece = 0;       // bytes per call
    size_t at = 0;
    int calls = 0;
    int fail_at = 0;        // the call (from 1) that returns -1; 0 for never
    int end_at = 0;         // the call that returns 0 although bytes are left; 0 for never
    int too_much_at = 0;    // the call that returns more than the buffer holds
    int extra_pieces = 0;   // pieces of one byte to hand over after the image's last

    static int64_t read(void * user) {
        giver & g = *(giver *) user;
        g.calls++;
        if (g.fail_at != 0 && g.calls == g.fail_at) {
            return -1;
        }
        if (g.end_at != 0 && g.calls == g.end_at) {
            return 0;
        }
        if (g.too_much_at != 0 && g.calls == g.too_much_at) {
            return (int64_t) g.buffer.size() + 1;
        }
        if (g.at == g.image->size()) {
            if (g.extra_pieces > 0) {
                g.extra_pieces--;
                g.buffer[0] = 0x5A;
                return 1;
            }
            return 0;
        }
        const size_t n = std::min(g.piece, g.image->size() - g.at);
        std::memcpy(g.buffer.data(), g.image->data() + g.at, n);
        g.at += n;
        return (int64_t) n;
    }
};

struct imported {
    int32_t status = PP_STATUS_OK;
    std::string why;
    int32_t n = -1;
    pp_memory_checkpoint marks[PP_CHECKPOINTS_MAX] = {};
    int calls = 0;
    bool buffer_zero = false;
};

imported import_image(talk & t, const std::vector<uint8_t> & image, size_t piece, giver from = giver(), size_t own = 0) {
    imported out;
    from.image = &image;
    from.piece = piece;
    from.buffer.assign(piece, 0xA5);
    pp_memory_source source = {};
    source.buffer = from.buffer.data();
    source.buffer_size = from.buffer.size();
    source.first_piece_offset = own;
    source.read = giver::read;
    source.user_data = &from;
    out.status = pp_session_import_memory(t.s, &source, out.marks, &out.n);
    out.why = out.status == PP_STATUS_OK ? "" : pp_session_last_error(t.s);
    out.calls = from.calls;
    out.buffer_zero = all_zero(from.buffer);
    if (out.status == PP_STATUS_OK) {
        t.memory = t.record();
    }
    return out;
}

// More handles than any session of this test gives out: a session numbers its checkpoints from
// 0 and never uses a number twice, and every import here takes at most two.
constexpr int32_t k_handles = 8192;

// What every import that did not succeed must leave: no token in memory, no record of any,
// no checkpoint reported or held (the ones named, which were held before, and any handle the
// session could have given out since), zeros in the caller's buffer.
bool nothing_left(const talk & t, const imported & result, const std::vector<int32_t> & handles_before) {
    bool gone = true;
    for (const int32_t handle : handles_before) {
        gone = gone && pp_session_checkpoint_size(t.s, handle) < 0;
    }
    for (int32_t handle = 0; handle < k_handles; handle++) {
        gone = gone && pp_session_checkpoint_size(t.s, handle) < 0;
    }
    bool none_reported = result.n == 0;
    for (const pp_memory_checkpoint & mark : result.marks) {
        none_reported = none_reported && mark.handle == -1 && pp_session_checkpoint_size(t.s, mark.handle) < 0;
    }
    return pp_session_memory_token_count(t.s) == 0 && pp_session_memory_tokens(t.s, nullptr, 0) == 0 && pp_session_memory_state_bytes(t.s, nullptr, 0) == 0 &&
           gone && none_reported && result.buffer_zero;
}

size_t piece_for(size_t total, size_t n_pieces) {
    const size_t unit = PP_MEMORY_PIECE_BYTES_MIN;
    return std::max(unit, (total / n_pieces + unit - 1) / unit * unit);
}

// The position of the only occurrence of an eight-byte number in the first `within` bytes, or
// die: how the test finds a size in the header of an image without knowing its layout.
size_t find_number(const std::vector<uint8_t> & image, size_t within, uint64_t number) {
    size_t found = within;
    int n = 0;
    for (size_t i = 0; i + sizeof(number) <= within; i++) {
        if (std::memcmp(image.data() + i, &number, sizeof(number)) == 0) {
            found = i;
            n++;
        }
    }
    if (n != 1) {
        pp_test::die("a size was not found exactly once at the beginning of the image: the test no longer knows where it is");
    }
    return found;
}

// The same for a number that may stand there more than once: its last position.
size_t find_last_number(const std::vector<uint8_t> & image, size_t within, uint64_t number) {
    size_t found = within;
    for (size_t i = 0; i + sizeof(number) <= within; i++) {
        if (std::memcmp(image.data() + i, &number, sizeof(number)) == 0) {
            found = i;
        }
    }
    if (found == within) {
        pp_test::die("a size was not found at the beginning of the image: the test no longer knows where it is");
    }
    return found;
}

void put_number(std::vector<uint8_t> & image, size_t at, uint64_t number) {
    std::memcpy(image.data() + at, &number, sizeof(number));
}

} // namespace

int main(int argc, char ** argv) {
    std::string gguf;
    int64_t per_token = 0;
    for (int i = 1; i + 1 < argc; i += 2) {
        if (std::string(argv[i]) == "--gguf") {
            gguf = argv[i + 1];
        } else if (std::string(argv[i]) == "--bytes-per-token") {
            per_token = std::atoll(argv[i + 1]);
        }
    }
    if (gguf.empty()) {
        std::fprintf(stderr, "usage: %s --gguf FILE [--bytes-per-token B]\n", argv[0]);
        return 2;
    }
#if defined(__GLIBC__)
    // Every block of any size its own mapping, given back when it is freed: the address space
    // then follows what is allocated exactly, which the checks with a limit on it rely on.
    mallopt(M_MMAP_THRESHOLD, 128 * 1024);
#endif

    report r;
    // The window of the model's windowed layers, 0 for a model without: such a model keeps
    // less in an image than it had in memory once the memory is longer than the window (see
    // the end of this file). The model is loaded for the one number and freed again.
    int32_t n_window = 0;
    {
        pp_test::quiet_logs();
        llama_backend_init();
        const pp_test::model_ptr facts = pp_test::load_model(gguf, false);
        n_window = llama_model_n_swa(facts.get());
    }
    if (pp_runtime_init(nullptr, 0) != PP_STATUS_OK) {
        pp_test::die(std::string("pp_runtime_init failed: ") + pp_runtime_description());
    }

    talk a = open(gguf, k_open); // the subject
    talk b = open(gguf, k_open); // the control
    const int32_t n_vocab = pp_session_vocab_size(a.s);

    std::vector<llama_token> prompt;
    for (int i = 0; (int) prompt.size() < k_prompt + 64; i++) {
        const llama_token id = (llama_token) (5 + ((int64_t) i * 7919) % (n_vocab - 10));
        if (!pp_session_is_end_of_generation(a.s, id)) {
            prompt.push_back(id);
        }
    }
    const std::vector<llama_token> head(prompt.begin(), prompt.begin() + k_first);
    const std::vector<llama_token> rest(prompt.begin() + k_first, prompt.begin() + k_prompt);
    const std::vector<llama_token> spare(prompt.begin() + k_prompt, prompt.end());

    // --- The same beginning in both: a prompt with two checkpoints and the start of a reply ---

    int32_t a_first = -1, a_second = -1, b_first = -1, b_second = -1;
    const auto begin = [&](talk & t, int32_t & first, int32_t & second) {
        t.evaluate(head);
        first = pp_session_save_checkpoint(t.s);
        t.evaluate(rest);
        second = pp_session_save_checkpoint(t.s);
        t.configure(1.6f, 0.0f, 0.0f);
        return t.reply(k_said);
    };
    const std::vector<llama_token> said_a = begin(a, a_first, a_second), said_b = begin(b, b_first, b_second);
    const int32_t n_held = k_prompt + k_said;
    if (a_first < 0 || a_second < 0 || b_first < 0 || b_second < 0) {
        pp_test::die("a checkpoint could not be saved");
    }
    const std::vector<float> ready = a.scores();
    r.check(said_a == said_b && same_bits(ready, b.scores()) && pp_session_memory_token_count(a.s) == n_held,
            "two sessions that do the same hold the same: %d tokens in memory, the same %d sampled among them, and the prediction that is ready the same bit for bit "
            "(largest difference %.3g). Everything below rests on that", n_held, k_said, apart(ready, b.scores()));

    // --- What an image will hold, before anything is written ---

    const std::vector<int32_t> both = {a_first, a_second};
    const std::vector<uint8_t> block_before = a.block();
    // What a checkpoint of the whole memory would take: the most a checkpoint of an image of it may.
    const size_t partial_whole = llama_state_seq_get_size_ext(a.ctx, 0, LLAMA_STATE_SEQ_FLAGS_PARTIAL_ONLY);
    const int64_t expected = pp_session_memory_state_bytes(a.s, both.data(), 2);
    const int64_t expected_bare = pp_session_memory_state_bytes(a.s, nullptr, 0);
    const int64_t size_first = pp_session_checkpoint_size(a.s, a_first), size_second = pp_session_checkpoint_size(a.s, a_second);
    // llama.cpp's public call puts eight bytes in front of the state, a mark and the sequence id.
    const int64_t state_bytes = (int64_t) block_before.size() - 8;
    const int64_t header_bytes = expected_bare - state_bytes - (int64_t) n_held * (int64_t) sizeof(llama_token);
    r.check(expected > 0 && expected == expected_bare + size_first + size_second && header_bytes > 0 && header_bytes < 4096,
            "pp_session_memory_state_bytes: %" PRId64 " bytes with the two checkpoints (%" PRId64 " and %" PRId64 " bytes), %" PRId64 " without: "
            "the state of the sequence (%" PRId64 " bytes, llama.cpp's own count less its 8 bytes in front), %d token ids and %" PRId64 " bytes at the beginning",
            expected, size_first, size_second, expected_bare, state_bytes, n_held, header_bytes);

    // --- An export that the sink stops, at every piece in turn ---

    // The memory is only read, so whatever piece the sink stops at, the memory, the checkpoints
    // and the prediction that was ready must be as they were. The prediction is looked at after
    // every stopped export, the state of the sequence at the end.
    const size_t piece = piece_for((size_t) expected, 48);
    const int n_pieces = (int) (((size_t) expected + piece - 1) / piece);
    {
        bool stopped = true, counted = true, zeroed = true, prediction_kept = true;
        for (int k = 1; k <= n_pieces; k++) {
            const exported cut = export_image(a, both, piece, k);
            stopped = stopped && cut.status == PP_STATUS_MEMORY_STOPPED && (int) cut.to.pieces.size() == k;
            counted = counted && cut.written == (int64_t) ((size_t) (k - 1) * piece) && cut.to.image.size() == (size_t) cut.written;
            zeroed = zeroed && all_zero(cut.to.buffer);
            prediction_kept = prediction_kept && same_bits(ready, a.scores());
        }
        r.check(stopped && counted && zeroed && prediction_kept && a.block() == block_before && pp_session_memory_token_count(a.s) == n_held &&
                pp_session_checkpoint_size(a.s, a_first) == size_first && pp_session_checkpoint_size(a.s, a_second) == size_second && a.record() == a.memory,
                "an export stopped by its sink at each of its %d pieces of %zu bytes in turn: MEMORY_STOPPED every time (%s), with the bytes of the accepted pieces counted (%s) and "
                "zeros in the buffer afterwards (%s); the prediction that was ready is the same bit for bit after every one (%s), and at the end the state of the "
                "sequence is byte for byte what it was, with the %d tokens, their record and both checkpoints",
                n_pieces, piece, stopped ? "yes" : "NO", counted ? "yes" : "NO", zeroed ? "yes" : "NO", prediction_kept ? "yes" : "NO", n_held);
    }

    // --- The export ---

    double export_seconds = seconds();
    exported whole = export_image(a, both, piece);
    export_seconds = seconds() - export_seconds;
    const std::vector<uint8_t> & image = whole.to.image;
    {
        bool sized = !whole.to.pieces.empty();
        for (size_t i = 0; i + 1 < whole.to.pieces.size(); i++) {
            sized = sized && whole.to.pieces[i] == piece;
        }
        sized = sized && whole.to.pieces.back() >= 1 && whole.to.pieces.back() <= piece;
        r.check(whole.status == PP_STATUS_OK && whole.written == expected && (int64_t) image.size() == expected && !whole.to.overflowed && sized &&
                (int) whole.to.pieces.size() == n_pieces && whole.to.n_last == 1 && whole.to.last_at_end && all_zero(whole.to.buffer),
                "the export: OK (%d), %" PRId64 " bytes written and %zu handed over for the %" PRId64 " announced, in %zu pieces of %zu bytes and a last one of %zu, "
                "\"last\" said once and for the final piece, zeros in the buffer afterwards (%.1f MiB in %.0f ms)",
                whole.status, whole.written, image.size(), expected, whole.to.pieces.size() - 1, piece, whole.to.pieces.back(), (double) expected / k_mib, 1e3 * export_seconds);
    }
    if (whole.status != PP_STATUS_OK || (int64_t) image.size() != expected) {
        return r.finish(); // nothing below means anything without an image
    }
    {
        // The parts of the image that the test can name without knowing its layout: after the
        // beginning come the token ids, then the state as llama.cpp writes it.
        const bool tokens_there = std::memcmp(image.data() + header_bytes, a.memory.data(), (size_t) n_held * sizeof(llama_token)) == 0;
        const bool state_there = std::memcmp(image.data() + header_bytes + (size_t) n_held * sizeof(llama_token), block_before.data() + 8, (size_t) state_bytes) == 0;
        r.check(tokens_there && state_there && same_bits(ready, a.scores()) && a.block() == block_before,
                "in the image, behind its first %" PRId64 " bytes: the ids of the %d tokens in memory (%s), then the state of the sequence byte for byte as "
                "llama_state_seq_get_data_ext writes it into one block (%s); the memory and the prediction that was ready are untouched by the export",
                header_bytes, n_held, tokens_there ? "yes" : "NO", state_there ? "yes" : "NO");
    }

    // The prediction that was ready before the exports is still the one that is sampled from.
    const llama_token next = a.sample();
    r.check(next == b.sample(), "after the exports the subject samples from the prediction that was ready, and chooses the control's token");
    a.evaluate({next});
    b.evaluate({next});
    const std::vector<float> control_next = b.scores();

    // --- Forgotten, and read back ---

    a.clear();
    double import_seconds = seconds();
    imported back = import_image(a, image, piece);
    import_seconds = seconds() - import_seconds;
    {
        llama_token none = LLAMA_TOKEN_NULL;
        const int32_t unready = pp_session_sample_next(a.s, &none);
        const bool marks = back.n == 2 && back.marks[0].n_tokens == k_first && back.marks[1].n_tokens == k_prompt && back.marks[0].handle != back.marks[1].handle &&
                           pp_session_checkpoint_size(a.s, back.marks[0].handle) == size_first && pp_session_checkpoint_size(a.s, back.marks[1].handle) == size_second;
        std::vector<llama_token> held(b.memory.begin(), b.memory.begin() + n_held);
        r.check(back.status == PP_STATUS_OK && pp_session_memory_token_count(a.s) == n_held && a.record() == held && marks && back.buffer_zero &&
                back.calls == n_pieces + 1 && unready == PP_STATUS_SAMPLING_FAILED,
                "forgotten and imported: OK (%d: \"%s\"), %d tokens in memory, the record of them the control's first %d ids, both checkpoints back at %d and %d tokens "
                "with their sizes (%s), zeros in the buffer, the source asked %d times (every piece and once for the end), and no prediction ready (%d) "
                "(%.1f MiB in %.0f ms)",
                back.status, back.why.c_str(), pp_session_memory_token_count(a.s), n_held, back.n > 0 ? back.marks[0].n_tokens : -1, back.n > 1 ? back.marks[1].n_tokens : -1,
                marks ? "yes" : "NO", back.calls, unready, (double) expected / k_mib, 1e3 * import_seconds);
    }
    if (back.status != PP_STATUS_OK) {
        return r.finish();
    }
    const std::vector<int32_t> both_back = {back.marks[0].handle, back.marks[1].handle};
    {
        const std::vector<uint8_t> block_after = a.block();
        const exported again = export_image(a, both_back, piece);
        r.check(block_after == block_before && again.status == PP_STATUS_OK && again.to.image == image,
                "the state of the sequence after the import is byte for byte what it was before the export (%zu bytes, in one block from llama.cpp), and the "
                "memory written out again gives the same image byte for byte, checkpoints included (%zu bytes)", block_after.size(), again.to.image.size());
    }

    // The next prediction. A model with windowed layers writes only the window of those
    // layers into a state. While the memory is no longer than the window that is all of it,
    // and what comes back is what was there, cell for cell; every image of this part of the
    // test is that short for the one such model there is (Shard-2, a window of 256). A model
    // with a shorter window would get back fewer cells than the control holds, in other
    // places of the buffers: its attention then adds the same numbers up in another order,
    // and it is held to the control to within rounding, as the long memory at the end of this
    // file is.
    a.evaluate({next});
    const std::vector<float> subject_next = a.scores();
    const bool exact = n_window == 0 || n_window >= 256;
    const double k_close = 5e-3; // the limit the logit tests of native/tests hold two ways of evaluating to
    r.check(exact ? same_bits(subject_next, control_next) : apart(subject_next, control_next) <= k_close,
            "the prediction after the next token: %s that of the control, which was never written out (largest difference %.3g over %d scores%s)",
            exact ? "bit for bit" : "within 5e-3 of", apart(subject_next, control_next), n_vocab,
            exact ? "" : "; this model's windowed layers hold less than the memory, and their restored cells lie elsewhere in the buffers");

    // --- A greedy reply, the penalties, the checkpoints ---

    {
        std::vector<uint64_t> seen_a, seen_b;
        a.configure(1.0f, 0.0f, 0.0f);
        b.configure(1.0f, 0.0f, 0.0f);
        const std::vector<llama_token> greedy_a = a.reply(64, &seen_a), greedy_b = b.reply(64, &seen_b);
        size_t same_steps = 0;
        while (same_steps < seen_a.size() && seen_a[same_steps] == seen_b[same_steps]) {
            same_steps++;
        }
        r.check(greedy_a == greedy_b && (!exact || same_steps == seen_a.size()),
                "64 greedy tokens after the import are the control's 64 (%s), and the scores each was chosen from are the control's bit for bit at %zu of 64 steps%s",
                greedy_a == greedy_b ? "the same" : "NOT the same", same_steps, exact ? "" : " (not required of a model whose window is shorter than the memory)");

        // The penalties are computed from the record of the tokens in memory, which the image
        // brought back: a repetition penalty over all of it, then presence and frequency over
        // the reply. A record that was not restored fails the sampling outright (its length is
        // held to the memory's); one with other ids in it chooses other tokens.
        a.configure(1.6f, 0.0f, 0.0f);
        b.configure(1.6f, 0.0f, 0.0f);
        const std::vector<llama_token> repeated_a = a.reply(16), repeated_b = b.reply(16);
        a.configure(1.0f, 0.5f, 1.5f);
        b.configure(1.0f, 0.5f, 1.5f);
        const std::vector<llama_token> present_a = a.reply(16), present_b = b.reply(16);
        r.check(repeated_a == repeated_b && present_a == present_b && a.record() == b.memory,
                "with a repetition penalty over the whole memory, then with presence and frequency penalties over the reply: the control's 16 tokens each time, "
                "and the record of the %zu tokens in memory is the control's", b.memory.size());
    }
    std::vector<float> control_after_second, control_after_first;
    {
        // Back through the checkpoints that came out of the image, against the control going
        // back through its own, which never left its memory.
        const bool to_second = pp_session_restore_checkpoint(a.s, both_back[1]) && pp_session_restore_checkpoint(b.s, b_second) &&
                               pp_session_memory_token_count(a.s) == k_prompt && pp_session_memory_token_count(b.s) == k_prompt;
        a.memory.resize(k_prompt);
        b.memory.resize(k_prompt);
        a.evaluate({spare[0]});
        b.evaluate({spare[0]});
        const std::vector<float> a2 = a.scores();
        control_after_second = b.scores();
        const bool to_first = pp_session_restore_checkpoint(a.s, both_back[0]) && pp_session_restore_checkpoint(b.s, b_first) &&
                              pp_session_memory_token_count(a.s) == k_first && pp_session_memory_token_count(b.s) == k_first;
        a.memory.resize(k_first);
        b.memory.resize(k_first);
        a.evaluate({spare[1]});
        b.evaluate({spare[1]});
        const std::vector<float> a1 = a.scores();
        control_after_first = b.scores();
        r.check(to_second && to_first && (exact ? same_bits(a2, control_after_second) && same_bits(a1, control_after_first)
                                                : apart(a2, control_after_second) <= k_close && apart(a1, control_after_first) <= k_close),
                "the checkpoints that came out of the image take the memory back to %d and then to %d tokens, and the prediction after one more token is %s the "
                "control's after going back through its own checkpoints (largest differences %.3g and %.3g)",
                k_prompt, k_first, exact ? "bit for bit" : "within 5e-3 of", apart(a2, control_after_second), apart(a1, control_after_first));

        // The second checkpoint now belongs to a length the memory no longer has.
        taker unused;
        unused.buffer.assign(piece, 0);
        pp_memory_sink sink = {unused.buffer.data(), unused.buffer.size(), 0, taker::write, &unused};
        const int32_t beyond = pp_session_export_memory(a.s, both_back.data(), 2, &sink, nullptr);
        r.check(beyond == PP_STATUS_INVALID_ARGUMENT && unused.pieces.empty() && pp_session_memory_state_bytes(a.s, both_back.data(), 2) == -1 &&
                pp_session_memory_state_bytes(a.s, both_back.data(), 1) > 0,
                "with %d tokens in memory, a checkpoint of %d named for an image: INVALID_ARGUMENT (%d) before the sink is called, and no size (-1); the checkpoint of %d alone is taken",
                pp_session_memory_token_count(a.s), k_prompt, beyond, k_first);
    }

    // --- Into a larger context, into the smallest that fits, and into one too small ---

    {
        a.clear();
        a.resize(2 * k_open);
        const imported larger = import_image(a, image, piece);
        const bool same_state = larger.status == PP_STATUS_OK && a.block() == block_before;
        if (larger.status == PP_STATUS_OK) {
            a.evaluate({next});
        }
        const std::vector<float> there = larger.status == PP_STATUS_OK ? a.scores() : std::vector<float>();
        r.check(same_state && larger.n == 2 && pp_session_context_size(a.s) == 2 * k_open && (exact ? same_bits(there, control_next) : apart(there, control_next) <= k_close),
                "the image, written in a context of %d tokens, imported into one of %d: OK (%d), the state of the sequence byte for byte the same, and the prediction "
                "after the next token %s the control's in its context of %d (largest difference %.3g)",
                k_open, 2 * k_open, larger.status, exact ? "bit for bit" : "within 5e-3 of", k_open, apart(there, control_next));
        if (larger.status != PP_STATUS_OK) {
            return r.finish();
        }

        // Two more images from here: one whose tokens fill the smallest context exactly, with
        // its checkpoints (the memory still begins with the tokens they were saved at), and
        // one with a single token more.
        const int32_t n_small = 256;
        std::vector<llama_token> filler;
        for (size_t i = 0; pp_session_memory_token_count(a.s) + (int32_t) filler.size() < n_small; i++) {
            filler.push_back(spare[i % spare.size()]);
        }
        a.evaluate(filler);
        const std::vector<int32_t> marks = {larger.marks[0].handle, larger.marks[1].handle};
        const exported fits = export_image(a, marks, piece);
        a.evaluate({spare[2]});
        const exported over = export_image(a, {}, piece);

        // What an image grows by with a token: the key-value rows of the token, which is the
        // model's figure in models/manifest.json, what llama.cpp records of its cell (12
        // bytes, twice for a model with two caches, and 12 more where a cell keeps its token
        // id) and the 4 bytes of its id in the record. Exactly, token for token, as long as
        // windowed layers still write every cell.
        if (per_token <= 0) {
            r.note("no --bytes-per-token was given: what an image grows by is NOT held to models/manifest.json");
        } else if (n_window > 0 && n_window < n_small) {
            r.note("this model's window is shorter than the images of this test: what an image grows by is NOT held to models/manifest.json");
        } else {
            const int64_t without = (int64_t) fits.to.image.size() - pp_session_checkpoint_size(a.s, marks[0]) - pp_session_checkpoint_size(a.s, marks[1]);
            const int64_t more = n_small - n_held, grown = without - expected_bare;
            r.check(fits.status == PP_STATUS_OK && grown % more == 0 && grown / more >= per_token + 16 && grown / more <= per_token + 28,
                    "an image of %d tokens is %" PRId64 " bytes larger than one of %d, both without checkpoints: %" PRId64 " bytes for each token and no remainder (%s), "
                    "which is the %" PRId64 " key-value bytes per token of models/manifest.json and %" PRId64 " more (from 16 to 28: the cell as llama.cpp records it, and the token's id)",
                    n_small, grown, n_held, grown / more, grown % more == 0 ? "exactly" : "NOT exactly", per_token, grown / more - per_token);
        }

        a.clear();
        a.resize(n_small);
        const imported too_many = import_image(a, over.to.image, piece);
        const bool empty_after = nothing_left(a, too_many, marks);
        const imported exactly = import_image(a, fits.to.image, piece);
        int32_t n_in = 0;
        pp_evaluation_observer observer = {[](void *) { return false; }, [](int32_t, void *) {}, nullptr};
        const int32_t one_more = exactly.status == PP_STATUS_OK ? pp_session_evaluate(a.s, spare.data(), 1, 1, &observer, &n_in) : -1;
        bool back_again = false;
        std::vector<float> here;
        if (exactly.status == PP_STATUS_OK && exactly.n == 2) {
            back_again = pp_session_restore_checkpoint(a.s, exactly.marks[1].handle) && pp_session_memory_token_count(a.s) == k_prompt;
            a.memory.resize(k_prompt);
            a.evaluate({spare[0]});
            here = a.scores();
        }
        r.check(fits.status == PP_STATUS_OK && over.status == PP_STATUS_OK && pp_session_context_size(a.s) == n_small &&
                too_many.status == PP_STATUS_CONTEXT_FULL && empty_after && too_many.calls == 1 &&
                exactly.status == PP_STATUS_OK && one_more == PP_STATUS_CONTEXT_FULL && back_again &&
                (exact ? same_bits(here, control_after_second) : apart(here, control_after_second) <= k_close),
                "into a context of %d tokens: an image of %d tokens is refused with CONTEXT_FULL (%d: \"%s\") after one piece, nothing left behind; an image of exactly %d "
                "is imported (%d), the memory is then full (one more token: %d), and going back through its checkpoint of %d gives the control's prediction %s "
                "(largest difference %.3g)",
                n_small, n_small + 1, too_many.status, too_many.why.c_str(), n_small, exactly.status, one_more, k_prompt, exact ? "bit for bit" : "within 5e-3",
                apart(here, control_after_second));
    }

    // --- An import that ends at every piece in turn ---

    // What a new session predicts after a few tokens: what the subject must predict after
    // every import that did not succeed.
    const std::vector<llama_token> probe(prompt.begin() + 7, prompt.begin() + 15);
    std::vector<float> control_probe;
    {
        talk fresh = open(gguf, k_open);
        fresh.evaluate(probe);
        control_probe = fresh.scores();
    }
    a.clear();
    {
        // The source fails at its first call, its second, and so on to the call after the last
        // piece, where the session asks whether anything follows: at that one the whole image
        // is in, checkpoints and all, and all of it has to go again.
        bool stopped = true, empty = true, works = true;
        int at_fault = 0;
        std::vector<int32_t> handles = both_back;
        for (int k = 1; k <= n_pieces + 1; k++) {
            giver from;
            from.fail_at = k;
            const imported cut = import_image(a, image, piece, from);
            const bool this_stopped = cut.status == PP_STATUS_MEMORY_STOPPED && cut.calls == k;
            const bool this_empty = nothing_left(a, cut, handles);
            a.evaluate(probe);
            const bool this_works = same_bits(a.scores(), control_probe);
            if ((!this_stopped || !this_empty || !this_works) && at_fault == 0) {
                at_fault = k;
            }
            stopped = stopped && this_stopped;
            empty = empty && this_empty;
            works = works && this_works;
            a.clear();
        }
        r.check(stopped && empty && works,
                "an import whose source fails at each of its %d calls in turn, the call after the last piece included: MEMORY_STOPPED every time (%s); afterwards no token "
                "in memory, no record, no checkpoint and zeros in the buffer (%s); and the session then predicts, after %zu tokens, bit for bit what a new session "
                "predicts (%s)%s%s",
                n_pieces + 1, stopped ? "yes" : "NO", empty ? "yes" : "NO", probe.size(), works ? "yes" : "NO", at_fault ? "; first at call " : "",
                at_fault ? std::to_string(at_fault).c_str() : "");
    }
    {
        // The same for an image that ends early: the source says "no more" at every call in turn.
        bool failed = true, empty = true;
        for (int k = 1; k <= n_pieces; k++) {
            giver from;
            from.end_at = k;
            const imported cut = import_image(a, image, piece, from);
            failed = failed && cut.status == PP_STATUS_MEMORY_FAILED && cut.calls == k;
            empty = empty && nothing_left(a, cut, both_back);
        }
        a.evaluate(probe);
        const bool works = same_bits(a.scores(), control_probe);
        a.clear();
        r.check(failed && empty && works, "an image that ends before each of its %d pieces in turn: MEMORY_FAILED every time (%s), nothing left behind (%s), and the session "
                "predicts as a new one afterwards (%s)", n_pieces, failed ? "yes" : "NO", empty ? "yes" : "NO", works ? "yes" : "NO");
    }
    {
        // Something behind the image: another piece, a byte more in the last piece, a source
        // that claims more bytes than its buffer holds.
        giver another;
        another.extra_pieces = 1;
        const imported followed = import_image(a, image, piece, another);
        const bool followed_empty = nothing_left(a, followed, both_back);
        std::vector<uint8_t> longer = image;
        longer.push_back(0);
        // Pieces of the image's own size, so that the byte lies in the last piece with the image's end.
        const imported tail = import_image(a, longer, std::max(piece, image.size() % piece == 0 ? piece + PP_MEMORY_PIECE_BYTES_MIN : piece));
        const bool tail_empty = nothing_left(a, tail, both_back);
        giver liar;
        liar.too_much_at = 2;
        const imported lied = import_image(a, image, piece, liar);
        const bool lied_empty = nothing_left(a, lied, both_back);
        r.check(followed.status == PP_STATUS_MEMORY_FAILED && followed_empty && tail.status == PP_STATUS_MEMORY_FAILED && tail_empty &&
                lied.status == PP_STATUS_MEMORY_FAILED && lied_empty,
                "a piece behind the image (%d: \"%s\"), a byte behind it in its last piece (%d: \"%s\"), a source that returns more bytes than its buffer holds "
                "(%d: \"%s\"): MEMORY_FAILED each, nothing left behind", followed.status, followed.why.c_str(), tail.status, tail.why.c_str(), lied.status, lied.why.c_str());
    }

    // --- A changed image ---

    // What authentication is for, and what the session can still tell without it. None of
    // these changes touches what llama.cpp believes without checking (see
    // pp_session_import_memory): every one is either stopped before the state is read or
    // leaves the state as it was written.
    {
        // Every byte of the image's beginning, changed in turn. All of it is sizes, counts and
        // marks that are held to each other and to the session, with one exception that nothing
        // in an image can settle: the length a checkpoint belongs to, changed into another
        // length within the memory.
        int refused = 0, accepted_as_other_length = 0, wrong = 0;
        for (int64_t at = 0; at < header_bytes; at++) {
            std::vector<uint8_t> changed = image;
            changed[(size_t) at] ^= 0xFF;
            const imported result = import_image(a, changed, piece);
            if (result.status == PP_STATUS_MEMORY_FAILED && nothing_left(a, result, both_back)) {
                refused++;
            } else if (result.status == PP_STATUS_OK && result.n == 2 && a.block() == block_before &&
                       (result.marks[0].n_tokens != k_first) != (result.marks[1].n_tokens != k_prompt)) {
                accepted_as_other_length++;
                a.clear();
            } else {
                wrong++;
                a.clear();
            }
        }
        r.check(wrong == 0 && refused >= header_bytes - 8 && refused + accepted_as_other_length == header_bytes,
                "each of the first %" PRId64 " bytes of the image changed in turn: %d refused with MEMORY_FAILED and nothing left behind, %d accepted, each of those a "
                "checkpoint's length changed into another length within the memory with everything else as it was written (which only the caller's authentication can tell), "
                "%d anything else", header_bytes, refused, accepted_as_other_length, wrong);
    }
    {
        const size_t tokens_at = (size_t) header_bytes, state_at = tokens_at + (size_t) n_held * sizeof(llama_token);
        const auto with_token = [&](llama_token id) {
            std::vector<uint8_t> changed = image;
            std::memcpy(changed.data() + tokens_at + 17 * sizeof(llama_token), &id, sizeof(id));
            const imported result = import_image(a, changed, piece);
            return result.status == PP_STATUS_MEMORY_FAILED && nothing_left(a, result, both_back);
        };
        const bool beyond_vocabulary = with_token(n_vocab), negative = with_token(-1);

        // The state one byte shorter and one byte longer than llama.cpp needs, in an image
        // whose sizes say so consistently: llama.cpp then asks for a byte the state does not
        // have, or leaves one, and neither is let pass.
        const size_t total_at = find_number(image, (size_t) header_bytes, (uint64_t) expected);
        const size_t state_size_at = find_number(image, (size_t) header_bytes, (uint64_t) state_bytes);
        std::vector<uint8_t> shorter = image;
        shorter.erase(shorter.begin() + (ptrdiff_t) (state_at + (size_t) state_bytes - 1));
        put_number(shorter, total_at, (uint64_t) expected - 1);
        put_number(shorter, state_size_at, (uint64_t) state_bytes - 1);
        const imported short_state = import_image(a, shorter, piece);
        const bool short_empty = nothing_left(a, short_state, both_back);
        std::vector<uint8_t> longer = image;
        longer.insert(longer.begin() + (ptrdiff_t) (state_at + (size_t) state_bytes), (uint8_t) 0);
        put_number(longer, total_at, (uint64_t) expected + 1);
        put_number(longer, state_size_at, (uint64_t) state_bytes + 1);
        const imported long_state = import_image(a, longer, piece);
        const bool long_empty = nothing_left(a, long_state, both_back);

        // A state that llama.cpp itself refuses: the number of cells, which it holds to the
        // context, set beyond any context (the second number of a state that begins with a
        // key-value cache, as the state of every model here does).
        std::vector<uint8_t> crowded = image;
        const uint32_t cells = 0x7fffffffu;
        std::memcpy(crowded.data() + state_at + 4, &cells, sizeof(cells));
        const imported refused_by_llama = import_image(a, crowded, piece);
        const bool crowded_empty = nothing_left(a, refused_by_llama, both_back);

        // A checkpoint one byte larger than a checkpoint of the whole memory, again with sizes
        // that agree with each other: the image's last checkpoint, with bytes added at its end.
        const size_t second_size_at = find_last_number(image, (size_t) header_bytes, (uint64_t) size_second);
        std::vector<uint8_t> swollen = image;
        const uint64_t grown = (uint64_t) partial_whole + 1;
        swollen.insert(swollen.end(), (size_t) (grown - (uint64_t) size_second), (uint8_t) 0);
        put_number(swollen, total_at, (uint64_t) expected + (grown - (uint64_t) size_second));
        put_number(swollen, second_size_at, grown);
        const imported swollen_result = import_image(a, swollen, piece);
        const bool swollen_empty = nothing_left(a, swollen_result, both_back);

        a.evaluate(probe);
        const bool works = same_bits(a.scores(), control_probe);
        a.clear();
        // The two states of a wrong size would also be refused at the image's end, where the
        // bytes no longer come out even. They are to be stopped where they go wrong, before
        // anything behind the state is read as something it is not: the reason says which.
        const bool short_at_state = short_state.why.find("more bytes were asked") != std::string::npos;
        const bool long_at_state = long_state.why.find("fewer bytes of the state") != std::string::npos;
        r.check(beyond_vocabulary && negative && short_state.status == PP_STATUS_MEMORY_FAILED && short_empty && short_at_state &&
                long_state.status == PP_STATUS_MEMORY_FAILED && long_empty && long_at_state &&
                refused_by_llama.status == PP_STATUS_MEMORY_FAILED && crowded_empty && swollen_result.status == PP_STATUS_MEMORY_FAILED && swollen_empty && works,
                "a token id beyond the vocabulary and a negative one (%s, %s); a state one byte shorter than llama.cpp needs (%d: \"%s\") and one byte longer (%d: \"%s\"); "
                "a state llama.cpp refuses (%d: \"%s\"); a checkpoint larger than one of the whole memory (%d: \"%s\"): MEMORY_FAILED each with nothing left behind, "
                "and the session predicts as a new one afterwards (%s)",
                beyond_vocabulary ? "refused" : "NOT refused", negative ? "refused" : "NOT refused", short_state.status, short_state.why.c_str(), long_state.status,
                long_state.why.c_str(), refused_by_llama.status, refused_by_llama.why.c_str(), swollen_result.status, swollen_result.why.c_str(), works ? "yes" : "NO");
    }

    // --- What is refused with nothing touched ---

    {
        // An import needs an empty memory and no checkpoint: with either, the source is not asked.
        a.evaluate(probe);
        giver untouched;
        const imported into_memory = import_image(a, image, piece, untouched);
        const bool kept = pp_session_memory_token_count(a.s) == (int32_t) probe.size() && same_bits(a.scores(), control_probe);
        const int32_t mark = pp_session_save_checkpoint(a.s);
        bool removed = false;
        const bool emptied = pp_session_rewind_to(a.s, 0, &removed) == PP_STATUS_OK && removed;
        imported beside_checkpoint;
        if (emptied && mark >= 0) {
            a.memory.clear();
            beside_checkpoint = import_image(a, image, piece);
        }
        if (emptied && mark >= 0) {
            r.check(into_memory.status == PP_STATUS_INVALID_ARGUMENT && into_memory.calls == 0 && kept && beside_checkpoint.status == PP_STATUS_INVALID_ARGUMENT &&
                    beside_checkpoint.calls == 0 && pp_session_checkpoint_size(a.s, mark) > 0,
                    "an import into a memory that holds tokens: INVALID_ARGUMENT (%d) with the source not asked, the tokens and the prediction that was ready untouched; "
                    "into an empty memory beside a checkpoint: INVALID_ARGUMENT (%d), the checkpoint still held", into_memory.status, beside_checkpoint.status);
        } else {
            r.check(into_memory.status == PP_STATUS_INVALID_ARGUMENT && into_memory.calls == 0 && kept,
                    "an import into a memory that holds tokens: INVALID_ARGUMENT (%d) with the source not asked, the tokens and the prediction that was ready untouched",
                    into_memory.status);
            r.note("this model's memory cannot be emptied beside a checkpoint without forgetting both: an import beside a checkpoint is NOT tried");
        }
        a.clear();

        // An export needs a memory, known checkpoints named once, and a sink it can use.
        taker unused;
        unused.buffer.assign(piece, 0);
        pp_memory_sink sink = {unused.buffer.data(), unused.buffer.size(), 0, taker::write, &unused};
        const int32_t of_nothing = pp_session_export_memory(a.s, nullptr, 0, &sink, nullptr);
        const int64_t size_of_nothing = pp_session_memory_state_bytes(a.s, nullptr, 0);
        a.evaluate(probe);
        const int32_t held = pp_session_save_checkpoint(a.s);
        const int32_t unknown[] = {held + 1000}, twice[] = {held, held};
        int32_t many[PP_CHECKPOINTS_MAX + 1];
        std::fill(many, many + PP_CHECKPOINTS_MAX + 1, held);
        const int32_t e_unknown = pp_session_export_memory(a.s, unknown, 1, &sink, nullptr);
        const int32_t e_twice = pp_session_export_memory(a.s, twice, 2, &sink, nullptr);
        const int32_t e_many = pp_session_export_memory(a.s, many, PP_CHECKPOINTS_MAX + 1, &sink, nullptr);
        const int32_t e_missing = pp_session_export_memory(a.s, nullptr, 1, &sink, nullptr);
        pp_memory_sink small = sink, shifted = sink, mute = sink, nowhere = sink;
        small.buffer_size = PP_MEMORY_PIECE_BYTES_MIN - 1;
        shifted.first_piece_offset = shifted.buffer_size;
        mute.write = nullptr;
        nowhere.buffer = nullptr;
        const int32_t e_small = pp_session_export_memory(a.s, nullptr, 0, &small, nullptr);
        const int32_t e_shifted = pp_session_export_memory(a.s, nullptr, 0, &shifted, nullptr);
        const int32_t e_mute = pp_session_export_memory(a.s, nullptr, 0, &mute, nullptr);
        const int32_t e_nowhere = pp_session_export_memory(a.s, nullptr, 0, &nowhere, nullptr);
        const int32_t e_none = pp_session_export_memory(a.s, nullptr, 0, nullptr, nullptr);
        const bool sizes_refused = pp_session_memory_state_bytes(a.s, unknown, 1) == -1 && pp_session_memory_state_bytes(a.s, twice, 2) == -1 &&
                                   pp_session_memory_state_bytes(a.s, many, PP_CHECKPOINTS_MAX + 1) == -1 && pp_session_memory_state_bytes(a.s, nullptr, 1) == -1;
        r.check(of_nothing == PP_STATUS_INVALID_ARGUMENT && size_of_nothing == 0 && e_unknown == PP_STATUS_INVALID_ARGUMENT && e_twice == PP_STATUS_INVALID_ARGUMENT &&
                e_many == PP_STATUS_INVALID_ARGUMENT && e_missing == PP_STATUS_INVALID_ARGUMENT && e_small == PP_STATUS_INVALID_ARGUMENT && e_shifted == PP_STATUS_INVALID_ARGUMENT &&
                e_mute == PP_STATUS_INVALID_ARGUMENT && e_nowhere == PP_STATUS_INVALID_ARGUMENT && e_none == PP_STATUS_INVALID_ARGUMENT && sizes_refused && unused.pieces.empty() &&
                same_bits(a.scores(), control_probe),
                "an export of an empty memory (%d, and a size of %" PRId64 "), with an unknown checkpoint (%d), one named twice (%d), %d of them (%d), a missing list (%d), "
                "a buffer one byte below the smallest (%d), a first piece offset of the whole buffer (%d), no callback (%d), no buffer (%d), no sink (%d): "
                "INVALID_ARGUMENT each, the sink never called, no size given for the checkpoints refused (-1), the prediction that was ready untouched",
                of_nothing, size_of_nothing, e_unknown, e_twice, PP_CHECKPOINTS_MAX + 1, e_many, e_missing, e_small, e_shifted, e_mute, e_nowhere, e_none);

        // And an import the same.
        a.clear();
        giver from;
        from.image = &image;
        from.piece = piece;
        from.buffer.assign(piece, 0);
        pp_memory_source source = {from.buffer.data(), from.buffer.size(), 0, giver::read, &from};
        pp_memory_source s_small = source, s_shifted = source, s_mute = source, s_nowhere = source;
        s_small.buffer_size = PP_MEMORY_PIECE_BYTES_MIN - 1;
        s_shifted.first_piece_offset = s_shifted.buffer_size;
        s_mute.read = nullptr;
        s_nowhere.buffer = nullptr;
        pp_memory_checkpoint marks[PP_CHECKPOINTS_MAX];
        int32_t n = 7;
        const int32_t i_small = pp_session_import_memory(a.s, &s_small, marks, &n);
        const int32_t i_shifted = pp_session_import_memory(a.s, &s_shifted, marks, &n);
        const int32_t i_mute = pp_session_import_memory(a.s, &s_mute, marks, &n);
        const int32_t i_nowhere = pp_session_import_memory(a.s, &s_nowhere, marks, &n);
        const int32_t i_none = pp_session_import_memory(a.s, nullptr, marks, &n);
        const int32_t i_no_marks = pp_session_import_memory(a.s, &source, nullptr, &n);
        const int32_t i_no_count = pp_session_import_memory(a.s, &source, marks, nullptr);
        r.check(i_small == PP_STATUS_INVALID_ARGUMENT && i_shifted == PP_STATUS_INVALID_ARGUMENT && i_mute == PP_STATUS_INVALID_ARGUMENT && i_nowhere == PP_STATUS_INVALID_ARGUMENT &&
                i_none == PP_STATUS_INVALID_ARGUMENT && i_no_marks == PP_STATUS_INVALID_ARGUMENT && i_no_count == PP_STATUS_INVALID_ARGUMENT && from.calls == 0 && n == 0,
                "an import with a buffer one byte below the smallest (%d), a first piece offset of the whole buffer (%d), no callback (%d), no buffer (%d), no source (%d), "
                "nowhere to report the checkpoints (%d, %d): INVALID_ARGUMENT each, the source never asked", i_small, i_shifted, i_mute, i_nowhere, i_none, i_no_marks, i_no_count);
    }

    // --- The caller's own bytes in the first piece ---

    {
        // A caller that seals pieces of one size wants its own header in the first of them.
        // The export leaves room at the start of the buffer and fills the rest; the import
        // skips as much in the first piece.
        const size_t own = 100;
        a.evaluate(head);
        a.evaluate(rest);
        exported out;
        const int64_t bytes = pp_session_memory_state_bytes(a.s, nullptr, 0);
        out.to.room = (size_t) bytes + own;
        out.to.image.reserve(out.to.room);
        out.to.buffer.assign(piece, 0);
        for (size_t i = 0; i < own; i++) {
            out.to.buffer[i] = (uint8_t) (i + 1);
        }
        pp_memory_sink sink = {out.to.buffer.data(), out.to.buffer.size(), own, taker::write, &out.to};
        out.status = pp_session_export_memory(a.s, nullptr, 0, &sink, &out.written);
        bool kept = out.to.image.size() == (size_t) bytes + own;
        for (size_t i = 0; i < own && kept; i++) {
            kept = out.to.image[i] == (uint8_t) (i + 1);
        }
        const exported plain = export_image(a, {}, piece);
        const bool same_behind = kept && std::equal(plain.to.image.begin(), plain.to.image.end(), out.to.image.begin() + (ptrdiff_t) own);
        const std::vector<uint8_t> state = a.block();
        a.clear();
        const imported skipped = import_image(a, out.to.image, piece, giver(), own);
        const bool restored = skipped.status == PP_STATUS_OK && a.block() == state;
        if (skipped.status == PP_STATUS_OK) {
            a.clear();
        }
        // A first piece that is shorter than the bytes to skip in it.
        std::vector<uint8_t> stub(out.to.image.begin(), out.to.image.begin() + (ptrdiff_t) (own / 2));
        const imported too_short = import_image(a, stub, piece, giver(), own);
        r.check(out.status == PP_STATUS_OK && out.written == bytes && kept && same_behind && !out.to.pieces.empty() && out.to.pieces.front() == piece && restored &&
                too_short.status == PP_STATUS_MEMORY_FAILED && nothing_left(a, too_short, {}),
                "with %zu bytes of the caller's own at the start of the first piece: the export hands them over unchanged at the head of a first piece of full size, the image "
                "behind them is the image without (%" PRId64 " bytes written, the caller's not counted); the import skips them and restores the state byte for byte (%d); "
                "a first piece shorter than they are: MEMORY_FAILED (%d)", own, out.written, skipped.status, too_short.status);
    }

    // --- No block of the state's size ---

    if (!k_limits_address_space) {
        r.note("this allocator ends the process when it cannot map (a sanitizer's, or Android's): the checks with a limit on the address space are NOT run");
    } else {
        // An image without checkpoints, whose import allocates none: what is left is what the
        // session and llama.cpp allocate for the state itself. With room in the address space
        // for a fraction of the state, a block of its size cannot be had (shown by asking for
        // one), and the export and the import both succeed.
        a.evaluate(head);
        a.evaluate(rest);
        const std::vector<uint8_t> state = a.block();
        const int64_t bytes = pp_session_memory_state_bytes(a.s, nullptr, 0);
        const int64_t room = std::min<int64_t>((int64_t) 2 << 20, (int64_t) state.size() / 4);
        exported out;
        out.to.room = (size_t) bytes;
        out.to.image.reserve(out.to.room);
        out.to.pieces.reserve(out.to.room / piece + 2);
        out.to.buffer.assign(piece, 0);
        pp_memory_sink sink = {out.to.buffer.data(), out.to.buffer.size(), 0, taker::write, &out.to};
        giver from;
        from.image = &out.to.image;
        from.piece = piece;
        from.buffer.assign(piece, 0);
        pp_memory_source source = {from.buffer.data(), from.buffer.size(), 0, giver::read, &from};
        pp_memory_checkpoint marks[PP_CHECKPOINTS_MAX];
        int32_t n = -1, imported_status = -1;
        bool limited = false, block_refused = false;
        {
            const address_space limit(room);
            limited = limit.in_force();
            void * whole_state = std::malloc(state.size());
            block_refused = whole_state == nullptr;
            std::free(whole_state);
            out.status = pp_session_export_memory(a.s, nullptr, 0, &sink, &out.written);
        }
        a.clear(); // outside the limit: a new context is a mapping of its own
        {
            const address_space limit(room);
            limited = limited && limit.in_force();
            imported_status = pp_session_import_memory(a.s, &source, marks, &n);
        }
        if (!limited) {
            r.note("no address space limit can be set here: that no block of the state's size is allocated is NOT checked");
        } else {
            r.check(block_refused && out.status == PP_STATUS_OK && out.written == bytes && imported_status == PP_STATUS_OK && a.block() == state,
                    "with room in the address space for %.1f MiB more, where a block of the state's %.1f MiB cannot be allocated (malloc: %s): the export of the %.1f MiB "
                    "image succeeds (%d) and so does its import (%d), the state byte for byte what it was",
                    (double) room / k_mib, (double) state.size() / k_mib, block_refused ? "refused" : "NOT refused", (double) bytes / k_mib, out.status, imported_status);
        }
        a.clear();
    }

    // --- A model with windowed layers: what its image holds, and what can be cut afterwards ---

    if (n_window > 0) {
        // Such a model writes of its windowed layers only the window before the end of the
        // memory, so an imported memory holds what a memory holds after a checkpoint was
        // restored: a cut is only right while the windowed layers still have the window before
        // it, and pp_session_rewind_to refuses any other, as it does there.
        const int32_t n_long = 2 * n_window + 88;
        talk w = open(gguf, 4 * k_open), c = open(gguf, 4 * k_open);
        std::vector<llama_token> text;
        for (int i = 0; (int) text.size() < n_long; i++) {
            text.push_back(prompt[(size_t) i % prompt.size()]);
        }
        w.evaluate(text);
        c.evaluate(text);
        bool cut_before = false;
        const exported out = export_image(w, {}, piece_for((size_t) pp_session_memory_state_bytes(w.s, nullptr, 0), 16));
        w.clear();
        const imported in = import_image(w, out.to.image, out.to.buffer.size());
        // In the control, which holds every key, the same cut is made.
        const bool control_cut = pp_session_rewind_to(c.s, n_long / 2, &cut_before) == PP_STATUS_OK && cut_before;
        bool far = true, near = false;
        const int32_t far_status = pp_session_rewind_to(w.s, n_long / 2, &far);
        const int32_t count_after_far = pp_session_memory_token_count(w.s);
        const int32_t near_status = pp_session_rewind_to(w.s, n_long - 1, &near);
        const int32_t count_after_near = pp_session_memory_token_count(w.s);
        // The control again with the whole text, to compare the token after the near cut.
        c.clear();
        c.evaluate(text);
        std::vector<float> here, there = c.scores();
        if (near) {
            w.memory.resize((size_t) n_long - 1);
            w.evaluate({text.back()});
            here = w.scores();
        }
        r.check(out.status == PP_STATUS_OK && in.status == PP_STATUS_OK && control_cut && far_status == PP_STATUS_OK && !far && count_after_far == n_long &&
                near_status == PP_STATUS_OK && near && count_after_near == n_long - 1 && apart(here, there) <= k_close,
                "windowed layers (a window of %d): a memory of %d tokens imported (%d); a cut to %d tokens, which a memory that was never written out allows, is refused "
                "with the memory untouched (removed: %s, %d tokens), a cut of the last token is made (removed: %s), and the prediction after that token read again is "
                "within 5e-3 of the control's (largest difference %.3g; bit for bit: %s)",
                n_window, n_long, in.status, n_long / 2, far ? "YES" : "no", count_after_far, near ? "yes" : "NO", apart(here, there), same_bits(here, there) ? "yes" : "no");
    }

    return r.finish();
}
