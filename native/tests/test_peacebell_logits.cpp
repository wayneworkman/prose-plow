// Validates a converted peacebell GGUF against logits of the PyTorch reference model.
//
//   test_peacebell_logits --gguf FILE --ref FILE --precision f32|f16 [--max-new-tokens N] [--top1-margin X] [--control-min X]
//
// The reference file (native/tests/refgen/formats.py) holds token sequences made of a prompt
// and the reference's greedy reply, with the reference's logits. What the prompt is and what
// ends a reply follows the file's chat format: marker ids around a conversation and the end
// marker's id (chatml-ids); a conversation rendered to plain text, ended by the marker's text or
// the end-of-text token (chatml-text); a text to continue, ended by the end-of-text token
// (completion).
//
// --precision f32 is the gate for the port itself. It runs the F32 file with an F32 KV cache
// and without flash attention, so that the only differences left are summation order and the
// float32 RoPE angle schedule, and requires every way of feeding the same tokens (one batch,
// token by token, uneven batches, every micro-batch size, after a rewind) to reproduce the
// reference logits within 5e-3 and the greedy reply exactly.
//
// --precision f16 is the gate for the file that ships. It runs the F16 file as the app will
// (F16 KV cache, default attention path) and requires the distributions to stay within the KL
// and top-1 limits measured for the 16-bit format, for the same ways of feeding the tokens.
// It needs a reference with the whole logit vector at every position.

#include "support/logit_support.h"

#include <cstdarg>

namespace {

using namespace pp_test;

// Limits from the validation plan of the design this port implements: the defaults of logit_limits.
logit_limits g_limits;

// The text that ends a reply of a model whose ChatML is plain text.
const std::string k_end_marker = "<|im_end|>";

// The sampler the app will run for this family: the tokens the file marks as never to be
// sampled are masked, then the most likely token is taken. extra_bias simulates a caller that
// pushes one of the masked tokens up: the mask must still win. Without the mask the sampler is
// the bare greedy choice, which is how the test learns what the mask is needed for.
llama_sampler * make_greedy_sampler(const llama_vocab * vocab, int32_t n_vocab, float extra_bias, bool masked) {
    int32_t n_suppress = 0;
    const llama_token * suppress = llama_vocab_get_suppress_tokens(vocab, &n_suppress);
    std::vector<llama_logit_bias> boost, mask;
    for (int32_t i = 0; i < n_suppress; i++) {
        boost.push_back({suppress[i], extra_bias});
        mask.push_back({suppress[i], -std::numeric_limits<float>::infinity()});
    }
    llama_sampler * chain = llama_sampler_chain_init(llama_sampler_chain_default_params());
    if (extra_bias != 0.0f) {
        llama_sampler_chain_add(chain, llama_sampler_init_logit_bias(n_vocab, (int32_t) boost.size(), boost.data()));
    }
    if (masked) {
        llama_sampler_chain_add(chain, llama_sampler_init_logit_bias(n_vocab, (int32_t) mask.size(), mask.data()));
    }
    llama_sampler_chain_add(chain, llama_sampler_init_greedy());
    return chain;
}

struct greedy_result {
    std::vector<llama_token> tokens;
    double seconds_prompt = 0.0;
    double seconds_reply = 0.0;
};

// Decodes the prompt in one batch, then generates greedily one token at a time until stop_token
// or n_max tokens. The stop token is not part of the result and is not fed back. With a
// stop_text the reply also ends once its decoded text contains that text; the tokens that spell
// it are part of the result, as they are in the reference, because the text has no token
// boundary of its own (its first character can share a token with what precedes it).
greedy_result generate(evaluator & ev, const llama_vocab * vocab, const std::vector<llama_token> & prompt,
                       llama_token stop_token, const std::string & stop_text, int n_max, float extra_bias, bool masked = true) {
    greedy_result result;
    llama_sampler * sampler = make_greedy_sampler(vocab, ev.vocab_size(), extra_bias, masked);
    llama_batch_ext * batch = llama_batch_ext_init(ev.context());
    ev.clear();
    std::string text;

    std::vector<llama_token> pending = prompt;
    size_t pos = 0;
    for (int step = 0; step <= n_max; step++) {
        llama_batch_ext_clear(batch);
        for (size_t i = 0; i < pending.size(); i++) {
            const int32_t idx = llama_batch_ext_add_token(batch, 0, pending[i]);
            const llama_pos p = (llama_pos) (pos + i);
            llama_batch_ext_set_pos(batch, idx, &p);
            llama_batch_ext_set_output_logits(batch, idx, i + 1 == pending.size());
        }
        const int64_t t0 = llama_time_us();
        const int32_t rc = llama_process(ev.context(), LLAMA_PROCESS_TYPE_DECODE, batch);
        const double seconds = (double) (llama_time_us() - t0) / 1e6;
        if (rc != 0) {
            die("llama_process returned " + std::to_string(rc));
        }
        (step == 0 ? result.seconds_prompt : result.seconds_reply) += seconds;
        pos += pending.size();
        if (step == n_max) {
            break;
        }
        const llama_token token = llama_sampler_sample(sampler, ev.context(), -1);
        if (token == stop_token) {
            break;
        }
        result.tokens.push_back(token);
        if (!stop_text.empty()) {
            char piece[256];
            const int32_t n = llama_token_to_piece(vocab, token, piece, (int32_t) sizeof(piece), 0, false);
            if (n < 0) {
                die("a token's text does not fit in 256 bytes");
            }
            text.append(piece, (size_t) n);
            if (text.find(stop_text) != std::string::npos) {
                break;
            }
        }
        pending.assign(1, token);
    }

    llama_batch_ext_free(batch);
    llama_sampler_free(sampler);
    return result;
}

} // namespace

int main(int argc, char ** argv) {
    std::string gguf, ref_path, precision;
    // The cap the reference's greedy replies were generated with: a reply that reached it has no
    // stop token, and the port must be cut at the same length to be comparable.
    int max_new_tokens = 96;
    // How far the logits must move when the memory holds the wrong beginning, for the rewind
    // test to count as a test. A trained model moves them by ten and more; an untrained one,
    // whose blocks barely look at the context yet, by less than one.
    double control_min = 0.5;
    for (int i = 1; i + 1 < argc; i += 2) {
        const std::string key = argv[i];
        if (key == "--gguf") {
            gguf = argv[i + 1];
        } else if (key == "--ref") {
            ref_path = argv[i + 1];
        } else if (key == "--precision") {
            precision = argv[i + 1];
        } else if (key == "--max-new-tokens") {
            max_new_tokens = std::atoi(argv[i + 1]);
        } else if (key == "--top1-margin") {
            g_limits.top1_margin = std::atof(argv[i + 1]);
        } else if (key == "--control-min") {
            control_min = std::atof(argv[i + 1]);
        }
    }
    if (gguf.empty() || ref_path.empty() || (precision != "f32" && precision != "f16") || max_new_tokens < 1) {
        std::fprintf(stderr, "usage: %s --gguf FILE --ref FILE --precision f32|f16 [--max-new-tokens N] [--top1-margin X] [--control-min X]\n", argv[0]);
        return 2;
    }
    const bool f16 = precision == "f16";

    quiet_logs();
    llama_backend_init();
    report r;
    {
        const reference ref = load_reference(ref_path);
        model_ptr model = load_model(gguf, false);
        const llama_vocab * vocab = llama_model_get_vocab(model.get());
        if ((uint32_t) llama_vocab_n_tokens(vocab) != ref.n_vocab) {
            die("the reference was made for a vocabulary of another size");
        }
        if (f16 && !ref.full_everywhere()) {
            die("--precision f16 needs a reference with the whole logit vector at every position");
        }
        const std::string arch = meta(model.get(), "general.architecture");
        r.check(arch == "peacebell", "architecture is '%s'", arch.c_str());
        r.note("%s, %s weights, %s KV cache, %d thread(s), %zu sequences", gguf.substr(gguf.find_last_of('/') + 1).c_str(),
               precision.c_str(), f16 ? "F16" : "F32", n_threads(), ref.seqs.size());

        evaluator ev(model.get(), f16);
        const std::vector<size_t> one_batch = {k_n_batch};
        const std::vector<size_t> one_token = {1};
        // Uneven on purpose: single tokens between larger batches, as a chat produces them.
        const std::vector<size_t> mixed = {3, 1, 1, 8, 2, 1, 17, k_n_batch};

        // One batch, token by token and uneven batches against the reference; and against each
        // other, which catches a cache or position error even where the reference is sparse.
        ev.open(k_n_batch);
        std::vector<logit_rows> baseline;
        agreement a_batch, a_token, a_mixed;
        double d_token = 0.0, d_mixed = 0.0;
        for (const sequence & s : ref.seqs) {
            const size_t n = s.ids.size();
            ev.clear();
            baseline.push_back(ev.decode(s.ids, 0, n, one_batch));
            compare(ref, s, baseline.back(), 0, n, g_limits, a_batch);

            ev.clear();
            const logit_rows by_token = ev.decode(s.ids, 0, n, one_token);
            compare(ref, s, by_token, 0, n, g_limits, a_token);
            d_token = std::max(d_token, max_abs_between(baseline.back(), by_token, 0, n));

            ev.clear();
            const logit_rows by_mixed = ev.decode(s.ids, 0, n, mixed);
            compare(ref, s, by_mixed, 0, n, g_limits, a_mixed);
            d_mixed = std::max(d_mixed, max_abs_between(baseline.back(), by_mixed, 0, n));
        }
        judge(r, f16, "one batch vs reference:", a_batch, g_limits);
        judge(r, f16, "token by token vs reference:", a_token, g_limits);
        judge(r, f16, "uneven batches vs reference:", a_mixed, g_limits);
        if (!f16) {
            r.check(d_token <= g_limits.f32_max_abs, "token by token vs one batch:        max |dlogit| %.3g (limit %.0e), whole vocabulary", d_token, g_limits.f32_max_abs);
            r.check(d_mixed <= g_limits.f32_max_abs, "uneven batches vs one batch:        max |dlogit| %.3g (limit %.0e), whole vocabulary", d_mixed, g_limits.f32_max_abs);
        }

        // Rewind against the reference: decode one conversation, cut the memory back to what it
        // shares with another one, decode the other one's tail.
        {
            agreement a_rewind;
            size_t n_rewinds = 0;
            bool all_removed = true;
            for (size_t i = 0; i < ref.seqs.size(); i++) {
                const sequence & from = ref.seqs[i];
                const sequence & to = ref.seqs[(i + 1) % ref.seqs.size()];
                const size_t keep = common_prefix(from.ids, to.ids);
                if (keep == 0 || keep == to.ids.size()) {
                    continue;
                }
                ev.clear();
                ev.decode(from.ids, 0, from.ids.size(), one_batch);
                all_removed = ev.rewind(keep) && all_removed;
                const logit_rows tail = ev.decode(to.ids, keep, to.ids.size(), one_batch);
                compare(ref, to, tail, keep, to.ids.size(), g_limits, a_rewind);
                n_rewinds++;
            }
            r.check(all_removed && n_rewinds > 0, "llama_memory_seq_rm succeeded for %zu rewind(s) to a shared prefix", n_rewinds);
            judge(r, f16, "after a rewind vs reference:", a_rewind, g_limits);
        }

        // Rewind at arbitrary positions: any cut followed by a different tail must equal a fresh
        // evaluation of the spliced sequence. The positions come from a fixed generator.
        {
            double d_splice = 0.0;
            agreement a_splice;
            double d_control = std::numeric_limits<double>::infinity();
            bool all_removed = true;
            uint32_t state = 12345;
            const auto next = [&](uint32_t bound) {
                state = state * 1664525u + 1013904223u;
                return (state >> 8) % bound;
            };
            const int n_cuts = 20;
            for (int c = 0; c < n_cuts; c++) {
                const sequence & head = ref.seqs[next((uint32_t) ref.seqs.size())];
                const sequence & tail = ref.seqs[next((uint32_t) ref.seqs.size())];
                const size_t limit = std::min(head.ids.size(), tail.ids.size()) - 1;
                const size_t cut = 1 + next((uint32_t) (limit - 1));
                std::vector<llama_token> spliced(head.ids.begin(), head.ids.begin() + (long) cut);
                spliced.insert(spliced.end(), tail.ids.begin() + (long) cut, tail.ids.end());

                ev.clear();
                ev.decode(head.ids, 0, head.ids.size(), one_batch);
                all_removed = ev.rewind(cut) && all_removed;
                const logit_rows after_cut = ev.decode(spliced, cut, spliced.size(), one_batch);

                ev.clear();
                const logit_rows fresh = ev.decode(spliced, 0, spliced.size(), one_batch);
                d_splice = std::max(d_splice, max_abs_between(after_cut, fresh, cut, spliced.size()));
                compare_rows(fresh, after_cut, cut, spliced.size(), g_limits, a_splice);

                // The control: the same tail after the wrong beginning. If this did not move the
                // logits, the comparison above would prove nothing about what the memory holds.
                if (!std::equal(head.ids.begin(), head.ids.begin() + (long) cut, tail.ids.begin())) {
                    ev.clear();
                    ev.decode(tail.ids, 0, cut, one_batch);
                    const logit_rows wrong = ev.decode(spliced, cut, spliced.size(), one_batch);
                    d_control = std::min(d_control, max_abs_between(wrong, fresh, cut, spliced.size()));
                }
            }
            r.check(all_removed, "llama_memory_seq_rm succeeded for %d rewinds at arbitrary positions", n_cuts);
            if (f16) {
                // With an F16 cache the kept cells were rounded from values computed in another batch
                // shape than in the fresh run, so the two differ by rounding and are held to the
                // limits of the 16-bit format rather than to the F32 one.
                judge(r, true, "arbitrary rewinds vs fresh run:", a_splice, g_limits);
            } else {
                r.check(d_splice <= g_limits.f32_max_abs, "arbitrary rewinds vs fresh run:     max |dlogit| %.3g (limit %.0e), whole vocabulary", d_splice, g_limits.f32_max_abs);
            }
            r.check(d_control >= control_min && std::isfinite(d_control),
                    "control, the wrong beginning:       max |dlogit| at least %.3g in every case (must exceed %.3g)", d_control, control_min);
        }

        // Greedy generation through the sampler chain, and the speed of it as a sanity figure.
        {
            const std::string end_key = arch + ".chat.end_token_id";
            const std::string end_value = meta(model.get(), end_key);
            // A file without chat markers (plain-text ChatML, or no chat format at all) ends on the
            // end-of-text token instead, and a plain-text conversation also on the marker's text.
            const llama_token stop = end_value.empty() ? llama_vocab_eos(vocab) : (llama_token) std::atoi(end_value.c_str());
            const std::string chat_format = meta(model.get(), arch + ".chat.format");
            const std::string stop_text = chat_format == "chatml-text" ? k_end_marker : std::string();
            r.check(llama_vocab_is_eog(vocab, stop), "the stop token %d is an end-of-generation token for llama.cpp (chat format '%s')", stop, chat_format.c_str());

            size_t n_same = 0, n_prompt_tokens = 0, n_reply_tokens = 0;
            double seconds_prompt = 0.0, seconds_reply = 0.0;
            std::string first_divergence = "none";
            for (size_t k = 0; k < ref.seqs.size(); k++) {
                const sequence & s = ref.seqs[k];
                const std::vector<llama_token> prompt(s.ids.begin(), s.ids.begin() + s.n_prompt);
                const greedy_result g = generate(ev, vocab, prompt, stop, stop_text, max_new_tokens, 0.0f);
                if (g.tokens == s.greedy) {
                    n_same++;
                } else if (first_divergence == "none") {
                    first_divergence = "sequence " + std::to_string(k) + ", reply token " + std::to_string(common_prefix(g.tokens, s.greedy));
                }
                n_prompt_tokens += prompt.size();
                n_reply_tokens += g.tokens.size();
                seconds_prompt += g.seconds_prompt;
                seconds_reply += g.seconds_reply;
            }
            if (f16) {
                // Reported, not gated: the 16-bit limits are statistical (top-1 above), and one
                // near-tie flipping would be within them.
                r.note("greedy replies identical to the reference: %zu of %zu, first divergence: %s", n_same, ref.seqs.size(), first_divergence.c_str());
            } else {
                r.check(n_same == ref.seqs.size(), "greedy replies identical to the reference: %zu of %zu, first divergence: %s",
                        n_same, ref.seqs.size(), first_divergence.c_str());
            }
            r.note("speed: prompt %.0f tokens/s (%zu tokens in one batch each), generation %.1f tokens/s (%zu tokens)",
                   (double) n_prompt_tokens / seconds_prompt, n_prompt_tokens, (double) n_reply_tokens / seconds_reply, n_reply_tokens);

            // A token the file marks as never to be sampled must stay unsampled even when a
            // caller's bias pushes it far above everything else.
            int32_t n_suppress = 0;
            const llama_token * suppress = llama_vocab_get_suppress_tokens(vocab, &n_suppress);
            const sequence & s = ref.seqs[0];
            const std::vector<llama_token> prompt(s.ids.begin(), s.ids.begin() + s.n_prompt);
            const greedy_result boosted = generate(ev, vocab, prompt, stop, stop_text, 16, 1e9f);
            const auto is_suppressed = [&](llama_token t) { return std::find(suppress, suppress + n_suppress, t) != suppress + n_suppress; };
            const bool leaked = std::any_of(boosted.tokens.begin(), boosted.tokens.end(), is_suppressed);
            r.check(n_suppress > 0 && !leaked, "%d suppressed token(s) (first: %d) never sampled, even with a +1e9 bias",
                    n_suppress, n_suppress > 0 ? suppress[0] : -1);

            // What the mask is up against, reported and not judged: the probability the model itself
            // gives the suppressed tokens over every position of the reference sequences, and whether
            // the bare greedy choice ever falls on one. An untrained output row can hold anything.
            if (n_suppress > 0) {
                double max_p = 0.0, sum_p = 0.0;
                size_t n_positions = 0, n_first = 0;
                std::vector<double> log_p;
                for (size_t k = 0; k < ref.seqs.size(); k++) {
                    for (size_t pos = 0; pos < ref.seqs[k].ids.size(); pos++) {
                        const float * row = baseline[k].row(pos);
                        log_softmax(row, ref.n_vocab, log_p);
                        const size_t best = (size_t) (std::max_element(row, row + ref.n_vocab) - row);
                        double p = 0.0;
                        for (int32_t i = 0; i < n_suppress; i++) {
                            p += std::exp(log_p[(size_t) suppress[i]]);
                        }
                        max_p = std::max(max_p, p);
                        sum_p += p;
                        n_first += is_suppressed((llama_token) best) ? 1 : 0;
                        n_positions++;
                    }
                }
                size_t n_unmasked_tokens = 0, n_unmasked_suppressed = 0;
                for (const sequence & q : ref.seqs) {
                    const std::vector<llama_token> q_prompt(q.ids.begin(), q.ids.begin() + q.n_prompt);
                    const greedy_result bare = generate(ev, vocab, q_prompt, stop, stop_text, max_new_tokens, 0.0f, false);
                    n_unmasked_tokens += bare.tokens.size();
                    n_unmasked_suppressed += (size_t) std::count_if(bare.tokens.begin(), bare.tokens.end(), is_suppressed);
                }
                r.note("without the mask: the model gives the suppressed token(s) a probability of at most %.3g (mean %.3g) over %zu positions, "
                       "ranks one first at %zu of them, and greedy generation chose one %zu times in %zu tokens",
                       max_p, sum_p / (double) n_positions, n_positions, n_first, n_unmasked_suppressed, n_unmasked_tokens);
            }
        }

        // Every micro-batch size must give the same logits: the cache and the positions are then
        // right however llama.cpp cuts a batch internally.
        {
            double d_ubatch = 0.0;
            agreement a_ubatch;
            const uint32_t sizes[] = {1, 2, 7, 32, 512};
            for (const uint32_t n_ubatch : sizes) {
                ev.open(n_ubatch);
                for (size_t k = 0; k < ref.seqs.size(); k++) {
                    const sequence & s = ref.seqs[k];
                    ev.clear();
                    const logit_rows rows = ev.decode(s.ids, 0, s.ids.size(), one_batch);
                    compare(ref, s, rows, 0, s.ids.size(), g_limits, a_ubatch);
                    d_ubatch = std::max(d_ubatch, max_abs_between(baseline[k], rows, 0, s.ids.size()));
                }
            }
            judge(r, f16, "n_ubatch 1, 2, 7, 32, 512 vs ref.:", a_ubatch, g_limits);
            if (!f16) {
                r.check(d_ubatch <= g_limits.f32_max_abs, "n_ubatch 1, 2, 7, 32, 512 vs default:   max |dlogit| %.3g (limit %.0e), whole vocabulary", d_ubatch, g_limits.f32_max_abs);
            }
        }
    }
    llama_backend_free();
    return r.finish();
}
