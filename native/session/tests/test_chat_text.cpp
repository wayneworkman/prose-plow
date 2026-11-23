// A chat with a model whose ChatML is plain text, end to end through the session's C interface.
//
//   test_chat_text --gguf FILE [--max-new-tokens N]
//
// For the models whose file says "<architecture>.chat.format = chatml-text" and whose memory is
// attention only (the byte-level BPE members of the peacebell family). It walks the path the
// application takes for such a model and checks each step's own answer:
//
//   render and tokenise a conversation with a system message (pp_chatml_text_tokenize), evaluate
//   it in chunks, generate greedily with the model's suppressed tokens banned, change an earlier
//   message and cut the memory back to what the two conversations share, generate again, end a
//   reply on the end-of-text token and on the end marker's text, and append the next turn.
//
// The weights do not matter here, and a model with random weights serves: what a reply says is
// never looked at. Such a model produces neither way of ending a reply by itself, so both are
// brought about by banning every token but the wanted one, which is something the sampler
// interface can do and shows the mechanism with the session's own calls.
//
// Whether the logits are right is the business of test_peacebell_logits; whether the session
// honours its contract in general, of test_session.

#include "proseplow_session.h"
#include "support/test_support.h"

#include <algorithm>
#include <numeric>

namespace {

using pp_test::report;

const std::string k_end_marker = PP_CHATML_TEXT_END_MARKER;

// The byte-level BPE tokenizer the family's checkpoints share, and what it must give for the
// texts its makers publish as a check. Another tokenizer file has other ids; the checks on these
// vectors then do not run.
const char * const k_known_tokenizer = "161294df90a5bc7449ca9e040bfa1928f5f6a8b4021bf6f042efeada5fc3591b";
const std::vector<llama_token> k_hi_prompt = {28, 92, 319, 63, 13129, 92, 30, 15650, 199, 14583, 28, 92, 319, 63, 500, 92, 30, 199,
                                              28, 92, 319, 63, 13129, 92, 30, 559, 7047, 199};
const std::vector<llama_token> k_hello_end = {19281, 16599, 92, 319, 63, 500, 92, 30, 199};

struct message {
    std::string role;
    std::string content;
};

std::vector<pp_chat_message> views(const std::vector<message> & messages) {
    std::vector<pp_chat_message> out;
    for (const message & m : messages) {
        out.push_back({m.role.data(), (int32_t) m.role.size(), m.content.data(), (int32_t) m.content.size()});
    }
    return out;
}

// The conversation as tokens and the token count at which each message ends.
bool encode(const llama_model * model, const std::vector<message> & messages, std::vector<llama_token> & tokens, std::vector<int32_t> & ends) {
    const std::vector<pp_chat_message> m = views(messages);
    const int32_t needed = pp_chatml_text_tokenize(model, m.data(), (int32_t) m.size(), true, nullptr, 0, nullptr);
    if (needed == PP_ERROR || needed > 0) {
        return false;
    }
    tokens.assign((size_t) -needed, LLAMA_TOKEN_NULL);
    ends.assign(messages.size(), -1);
    return pp_chatml_text_tokenize(model, m.data(), (int32_t) m.size(), true, tokens.data(), -needed, ends.data()) == -needed;
}

std::vector<llama_token> tokenize(pp_session * s, const std::string & text) {
    const int32_t needed = pp_session_tokenize(s, text.data(), (int32_t) text.size(), false, nullptr, 0);
    if (needed == PP_ERROR || needed > 0) {
        pp_test::die("the session cannot tokenise a test text");
    }
    std::vector<llama_token> out((size_t) -needed);
    if (pp_session_tokenize(s, text.data(), (int32_t) text.size(), false, out.data(), -needed) != -needed) {
        pp_test::die("the session cannot tokenise a test text");
    }
    return out;
}

std::string bytes_of(pp_session * s, llama_token token) {
    char buffer[256];
    const int32_t n = pp_session_token_to_bytes(s, token, buffer, (int32_t) sizeof(buffer));
    if (n < 0) {
        pp_test::die("a token's text does not fit in 256 bytes");
    }
    return std::string(buffer, (size_t) n);
}

// The session requires both callbacks of an evaluation. Nothing is cancelled here; the progress
// reports are counted, one per chunk.
int g_progress_reports = 0;
const pp_evaluation_observer k_observer = {
    [](void *) { return false; },
    [](int32_t, void *) { g_progress_reports++; },
    nullptr,
};

bool evaluate(pp_session * s, const std::vector<llama_token> & tokens, size_t first, int32_t chunk) {
    int32_t n_in_memory = -1;
    const int32_t n = (int32_t) (tokens.size() - first);
    return pp_session_evaluate(s, tokens.data() + first, n, chunk, &k_observer, &n_in_memory) == PP_STATUS_OK && n_in_memory == n;
}

bool configure(pp_session * s, const std::vector<llama_token> & banned) {
    pp_sampler_config config = {};
    config.temperature = 0.0f; // greedy: the test must give the same reply twice
    config.top_p = 1.0f;
    config.repetition_penalty = 1.0f;
    config.penalty_window = 1;
    config.banned_tokens = banned.data();
    config.n_banned_tokens = (int32_t) banned.size();
    return pp_session_configure_sampler(s, &config) == PP_STATUS_OK;
}

enum class ending { failed, limit, end_of_text, marker };

struct reply {
    std::vector<llama_token> tokens; // what was sampled; all but the last were evaluated when the reply ended on a token or a marker
    std::string text;                // their bytes, the end marker included when the reply ended on it
    ending why = ending::failed;
    int n_evaluated = 0;             // how many of the tokens went back into the memory
};

// The application's generation loop: sample, stop on the end-of-text token (which is never fed
// back) or once the decoded text contains the end marker, else evaluate the token and go on.
reply generate(pp_session * s, int n_max) {
    reply r;
    for (int i = 0; i < n_max; i++) {
        llama_token token = LLAMA_TOKEN_NULL;
        if (pp_session_sample_next(s, &token) != PP_STATUS_OK) {
            return r;
        }
        if (pp_session_is_end_of_generation(s, token)) {
            r.why = ending::end_of_text;
            return r;
        }
        r.tokens.push_back(token);
        r.text += bytes_of(s, token);
        if (r.text.find(k_end_marker) != std::string::npos) {
            r.why = ending::marker;
            return r;
        }
        if (!evaluate(s, r.tokens, r.tokens.size() - 1, 1)) {
            return r;
        }
        r.n_evaluated++;
    }
    r.why = ending::limit;
    return r;
}

const char * name(ending e) {
    switch (e) {
        case ending::limit:       return "the length limit";
        case ending::end_of_text: return "the end-of-text token";
        case ending::marker:      return "the end marker";
        default:                  return "a failure";
    }
}

size_t common_prefix(const std::vector<llama_token> & a, const std::vector<llama_token> & b) {
    size_t n = 0;
    while (n < a.size() && n < b.size() && a[n] == b[n]) {
        n++;
    }
    return n;
}

} // namespace

int main(int argc, char ** argv) {
    std::string gguf;
    int max_new_tokens = 24;
    for (int i = 1; i + 1 < argc; i += 2) {
        const std::string key = argv[i];
        if (key == "--gguf") {
            gguf = argv[i + 1];
        } else if (key == "--max-new-tokens") {
            max_new_tokens = std::atoi(argv[i + 1]);
        }
    }
    if (gguf.empty() || max_new_tokens < 1) {
        std::fprintf(stderr, "usage: %s --gguf FILE [--max-new-tokens N]\n", argv[0]);
        return 2;
    }

    report r;
    if (pp_runtime_init(nullptr, 0) != PP_STATUS_OK) {
        pp_test::die("pp_runtime_init failed");
    }
    {
        // The chat encoder works on a model, the session does not hand its own out: the encoder
        // gets the vocabulary of the same file. The application renders in Kotlin and tokenises
        // through the session; the two are compared below.
        pp_test::model_ptr vocab_model = pp_test::load_model(gguf, true);
        const llama_vocab * vocab = llama_model_get_vocab(vocab_model.get());
        const std::string arch = pp_test::meta(vocab_model.get(), "general.architecture");
        r.check(pp_chatml_text_supported(vocab_model.get()), "the file's conversations are plain-text ChatML (%s.chat.format = '%s')",
                arch.c_str(), pp_test::meta(vocab_model.get(), arch + ".chat.format").c_str());

        pp_session_params params = {};
        params.n_ctx = 1024;
        params.n_batch = 512;
        params.n_threads = pp_test::n_threads();
        params.load_mode = PP_LOAD_MODE_READ;
        pp_session * s = nullptr;
        char error[256] = "";
        if (pp_session_open_path(gguf.c_str(), &params, &s, error, sizeof(error)) != PP_STATUS_OK) {
            pp_test::die(std::string("the session cannot open the model: ") + error);
        }

        // What the tokenizer file's makers publish as its check, when this is that file.
        const llama_token eos = llama_vocab_eos(vocab);
        if (pp_test::meta(vocab_model.get(), arch + ".tokenizer.source_sha256") == k_known_tokenizer) {
            std::vector<llama_token> hi;
            std::vector<int32_t> hi_ends;
            const bool encoded = encode(vocab_model.get(), {{"user", "Hi"}}, hi, hi_ends);
            r.check(encoded && hi == k_hi_prompt && tokenize(s, "<|im_start|>user\nHi<|im_end|>\n<|im_start|>assistant\n") == k_hi_prompt,
                    "the published prompt vector: %zu tokens from the encoder and from the session's tokenizer, both as published", hi.size());
            r.check(tokenize(s, "Hello.<|im_end|>\n") == k_hello_end, "the published reply vector: 'Hello.<|im_end|>\\n' is its %zu tokens, the marker beginning inside '.<'",
                    k_hello_end.size());
            const std::vector<llama_token> y_diaeresis = tokenize(s, "\xC3\xBF");
            r.check(y_diaeresis == std::vector<llama_token>({128, 124}), "the character U+00FF is the two tokens of its UTF-8 bytes (%zu tokens, first %d), not the training pad",
                    y_diaeresis.size(), y_diaeresis.empty() ? -1 : y_diaeresis[0]);
        } else {
            r.note("another tokenizer file than the one whose check vectors are known: they were not compared");
        }
        const std::vector<llama_token> literal = tokenize(s, "a<|endoftext|>b");
        r.check(literal.size() > 3 && std::find(literal.begin(), literal.end(), eos) == literal.end(),
                "the end-of-text token's name typed in a message stays ordinary characters (%zu tokens, none of them id %d)", literal.size(), eos);

        // The tokens the file says must never be sampled; the application bans them.
        int32_t n_suppress = 0;
        const llama_token * suppress = llama_vocab_get_suppress_tokens(vocab, &n_suppress);
        const std::vector<llama_token> banned(suppress, suppress + n_suppress);
        const auto is_banned = [&](llama_token t) { return std::find(banned.begin(), banned.end(), t) != banned.end(); };
        r.check(n_suppress > 0, "the file suppresses %d token(s) (first: %d)", n_suppress, n_suppress > 0 ? suppress[0] : -1);

        // A conversation of three turns with a system message; the outer spaces go, as the template's trim removes them.
        const std::vector<message> first = {
            {"system", "You are a careful assistant."},
            {"user", "  What is the capital of France?  "},
            {"assistant", "Paris."},
            {"user", "And of Italy?"},
        };
        const std::string rendered = "<|im_start|>system\nYou are a careful assistant.<|im_end|>\n<|im_start|>user\nWhat is the capital of France?<|im_end|>\n"
                                     "<|im_start|>assistant\nParis.<|im_end|>\n<|im_start|>user\nAnd of Italy?<|im_end|>\n<|im_start|>assistant\n";
        std::vector<llama_token> prompt;
        std::vector<int32_t> ends;
        const bool encoded = encode(vocab_model.get(), first, prompt, ends);
        const bool ends_rise = encoded && std::is_sorted(ends.begin(), ends.end()) && std::adjacent_find(ends.begin(), ends.end()) == ends.end()
                               && ends.front() > 0 && ends.back() < (int32_t) prompt.size();
        r.check(encoded && prompt == tokenize(s, rendered) && ends_rise,
                "a conversation with a system message: %zu tokens, equal to the session's tokens of the rendered text; the messages end at %d, %d, %d and %d",
                prompt.size(), ends.size() > 0 ? ends[0] : -1, ends.size() > 1 ? ends[1] : -1, ends.size() > 2 ? ends[2] : -1, ends.size() > 3 ? ends[3] : -1);
        r.check(std::find(prompt.begin(), prompt.end(), eos) == prompt.end(), "no end-of-text token in the prompt");

        // Evaluate in chunks, reply.
        const bool evaluated = configure(s, banned) && evaluate(s, prompt, 0, 16);
        r.check(evaluated && pp_session_memory_token_count(s) == (int32_t) prompt.size() && g_progress_reports == (int) ((prompt.size() + 15) / 16),
                "the prompt is evaluated in chunks of 16 with %d progress reports; the memory holds %d tokens", g_progress_reports,
                pp_session_memory_token_count(s));
        if (!evaluated) {
            // Nothing below can work; say what the session answered and stop.
            r.note("the session's last error: %s", pp_session_last_error(s));
            pp_session_close(s);
            return r.finish();
        }
        const int64_t t0 = llama_time_us();
        const reply one = generate(s, max_new_tokens);
        const double seconds = (double) (llama_time_us() - t0) / 1e6;
        r.check(one.why != ending::failed && std::none_of(one.tokens.begin(), one.tokens.end(), is_banned),
                "a greedy reply of %zu tokens, ended by %s, without a banned token (%.1f tokens/s)", one.tokens.size(), name(one.why),
                (double) one.tokens.size() / seconds);
        std::vector<llama_token> memory = prompt;
        memory.insert(memory.end(), one.tokens.begin(), one.tokens.begin() + one.n_evaluated);
        r.check(pp_session_memory_token_count(s) == (int32_t) memory.size(), "the memory holds the prompt and the %d reply tokens that were fed back", one.n_evaluated);

        // The last question is changed: cut the memory back to what the two conversations share.
        std::vector<message> second = first;
        second.back().content = "And of Spain?";
        std::vector<llama_token> edited;
        std::vector<int32_t> edited_ends;
        if (!encode(vocab_model.get(), second, edited, edited_ends)) {
            pp_test::die("the edited conversation cannot be encoded");
        }
        const size_t keep = common_prefix(memory, edited);
        bool removed = false;
        const bool rewound = pp_session_rewind_to(s, (int32_t) keep, &removed) == PP_STATUS_OK && removed
                             && pp_session_memory_token_count(s) == (int32_t) keep;
        r.check(rewound && keep >= (size_t) ends[2] && keep < edited.size(),
                "after the edit the memory is cut back to the %zu shared tokens (the third message ends at %d); %zu tokens remain to evaluate",
                keep, ends[2], edited.size() - keep);
        const bool tail_ok = evaluate(s, edited, keep, 16);
        const reply two = generate(s, max_new_tokens);
        r.check(tail_ok && two.why != ending::failed, "the edited conversation gets a reply of %zu tokens, ended by %s", two.tokens.size(), name(two.why));

        // The same steps again without the first reply in between: if the removed cells left a
        // trace, the two replies would differ. Then the edited conversation from an empty memory.
        const bool replayed = pp_session_clear_memory(s) == PP_STATUS_OK && configure(s, banned) && evaluate(s, prompt, 0, 16)
                              && pp_session_rewind_to(s, (int32_t) keep, &removed) == PP_STATUS_OK && removed && evaluate(s, edited, keep, 16);
        const reply two_again = generate(s, max_new_tokens);
        r.check(replayed && two_again.tokens == two.tokens, "the reply after the cut is the same whether or not a reply was generated and removed before it (%zu tokens)",
                two_again.tokens.size());
        const bool fresh_ok = pp_session_clear_memory(s) == PP_STATUS_OK && configure(s, banned) && evaluate(s, edited, 0, 512);
        const reply two_fresh = generate(s, max_new_tokens);
        // Reported, not required: the kept cells were rounded to 16 bits from values computed in
        // another batch shape than in the fresh run, and a model with random weights has ties
        // that such a rounding can turn. The logit tests hold that difference to its limit.
        r.check(fresh_ok && two_fresh.why != ending::failed, "the edited conversation evaluated from an empty memory gets a reply of %zu tokens", two_fresh.tokens.size());
        r.note("reply after the cut against reply from an empty memory: %s (first %zu of %zu tokens equal)",
               two_fresh.tokens == two.tokens ? "identical" : "different", common_prefix(two_fresh.tokens, two.tokens), two.tokens.size());

        // Ending a reply. The memory holds the edited prompt; a model with random weights ends
        // nothing by itself, so every token but the wanted one is banned for each step.
        const int32_t n_vocab = pp_session_vocab_size(s);
        const auto all_but = [&](llama_token wanted) {
            std::vector<llama_token> others;
            others.reserve((size_t) n_vocab);
            for (llama_token t = 0; t < n_vocab; t++) {
                if (t != wanted) {
                    others.push_back(t);
                }
            }
            return others;
        };
        const bool at_prompt = pp_session_clear_memory(s) == PP_STATUS_OK && evaluate(s, edited, 0, 512);
        const reply ended = configure(s, all_but(eos)) ? generate(s, max_new_tokens) : reply();
        r.check(at_prompt && ended.why == ending::end_of_text && ended.tokens.empty() && pp_session_memory_token_count(s) == (int32_t) edited.size(),
                "the end-of-text token (id %d) ends the reply, is not part of it and is not fed back: the memory still holds %d tokens",
                eos, pp_session_memory_token_count(s));

        // The marker's text: its first character arrives in the same token as the full stop, so no
        // comparison of token ids could find it. A prediction serves one choice, and the one of the
        // prompt was spent on the end-of-text token: the prompt's last token is evaluated again,
        // which is also how an application asks for another reply to the same prompt.
        const std::vector<llama_token> spelled = tokenize(s, "Hello." + k_end_marker);
        reply marked;
        bool forced = pp_session_rewind_to(s, (int32_t) edited.size() - 1, &removed) == PP_STATUS_OK && removed && evaluate(s, edited, edited.size() - 1, 1);
        for (size_t i = 0; i < spelled.size() && forced && marked.why == ending::failed; i++) {
            llama_token token = LLAMA_TOKEN_NULL;
            forced = configure(s, all_but(spelled[i])) && pp_session_sample_next(s, &token) == PP_STATUS_OK && token == spelled[i];
            if (!forced) {
                break;
            }
            marked.tokens.push_back(token);
            marked.text += bytes_of(s, token);
            if (marked.text.find(k_end_marker) != std::string::npos) {
                marked.why = ending::marker;
            } else {
                forced = evaluate(s, marked.tokens, marked.tokens.size() - 1, 1);
                marked.n_evaluated++;
            }
        }
        const std::string said = marked.text.substr(0, marked.text.find(k_end_marker));
        r.check(forced && marked.why == ending::marker && marked.tokens == spelled && said == "Hello.",
                "the end marker's text ends the reply at its last token (%zu tokens); the reply is '%s'", marked.tokens.size(), said.c_str());

        // The next turn: the conversation with that reply in it begins with everything the memory
        // holds, so nothing is removed and only the rest is evaluated.
        std::vector<message> third = second;
        third.push_back({"assistant", said});
        third.push_back({"user", "Thank you."});
        std::vector<llama_token> next;
        std::vector<int32_t> next_ends;
        if (!encode(vocab_model.get(), third, next, next_ends)) {
            pp_test::die("the continued conversation cannot be encoded");
        }
        std::vector<llama_token> held = edited;
        held.insert(held.end(), marked.tokens.begin(), marked.tokens.begin() + marked.n_evaluated);
        const bool is_prefix = common_prefix(held, next) == held.size() && pp_session_memory_token_count(s) == (int32_t) held.size();
        r.check(is_prefix && configure(s, banned) && evaluate(s, next, held.size(), 512) && pp_session_memory_token_count(s) == (int32_t) next.size(),
                "the next turn is appended without a cut: the %zu tokens in memory begin the new conversation of %zu", held.size(), next.size());
        const reply last = generate(s, 4);
        r.check(last.why != ending::failed && std::none_of(last.tokens.begin(), last.tokens.end(), is_banned), "and generation goes on from there (%zu tokens)", last.tokens.size());

        pp_session_close(s);
    }
    return r.finish();
}
