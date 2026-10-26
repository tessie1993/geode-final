cmake_minimum_required(VERSION 3.22.1)
project(geode C CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

set(GEODE_PAGE_FLAGS "-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384")
set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} ${GEODE_PAGE_FLAGS}")
set(CMAKE_MODULE_LINKER_FLAGS "${CMAKE_MODULE_LINKER_FLAGS} ${GEODE_PAGE_FLAGS}")

set(GEODE_COMPILE_OPTIONS
    -std=c++20
    -fvisibility=hidden
    -Wall
    -Wextra
    $<$<CONFIG:Release>:-O2>
    $<$<CONFIG:Release>:-flto>)
set(GEODE_LINK_OPTIONS
    -Wl,-z,max-page-size=16384
    -Wl,-z,common-page-size=16384
    $<$<CONFIG:Release>:-flto>)

# projectM is the stock v4.1.8 submodule plus Geode's patches (render into a caller's FBO, the render
# clock and seed), applied here so the submodule stays pinned to the tag. See tools/projectm-patches.cmake.
include(tools/projectm-patches.cmake)
set(GEODE_PROJECTM_DIR "${CMAKE_CURRENT_SOURCE_DIR}/third_party/projectm")
geode_patch_projectm("${GEODE_PROJECTM_DIR}")

# Same values .github/workflows/native-libs.yml passed when the engine was a prebuilt blob.
set(CMAKE_BUILD_TYPE Release CACHE STRING "" FORCE)
set(BUILD_SHARED_LIBS ON CACHE BOOL "" FORCE)
set(ENABLE_PLAYLIST OFF CACHE BOOL "" FORCE)
add_subdirectory(third_party/projectm)

# kissfft refuses BUILD_SHARED_LIBS together with KISSFFT_STATIC; the normal variable shadows the forced cache entry projectM needed.
set(BUILD_SHARED_LIBS OFF)
set(KISSFFT_DATATYPE "float" CACHE STRING "" FORCE)
set(KISSFFT_STATIC ON CACHE BOOL "" FORCE)
set(KISSFFT_TEST OFF CACHE BOOL "" FORCE)
set(KISSFFT_TOOLS OFF CACHE BOOL "" FORCE)
set(KISSFFT_PKGCONFIG OFF CACHE BOOL "" FORCE)
add_subdirectory(third_party/kissfft)

set(BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(BUILD_BINDINGS OFF CACHE BOOL "" FORCE)
set(BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WITH_ZLIB OFF CACHE BOOL "" FORCE)
set(VISIBILITY_HIDDEN ON CACHE BOOL "" FORCE)
add_subdirectory(third_party/taglib)

add_subdirectory(third_party/oboe)

# The Style lab (docs/style-packs.md, "Authoring loop"): Style packs pushed with adb reload without a
# restart. Only the debug build type turns it on (app/build.gradle.kts); a release build compiles no
# hot-reload path. Set for every target below, so each one sees the same class layouts.
option(GEODE_STYLE_HOT_RELOAD "Build the Style lab's hot reload (debug builds only)" OFF)
if(GEODE_STYLE_HOT_RELOAD)
    add_compile_definitions(GEODE_STYLE_HOT_RELOAD=1)
endif()

add_subdirectory(core)

add_library(geode SHARED
    app/src/main/cpp/geode_jni.cpp
    app/src/main/cpp/geode_viz_jni.cpp
    app/src/main/cpp/geode_tags_jni.cpp
    app/src/main/cpp/geode_player_jni.cpp
    app/src/main/cpp/jni_guard.cpp)
target_compile_options(geode PRIVATE ${GEODE_COMPILE_OPTIONS})
target_link_options(geode PRIVATE ${GEODE_LINK_OPTIONS})
target_link_libraries(geode PRIVATE geode_core log android)
