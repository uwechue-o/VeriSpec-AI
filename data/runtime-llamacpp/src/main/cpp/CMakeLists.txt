cmake_minimum_required(VERSION 3.22.1)
project(verispec_llama LANGUAGES C CXX)

# Opt-in native build (see data/runtime-llamacpp/build.gradle.kts).
# LLAMA_CPP_DIR must point at a llama.cpp checkout (https://github.com/ggml-org/llama.cpp).
if(NOT DEFINED LLAMA_CPP_DIR OR LLAMA_CPP_DIR STREQUAL "")
    message(FATAL_ERROR "LLAMA_CPP_DIR is not set. Pass -Pverispec.llamaCppDir=/path/to/llama.cpp")
endif()

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Keep llama.cpp lean: no examples, tests or server; shared libs off so the JNI
# library is self-contained (one .so in the APK, hashable as provenance).
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
# Neutrality: no vendor-specific tuning flags here. Backends (Vulkan/OpenCL) are
# opt-in via -DGGML_VULKAN=ON etc. and are recorded in the bundle's system info.

add_subdirectory(${LLAMA_CPP_DIR} ${CMAKE_CURRENT_BINARY_DIR}/llama.cpp EXCLUDE_FROM_ALL)

# Provenance: the exact llama.cpp release and commit compiled in, reported as
# the bundle's runtime version. Read from git rather than llama.cpp's own build
# number, which counts commits and is wrong in a shallow checkout.
find_package(Git QUIET)
set(VERISPEC_LLAMA_VERSION "unknown")
if(GIT_FOUND)
    execute_process(COMMAND ${GIT_EXECUTABLE} -C ${LLAMA_CPP_DIR} describe --tags --always
                    OUTPUT_VARIABLE LLAMA_TAG OUTPUT_STRIP_TRAILING_WHITESPACE ERROR_QUIET)
    execute_process(COMMAND ${GIT_EXECUTABLE} -C ${LLAMA_CPP_DIR} rev-parse --short=12 HEAD
                    OUTPUT_VARIABLE LLAMA_COMMIT OUTPUT_STRIP_TRAILING_WHITESPACE ERROR_QUIET)
    if(LLAMA_TAG)
        set(VERISPEC_LLAMA_VERSION "${LLAMA_TAG} (${LLAMA_COMMIT})")
    endif()
endif()
message(STATUS "verispec_llama: llama.cpp ${VERISPEC_LLAMA_VERSION}")

add_library(verispec_llama SHARED llama_jni.cpp)
target_include_directories(verispec_llama PRIVATE ${LLAMA_CPP_DIR}/include ${LLAMA_CPP_DIR}/ggml/include)
target_link_libraries(verispec_llama PRIVATE llama ggml log android)
target_compile_definitions(verispec_llama PRIVATE VERISPEC_LLAMA_VERSION="${VERISPEC_LLAMA_VERSION}")
# llama_batch_get_one is deprecated in favour of the batch_ext API but still supported.
target_compile_options(verispec_llama PRIVATE -fvisibility=hidden -O2 -Wno-deprecated-declarations)
