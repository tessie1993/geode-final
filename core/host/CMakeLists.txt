# Host build of the pure native core plus its GoogleTest suite. CI runs it on Linux with no
# Android SDK, NDK or GPU (docs/ci.md); the device build stays in the root CMakeLists.txt.
#
#   cmake -S core/host -B build-host -DGEODE_SANITIZE=ON && cmake --build build-host && ctest --test-dir build-host
cmake_minimum_required(VERSION 3.22)
project(geode_host C CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

option(GEODE_SANITIZE "Build everything with AddressSanitizer and UndefinedBehaviorSanitizer" OFF)
# ThreadSanitizer cannot share a build with AddressSanitizer, so the concurrency tests get a build of their own.
option(GEODE_SANITIZE_THREAD "Build everything with ThreadSanitizer" OFF)
if(GEODE_SANITIZE AND GEODE_SANITIZE_THREAD)
    message(FATAL_ERROR "GEODE_SANITIZE and GEODE_SANITIZE_THREAD need separate builds")
endif()
if(GEODE_SANITIZE)
    add_compile_options(-fsanitize=address,undefined -fno-sanitize-recover=all -fno-omit-frame-pointer)
    add_link_options(-fsanitize=address,undefined)
endif()
if(GEODE_SANITIZE_THREAD)
    add_compile_options(-fsanitize=thread -fno-omit-frame-pointer)
    add_link_options(-fsanitize=thread)
endif()

get_filename_component(GEODE_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../.." ABSOLUTE)

# The pure libraries; each one can be mutation tested on its own.
set(GEODE_PURE_LIBRARIES geode_util geode_bridge geode_analysis geode_audio geode_viz)

# Mutation testing with Mull (docs/ci.md, "C++ mutation tests"). GEODE_MUTATE names the one pure
# library whose code Mull's compiler plugin mutates; the rest of the build is left unmutated. There is
# no source coverage: with it, Mull 0.34 marks mutants inside switch cases as NoCoverage although the
# tests run them, and never runs them. Without it every mutant runs, and one no test reaches simply
# survives. CI only; it changes how the host build is compiled, never what the code does.
#
#   MULL_CONFIG=config/mutation/mull.yml cmake -S core/host -B build-mutation \
#       -DGEODE_MUTATE=geode_analysis -DGEODE_MULL_PLUGIN=/usr/lib/mull-ir-frontend-18
set(GEODE_MUTATE "" CACHE STRING "Pure library target for Mull to mutate (empty: no mutation)")
set(GEODE_MULL_PLUGIN "" CACHE FILEPATH "Mull's compiler plugin, mull-ir-frontend-<LLVM major>")
if(GEODE_MUTATE)
    if(NOT GEODE_MUTATE IN_LIST GEODE_PURE_LIBRARIES)
        message(FATAL_ERROR "GEODE_MUTATE=${GEODE_MUTATE} is not one of: ${GEODE_PURE_LIBRARIES}")
    endif()
    if(GEODE_SANITIZE)
        message(FATAL_ERROR "GEODE_MUTATE and GEODE_SANITIZE are separate builds")
    endif()
    if(NOT CMAKE_CXX_COMPILER_ID MATCHES "Clang")
        message(FATAL_ERROR "GEODE_MUTATE needs clang: Mull is an LLVM pass plugin")
    endif()
    if(NOT EXISTS "${GEODE_MULL_PLUGIN}")
        message(FATAL_ERROR "GEODE_MULL_PLUGIN='${GEODE_MULL_PLUGIN}' does not exist")
    endif()
    # Mull maps mutants to source lines through debug info, and records the compile command in it.
    add_compile_options(-O0 -g -grecord-command-line)
endif()

# Same kissfft configuration as the device build.
set(BUILD_SHARED_LIBS OFF)
set(KISSFFT_DATATYPE "float" CACHE STRING "" FORCE)
set(KISSFFT_STATIC ON CACHE BOOL "" FORCE)
set(KISSFFT_TEST OFF CACHE BOOL "" FORCE)
set(KISSFFT_TOOLS OFF CACHE BOOL "" FORCE)
set(KISSFFT_PKGCONFIG OFF CACHE BOOL "" FORCE)
add_subdirectory("${GEODE_ROOT}/third_party/kissfft" kissfft)

# The pure part of each native library (core/pure_sources.cmake), built as the same targets with the
# same public and private include directories as the device build (core/native_libraries.cmake).
include("${GEODE_ROOT}/core/pure_sources.cmake")
include("${GEODE_ROOT}/core/native_libraries.cmake")
geode_api_types_library()
geode_core_library(geode_util util ${GEODE_UTIL_PURE_SOURCES})
geode_core_library(geode_bridge bridge ${GEODE_BRIDGE_PURE_SOURCES})
target_link_libraries(geode_bridge PUBLIC geode_api_types)
geode_core_library(geode_analysis analysis ${GEODE_ANALYSIS_PURE_SOURCES})
target_link_libraries(geode_analysis PUBLIC geode_api_types kissfft::kissfft)
geode_core_library(geode_audio audio ${GEODE_AUDIO_PURE_SOURCES})
target_link_libraries(geode_audio PUBLIC geode_api_types PRIVATE geode_util)
geode_core_library(geode_viz viz ${GEODE_VIZ_PURE_SOURCES})
target_link_libraries(geode_viz PUBLIC geode_api_types PRIVATE geode_analysis geode_util)
foreach(geode_lib ${GEODE_PURE_LIBRARIES})
    target_compile_options(${geode_lib} PRIVATE -Wall -Wextra)
endforeach()
if(GEODE_MUTATE)
    target_compile_options(${GEODE_MUTATE} PRIVATE "-fpass-plugin=${GEODE_MULL_PLUGIN}")
endif()
add_library(geode_core_pure INTERFACE)
target_link_libraries(geode_core_pure INTERFACE ${GEODE_PURE_LIBRARIES})

include(FetchContent)
FetchContent_Declare(
    googletest
    URL https://github.com/google/googletest/archive/refs/tags/v1.17.0.tar.gz
    DOWNLOAD_EXTRACT_TIMESTAMP TRUE)
set(INSTALL_GTEST OFF CACHE BOOL "" FORCE)
set(BUILD_GMOCK OFF CACHE BOOL "" FORCE)
FetchContent_MakeAvailable(googletest)

enable_testing()
file(GLOB GEODE_HOST_TEST_SOURCES CONFIGURE_DEPENDS "${GEODE_ROOT}/core/tests/*_test.cpp")
add_executable(geode_core_tests ${GEODE_HOST_TEST_SOURCES})
target_compile_options(geode_core_tests PRIVATE -Wall -Wextra)
target_link_libraries(geode_core_tests PRIVATE geode_core_pure GTest::gtest_main)
# The asset root the Style packs ship from (styles/ under it), so the tests read the real packs, and
# the shared shader libraries (shaders/lib_*.glsl).
target_compile_definitions(geode_core_tests PRIVATE
    GEODE_STYLE_PACK_ROOT="${GEODE_ROOT}/engine/scenes/src/main/stylepacks"
    GEODE_SHADER_ROOT="${GEODE_ROOT}/app/src/main/assets/shaders")
include(GoogleTest)
gtest_discover_tests(geode_core_tests)
