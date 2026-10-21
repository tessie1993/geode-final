# Shared by the device build (core/CMakeLists.txt) and the host test build (core/host/CMakeLists.txt).
#
# Each native library lives in core/<dir>/: its public headers in core/<dir>/include/geode/<dir>/
# (the only directory it exports, included as "geode/<dir>/Header.hpp") and its sources and private
# headers beside them (included relative to core/<dir>/). tools/ci/check_native_includes.py fails
# CI when a library includes another library's private header or one outside its dependency graph.
# The graph: docs/native-libraries.md.
set(GEODE_CORE_DIR "${CMAKE_CURRENT_LIST_DIR}")

# geode_core_library(<target> <dir> <source>...): a static library from sources relative to core/.
function(geode_core_library target dir)
    set(sources ${ARGN})
    list(TRANSFORM sources PREPEND "${GEODE_CORE_DIR}/")
    add_library(${target} STATIC ${sources})
    target_include_directories(${target}
        PUBLIC "${GEODE_CORE_DIR}/${dir}/include"
        PRIVATE "${GEODE_CORE_DIR}/${dir}")
endfunction()

# The C ABI header (geode/api/geode_api.h) is its own interface target: every library speaks its
# types, while the api library that implements it depends on all of them.
function(geode_api_types_library)
    add_library(geode_api_types INTERFACE)
    target_include_directories(geode_api_types INTERFACE "${GEODE_CORE_DIR}/api/include")
endfunction()
