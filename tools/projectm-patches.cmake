# Geode's copy of projectM: the third_party/projectm submodule, pinned to the stock v4.1.8 tag, plus
# these local patches, applied at configure time so the submodule itself stays unmodified
# (tools/build-projectm.md). LGPL-2.1: the patches are the corresponding source of the modification.
# The app ships byte-identical copies in app/src/main/assets/third_party/projectm/ (THIRD_PARTY_NOTICES);
# tools/ci/check_notices.py fails when the two sets differ, so change or add a patch in both places.
#
#   projectm-v4.1.8-render-fbo-backport.patch  projectm_opengl_render_frame_fbo, backported from master;
#                                              MilkdropScene renders into its own framebuffer with it.
#   projectm-v4.1.8-render-clock.patch         projectm_set_frame_time and projectm_set_random_seed:
#                                              a Render drives the engine from the render clock with a
#                                              fixed seed, so the same input gives the same frames.
#   projectm-v4.1.8-plasma-mediump.patch       upstream master 0227b7a: the plasma transition's noise
#                                              no longer overflows at mediump on mobile GPUs.
#   projectm-v4.1.8-stereo-beats.patch         upstream master 494269e: bass, mid and treble from both
#                                              channels, now that Geode feeds stereo.
#   projectm-v4.1.8-loudness.patch             the loudness followers hold and read 1.0 in silence,
#                                              start from the first real value and warm up by time,
#                                              and projectm_set_beat_sensitivity scales their swing.
#   projectm-eval-render-clock.patch           (in vendor/projectm-eval) lets the seeded instance feed
#                                              the expression rand() as well.
#
# Order matters: the render-clock patch is written against the backport, and the loudness patch against
# the stereo-beats one. Applying is idempotent: a
# reverse check recognises an already-patched tree, which is what every configure after the first
# (and each per-ABI configure the Android Gradle plugin runs) sees.

set(GEODE_PROJECTM_PATCH_DIR "${CMAKE_CURRENT_LIST_DIR}")

function(geode_apply_patch working_dir patch)
    execute_process(
        COMMAND "${GIT_EXECUTABLE}" apply --reverse --check "${patch}"
        WORKING_DIRECTORY "${working_dir}"
        RESULT_VARIABLE already_applied
        OUTPUT_QUIET ERROR_QUIET)
    if(already_applied EQUAL 0)
        return()
    endif()
    execute_process(
        COMMAND "${GIT_EXECUTABLE}" apply "${patch}"
        WORKING_DIRECTORY "${working_dir}"
        RESULT_VARIABLE apply_result)
    if(apply_result EQUAL 0)
        return()
    endif()
    # A parallel configure may have applied it in between; only fail if it is still missing.
    execute_process(
        COMMAND "${GIT_EXECUTABLE}" apply --reverse --check "${patch}"
        WORKING_DIRECTORY "${working_dir}"
        RESULT_VARIABLE already_applied
        OUTPUT_QUIET ERROR_QUIET)
    if(NOT already_applied EQUAL 0)
        message(FATAL_ERROR
            "Could not apply ${patch} in ${working_dir}. "
            "Run 'git submodule update --init --recursive' and make sure third_party/projectm is at v4.1.8 "
            "with no other local changes.")
    endif()
endfunction()

# Patches the projectM checkout at projectm_dir and keeps the build on the patched, vendored
# projectm-eval (a system-installed one would not carry the patch).
function(geode_patch_projectm projectm_dir)
    find_package(Git REQUIRED)
    geode_apply_patch("${projectm_dir}" "${GEODE_PROJECTM_PATCH_DIR}/projectm-v4.1.8-render-fbo-backport.patch")
    geode_apply_patch("${projectm_dir}" "${GEODE_PROJECTM_PATCH_DIR}/projectm-v4.1.8-render-clock.patch")
    geode_apply_patch("${projectm_dir}" "${GEODE_PROJECTM_PATCH_DIR}/projectm-v4.1.8-plasma-mediump.patch")
    geode_apply_patch("${projectm_dir}" "${GEODE_PROJECTM_PATCH_DIR}/projectm-v4.1.8-stereo-beats.patch")
    geode_apply_patch("${projectm_dir}" "${GEODE_PROJECTM_PATCH_DIR}/projectm-v4.1.8-loudness.patch")
    geode_apply_patch("${projectm_dir}/vendor/projectm-eval" "${GEODE_PROJECTM_PATCH_DIR}/projectm-eval-render-clock.patch")
    set(ENABLE_SYSTEM_PROJECTM_EVAL OFF CACHE BOOL "" FORCE)
endfunction()
