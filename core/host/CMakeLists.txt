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
if(GEODE_SANITIZE)
    add_compile_options(-fsanitize=address,undefined -fno-sanitize-recover=all -fno-omit-frame-pointer)
    add_link_options(-fsanitize=address,undefined)
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

include("${GEODE_ROOT}/core/pure_sources.cmake")
list(TRANSFORM GEODE_CORE_PURE_SOURCES PREPEND "${GEODE_ROOT}/core/")
add_library(geode_core_pure STATIC ${GEODE_CORE_PURE_SOURCES})
target_include_directories(geode_core_pure PUBLIC "${GEODE_ROOT}/core")
target_compile_options(geode_core_pure PRIVATE -Wall -Wextra)
target_link_libraries(geode_core_pure PUBLIC kissfft::kissfft)

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
include(GoogleTest)
gtest_discover_tests(geode_core_tests)
