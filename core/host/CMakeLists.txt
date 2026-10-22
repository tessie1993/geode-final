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
foreach(geode_lib geode_util geode_bridge geode_analysis geode_audio geode_viz)
    target_compile_options(${geode_lib} PRIVATE -Wall -Wextra)
endforeach()
add_library(geode_core_pure INTERFACE)
target_link_libraries(geode_core_pure INTERFACE geode_util geode_bridge geode_analysis geode_audio geode_viz)

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
# The asset root the Style packs ship from (styles/ under it), so the tests read the real packs.
target_compile_definitions(geode_core_tests PRIVATE
    GEODE_STYLE_PACK_ROOT="${GEODE_ROOT}/engine/scenes/src/main/stylepacks")
include(GoogleTest)
gtest_discover_tests(geode_core_tests)
