# The Android native build of Prose Plow: the patched llama.cpp as shared libraries with every
# CPU variant, our two static libraries on top of it, and the JNI binding.
#
# Gradle (llama/build.gradle.kts) runs this with the NDK's toolchain file. It passes
#   PROSEPLOW_LLAMA_SRC   the patched copy of llama.cpp, written by the prepareLlamaSource task
# and everything else about the build is decided here, in one place that is reviewed with the
# code, rather than spread over Gradle arguments.
#
# The host build (native/CMakeLists.txt) compiles the same sources differently on purpose:
# static, tuned to the build machine, with the tests. What the two have in common is the
# prepared source tree, native/lib and native/session.
cmake_minimum_required(VERSION 3.22)
project(proseplow_android LANGUAGES C CXX)

if (NOT ANDROID)
    message(FATAL_ERROR "This is the Android build. The host build is native/CMakeLists.txt (see native/README.md).")
endif()
if (NOT PROSEPLOW_LLAMA_SRC OR NOT EXISTS "${PROSEPLOW_LLAMA_SRC}/.proseplow-prepared")
    message(FATAL_ERROR "PROSEPLOW_LLAMA_SRC must name a copy written by native/tools/prepare-llama-src.sh; "
                        "Gradle's prepareLlamaSource task does that before it configures this project.")
endif()

get_filename_component(_repo "${CMAKE_CURRENT_SOURCE_DIR}/../../../.." ABSOLUTE)
set(_native "${_repo}/native")

if (NOT CMAKE_BUILD_TYPE)
    set(CMAKE_BUILD_TYPE Release CACHE STRING "Build type" FORCE)
endif()

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

#
# Reproducibility: the same sources give the same bytes wherever they are checked out
#

# Source paths end up in binaries twice: in the __FILE__ of every assertion, and in the debug
# information, which is stripped before packaging but is hashed into the build id that stays.
# Each directory that can differ between two machines is therefore replaced by a fixed name.
# The order matters: clang lets the most specific prefix win for __FILE__ and the last matching
# option win for debug information, and the build tree and the prepared copy lie inside the
# repository, so they come after it.
add_compile_options(
    "-ffile-prefix-map=${_repo}=."
    "-ffile-prefix-map=${CMAKE_ANDROID_NDK}=ndk"
    "-ffile-prefix-map=${CMAKE_BINARY_DIR}=build"
    "-ffile-prefix-map=${PROSEPLOW_LLAMA_SRC}=llama.cpp")

# llama.cpp and ggml stamp the binary with "the current git commit" of their source directory.
# The prepared copy is not a repository but lies inside this one, so git would report this
# repository's commit and dirty state. The ceiling stops that lookup, and the stamp is set from
# the pinned upstream commit, as in the host build.
file(STRINGS "${_native}/patches/UPSTREAM_COMMIT" _upstream_commit LIMIT_COUNT 1)
string(SUBSTRING "${_upstream_commit}" 0 7 LLAMA_BUILD_COMMIT)
set(LLAMA_BUILD_NUMBER 0)
get_filename_component(_llama_src_parent "${PROSEPLOW_LLAMA_SRC}" DIRECTORY)
set(ENV{GIT_CEILING_DIRECTORIES} "${_llama_src_parent}")

# A pinned patch or an added file that changes must reach the build: the Gradle task rewrites
# the copy, and this makes an existing build tree configure again when it does.
set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS "${PROSEPLOW_LLAMA_SRC}/.proseplow-prepared")

#
# Being a good neighbour on a shared build machine
#

# Gradle starts Ninja without a job limit, and Ninja then uses every processor. A job pool is
# the one limit that holds however Ninja is started. It is taken from the environment variable
# that also limits the host build, PROSEPLOW_JOBS, when this project is configured, and not
# from a Gradle argument: an argument would make Gradle keep a separate build tree for every
# value, and compile everything again for each.
if (DEFINED ENV{PROSEPLOW_JOBS} AND "$ENV{PROSEPLOW_JOBS}" MATCHES "^[1-9][0-9]*$")
    set_property(GLOBAL PROPERTY JOB_POOLS "proseplow=$ENV{PROSEPLOW_JOBS}")
    set(CMAKE_JOB_POOL_COMPILE proseplow)
    set(CMAKE_JOB_POOL_LINK proseplow)
    message(STATUS "At most $ENV{PROSEPLOW_JOBS} compile and link jobs at once (PROSEPLOW_JOBS)")
endif()

#
# llama.cpp
#

# Shared libraries with the CPU backend built once per instruction set (GGML_CPU_ALL_VARIANTS):
# at run time the best one for the phone's processor is loaded from the app's native library
# directory, which is why the app must have its libraries extracted there. One build then
# serves a 2020 phone and a current one alike. No -march is set anywhere else: each variant
# sets its own.
set(BUILD_SHARED_LIBS     ON  CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL       ON  CACHE BOOL "" FORCE)
set(GGML_CPU_ALL_VARIANTS ON  CACHE BOOL "" FORCE)
set(GGML_NATIVE           OFF CACHE BOOL "" FORCE)
# The thread count is exactly what the session passes; the NDK's OpenMP runtime would also be
# another library to package.
set(GGML_OPENMP           OFF CACHE BOOL "" FORCE)
# tinyBLAS: with native/patches/0002 it carries the kernel that multiplies 16-bit float weights
# by 32-bit activations on every ARM variant, which is what a prompt of an F16 model spends its
# time in.
set(GGML_LLAMAFILE        ON  CACHE BOOL "" FORCE)
# Nothing may come from outside the build tree: no compiler cache, no download at configure
# time (KleidiAI is fetched from the network when enabled).
set(GGML_CCACHE           OFF CACHE BOOL "" FORCE)
set(GGML_CPU_KLEIDIAI     OFF CACHE BOOL "" FORCE)
set(GGML_LTO              OFF CACHE BOOL "" FORCE)
# The CPU only, for now. Hexagon needs a proprietary SDK and can never be enabled here.
set(GGML_OPENCL           OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN           OFF CACHE BOOL "" FORCE)
set(GGML_HEXAGON          OFF CACHE BOOL "" FORCE)
set(GGML_RPC              OFF CACHE BOOL "" FORCE)
set(GGML_BLAS             OFF CACHE BOOL "" FORCE)
# Only libllama. llama.cpp's helper library ("common") carries an HTTP client and a downloader.
set(LLAMA_BUILD_COMMON    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS     OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS     OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES  OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP       OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL         OFF CACHE BOOL "" FORCE)
set(LLAMA_LLGUIDANCE      OFF CACHE BOOL "" FORCE)

add_subdirectory("${PROSEPLOW_LLAMA_SRC}" "${CMAKE_BINARY_DIR}/llama.cpp")

unset(ENV{GIT_CEILING_DIRECTORIES})

foreach (_forbidden common llama-common)
    if (TARGET ${_forbidden})
        message(FATAL_ERROR "The ${_forbidden} target exists: only libllama may be built and linked")
    endif()
endforeach()

#
# Our libraries and the JNI binding
#

add_subdirectory("${_native}/lib" "${CMAKE_BINARY_DIR}/proseplow-native")
add_subdirectory("${_native}/session" "${CMAKE_BINARY_DIR}/proseplow-session")

add_library(proseplow_jni SHARED proseplow_jni.cpp)
target_link_libraries(proseplow_jni PRIVATE proseplow_session)
target_compile_options(proseplow_jni PRIVATE -Wall -Wextra -Wpedantic -fvisibility=hidden)
# The library's whole interface is JNI_OnLoad, which registers the native methods by table: the
# C functions of the static libraries linked into it are not exported again.
target_link_options(proseplow_jni PRIVATE -Wl,--exclude-libs,ALL -Wl,--no-undefined)
