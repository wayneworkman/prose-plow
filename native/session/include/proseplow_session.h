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
//   switched off for the whole process by pp_runtime_init. Buffers of ours that held text,
//   tokens or model state are overwritten with zeros before they are freed.
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
// therefore looks at the calling thread's affinity at the start of every evaluation; when it is
// not what the session left there, it finds out what the system allows now and rebuilds its
// thread pool: pinned, one thread per listed CPU, when every listed CPU is allowed, and
// otherwise n_threads threads without any affinity. Keep n_threads below the number of big
// cores, so that threads which cannot be pinned never outnumber the cores they compete for.
// One thread should make all the calls of a session, as the Kotlin engine does: an evaluation
// from another thread rebuilds the pool. Where threads cannot be pinned at all (no such system
// call, or it is refused), a session simply runs n_threads threads.
struct pp_session_params {
    int32_t n_ctx;      // the context in tokens; at least 1. Read the real value back with pp_session_context_size
    int32_t n_batch;    // the most tokens evaluated by one llama_process call (and one micro-batch); at least 1
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
int32_t pp_session_open_fd(
        int fd,
        int64_t offset,
        int64_t length,
        const struct pp_session_params * params,
        struct pp_session ** session,
        char * error,
        size_t error_size);

// Frees everything: the sampler, the checkpoints (zeroed first), the context and the model.
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

// The number of tokens the memory can hold: llama_n_ctx of the created context.
int32_t pp_session_context_size(const struct pp_session * session);

// The number of tokens in memory: llama_memory_seq_pos_max + 1, which is 0 when it is empty.
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
// cancellation or a failure. A prediction is ready only after a complete run.
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
// Returns PP_STATUS_INVALID_ARGUMENT when n_tokens is negative or beyond the present length.
int32_t pp_session_rewind_to(struct pp_session * session, int32_t n_tokens, bool * removed);

// Saves what is needed to bring the memory back to its present length later, in native memory:
// llama_state_seq_get_data_ext with LLAMA_STATE_SEQ_FLAGS_PARTIAL_ONLY, together with the
// present length. An attention-only model writes next to nothing, a model with recurrent state
// writes that state. Returns a handle, non-negative and never reused while the session lives,
// or -1 when the state could not be saved.
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

// Forgets the conversation completely: llama_memory_clear(mem, true), which also clears the
// data buffers; every checkpoint zeroed and freed; llama_sampler_reset; the last prediction and
// the token buffer of the batch overwritten. Afterwards the memory is empty, the model is still
// loaded and the sampler is still configured.
void pp_session_clear_memory(struct pp_session * session);

//
// Sampling
//

// The sampler, fully resolved: no field means "use a default".
struct pp_sampler_config {
    float    temperature;         // 0 or less selects greedy decoding
    int32_t  top_k;               // 0 disables it
    float    top_p;               // 1 or more disables it
    float    min_p;               // 0 or less disables it
    float    repetition_penalty;  // 1 is neutral
    float    presence_penalty;    // 0 is neutral
    float    frequency_penalty;   // 0 is neutral
    int32_t  penalty_window;      // how many of the latest sampled tokens the penalties see; at least 1
    uint32_t seed;
    const llama_token * banned_tokens; // tokens that must never be sampled; may be NULL when n_banned_tokens is 0
    int32_t  n_banned_tokens;
};

// Replaces the sampler with a new chain, in exactly this order:
//   1. llama_sampler_init_logit_bias with minus infinity for every banned token (skipped when
//      there is none);
//   2. llama_sampler_init_penalties (skipped when all three penalties are neutral);
//   3. for a temperature above 0: llama_sampler_init_temp, then top_k, top_p and min_p (each
//      skipped when disabled), then llama_sampler_init_dist(seed);
//   4. otherwise llama_sampler_init_greedy alone.
// Banned tokens and penalties act on the raw scores, the temperature comes next and the
// truncations see the distribution after it: the order of the models' reference
// implementations, not llama.cpp's default order. The new chain has seen no tokens.
//
// Returns PP_STATUS_INVALID_ARGUMENT for a value outside the ranges above, a number that is not
// finite, or a banned token outside the vocabulary; the previous sampler is kept in that case.
int32_t pp_session_configure_sampler(struct pp_session * session, const struct pp_sampler_config * config);

// Chooses the next token from the prediction left by the last complete pp_session_evaluate:
// llama_sampler_sample(chain, ctx, -1), which also records the token in the sampler. The token
// is not evaluated. Valid once per complete evaluation; returns PP_STATUS_SAMPLING_FAILED when
// no sampler is configured, no fresh prediction is ready, or the sampler produced no token.
int32_t pp_session_sample_next(struct pp_session * session, llama_token * token);

#ifdef __cplusplus
}
#endif

#endif // PROSEPLOW_SESSION_H
