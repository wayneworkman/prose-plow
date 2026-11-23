// What of a conversation can still be read in the process after pp_session_clear_memory, which
// is what the app calls when it locks, and after pp_session_close.
//
//   test_residue --gguf FILE
//
// The program tokenises and evaluates a text, samples a reply, forgets, and after each step
// reads every writable private mapping of its own process (the heap, the stacks, anonymous
// memory) through /proc/self/mem, looking for
//   - 16 consecutive bytes of the text, as it was given and as the SentencePiece front-end
//     rewrites it (every space as U+2581),
//   - 6 consecutive token ids of the prompt,
//   - 4 consecutive token ids of the reply.
// Its own copies of all three exist in clear only inside the calls: they are kept XOR-masked,
// compared masked, and every clear buffer is overwritten before a scan. The first scan, before
// anything was tokenised, must therefore find nothing, and the scan of the live conversation
// must find something: the two together show that a later "nothing" means nothing is there.
//
// What it requires and what it only reports follows what the session promises (see
// pp_session_clear_memory in the header):
//   - required: no token ids of the reply after forgetting. The only place that holds them in
//     order is the session's record of the tokens in memory, from which its penalties are
//     computed, and the session overwrites it.
//   - reported, not required: the text and the prompt's token ids. llama.cpp copies both into
//     allocations of its own and frees them as they are, so what a scan finds depends on what
//     the allocator did with those blocks since. The numbers are the measurement to quote; a
//     session that claimed them to be zero would have to make this program require it.
//
// A model can repeat its prompt, and Qwen3.5 does with this text: ten of its twelve reply
// tokens are a stretch of the prompt. Windows of the reply that also occur in the prompt are
// therefore not counted as the reply's: finding one says where the prompt is, which is the
// second point, and nothing about that record. (This was learnt on a phone, where such windows
// were found after forgetting, in the copy llama.cpp makes of each micro-batch's tokens.)
//
// Where a process cannot read itself through /proc/self/mem (anything but Linux) the program
// reports that it measured nothing, and likewise in a build with a sanitizer, whose shadow
// memory makes the writable address space terabytes and whose allocator keeps freed blocks back
// on purpose. Exit codes as in test_support.h.

#include "proseplow_session.h"
#include "support/test_support.h"

#include <algorithm>
#include <cinttypes>
#include <cstring>
#include <unordered_map>

#include <fcntl.h>
#include <unistd.h>

namespace {

using pp_test::report;

constexpr unsigned char k_mask = 0x5A;
constexpr uint32_t k_mask32 = 0x5A5A5A5Au;
constexpr size_t k_text_window = 16;
constexpr size_t k_prompt_window = 6;
constexpr size_t k_reply_window = 4;

// A string literal stored masked, so that the program's own image never holds it in clear.
template <size_t N> struct masked_literal {
    unsigned char data[N];
    constexpr masked_literal(const char (&text)[N]) : data{} {
        for (size_t i = 0; i < N; i++) {
            data[i] = (unsigned char) ((unsigned char) text[i] ^ k_mask);
        }
    }
};

// Ordinary prose with a few things nobody would want left behind. Invented for this test.
constexpr masked_literal k_secret(
    "My bank PIN is 4471 and the safe combination is 28-51-93. Meet Zofia at the old quarry at midnight, "
    "bring the ledger from the blue cabinet, and tell nobody about the diagnosis I got on Tuesday.");
constexpr size_t k_secret_len = sizeof(k_secret.data) - 1;

// Overwrites memory in a way the compiler may not remove although the buffer is freed next.
void wipe(void * data, size_t size) {
    std::memset(data, 0, size);
    __asm__ __volatile__("" : : "r"(data) : "memory");
}

struct found {
    int text = 0;       // windows of the text, in either form
    int prompt_ids = 0; // windows of the prompt's token ids
    int reply_ids = 0;  // windows of the reply's token ids
};

// The patterns, all masked. A window is looked up through its first byte or id.
struct patterns {
    std::vector<unsigned char> text;       // as given
    std::vector<unsigned char> normalised; // every space as U+2581
    std::vector<uint32_t> prompt;
    std::vector<uint32_t> reply;

    std::vector<uint16_t> text_starts[256];
    std::vector<uint16_t> normalised_starts[256];
    std::unordered_multimap<uint32_t, size_t> prompt_starts;
    std::unordered_multimap<uint32_t, size_t> reply_starts;
    // How many windows of the reply are the reply's alone, and how many also occur in the
    // prompt and are left out of reply_starts for that reason.
    size_t reply_windows_own = 0;
    size_t reply_windows_shared = 0;

    void index() {
        for (int b = 0; b < 256; b++) {
            text_starts[b].clear();
            normalised_starts[b].clear();
        }
        for (size_t i = 0; i + k_text_window <= text.size(); i++) {
            text_starts[text[i]].push_back((uint16_t) i);
        }
        for (size_t i = 0; i + k_text_window <= normalised.size(); i++) {
            normalised_starts[normalised[i]].push_back((uint16_t) i);
        }
        prompt_starts.clear();
        for (size_t i = 0; i + k_prompt_window <= prompt.size(); i++) {
            prompt_starts.emplace(prompt[i], i);
        }
        reply_starts.clear();
        reply_windows_own = 0;
        reply_windows_shared = 0;
        for (size_t i = 0; i + k_reply_window <= reply.size(); i++) {
            bool in_prompt = false;
            for (size_t j = 0; j + k_reply_window <= prompt.size() && !in_prompt; j++) {
                in_prompt = std::equal(reply.begin() + (ptrdiff_t) i, reply.begin() + (ptrdiff_t) (i + k_reply_window), prompt.begin() + (ptrdiff_t) j);
            }
            if (in_prompt) {
                reply_windows_shared++;
            } else {
                reply_starts.emplace(reply[i], i);
                reply_windows_own++;
            }
        }
    }
};

bool bytes_match(const unsigned char * at, const std::vector<unsigned char> & masked, size_t start) {
    for (size_t i = 0; i < k_text_window; i++) {
        if ((unsigned char) (at[i] ^ k_mask) != masked[start + i]) {
            return false;
        }
    }
    return true;
}

bool ids_match(const unsigned char * at, const std::vector<uint32_t> & masked, size_t start, size_t n) {
    for (size_t i = 0; i < n; i++) {
        uint32_t id;
        std::memcpy(&id, at + 4 * i, 4);
        if ((id ^ k_mask32) != masked[start + i]) {
            return false;
        }
    }
    return true;
}

// The scan buffer, allocated once: a scan must not move the heap it is looking at.
std::vector<unsigned char> g_block;
constexpr size_t k_block = (size_t) 1 << 20;
constexpr size_t k_overlap = 64; // more than the longest window, so none is cut by a block's end

void scan_range(int mem, uintptr_t begin, uintptr_t end, const patterns & p, found & hits) {
    const uintptr_t own_begin = (uintptr_t) g_block.data(), own_end = own_begin + g_block.size();
    for (uintptr_t at = begin; at < end; at += k_block) {
        const size_t want = (size_t) std::min<uintptr_t>(k_block + k_overlap, end - at);
        const ssize_t got = pread(mem, g_block.data(), want, (off_t) at);
        if (got <= 0) {
            continue; // a page that cannot be read holds nothing readable
        }
        const unsigned char * bytes = g_block.data();
        for (ssize_t i = 0; i + (ssize_t) (4 * k_prompt_window) <= got && i < (ssize_t) k_block; i++) {
            const uintptr_t address = at + (uintptr_t) i;
            if (address >= own_begin && address < own_end) {
                continue; // the scan buffer itself, which holds what was just read
            }
            const unsigned char first = (unsigned char) (bytes[i] ^ k_mask);
            bool is_text = false;
            for (const uint16_t start : p.text_starts[first]) {
                is_text = is_text || bytes_match(bytes + i, p.text, start);
            }
            for (const uint16_t start : p.normalised_starts[first]) {
                is_text = is_text || bytes_match(bytes + i, p.normalised, start);
            }
            hits.text += is_text ? 1 : 0;
            if ((address & 3) != 0) {
                continue;
            }
            uint32_t id;
            std::memcpy(&id, bytes + i, 4);
            id ^= k_mask32;
            const auto prompt = p.prompt_starts.equal_range(id);
            for (auto it = prompt.first; it != prompt.second; ++it) {
                if (ids_match(bytes + i, p.prompt, it->second, k_prompt_window)) {
                    hits.prompt_ids++;
                    break;
                }
            }
            const auto reply = p.reply_starts.equal_range(id);
            for (auto it = reply.first; it != reply.second; ++it) {
                if (ids_match(bytes + i, p.reply, it->second, k_reply_window)) {
                    hits.reply_ids++;
                    break;
                }
            }
        }
    }
}

// More writable address space than this is not a process as it runs on a phone or a desktop but
// one with a sanitizer's shadow memory mapped, which cannot be read through in any useful time.
constexpr uint64_t k_scan_limit = (uint64_t) 16 << 30;

// Every writable private mapping of the process. False when the process cannot read itself, or
// is too large to be read; why says which.
bool scan(const patterns & p, found & hits, const char ** why = nullptr) {
    static const char * ignored = nullptr;
    const char *& reason = why != nullptr ? *why : ignored;
    reason = "this process cannot read its own memory through /proc/self/mem";
    hits = found();
    std::FILE * maps = std::fopen("/proc/self/maps", "r");
    const int mem = open("/proc/self/mem", O_RDONLY);
    if (maps == nullptr || mem < 0) {
        if (maps != nullptr) {
            std::fclose(maps);
        }
        if (mem >= 0) {
            close(mem);
        }
        return false;
    }
    // Read the list first: scanning while listing would list the list's own buffer growing.
    std::vector<std::pair<uintptr_t, uintptr_t>> ranges;
    ranges.reserve(4096);
    char line[1024];
    while (std::fgets(line, sizeof(line), maps) != nullptr) {
        unsigned long long begin = 0, end = 0;
        char permissions[8] = "";
        char name[512] = "";
        if (std::sscanf(line, "%llx-%llx %7s %*s %*s %*s %511[^\n]", &begin, &end, permissions, name) < 3) {
            continue;
        }
        // [vvar] and [vsyscall] belong to the kernel and cannot be read this way.
        if (permissions[0] == 'r' && permissions[1] == 'w' && permissions[3] == 'p' && std::strstr(name, "[v") == nullptr) {
            ranges.emplace_back((uintptr_t) begin, (uintptr_t) end);
        }
    }
    std::fclose(maps);
    uint64_t total = 0;
    for (const auto & range : ranges) {
        total += (uint64_t) (range.second - range.first);
    }
    if (total > k_scan_limit) {
        close(mem);
        reason = "the writable address space of this process is too large to search (a build with a sanitizer)";
        return false;
    }
    for (const auto & range : ranges) {
        scan_range(mem, range.first, range.second, p, hits);
    }
    close(mem);
    wipe(g_block.data(), g_block.size());
    wipe(line, sizeof(line));
    return true;
}

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
    report r;
    if (pp_runtime_init(nullptr, 0) != PP_STATUS_OK) {
        pp_test::die(std::string("pp_runtime_init failed: ") + pp_runtime_description());
    }
    pp_session_params params = {};
    params.n_ctx     = 512;
    params.context_budget_bytes = (int64_t) 2 << 30; // a limit that is stated, not memory that is taken
    params.n_batch   = 64;
    params.n_threads = pp_test::n_threads();
    params.load_mode = PP_LOAD_MODE_MMAP;
    char error[256];
    pp_session * s = nullptr;
    if (pp_session_open_path(gguf.c_str(), &params, &s, error, sizeof(error)) != PP_STATUS_OK) {
        pp_test::die(std::string("cannot open the model: ") + error);
    }

    // Everything the scans need is allocated now, so that no scan changes the heap it reads.
    g_block.resize(k_block + k_overlap);
    patterns p;
    p.text.assign(k_secret.data, k_secret.data + k_secret_len);
    for (size_t i = 0; i < k_secret_len; i++) {
        if (k_secret.data[i] == (unsigned char) (' ' ^ k_mask)) {
            for (const unsigned char c : {0xE2, 0x96, 0x81}) {
                p.normalised.push_back((unsigned char) (c ^ k_mask));
            }
        } else {
            p.normalised.push_back(k_secret.data[i]);
        }
    }
    p.prompt.reserve(512);
    p.reply.reserve(64);
    p.index();

    found before;
    const char * why = nullptr;
    if (!scan(p, before, &why)) {
        r.note("%s: nothing was measured", why);
        pp_session_close(s);
        return r.finish();
    }
    r.check(before.text == 0 && before.prompt_ids == 0 && before.reply_ids == 0, "before the text is used the process holds no window of it: %d found", before.text);

    // --- The text, in clear only inside this block ---
    int32_t n_tokens = 0;
    {
        std::vector<llama_token> tokens(512);
        std::string clear(k_secret_len, '\0');
        for (size_t i = 0; i < k_secret_len; i++) {
            clear[i] = (char) (k_secret.data[i] ^ k_mask);
        }
        n_tokens = pp_session_tokenize(s, clear.data(), (int32_t) clear.size(), false, tokens.data(), (int32_t) tokens.size());
        wipe(clear.data(), clear.size());
        for (int32_t i = 0; i < n_tokens; i++) {
            p.prompt.push_back((uint32_t) tokens[(size_t) i] ^ k_mask32);
        }
        wipe(tokens.data(), tokens.size() * sizeof(llama_token));
    }
    if (n_tokens < 24) {
        pp_test::die("the text could not be tokenised");
    }
    p.index();
    found after_tokenize;
    scan(p, after_tokenize);

    // --- Evaluate it, sample a reply with a penalty (the session keeps a record of the tokens in
    // memory for its penalties whether one is set or not; this makes it read the record too) ---
    pp_sampler_config config = {};
    config.top_p = 1.0f;
    config.repetition_penalty = 1.1f;
    config.penalty_window = 256;
    config.reply_start = PP_REPLY_START_AT_SAMPLING;
    const pp_evaluation_observer observer = {[](void *) { return false; }, [](int32_t, void *) {}, nullptr};
    int32_t in_memory = 0;
    int32_t status = pp_session_configure_sampler(s, &config);
    {
        std::vector<llama_token> clear((size_t) n_tokens);
        for (int32_t i = 0; i < n_tokens; i++) {
            clear[(size_t) i] = (llama_token) (p.prompt[(size_t) i] ^ k_mask32);
        }
        status = status == PP_STATUS_OK ? pp_session_evaluate(s, clear.data(), n_tokens, 32, &observer, &in_memory) : status;
        wipe(clear.data(), clear.size() * sizeof(llama_token));
    }
    const int32_t checkpoint = pp_session_save_checkpoint(s);
    for (int i = 0; i < 12 && status == PP_STATUS_OK; i++) {
        llama_token token = LLAMA_TOKEN_NULL;
        status = pp_session_sample_next(s, &token);
        if (status == PP_STATUS_OK) {
            p.reply.push_back((uint32_t) token ^ k_mask32);
            status = pp_session_evaluate(s, &token, 1, 1, &observer, &in_memory);
        }
        token = 0;
    }
    if (status != PP_STATUS_OK || checkpoint < 0) {
        pp_test::die(std::string("the conversation could not be evaluated: ") + pp_session_last_error(s));
    }
    p.index();
    found live;
    scan(p, live);
    // A reply that is the prompt over again has no window of its own to look for.
    r.check(live.prompt_ids > 0 && (live.reply_ids > 0 || p.reply_windows_own == 0),
            "while the conversation is live the scan finds it: %d window(s) of the text, %d of the prompt's %d token ids, %d of the reply's %zu", live.text,
            live.prompt_ids, n_tokens, live.reply_ids, p.reply.size());
    r.note("of the reply's %zu windows, %zu also occur in the prompt and are counted with the prompt; the other %zu are looked for as the reply's",
           p.reply_windows_own + p.reply_windows_shared, p.reply_windows_shared, p.reply_windows_own);
    r.note("after pp_session_tokenize alone, before anything was evaluated: %d window(s) of the text", after_tokenize.text);

    // --- Forget ---
    const int32_t cleared = pp_session_clear_memory(s);
    found after_clear;
    scan(p, after_clear);
    r.check(cleared == PP_STATUS_OK && after_clear.reply_ids == 0, "after pp_session_clear_memory: no window of the reply's token ids (%d found)", after_clear.reply_ids);
    r.note("after pp_session_clear_memory: %d window(s) of the text and %d of the prompt's token ids are still readable, in blocks that llama.cpp freed without overwriting them",
           after_clear.text, after_clear.prompt_ids);

    // --- The same conversation again, and a change of the context's size ---
    // First with the memory carried over into the larger context, which keeps the conversation
    // and copies its state through one block; then back with a budget one byte short of what
    // carrying needs, which forgets it. What is required after that is what is required after
    // a clear: the freed contexts and the block the state went through have left no token id
    // of the reply behind.
    {
        std::vector<llama_token> clear((size_t) n_tokens);
        for (int32_t i = 0; i < n_tokens; i++) {
            clear[(size_t) i] = (llama_token) (p.prompt[(size_t) i] ^ k_mask32);
        }
        status = pp_session_evaluate(s, clear.data(), n_tokens, 32, &observer, &in_memory);
        wipe(clear.data(), clear.size() * sizeof(llama_token));
        // The reply one token at a time, as sampled tokens are evaluated: given in one piece it
        // would be a prompt, and llama.cpp's freed copies of a prompt's tokens are the part
        // that is reported and not required.
        for (size_t i = 0; i < p.reply.size() && status == PP_STATUS_OK; i++) {
            llama_token token = (llama_token) (p.reply[i] ^ k_mask32);
            status = pp_session_evaluate(s, &token, 1, 1, &observer, &in_memory);
            token = 0;
        }
    }
    const int32_t held = pp_session_memory_token_count(s);
    const int32_t n_small = pp_session_context_size(s), n_large = 2 * n_small;
    const int64_t generous = (int64_t) 2 << 30;
    const int32_t grown = pp_session_resize_context(s, n_large, generous);
    const int32_t carried = pp_session_memory_token_count(s);
    const int64_t peak = std::max(pp_session_context_bytes(s, n_small), pp_session_context_bytes(s, n_large)) + pp_session_carry_bytes(s);
    const int32_t shrunk = pp_session_resize_context(s, n_small, peak - 1);
    found after_resize;
    scan(p, after_resize);
    r.check(status == PP_STATUS_OK && grown == PP_STATUS_OK && carried == held && held > n_tokens && shrunk == PP_STATUS_OK && pp_session_memory_token_count(s) == 0 &&
            after_resize.reply_ids == 0,
            "the conversation again (%d tokens), carried into a context of %d (%d tokens there), then back to %d with a budget one byte short of carrying it: "
            "an empty memory and no window of the reply's token ids (%d found)", held, n_large, carried, n_small, after_resize.reply_ids);
    r.note("after that change of size: %d window(s) of the text and %d of the prompt's token ids are still readable", after_resize.text, after_resize.prompt_ids);

    pp_session_close(s);
    found after_close;
    scan(p, after_close);
    r.check(after_close.reply_ids == 0, "after pp_session_close: no window of the reply's token ids (%d found)", after_close.reply_ids);
    r.note("after pp_session_close: %d window(s) of the text and %d of the prompt's token ids are still readable", after_close.text, after_close.prompt_ids);
    return r.finish();
}
