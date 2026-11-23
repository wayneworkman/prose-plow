# proseplow_session: the generation session, the native half of the seam the Kotlin engine
# drives (include/proseplow_session.h). One loaded model, one context, one conversation.
#
# It links proseplow_native and libllama and nothing else. Like proseplow_native it holds no
# host-only code and no JNI: the Android build compiles these same sources, and the JNI binding
# is a thin layer over the C interface.
add_library(proseplow_session STATIC
    src/session.cpp
    src/state_stream.cpp)

target_include_directories(proseplow_session PUBLIC include)
# state_stream.cpp, and no other file, is compiled against two internal headers of llama.cpp
# (src/llama-io.h and src/llama-memory.h): it implements the interface llama.cpp writes and
# reads a state through, so that a memory can leave and enter a context in pieces. As a system
# directory, so that the warnings asked for below are about this project's code only.
if (NOT EXISTS "${PROSEPLOW_LLAMA_SRC}/src/llama-io.h" OR NOT EXISTS "${PROSEPLOW_LLAMA_SRC}/src/llama-memory.h")
    message(FATAL_ERROR "The prepared llama.cpp has no src/llama-io.h or src/llama-memory.h: see \"Moving the upstream pin\" in native/README.md")
endif()
set_source_files_properties(src/state_stream.cpp PROPERTIES COMPILE_OPTIONS "-isystem${PROSEPLOW_LLAMA_SRC}/src")
target_link_libraries(proseplow_session PUBLIC proseplow_native llama)
target_compile_options(proseplow_session PRIVATE -Wall -Wextra -Wpedantic)

if (PROSEPLOW_BUILD_TESTS)
    add_subdirectory(tests)
endif()
