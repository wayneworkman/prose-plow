// Tokenizer front-end and chat encoder for peacebell models with the SentencePiece tokenizer.
//
// The reference tokenizer is a SentencePiece BPE model with the "identity" normaliser, a dummy
// prefix, removal of extra whitespace and escaped whitespace, plus user-defined symbols that are
// matched anywhere in the text, longest first at the leftmost position, and never merged with
// their neighbours. llama.cpp's "llama" vocabulary reproduces the merges and nothing else of
// that. This file does the rest:
//
//   1. split the text into user-defined symbols and single characters;
//   2. normalise: drop leading spaces, start with one dummy prefix, turn each run of spaces into
//      one U+2581, drop trailing U+2581 after the last symbol;
//   3. give every stretch between two symbols to llama_tokenize on its own, and put the symbols'
//      ids in between.
//
// Step 3 relies on the model file: add_space_prefix is off (the dummy prefix is ours), and the
// two chat markers are typed CONTROL, so llama_tokenize with parse_special = false never
// produces them. The constructor checks all of that and refuses a file it cannot serve exactly.
//
// The text is a conversation, so the copies this file makes of it (the normalised stretches and
// the token ids) are overwritten before they are freed, and neither is allowed to grow by
// itself: a string or vector that grows frees its old storage as it is. That covers this file's
// copies only; llama_tokenize makes its own of every stretch it is handed and frees them as
// they are.

#include "proseplow_native.h"

#include <algorithm>
#include <array>
#include <cstring>
#include <limits>
#include <memory>
#include <string>
#include <string_view>
#include <vector>

namespace {

constexpr std::string_view k_escaped_space = "\xE2\x96\x81"; // U+2581, SentencePiece's visible space
constexpr std::string_view k_im_start      = "<|im_start|>";
constexpr std::string_view k_im_end        = "<|im_end|>";
constexpr std::string_view k_assistant     = "assistant";

struct symbol {
    std::string text;
    llama_token id;
};

// User-defined symbols, grouped by first byte and sorted longest first within a group, so the
// first symbol that matches at a position is the longest one: SentencePiece's rule.
class symbol_table {
  public:
    void add(std::string_view text, llama_token id) {
        by_first_byte[(unsigned char) text[0]].push_back({std::string(text), id});
    }

    void finish() {
        for (auto & group : by_first_byte) {
            std::stable_sort(group.begin(), group.end(), [](const symbol & a, const symbol & b) {
                return a.text.size() > b.text.size();
            });
        }
    }

    const symbol * match(std::string_view rest) const {
        for (const symbol & s : by_first_byte[(unsigned char) rest[0]]) {
            if (rest.size() >= s.text.size() && std::memcmp(rest.data(), s.text.data(), s.text.size()) == 0) {
                return &s;
            }
        }
        return nullptr;
    }

  private:
    std::array<std::vector<symbol>, 256> by_first_byte;
};

// Length in bytes of the well-formed UTF-8 character at the start of s, or 0 when there is none.
// Strict on purpose (no overlong forms, no surrogates, nothing above U+10FFFF): the reference
// tokenizer only ever sees well-formed text, so anything else has no defined tokenisation.
size_t utf8_char_len(std::string_view s) {
    const auto byte = [&](size_t i) { return (unsigned char) s[i]; };
    const auto cont = [&](size_t i) { return i < s.size() && (byte(i) & 0xC0) == 0x80; };
    if (s.empty()) {
        return 0;
    }
    const unsigned char b0 = byte(0);
    if (b0 < 0x80) {
        return 1;
    }
    if (b0 >= 0xC2 && b0 <= 0xDF) {
        return cont(1) ? 2 : 0;
    }
    if (b0 >= 0xE0 && b0 <= 0xEF) {
        if (!cont(1) || !cont(2)) {
            return 0;
        }
        if ((b0 == 0xE0 && byte(1) < 0xA0) || (b0 == 0xED && byte(1) > 0x9F)) {
            return 0;
        }
        return 3;
    }
    if (b0 >= 0xF0 && b0 <= 0xF4) {
        if (!cont(1) || !cont(2) || !cont(3)) {
            return 0;
        }
        if ((b0 == 0xF0 && byte(1) < 0x90) || (b0 == 0xF4 && byte(1) > 0x8F)) {
            return 0;
        }
        return 4;
    }
    return 0;
}

// Overwrites memory in a way the compiler may not remove although the buffer is freed next.
void wipe(void * data, size_t size) {
    if (data == nullptr || size == 0) {
        return;
    }
    std::memset(data, 0, size);
    __asm__ __volatile__("" : : "r"(data) : "memory");
}

// Overwrites the whole storage of a string or a vector, also the part beyond its present size,
// which holds what it held before it was last shortened.
template <typename Container> void wipe_storage(Container & c) {
    c.resize(c.capacity()); // within the capacity: nothing is allocated, so nothing can throw
    wipe(c.data(), c.size() * sizeof(*c.data()));
    c.clear();
}

// Overwrites a string or a vector when the scope ends, whichever way it ends.
template <typename Container> class wiped_at_exit {
  public:
    explicit wiped_at_exit(Container & container) : c(container) {}
    ~wiped_at_exit() { wipe_storage(c); }
    wiped_at_exit(const wiped_at_exit &) = delete;
    wiped_at_exit & operator=(const wiped_at_exit &) = delete;

  private:
    Container & c;
};

// Makes room for n_more tokens. When that takes new storage, the old one is overwritten before
// it is given up, which a vector growing by itself would not do.
void reserve_wiped(std::vector<llama_token> & ids, size_t n_more) {
    if (ids.capacity() - ids.size() >= n_more) {
        return;
    }
    std::vector<llama_token> grown;
    grown.reserve(std::max(ids.size() + n_more, 2 * ids.capacity()));
    grown.assign(ids.begin(), ids.end());
    wipe_storage(ids);
    ids.swap(grown);
}

bool ends_with(const std::string & s, std::string_view suffix) {
    return s.size() >= suffix.size() && std::memcmp(s.data() + s.size() - suffix.size(), suffix.data(), suffix.size()) == 0;
}

bool meta_value(const llama_model * model, const std::string & key, std::string & value) {
    char buf[128];
    const int32_t n = llama_model_meta_val_str(model, key.c_str(), buf, sizeof(buf));
    if (n < 0 || (size_t) n >= sizeof(buf)) {
        return false;
    }
    value.assign(buf, (size_t) n);
    return true;
}

bool meta_is(const llama_model * model, const std::string & key, std::string_view expected) {
    std::string value;
    return meta_value(model, key, value) && value == expected;
}

bool meta_token(const llama_model * model, const std::string & key, int32_t n_vocab, llama_token & token) {
    std::string value;
    if (!meta_value(model, key, value) || value.empty() || value.size() > 9 ||
        !std::all_of(value.begin(), value.end(), [](char c) { return c >= '0' && c <= '9'; })) {
        return false;
    }
    token = (llama_token) std::stol(value);
    return token < n_vocab;
}

} // namespace

struct pp_peacebell_tokenizer {
    const llama_vocab * vocab = nullptr;
    llama_token im_start = LLAMA_TOKEN_NULL;
    llama_token im_end   = LLAMA_TOKEN_NULL;
    symbol_table symbols_safe;  // the user-defined symbols of the vocabulary
    symbol_table symbols_exact; // the same plus the two chat markers

    // Appends the tokens of one normalised stretch of ordinary text.
    bool tokenize_run(const std::string & run, std::vector<llama_token> & out) const {
        if (run.empty()) {
            return true;
        }
        if (run.size() > (size_t) std::numeric_limits<int32_t>::max()) {
            return false;
        }
        // A token covers at least one byte, so the run's length bounds the token count.
        const size_t base = out.size();
        const int32_t capacity = (int32_t) run.size();
        reserve_wiped(out, run.size());
        out.resize(base + run.size());
        const int32_t n = llama_tokenize(vocab, run.data(), capacity, out.data() + base, capacity, false, false);
        if (n < 0) {
            out.resize(base);
            return false;
        }
        out.resize(base + (size_t) n);
        return true;
    }

    // Appends the tokens of text. Returns false for text that is not well-formed UTF-8.
    bool encode(std::string_view text, pp_marker_mode mode, std::vector<llama_token> & out) const {
        const symbol_table & symbols = mode == PP_MARKER_MODE_EXACT ? symbols_exact : symbols_safe;

        size_t i = 0;
        // Leading spaces vanish. No symbol starts with a space, so this cannot eat one.
        while (i < text.size() && text[i] == ' ') {
            i++;
        }
        if (i == text.size()) {
            return true;
        }

        // All the storage either of the two will need, taken once: the normalised text is the
        // prefix plus the text with every space as three bytes at most, and each token, a
        // symbol's or a merged one, stands for at least one byte of that.
        const size_t n_normalised = k_escaped_space.size() + text.size() + 2 * (size_t) std::count(text.begin(), text.end(), ' ');
        std::string run;
        const wiped_at_exit<std::string> wiped_run(run);
        run.reserve(n_normalised);
        reserve_wiped(out, n_normalised);

        run.assign(k_escaped_space);      // the one dummy prefix of the whole text
        bool prev_space = true;           // a space is dropped when the output already ends in one
        while (i < text.size()) {
            const std::string_view rest = text.substr(i);
            if (const symbol * s = symbols.match(rest)) {
                // A symbol is frozen: what precedes it is merged on its own.
                if (!tokenize_run(run, out)) {
                    return false;
                }
                run.clear();
                out.push_back(s->id);
                prev_space = false;
                i += s->text.size();
            } else if (rest[0] == ' ') {
                if (!prev_space) {
                    run.append(k_escaped_space);
                    prev_space = true;
                }
                i++;
            } else {
                const size_t len = utf8_char_len(rest);
                if (len == 0) {
                    return false;
                }
                // A typed U+2581 lands here as an ordinary character: it does not count as a space
                // for collapsing, but it is removed with the trailing ones below, as SentencePiece does.
                run.append(rest.substr(0, len));
                prev_space = false;
                i += len;
            }
        }

        // Trailing spaces vanish too, back to the last symbol and no further. That can consume the
        // dummy prefix: text made only of U+2581 has no tokens.
        while (ends_with(run, k_escaped_space)) {
            run.resize(run.size() - k_escaped_space.size());
        }
        return tokenize_run(run, out);
    }
};

namespace {

// The contract of the token-producing functions: all or nothing, and the size needed when the
// buffer is too small.
int32_t deliver(const std::vector<llama_token> & ids, llama_token * tokens, int32_t n_tokens_max) {
    if (ids.size() > (size_t) std::numeric_limits<int32_t>::max()) {
        return PP_ERROR;
    }
    const int32_t n = (int32_t) ids.size();
    if (n > n_tokens_max) {
        return -n;
    }
    std::copy(ids.begin(), ids.end(), tokens);
    return n;
}

bool valid_text(const char * text, int32_t len) {
    return len >= 0 && (text != nullptr || len == 0);
}

std::string_view view(const char * text, int32_t len) {
    return len == 0 ? std::string_view() : std::string_view(text, (size_t) len);
}

pp_peacebell_tokenizer * fail(const char ** error, const char * message) {
    if (error != nullptr) {
        *error = message;
    }
    return nullptr;
}

} // namespace

pp_peacebell_tokenizer * pp_peacebell_tokenizer_init(const llama_model * model, const char ** error) {
    try {
        if (error != nullptr) {
            *error = nullptr;
        }
        if (model == nullptr) {
            return fail(error, "no model");
        }

        std::string arch;
        if (!meta_value(model, "general.architecture", arch) || arch != "peacebell") {
            return fail(error, "not a peacebell model");
        }
        const std::string tok = arch + ".tokenizer.";
        if (!meta_is(model, tok + "kind", "sentencepiece-bpe") || !meta_is(model, "tokenizer.ggml.model", "llama")) {
            return fail(error, "the model does not use the SentencePiece BPE tokenizer");
        }
        // Exactly the normaliser configuration implemented above, as recorded by the converter.
        if (!meta_is(model, tok + "spm.normalizer.name", "identity") ||
            !meta_is(model, tok + "spm.normalizer.add_dummy_prefix", "true") ||
            !meta_is(model, tok + "spm.normalizer.remove_extra_whitespaces", "true") ||
            !meta_is(model, tok + "spm.normalizer.escape_whitespaces", "true") ||
            !meta_is(model, tok + "spm.normalizer.precompiled_charsmap_size", "0") ||
            !meta_is(model, tok + "spm.treat_whitespace_as_suffix", "false") ||
            !meta_is(model, tok + "spm.byte_fallback", "true")) {
            return fail(error, "the model's SentencePiece normaliser is not the one this front-end implements");
        }
        // The dummy prefix is added here, once per text; llama.cpp must not add its own per call.
        if (!meta_is(model, "tokenizer.ggml.add_space_prefix", "false")) {
            return fail(error, "the model file asks llama.cpp to add a space prefix");
        }

        auto t = std::make_unique<pp_peacebell_tokenizer>();
        t->vocab = llama_model_get_vocab(model);
        const int32_t n_vocab = llama_vocab_n_tokens(t->vocab);

        if (!meta_token(model, arch + ".chat.start_token_id", n_vocab, t->im_start) ||
            !meta_token(model, arch + ".chat.end_token_id", n_vocab, t->im_end) ||
            k_im_start != llama_vocab_get_text(t->vocab, t->im_start) ||
            k_im_end != llama_vocab_get_text(t->vocab, t->im_end)) {
            return fail(error, "the model file does not name the chat marker tokens");
        }
        // Safe mode rests on this: a marker that llama.cpp matched in plain text would let a
        // message close its own turn however this front-end was called.
        const auto is_control_only = [&](llama_token token) {
            const auto attr = llama_vocab_get_attr(t->vocab, token);
            return (attr & LLAMA_TOKEN_ATTR_CONTROL) != 0 && (attr & LLAMA_TOKEN_ATTR_USER_DEFINED) == 0;
        };
        if (!is_control_only(t->im_start) || !is_control_only(t->im_end)) {
            return fail(error, "the chat marker tokens are not control tokens");
        }

        size_t n_symbols = 0;
        for (llama_token id = 0; id < n_vocab; id++) {
            if ((llama_vocab_get_attr(t->vocab, id) & LLAMA_TOKEN_ATTR_USER_DEFINED) == 0) {
                continue;
            }
            const std::string_view text = llama_vocab_get_text(t->vocab, id);
            if (text.empty() || text.find(' ') != std::string_view::npos || text.find(k_escaped_space) != std::string_view::npos) {
                return fail(error, "a user-defined symbol is empty or contains a space");
            }
            t->symbols_safe.add(text, id);
            t->symbols_exact.add(text, id);
            n_symbols++;
        }
        t->symbols_exact.add(k_im_start, t->im_start);
        t->symbols_exact.add(k_im_end, t->im_end);
        t->symbols_safe.finish();
        t->symbols_exact.finish();

        // The converter counted SentencePiece's user-defined symbols, markers included. A different
        // count here means the token types were rewritten between the converter and this point.
        if (!meta_is(model, tok + "spm.user_defined_symbol_count", std::to_string(n_symbols + 2))) {
            return fail(error, "the user-defined symbols of the vocabulary do not match the model file's record");
        }

        return t.release();
    } catch (...) {
        return fail(error, "out of memory or internal error");
    }
}

void pp_peacebell_tokenizer_free(pp_peacebell_tokenizer * tokenizer) {
    delete tokenizer;
}

int32_t pp_peacebell_tokenize(
        const pp_peacebell_tokenizer * tokenizer,
        const char * text,
        int32_t text_len,
        llama_token * tokens,
        int32_t n_tokens_max,
        enum pp_marker_mode mode) {
    try {
        if (tokenizer == nullptr || !valid_text(text, text_len) || n_tokens_max < 0 || (tokens == nullptr && n_tokens_max > 0)) {
            return PP_ERROR;
        }
        std::vector<llama_token> ids;
        const wiped_at_exit<std::vector<llama_token>> wiped_ids(ids);
        if (!tokenizer->encode(view(text, text_len), mode, ids)) {
            return PP_ERROR;
        }
        return deliver(ids, tokens, n_tokens_max);
    } catch (...) {
        return PP_ERROR;
    }
}

int32_t pp_peacebell_chat_tokenize(
        const pp_peacebell_tokenizer * tokenizer,
        const pp_chat_message * messages,
        int32_t n_messages,
        bool add_generation_prompt,
        llama_token * tokens,
        int32_t n_tokens_max,
        enum pp_marker_mode mode) {
    try {
        if (tokenizer == nullptr || n_messages < 0 || (messages == nullptr && n_messages > 0) ||
            n_tokens_max < 0 || (tokens == nullptr && n_tokens_max > 0)) {
            return PP_ERROR;
        }
        std::vector<llama_token> ids;
        const wiped_at_exit<std::vector<llama_token>> wiped_ids(ids);
        std::string header;
        // The role line is one text of its own ("user\n"), encoded apart from the content: that is
        // how the training data was built, and one joined string would merge differently.
        const auto add_header = [&](std::string_view role) {
            header.assign(role);
            header.push_back('\n');
            reserve_wiped(ids, 1);
            ids.push_back(tokenizer->im_start);
            return tokenizer->encode(header, mode, ids);
        };
        for (int32_t i = 0; i < n_messages; i++) {
            const pp_chat_message & m = messages[i];
            if (!valid_text(m.role, m.role_len) || !valid_text(m.content, m.content_len)) {
                return PP_ERROR;
            }
            if (!add_header(view(m.role, m.role_len)) || !tokenizer->encode(view(m.content, m.content_len), mode, ids)) {
                return PP_ERROR;
            }
            reserve_wiped(ids, 1);
            ids.push_back(tokenizer->im_end);
        }
        if (add_generation_prompt && !add_header(k_assistant)) {
            return PP_ERROR;
        }
        return deliver(ids, tokens, n_tokens_max);
    } catch (...) {
        return PP_ERROR;
    }
}

llama_token pp_peacebell_chat_start_token(const pp_peacebell_tokenizer * tokenizer) {
    return tokenizer != nullptr ? tokenizer->im_start : LLAMA_TOKEN_NULL;
}

llama_token pp_peacebell_chat_end_token(const pp_peacebell_tokenizer * tokenizer) {
    return tokenizer != nullptr ? tokenizer->im_end : LLAMA_TOKEN_NULL;
}
