// The JNI binding of the generation session: the native methods of the Kotlin object
// us.theworkmans.proseplow.llama.LlamaJni, each a few lines over one function of
// proseplow_session.h. Nothing is decided here.
//
// Rules this file keeps:
// - No logging, anywhere. A failure travels as an exception with a code; nothing is written to
//   logcat, so no prompt text can get there by accident.
// - Text and tokens cross as primitive arrays, copied with Get/Set...ArrayRegion. JNI strings
//   are never used for text coming in (they are "modified UTF-8", which encodes characters
//   outside the basic plane differently), and no array is ever pinned: there is no
//   Get...ArrayCritical in this file, so no garbage collection is blocked while llama.cpp works.
// - A native method that fails throws NativeLlamaException with the session's status as its
//   code. It never calls a JNI function while an exception is pending: after a callback into
//   Kotlin threw, the work is wound down without further calls into the VM and the method
//   returns, so that the Kotlin exception is the one the caller sees.
// - No local reference is created inside a loop: the callbacks pass and return primitives only.
// - The copies of text and tokens that this file makes are overwritten before they are freed,
//   also when the call fails. That is this file's copies only: llama.cpp makes its own of what
//   it is handed and frees them as they are (see pp_session_clear_memory in the header).
// - A callback is made on the thread that called the native method, with that call's JNIEnv.
//   Should llama.cpp ever call back from another thread, the callback answers without touching
//   the VM.
//
// Only JNI_OnLoad is exported; the methods are registered from a table, which fails when the
// library is loaded (not at the first call) if the two sides have drifted apart.

#include "proseplow_session.h"

#include <jni.h>

#include <cstring>
#include <new>
#include <string>
#include <vector>

#include <pthread.h>

namespace {

// Names the Kotlin side must keep. They are also listed in llama/consumer-rules.pro, so that a
// minified build cannot rename them.
const char * const k_jni_class       = "us/theworkmans/proseplow/llama/LlamaJni";
const char * const k_exception_class = "us/theworkmans/proseplow/core/engine/NativeLlamaException";
const char * const k_observer_class  = "us/theworkmans/proseplow/core/engine/EvaluationObserver";
const char * const k_progress_class  = "us/theworkmans/proseplow/core/engine/LoadProgress";

// Global references, held for the life of the process: a method id is only valid while its
// class is loaded.
jclass    g_exception_class    = nullptr;
jclass    g_observer_class     = nullptr;
jclass    g_progress_class     = nullptr;
jmethodID g_exception_init     = nullptr; // NativeLlamaException(int, String)
jmethodID g_observer_progress  = nullptr; // EvaluationObserver.onProgress(int)
jmethodID g_observer_cancelled = nullptr; // EvaluationObserver.isCancelled(): boolean
jmethodID g_load_progress      = nullptr; // LoadProgress.onProgress(float): boolean

void wipe(void * data, size_t size) {
    if (data == nullptr || size == 0) {
        return;
    }
    std::memset(data, 0, size);
    __asm__ __volatile__("" : : "r"(data) : "memory");
}

// A buffer for text or tokens of a conversation that is overwritten when it goes out of scope,
// on every path out, an exception included. It is sized once and never grows: growing would
// free the old storage as it is.
template <typename T> class wiped {
  public:
    explicit wiped(size_t size) : storage(size) {}
    ~wiped() { wipe(storage.data(), storage.size() * sizeof(T)); }
    wiped(const wiped &) = delete;
    wiped & operator=(const wiped &) = delete;

    T *    data() { return storage.data(); }
    size_t size() const { return storage.size(); }
    bool   empty() const { return storage.empty(); }

  private:
    std::vector<T> storage;
};

// Throws NativeLlamaException(code, message) unless an exception is already on its way up.
void throw_native(JNIEnv * env, int32_t code, const char * message) {
    if (env->ExceptionCheck()) {
        return;
    }
    // NewStringUTF wants modified UTF-8; printable ASCII is valid in every UTF-8 there is.
    char text[256];
    size_t n = 0;
    for (const char * c = message != nullptr ? message : ""; *c != '\0' && n + 1 < sizeof(text); c++) {
        text[n++] = *c >= 0x20 && *c < 0x7f ? *c : '?';
    }
    text[n] = '\0';
    jstring string = env->NewStringUTF(text);
    if (string == nullptr) {
        return; // the VM has thrown OutOfMemoryError
    }
    jobject exception = env->NewObject(g_exception_class, g_exception_init, (jint) code, string);
    if (exception != nullptr) {
        env->Throw((jthrowable) exception);
    }
}

pp_session * session_of(JNIEnv * env, jlong handle) {
    if (handle == 0) {
        throw_native(env, PP_STATUS_INVALID_ARGUMENT, "the session is closed");
    }
    return (pp_session *) handle;
}

// Runs body and turns a C++ exception into a NativeLlamaException: none may cross into the VM.
template <typename T, typename Body> T guarded(JNIEnv * env, int32_t status_on_failure, T on_failure, Body && body) {
    try {
        return body();
    } catch (const std::bad_alloc &) {
        throw_native(env, PP_STATUS_OUT_OF_MEMORY, "out of memory in the native binding");
    } catch (...) {
        throw_native(env, status_on_failure, "unexpected failure in the native binding");
    }
    return on_failure;
}

// A callback into Kotlin during a native call.
struct upcall {
    JNIEnv *  env;
    jobject   target;
    pthread_t thread = pthread_self();
    bool      threw = false; // a Kotlin exception is pending: make no more calls into the VM

    bool usable() const { return !threw && pthread_equal(thread, pthread_self()) != 0; }
};

bool evaluation_is_cancelled(void * data) {
    upcall * call = (upcall *) data;
    if (call->threw) {
        return true; // wind the evaluation down
    }
    if (!call->usable()) {
        return false;
    }
    const jboolean cancelled = call->env->CallBooleanMethod(call->target, g_observer_cancelled);
    if (call->env->ExceptionCheck()) {
        call->threw = true;
        return true;
    }
    return cancelled == JNI_TRUE;
}

void evaluation_on_progress(int32_t n_evaluated, void * data) {
    upcall * call = (upcall *) data;
    if (!call->usable()) {
        return;
    }
    call->env->CallVoidMethod(call->target, g_observer_progress, (jint) n_evaluated);
    if (call->env->ExceptionCheck()) {
        call->threw = true;
    }
}

bool load_on_progress(float fraction, void * data) {
    upcall * call = (upcall *) data;
    if (call->threw) {
        return false; // abandon the load
    }
    if (!call->usable()) {
        return true;
    }
    const jboolean go_on = call->env->CallBooleanMethod(call->target, g_load_progress, (jfloat) fraction);
    if (call->env->ExceptionCheck()) {
        call->threw = true;
        return false;
    }
    return go_on == JNI_TRUE;
}

// A byte array as a std::string. Returns false when a Java exception is pending.
bool bytes_of(JNIEnv * env, jbyteArray array, std::string & out) {
    const jsize n = env->GetArrayLength(array);
    out.resize((size_t) n);
    if (n > 0) {
        env->GetByteArrayRegion(array, 0, n, (jbyte *) out.data());
    }
    return !env->ExceptionCheck();
}

//
// The process
//

// backend_libraries holds the candidate library names, one per line (a single array rather than
// an array of strings: nothing to look up, no local reference per name).
jint runtime_init(JNIEnv * env, jclass, jbyteArray backend_libraries) {
    return guarded(env, PP_STATUS_LOAD_FAILED, (jint) PP_STATUS_LOAD_FAILED, [&]() -> jint {
        std::string lines;
        if (backend_libraries == nullptr || !bytes_of(env, backend_libraries, lines)) {
            return PP_STATUS_INVALID_ARGUMENT;
        }
        std::vector<std::string> names;
        size_t begin = 0;
        while (begin < lines.size()) {
            size_t end = lines.find('\n', begin);
            if (end == std::string::npos) {
                end = lines.size();
            }
            if (end > begin) {
                names.push_back(lines.substr(begin, end - begin));
            }
            begin = end + 1;
        }
        std::vector<const char *> pointers;
        for (const std::string & name : names) {
            pointers.push_back(name.c_str());
        }
        return pp_runtime_init(pointers.data(), (int32_t) pointers.size());
    });
}

jbyteArray runtime_description(JNIEnv * env, jclass) {
    const char * text = pp_runtime_description();
    const jsize n = (jsize) std::strlen(text);
    jbyteArray out = env->NewByteArray(n);
    if (out != nullptr && n > 0) {
        env->SetByteArrayRegion(out, 0, n, (const jbyte *) text);
    }
    return out;
}

//
// Opening and closing
//

jlong open_fd(JNIEnv * env, jclass, jint fd, jlong offset, jlong length, jint n_ctx, jlong context_budget, jint n_batch, jint n_threads,
              jintArray pinned_cpus, jboolean map, jobject progress) {
    return guarded(env, PP_STATUS_LOAD_FAILED, (jlong) 0, [&]() -> jlong {
        std::vector<int32_t> cpus;
        if (pinned_cpus != nullptr) {
            cpus.resize((size_t) env->GetArrayLength(pinned_cpus));
            if (!cpus.empty()) {
                env->GetIntArrayRegion(pinned_cpus, 0, (jsize) cpus.size(), (jint *) cpus.data());
            }
        }
        upcall call{env, progress};
        pp_session_params params = {};
        params.n_ctx         = n_ctx;
        params.context_budget_bytes = context_budget;
        params.n_batch       = n_batch;
        params.n_threads     = n_threads;
        params.pinned_cpus   = cpus.empty() ? nullptr : cpus.data();
        params.n_pinned_cpus = (int32_t) cpus.size();
        params.load_mode     = map == JNI_TRUE ? PP_LOAD_MODE_MMAP : PP_LOAD_MODE_READ;
        if (progress != nullptr) {
            params.progress = load_on_progress;
            params.progress_user_data = &call;
        }

        char error[256];
        pp_session * session = nullptr;
        const int32_t status = pp_session_open_fd((int) fd, (int64_t) offset, (int64_t) length, &params, &session, error, sizeof(error));
        if (call.threw) {
            // The progress callback threw, which also abandoned the load; its exception stands.
            pp_session_close(session);
            return 0;
        }
        if (status != PP_STATUS_OK) {
            throw_native(env, status, error);
            return 0;
        }
        return (jlong) session;
    });
}

void close_session(JNIEnv *, jclass, jlong handle) {
    pp_session_close((pp_session *) handle);
}

//
// Tokens
//

jintArray tokenize(JNIEnv * env, jclass, jlong handle, jbyteArray utf8, jboolean parse_special) {
    return guarded(env, PP_STATUS_TOKENIZE_FAILED, (jintArray) nullptr, [&]() -> jintArray {
        pp_session * s = session_of(env, handle);
        if (s == nullptr) {
            return nullptr;
        }
        if (utf8 == nullptr) {
            throw_native(env, PP_STATUS_INVALID_ARGUMENT, "tokenize needs a byte array");
            return nullptr;
        }
        const jsize length = env->GetArrayLength(utf8);
        // Refused before it is copied: the session would refuse it too, after the copy.
        if (length > PP_TOKENIZE_BYTES_MAX) {
            throw_native(env, PP_STATUS_TOKENIZE_FAILED, "the text is longer than the most that is tokenised in one call");
            return nullptr;
        }
        wiped<char> text((size_t) length);
        if (length > 0) {
            env->GetByteArrayRegion(utf8, 0, length, (jbyte *) text.data());
        }
        const bool special = parse_special == JNI_TRUE;
        // Room for any result, so that one call does it: a token stands for at least one byte of
        // the text as the tokenizer sees it, and the peacebell front-end makes the text up to
        // three times as long as it came (a space becomes a character of three bytes) and puts
        // one such character in front. Not "measure, then fill": llama.cpp copies the text each
        // time it tokenises it and frees the copies as they are, so every call that can be
        // spared is a set of copies that never exists.
        wiped<llama_token> tokens(3 * (size_t) length + 8);
        const int32_t n = pp_session_tokenize(s, text.data(), (int32_t) text.size(), special, tokens.data(), (int32_t) tokens.size());
        jintArray out = nullptr;
        if (n == PP_ERROR || n < 0) {
            throw_native(env, PP_STATUS_TOKENIZE_FAILED, n == PP_ERROR ? pp_session_last_error(s) : "the tokenizer produced more tokens than the text has room for");
        } else {
            out = env->NewIntArray((jsize) n);
            if (out != nullptr && n > 0) {
                env->SetIntArrayRegion(out, 0, (jsize) n, (const jint *) tokens.data());
            }
        }
        return out;
    });
}

jbyteArray token_to_bytes(JNIEnv * env, jclass, jlong handle, jint token) {
    return guarded(env, PP_STATUS_INVALID_ARGUMENT, (jbyteArray) nullptr, [&]() -> jbyteArray {
        pp_session * s = session_of(env, handle);
        if (s == nullptr) {
            return nullptr;
        }
        // Pieces are short; the stack buffer covers all but freak tokens.
        char small[64];
        std::string large;
        char * bytes = small;
        int32_t n = pp_session_token_to_bytes(s, (llama_token) token, small, (int32_t) sizeof(small));
        if (n != PP_ERROR && n < 0) {
            large.resize((size_t) -n);
            bytes = large.data();
            n = pp_session_token_to_bytes(s, (llama_token) token, bytes, (int32_t) large.size());
        }
        jbyteArray out = nullptr;
        if (n == PP_ERROR || n < 0) {
            throw_native(env, PP_STATUS_INVALID_ARGUMENT, pp_session_last_error(s));
        } else {
            out = env->NewByteArray((jsize) n);
            if (out != nullptr && n > 0) {
                env->SetByteArrayRegion(out, 0, (jsize) n, (const jbyte *) bytes);
            }
        }
        wipe(small, sizeof(small));
        wipe(large.data(), large.size());
        return out;
    });
}

jboolean is_end_of_generation(JNIEnv *, jclass, jlong handle, jint token) {
    return handle != 0 && pp_session_is_end_of_generation((pp_session *) handle, (llama_token) token) ? JNI_TRUE : JNI_FALSE;
}

//
// Memory
//

jint context_size(JNIEnv *, jclass, jlong handle) {
    return handle == 0 ? 0 : pp_session_context_size((pp_session *) handle);
}

jint memory_token_count(JNIEnv *, jclass, jlong handle) {
    return handle == 0 ? 0 : pp_session_memory_token_count((pp_session *) handle);
}

jint max_context_size(JNIEnv *, jclass, jlong handle) {
    return handle == 0 ? 0 : pp_session_max_context_size((pp_session *) handle);
}

jlong context_bytes(JNIEnv * env, jclass, jlong handle, jint tokens) {
    if (handle == 0) {
        return 0;
    }
    pp_session * s = (pp_session *) handle;
    if (tokens < 1 || tokens > pp_session_max_context_size(s)) {
        throw_native(env, PP_STATUS_INVALID_ARGUMENT, "a context size outside 1 to the largest context of this model has no cost");
        return 0;
    }
    const int64_t bytes = pp_session_context_bytes(s, tokens);
    if (bytes < 0) {
        // The session could not read what llama.cpp reports of a context: no size can be
        // costed, and none will be changed to (see pp_session_context_bytes).
        throw_native(env, PP_STATUS_CONTEXT_REFUSED, "what a context costs is not known for this session");
        return 0;
    }
    return (jlong) bytes;
}

void resize_context(JNIEnv * env, jclass, jlong handle, jint tokens, jlong budget) {
    if (handle == 0) {
        throw_native(env, PP_STATUS_INVALID_ARGUMENT, "a closed session has no context to resize");
        return;
    }
    pp_session * s = (pp_session *) handle;
    // Refused, or not created at the size asked for: either way the caller is told with the
    // status as its code, and asks the session what it has now.
    const int32_t status = pp_session_resize_context(s, tokens, (int64_t) budget);
    if (status != PP_STATUS_OK) {
        throw_native(env, status, pp_session_last_error(s));
    }
}

jint evaluate(JNIEnv * env, jclass, jlong handle, jintArray tokens, jint chunk_size, jobject observer) {
    return guarded(env, PP_STATUS_EVALUATION_FAILED, (jint) 0, [&]() -> jint {
        pp_session * s = session_of(env, handle);
        if (s == nullptr) {
            return 0;
        }
        if (tokens == nullptr || observer == nullptr) {
            throw_native(env, PP_STATUS_INVALID_ARGUMENT, "evaluate needs tokens and an observer");
            return 0;
        }
        const jsize count = env->GetArrayLength(tokens);
        // More tokens than the largest context of this model holds cannot be evaluated; refused
        // before they are copied.
        if (count > pp_session_max_context_size(s)) {
            throw_native(env, PP_STATUS_CONTEXT_FULL, "more tokens than the largest context of this model holds");
            return 0;
        }
        wiped<llama_token> copy((size_t) count);
        if (!copy.empty()) {
            env->GetIntArrayRegion(tokens, 0, (jsize) copy.size(), (jint *) copy.data());
        }
        upcall call{env, observer};
        const pp_evaluation_observer callbacks = {evaluation_is_cancelled, evaluation_on_progress, &call};
        int32_t n_in_memory = 0;
        const int32_t status = pp_session_evaluate(s, copy.data(), (int32_t) copy.size(), chunk_size, &callbacks, &n_in_memory);
        if (call.threw) {
            return 0; // the observer's exception is pending and is what the caller gets
        }
        if (status != PP_STATUS_OK) {
            throw_native(env, status, pp_session_last_error(s));
            return 0;
        }
        return n_in_memory;
    });
}

jboolean rewind_to(JNIEnv * env, jclass, jlong handle, jint token_count) {
    pp_session * s = session_of(env, handle);
    if (s == nullptr) {
        return JNI_FALSE;
    }
    bool removed = false;
    const int32_t status = pp_session_rewind_to(s, token_count, &removed);
    if (status != PP_STATUS_OK) {
        throw_native(env, status, pp_session_last_error(s));
        return JNI_FALSE;
    }
    return removed ? JNI_TRUE : JNI_FALSE;
}

jint save_checkpoint(JNIEnv *, jclass, jlong handle) {
    return handle == 0 ? -1 : pp_session_save_checkpoint((pp_session *) handle);
}

jboolean restore_checkpoint(JNIEnv *, jclass, jlong handle, jint checkpoint) {
    return handle != 0 && pp_session_restore_checkpoint((pp_session *) handle, checkpoint) ? JNI_TRUE : JNI_FALSE;
}

void drop_checkpoint(JNIEnv *, jclass, jlong handle, jint checkpoint) {
    pp_session_drop_checkpoint((pp_session *) handle, checkpoint);
}

void clear_memory(JNIEnv * env, jclass, jlong handle) {
    if (handle == 0) {
        return; // a closed session holds no conversation: nothing to forget, as before
    }
    pp_session * s = (pp_session *) handle;
    // The conversation is gone either way; a failure says that the session can no longer be
    // used and must be closed, which is what the engine does with an exception from here.
    const int32_t status = pp_session_clear_memory(s);
    if (status != PP_STATUS_OK) {
        throw_native(env, status, pp_session_last_error(s));
    }
}

//
// Sampling
//

void configure_sampler(JNIEnv * env, jclass, jlong handle, jfloat temperature, jint top_k, jfloat top_p, jfloat min_p,
                       jfloat repetition_penalty, jfloat presence_penalty, jfloat frequency_penalty, jint penalty_window,
                       jint reply_start, jint seed, jintArray banned) {
    guarded(env, PP_STATUS_SAMPLING_FAILED, 0, [&]() -> int {
        pp_session * s = session_of(env, handle);
        if (s == nullptr) {
            return 0;
        }
        std::vector<llama_token> banned_tokens;
        if (banned != nullptr) {
            banned_tokens.resize((size_t) env->GetArrayLength(banned));
            if (!banned_tokens.empty()) {
                env->GetIntArrayRegion(banned, 0, (jsize) banned_tokens.size(), (jint *) banned_tokens.data());
            }
        }
        pp_sampler_config config = {};
        config.temperature        = temperature;
        config.top_k              = top_k;
        config.top_p              = top_p;
        config.min_p              = min_p;
        config.repetition_penalty = repetition_penalty;
        config.presence_penalty   = presence_penalty;
        config.frequency_penalty  = frequency_penalty;
        config.penalty_window     = penalty_window;
        config.reply_start        = reply_start; // -1 (PP_REPLY_START_AT_SAMPLING) or a memory position; below -1 is refused
        config.seed               = (uint32_t) seed; // the 32 bits as they are, read as unsigned
        config.banned_tokens      = banned_tokens.empty() ? nullptr : banned_tokens.data();
        config.n_banned_tokens    = (int32_t) banned_tokens.size();
        const int32_t status = pp_session_configure_sampler(s, &config);
        if (status != PP_STATUS_OK) {
            throw_native(env, status, pp_session_last_error(s));
        }
        return 0;
    });
}

jint sample_next(JNIEnv * env, jclass, jlong handle) {
    pp_session * s = session_of(env, handle);
    if (s == nullptr) {
        return 0;
    }
    llama_token token = LLAMA_TOKEN_NULL;
    const int32_t status = pp_session_sample_next(s, &token);
    if (status != PP_STATUS_OK) {
        throw_native(env, status, pp_session_last_error(s));
        return 0;
    }
    return token;
}

//
// Diagnostics
//

// The number of evaluation threads, negative when each is pinned to a CPU of its own.
jint thread_setup(JNIEnv *, jclass, jlong handle) {
    int32_t n_threads = 0;
    bool pinned = false;
    pp_session_thread_setup((pp_session *) handle, &n_threads, &pinned);
    return pinned ? -n_threads : n_threads;
}

// The table of native methods: name, signature, function. The Kotlin declarations in
// LlamaJni.kt must match line for line.
const JNINativeMethod k_methods[] = {
    {"runtimeInit",        "([B)I",                                                             (void *) runtime_init},
    {"runtimeDescription", "()[B",                                                              (void *) runtime_description},
    {"openFd",             "(IJJIJII[IZLus/theworkmans/proseplow/core/engine/LoadProgress;)J",  (void *) open_fd},
    {"close",              "(J)V",                                                              (void *) close_session},
    {"tokenize",           "(J[BZ)[I",                                                          (void *) tokenize},
    {"tokenToBytes",       "(JI)[B",                                                            (void *) token_to_bytes},
    {"isEndOfGeneration",  "(JI)Z",                                                             (void *) is_end_of_generation},
    {"contextSize",        "(J)I",                                                              (void *) context_size},
    {"memoryTokenCount",   "(J)I",                                                              (void *) memory_token_count},
    {"maxContextSize",     "(J)I",                                                              (void *) max_context_size},
    {"contextBytes",       "(JI)J",                                                             (void *) context_bytes},
    {"resizeContext",      "(JIJ)V",                                                            (void *) resize_context},
    {"evaluate",           "(J[IILus/theworkmans/proseplow/core/engine/EvaluationObserver;)I",  (void *) evaluate},
    {"rewindTo",           "(JI)Z",                                                             (void *) rewind_to},
    {"saveCheckpoint",     "(J)I",                                                              (void *) save_checkpoint},
    {"restoreCheckpoint",  "(JI)Z",                                                             (void *) restore_checkpoint},
    {"dropCheckpoint",     "(JI)V",                                                             (void *) drop_checkpoint},
    {"clearMemory",        "(J)V",                                                              (void *) clear_memory},
    {"configureSampler",   "(JFIFFFFFIII[I)V",                                                  (void *) configure_sampler},
    {"sampleNext",         "(J)I",                                                              (void *) sample_next},
    {"threadSetup",        "(J)I",                                                              (void *) thread_setup},
};

} // namespace

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM * vm, void *) {
    JNIEnv * env = nullptr;
    if (vm->GetEnv((void **) &env, JNI_VERSION_1_6) != JNI_OK || env == nullptr) {
        return JNI_ERR;
    }
    // FindClass here uses the class loader of the code that loaded the library, which is the
    // app's; from a callback on a native thread it would not find any of these.
    jclass exception = env->FindClass(k_exception_class);
    jclass observer  = exception != nullptr ? env->FindClass(k_observer_class) : nullptr;
    jclass progress  = observer != nullptr ? env->FindClass(k_progress_class) : nullptr;
    jclass natives   = progress != nullptr ? env->FindClass(k_jni_class) : nullptr;
    if (natives == nullptr) {
        return JNI_ERR; // with the VM's own exception pending, which names the missing class
    }
    g_exception_class    = (jclass) env->NewGlobalRef(exception);
    g_observer_class     = (jclass) env->NewGlobalRef(observer);
    g_progress_class     = (jclass) env->NewGlobalRef(progress);
    g_exception_init     = env->GetMethodID(exception, "<init>", "(ILjava/lang/String;)V");
    g_observer_progress  = g_exception_init != nullptr ? env->GetMethodID(observer, "onProgress", "(I)V") : nullptr;
    g_observer_cancelled = g_observer_progress != nullptr ? env->GetMethodID(observer, "isCancelled", "()Z") : nullptr;
    g_load_progress      = g_observer_cancelled != nullptr ? env->GetMethodID(progress, "onProgress", "(F)Z") : nullptr;
    if (g_exception_class == nullptr || g_observer_class == nullptr || g_progress_class == nullptr || g_load_progress == nullptr) {
        return JNI_ERR;
    }
    if (env->RegisterNatives(natives, k_methods, (jint) (sizeof(k_methods) / sizeof(k_methods[0]))) != JNI_OK) {
        return JNI_ERR;
    }
    return JNI_VERSION_1_6;
}
