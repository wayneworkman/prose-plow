// A chat session with Boris-2 through the calls an application uses, against the replies of the
// PyTorch reference.
//
//   test_boris2_chat --gguf FILE --ref FILE --precision f32|f16
//
// The reference file (native/tests/refgen/gen_chat_ref.py) is a series of requests, each a whole
// conversation: a new turn appended, the same request again, an earlier message changed,
// another beginning. Its replies were generated from scratch for every request. This program
// serves the same requests from ONE context whose memory it keeps between them, which is what
// an application does and what the model's recurrent Canon state makes delicate. The steps
// below are the discipline an application has to follow, in executable form:
//
//   1. Encode the conversation with pp_chatml_text_tokenize, which also says where each
//      message ends.
//   2. Keep the longest common prefix with the tokens in memory, less one token: a checkpoint
//      restores state, not logits, so the last token of the prompt is always decoded again.
//   3. If tokens must go: restore the latest checkpoint at or before that prefix, then cut the
//      memory at the checkpoint's position with llama_memory_seq_rm, which succeeds only then.
//      Without such a checkpoint, remove the whole sequence and start from position 0.
//   4. Decode the rest, stopping at every message end to save a checkpoint
//      (llama_state_seq_get_data_ext with LLAMA_STATE_SEQ_FLAGS_PARTIAL_ONLY).
//   5. Generate until the decoded text contains the end marker; never feed the end-of-text token.
//
// With --precision f32 every reply must equal the reference's, token for token and as text.
// With --precision f16 (the file that ships, F16 KV cache) the same steps must work; a reply
// that differs at a near-tie is reported, not failed.

#include "support/logit_support.h"

#include "proseplow_native.h"

#include <cstring>

namespace {

using namespace pp_test;

using blob = std::vector<uint8_t>;

struct request {
    std::vector<std::string> roles, contents;
    std::vector<llama_token> prompt, reply;
    bool ended = false;
    std::string text;
};

const std::string k_marker = PP_CHATML_TEXT_END_MARKER;

// What happened to the memory while a request was served.
struct tally {
    size_t n_appended = 0;     // requests that only added tokens
    size_t n_rewound = 0;      // requests that went back to a checkpoint
    size_t n_cleared = 0;      // requests that found no checkpoint to go back to and started over
    size_t n_refused = 0;      // cuts refused before the checkpoint was restored (must equal n_rewound)
    size_t n_reused = 0;       // tokens that were already in memory and stayed
    size_t n_decoded = 0;      // prompt tokens decoded
    size_t n_checkpoints = 0;  // checkpoints taken
    size_t checkpoint_bytes = 0;
    bool   ok = true;          // every restore and every cut that had to work did
};

class session {
  public:
    session(evaluator & ev, const llama_model * model) : ev(ev), vocab(llama_model_get_vocab(model)) {
        sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
        llama_sampler_chain_add(sampler, llama_sampler_init_greedy());
    }

    ~session() { llama_sampler_free(sampler); }

    // Steps 2 to 4: afterwards the memory holds exactly ids, and the logits of its last token
    // are those of this call. bounds are the token counts at which a checkpoint is worth having.
    void feed(const std::vector<llama_token> & ids, const std::vector<size_t> & bounds, tally & t) {
        llama_context * ctx = ev.context();
        llama_memory_t mem = llama_get_memory(ctx);

        size_t n_keep = std::min(common_prefix(cached, ids), ids.size() - 1);
        if (n_keep < cached.size()) {
            // The latest checkpoint at or before the first token that changes.
            size_t i = checkpoints.size();
            while (i > 0 && checkpoints[i - 1].first > n_keep) {
                i--;
            }
            if (i > 0) {
                const auto & [n_tokens, saved] = checkpoints[i - 1];
                // The cut is refused while the recurrent state is ahead of it, and allowed once
                // the state of exactly that position is back.
                t.n_refused += llama_memory_seq_rm(mem, 0, (llama_pos) n_tokens, -1) ? 0 : 1;
                t.ok = llama_state_seq_set_data_ext(ctx, saved.data(), saved.size(), 0, LLAMA_STATE_SEQ_FLAGS_PARTIAL_ONLY) == saved.size() && t.ok;
                t.ok = llama_memory_seq_rm(mem, 0, (llama_pos) n_tokens, -1) && t.ok;
                n_keep = n_tokens;
                t.n_rewound++;
            } else {
                t.ok = llama_memory_seq_rm(mem, 0, -1, -1) && t.ok;
                n_keep = 0;
                t.n_cleared++;
            }
            cached.resize(n_keep);
            checkpoints.resize(i);
        } else {
            t.n_appended++;
        }
        t.n_reused += n_keep;

        for (const size_t bound : bounds) {
            if (bound < cached.size() || (bound == cached.size() && has_checkpoint(bound)) || bound == 0) {
                continue;
            }
            if (bound > cached.size()) {
                if (ev.process(ids, cached.size(), bound) != 0) {
                    die("cannot decode a prompt");
                }
                t.n_decoded += bound - cached.size();
                cached.assign(ids.begin(), ids.begin() + (long) bound);
            }
            blob saved(llama_state_seq_get_size_ext(ctx, 0, LLAMA_STATE_SEQ_FLAGS_PARTIAL_ONLY));
            if (llama_state_seq_get_data_ext(ctx, saved.data(), saved.size(), 0, LLAMA_STATE_SEQ_FLAGS_PARTIAL_ONLY) != saved.size()) {
                die("cannot take a checkpoint");
            }
            t.n_checkpoints++;
            t.checkpoint_bytes = saved.size();
            checkpoints.emplace_back(bound, std::move(saved));
        }
    }

    // Step 5. Returns the generated tokens; text receives the reply up to the end marker and
    // ended says whether the marker was reached.
    std::vector<llama_token> generate(size_t max_new_tokens, std::string & text, bool & ended) {
        std::vector<llama_token> reply;
        std::string decoded;
        ended = false;
        while (reply.size() < max_new_tokens) {
            const llama_token token = llama_sampler_sample(sampler, ev.context(), -1);
            if (llama_vocab_is_eog(vocab, token)) {
                break; // the end-of-text token ends the reply and is never fed
            }
            reply.push_back(token);
            char piece[64];
            const int32_t n = llama_token_to_piece(vocab, token, piece, sizeof(piece), 0, false);
            if (n < 0) {
                die("a token is longer than expected");
            }
            decoded.append(piece, (size_t) n);
            // The marker is found in the text: its first character may share a token with the
            // reply's last one, so no token sequence marks the end.
            if (decoded.find(k_marker) != std::string::npos) {
                ended = true;
                break;
            }
            if (reply.size() == max_new_tokens) {
                break;
            }
            cached.push_back(token);
            if (ev.process(cached, cached.size() - 1, cached.size()) != 0) {
                die("cannot decode a generated token");
            }
        }
        text = decoded.substr(0, decoded.find(k_marker));
        return reply;
    }

    size_t n_cached() const { return cached.size(); }
    size_t n_checkpoints() const { return checkpoints.size(); }

  private:
    bool has_checkpoint(size_t n_tokens) const {
        return std::any_of(checkpoints.begin(), checkpoints.end(), [&](const auto & c) { return c.first == n_tokens; });
    }

    evaluator & ev;
    const llama_vocab * vocab;
    llama_sampler * sampler = nullptr;
    std::vector<llama_token> cached;                    // the tokens in memory; a token's index is its position
    std::vector<std::pair<size_t, blob>> checkpoints;   // (token count, recurrent state at that count), ascending
};

} // namespace

int main(int argc, char ** argv) {
    std::string gguf, ref_path, precision;
    for (int i = 1; i + 1 < argc; i += 2) {
        const std::string key = argv[i];
        if (key == "--gguf") {
            gguf = argv[i + 1];
        } else if (key == "--ref") {
            ref_path = argv[i + 1];
        } else if (key == "--precision") {
            precision = argv[i + 1];
        }
    }
    if (gguf.empty() || ref_path.empty() || (precision != "f32" && precision != "f16")) {
        std::fprintf(stderr, "usage: %s --gguf FILE --ref FILE --precision f32|f16\n", argv[0]);
        return 2;
    }
    const bool f16 = precision == "f16";

    quiet_logs();
    llama_backend_init();
    report r;
    {
        reader in(ref_path);
        in.expect_magic("PPCHAT01");
        const std::string sha = in.bytes(64);
        const uint32_t max_new_tokens = in.u32();
        std::vector<request> requests(in.u32());
        for (request & q : requests) {
            const uint32_t n_messages = in.u32();
            for (uint32_t m = 0; m < n_messages; m++) {
                q.roles.push_back(in.sized_bytes());
                q.contents.push_back(in.sized_bytes());
            }
            q.prompt = in.array<llama_token>(in.u32());
            q.reply = in.array<llama_token>(in.u32());
            q.ended = in.u8() != 0;
            q.text = in.sized_bytes();
        }
        if (!in.at_end()) {
            die(ref_path + " has trailing bytes");
        }

        model_ptr model = load_model(gguf, false);
        const std::string arch = meta(model.get(), "general.architecture");
        r.check(meta(model.get(), arch + ".tokenizer.source_sha256") == sha && pp_chatml_text_supported(model.get()),
                "the reference was generated with the model's tokenizer file and the model is plain-text ChatML");
        r.note("%s, %s weights, %s KV cache, %d thread(s), %zu requests", gguf.substr(gguf.find_last_of('/') + 1).c_str(),
               precision.c_str(), f16 ? "F16" : "F32", n_threads(), requests.size());

        evaluator ev(model.get(), f16);
        ev.open(k_n_batch);
        session chat(ev, model.get());

        tally t;
        size_t bad_prompts = 0, bad_ends = 0, same_tokens = 0, same_text = 0, same_end = 0, marker_in_text = 0, n_reply_tokens = 0;
        std::string first_divergence = "none";
        for (size_t k = 0; k < requests.size(); k++) {
            const request & q = requests[k];
            std::vector<pp_chat_message> messages;
            for (size_t m = 0; m < q.roles.size(); m++) {
                messages.push_back({q.roles[m].data(), (int32_t) q.roles[m].size(), q.contents[m].data(), (int32_t) q.contents[m].size()});
            }

            // Step 1. The encoder must give the reference's tokens; the rest of the request is
            // driven by the reference's tokens either way, so one failure does not hide the others.
            std::vector<llama_token> ids(q.prompt.size() + 16);
            std::vector<int32_t> ends(messages.size(), -1);
            const int32_t n = pp_chatml_text_tokenize(model.get(), messages.data(), (int32_t) messages.size(), true, ids.data(), (int32_t) ids.size(), ends.data());
            ids.resize(n > 0 ? (size_t) n : 0);
            bad_prompts += ids != q.prompt ? 1 : 0;
            std::vector<size_t> bounds;
            for (const int32_t end : ends) {
                if (end > 0 && (size_t) end < q.prompt.size()) {
                    bounds.push_back((size_t) end);
                } else {
                    bad_ends++;
                }
            }
            bounds.push_back(q.prompt.size()); // the end of the generation prompt

            chat.feed(q.prompt, bounds, t);

            std::string text;
            bool ended = false;
            const std::vector<llama_token> reply = chat.generate(max_new_tokens, text, ended);
            n_reply_tokens += reply.size();
            same_tokens += reply == q.reply ? 1 : 0;
            same_end += ended == q.ended ? 1 : 0;
            // A reply cut by the length limit may stop inside a character; only finished replies have a text to compare.
            same_text += !q.ended || text == q.text ? 1 : 0;
            marker_in_text += text.find(k_marker) != std::string::npos ? 1 : 0;
            if (reply != q.reply && first_divergence == "none") {
                first_divergence = "request " + std::to_string(k) + ", reply token " + std::to_string(common_prefix(reply, q.reply));
            }
        }

        r.check(bad_prompts == 0, "pp_chatml_text_tokenize gives the reference's prompt tokens: %zu mismatches out of %zu requests", bad_prompts, requests.size());
        r.check(bad_ends == 0, "every message end is a token boundary inside the prompt (%zu are not)", bad_ends);
        r.check(t.ok && t.n_refused == t.n_rewound, "every checkpoint restore and every cut after it succeeded; %zu of %zu cuts were refused before the restore",
                t.n_refused, t.n_rewound);
        // The session is only a test of the discipline if every kind of request occurred.
        r.check(t.n_appended > 0 && t.n_rewound > 0 && t.n_cleared > 0,
                "memory across %zu requests: %zu appended, %zu rewound to a checkpoint, %zu started over; %zu prompt tokens reused, %zu decoded",
                requests.size(), t.n_appended, t.n_rewound, t.n_cleared, t.n_reused, t.n_decoded);
        r.note("%zu checkpoints taken, %zu bytes each, %zu held at the end", t.n_checkpoints, t.checkpoint_bytes, chat.n_checkpoints());
        if (f16) {
            // Reported, not gated: the 16-bit limits are statistical, and one near-tie flipping is within them.
            r.note("replies identical to the reference: %zu of %zu (%zu tokens), first divergence: %s",
                   same_tokens, requests.size(), n_reply_tokens, first_divergence.c_str());
        } else {
            r.check(same_tokens == requests.size() && same_text == requests.size() && same_end == requests.size(),
                    "replies identical to the reference: %zu of %zu as tokens, %zu as text, %zu ended the same way (%zu tokens), first divergence: %s",
                    same_tokens, requests.size(), same_text, same_end, n_reply_tokens, first_divergence.c_str());
        }
        r.check(marker_in_text == 0, "the end marker appears in %zu reply texts", marker_in_text);
    }
    llama_backend_free();
    return r.finish();
}
