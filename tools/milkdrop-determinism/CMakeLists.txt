# Host build of Geode's copy of projectM (the third_party/projectm submodule plus the patches in
# tools/projectm-patches.cmake) and of the frame-exactness check for MilkDrop Renders. CI runs it on
# Linux under Mesa's software GLES (docs/ci.md, "MilkDrop Renders are frame-exact").
#
#   cmake -S tools/milkdrop-determinism -B build-milkdrop && cmake --build build-milkdrop && ctest --test-dir build-milkdrop
cmake_minimum_required(VERSION 3.22)
project(geode_milkdrop_determinism C CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

get_filename_component(GEODE_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../.." ABSOLUTE)
include("${GEODE_ROOT}/tools/projectm-patches.cmake")
geode_patch_projectm("${GEODE_ROOT}/third_party/projectm")

# The app's projectM configuration (root CMakeLists.txt), on GLES 3 like the phone.
set(BUILD_SHARED_LIBS ON CACHE BOOL "" FORCE)
set(ENABLE_PLAYLIST OFF CACHE BOOL "" FORCE)
set(ENABLE_GLES ON CACHE BOOL "" FORCE)
set(ENABLE_SDL_UI OFF CACHE BOOL "" FORCE)
set(BUILD_TESTING OFF CACHE BOOL "" FORCE)
add_subdirectory("${GEODE_ROOT}/third_party/projectm" projectm)

find_library(GEODE_EGL_LIBRARY EGL REQUIRED)
add_executable(milkdrop_determinism milkdrop_determinism.cpp)
target_compile_options(milkdrop_determinism PRIVATE -Wall -Wextra)
target_link_libraries(milkdrop_determinism PRIVATE libprojectM::projectM "${GEODE_EGL_LIBRARY}")

enable_testing()
add_test(NAME milkdrop_renders_are_frame_exact
    COMMAND milkdrop_determinism
        "${CMAKE_CURRENT_SOURCE_DIR}/fixtures/random_drift.milk"
        "${CMAKE_CURRENT_SOURCE_DIR}/fixtures/random_bloom.milk"
        "${CMAKE_CURRENT_SOURCE_DIR}/fixtures")
# Mesa's software rasterizer, with no display server.
set_tests_properties(milkdrop_renders_are_frame_exact PROPERTIES
    ENVIRONMENT "EGL_PLATFORM=surfaceless;LIBGL_ALWAYS_SOFTWARE=1")
