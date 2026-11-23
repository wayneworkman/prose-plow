// Token-exactness of llama.cpp's own tokenizer for a model whose reference tokenizer it is
// expected to reproduce without a front-end (Boris-2, Shard-2 and the byte-level BPE members of
// the peacebell family), and of the plain-text ChatML encoder of our library on top of it.
//
//   test_plain_tokenizer --gguf FILE --vectors FILE
//
// The vector file has the same format as the one for the peacebell front-end. Here "exact" is
// the reference with its special tokens recognised in the text, which llama_tokenize does with
// parse_special = true, and "safe" is the reference with them treated as plain characters,
// which is parse_special = false and what message content must go through.
//
// A vocabulary's control tokens (an end-of-text token; for Shard-2 also a padding token, a
// second marker and the deletion token, which its tokenizer file does not even contain) are
// not text. The test holds every one of them to that: typed as text its name stays plain
// characters, and turned back into text it contributes nothing.
//
// The conversations of the file are the reference's chat template rendered and tokenised in
// one call. A model without a chat template has none, and the checks on them do not run. pp_chatml_text_tokenize must reproduce the safe variant, which differs from the
// exact one only where a message contains the end-of-text literal.

#include "support/test_support.h"

#include "proseplow_native.h"

#include <algorithm>

namespace {

std::vector<llama_token> tokenize(const llama_vocab * vocab, const std::string & text, bool parse_special) {
    std::vector<llama_token> out(text.size() + 8);
    int32_t n = llama_tokenize(vocab, text.data(), (int32_t) text.size(), out.data(), (int32_t) out.size(), false, parse_special);
    if (n < 0) {
        out.resize((size_t) -n);
        n = llama_tokenize(vocab, text.data(), (int32_t) text.size(), out.data(), (int32_t) out.size(), false, parse_special);
    }
    out.resize(n < 0 ? 0 : (size_t) n);
    return out;
}

// The text a token contributes when a reply is shown (special = false) or when a prompt is printed with its markers.
std::string piece(const llama_vocab * vocab, llama_token token, bool special) {
    char buf[64];
    const int32_t n = llama_token_to_piece(vocab, token, buf, (int32_t) sizeof(buf), 0, special);
    return n > 0 ? std::string(buf, (size_t) n) : std::string();
}

struct chat {
    std::vector<std::string> roles, contents;
    bool add_generation_prompt = false;
    std::vector<llama_token> exact, safe;

    std::vector<pp_chat_message> messages() const {
        std::vector<pp_chat_message> out;
        for (size_t i = 0; i < roles.size(); i++) {
            out.push_back({roles[i].data(), (int32_t) roles[i].size(), contents[i].data(), (int32_t) contents[i].size()});
        }
        return out;
    }
};

// The encoder's answer for the first n_messages messages, or an empty optional-like pair on error.
bool chat_tokenize(const llama_model * model, const std::vector<pp_chat_message> & messages, size_t n_messages, bool add_generation_prompt,
                   std::vector<llama_token> & tokens, std::vector<int32_t> * ends) {
    const int32_t needed = pp_chatml_text_tokenize(model, messages.data(), (int32_t) n_messages, add_generation_prompt, nullptr, 0, nullptr);
    if (needed == PP_ERROR || needed > 0) {
        return false;
    }
    tokens.assign((size_t) -(int64_t) needed, LLAMA_TOKEN_NULL);
    if (ends != nullptr) {
        ends->assign(n_messages, -2);
    }
    const int32_t n = pp_chatml_text_tokenize(model, messages.data(), (int32_t) n_messages, add_generation_prompt,
                                              tokens.data(), (int32_t) tokens.size(), ends != nullptr ? ends->data() : nullptr);
    return n == -needed;
}

} // namespace

int main(int argc, char ** argv) {
    std::string gguf, vectors;
    for (int i = 1; i + 1 < argc; i += 2) {
        const std::string key = argv[i];
        if (key == "--gguf") {
            gguf = argv[i + 1];
        } else if (key == "--vectors") {
            vectors = argv[i + 1];
        }
    }
    if (gguf.empty() || vectors.empty()) {
        std::fprintf(stderr, "usage: %s --gguf FILE --vectors FILE\n", argv[0]);
        return 2;
    }

    pp_test::quiet_logs();
    llama_backend_init();
    pp_test::report r;
    {
        pp_test::model_ptr model = pp_test::load_model(gguf, true);
        const llama_vocab * vocab = llama_model_get_vocab(model.get());
        const llama_token eos = llama_vocab_eos(vocab);

        pp_test::reader in(vectors);
        in.expect_magic("PPTOKV01");
        const std::string sha = in.bytes(64);
        const std::string arch = pp_test::meta(model.get(), "general.architecture");
        r.check(pp_test::meta(model.get(), arch + ".tokenizer.source_sha256") == sha, "the vectors were generated from the model's tokenizer file (%.12s...)", sha.c_str());

        // The tokens of this vocabulary that are not text.
        std::vector<llama_token> control;
        for (llama_token id = 0; id < llama_vocab_n_tokens(vocab); id++) {
            if (llama_vocab_is_control(vocab, id)) {
                control.push_back(id);
            }
        }
        const auto n_control_in = [&](const std::vector<llama_token> & ids) {
            return (size_t) std::count_if(ids.begin(), ids.end(), [&](llama_token t) { return std::binary_search(control.begin(), control.end(), t); });
        };

        const uint32_t n_text = in.u32();
        size_t bad_special = 0, bad_plain = 0, n_tokens = 0, n_differ = 0, n_eos_plain = 0, n_control_plain = 0, n_control_special = 0;
        for (uint32_t i = 0; i < n_text; i++) {
            const std::string text = in.sized_bytes();
            const std::vector<llama_token> exact = in.ids_u16();
            const std::vector<llama_token> safe = in.u8() != 0 ? in.ids_u16() : exact;
            const std::vector<llama_token> plain = tokenize(vocab, text, false);
            const std::vector<llama_token> special = tokenize(vocab, text, true);
            bad_special += special != exact ? 1 : 0;
            bad_plain += plain != safe ? 1 : 0;
            n_eos_plain += (size_t) std::count(plain.begin(), plain.end(), eos);
            n_control_plain += n_control_in(plain);
            n_control_special += n_control_in(special);
            n_tokens += exact.size();
            n_differ += exact != safe ? 1 : 0;
        }
        r.check(bad_special == 0, "special tokens parsed: %zu mismatches out of %u texts (%zu tokens)", bad_special, n_text, n_tokens);
        r.check(bad_plain == 0, "special tokens as plain text: %zu mismatches out of %u texts (%zu texts differ between the two)", bad_plain, n_text, n_differ);
        // What makes "one conversation is one document" exact for Boris-2: text never becomes the end-of-text token.
        r.check(n_eos_plain == 0 && n_differ > 0, "the end-of-text token %d occurs %zu times in text tokenised as plain characters (%zu texts contain its literal)",
                eos, n_eos_plain, n_differ);

        // Every control token, by its own name: plain characters when typed, the token only
        // when special tokens are parsed, and nothing at all when a reply is turned into text.
        size_t n_stays_text = 0, n_parsed = 0, n_silent = 0;
        std::string names;
        for (const llama_token id : control) {
            const std::string name = llama_vocab_get_text(vocab, id);
            const std::vector<llama_token> typed = tokenize(vocab, name, false);
            std::string back;
            for (const llama_token t : typed) {
                back += piece(vocab, t, false);
            }
            n_stays_text += n_control_in(typed) == 0 && typed.size() > 1 && back == name ? 1 : 0;
            n_parsed += tokenize(vocab, name, true) == std::vector<llama_token>{id} ? 1 : 0;
            n_silent += piece(vocab, id, false).empty() && piece(vocab, id, true) == name ? 1 : 0;
            names += (names.empty() ? "" : " ") + name;
        }
        r.check(!control.empty() && n_control_plain == 0 && n_control_special > 0,
                "the %zu control token(s) of the vocabulary occur %zu times in the texts tokenised as plain characters and %zu times with special tokens parsed",
                control.size(), n_control_plain, n_control_special);
        r.check(n_stays_text == control.size() && n_parsed == control.size() && n_silent == control.size(),
                "%s: typed as text, %zu of %zu stay plain characters that read back as typed; parsed, %zu are their one token; turned into text, %zu contribute nothing",
                names.c_str(), n_stays_text, control.size(), n_parsed, n_silent);

        const uint32_t n_chat = in.u32();
        std::vector<chat> chats(n_chat);
        for (chat & c : chats) {
            const uint32_t n_messages = in.u32();
            for (uint32_t m = 0; m < n_messages; m++) {
                c.roles.push_back(in.sized_bytes());
                c.contents.push_back(in.sized_bytes());
            }
            c.add_generation_prompt = in.u8() != 0;
            c.exact = in.ids_u16();
            c.safe = in.u8() != 0 ? in.ids_u16() : c.exact;
        }
        if (!in.at_end()) {
            pp_test::die(vectors + " has trailing bytes");
        }

        if (n_chat > 0) {
            r.check(pp_chatml_text_supported(model.get()), "the model's conversations are plain-text ChatML ('%s')",
                    pp_test::meta(model.get(), arch + ".chat.format").c_str());

            size_t bad = 0, n_same_as_exact = 0, n_chat_tokens = 0, n_eos = 0, bad_ends = 0, n_ends = 0;
            std::vector<llama_token> got, prefix;
            std::vector<int32_t> ends;
            for (const chat & c : chats) {
                const std::vector<pp_chat_message> messages = c.messages();
                if (!chat_tokenize(model.get(), messages, messages.size(), c.add_generation_prompt, got, &ends) || got != c.safe) {
                    bad++;
                    continue;
                }
                n_same_as_exact += c.safe == c.exact ? 1 : 0;
                n_chat_tokens += got.size();
                n_eos += (size_t) std::count(got.begin(), got.end(), eos);
                // A message end is where a shorter conversation ends: the tokens of the first i + 1
                // messages alone must be exactly the first message_ends[i] tokens.
                for (size_t i = 0; i < messages.size(); i++) {
                    n_ends++;
                    const bool ok = ends[i] > (i == 0 ? 0 : ends[i - 1]) && (size_t) ends[i] <= got.size()
                            && chat_tokenize(model.get(), messages, i + 1, false, prefix, nullptr)
                            && prefix.size() == (size_t) ends[i] && std::equal(prefix.begin(), prefix.end(), got.begin());
                    bad_ends += ok ? 0 : 1;
                }
            }
            r.check(bad == 0, "conversations: %zu mismatches out of %u (%zu tokens; %zu of them are also what the reference gives with special tokens parsed)",
                    bad, n_chat, n_chat_tokens, n_same_as_exact);
            r.check(bad_ends == 0 && n_ends > 0, "message ends: %zu of %zu are not the token count of the shorter conversation", bad_ends, n_ends);
            r.check(n_eos == 0, "the end-of-text token occurs %zu times in the encoded conversations", n_eos);

            // The calling contract.
            const chat & c = chats[0];
            const std::vector<pp_chat_message> messages = c.messages();
            const int32_t n_messages = (int32_t) messages.size();
            const int32_t needed = -pp_chatml_text_tokenize(model.get(), messages.data(), n_messages, c.add_generation_prompt, nullptr, 0, nullptr);
            std::vector<llama_token> small((size_t) needed, -7);
            int32_t end_guard = -7;
            const int32_t too_small = pp_chatml_text_tokenize(model.get(), messages.data(), n_messages, c.add_generation_prompt, small.data(), needed - 1, &end_guard);
            r.check(needed == (int32_t) c.safe.size() && too_small == -needed && small[0] == -7 && end_guard == -7,
                    "a buffer that is too small gets the size needed (%d) and nothing is written", needed);

            const std::string malformed = "caf\xC3";
            const pp_chat_message bad_content = {"user", 4, malformed.data(), (int32_t) malformed.size()};
            const pp_chat_message bad_role = {malformed.data(), (int32_t) malformed.size(), "hi", 2};
            const pp_chat_message negative = {"user", 4, "hi", -1};
            const pp_chat_message null_content = {"user", 4, nullptr, 2};
            const auto error = [&](const pp_chat_message * m, int32_t n) {
                return pp_chatml_text_tokenize(model.get(), m, n, true, small.data(), (int32_t) small.size(), nullptr) == PP_ERROR;
            };
            r.check(error(&bad_content, 1) && error(&bad_role, 1) && error(&negative, 1) && error(&null_content, 1) && error(nullptr, 1) && error(messages.data(), -1)
                        && pp_chatml_text_tokenize(nullptr, messages.data(), n_messages, true, small.data(), needed, nullptr) == PP_ERROR
                        && pp_chatml_text_tokenize(model.get(), messages.data(), n_messages, true, nullptr, needed, nullptr) == PP_ERROR
                        && !pp_chatml_text_supported(nullptr),
                    "malformed UTF-8, null and negative arguments are errors, not tokens");

            // No messages: nothing, or the generation prompt alone.
            std::vector<llama_token> prompt_only(32);
            const int32_t n_none = pp_chatml_text_tokenize(model.get(), nullptr, 0, false, prompt_only.data(), 32, nullptr);
            const int32_t n_prompt = pp_chatml_text_tokenize(model.get(), nullptr, 0, true, prompt_only.data(), 32, nullptr);
            prompt_only.resize(n_prompt > 0 ? (size_t) n_prompt : 0);
            r.check(n_none == 0 && n_prompt > 0 && prompt_only == tokenize(vocab, "<|im_start|>assistant\n", false),
                    "an empty conversation is no tokens, or the %d tokens of the generation prompt", n_prompt);
        }
    }
    llama_backend_free();
    return r.finish();
}
