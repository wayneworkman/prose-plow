// Prose Plow's native layer above libllama.
//
// Plain C interface, no JNI and nothing host-specific: the Android bridge will be a thin layer on
// top of these functions. Today it holds the tokenizer front-end and the chat encoder of the
// peacebell models; the generation session will be added here.
//
// Rules for every function in this file:
// - Text is UTF-8 with an explicit byte length and need not be NUL-terminated. A caller coming
//   from Java must encode with real UTF-8 (String.getBytes(UTF_8)), not JNI's modified UTF-8,
//   which would tokenise every character outside the BMP differently.
// - No function logs, prints or stores the text or the tokens it is given.
// - No C++ exception leaves a function; failures are reported through the return value.
// - Functions that fill a token buffer return the number of tokens written. When the buffer is
//   too small they write nothing and return the negative of the size needed, so a caller can
//   pass (NULL, 0) to measure. PP_ERROR means the call itself was invalid.

#ifndef PROSEPLOW_NATIVE_H
#define PROSEPLOW_NATIVE_H

#include "llama.h"

#include <stdbool.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

// Returned by token-producing functions for a null argument, a negative length or text that is
// not valid UTF-8. It cannot be confused with a "buffer too small" answer.
#define PP_ERROR INT32_MIN

// What the literal text "<|im_start|>" or "<|im_end|>" inside a message becomes.
enum pp_marker_mode {
    // Ordinary characters. This is the mode for anything a person typed or pasted: a message can
    // then never close its own turn or open a forged one.
    PP_MARKER_MODE_SAFE = 0,
    // The marker tokens, exactly as the reference tokenizer does. Only for tests that compare
    // against the reference, and for text the application itself wrote.
    PP_MARKER_MODE_EXACT = 1,
};

// One chat message. Roles are short ASCII words chosen by the application ("system", "user",
// "assistant"); the content is free text.
struct pp_chat_message {
    const char * role;
    int32_t      role_len;
    const char * content;
    int32_t      content_len;
};

//
// peacebell with the SentencePiece tokenizer (peacebell.chat.format = "chatml-ids")
//
// llama.cpp reproduces the BPE merges of this tokenizer but not SentencePiece's normaliser nor its
// user-defined symbols (years, month names and a few names are single tokens, even inside
// words). This front-end does that part and calls llama_tokenize once per stretch of ordinary
// text, which makes the result equal to the reference tokenizer token for token.

struct pp_peacebell_tokenizer;

// Creates the front-end for a loaded model. The model must outlive the returned object.
// Returns NULL when the file is not one this front-end can tokenise exactly (another
// architecture, another tokenizer kind, a normaliser configuration it does not implement); in
// that case *error, when error is not NULL, points to a static description. The object is
// immutable after creation and may be used from several threads at once.
struct pp_peacebell_tokenizer * pp_peacebell_tokenizer_init(const struct llama_model * model, const char ** error);

void pp_peacebell_tokenizer_free(struct pp_peacebell_tokenizer * tokenizer);

// The tokens of one piece of text: what the reference's SentencePiece encode returns for it
// (EXACT), or the same with the two chat markers treated as plain characters (SAFE). No
// beginning-of-text or other special token is added. Empty and all-space text gives no tokens.
int32_t pp_peacebell_tokenize(
        const struct pp_peacebell_tokenizer * tokenizer,
        const char * text,
        int32_t text_len,
        llama_token * tokens,
        int32_t n_tokens_max,
        enum pp_marker_mode mode);

// The tokens of a conversation, as the model was trained to see it: for each message the start
// marker, the tokens of role + "\n", the tokens of the content and the end marker, the two markers
// injected as ids and the two texts encoded separately; then, with add_generation_prompt, the
// start marker and the tokens of "assistant\n", after which the model writes its reply.
// mode applies to the role and the content of every message.
int32_t pp_peacebell_chat_tokenize(
        const struct pp_peacebell_tokenizer * tokenizer,
        const struct pp_chat_message * messages,
        int32_t n_messages,
        bool add_generation_prompt,
        llama_token * tokens,
        int32_t n_tokens_max,
        enum pp_marker_mode mode);

// The ids of the two chat markers. Generation ends when the model produces the end marker.
llama_token pp_peacebell_chat_start_token(const struct pp_peacebell_tokenizer * tokenizer);
llama_token pp_peacebell_chat_end_token(const struct pp_peacebell_tokenizer * tokenizer);

#ifdef __cplusplus
}
#endif

#endif // PROSEPLOW_NATIVE_H
