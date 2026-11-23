// The state of a sequence through the caller's buffer: see state_stream.h.
//
// This is the one file of the project that includes llama.cpp's internal headers. It uses
// three things of them and nothing else:
// - llama_io_write_i and llama_io_read_i (src/llama-io.h), the interfaces every state of
//   llama.cpp is written and read through, which the classes below implement;
// - llama_memory_i::state_write and ::state_read (src/llama-memory.h), the two virtual
//   functions that llama_context::state_seq_write_data and ::state_seq_read_data call, and
//   that are all those two do.
// Both are reached through the object llama_get_memory returns, by its own table of virtual
// functions: nothing here needs a symbol that libllama does not export. When the upstream pin
// moves, this file either stops compiling (an interface gained a member that must be
// implemented) or `<model>.memory` fails; "Moving the upstream pin" in native/README.md says
// what to look at.
//
// Invariants:
// - No block of the state's size is allocated: the rows go between a tensor of the memory and
//   the caller's buffer directly (ggml_backend_tensor_get and _set), as many bytes at a time
//   as the buffer has room for or holds.
// - A range llama.cpp names is held to the tensor it names before ggml is asked for it: ggml
//   ends the process over a range outside a tensor (GGML_ASSERT), and here that is a failure
//   of the call.

#include "state_stream.h"

#include "llama-io.h"
#include "llama-memory.h"

#include "ggml-backend.h"
#include "ggml.h"

#include <algorithm>
#include <cstring>
#include <stdexcept>

namespace {

// Whether llama.cpp may be given, or asked for, size bytes of the tensor from offset: what
// ggml_backend_tensor_get and _set assert.
bool in_tensor(const ggml_tensor * tensor, size_t offset, size_t size) {
    if (tensor == nullptr || tensor->data == nullptr || (tensor->view_src != nullptr ? tensor->view_src->buffer : tensor->buffer) == nullptr) {
        return false;
    }
    const size_t bytes = ggml_nbytes(tensor);
    return offset <= bytes && size <= bytes - offset;
}

// Adds up what a state would take, as llama.cpp's own llama_io_write_dummy does.
class measuring_writer final : public llama_io_write_i {
  public:
    void write(const void *, size_t size) override { count += size; }
    void write_tensor(ggml_tensor *, size_t, size_t size) override { count += size; }
    size_t n_bytes() override { return count; }

  private:
    size_t count = 0;
};

class sink_writer final : public llama_io_write_i {
  public:
    explicit sink_writer(pp_stream::piece_writer & target) : out(target) {}

    void write(const void * src, size_t size) override {
        count += size;
        out.write(src, size);
    }

    void write_tensor(ggml_tensor * tensor, size_t offset, size_t size) override {
        count += size;
        if (!in_tensor(tensor, offset, size)) {
            out.fail("llama.cpp named a range outside a tensor of the memory while writing the state");
            return;
        }
        while (size > 0) {
            size_t available = 0;
            uint8_t * at = out.space(available);
            if (at == nullptr) {
                return; // stopped or failed: the rest of the walk is counted and dropped
            }
            const size_t n = std::min(size, available);
            ggml_backend_tensor_get(tensor, at, offset, n);
            out.advance(n);
            offset += n;
            size -= n;
        }
    }

    // What llama.cpp was told is written, whether or not the sink still takes it: its own
    // writers count the same way, and nothing in a state depends on the number.
    size_t n_bytes() override { return count; }

  private:
    pp_stream::piece_writer & out;
    size_t count = 0;
};

class source_reader final : public llama_io_read_i {
  public:
    explicit source_reader(pp_stream::piece_reader & origin) : in(origin) {}

    void read(void * dst, size_t size) override {
        in.read(dst, size);
        count += size;
    }

    // The rows go into the memory as they arrive. llama.cpp's own reader of a block keeps them
    // back until the whole state was understood, which it can because the block is there; so
    // after a failure the memory here holds part of a state, and discard() has nothing to
    // drop. That is why a failed import always ends with the memory cleared, data included.
    void read_tensor(ggml_tensor * tensor, size_t offset, size_t size) override {
        if (!in_tensor(tensor, offset, size)) {
            in.fail("llama.cpp named a range outside a tensor of the memory while reading the state");
        }
        while (size > 0) {
            size_t available = 0;
            const uint8_t * at = in.data(available);
            const size_t n = std::min(size, available);
            ggml_backend_tensor_set(tensor, at, offset, n);
            in.advance(n);
            count += n;
            offset += n;
            size -= n;
        }
    }

    size_t n_bytes() override { return count; }

  private:
    pp_stream::piece_reader & in;
    size_t count = 0;
};

llama_memory_t memory_of(llama_context * ctx) {
    const llama_memory_t memory = ctx == nullptr ? nullptr : llama_get_memory(ctx);
    if (memory == nullptr) {
        throw std::runtime_error("the context has no memory");
    }
    return memory;
}

} // namespace

namespace pp_stream {

//
// piece_writer
//

piece_writer::piece_writer(const pp_memory_sink & target, uint64_t n_total) : sink(target), total(n_total), fill(target.first_piece_offset), own(target.first_piece_offset) {}

void piece_writer::hand_on() {
    const size_t n = fill;
    const uint64_t of_image = (uint64_t) (fill - own);
    fill = 0;
    own = 0;
    if (!sink.write(n, produced == total, sink.user_data)) {
        ended = true;
        stopped_by_sink = true;
        return;
    }
    delivered += of_image;
}

uint8_t * piece_writer::space(size_t & available) {
    available = 0;
    if (ended) {
        return nullptr;
    }
    if (produced == total) {
        fail("the memory has more bytes to write than were announced for it");
        return nullptr;
    }
    available = (size_t) std::min<uint64_t>(sink.buffer_size - fill, total - produced);
    return sink.buffer + fill;
}

void piece_writer::advance(size_t n) {
    if (ended) {
        return;
    }
    fill += n;
    produced += n;
    if (fill == sink.buffer_size || produced == total) {
        hand_on();
    }
}

void piece_writer::write(const void * src, size_t size) {
    const uint8_t * from = (const uint8_t *) src;
    while (size > 0) {
        size_t available = 0;
        uint8_t * at = space(available);
        if (at == nullptr) {
            return;
        }
        const size_t n = std::min(size, available);
        std::memcpy(at, from, n);
        advance(n);
        from += n;
        size -= n;
    }
}

void piece_writer::fail(const char * why) {
    if (!ended) {
        ended = true;
        why_failed = why;
    }
}

//
// piece_reader
//

piece_reader::piece_reader(const pp_memory_source & origin) : source(origin) {}

void piece_reader::fail(const char * why) {
    if (!ended) {
        ended = true;
        why_failed = why;
    }
    throw std::runtime_error("the memory image cannot be read further");
}

const uint8_t * piece_reader::data(size_t & available) {
    available = 0;
    if (ended) {
        throw std::runtime_error("the memory image cannot be read further");
    }
    if (taken == allowed) {
        fail("more bytes were asked of a part of the memory image than the image says the part has");
    }
    while (pos == have) {
        const int64_t n = source.read(source.user_data);
        if (n < 0) {
            ended = true;
            stopped_by_source = true;
            throw std::runtime_error("the source of the memory image stopped");
        }
        if (n == 0) {
            fail("the memory image ended before its last byte");
        }
        if ((uint64_t) n > (uint64_t) source.buffer_size) {
            fail("the source of the memory image returned more bytes than its buffer holds");
        }
        have = (size_t) n;
        pos = 0;
        if (first) {
            first = false;
            if (have < source.first_piece_offset) {
                fail("the first piece of the memory image is shorter than the caller's own bytes in it");
            }
            pos = source.first_piece_offset;
        }
    }
    available = (size_t) std::min<uint64_t>(have - pos, allowed - taken);
    return source.buffer + pos;
}

void piece_reader::advance(size_t n) {
    pos += n;
    taken += n;
}

void piece_reader::read(void * dst, size_t size) {
    uint8_t * to = (uint8_t *) dst;
    while (size > 0) {
        size_t available = 0;
        const uint8_t * at = data(available);
        const size_t n = std::min(size, available);
        std::memcpy(to, at, n);
        advance(n);
        to += n;
        size -= n;
    }
}

bool piece_reader::at_end() {
    if (ended) {
        return false;
    }
    ended = true;
    if (first || pos != have) {
        why_failed = "bytes follow the memory image in its last piece";
        return false;
    }
    const int64_t n = source.read(source.user_data);
    if (n < 0) {
        stopped_by_source = true;
        return false;
    }
    if (n > 0) {
        why_failed = "another piece follows the memory image";
        return false;
    }
    return true;
}

//
// The state of a sequence
//

uint64_t state_bytes(llama_context * ctx, llama_seq_id seq) {
    measuring_writer io;
    static_cast<const llama_memory_i *>(memory_of(ctx))->state_write(io, seq, LLAMA_STATE_SEQ_FLAGS_NONE);
    return (uint64_t) io.n_bytes();
}

void write_state(llama_context * ctx, llama_seq_id seq, piece_writer & out) {
    sink_writer io(out);
    static_cast<const llama_memory_i *>(memory_of(ctx))->state_write(io, seq, LLAMA_STATE_SEQ_FLAGS_NONE);
}

void read_state(llama_context * ctx, llama_seq_id seq, piece_reader & in) {
    source_reader io(in);
    memory_of(ctx)->state_read(io, seq, LLAMA_STATE_SEQ_FLAGS_NONE);
}

} // namespace pp_stream
