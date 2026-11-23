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

# The path of a chat with a model whose ChatML is plain text. It has no ctest entry of its own:
# the tests of the byte-level BPE members of the peacebell family (native/tests/run.py) run it
# on the files they convert.
add_executable(test_chat_text test_chat_text.cpp)
target_include_directories(test_chat_text PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/../../tests")
target_link_libraries(test_chat_text PRIVATE proseplow_session)
target_compile_options(test_chat_text PRIVATE -Wall -Wextra -Wpedantic)

# What a forgotten conversation leaves in the memory of the process: a measurement more than a
# test (see the top of test_residue.cpp for what it requires and what it only reports).
add_executable(test_residue test_residue.cpp)
target_include_directories(test_residue PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/../../tests")
target_link_libraries(test_residue PRIVATE proseplow_session)
target_compile_options(test_residue PRIVATE -Wall -Wextra -Wpedantic)

# What the sampling penalties cover: the session's choices against a second context on the same
# file and the penalties' definitions, and for Boris-2 against replies of its reference library.
add_executable(test_penalties test_penalties.cpp)
target_include_directories(test_penalties PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/../../tests")
target_link_libraries(test_penalties PRIVATE proseplow_session)
target_compile_options(test_penalties PRIVATE -Wall -Wextra -Wpedantic)

# One penalties entry per bundled model, and one for Shard-2: the kinds of memory go back in
# different ways, and what the penalties cover has to survive each of them.
function(proseplow_add_penalties_test model)
    add_test(NAME ${model}.penalties
        COMMAND "${PROSEPLOW_PYTHON}" "${CMAKE_CURRENT_SOURCE_DIR}/run_session.py"
                --bin-dir "$<TARGET_FILE_DIR:test_penalties>"
                --work-dir "${CMAKE_BINARY_DIR}/test-work"
                --llama-src "${PROSEPLOW_LLAMA_SRC}"
                --penalties
                ${model})
    set_tests_properties(${model}.penalties PROPERTIES
        SKIP_RETURN_CODE 77
        RESOURCE_LOCK proseplow_cpu
        TIMEOUT 900
        ENVIRONMENT "PYTHONDONTWRITEBYTECODE=1;CUDA_VISIBLE_DEVICES=")
endfunction()
proseplow_add_penalties_test(peacebell-v1-291m)
proseplow_add_penalties_test(boris-2-instruct)
proseplow_add_penalties_test(qwen3.5-0.8b)
proseplow_add_penalties_test(shard-2)

# The size of a session's context: what a size costs, held to what llama.cpp allocates and to
# the two facts of the model in models/manifest.json; how the size is changed with the memory
# carried over or not; and what a size that cannot be had leaves behind.
add_executable(test_context test_context.cpp)
target_include_directories(test_context PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/../../tests")
target_link_libraries(test_context PRIVATE proseplow_session)
target_compile_options(test_context PRIVATE -Wall -Wextra -Wpedantic)

function(proseplow_add_context_test model)
    add_test(NAME ${model}.context
        COMMAND "${PROSEPLOW_PYTHON}" "${CMAKE_CURRENT_SOURCE_DIR}/run_session.py"
                --bin-dir "$<TARGET_FILE_DIR:test_context>"
                --work-dir "${CMAKE_BINARY_DIR}/test-work"
                --llama-src "${PROSEPLOW_LLAMA_SRC}"
                --context
                ${model})
    set_tests_properties(${model}.context PROPERTIES
        SKIP_RETURN_CODE 77
        RESOURCE_LOCK proseplow_cpu
        TIMEOUT 900
        ENVIRONMENT "PYTHONDONTWRITEBYTECODE=1;CUDA_VISIBLE_DEVICES=")
endfunction()
proseplow_add_context_test(peacebell-v1-291m)
proseplow_add_context_test(boris-2-instruct)
proseplow_add_context_test(qwen3.5-0.8b)
proseplow_add_context_test(shard-2)

# No native code of this project hands the state of a model to a file: the two calls of llama.cpp
# that write one in clear are referred to by neither library as built, and called in no source,
# the JNI binding included (which the host does not build).
add_test(NAME session.state-never-to-file
    COMMAND "${PROSEPLOW_PYTHON}" "${CMAKE_CURRENT_SOURCE_DIR}/check_state_files.py"
            --nm "${CMAKE_NM}"
            --source "${CMAKE_CURRENT_SOURCE_DIR}/../src"
            --source "${CMAKE_CURRENT_SOURCE_DIR}/../../lib/src"
            --source "${CMAKE_CURRENT_SOURCE_DIR}/../../../llama/src/main/cpp"
            "$<TARGET_FILE:proseplow_session>" "$<TARGET_FILE:proseplow_native>")
set_tests_properties(session.state-never-to-file PROPERTIES ENVIRONMENT "PYTHONDONTWRITEBYTECODE=1")

# The memory written out as an image and read back: a session whose memory went out and came
# back against one that was never interrupted, and what an export or an import that ends early
# leaves behind. The test reads the scores and the state of the context a session works with,
# which a session does not show: every context the session library creates is made to pass
# through a function of the test (see the top of test_memory.cpp), by the linker and without a
# line of the session's code knowing.
add_executable(test_memory test_memory.cpp)
target_include_directories(test_memory PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/../../tests")
target_link_libraries(test_memory PRIVATE proseplow_session)
target_link_options(test_memory PRIVATE "-Wl,--wrap=llama_init_from_model")
target_compile_options(test_memory PRIVATE -Wall -Wextra -Wpedantic)

function(proseplow_add_memory_test model)
    add_test(NAME ${model}.memory
        COMMAND "${PROSEPLOW_PYTHON}" "${CMAKE_CURRENT_SOURCE_DIR}/run_session.py"
                --bin-dir "$<TARGET_FILE_DIR:test_memory>"
                --work-dir "${CMAKE_BINARY_DIR}/test-work"
                --llama-src "${PROSEPLOW_LLAMA_SRC}"
                --memory
                ${model})
    set_tests_properties(${model}.memory PROPERTIES
        SKIP_RETURN_CODE 77
        RESOURCE_LOCK proseplow_cpu
        TIMEOUT 900
        ENVIRONMENT "PYTHONDONTWRITEBYTECODE=1;CUDA_VISIBLE_DEVICES=")
endfunction()
proseplow_add_memory_test(peacebell-v1-291m)
proseplow_add_memory_test(boris-2-instruct)
proseplow_add_memory_test(qwen3.5-0.8b)
proseplow_add_memory_test(shard-2)

# One residue entry per bundled model: they are what the app forgets.
function(proseplow_add_residue_test model)
    add_test(NAME ${model}.residue
        COMMAND "${PROSEPLOW_PYTHON}" "${CMAKE_CURRENT_SOURCE_DIR}/run_session.py"
                --bin-dir "$<TARGET_FILE_DIR:test_residue>"
                --work-dir "${CMAKE_BINARY_DIR}/test-work"
                --llama-src "${PROSEPLOW_LLAMA_SRC}"
                --residue
                ${model})
    set_tests_properties(${model}.residue PROPERTIES
        SKIP_RETURN_CODE 77
        RESOURCE_LOCK proseplow_cpu
        TIMEOUT 600
        ENVIRONMENT "PYTHONDONTWRITEBYTECODE=1;CUDA_VISIBLE_DEVICES=")
endfunction()
proseplow_add_residue_test(peacebell-v1-291m)
proseplow_add_residue_test(boris-2-instruct)
proseplow_add_residue_test(qwen3.5-0.8b)
proseplow_add_residue_test(shard-2)

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
# Shard-2, as the file that would ship: attention only like peacebell, but with windowed layers,
# whose checkpoint is that window and whose memory, once a checkpoint was restored, can only be
# cut back as far as the restored window reaches.
proseplow_add_session_test(shard-2)
