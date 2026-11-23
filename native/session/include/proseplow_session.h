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
// - In one process, contexts are created from one thread at a time only: no two of
//   pp_session_open_path, pp_session_open_fd, pp_session_resize_context and
//   pp_session_clear_memory may run at once, on the same session or on different ones. llama.cpp
//   has one log callback for the whole process, and a session reads from it the cost of each
//   context it creates and the description of a failed open; two creations at once would mix
//   their lines (the result is memory-safe, but a cost or an error text could be another
//   creation's). The allocator's count of what a creation allocated (pp_session_context_bytes)
//   is process-wide for the same reason. The Kotlin engine has one session and serialises its
//   calls.
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
    PP_STATUS_CONTEXT_REFUSED   = 9, // a context size was refused: above what the model was trained for, its
                                     // computed cost above the budget, or its cost not known (see each function)
    PP_STATUS_CONTEXT_NOT_CREATED = 10, // a context of the asked size could not be created after the old one
                                     // was freed; pp_session_context_size says what the session has instead
};

// The limits on sizes that arrive from outside. llama.cpp takes a size as given and allocates
// for it at once (52 bytes of bookkeeping per context token before any buffer is requested), so
// a value that is merely wrong, a setting read from damaged storage for instance, would
// otherwise end the process inside the call instead of coming back as a status.

// The largest batch a session is opened with, in tokens: the most that one llama_process call
// evaluates. The compute buffers are reserved for a whole batch (the scores of every token of
// it: the batch times the vocabulary times four bytes), so this is the size that must not be
// taken as given.
//
// The context has no such constant. Its ceiling is the model's own, the context it was trained
// for (pp_session_max_context_size: 2,048 tokens for Boris-2, 32,768 for peacebell, 262,144
// for Qwen3.5), and below that ceiling a size is held to a budget in bytes that the caller
// states: the session computes what a context of the size costs before it allocates anything
// and refuses a size that costs more (pp_session_context_bytes, pp_session_resize_context).
#define PP_BATCH_TOKENS_MAX 32768

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
    int32_t n_ctx;      // the context the session opens with, in tokens; at least 1. More than the model was
                        // trained for is taken as that. It is also the size pp_session_clear_memory returns
                        // to. Read the real value back with pp_session_context_size: llama.cpp rounds it up
    int64_t context_budget_bytes; // the most bytes the context may cost (see pp_session_context_bytes); at
                        // least 1. An opening size that costs more is refused: PP_STATUS_CONTEXT_REFUSED.
                        // INT64_MAX states that there is no limit (see pp_session_open_path)
    int32_t n_batch;    // the most tokens evaluated by one llama_process call (and one micro-batch); from 1 to
                        // PP_BATCH_TOKENS_MAX. More than the opening context n_ctx is taken as n_ctx, for
                        // the life of the session: a context it grows to later keeps that batch
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
// pp_session_params, before the file is touched. Returns PP_STATUS_CONTEXT_REFUSED, with the
// model freed again and nothing left allocated, when the opening context costs more than the
// budget, and when its cost cannot be known (llama.cpp's report was not understood, see
// pp_session_context_bytes) while the budget is below INT64_MAX. With a budget of INT64_MAX the
// caller states no limit: such a session opens although its cost is unknown, and every
// pp_session_resize_context of it is then refused.
//
// What a context costs is only known from llama.cpp's report of one, so a session creates a
// small context first and frees it (the larger of one batch and an eighth of the opening size,
// the eighth at most four batches, rounded up to 256 tokens, when that is less than the opening
// size): the one allocation that is made before any cost is known. The budget is then checked
// twice, and the promise is the pair of checks, not the first alone:
// - before the opening context is allocated, against an estimate from that one small context.
//   With one size known, the growth of the compute reservation and of llama.cpp's bookkeeping
//   between it and the opening size is not known yet and counted as nothing, so this estimate
//   can be below the true cost; a budget below it is refused with nothing more allocated;
// - after the opening context exists, against its cost from its own report (two sizes are known
//   then). A budget that the first check let through and this one does not is refused, and the
//   context is freed again. A session too small for the first context has only this check.
// So the opening context can exist for a moment although the budget does not cover it: by at
// most that growth, which is small beside the context itself (the attention mask and about 52
// bytes per token).
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

// The number of tokens the memory can hold now: llama_n_ctx of the context there is. It changes
// with pp_session_resize_context and goes back to the opening size with
// pp_session_clear_memory. 0 for a session that has no context (see both).
int32_t pp_session_context_size(const struct pp_session * session);

// The most tokens a context of this model can have: the context it was trained for, from the
// model file (llama_model_n_ctx_train).
int32_t pp_session_max_context_size(const struct pp_session * session);

// What a context of n_ctx tokens costs in bytes, at the batch this session was opened with:
// what llama.cpp allocates for it (the key-value buffers, which are zeroed when they are
// created and so resident at once; the recurrent state; the compute and output buffers; and
// its own bookkeeping), and the session's record of the tokens. It is an upper bound on what
// becomes resident, because the compute buffers and the bookkeeping are reserved for the worst
// case and mostly never touched; the difference between two sizes, which is nearly all
// key-value buffer, is what a caller should budget with.
//
// The figures are llama.cpp's own, read from the lines in which it reports each buffer it
// allocated for a context of this session ("... buffer size = 48.00 MiB"), and only from
// contexts created at the session's batch (pp_session_params.n_batch): a context smaller than
// the batch has a smaller compute reservation, and its report is not used at all. A size below
// the batch is therefore costed as if it had the whole batch: too high, never too low. The
// figures are the key-value bytes per token from the largest context created so far; the
// compute reservation from two contexts of different sizes, the two farthest apart, growing
// in a straight line between and beyond them and taken at the smaller's figure below it; the
// other buffers llama.cpp reports (the recurrent state, the output), the largest figure seen;
// and the unreported part below. Nothing is derived from the architecture.
//
// While only one size has been created (a session opened at about one batch, which makes no
// small first context: see pp_session_open_path), what the compute reservation and the
// unreported part grow by is not known, and a larger size is costed as if both grew in
// proportion to the size: several times too high, never too low. The first larger context
// the session creates (within a budget that covers that figure) gives the second size, and the
// figures are close from then on. Beyond the largest size created, the straight line is
// continued, and what grows can grow faster there (the compute reservation is the largest of
// several buffers, and a faster growing one can take over): what a size beyond adds to the
// cost is counted 2% higher. That is a margin, measured (without it the cost was up to 0.3%
// short at a model's largest context), not a bound.
//
// The unreported part is what llama.cpp allocates for a context without reporting it (the
// scheduler's and the cells' bookkeeping). It is measured as the allocator's count of bytes
// in use (mallinfo) after llama_init_from_model less the count before, less the buffers
// reported. That count is process-wide: whatever another thread allocates or frees during the
// creation is in it. The figure is a fixed part plus a part per token: the part per token is
// fitted from the first two sizes created (the small first context and the opening context, one
// straight after the other), never less than nothing; the fixed part is the largest that any
// creation since has shown (its measurement less the part per token times its size), and never
// less than the largest measurement of all; before a second size exists it is that largest
// measurement, in proportion to the size as above. A later
// creation can therefore raise the figure, never lower it: memory freed elsewhere during a
// creation makes that measurement smaller and is ignored, memory allocated elsewhere makes it
// larger and is kept. Its error goes towards costing more, by what other threads allocated
// during a creation, with one exception: the part per token rests on two measurements and is
// lower than llama.cpp's real bookkeeping (about 52 bytes per token) when other threads freed
// memory during the second of them. Where the allocator cannot be asked (neither glibc nor
// Android) the figure is 0. Not included at all: the sets in which llama.cpp records which
// cells are in use, which grow by roughly 90 bytes per token in memory while a conversation
// runs.
//
// Returns -1 for a size below 1 or above pp_session_max_context_size, and when the report was
// not understood (its form is llama.cpp's to change): no size is costed then, and none can be
// changed to.
int64_t pp_session_context_bytes(const struct pp_session * session, int32_t n_ctx);

// The bytes of the block that carrying the present memory over to another context would hold
// beside a context (see pp_session_resize_context): llama_state_seq_get_size_ext for the whole
// sequence. 0 for an empty memory and for a session without a context. The session does this
// arithmetic itself; the number is here for a caller that wants to know the peak beforehand,
// and for the tests of that arithmetic.
int64_t pp_session_carry_bytes(const struct pp_session * session);

// Gives the session a context of n_ctx tokens while the model stays loaded.
//
// budget_bytes is the most bytes the session's context memory may occupy at any moment during
// and after the call, in the terms of pp_session_context_bytes. What happens is the best of
// three that fits it:
//
// - The memory is carried over. Its sequence state (llama_state_seq_get_data_ext, every cell
//   in use and the recurrent state) is written into one block; the old context is overwritten
//   and freed as at a clear; the new one is created; the block is read into it, overwritten and
//   freed. The peak is that block beside a context: beside the old one while it is written and
//   beside the new one while it is read, so the larger of the two contexts plus the block
//   (about the tokens in memory times the key-value bytes per token, plus the recurrent state).
//   Afterwards pp_session_memory_token_count is what it was, every checkpoint is still valid,
//   and the penalties cover what they covered. This needs the memory to fit the new size.
// - The memory comes back empty, when the block does not fit the budget beside a context or
//   could not be written or read: exactly what pp_session_clear_memory does, at the new size.
//   Every checkpoint handle is unknown and the sampler begins again.
// - The size is refused, when it is above pp_session_max_context_size, when its cost is above
//   the budget, or when the cost is not known: PP_STATUS_CONTEXT_REFUSED, and nothing was
//   touched. The memory, the checkpoints and a ready prediction are as they were.
//
// Which of the first two happened is what pp_session_memory_token_count says afterwards. No
// prediction is ready after either: evaluate at least one token before sampling, as after a
// restored checkpoint. llama.cpp makes every context a whole number of 256 tokens, so a size
// is rounded up to one before it is costed or compared.
//
// The checks are made in this order, and the first that applies answers: a size or budget
// below 1 (PP_STATUS_INVALID_ARGUMENT); a size above pp_session_max_context_size, a cost that
// is not known, a cost above the budget (each PP_STATUS_CONTEXT_REFUSED); and only then a size
// that rounds to the present one, which is nothing to do and changes nothing, a ready
// prediction included (PP_STATUS_OK). So asking for the present size with a budget below its
// cost is refused, not answered OK.
//
// When the new context cannot be created although its cost fits the budget, the session tries
// the size it had, then halves of that down to 256 tokens, each only if it fits the budget, and
// answers PP_STATUS_CONTEXT_NOT_CREATED: pp_session_context_size says which exists, and 0
// means none (the session is then as after a failed pp_session_clear_memory). A memory that
// was being carried over is put into whichever of those sizes was created when it fits there
// (the size it had, or a half that still holds every token in memory), with its checkpoints
// and penalties as after a carry-over; otherwise it is gone, as after a clear.
//
// When the memory did not come through (it was not carried, or it was lost in the fall-back)
// the sampler is built again as at a clear. If that fails the answer is
// PP_STATUS_OUT_OF_MEMORY, whatever became of the size: the session then has no sampler and
// sampling fails until pp_session_configure_sampler or pp_session_clear_memory succeeds;
// pp_session_context_size says which size exists, and pp_session_last_error names both
// failures when the size was not as asked either.
//
// What is and is not promised about failing. A size is costed and held to the budget before
// anything is allocated, and before llama.cpp is asked for a context the session maps and
// unmaps the whole cost once, untouched, so a size the address space has no room for is
// reported and never reaches llama.cpp. That matters because llama.cpp reports a key-value or
// compute buffer it could not allocate (the creation fails and this function falls back), but
// ggml ends the process when one of its own bookkeeping buffers cannot be had (GGML_ASSERT in
// ggml_init): between the test and the allocation another thread can take the room, and then
// that remains possible. And on a system that hands out address space without memory behind
// it, Android among them, an allocation does not fail at all: the process is ended while the
// buffer is zeroed. The budget is the protection there, and it is the caller's to derive from
// the memory the system has free.
//
// Returns PP_STATUS_INVALID_ARGUMENT for a size or a budget below 1; the others as above.
int32_t pp_session_resize_context(struct pp_session * session, int32_t n_ctx, int64_t budget_bytes);

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
// least as long: the restored state belongs to those tokens, and they are the tokens the
// penalties then take the memory to hold (see pp_session_configure_sampler). Returns true when
// the memory now has the saved length; false for an unknown handle or when either call failed
// (clear the memory then). The checkpoint stays valid.
bool pp_session_restore_checkpoint(struct pp_session * session, int32_t checkpoint);

// Zeroes and frees a checkpoint. An unknown handle is ignored.
void pp_session_drop_checkpoint(struct pp_session * session, int32_t checkpoint);

// The size of a checkpoint's saved state in bytes, or -1 for an unknown handle. For diagnostics.
int64_t pp_session_checkpoint_size(const struct pp_session * session, int32_t checkpoint);

// Forgets the conversation: this is what runs when the app locks. Afterwards the memory is
// empty, every checkpoint handle is unknown, no prediction is ready, the model is still loaded
// and the sampler is still configured as it was: its random numbers start where they started,
// and no reply has begun. The context has the size the session was opened with, whatever it
// had grown to: the memory a long conversation needed is given back with the conversation.
//
// What is done, in this order:
// - every checkpoint is overwritten with zeros and freed;
// - the scores of the last prediction and the session's own copy of them are overwritten;
// - the sampler chain is freed (it holds no token of a conversation);
// - the token buffer of the batch is overwritten, and the batch is freed;
// - llama_memory_clear(mem, true) overwrites the key-value and state buffers of the context,
//   and the context is freed with everything it owns;
// - the session's record of the tokens in memory, which the penalties are computed from, is
//   overwritten and emptied;
// - a new context of the opening size, a new batch and a new sampler chain are created from
//   the parameters the first ones were created with (the thread pool is kept: it holds
//   nothing of a conversation);
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
    float    repetition_penalty;  // 1 is neutral; covers every token in memory
    float    presence_penalty;    // 0 is neutral; covers the tokens of the reply
    float    frequency_penalty;   // 0 is neutral; covers the tokens of the reply
    int32_t  penalty_window;      // how many of the latest tokens in memory the penalties look at: at least 1, or
                                  // PP_PENALTY_NO_WINDOW for the whole memory, which is what the definitions
                                  // below have. 0 and every other negative value are refused
    int32_t  reply_start;         // where the reply began, for the presence and frequency penalties:
                                  // PP_REPLY_START_AT_SAMPLING (-1) for where the first token is sampled after
                                  // this call, or the position in memory (0 or more) at which the answer began,
                                  // for a reply that continues an answer already in memory. Below -1 is refused.
                                  // Mind that a zero-initialised struct says position 0: the whole memory
    uint32_t seed;
    const llama_token * banned_tokens; // tokens that must never be sampled; may be NULL when n_banned_tokens is 0
    int32_t  n_banned_tokens;     // at most the size of the vocabulary
};

// No window for the penalties: llama.cpp's own value for "the whole context". Named and not a
// number of tokens, because the context of a session can change (pp_session_resize_context).
#define PP_PENALTY_NO_WINDOW (-1)

// The reply begins where the first token is sampled after the sampler is configured: see
// pp_sampler_config.reply_start.
#define PP_REPLY_START_AT_SAMPLING (-1)

// The lowest temperature that is used as one. The scores are divided by the temperature: a
// value this side of a millionth turns their differences into infinities, and the "choice"
// among them is then whatever the arithmetic leaves (the last token of the vocabulary, for a
// denormal). Greedy decoding is what such a temperature means, so it is what it gets.
#define PP_TEMPERATURE_MIN 1e-6f

// Replaces the sampler. A token is then sampled in exactly this order:
//   1. the penalties, computed by the session (nothing is done when all three are neutral);
//   2. llama_sampler_init_logit_bias with minus infinity for every banned token (skipped when
//      there is none);
//   3. for a temperature of PP_TEMPERATURE_MIN or more: llama_sampler_init_temp, then top_k,
//      top_p and min_p (each skipped when disabled), then llama_sampler_init_dist(seed);
//   4. otherwise llama_sampler_init_greedy alone.
// Penalties and banned tokens act on the raw scores, the temperature comes next and the
// truncations see the distribution after it: the order of the models' reference
// implementations, not llama.cpp's default order.
//
// What the penalties cover. Each covers what it covers where the settings of the models come
// from, and those are two different things:
// - The repetition penalty covers every token in memory, the prompt as much as the reply, and a
//   token once however often it occurs: a score below zero is multiplied by the penalty, any
//   other divided by it. This is the repetition_penalty of the transformers library
//   (RepetitionPenaltyLogitsProcessor, which is shown the whole sequence), the meaning of that
//   name in a model's generation_config.json; vLLM computes the same over prompt and output.
// - The presence and frequency penalties cover the tokens of the reply and no token of the
//   prompt: the score less frequency_penalty times the number of times the token occurs in the
//   reply, less presence_penalty when that number is not zero. This is the definition of the
//   OpenAI API, which vLLM and SGLang, the servers Qwen3.5's model card names, implement by
//   counting the tokens of the output.
// llama.cpp's own penalties do neither: they are computed from the tokens its sampler was told
// of, which in this library were the sampled ones, so a repetition penalty did not reach the
// prompt; in llama.cpp's server they are prompt and reply for all three alike.
//
// The session computes them from its own record of the tokens in memory, so they follow the
// memory: a token is covered from the moment it is evaluated, whoever chose it, and no longer
// once it is removed (pp_session_rewind_to, pp_session_restore_checkpoint,
// pp_session_clear_memory). The reply is what the memory holds from reply_start on: with
// PP_REPLY_START_AT_SAMPLING, from the length the memory had when the first token was sampled
// after this call; with a position, from that position, so that a reply which continues an
// answer already in memory ("Continue": a new generation after a partial assistant message)
// counts the whole answer and not only what is sampled now. A position beyond the memory counts
// nothing until the memory reaches it, and a window (penalty_window) still limits what is
// counted to the latest tokens. pp_session_clear_memory, and a pp_session_resize_context that
// empties the memory, end the reply: the next one begins where sampling begins, whatever
// position was configured, because the memory it was a position in is gone. Configure the
// sampler once for every reply, before its first token is sampled.
//
// Returns PP_STATUS_INVALID_ARGUMENT for a value outside the ranges above (reply_start below -1
// among them), a number that is not finite, more banned tokens than the vocabulary has, or a banned token outside the
// vocabulary; the previous sampler is kept in that case.
int32_t pp_session_configure_sampler(struct pp_session * session, const struct pp_sampler_config * config);

// Chooses the next token from the prediction left by the last complete pp_session_evaluate:
// what llama_sampler_sample(chain, ctx, -1) does, step by step (the scores into a candidate
// list, the penalties, llama_sampler_apply, llama_sampler_accept), so that the candidate list
// is the session's own and is overwritten before the function returns, as are the scores
// themselves. The last sampler of the chain, the one that chooses, is applied on its own after
// the others, and only when there is something to choose from. The chosen token is not
// evaluated, and it counts for the penalties only once it is: pass it to pp_session_evaluate
// before the next token is sampled, as a reply needs anyway. Valid once per complete
// evaluation.
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
