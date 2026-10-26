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
#   projectm-v4.1.8-texture-pool.patch         the noise textures are made once per engine (and per
#                                              seed), not on every new set of texture paths; hidden
#                                              folders are not scanned; a random texture picks each
#                                              file with the same chance (#307).
#   projectm-v4.1.8-lean-frame.patch           a preset that shows no motion vectors does not write
#                                              their u/v map every frame (#303).
#   projectm-v4.1.8-program-cache.patch        projectm_opengl_set_shader_caches: per-thread hooks that
#                                              keep linked programs and translated preset shaders, so
#                                              a preset seen before loads without either (#304).
#   projectm-v4.1.8-shader-extras.patch        the shader inputs and functions BeatDrop-era presets
#                                              use (spectrum and waveform textures, pointer, clock,
#                                              tanh and friends, random slots above 15), plus
#                                              projectm_set_pointer and a shader fallback callback (#306).
#   projectm-eval-render-clock.patch           (in vendor/projectm-eval) lets the seeded instance feed
#                                              the expression rand() as well.
#
# Order matters: each patch is written against the ones above it. Applying is idempotent: the chain is
# applied from the first patch not yet in the tree, found as the last one whose reverse check passes
# (an earlier patch can fail its own reverse check once a later one has changed lines around it), under
# a lock in the submodule's git folder so the per-ABI configures the Android Gradle plugin runs side by
# side never patch at once. Every configure after the first therefore finds the tree done.

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

# The patches applied to projectM itself, in order.
set(GEODE_PROJECTM_PATCHES
    render-fbo-backport
    render-clock
    plasma-mediump
    stereo-beats
    loudness
    texture-pool
    lean-frame
    program-cache
    shader-extras)

# Patches the projectM checkout at projectm_dir and keeps the build on the patched, vendored
# projectm-eval (a system-installed one would not carry the patch).
function(geode_patch_projectm projectm_dir)
    find_package(Git REQUIRED)
    execute_process(
        COMMAND "${GIT_EXECUTABLE}" rev-parse --absolute-git-dir
        WORKING_DIRECTORY "${projectm_dir}"
        OUTPUT_VARIABLE git_dir
        OUTPUT_STRIP_TRAILING_WHITESPACE
        RESULT_VARIABLE git_dir_result)
    if(git_dir_result EQUAL 0 AND IS_DIRECTORY "${git_dir}")
        file(LOCK "${git_dir}/geode-patch.lock" GUARD FUNCTION TIMEOUT 600)
    endif()
    # The first patch not in the tree yet: the one after the last whose reverse check passes. Nothing is
    # ever applied on top of the last patch in, so its reverse check holds even where earlier ones fail.
    list(LENGTH GEODE_PROJECTM_PATCHES count)
    set(start 0)
    set(index ${count})
    while(index GREATER 0)
        math(EXPR index "${index} - 1")
        list(GET GEODE_PROJECTM_PATCHES ${index} name)
        execute_process(
            COMMAND "${GIT_EXECUTABLE}" apply --reverse --check "${GEODE_PROJECTM_PATCH_DIR}/projectm-v4.1.8-${name}.patch"
            WORKING_DIRECTORY "${projectm_dir}"
            RESULT_VARIABLE applied
            OUTPUT_QUIET ERROR_QUIET)
        if(applied EQUAL 0)
            math(EXPR start "${index} + 1")
            break()
        endif()
    endwhile()
    if(start LESS count)
        math(EXPR last "${count} - 1")
        foreach(index RANGE ${start} ${last})
            list(GET GEODE_PROJECTM_PATCHES ${index} name)
            geode_apply_patch("${projectm_dir}" "${GEODE_PROJECTM_PATCH_DIR}/projectm-v4.1.8-${name}.patch")
        endforeach()
    endif()
    geode_apply_patch("${projectm_dir}/vendor/projectm-eval" "${GEODE_PROJECTM_PATCH_DIR}/projectm-eval-render-clock.patch")
    set(ENABLE_SYSTEM_PROJECTM_EVAL OFF CACHE BOOL "" FORCE)
endfunction()
