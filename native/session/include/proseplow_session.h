// The generation session: one loaded model with one context holding one conversation.
//
// This is the native half of the seam that the Kotlin engine drives (the NativeLlama interface
// in the core module), call for call. The rule of that seam is "thin native, smart Kotlin": a
// session exposes llama.cpp operations and decides nothing. It never renders a prompt, never
// looks for a stop string, never trims or shifts the context, never chooses a sampling default
// and never retries. Plain C, no JNI, nothing host- or Android-specific: the Android bridge and
// the host tests are both thin callers of these functions.
//
// It links libllama and proseplow_native and nothing else; llama.cpp's helper library, which
// carries a network client, is never used.
//
// Rules for every function in this file:
// - Calls on one session are made one at a time, never concurrently. Callbacks are made on the
//   calling thread, before the call returns.
// - "Memory" is the context's memory for sequence 0. Its length in tokens is the only position
//   the caller and the session share; evaluation always appends at the end.
// - No function logs, prints or stores prompt text, token ids or pieces. llama.cpp's own log is
//   switched off for the whole process by pp_runtime_init.
// - The buffers this library allocates itself for text, tokens, predictions or model state are
//   overwritten with zeros before they are freed. That is NOT true of llama.cpp's: it copies the
//   text it tokenises and the tokens it evaluates into allocations of its own and frees them as
//   they are. What that leaves in the process is stated at pp_session_clear_memory, and nothing
//   in this library should be read as a promise beyond that statement.
// - A size that arrives from outside is checked against a limit before anything is allocated
//   for it, and a value beyond its limit is answered with a status: the limits are the PP_..._MAX
//   constants below, the model's trained context and the size of the vocabulary.
// - No C++ exception leaves a function. Functions that can fail return a pp_status; the ones
//   that fill a caller's buffer use the convention of proseplow_native.h (the count written, the
//   negative of the size needed when the buffer is too small, PP_ERROR when the call failed).
// - After a failure the caller should assume nothing about the memory and clear it.
//   pp_session_last_error describes the failure in a few technical words that never contain
//   text from a conversation.

#ifndef PROSEPLOW_SESSION_H
#define PROSEPLOW_SESSION_H

#include "proseplow_native.h"

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

// The values are the codes of the Kotlin NativeLlamaException, one for one. They are part of the
// contract with the Kotlin side: never renumber them.
enum pp_status {
    PP_STATUS_OK                = 0,
    PP_STATUS_LOAD_FAILED       = 1, // the file is missing, unreadable or not a model llama.cpp accepts
    PP_STATUS_LOAD_CANCELLED    = 2, // the progress callback returned false
    PP_STATUS_TOKENIZE_FAILED   = 3,
    PP_STATUS_CONTEXT_FULL      = 4, // llama_process found no room in the memory (its return value 1)
    PP_STATUS_EVALUATION_FAILED = 5, // llama_process failed for any other reason
    PP_STATUS_SAMPLING_FAILED   = 6,
    PP_STATUS_OUT_OF_MEMORY     = 7,
    PP_STATUS_INVALID_ARGUMENT  = 8, // the caller broke a documented precondition
};

// The limits on sizes that arrive from outside. llama.cpp takes a size as given and allocates
// for it at once (52 bytes of bookkeeping per context token before any buffer is requested), so
// a value that is merely wrong, a setting read from damaged storage for instance, would
// otherwise end the process inside the call instead of coming back as a status.

// The largest context a session opens, in tokens. A session also refuses a context above the
// one its model was trained for; this ceiling is for the model whose trained context is beyond
// anything a phone can hold (Qwen3.5: 262144 tokens). It is the largest trained context among
// the models that fit one, eight times what the app runs, and about 2 GiB of memory for a
// model of the bundled kind (some 64 KiB per token).
#define PP_CONTEXT_TOKENS_MAX 32768

// The most checkpoints a session holds at a time. One is megabytes for a model with recurrent
// state (20 MB for Boris-2); the engine keeps four.
#define PP_CHECKPOINTS_MAX 8

// The longest text pp_session_tokenize accepts, in bytes (256 KiB). Tokenising cannot be
// interrupted and takes time in proportion to the text, during which a lock has to wait. At
// this length the slowest text measured on a desktop processor took 0.7 seconds (Qwen3.5, a text
// made of nothing but chat markers) and ordinary prose 0.05; a phone takes several times that.
// It is more text than the largest context holds at eight bytes per token, where ordinary text
// has three to four.
#define PP_TOKENIZE_BYTES_MAX 262144

//
// The process
//

// Prepares llama.cpp for this process. Call it before anything else; calling it again does
// nothing and returns the first call's result.
//
// - Switches llama.cpp's and ggml's logging off for the life of the process, so that no prompt
//   text can reach a log however llama.cpp is built.
// - Pins the numeric locale to "C": llama.cpp parses and prints numbers with the C library.
// - Loads the compute backend. When the backends are linked into the program, as in the host
//   build, there is nothing to choose: pass (NULL, 0). When the CPU backend is built once per
//   instruction set as separate libraries, as on Android, backend_libraries names all of
//   them, and the one that scores highest on this processor (ggml_backend_score: 0 means "this
//   processor lacks an instruction I need") is loaded with ggml_backend_load. A name is
//   whatever the dynamic loader accepts: a path, or a bare file name that it finds on the
//   program's library search path. The second matters on Android, where an app's libraries
//   usually stay inside its APK: there is then no directory to list, which is all that ggml's
//   own ggml_backend_load_all_from_path can do, but the loader finds them by name all the same.
//
// Returns PP_STATUS_LOAD_FAILED when no backend could be loaded: no model can be opened then.
// The backend stays loaded until the process ends; there is nothing to free.
int32_t pp_runtime_init(const char * const * backend_libraries, int32_t n_backend_libraries);

// What pp_runtime_init found, for diagnostics: the backend library that was chosen (its file
// name says which CPU variant) and the processor features the backend reports. A static string,
// empty before pp_runtime_init. It describes the machine, never a conversation.
const char * pp_runtime_description(void);

//
// Opening and closing
//

// How the weights get into memory.
enum pp_load_mode {
    // Read into allocated memory. Works for a model at any offset of any file.
    PP_LOAD_MODE_READ = 0,
    // Map the file. The weights are then clean file-backed pages, which the system can drop and
    // read again under memory pressure instead of swapping. llama.cpp maps the WHOLE containing
    // file from its start and asks for all of it to be read ahead, so this suits a file that is
    // little more than the model. The model's data must sit at a multiple of 32 bytes in the
    // file (any model that starts on a page boundary does); otherwise the load fails.
    PP_LOAD_MODE_MMAP = 1,
};

// Called during a load with the fraction done, from 0 to 1. Return false to abandon the load.
typedef bool (*pp_load_progress_fn)(float fraction, void * user_data);

// Everything about a session that is fixed when it is opened. There are no defaults: every
// field is the caller's decision. The model always runs on the CPU (n_gpu_layers = 0 and an
// empty device list), whatever backends are loaded.
//
// Threads. On a phone the difference between the right and the wrong threads is a factor of
// five: the scheduler parks big cores, and a pool of threads that wait for each other by
// spinning stalls as soon as two of them share a core. The remedy is one thread on each big
// core and nowhere else, which is what pinned_cpus asks for. It is not always allowed: Android
// confines a process to a set of cores that depends on whether its app is in front, and moves
// it between those sets at any time, which also discards every affinity set before. A session
// therefore looks at the calling thread's affinity before every chunk of an evaluation; when it
// is not what the session left there, it finds out what the system allows now and rebuilds its
// thread pool: pinned, one thread per listed CPU, when every listed CPU is allowed, and
// otherwise n_threads threads without any affinity. Keep n_threads below the number of big
// cores, so that threads which cannot be pinned never outnumber the cores they compete for.
// One thread should make all the calls of a session, as the Kotlin engine does: an evaluation
// from another thread rebuilds the pool. Where threads cannot be pinned at all (no such system
// call, or it is refused), a session simply runs n_threads threads.
struct pp_session_params {
    int32_t n_ctx;      // the context in tokens; from 1 to PP_CONTEXT_TOKENS_MAX and at most the model's trained
                        // context. Read the real value back with pp_session_context_size: llama.cpp rounds it up
    int32_t n_batch;    // the most tokens evaluated by one llama_process call (and one micro-batch); from 1 to
                        // PP_CONTEXT_TOKENS_MAX. More than n_ctx is taken as n_ctx
    int32_t n_threads;  // threads for evaluation when they are not pinned; at least 1
    const int32_t * pinned_cpus; // CPU numbers to run one pinned thread each; NULL when n_pinned_cpus is 0
    int32_t n_pinned_cpus;       // 0: never pin
    enum pp_load_mode   load_mode;
    pp_load_progress_fn progress;  // may be NULL
    void *              progress_user_data;
};

struct pp_session;

// Loads the model file at path and creates its context.
//
// On success *session is the new session and the result is PP_STATUS_OK. On failure *session is
// NULL, nothing stays allocated, and error (when not NULL) receives a short NUL-terminated
// description of at most error_size bytes.
//
// Returns PP_STATUS_INVALID_ARGUMENT for parameters outside the ranges stated at
// pp_session_params. A context or batch above PP_CONTEXT_TOKENS_MAX is refused before the file
// is touched; a context above the model's trained one is refused once the model is loaded and
// says so, before any context is created.
int32_t pp_session_open_path(
        const char * path,
        const struct pp_session_params * params,
        struct pp_session ** session,
        char * error,
        size_t error_size);

// The same for a model embedded in a larger file: length bytes at offset of the open file fd
// (what Android's AssetManager.openFd gives for an asset stored without compression). The
// descriptor is duplicated: the caller keeps ownership of fd and may close it as soon as this
// function returns, in either load mode.
//
// The length is held to: before anything is loaded the GGUF header at offset is read, and the
// load is refused with PP_STATUS_LOAD_FAILED unless the header and every tensor it lists end
// within offset + length. llama.cpp itself reads a tensor wherever the header says it is, which
// for a model cut short inside a larger file is whatever follows it there.
int32_t pp_session_open_fd(
        int fd,
        int64_t offset,
        int64_t length,
        const struct pp_session_params * params,
        struct pp_session ** session,
        char * error,
        size_t error_size);

// Frees everything: the sampler, the checkpoints, the context and the model, after overwriting
// what pp_session_clear_memory overwrites, and then asks the allocator to give its free pages
// back to the system. What is said there about llama.cpp's own freed copies holds here too.
// NULL is ignored. The session must not be used afterwards.
void pp_session_close(struct pp_session * session);

// The description of the most recent failure of a call on this session; empty when none failed
// yet. Valid until the next call on the session.
const char * pp_session_last_error(const struct pp_session * session);

// How the evaluation threads were set up by the latest evaluation: their number, and whether
// each is pinned to a CPU of its own. Before the first evaluation: n_threads and false.
void pp_session_thread_setup(const struct pp_session * session, int32_t * n_threads, bool * pinned);

//
// Tokens
//

// Tokenises one piece of text on its own. No beginning-of-text, end-of-text or other token is
// added. With parse_special false, text that spells a control token comes back as the tokens of
// its characters; with true it becomes the control token.
//
// The tokenizer is chosen by the model file: a peacebell model with the SentencePiece tokenizer
// goes through the front-end of proseplow_native.h (parse_special selects its exact mode, plain
// text its safe mode), which reproduces the reference tokenizer; every other model goes through
// llama_tokenize with add_special = false.
//
// Returns the number of tokens written, the negative of the number needed when n_tokens_max is
// too small (so (NULL, 0) measures), or PP_ERROR when the text cannot be tokenised.
//
// Text of more than PP_TOKENIZE_BYTES_MAX bytes is refused with PP_ERROR (the status behind it
// is PP_STATUS_TOKENIZE_FAILED) before any of it is looked at. A limit, and not tokenising in
// pieces with a way to stop in between: where a piece ends changes the tokens on both sides of
// the cut, so pieces would only be right where the session knew that no token can span the
// cut, which is the tokenizer's knowledge and different for every model; and this call has
// nobody to ask whether to stop.
int32_t pp_session_tokenize(
        struct pp_session * session,
        const char * text,
        int32_t text_len,
        bool parse_special,
        llama_token * tokens,
        int32_t n_tokens_max);

// The bytes a token contributes to the output text: llama_token_to_piece with lstrip = 0 and
// special = false, so a control token gives no bytes. The bytes are raw: a token can end in the
// middle of a UTF-8 character. Returns the number of bytes written, the negative of the number
// needed, or PP_ERROR for a token id outside the vocabulary.
int32_t pp_session_token_to_bytes(
        struct pp_session * session,
        llama_token token,
        char * buffer,
        int32_t buffer_size);

// Whether the model ends its turn with this token (llama_vocab_is_eog).
bool pp_session_is_end_of_generation(const struct pp_session * session, llama_token token);

// The number of tokens in the vocabulary.
int32_t pp_session_vocab_size(const struct pp_session * session);

//
// Memory
//

// The number of tokens the memory can hold: llama_n_ctx of the created context. 0 for a session
// that has no context (see pp_session_clear_memory).
int32_t pp_session_context_size(const struct pp_session * session);

// The number of tokens in memory: llama_memory_seq_pos_max + 1, which is 0 when it is empty,
// as it also is for a session that has no context.
int32_t pp_session_memory_token_count(const struct pp_session * session);

// The two callbacks of pp_session_evaluate. Both are required.
struct pp_evaluation_observer {
    // True when the evaluation should stop as soon as it can. Polled before every chunk and,
    // for models that allow it (see pp_session_evaluate), repeatedly while a chunk is computed.
    // Once it has returned true it is not called again during that evaluation.
    bool (*is_cancelled)(void * user_data);
    // Called after each completed chunk with the number of tokens of this call evaluated so far.
    void (*on_progress)(int32_t n_evaluated, void * user_data);
    void * user_data;
};

// Evaluates tokens, appended in order after the tokens in memory, and leaves the model's
// prediction for the token after the last one ready for pp_session_sample_next.
//
// The tokens are processed in chunks of at most chunk_size (and at most n_batch), each with
// explicit positions and one llama_process call; output is requested for the last token of the
// call only.
//
// *n_in_memory receives the number of tokens of this call that are in memory when the function
// returns, computed from the memory's own length: n_tokens after a complete run, fewer after a
// cancellation or a failure. A prediction is ready only after a complete run. The scores of the
// prediction before it are overwritten when the call begins, whatever becomes of the call.
//
// Before every chunk the session looks at the calling thread's affinity and refits its thread
// pool when the system has moved the process to other cores (see pp_session_params), so a long
// evaluation follows such a move at the next chunk and not only at the next call.
//
// Cancellation is not a failure: the result is PP_STATUS_OK with *n_in_memory below n_tokens.
// For a model whose memory is attention only, a chunk that is being computed is interrupted
// through llama.cpp's abort callback and removed from the memory again. A model with recurrent
// state (llama_model_is_hybrid or llama_model_is_recurrent: Qwen3.5, Boris-2) cannot take an
// interrupted chunk back out of its state, which would leave the memory unusable; for such a
// model the callback is polled between chunks only, so a cancellation takes effect when the
// current chunk is done.
//
// Returns PP_STATUS_CONTEXT_FULL when the memory has no room (the memory is then as it was
// before the failing chunk), PP_STATUS_EVALUATION_FAILED for any other failure of
// llama_process, and PP_STATUS_INVALID_ARGUMENT for no tokens, a chunk size below 1, a token id
// outside the vocabulary or a missing callback. A Boris-2 model also answers
// PP_STATUS_INVALID_ARGUMENT, with the memory untouched, when the tokens contain its
// end-of-text token: that model's graph ends the process on such an input, so the token is
// stopped here (it ends a reply and is never part of a prompt).
int32_t pp_session_evaluate(
        struct pp_session * session,
        const llama_token * tokens,
        int32_t n_tokens,
        int32_t chunk_size,
        const struct pp_evaluation_observer * observer,
        int32_t * n_in_memory);

// Shortens the memory to its first n_tokens tokens: llama_memory_seq_rm(mem, 0, n_tokens, -1).
//
// *removed receives that call's own answer. False means this model cannot remove the end of its
// memory (models with recurrent state can only be cleared or put back to a checkpoint); the
// memory is then exactly as it was, and nothing else is tried. n_tokens equal to the present
// length removes nothing and answers true. A prediction that was ready is gone after a removal.
// Checkpoints are not touched. The memory is never shifted: llama_memory_seq_add and
// llama_memory_seq_div are not called anywhere in this library.
//
// One case is answered here and not by llama.cpp. A model with windowed layers (Shard-2) keeps
// the keys of those layers from some position on: from the first, until a checkpoint is
// restored, which brings back the window before its own end and nothing older. llama.cpp cuts
// such a memory at any length and says it did, also where the tokens evaluated next would look
// for keys that are gone. *removed is false for such a cut and the memory is untouched: the
// caller restores a checkpoint at or before n_tokens, or clears, exactly as for a model with
// recurrent state. A cut the windowed layers can serve is made as usual.
//
// Returns PP_STATUS_INVALID_ARGUMENT when n_tokens is negative or beyond the present length.
int32_t pp_session_rewind_to(struct pp_session * session, int32_t n_tokens, bool * removed);

// Saves what is needed to bring the memory back to its present length later, in native memory:
// llama_state_seq_get_data_ext with LLAMA_STATE_SEQ_FLAGS_PARTIAL_ONLY, together with the
// present length. An attention-only model writes next to nothing, a model with recurrent state
// writes that state, and a model with windowed layers the window of those layers (megabytes
// once the memory is longer than the window). Returns a handle, non-negative and never reused while the session lives,
// or -1 when the state could not be saved. A session holds at most PP_CHECKPOINTS_MAX
// checkpoints: with that many the answer is -1 and nothing is saved, until one is dropped.
int32_t pp_session_save_checkpoint(struct pp_session * session);

// Brings the memory back to the length it had when the checkpoint was saved:
// llama_state_seq_set_data_ext with the same flag, then llama_memory_seq_rm from that length.
// The caller guarantees that the memory still begins with the tokens it held then and is at
// least as long. Returns true when the memory now has the saved length; false for an unknown
// handle or when either call failed (clear the memory then). The checkpoint stays valid.
bool pp_session_restore_checkpoint(struct pp_session * session, int32_t checkpoint);

// Zeroes and frees a checkpoint. An unknown handle is ignored.
void pp_session_drop_checkpoint(struct pp_session * session, int32_t checkpoint);

// The size of a checkpoint's saved state in bytes, or -1 for an unknown handle. For diagnostics.
int64_t pp_session_checkpoint_size(const struct pp_session * session, int32_t checkpoint);

// Forgets the conversation: this is what runs when the app locks. Afterwards the memory is
// empty, every checkpoint handle is unknown, no prediction is ready, the model is still loaded
// and the sampler is still configured as it was, having seen no tokens.
//
// What is done, in this order:
// - every checkpoint is overwritten with zeros and freed;
// - the scores of the last prediction and the session's own copy of them are overwritten;
// - the sampler chain's record of the sampled tokens is overwritten, and the chain is freed;
// - the token buffer of the batch is overwritten, and the batch is freed;
// - llama_memory_clear(mem, true) overwrites the key-value and state buffers of the context,
//   and the context is freed with everything it owns;
// - a new context, a new batch and a new sampler chain are created from the parameters the old
//   ones were created with (the thread pool is kept: it holds nothing of a conversation);
// - the allocator is asked to give its free pages back to the system (malloc_trim with glibc,
//   mallopt(M_PURGE_ALL) on Android), which empties those pages.
//
// What this does NOT do, and no caller should assume: it does not overwrite the memory that
// llama.cpp has freed without overwriting it. llama.cpp copies the text it is given to
// tokenise, several times over, and the tokens of every chunk it evaluates, and frees each
// copy as it is. Such a copy stays readable in the process until the allocator hands the block
// out again or gives its page back, which the last step above only achieves for pages that
// are free as a whole. Measured after this function returned, with glibc on a desktop and with
// Android's allocator on a phone: stretches of a prompt's text were still in the heap for two
// of three models on the desktop and two of four on the phone, and stretches of its token ids
// for up to two of the three and for all four; which, and how much, depends on what the
// allocator did with the blocks (native/README.md, "Forgetting", has the figures and names
// the allocations). Only the end of the process removes them for certain.
//
// Returns PP_STATUS_OK, or PP_STATUS_OUT_OF_MEMORY when the new context or the new sampler
// chain could not be created. The conversation is gone in that case too, but the session is
// not whole: without a context every call that needs one answers PP_STATUS_OUT_OF_MEMORY (or
// -1, false, 0 where there is no status), and without a sampler sampling fails, until another
// pp_session_clear_memory has succeeded. Close the session when it does not.
int32_t pp_session_clear_memory(struct pp_session * session);

//
// Sampling
//

// The sampler, fully resolved: no field means "use a default".
struct pp_sampler_config {
    float    temperature;         // below PP_TEMPERATURE_MIN (0 and negative values included) selects greedy decoding
    int32_t  top_k;               // 0 disables it
    float    top_p;               // 1 or more disables it
    float    min_p;               // 0 or less disables it
    float    repetition_penalty;  // 1 is neutral
    float    presence_penalty;    // 0 is neutral
    float    frequency_penalty;   // 0 is neutral
    int32_t  penalty_window;      // how many of the latest sampled tokens the penalties see; at least 1. More than
                                  // the context holds is taken as the context size: there are no more tokens to see
    uint32_t seed;
    const llama_token * banned_tokens; // tokens that must never be sampled; may be NULL when n_banned_tokens is 0
    int32_t  n_banned_tokens;     // at most the size of the vocabulary
};

// The lowest temperature that is used as one. The scores are divided by the temperature: a
// value this side of a millionth turns their differences into infinities, and the "choice"
// among them is then whatever the arithmetic leaves (the last token of the vocabulary, for a
// denormal). Greedy decoding is what such a temperature means, so it is what it gets.
#define PP_TEMPERATURE_MIN 1e-6f

// Replaces the sampler with a new chain, in exactly this order:
//   1. llama_sampler_init_logit_bias with minus infinity for every banned token (skipped when
//      there is none);
//   2. llama_sampler_init_penalties (skipped when all three penalties are neutral);
//   3. for a temperature of PP_TEMPERATURE_MIN or more: llama_sampler_init_temp, then top_k,
//      top_p and min_p (each skipped when disabled), then llama_sampler_init_dist(seed);
//   4. otherwise llama_sampler_init_greedy alone.
// Banned tokens and penalties act on the raw scores, the temperature comes next and the
// truncations see the distribution after it: the order of the models' reference
// implementations, not llama.cpp's default order. The new chain has seen no tokens.
//
// Returns PP_STATUS_INVALID_ARGUMENT for a value outside the ranges above, a number that is not
// finite, more banned tokens than the vocabulary has, or a banned token outside the
// vocabulary; the previous sampler is kept in that case.
int32_t pp_session_configure_sampler(struct pp_session * session, const struct pp_sampler_config * config);

// Chooses the next token from the prediction left by the last complete pp_session_evaluate:
// what llama_sampler_sample(chain, ctx, -1) does, step by step (the scores into a candidate
// list, llama_sampler_apply, llama_sampler_accept), so that the candidate list is the
// session's own and is overwritten before the function returns, as are the scores themselves.
// The last sampler of the chain, the one that chooses, is applied on its own after the others,
// and only when there is something to choose from. The chosen token is recorded in the
// sampler. It is not evaluated. Valid once per complete evaluation.
//
// Returns PP_STATUS_SAMPLING_FAILED when no sampler is configured, when no fresh prediction is
// ready, and when nothing is left to choose from after the bans, penalties, temperature and
// truncations: every token ruled out (a ban is a score of minus infinity, and every token can
// be banned), or scores that are no longer numbers. llama.cpp's samplers do not expect either:
// they would name a token all the same, or end the process on an assertion in a build that
// has assertions. No token is returned or recorded then.
int32_t pp_session_sample_next(struct pp_session * session, llama_token * token);

#ifdef __cplusplus
}
#endif

#endif // PROSEPLOW_SESSION_H
