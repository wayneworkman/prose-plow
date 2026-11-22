# Host build of the native side of Prose Plow: the patched llama.cpp, our own library on top of
# libllama, and the tests that validate both against the PyTorch references.
#
# The Android build will add this same directory as its native project; anything host-only
# (tests, the tools they need) is behind PROSEPLOW_BUILD_TESTS.
cmake_minimum_required(VERSION 3.22)
project(proseplow_native LANGUAGES C CXX)

# Build outputs never go next to the sources: a build tree under native/ would be picked up by
# source searches and could end up in a commit.
file(RELATIVE_PATH _rel_build "${CMAKE_CURRENT_SOURCE_DIR}" "${CMAKE_BINARY_DIR}")
if (NOT _rel_build MATCHES "^\\.\\./")
    message(FATAL_ERROR "Build out of tree: pass -B a scratch directory outside native/ (see native/README.md)")
endif()

if (NOT CMAKE_BUILD_TYPE AND NOT CMAKE_CONFIGURATION_TYPES)
    set(CMAKE_BUILD_TYPE Release CACHE STRING "Build type" FORCE)
endif()

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

option(PROSEPLOW_BUILD_TESTS "Build the host tests and the tools they use" ON)
# The patched copy of llama.cpp. The environment variable lets the converters and the host
# build share one copy; without it the copy lives in the build tree, which is what a build
# driven by another tool (the Android one) wants.
if (DEFINED ENV{PROSEPLOW_LLAMA_SRC} AND NOT "$ENV{PROSEPLOW_LLAMA_SRC}" STREQUAL "")
    set(_llama_src_default "$ENV{PROSEPLOW_LLAMA_SRC}")
else()
    set(_llama_src_default "${CMAKE_BINARY_DIR}/llama-src")
endif()
set(PROSEPLOW_LLAMA_SRC "${_llama_src_default}" CACHE PATH
    "The patched copy of llama.cpp written by tools/prepare-llama-src.sh")

#
# The patched llama.cpp
#

# The prepare script is the only way a llama.cpp source tree reaches a build. It is idempotent,
# so running it on every configure costs nothing when the inputs did not change, and the
# dependencies below make an incremental build re-run it when a patch or an added file changes.
execute_process(
    COMMAND "${CMAKE_CURRENT_SOURCE_DIR}/tools/prepare-llama-src.sh" "${PROSEPLOW_LLAMA_SRC}"
    RESULT_VARIABLE _prepare_result)
if (NOT _prepare_result EQUAL 0)
    message(FATAL_ERROR "tools/prepare-llama-src.sh failed; see the message above")
endif()
file(GLOB_RECURSE _prepare_inputs CONFIGURE_DEPENDS
    "${CMAKE_CURRENT_SOURCE_DIR}/patches/*"
    "${CMAKE_CURRENT_SOURCE_DIR}/llama-ext/*")
set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS
    ${_prepare_inputs} "${CMAKE_CURRENT_SOURCE_DIR}/tools/prepare-llama-src.sh")

# CPU only, static, no optional dependency picked up from the build machine. OpenMP is off so
# that the thread count is exactly what the caller passes to llama.cpp; ccache is off so that a
# build never depends on a cache outside the build directory.
set(BUILD_SHARED_LIBS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON   OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP      OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP          OFF CACHE BOOL "" FORCE)
set(GGML_CCACHE          OFF CACHE BOOL "" FORCE)
set(GGML_CUDA            OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN          OFF CACHE BOOL "" FORCE)
set(GGML_OPENCL          OFF CACHE BOOL "" FORCE)
set(GGML_BLAS            OFF CACHE BOOL "" FORCE)

# llama.cpp and ggml stamp the binary with "the current git commit" of their source directory.
# The patched copy is not a repository, but it sits inside this one, so git would report this
# repository's commit and dirty state: wrong, and different on every commit of the app. The
# ceiling stops that lookup, and the version strings are set from the pinned upstream commit.
file(STRINGS "${CMAKE_CURRENT_SOURCE_DIR}/patches/UPSTREAM_COMMIT" _upstream_commit LIMIT_COUNT 1)
string(SUBSTRING "${_upstream_commit}" 0 7 LLAMA_BUILD_COMMIT)
set(LLAMA_BUILD_NUMBER 0)
get_filename_component(_llama_src_parent "${PROSEPLOW_LLAMA_SRC}" DIRECTORY)
set(ENV{GIT_CEILING_DIRECTORIES} "${_llama_src_parent}")

add_subdirectory("${PROSEPLOW_LLAMA_SRC}" "${CMAKE_BINARY_DIR}/llama.cpp" EXCLUDE_FROM_ALL)

unset(ENV{GIT_CEILING_DIRECTORIES})

# llama.cpp's helper library carries an HTTP client and a model downloader. The app must never
# link it, so its mere presence in the build is an error rather than something to avoid by care.
foreach (_forbidden common llama-common)
    if (TARGET ${_forbidden})
        message(FATAL_ERROR "The ${_forbidden} target exists: only libllama may be built and linked")
    endif()
endforeach()

#
# Our library and its tests
#

add_subdirectory(lib)

if (PROSEPLOW_BUILD_TESTS)
    enable_testing()
    add_subdirectory(tests)
endif()
add_subdirectory(session)  # the generation session and its tests; after enable_testing() so that ctest sees them
