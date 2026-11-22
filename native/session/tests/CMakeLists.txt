# Host tests of the generation session, in the style of native/tests: one plain executable that
# takes its files as arguments, run by ctest through a small Python script that finds the model
# files, prepares what the test needs and reports a test as skipped (exit code 77) when the
# weights are not on this machine.

# The same interpreter rule as native/tests: the first one on PATH, not CMake's own finder.
find_program(PROSEPLOW_PYTHON NAMES python3 python REQUIRED)

add_executable(test_session test_session.cpp)
# For support/test_support.h and support/logit_support.h, which read the committed reference files.
target_include_directories(test_session PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/../../tests")
target_link_libraries(test_session PRIVATE proseplow_session)
target_compile_options(test_session PRIVATE -Wall -Wextra -Wpedantic)

# One ctest entry per model. The lock is the one every weight-loading test of native/tests
# takes, so that "ctest -j" never runs two models at once on a shared machine.
function(proseplow_add_session_test model)
    add_test(NAME ${model}.session
        COMMAND "${PROSEPLOW_PYTHON}" "${CMAKE_CURRENT_SOURCE_DIR}/run_session.py"
                --bin-dir "$<TARGET_FILE_DIR:test_session>"
                --work-dir "${CMAKE_BINARY_DIR}/test-work"
                --llama-src "${PROSEPLOW_LLAMA_SRC}"
                ${model})
    set_tests_properties(${model}.session PROPERTIES
        SKIP_RETURN_CODE 77
        RESOURCE_LOCK proseplow_cpu
        TIMEOUT 1800
        ENVIRONMENT "PYTHONDONTWRITEBYTECODE=1;CUDA_VISIBLE_DEVICES=")
endfunction()

# The three bundled models, each as the file that ships (16-bit floats). Between them they have
# the two kinds of memory a session has to drive: attention only (peacebell: the end of the
# memory can be removed, a chunk can be interrupted) and attention plus recurrent state
# (Boris-2, Qwen3.5: neither, and a checkpoint has to be restored instead); and the two ways
# of tokenising (the front-end for peacebell, llama.cpp's own tokenizer for the others).
proseplow_add_session_test(peacebell-v1-291m)
proseplow_add_session_test(boris-2-instruct)
proseplow_add_session_test(qwen3.5-0.8b)
