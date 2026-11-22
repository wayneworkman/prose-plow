# proseplow_session: the generation session, the native half of the seam the Kotlin engine
# drives (include/proseplow_session.h). One loaded model, one context, one conversation.
#
# It links proseplow_native and libllama and nothing else. Like proseplow_native it holds no
# host-only code and no JNI: the Android build compiles these same sources, and the JNI binding
# is a thin layer over the C interface.
add_library(proseplow_session STATIC
    src/session.cpp)

target_include_directories(proseplow_session PUBLIC include)
target_link_libraries(proseplow_session PUBLIC proseplow_native llama)
target_compile_options(proseplow_session PRIVATE -Wall -Wextra -Wpedantic)

if (PROSEPLOW_BUILD_TESTS)
    add_subdirectory(tests)
endif()
