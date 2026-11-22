// Plain-text ChatML: renders a conversation as the model's chat template does and tokenises it
// in one call.
//
// The template of these models is
//
//   {% for m in messages %}<|im_start|>{{ m['role'] }}\n{{ m['content'] | trim }}<|im_end|>\n{% endfor %}
//   {% if add_generation_prompt %}<|im_start|>assistant\n{% endif %}
//
// which is simple enough to write out here instead of carrying a template engine. The one
// subtle part is "trim": it is Python's str.strip(), whose idea of whitespace is a fixed list of
// code points that is neither ASCII whitespace nor what other languages' trim functions use.
//
// Everything else is llama_tokenize with special-token parsing off: the markers are ordinary
// text for these models, and so is anything in a message that looks like a special token.

#include "proseplow_native.h"

#include <algorithm>
#include <cstring>
#include <limits>
#include <string>
#include <string_view>
#include <vector>

namespace {

constexpr std::string_view k_im_start  = "<|im_start|>";
constexpr std::string_view k_im_end    = PP_CHATML_TEXT_END_MARKER;
constexpr std::string_view k_assistant = "assistant";

// Decodes the well-formed UTF-8 character at the start of s. Returns its length in bytes, or 0
// when there is none. Strict (no overlong forms, no surrogates, nothing above U+10FFFF): the
// reference tokenizer only ever sees well-formed text.
size_t utf8_decode(std::string_view s, char32_t & code_point) {
    const auto byte = [&](size_t i) { return (unsigned char) s[i]; };
    const auto cont = [&](size_t i) { return i < s.size() && (byte(i) & 0xC0) == 0x80; };
    if (s.empty()) {
        return 0;
    }
    const unsigned char b0 = byte(0);
    if (b0 < 0x80) {
        code_point = b0;
        return 1;
    }
    if (b0 >= 0xC2 && b0 <= 0xDF && cont(1)) {
        code_point = (char32_t) (((b0 & 0x1F) << 6) | (byte(1) & 0x3F));
        return 2;
    }
    if (b0 >= 0xE0 && b0 <= 0xEF && cont(1) && cont(2)) {
        if ((b0 == 0xE0 && byte(1) < 0xA0) || (b0 == 0xED && byte(1) > 0x9F)) {
            return 0;
        }
        code_point = (char32_t) (((b0 & 0x0F) << 12) | ((byte(1) & 0x3F) << 6) | (byte(2) & 0x3F));
        return 3;
    }
    if (b0 >= 0xF0 && b0 <= 0xF4 && cont(1) && cont(2) && cont(3)) {
        if ((b0 == 0xF0 && byte(1) < 0x90) || (b0 == 0xF4 && byte(1) > 0x8F)) {
            return 0;
        }
        code_point = (char32_t) (((b0 & 0x07) << 18) | ((byte(1) & 0x3F) << 12) | ((byte(2) & 0x3F) << 6) | (byte(3) & 0x3F));
        return 4;
    }
    return 0;
}

// The characters Python's str.strip() removes: exactly those for which str.isspace() is true.
// Not the same set as C's isspace (which lacks everything above U+0020) nor as Java's or
// Kotlin's trim (which keep U+00A0, for one).
bool is_python_space(char32_t c) {
    return (c >= 0x0009 && c <= 0x000D) || (c >= 0x001C && c <= 0x0020) || c == 0x0085 || c == 0x00A0 || c == 0x1680
        || (c >= 0x2000 && c <= 0x200A) || c == 0x2028 || c == 0x2029 || c == 0x202F || c == 0x205F || c == 0x3000;
}

// Checks that text is well-formed UTF-8 and finds the byte range left after stripping Python
// whitespace from both ends. Returns false for malformed text.
bool strip(std::string_view text, std::string_view & stripped) {
    size_t begin = 0;        // first byte that is not leading whitespace
    size_t end = 0;          // one past the last character that is not whitespace
    bool leading = true;
    for (size_t pos = 0; pos < text.size();) {
        char32_t c = 0;
        const size_t n = utf8_decode(text.substr(pos), c);
        if (n == 0) {
            return false;
        }
        if (!is_python_space(c)) {
            if (leading) {
                begin = pos;
                leading = false;
            }
            end = pos + n;
        }
        pos += n;
    }
    stripped = leading ? std::string_view() : text.substr(begin, end - begin);
    return true;
}

bool valid_utf8(std::string_view text) {
    std::string_view ignored;
    return strip(text, ignored);
}

// All of text as ordinary characters. False when llama.cpp cannot tokenise it.
bool tokenize(const llama_vocab * vocab, std::string_view text, bool parse_special, std::vector<llama_token> & out) {
    out.clear();
    if (text.empty()) {
        return true;
    }
    if (text.size() > (size_t) std::numeric_limits<int32_t>::max()) {
        return false;
    }
    // A byte-level BPE never produces more tokens than there are bytes.
    out.resize(text.size());
    int32_t n = llama_tokenize(vocab, text.data(), (int32_t) text.size(), out.data(), (int32_t) out.size(), false, parse_special);
    if (n < 0 && n != std::numeric_limits<int32_t>::min()) {
        out.resize((size_t) -(int64_t) n);
        n = llama_tokenize(vocab, text.data(), (int32_t) text.size(), out.data(), (int32_t) out.size(), false, parse_special);
    }
    if (n < 0) {
        out.clear();
        return false;
    }
    out.resize((size_t) n);
    return true;
}

bool meta_is(const llama_model * model, const std::string & key, std::string_view expected) {
    char buf[64];
    const int32_t n = llama_model_meta_val_str(model, key.c_str(), buf, sizeof(buf));
    return n >= 0 && (size_t) n < sizeof(buf) && std::string_view(buf, (size_t) n) == expected;
}

bool supported(const llama_model * model) {
    char arch[64];
    const int32_t n_arch = llama_model_meta_val_str(model, "general.architecture", arch, sizeof(arch));
    if (n_arch <= 0 || (size_t) n_arch >= sizeof(arch) || !meta_is(model, std::string(arch, (size_t) n_arch) + ".chat.format", "chatml-text")) {
        return false;
    }
    const llama_vocab * vocab = llama_model_get_vocab(model);
    if (llama_vocab_type(vocab) != LLAMA_VOCAB_TYPE_BPE || llama_vocab_eos(vocab) == LLAMA_TOKEN_NULL) {
        return false;
    }
    // A model with marker tokens was trained on those tokens, not on the marker's characters:
    // rendering its conversations as text would feed it something it never saw.
    std::vector<llama_token> ids;
    for (const std::string_view marker : {k_im_start, k_im_end}) {
        if (!tokenize(vocab, marker, true, ids) || ids.size() < 2) {
            return false;
        }
    }
    return true;
}

} // namespace

bool pp_chatml_text_supported(const struct llama_model * model) {
    if (model == nullptr) {
        return false;
    }
    try {
        return supported(model);
    } catch (...) {
        return false;
    }
}

int32_t pp_chatml_text_tokenize(
        const struct llama_model * model,
        const struct pp_chat_message * messages,
        int32_t n_messages,
        bool add_generation_prompt,
        llama_token * tokens,
        int32_t n_tokens_max,
        int32_t * message_ends) {
    if (model == nullptr || n_messages < 0 || (n_messages > 0 && messages == nullptr) || n_tokens_max < 0 || (n_tokens_max > 0 && tokens == nullptr)) {
        return PP_ERROR;
    }
    try {
        if (!supported(model)) {
            return PP_ERROR;
        }
        const llama_vocab * vocab = llama_model_get_vocab(model);

        std::string text;
        std::vector<size_t> end_offsets; // bytes of text that hold the messages 0..i
        for (int32_t i = 0; i < n_messages; i++) {
            const pp_chat_message & m = messages[i];
            if (m.role_len < 0 || m.content_len < 0 || (m.role_len > 0 && m.role == nullptr) || (m.content_len > 0 && m.content == nullptr)) {
                return PP_ERROR;
            }
            const std::string_view role(m.role_len > 0 ? m.role : "", (size_t) m.role_len);
            std::string_view content(m.content_len > 0 ? m.content : "", (size_t) m.content_len);
            if (!valid_utf8(role) || !strip(content, content)) {
                return PP_ERROR;
            }
            text.append(k_im_start).append(role).append("\n").append(content).append(k_im_end).append("\n");
            end_offsets.push_back(text.size());
        }
        if (add_generation_prompt) {
            text.append(k_im_start).append(k_assistant).append("\n");
        }

        std::vector<llama_token> ids;
        if (!tokenize(vocab, text, false, ids) || ids.size() > (size_t) std::numeric_limits<int32_t>::max()) {
            return PP_ERROR;
        }
        // One conversation is one document for the model, which is exact only while the
        // end-of-text token is never fed. Without special-token parsing no text can produce it;
        // this makes sure of it instead of relying on that.
        const llama_token eos = llama_vocab_eos(vocab);
        if (std::find(ids.begin(), ids.end(), eos) != ids.end()) {
            return PP_ERROR;
        }
        if (ids.size() > (size_t) n_tokens_max) {
            return -(int32_t) ids.size();
        }

        if (message_ends != nullptr) {
            // The newline after an end marker is a token of its own for the tokenizers of this
            // format, so the tokens of the first messages are a prefix of the tokens of the
            // whole conversation. That is verified per message here, not assumed.
            std::vector<llama_token> prefix;
            for (int32_t i = 0; i < n_messages; i++) {
                const bool ok = tokenize(vocab, std::string_view(text).substr(0, end_offsets[(size_t) i]), false, prefix)
                        && prefix.size() <= ids.size() && std::equal(prefix.begin(), prefix.end(), ids.begin());
                message_ends[i] = ok ? (int32_t) prefix.size() : -1;
            }
        }
        std::copy(ids.begin(), ids.end(), tokens);
        return (int32_t) ids.size();
    } catch (...) {
        return PP_ERROR;
    }
}
