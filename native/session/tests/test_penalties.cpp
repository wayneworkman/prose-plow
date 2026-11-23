// What the session's sampling penalties cover, against a real model file.
//
//   test_penalties --gguf FILE [--ref FILE] [--penalty-ref FILE]
//
// Each penalty has to cover what it covers where it is defined (see pp_session_configure_sampler
// in the header): the repetition penalty every token in memory, prompt and reply alike and each
// once; the presence and frequency penalties the tokens of the reply and no token of the prompt.
// The session only ever says which token it chose, so the program brings a second opinion: a
// context of its own on the same file, straight from llama.cpp and unknown to the session, that
// is fed the same tokens and whose scores are put through the definitions as they are written
// out here, independently of the session's code. At every step the session's choice must be the
// token that comes out on top there. A step where the two best scores are closer than k_lead is
// not judged: the two contexts are not fed in the same pieces, and their scores may differ in
// the last digits.
//
// A check that agrees with the definition could still agree with a wrong one that happens to
// choose the same tokens. So every check names the mistake it is there to catch (a penalty over
// the reply alone, a penalty that also counts the prompt, a record that still holds tokens
// which left the memory or lacks tokens that are in it) and counts the steps at which that
// mistake would have chosen another token; a check without such a step proves nothing and fails.
//
//   --ref          a logit reference of native/tests/refgen (PPLOGIT1): its prompts become the
//                  test's prompts. Required for peacebell, as in test_session.
//   --penalty-ref  a penalty reference (PPPENAL1, gen_penalty_ref.py): the requests of a
//                  conversation with the replies that transformers' generate() gives with the
//                  model's repetition penalty. The session serves them from one memory, with
//                  the same penalty and without a temperature, and must choose the reference's
//                  tokens; at the first position of each reply, where only the prompt can be
//                  penalised, its first choices in order must be the reference's.
//
// Every check prints one line with what was measured. Exit codes as in test_support.h.

#include "proseplow_session.h"
#include "support/logit_support.h"

#include <algorithm>
#include <cmath>
#include <functional>
#include <limits>

namespace {

using pp_test::report;

constexpr int32_t k_chunk  = 32; // the engine's default chunk
constexpr int32_t k_stride = 24; // a checkpoint every so many tokens of a prompt
constexpr int     k_steps  = 16; // tokens sampled in one run
// The lead a choice must have over the next best token to be judged against the second context.
constexpr double  k_lead = 0.02;
// The same against the fp32 reference, from which the 16-bit file is a few hundredths away.
constexpr double  k_ref_lead = 0.1;
// How far a logit of the 16-bit file may be from the reference's at one position.
constexpr double  k_ref_logit = 0.05;

struct session_closer {
    void operator()(pp_session * s) const { pp_session_close(s); }
};
using session_ptr = std::unique_ptr<pp_session, session_closer>;

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

std::vector<llama_token> from(const std::vector<llama_token> & tokens, size_t first) {
    return std::vector<llama_token>(tokens.begin() + (std::ptrdiff_t) std::min(first, tokens.size()), tokens.end());
}

std::vector<llama_token> joined(std::vector<llama_token> a, const std::vector<llama_token> & b) {
    a.insert(a.end(), b.begin(), b.end());
    return a;
}

// The second opinion: the model's scores after a token sequence, from a context the session
// does not know.
class oracle {
  public:
    explicit oracle(const std::string & gguf) : model(pp_test::load_model(gguf, false)), eval(model.get(), true) {
        eval.open(pp_test::k_n_batch);
    }

    // The scores for the token after ids; valid until the next call.
    const float * after(const std::vector<llama_token> & ids) {
        size_t have = pp_test::common_prefix(held, ids);
        if (have < held.size() || have == ids.size()) {
            // Not a continuation of what the context holds. It starts over: that is right for
            // every kind of memory, and nothing here is in a hurry.
            eval.clear();
            have = 0;
        }
        for (size_t pos = have; pos < ids.size();) {
            const size_t n = std::min<size_t>(k_chunk, ids.size() - pos);
            if (eval.process(ids, pos, pos + n) != 0) {
                pp_test::die("the second context could not evaluate");
            }
            pos += n;
        }
        held = ids;
        return llama_get_logits_ith(eval.context(), -1);
    }

    int32_t vocab_size() const { return eval.vocab_size(); }

  private:
    pp_test::model_ptr model;
    pp_test::evaluator eval;
    std::vector<llama_token> held;
};

// What the penalties are computed from at one step.
struct coverage {
    std::vector<llama_token> repetition; // each token in here is penalised once
    std::vector<llama_token> additive;   // the tokens the presence and frequency penalties count
};

struct choice {
    llama_token token = LLAMA_TOKEN_NULL;
    double lead = 0.0; // of its score over the next best
};

// The definitions, written out for this test:
// - the repetition penalty as transformers' RepetitionPenaltyLogitsProcessor has it (and vLLM
//   after it): a score below zero is multiplied by the penalty and any other divided by it,
//   once for a token however often it occurs;
// - the frequency and presence penalties as the OpenAI API defines them and vLLM and SGLang
//   implement them: the score less the frequency penalty times the token's count, less the
//   presence penalty when the count is not zero;
// - a banned token is never chosen.
// The scores after the penalties go to *scores when it is given.
choice expected(const float * raw, int32_t n_vocab, const pp_sampler_config & config, const coverage & c, const std::vector<uint8_t> & banned,
                std::vector<float> * scores = nullptr) {
    std::vector<float> score(raw, raw + n_vocab);
    std::vector<int32_t> count((size_t) n_vocab, 0);
    for (const llama_token t : c.repetition) {
        count[(size_t) t] = 1;
    }
    for (int32_t id = 0; id < n_vocab; id++) {
        if (count[(size_t) id] != 0) {
            score[(size_t) id] = score[(size_t) id] < 0.0f ? score[(size_t) id] * config.repetition_penalty : score[(size_t) id] / config.repetition_penalty;
        }
    }
    std::fill(count.begin(), count.end(), 0);
    for (const llama_token t : c.additive) {
        count[(size_t) t]++;
    }
    for (int32_t id = 0; id < n_vocab; id++) {
        if (count[(size_t) id] != 0) {
            score[(size_t) id] -= (float) count[(size_t) id] * config.frequency_penalty + config.presence_penalty;
        }
    }
    choice best;
    double first = -std::numeric_limits<double>::infinity(), second = first;
    for (int32_t id = 0; id < n_vocab; id++) {
        if (banned[(size_t) id] != 0) {
            continue;
        }
        const double v = score[(size_t) id];
        if (v > first) {
            second = first;
            first = v;
            best.token = id;
        } else if (v > second) {
            second = v;
        }
    }
    best.lead = first - second;
    if (scores != nullptr) {
        scores->swap(score);
    }
    return best;
}

// The session together with the test's own account of what its memory holds, and the ways an
// application has of going back in it: removing the end of the memory where the model allows
// it, otherwise a checkpoint at or before the place and the tokens after it once more,
// otherwise an empty memory and everything once more.
class talk {
  public:
    explicit talk(pp_session * session) : s(session) {}

    std::vector<llama_token> memory;
    bool try_rewind = true; // false: go back through checkpoints even where the end can be removed
    int n_rewound = 0, n_restored = 0, n_cleared = 0, n_saved = 0, n_dropped = 0;

    void clear() {
        if (pp_session_clear_memory(s) != PP_STATUS_OK) {
            pp_test::die(std::string("pp_session_clear_memory failed: ") + pp_session_last_error(s));
        }
        memory.clear();
        marks.clear(); // the session has freed them
    }

    void evaluate(const std::vector<llama_token> & tokens) {
        if (tokens.empty()) {
            return;
        }
        pp_evaluation_observer observer = {[](void *) { return false; }, [](int32_t, void *) {}, nullptr};
        int32_t n = 0;
        if (pp_session_evaluate(s, tokens.data(), (int32_t) tokens.size(), k_chunk, &observer, &n) != PP_STATUS_OK || n != (int32_t) tokens.size()) {
            pp_test::die(std::string("pp_session_evaluate failed: ") + pp_session_last_error(s));
        }
        memory.insert(memory.end(), tokens.begin(), tokens.end());
        if (pp_session_memory_token_count(s) != (int32_t) memory.size()) {
            pp_test::die("the memory does not have the length the test expects");
        }
    }

    void push(llama_token token) { evaluate({token}); }

    // A checkpoint of the memory as it is. The oldest goes when the session holds its fill.
    void mark() {
        if (memory.empty() || (!marks.empty() && marks.back().position == memory.size())) {
            return;
        }
        if (marks.size() >= (size_t) PP_CHECKPOINTS_MAX) {
            pp_session_drop_checkpoint(s, marks.front().handle);
            marks.erase(marks.begin());
            n_dropped++;
        }
        const int32_t handle = pp_session_save_checkpoint(s);
        if (handle < 0) {
            pp_test::die(std::string("pp_session_save_checkpoint failed: ") + pp_session_last_error(s));
        }
        marks.push_back({memory.size(), handle});
        n_saved++;
    }

    void drop_marks_after(size_t position) {
        while (!marks.empty() && marks.back().position > position) {
            pp_session_drop_checkpoint(s, marks.back().handle);
            marks.pop_back();
            n_dropped++;
        }
    }

    void drop_marks() { drop_marks_after(0); }

    // Shortens the memory to its first n tokens. No prediction is ready afterwards for certain.
    void back_to(size_t n) {
        if (n >= memory.size()) {
            return;
        }
        const std::vector<llama_token> before = memory;
        bool removed = false;
        if (try_rewind && n > 0) {
            if (pp_session_rewind_to(s, (int32_t) n, &removed) != PP_STATUS_OK) {
                pp_test::die(std::string("pp_session_rewind_to failed: ") + pp_session_last_error(s));
            }
        }
        if (removed) {
            memory.resize(n);
            drop_marks_after(n);
            n_rewound++;
            return;
        }
        drop_marks_after(n);
        if (!marks.empty() && pp_session_restore_checkpoint(s, marks.back().handle)) {
            memory.resize(marks.back().position);
            n_restored++;
        } else {
            clear();
            n_cleared++;
        }
        evaluate(std::vector<llama_token>(before.begin() + (std::ptrdiff_t) memory.size(), before.begin() + (std::ptrdiff_t) n));
    }

    // The first n tokens of the memory, with the prediction for the token after them ready.
    void ready_at(size_t n) {
        const llama_token last = memory.at(n - 1);
        back_to(n - 1);
        push(last);
    }

    // Makes the memory hold exactly the prompt, with a prediction ready, keeping what it shares
    // with the memory as it is; checkpoints are saved on the way, as an application saves them.
    void bring_to(const std::vector<llama_token> & prompt) {
        // The last prompt token is always evaluated afresh: sampling needs its prediction.
        back_to(std::min(pp_test::common_prefix(memory, prompt), prompt.size() - 1));
        while (memory.size() < prompt.size()) {
            size_t end = (memory.size() / k_stride + 1) * k_stride;
            if (memory.size() < prompt.size() - 1) {
                end = std::min(end, prompt.size() - 1);
            }
            end = std::min(end, prompt.size());
            evaluate(std::vector<llama_token>(prompt.begin() + (std::ptrdiff_t) memory.size(), prompt.begin() + (std::ptrdiff_t) end));
            if (memory.size() < prompt.size()) {
                mark();
            }
        }
    }

    llama_token sample() {
        llama_token token = LLAMA_TOKEN_NULL;
        if (pp_session_sample_next(s, &token) != PP_STATUS_OK) {
            pp_test::die(std::string("pp_session_sample_next failed: ") + pp_session_last_error(s));
        }
        return token;
    }

    void configure(const pp_sampler_config & config) {
        if (pp_session_configure_sampler(s, &config) != PP_STATUS_OK) {
            pp_test::die(std::string("pp_session_configure_sampler failed: ") + pp_session_last_error(s));
        }
    }

  private:
    struct saved {
        size_t  position;
        int32_t handle;
    };

    pp_session * s;
    std::vector<saved> marks; // ascending positions
};

using cover_fn = std::function<coverage(const std::vector<llama_token> & memory)>;

// What became of one run of sampled tokens.
struct verdict {
    std::vector<llama_token> tokens;
    std::vector<double> leads; // of the expected token over the next best, at every step
    int judged = 0;           // steps where the expected token had a clear lead
    int agreed = 0;           // of those, steps where the session chose it
    std::vector<int> telling; // per mistake: judged steps at which the mistake would have chosen another token
    std::string first_miss = "none";

    bool sound(int n_steps) const { return agreed == judged && 2 * judged >= n_steps; }
};

// Samples n tokens from the ready prediction, each evaluated before the next (the last one
// too, so that the memory ends with the run). Before each, the second context scores the same
// memory; `right` says what the penalties must cover there, each of `mistakes` what a wrong
// record would cover.
verdict run(talk & t, oracle & o, const pp_sampler_config & config, const std::vector<uint8_t> & banned, int n, const cover_fn & right,
            const std::vector<cover_fn> & mistakes) {
    verdict v;
    v.telling.assign(mistakes.size(), 0);
    for (int i = 0; i < n; i++) {
        const float * raw = o.after(t.memory);
        const choice want = expected(raw, o.vocab_size(), config, right(t.memory), banned);
        const llama_token got = t.sample();
        v.leads.push_back(want.lead);
        if (want.lead >= k_lead) {
            v.judged++;
            if (got == want.token) {
                v.agreed++;
            } else if (v.first_miss == "none") {
                v.first_miss = "step " + std::to_string(i) + ": token " + std::to_string(got) + " where " + std::to_string(want.token) +
                               " leads by " + std::to_string(want.lead);
            }
            for (size_t k = 0; k < mistakes.size(); k++) {
                const choice other = expected(raw, o.vocab_size(), config, mistakes[k](t.memory), banned);
                v.telling[k] += other.token != want.token && other.lead >= k_lead ? 1 : 0;
            }
        }
        v.tokens.push_back(got);
        t.push(got);
    }
    return v;
}

struct penalty_request {
    std::vector<llama_token> prompt, plain, reply;
    std::vector<float> margin;
    std::vector<int32_t> first_idx;
    std::vector<float> first_logit, first_score;
};

struct penalty_reference {
    std::string tokenizer_sha256;
    float penalty = 1.0f;
    uint32_t max_new_tokens = 0;
    uint32_t top_k = 0;
    std::vector<penalty_request> requests;
};

// A penalty reference (native/tests/refgen/formats.py, "PPPENAL1"). The messages are for people.
penalty_reference load_penalty_reference(const std::string & path) {
    pp_test::reader in(path);
    in.expect_magic("PPPENAL1");
    penalty_reference ref;
    ref.tokenizer_sha256 = in.bytes(64);
    ref.penalty = in.array<float>(1)[0];
    ref.max_new_tokens = in.u32();
    ref.top_k = in.u32();
    ref.requests.resize(in.u32());
    for (penalty_request & q : ref.requests) {
        for (uint32_t n = in.u32(); n > 0; n--) {
            in.sized_bytes();
            in.sized_bytes();
        }
        q.prompt = in.array<llama_token>(in.u32());
        q.plain = in.array<llama_token>(in.u32());
        q.reply = in.array<llama_token>(in.u32());
        q.margin = in.array<float>(q.reply.size());
        q.first_idx = in.array<int32_t>(ref.top_k);
        q.first_logit = in.array<float>(ref.top_k);
        q.first_score = in.array<float>(ref.top_k);
    }
    if (!in.at_end()) {
        pp_test::die(path + " has trailing bytes");
    }
    return ref;
}

bool contains(const std::vector<llama_token> & tokens, llama_token token) {
    return std::find(tokens.begin(), tokens.end(), token) != tokens.end();
}

} // namespace

int main(int argc, char ** argv) {
    std::string gguf, ref_path, penalty_ref_path;
    for (int i = 1; i + 1 < argc; i += 2) {
        const std::string key = argv[i];
        if (key == "--gguf") {
            gguf = argv[i + 1];
        } else if (key == "--ref") {
            ref_path = argv[i + 1];
        } else if (key == "--penalty-ref") {
            penalty_ref_path = argv[i + 1];
        }
    }
    if (gguf.empty()) {
        std::fprintf(stderr, "usage: %s --gguf FILE [--ref FILE] [--penalty-ref FILE]\n", argv[0]);
        return 2;
    }

    report r;
    if (pp_runtime_init(nullptr, 0) != PP_STATUS_OK) {
        pp_test::die("pp_runtime_init failed");
    }

    session_ptr session;
    {
        char error[256];
        pp_session * opened = nullptr;
        pp_session_params params = {};
        params.n_ctx     = pp_test::k_n_ctx;
        params.context_budget_bytes = (int64_t) 2 << 30; // a limit that is stated, not memory that is taken
        params.n_batch   = pp_test::k_n_batch;
        params.n_threads = pp_test::n_threads();
        params.load_mode = PP_LOAD_MODE_READ;
        if (pp_session_open_path(gguf.c_str(), &params, &opened, error, sizeof(error)) != PP_STATUS_OK) {
            pp_test::die(std::string("the model does not open: ") + error);
        }
        session.reset(opened);
    }
    pp_session * s = session.get();
    // The second context. llama.cpp's log goes where pp_runtime_init has sent it, so loading the
    // model once more puts nothing into the test's output.
    oracle o(gguf);
    const int32_t n_vocab = pp_session_vocab_size(s);

    pp_test::model_ptr vocab_model = pp_test::load_model(gguf, true);
    const llama_vocab * vocab = llama_model_get_vocab(vocab_model.get());
    const std::string arch = pp_test::meta(vocab_model.get(), "general.architecture");
    const bool recurrent = llama_model_is_hybrid(vocab_model.get()) || llama_model_is_recurrent(vocab_model.get());
    r.note("model: architecture '%s', %s memory, %d tokens in the vocabulary", arch.c_str(), recurrent ? "attention plus recurrent state" : "attention-only", n_vocab);

    // --- The prompts, as in test_session, and what is banned ---

    std::vector<std::vector<llama_token>> prompts;
    std::vector<llama_token> app_banned; // what the app bans for this model
    if (!ref_path.empty()) {
        const pp_test::reference ref = pp_test::load_reference(ref_path);
        for (const pp_test::sequence & q : ref.seqs) {
            prompts.emplace_back(q.ids.begin(), q.ids.begin() + q.n_prompt);
        }
        int32_t n_suppress = 0;
        const llama_token * suppress = llama_vocab_get_suppress_tokens(vocab, &n_suppress);
        app_banned.assign(suppress, suppress + n_suppress);
    } else if (arch == "qwen35") {
        for (const char * question : {"What is the capital of Australia?", "Name three colours of the rainbow."}) {
            prompts.push_back(tokenize(s, std::string("<|im_start|>system\nYou are a helpful assistant.<|im_end|>\n<|im_start|>user\n") + question +
                                          "<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n", true));
        }
    } else {
        for (const char * question : {"What is the capital of France?", "Name three colours of the rainbow."}) {
            prompts.push_back(tokenize(s, std::string("<|im_start|>user\n") + question + "<|im_end|>\n<|im_start|>assistant\n", false));
        }
    }
    if (prompts.size() < 2 || prompts[0].size() < 8 || prompts[1].size() < 8 || prompts[0] == prompts[1]) {
        pp_test::die("the test needs two different prompts of some length");
    }
    const std::vector<llama_token> & a = prompts[0];
    const std::vector<llama_token> & b = prompts[1];
    const size_t n_a = a.size();

    // In the runs below nothing may end a reply: every sampled token is evaluated, and a run
    // has to have its length. So the tokens that end one are banned with the app's own, as is
    // the token named as the end of a turn in the file, and the end-of-text token that Boris-2
    // cannot take as input is among the first.
    std::vector<llama_token> run_banned = app_banned;
    for (llama_token id = 0; id < n_vocab; id++) {
        if (pp_session_is_end_of_generation(s, id)) {
            run_banned.push_back(id);
        }
    }
    const std::string end_token = pp_test::meta(vocab_model.get(), arch + ".chat.end_token_id");
    if (!end_token.empty()) {
        run_banned.push_back((llama_token) std::atoi(end_token.c_str()));
    }
    const auto flags_of = [&](const std::vector<llama_token> & list) {
        std::vector<uint8_t> flags((size_t) n_vocab, 0);
        for (const llama_token token : list) {
            flags[(size_t) token] = 1;
        }
        return flags;
    };
    const std::vector<uint8_t> run_flags = flags_of(run_banned);

    const auto greedy = [&](const std::vector<llama_token> & banned, float repetition, float presence, float frequency) {
        pp_sampler_config config = {};
        config.temperature        = 0.0f;
        config.top_p              = 1.0f;
        config.repetition_penalty = repetition;
        config.presence_penalty   = presence;
        config.frequency_penalty  = frequency;
        config.penalty_window     = PP_PENALTY_NO_WINDOW; // as the engine sets it
        config.reply_start        = PP_REPLY_START_AT_SAMPLING; // the engine's value outside "Continue"
        config.banned_tokens      = banned.data();
        config.n_banned_tokens    = (int32_t) banned.size();
        return config;
    };

    talk t(s);
    const cover_fn nothing = [](const std::vector<llama_token> &) { return coverage(); };

    // --- The instrument ---

    // The prompt with the beginning of its reply, which is what an application sends to have a
    // message continued, for the checks of what a penalty covers. It is cut where they have
    // something to decide: before a token that the prompt, so extended, already holds, and
    // that the model prefers to the next best one by less than a penalty takes from it. A
    // penalty that reaches the prompt and one that does not then part at their first step.
    std::vector<llama_token> begun = a;
    {
        const pp_sampler_config config = greedy(run_banned, 1.0f, 0.0f, 0.0f);
        t.bring_to(a);
        t.configure(config);
        const verdict v = run(t, o, config, run_flags, 2 * k_steps, nothing, {});
        r.check(v.sound(2 * k_steps), "without penalties the session chooses the token a second context on the same file scores highest: "
                "%d of the %d choices with a clear lead, in %d steps after a prompt of %zu tokens (first miss: %s)",
                v.agreed, v.judged, 2 * k_steps, n_a, v.first_miss.c_str());
        size_t cut = k_steps / 2;
        for (size_t j = 1; j < v.tokens.size(); j++) {
            const std::vector<llama_token> earlier = joined(a, std::vector<llama_token>(v.tokens.begin(), v.tokens.begin() + (std::ptrdiff_t) j));
            if (contains(earlier, v.tokens[j]) && v.leads[j] >= 2.0 * k_lead && v.leads[j] < 0.9) {
                cut = j;
                break;
            }
        }
        begun.insert(begun.end(), v.tokens.begin(), v.tokens.begin() + (std::ptrdiff_t) cut);
        r.note("the prompt of the checks below is that prompt with the first %zu tokens of this reply: the token after them leads by %.2f and %s",
               cut, v.leads[cut], contains(begun, v.tokens[cut]) ? "is in that prompt already" : "is not in that prompt (no such place in the reply)");
    }
    const size_t n_begun = begun.size();

    // --- The repetition penalty: every token in memory ---

    {
        int telling = 0;
        for (const float repetition : {1.1f, 1.6f}) {
            const pp_sampler_config config = greedy(run_banned, repetition, 0.0f, 0.0f);
            t.bring_to(begun);
            t.configure(config);
            const verdict v = run(t, o, config, run_flags, k_steps,
                                  [](const std::vector<llama_token> & m) { return coverage{m, {}}; },
                                  {[&](const std::vector<llama_token> & m) { return coverage{from(m, n_begun), {}}; }});
            telling += v.telling[0];
            r.check(v.sound(k_steps), "a repetition penalty of %.1f covers prompt and reply: %d of %d judged choices are the definition's over the whole memory; "
                    "at %d of them a penalty over the reply alone would have chosen another token (first miss: %s)",
                    (double) repetition, v.agreed, v.judged, v.telling[0], v.first_miss.c_str());
        }
        r.check(telling >= 1, "these runs can tell a penalty over the whole memory from one over the reply alone: %d steps do", telling);
    }

    // --- The presence and frequency penalties: the reply, and no token of the prompt ---

    std::vector<llama_token> presence_reply;
    {
        struct setting {
            float repetition, presence, frequency;
        };
        // The first is what Qwen3.5's model card asks for; the last has all three at once.
        const setting settings[] = {{1.0f, 2.0f, 0.0f}, {1.0f, 0.5f, 0.5f}, {1.1f, 1.5f, 0.3f}};
        int telling = 0, telling_first = 0;
        for (const setting & p : settings) {
            const pp_sampler_config config = greedy(run_banned, p.repetition, p.presence, p.frequency);
            t.bring_to(begun);
            t.configure(config);
            const verdict v = run(t, o, config, run_flags, k_steps,
                                  [&](const std::vector<llama_token> & m) { return coverage{m, from(m, n_begun)}; },
                                  {[](const std::vector<llama_token> & m) { return coverage{m, m}; }});
            telling += v.telling[0];
            r.check(v.sound(k_steps), "repetition %.1f, presence %.1f, frequency %.1f: %d of %d judged choices are the definition's with presence and frequency "
                    "over the reply alone; at %d of them counting the prompt as well would have chosen another token (first miss: %s)",
                    (double) p.repetition, (double) p.presence, (double) p.frequency, v.agreed, v.judged, v.telling[0], v.first_miss.c_str());
            if (&p == &settings[0]) {
                presence_reply = v.tokens;
                telling_first = v.telling[0];
            }
        }
        r.check(telling_first >= 1, "these runs can tell presence and frequency over the reply from the same over prompt and reply: %d steps do, "
                "%d of them with the presence penalty of %.1f alone", telling, telling_first, (double) settings[0].presence);

        // The reply begins where sampling begins, not where the sampler was configured: here
        // it is configured while the memory still holds the last run, before the memory is
        // cut back and the end of the prompt evaluated again.
        const pp_sampler_config config = greedy(run_banned, 1.0f, 2.0f, 0.0f);
        t.configure(config);
        t.bring_to(begun);
        const verdict v = run(t, o, config, run_flags, k_steps, [&](const std::vector<llama_token> & m) { return coverage{m, from(m, n_begun)}; }, {});
        r.check(v.sound(k_steps) && v.tokens == presence_reply, "configured before the memory was brought to the prompt: the same %zu tokens as when configured after it (%d of %d judged)",
                v.tokens.size(), v.agreed, v.judged);
    }

    // --- A reply that continues an answer: the penalties count the whole answer ---

    {
        // "Continue": the memory holds the prompt and the beginning of its answer (`begun`),
        // and a new generation goes on from there. Configured with reply_start at the length of
        // the prompt, the presence and frequency penalties count the whole answer. The mistake
        // they must be told from is counting from where this sampling began, which is what
        // PP_REPLY_START_AT_SAMPLING does, as all of the runs above.
        struct setting {
            float repetition, presence, frequency;
        };
        const setting settings[] = {{1.0f, 2.0f, 0.0f}, {1.0f, 0.5f, 0.5f}, {1.1f, 1.5f, 0.3f}};
        const cover_fn right = [&](const std::vector<llama_token> & m) { return coverage{m, from(m, n_a)}; };
        const cover_fn from_sampling = [&](const std::vector<llama_token> & m) { return coverage{m, from(m, n_begun)}; };
        int telling = 0, telling_again = 0;
        bool all_sound = true, all_again = true;
        std::string misses;
        for (const setting & p : settings) {
            pp_sampler_config config = greedy(run_banned, p.repetition, p.presence, p.frequency);
            config.reply_start = (int32_t) n_a;
            t.bring_to(begun);
            t.configure(config);
            const verdict v = run(t, o, config, run_flags, k_steps, right, {from_sampling});
            telling += v.telling[0];
            all_sound = all_sound && v.sound(k_steps);

            // Back to where the generation began, through a checkpoint, with the sampler left as
            // it is: the configured beginning of the answer still holds, and the same tokens
            // follow.
            t.try_rewind = false;
            const int restored = t.n_restored;
            t.ready_at(n_begun);
            const verdict again = run(t, o, config, run_flags, k_steps, right, {from_sampling});
            t.try_rewind = true;
            telling_again += again.telling[0];
            all_again = all_again && again.sound(k_steps) && again.tokens == v.tokens && t.n_restored > restored;
            misses += (misses.empty() ? "" : ", ") + v.first_miss + " / " + again.first_miss;
        }
        r.check(all_sound && telling >= 1,
                "configured with reply_start at the end of the prompt (%zu) after %zu tokens of the answer: presence and frequency count the whole answer at every judged "
                "step; at %d steps counting from where sampling began would have chosen another token (first misses: %s)",
                n_a, n_begun - n_a, telling, misses.c_str());
        r.check(all_again && telling_again >= 1,
                "back to where the generation began through a checkpoint, sampler untouched: the same tokens again, the definition's at every judged step; "
                "%d steps tell the two beginnings apart", telling_again);

        // Below -1 is refused, and the sampler configured before stays.
        pp_sampler_config bad = greedy(run_banned, 1.0f, 2.0f, 0.0f);
        bad.reply_start = -2;
        const int32_t refused = pp_session_configure_sampler(s, &bad);
        bad.reply_start = std::numeric_limits<int32_t>::min();
        const int32_t refused_min = pp_session_configure_sampler(s, &bad);
        t.ready_at(t.memory.size());
        llama_token token = LLAMA_TOKEN_NULL;
        const int32_t sampled = pp_session_sample_next(s, &token);
        if (sampled == PP_STATUS_OK) {
            t.push(token);
        }
        r.check(refused == PP_STATUS_INVALID_ARGUMENT && refused_min == PP_STATUS_INVALID_ARGUMENT && sampled == PP_STATUS_OK,
                "reply_start -2 and INT32_MIN: INVALID_ARGUMENT (%d, %d), and the sampler configured before still samples (%d)", refused, refused_min, sampled);
    }

    // --- Going back in the memory, with the sampler left as it is ---

    {
        const pp_sampler_config config = greedy(run_banned, 1.6f, 1.0f, 0.0f);
        const int kept = k_steps / 2; // tokens of the reply that stay when the test goes back into it
        const cover_fn right = [&](const std::vector<llama_token> & m) { return coverage{m, from(m, n_a)}; };
        for (const bool try_rewind : {true, false}) {
            if (try_rewind && recurrent) {
                continue; // such a model has one way back
            }
            t.try_rewind = try_rewind;
            const int rewound = t.n_rewound, restored = t.n_restored;
            // A reply of k_steps tokens with a checkpoint saved one token before `kept`, which
            // is where the way back into the reply leads.
            const auto reply_with_mark = [&](const std::vector<cover_fn> & mistakes) {
                verdict v = run(t, o, config, run_flags, kept - 1, right, mistakes);
                t.mark();
                const verdict rest = run(t, o, config, run_flags, k_steps - (kept - 1), right, mistakes);
                v.tokens = joined(v.tokens, rest.tokens);
                v.judged += rest.judged;
                v.agreed += rest.agreed;
                for (size_t k = 0; k < mistakes.size(); k++) {
                    v.telling[k] += rest.telling[k];
                }
                v.first_miss = v.first_miss == "none" ? rest.first_miss : v.first_miss;
                return v;
            };
            t.bring_to(a);
            t.configure(config);
            const verdict first = reply_with_mark({});
            const std::vector<llama_token> reply = first.tokens;

            // Back to the end of the prompt. What was sampled is no longer in memory and must
            // no longer count: the same reply again, which a record that still held the old
            // one would not give, because every token of it would be penalised.
            t.ready_at(n_a);
            const verdict again = reply_with_mark({[&](const std::vector<llama_token> & m) { return coverage{joined(m, reply), joined(from(m, n_a), reply)}; }});
            r.check(first.sound(k_steps) && again.sound(k_steps) && again.tokens == reply && again.telling[0] >= 1,
                    "back to the end of the prompt by %s, sampler untouched: the same %zu tokens again, %d of %d judged choices the definition's; "
                    "at %d of them a record that still held the removed reply would have chosen another token (first miss: %s)",
                    try_rewind ? "removing the end of the memory" : "a checkpoint", reply.size(), again.agreed, again.judged, again.telling[0], again.first_miss.c_str());

            // Back into the reply. Its first tokens are still the reply and still count; the
            // ones after the cut do not.
            const std::vector<llama_token> removed = from(reply, (size_t) kept);
            t.ready_at(n_a + (size_t) kept);
            const verdict tail = run(t, o, config, run_flags, k_steps - kept, right,
                                     {[&](const std::vector<llama_token> & m) { return coverage{joined(m, removed), joined(from(m, n_a), removed)}; },
                                      [&](const std::vector<llama_token> & m) { return coverage{m, from(m, n_a + (size_t) kept)}; }});
            r.check(tail.sound(k_steps - kept) && tail.tokens == removed && tail.telling[0] >= 1,
                    "back into the reply after %d of its tokens: the same %zu tokens follow again, %d of %d judged choices the definition's; "
                    "a record that still held the removed tokens would have differed at %d, one that began the reply anew at the cut at %d (first miss: %s)",
                    kept, removed.size(), tail.agreed, tail.judged, tail.telling[0], tail.telling[1], tail.first_miss.c_str());

            // Checkpoints are not part of what the penalties see: with all of them dropped the
            // reply goes on as the definition says.
            t.drop_marks();
            const verdict on = run(t, o, config, run_flags, 4, right, {});
            r.check(on.sound(4), "every checkpoint dropped in the middle of the reply: %d of %d judged choices are the definition's (first miss: %s)",
                    on.agreed, on.judged, on.first_miss.c_str());
            r.note("this used %d removal(s) of the end of the memory and %d checkpoint(s) restored", t.n_rewound - rewound, t.n_restored - restored);
        }
        t.try_rewind = true;
    }

    // --- Forgetting, with the sampler left as it is ---

    {
        const pp_sampler_config config = greedy(run_banned, 1.6f, 1.0f, 0.0f);
        t.bring_to(a);
        t.configure(config);
        const verdict before = run(t, o, config, run_flags, k_steps, [&](const std::vector<llama_token> & m) { return coverage{m, from(m, n_a)}; }, {});
        const std::vector<llama_token> old = t.memory;
        t.clear();
        t.bring_to(b);
        const size_t n_b = b.size();
        const verdict v = run(t, o, config, run_flags, k_steps,
                              [&](const std::vector<llama_token> & m) { return coverage{m, from(m, n_b)}; },
                              {[&](const std::vector<llama_token> & m) { return coverage{joined(old, m), joined(from(old, n_a), from(m, n_b))}; }});
        r.check(before.sound(k_steps) && v.sound(k_steps) && v.telling[0] >= 1,
                "after a clear and another prompt of %zu tokens: %d of %d judged choices are the definition's over the new memory alone; "
                "at %d of them a record that still held the forgotten conversation would have chosen another token (first miss: %s)",
                n_b, v.agreed, v.judged, v.telling[0], v.first_miss.c_str());
    }

    // --- An evaluation that was stopped half way ---

    {
        // What a stop button does to a prompt: the call is cancelled once its first chunk is
        // done, here all of the prompt but its last token, and a second call evaluates the
        // rest. The tokens the cancelled call left in the memory are in it like any others.
        const pp_sampler_config config = greedy(run_banned, 1.6f, 0.0f, 0.0f);
        t.clear();
        int32_t reported = 0, n_in_memory = 0;
        const pp_evaluation_observer observer = {[](void * data) { return *(int32_t *) data > 0; },
                                                 [](int32_t n, void * data) { *(int32_t *) data = n; }, &reported};
        const int32_t status = pp_session_evaluate(s, begun.data(), (int32_t) n_begun, (int32_t) n_begun - 1, &observer, &n_in_memory);
        const bool stopped = status == PP_STATUS_OK && n_in_memory == (int32_t) n_begun - 1 && pp_session_memory_token_count(s) == n_in_memory;
        const size_t n_first = (size_t) std::max(0, n_in_memory);
        t.memory.assign(begun.begin(), begun.begin() + (std::ptrdiff_t) std::min(n_first, n_begun));
        t.evaluate(from(begun, t.memory.size()));
        t.configure(config);
        const verdict v = run(t, o, config, run_flags, k_steps,
                              [](const std::vector<llama_token> & m) { return coverage{m, {}}; },
                              {[&](const std::vector<llama_token> & m) { return coverage{from(m, n_first), {}}; }});
        r.check(stopped && v.sound(k_steps) && v.telling[0] >= 1,
                "a prompt of %zu tokens whose evaluation was cancelled with %d of them in memory and finished by a second call: %d of %d judged choices are the "
                "definition's over the whole memory; at %d of them a record without the tokens of the cancelled call would have chosen another token (first miss: %s)",
                n_begun, n_in_memory, v.agreed, v.judged, v.telling[0], v.first_miss.c_str());
    }

    // --- The window ---

    {
        pp_sampler_config config = greedy(run_banned, 1.6f, 1.0f, 0.0f);
        config.penalty_window = 8;
        const size_t window = (size_t) config.penalty_window;
        t.bring_to(a);
        t.configure(config);
        const verdict v = run(t, o, config, run_flags, k_steps,
                              [&](const std::vector<llama_token> & m) {
                                  const size_t first = m.size() > window ? m.size() - window : 0;
                                  return coverage{from(m, first), from(m, std::max(first, n_a))};
                              },
                              {[&](const std::vector<llama_token> & m) { return coverage{m, from(m, n_a)}; }});
        r.check(v.sound(k_steps) && v.telling[0] >= 1,
                "a window of %zu tokens: %d of %d judged choices are the definition's over the last %zu tokens of the memory; "
                "at %d of them the whole memory would have chosen another token (first miss: %s)",
                window, v.agreed, v.judged, window, v.telling[0], v.first_miss.c_str());
    }

    // --- The reference: transformers' generate() with the model's repetition penalty ---

    if (!penalty_ref_path.empty()) {
        const penalty_reference ref = load_penalty_reference(penalty_ref_path);
        r.check(ref.tokenizer_sha256 == pp_test::meta(vocab_model.get(), arch + ".tokenizer.source_sha256"),
                "the penalty reference was generated from this model's tokenizer file");

        // What the file itself says: where a reply with the penalty first leaves the reply
        // without it, the token without it is one of the prompt that the reply has not had.
        // A penalty over the reply alone leaves that token its score and so chooses it.
        size_t n_telling = 0, n_first = 0;
        for (const penalty_request & q : ref.requests) {
            const size_t d = pp_test::common_prefix(q.plain, q.reply);
            if (d < q.plain.size() && d < q.reply.size() && contains(q.prompt, q.plain[d]) &&
                !contains(std::vector<llama_token>(q.reply.begin(), q.reply.begin() + (std::ptrdiff_t) d), q.plain[d])) {
                n_telling++;
                n_first += d == 0 ? 1 : 0;
            }
        }
        r.check(n_telling >= 1 && n_first >= 1, "the reference tells the two coverages apart: in %zu of %zu requests the reply with a penalty of %.2f first differs from "
                "the reply without it at a token that only the prompt holds, in %zu of them at the first token of the reply",
                n_telling, ref.requests.size(), (double) ref.penalty, n_first);

        // The requests, from one memory. The session is given the reference's token after each
        // choice, so that one close call does not hide what comes after it.
        t.clear();
        const int rewound = t.n_rewound, restored = t.n_restored, cleared = t.n_cleared;
        const pp_sampler_config config = greedy(app_banned, ref.penalty, 0.0f, 0.0f);
        size_t n_tokens = 0, n_judged = 0, n_agreed = 0, n_close = 0, n_close_agreed = 0, n_reused = 0, n_prompt = 0;
        std::string first_miss = "none";
        for (size_t k = 0; k < ref.requests.size(); k++) {
            const penalty_request & q = ref.requests[k];
            n_reused += std::min(pp_test::common_prefix(t.memory, q.prompt), q.prompt.size() - 1);
            n_prompt += q.prompt.size();
            t.bring_to(q.prompt);
            t.configure(config);
            for (size_t i = 0; i < q.reply.size(); i++) {
                const llama_token got = t.sample();
                n_tokens++;
                if ((double) q.margin[i] >= k_ref_lead) {
                    n_judged++;
                    n_agreed += got == q.reply[i] ? 1 : 0;
                    if (got != q.reply[i] && first_miss == "none") {
                        first_miss = "request " + std::to_string(k) + ", reply token " + std::to_string(i) + ": " + std::to_string(got) + " where the reference has " +
                                     std::to_string(q.reply[i]) + " with a lead of " + std::to_string(q.margin[i]);
                    }
                } else {
                    n_close++;
                    n_close_agreed += got == q.reply[i] ? 1 : 0;
                }
                // As the engine leaves it: the last token of a reply is not evaluated.
                if (i + 1 < q.reply.size()) {
                    t.push(q.reply[i]);
                }
            }
        }
        r.check(n_agreed == n_judged && 4 * n_judged >= 3 * n_tokens,
                "%zu requests of one conversation, greedy with a repetition penalty of %.2f: %zu of the %zu reply tokens that the reference chose with a lead of %.2f "
                "or more are the session's choice too, and %zu of the %zu closer calls (first miss: %s)",
                ref.requests.size(), (double) ref.penalty, n_agreed, n_judged, k_ref_lead, n_close_agreed, n_close, first_miss.c_str());
        r.check(t.n_restored + t.n_rewound > restored + rewound && t.n_cleared > cleared && n_reused > 0,
                "the memory across the requests: %zu of %zu prompt tokens were still in it; %d removal(s) of its end, %d checkpoint(s) restored, %d time(s) started over",
                n_reused, n_prompt, t.n_rewound - rewound, t.n_restored - restored, t.n_cleared - cleared);

        // The first position of each reply, where the reply is empty: whatever the penalty
        // changes there, it changes because of the prompt.
        double worst_logit = 0.0, worst_score = 0.0, worst_formula = 0.0;
        size_t n_ranks = 0, n_ranks_agreed = 0, n_penalised = 0, n_listed = 0, n_reordered = 0, n_plain_first = 0, n_plain_first_agreed = 0;
        bool only_the_prompt = true;
        std::string first_rank_miss = "none";
        for (size_t k = 0; k < ref.requests.size(); k++) {
            const penalty_request & q = ref.requests[k];
            // The numbers: the reference's logits against the second context's, and its scores
            // after the penalty against the definition as written here, applied to those.
            t.bring_to(q.prompt);
            const float * raw = o.after(q.prompt);
            std::vector<float> scores;
            expected(raw, n_vocab, config, coverage{q.prompt, {}}, flags_of(app_banned), &scores);
            for (uint32_t j = 0; j < ref.top_k; j++) {
                const llama_token id = q.first_idx[j];
                const bool in_prompt = contains(q.prompt, id);
                worst_logit = std::max(worst_logit, std::fabs((double) raw[id] - (double) q.first_logit[j]));
                worst_score = std::max(worst_score, std::fabs((double) scores[(size_t) id] - (double) q.first_score[j]));
                // And within the file: a token's score differs from its logit exactly when the
                // prompt holds the token, and then by the definition.
                const float by_definition = !in_prompt ? q.first_logit[j] : q.first_logit[j] < 0.0f ? q.first_logit[j] * ref.penalty : q.first_logit[j] / ref.penalty;
                worst_formula = std::max(worst_formula, std::fabs((double) by_definition - (double) q.first_score[j]));
                only_the_prompt = only_the_prompt && ((q.first_score[j] != q.first_logit[j]) == in_prompt || q.first_logit[j] == 0.0f);
                n_listed++;
                n_penalised += in_prompt ? 1 : 0;
            }

            // The session's choices in order: the first, then the first with that one banned,
            // and so on, for as long as the reference's scores are clearly apart.
            std::vector<llama_token> taken = app_banned;
            size_t ranks = 0;
            while (ranks + 1 < ref.top_k && ranks < 8 && (double) (q.first_score[ranks] - q.first_score[ranks + 1]) >= k_ref_lead) {
                t.ready_at(q.prompt.size());
                t.configure(greedy(taken, ref.penalty, 0.0f, 0.0f));
                const llama_token got = t.sample();
                n_ranks++;
                n_ranks_agreed += got == q.first_idx[ranks] ? 1 : 0;
                if (got != q.first_idx[ranks] && first_rank_miss == "none") {
                    first_rank_miss = "request " + std::to_string(k) + ", choice " + std::to_string(ranks) + ": " + std::to_string(got) + " where the reference has " +
                                      std::to_string(q.first_idx[ranks]);
                }
                taken.push_back(q.first_idx[ranks]);
                ranks++;
            }
            // Would the logits before the penalty have put these tokens in another order?
            for (size_t j = 0; j + 1 < ranks; j++) {
                n_reordered += q.first_logit[j] < q.first_logit[j + 1] ? 1 : 0;
            }

            // And the first choice with the penalty switched off, where the reference has another one then.
            if (q.plain[0] != q.reply[0]) {
                t.ready_at(q.prompt.size());
                t.configure(greedy(app_banned, 1.0f, 0.0f, 0.0f));
                n_plain_first++;
                n_plain_first_agreed += t.sample() == q.plain[0] ? 1 : 0;
            }
        }
        r.check(worst_logit <= k_ref_logit && worst_score <= k_ref_logit && worst_formula <= 1e-5 && only_the_prompt && n_penalised >= 1,
                "the first position of %zu replies, the %u highest scores each: the logits are within %.3f of the reference's (limit %.2f) and, with the definition "
                "applied to the prompt's tokens, within %.3f of its scores after the penalty; in the reference %zu of these %zu tokens are in the prompt and exactly "
                "those have another score than logit, the definition's to within %.1e",
                ref.requests.size(), ref.top_k, worst_logit, k_ref_logit, worst_score, n_penalised, n_listed, worst_formula);
        r.check(n_ranks_agreed == n_ranks && n_ranks >= 2 * ref.requests.size() && n_reordered >= 1,
                "the session's first choices at those positions, each found by banning the ones before it: %zu of %zu are the reference's, in its order after the "
                "penalty; %zu neighbours among them stand the other way round before it (first miss: %s)",
                n_ranks_agreed, n_ranks, n_reordered, first_rank_miss.c_str());
        r.check(n_plain_first >= 1 && n_plain_first_agreed == n_plain_first,
                "with the penalty switched off the first token is the reference's without a penalty, another one than with it: %zu of %zu replies",
                n_plain_first_agreed, n_plain_first);
    }

    return r.finish();
}
