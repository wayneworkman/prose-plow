// The pieces of a memory image, and the state of a sequence written and read through them.
//
// Internal to the session library: session.cpp is the only user. See pp_session_export_memory
// and pp_session_import_memory in include/proseplow_session.h for the contract this serves.
//
// llama.cpp's public interface moves the state of a sequence as one block (the caller allocates
// all of it) or as a file in clear. Inside, every state goes through two small interfaces of
// its own, llama_io_write_i and llama_io_read_i, which are handed the scalars of the state one
// by one and each stretch of key and value rows as "this tensor, from this offset, this many
// bytes". state_stream.cpp implements the two over the caller's buffer, so that a state leaves
// and enters a context a piece at a time and no block of its size ever exists. That file is the
// one place in this project that is compiled against llama.cpp's internal headers; nothing of
// them shows here.
//
// Invariants:
// - Neither class allocates, and neither keeps a byte of an image outside the caller's buffer.
// - A writer never throws: once the sink has said stop, or something went wrong, it takes
//   whatever it is handed and drops it, so that llama.cpp finishes its walk over the cells
//   undisturbed. A reader cannot do the same, because it would have to make up the bytes it
//   hands on; it throws std::runtime_error, as llama.cpp's own readers do, and goes on throwing.
// - Neither overwrites the buffer when it is done: the caller of both, which knows when the
//   last use of the buffer was, does.

#ifndef PROSEPLOW_STATE_STREAM_H
#define PROSEPLOW_STATE_STREAM_H

#include "proseplow_session.h"

#include <cstddef>
#include <cstdint>

namespace pp_stream {

// Fills the sink's buffer and hands it on whenever it is full, and when the last byte of the
// image is in it.
class piece_writer {
  public:
    // total is the number of bytes the image will have: the writer says "last" from it, and
    // more bytes than that are a failure.
    piece_writer(const pp_memory_sink & sink, uint64_t total);

    // Appends bytes to the image.
    void write(const void * src, size_t size);
    // Where the next bytes of the image go and how many fit there: for a caller that can fill
    // the buffer itself and so saves a copy. NULL, with nothing available, once nothing more
    // is taken.
    uint8_t * space(size_t & available);
    // Says that n bytes were put at space(), and hands the piece on when that fills the buffer
    // or completes the image.
    void advance(size_t n);
    // Ends the writing for a reason that is not the sink's; why is a literal.
    void fail(const char * why);

    // Every byte of the image was handed to the sink, and the sink accepted the last piece.
    bool complete() const { return !ended && delivered == total; }
    // The sink returned false.
    bool stopped() const { return stopped_by_sink; }
    // What went wrong when it was neither; NULL when nothing did.
    const char * problem() const { return why_failed; }
    // The bytes of the image in the pieces the sink accepted.
    uint64_t n_delivered() const { return delivered; }

  private:
    void hand_on();

    const pp_memory_sink sink; // a copy: what the caller's struct says later does not matter
    const uint64_t total;
    uint64_t produced = 0;   // bytes of the image put into the buffer so far
    uint64_t delivered = 0;  // of those, the ones in pieces the sink accepted
    size_t fill = 0;         // bytes in the buffer, the caller's own first bytes included
    size_t own = 0;          // of those, the caller's own: not zero in the first piece only
    bool ended = false;
    bool stopped_by_sink = false;
    const char * why_failed = nullptr;
};

// Asks the source for a piece whenever the bytes of the one before are used up.
class piece_reader {
  public:
    explicit piece_reader(const pp_memory_source & source);

    // Allows n more bytes to be taken. A reader is created allowing none, and what a part of
    // the image may take is allowed part by part, so that nothing read from the image can make
    // a reader of it (llama.cpp's, above all) take more than the part's own size.
    void allow(uint64_t n) { allowed += n; }
    // Every byte that was allowed has been taken.
    bool used_up() const { return taken == allowed; }

    // Copies the next bytes of the image out. Throws when they cannot be had.
    void read(void * dst, size_t size);
    // The next bytes of the image in the buffer and how many are there: at least one, never
    // more than are allowed. Throws when there is none to be had.
    const uint8_t * data(size_t & available);
    // Says that n of the bytes at data() were used.
    void advance(size_t n);
    // Ends the reading for a reason that is not the source's, and throws; why is a literal.
    [[noreturn]] void fail(const char * why);

    // True when the image's last byte was the last byte of its piece and the source, asked
    // once more, says that nothing follows. Asks the source: call it once, at the end.
    bool at_end();

    // The source returned a negative number.
    bool stopped() const { return stopped_by_source; }
    // What went wrong when it was not that; NULL when nothing did.
    const char * problem() const { return why_failed; }

  private:
    const pp_memory_source source; // a copy, as the writer's
    uint64_t allowed = 0;
    uint64_t taken = 0;
    size_t have = 0;  // bytes of the present piece in the buffer
    size_t pos = 0;   // of those, the ones already used
    bool first = true;
    bool ended = false;
    bool stopped_by_source = false;
    const char * why_failed = nullptr;
};

// The number of bytes write_state hands on for the sequence as it is now. Nothing is copied.
// Throws what llama.cpp throws.
uint64_t state_bytes(llama_context * ctx, llama_seq_id seq);

// Writes the state of the sequence: what llama.cpp's llama_state_seq_get_data_ext writes with
// LLAMA_STATE_SEQ_FLAGS_NONE behind the eight bytes it puts in front (a mark and the sequence
// id), byte for byte, and without the block. Only reads the context. Throws what llama.cpp
// throws; a sink that stopped does not make it throw (see out.stopped()).
void write_state(llama_context * ctx, llama_seq_id seq, piece_writer & out);

// Reads the state of the sequence from the reader, which must stand at its first byte. The
// sequence is emptied first (llama.cpp does that). Throws when the reader does and when
// llama.cpp refuses the state; what the memory holds then is not defined, and the caller
// clears it.
void read_state(llama_context * ctx, llama_seq_id seq, piece_reader & in);

} // namespace pp_stream

#endif // PROSEPLOW_STATE_STREAM_H
