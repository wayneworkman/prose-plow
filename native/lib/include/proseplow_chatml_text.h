// Plain-text ChatML: the chat encoding of the models whose GGUF says
// "<architecture>.chat.format = chatml-text" (Boris-2, and the byte-level BPE members of the
// peacebell family).
//
// Part of proseplow_native.h, which includes this file after the types it needs; do not include
// it on its own. The rules at the top of proseplow_native.h apply to every function here.
//
// For these models the chat markers are NOT tokens. A conversation is the text
//
//     <|im_start|>ROLE\nCONTENT<|im_end|>\n        once per message
//     <|im_start|>assistant\n                      when a reply is to be generated
//
// tokenised in one call as ordinary text, exactly as the model's chat template and reference
// tokenizer produce it. CONTENT is the message with its outer whitespace removed (the template's
// "trim", which is Python's str.strip() and removes more than ASCII spaces); ROLE is used as
// given. Three consequences the application must handle:
//
// - A reply has no end token. It ends where the DECODED TEXT first contains
//   PP_CHATML_TEXT_END_MARKER; the reply is the text before it. The marker's first character
//   can share a token with the text before it (".<" is one token), so the end cannot be found
//   by comparing token ids. While streaming, hold back any tail of the text that is a prefix
//   of the marker. The end-of-text token ends a reply too (llama_vocab_is_eog).
// - Marker text inside a message is indistinguishable from a real marker: a message that
//   contains "<|im_end|>" does close its turn as far as the model can tell. There is no safe
//   mode to choose, because there is no marker token to withhold. An application that must
//   prevent this has to alter such text before it gets here.
// - The end-of-text token is never part of a conversation. Its text ("<|endoftext|>") inside a
//   message becomes ordinary characters, and the token itself must not be fed after it was
//   generated: Boris-2 treats one sequence as one document and rejects that token as input.

#ifndef PROSEPLOW_CHATML_TEXT_H
#define PROSEPLOW_CHATML_TEXT_H

#ifdef __cplusplus
extern "C" {
#endif

// The text that ends a turn. Generation stops when the decoded reply contains it.
#define PP_CHATML_TEXT_END_MARKER "<|im_end|>"

// True when the model's conversations are plain-text ChatML: the file says so, its tokenizer is
// a byte-level BPE, the markers are not vocabulary tokens and it has an end-of-text token. The
// function below refuses any other model; a model with marker tokens (peacebell with the
// SentencePiece tokenizer, Qwen) needs its own encoding.
bool pp_chatml_text_supported(const struct llama_model * model);

// The tokens of a conversation, as described above. Nothing is added at either end.
//
// message_ends, when not NULL, receives n_messages values: message_ends[i] is the number of
// leading tokens that hold the messages 0..i completely, including the newline that follows
// the end marker of message i. Those are the positions where a conversation that changes later
// can be cut: a caller that keeps a checkpoint of the model's recurrent state at these token
// counts (and at the returned total, the end of the generation prompt) can rewind to any of
// them. A value is -1 if that message does not end on a token boundary, which cannot happen
// with the tokenizers verified for this format but is checked rather than assumed.
// message_ends is written only when the tokens are.
//
// Returns PP_ERROR for a model that is not plain-text ChatML, a null or negative argument, a
// role or content that is not valid UTF-8, and (never expected) a tokenisation that contains
// the end-of-text token.
int32_t pp_chatml_text_tokenize(
        const struct llama_model * model,
        const struct pp_chat_message * messages,
        int32_t n_messages,
        bool add_generation_prompt,
        llama_token * tokens,
        int32_t n_tokens_max,
        int32_t * message_ends);

#ifdef __cplusplus
}
#endif

#endif // PROSEPLOW_CHATML_TEXT_H
