cmake_minimum_required(VERSION 3.22.1)

project("ai-chat" VERSION 1.0.0 LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED true)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED true)

set(CMAKE_C_FLAGS   "${CMAKE_C_FLAGS}"   CACHE STRING "" FORCE)
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS}" CACHE STRING "" FORCE)

# --------------------------------------------------------------------------
# AI Chat library
# --------------------------------------------------------------------------

if(DEFINED ANDROID_ABI)
    message(STATUS "Detected Android ABI: ${ANDROID_ABI}")
    if(ANDROID_ABI STREQUAL "arm64-v8a")
        set(GGML_SYSTEM_ARCH "ARM")
        set(GGML_CPU_KLEIDIAI ON)
        set(GGML_OPENMP ON)
    elseif(ANDROID_ABI STREQUAL "x86_64")
        set(GGML_SYSTEM_ARCH "x86")
        set(GGML_CPU_KLEIDIAI OFF)
        set(GGML_OPENMP OFF)
    else()
        message(FATAL_ERROR "Unsupported ABI: ${ANDROID_ABI}")
    endif()
endif()

# Where llama.cpp lives. Two layouts are supported, checked in this order:
#
#   1. The documented one, which SETUP.md and setup.sh produce - this project sits inside a
#      llama.cpp checkout at examples/entity.android/, so the root is six levels up.
#   2. A sibling checkout, ../llama.cpp next to the project. Convenient when the app is
#      cloned on its own and llama.cpp is fetched beside it.
#
# Probing rather than hardcoding matters because judges and contributors follow SETUP.md,
# while a local working copy is often laid out the other way. Hardcoding either one silently
# breaks the other.
set(LLAMA_SRC ${CMAKE_CURRENT_LIST_DIR}/../../../../../../)
if(NOT EXISTS ${LLAMA_SRC}/ggml/CMakeLists.txt)
    set(LLAMA_SRC ${CMAKE_CURRENT_LIST_DIR}/../../../../../../../llama.cpp)
endif()
if(NOT EXISTS ${LLAMA_SRC}/ggml/CMakeLists.txt)
    message(FATAL_ERROR
        "llama.cpp not found. Place this project at <llama.cpp>/examples/entity.android "
        "(see SETUP.md, or run setup.sh), or clone llama.cpp as a sibling directory.")
endif()
add_subdirectory(${LLAMA_SRC} build-llama)

add_library(${CMAKE_PROJECT_NAME} SHARED
        ai_chat.cpp)

target_compile_definitions(${CMAKE_PROJECT_NAME} PRIVATE
        GGML_SYSTEM_ARCH=${GGML_SYSTEM_ARCH}
        GGML_CPU_KLEIDIAI=$<BOOL:${GGML_CPU_KLEIDIAI}>
        GGML_OPENMP=$<BOOL:${GGML_OPENMP}>
)

target_include_directories(${CMAKE_PROJECT_NAME} PRIVATE
        ${LLAMA_SRC}
        ${LLAMA_SRC}/common
        ${LLAMA_SRC}/include
        ${LLAMA_SRC}/ggml/include
        ${LLAMA_SRC}/ggml/src)

target_link_libraries(${CMAKE_PROJECT_NAME}
        llama
        llama-common
        android
        log)
