# Host tests. The programs are plain executables that take their files as arguments; ctest runs
# them through run.py, which finds the model files from the environment at test time, converts
# what is missing, generates the reference data that is too large to commit, and reports a test
# as skipped (exit code 77) when the weights it needs are not on this machine.

# The interpreter is the first one on PATH, the same one that ran pip for native/requirements.txt.
# CMake's own Python finder is not used: it prefers whatever virtualenv happens to be activated
# in the calling shell over PATH, which can be an unrelated one.
find_program(PROSEPLOW_PYTHON NAMES python3 python REQUIRED)
message(STATUS "Python for the tests: ${PROSEPLOW_PYTHON}")

foreach (_test test_peacebell_logits test_peacebell_tokenizer test_plain_tokenizer)
    add_executable(${_test} ${_test}.cpp)
    target_include_directories(${_test} PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}")
    target_link_libraries(${_test} PRIVATE proseplow_native llama)
    target_compile_options(${_test} PRIVATE -Wall -Wextra -Wpedantic)
endforeach()

# Registers one ctest entry. Every test that loads weights takes the same lock, so that even
# "ctest -j" never runs two models at once on a shared machine.
function(proseplow_add_test name)
    add_test(NAME ${name}
        COMMAND "${PROSEPLOW_PYTHON}" "${CMAKE_CURRENT_SOURCE_DIR}/run.py"
                --bin-dir "$<TARGET_FILE_DIR:test_peacebell_logits>"
                --work-dir "${CMAKE_BINARY_DIR}/test-work"
                --llama-src "${PROSEPLOW_LLAMA_SRC}"
                ${ARGN})
    set_tests_properties(${name} PROPERTIES
        SKIP_RETURN_CODE 77
        RESOURCE_LOCK proseplow_cpu
        TIMEOUT 1800
        ENVIRONMENT "PYTHONDONTWRITEBYTECODE=1;CUDA_VISIBLE_DEVICES=")
endfunction()

foreach (_model peacebell-v1-291m peacebell-v1-148m)
    proseplow_add_test(${_model}.convert-determinism convert-determinism ${_model})
    proseplow_add_test(${_model}.gguf-f32            gguf-f32            ${_model})
    proseplow_add_test(${_model}.gguf-f16            gguf-f16            ${_model})
    proseplow_add_test(${_model}.tokenizer           tokenizer           ${_model})
    proseplow_add_test(${_model}.logits-f32          logits-f32          ${_model})
    proseplow_add_test(${_model}.logits-f16          logits-f16          ${_model})
endforeach()

# The live comparison against the SentencePiece library covers every user-defined symbol of the
# tokenizer file; the two checkpoints share that file, so one run is enough.
proseplow_add_test(peacebell-v1-291m.tokenizer-live tokenizer-live peacebell-v1-291m)

# The byte-level BPE member of the family is not trained yet: a seeded random checkpoint with
# its documented shape stands in for it.
proseplow_add_test(peacebell-bpe-random random-bpe)
