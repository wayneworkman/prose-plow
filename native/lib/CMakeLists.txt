# proseplow_native: our own code above libllama, with a C interface (include/proseplow_native.h).
#
# It links libllama and nothing else from llama.cpp. Keep it free of host-only code: the same
# sources are compiled for Android.
add_library(proseplow_native STATIC
    src/chatml_text.cpp
    src/peacebell_tokenizer.cpp)

target_include_directories(proseplow_native PUBLIC include)
target_link_libraries(proseplow_native PUBLIC llama)
target_compile_options(proseplow_native PRIVATE -Wall -Wextra -Wpedantic)
