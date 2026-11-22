// Shared plumbing of the host test programs: reading the reference files written by
// native/tests/refgen/formats.py (the two must stay in step), loading a model quietly, and
// reporting. Header-only so that each test stays one translation unit.
//
// A test program exits with 0 (all checks passed), 1 (a check failed) or 2 (it could not run:
// bad arguments, unreadable file). Whether a test is skipped because the weights are absent is
// decided by the runner (native/tests/run.py), which knows where the files should be.

#pragma once

#include "llama.h"

#include <cstdarg>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <memory>
#include <stdexcept>
#include <string>
#include <vector>

namespace pp_test {

[[noreturn]] inline void die(const std::string & message) {
    std::fprintf(stderr, "error: %s\n", message.c_str());
    std::exit(2);
}

// Collects the verdicts of a test program: every check prints one line with its measured value
// and its limit, so a log shows the margin and not only pass or fail.
class report {
  public:
    void check(bool ok, const char * format, ...) __attribute__((format(printf, 3, 4))) {
        va_list args;
        va_start(args, format);
        std::printf("%s  ", ok ? "ok  " : "FAIL");
        std::vprintf(format, args);
        std::printf("\n");
        va_end(args);
        n_checks++;
        if (!ok) {
            n_failed++;
        }
    }

    void note(const char * format, ...) __attribute__((format(printf, 2, 3))) {
        va_list args;
        va_start(args, format);
        std::printf("note  ");
        std::vprintf(format, args);
        std::printf("\n");
        va_end(args);
    }

    int finish() const {
        std::printf("%s: %d check(s), %d failed\n", n_failed == 0 ? "PASSED" : "FAILED", n_checks, n_failed);
        std::fflush(stdout);
        return n_failed == 0 ? 0 : 1;
    }

  private:
    int n_checks = 0;
    int n_failed = 0;
};

// Sequential reader of a little-endian reference file held in memory.
class reader {
  public:
    explicit reader(const std::string & file_path) : path(file_path) {
        // The formats are little-endian and read by memcpy; a big-endian host would need swaps.
        const uint32_t probe = 1;
        if (*(const unsigned char *) &probe != 1) {
            die("the reference files are little-endian and this host is not");
        }
        std::FILE * f = std::fopen(path.c_str(), "rb");
        if (f == nullptr) {
            die("cannot open " + path);
        }
        std::fseek(f, 0, SEEK_END);
        const long size = std::ftell(f);
        std::fseek(f, 0, SEEK_SET);
        data.resize(size > 0 ? (size_t) size : 0);
        if (!data.empty() && std::fread(data.data(), 1, data.size(), f) != data.size()) {
            die("cannot read " + path);
        }
        std::fclose(f);
    }

    void expect_magic(const char * magic) {
        if (std::memcmp(take(8), magic, 8) != 0) {
            die(path + " is not a " + magic + " file");
        }
    }

    uint32_t u32() {
        uint32_t v;
        std::memcpy(&v, take(4), 4);
        return v;
    }

    uint8_t u8() { return *(const uint8_t *) take(1); }

    std::string bytes(size_t n) {
        const char * p = take(n);
        return std::string(p, n);
    }

    std::string sized_bytes() { return bytes(u32()); }

    template <typename T> std::vector<T> array(size_t count) {
        std::vector<T> v(count);
        if (count > 0) {
            std::memcpy(v.data(), take(count * sizeof(T)), count * sizeof(T));
        }
        return v;
    }

    // Token ids are stored as u16 in the token vector files.
    std::vector<llama_token> ids_u16() {
        const std::vector<uint16_t> raw = array<uint16_t>(u32());
        return std::vector<llama_token>(raw.begin(), raw.end());
    }

    bool at_end() const { return pos == data.size(); }

  private:
    const char * take(size_t n) {
        if (n > data.size() - pos) {
            die(path + " is truncated");
        }
        const char * p = data.data() + pos;
        pos += n;
        return p;
    }

    std::string path;
    std::vector<char> data;
    size_t pos = 0;
};

struct model_deleter {
    void operator()(llama_model * m) const { llama_model_free(m); }
};
struct context_deleter {
    void operator()(llama_context * c) const { llama_free(c); }
};
using model_ptr   = std::unique_ptr<llama_model, model_deleter>;
using context_ptr = std::unique_ptr<llama_context, context_deleter>;

// llama.cpp logs the whole model description at load; a test log should show the test's own
// lines. Errors still come through.
inline void quiet_logs() {
    llama_log_set([](enum ggml_log_level level, const char * text, void *) {
        if (level == GGML_LOG_LEVEL_ERROR) {
            std::fputs(text, stderr);
        }
    }, nullptr);
}

inline model_ptr load_model(const std::string & path, bool vocab_only) {
    llama_model_params params = llama_model_default_params();
    params.vocab_only   = vocab_only;
    params.n_gpu_layers = 0;
    model_ptr model(llama_model_load_from_file(path.c_str(), params));
    if (!model) {
        die("cannot load " + path);
    }
    return model;
}

inline std::string meta(const llama_model * model, const std::string & key) {
    char buf[256];
    const int32_t n = llama_model_meta_val_str(model, key.c_str(), buf, sizeof(buf));
    return n < 0 ? std::string() : std::string(buf);
}

// Threads for inference in tests: PROSEPLOW_THREADS, else 4. Build machines are shared, so the
// tests never take every core on their own initiative.
inline int n_threads() {
    const char * env = std::getenv("PROSEPLOW_THREADS");
    const int n = env != nullptr ? std::atoi(env) : 4;
    return n >= 1 ? n : 4;
}

} // namespace pp_test
