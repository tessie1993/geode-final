include(${CMAKE_CURRENT_SOURCE_DIR}/pure_sources.cmake)
include(${CMAKE_CURRENT_SOURCE_DIR}/native_libraries.cmake)

# One static library per area, dependencies one way only (docs/native-libraries.md):
#
#   util, api_types  <-  bridge, render, analysis, audio, library  <-  viz  <-  api  <-  geode_core
#
# Every list below is relative to core/. The pure part of each library comes from pure_sources.cmake.

geode_api_types_library()
target_link_libraries(geode_api_types INTERFACE android)

geode_core_library(geode_util util
    ${GEODE_UTIL_PURE_SOURCES}
)
target_link_libraries(geode_util PUBLIC log)

geode_core_library(geode_bridge bridge
    ${GEODE_BRIDGE_PURE_SOURCES}
)
target_link_libraries(geode_bridge PUBLIC geode_api_types)

geode_core_library(geode_render render
    render/ShaderSource.cpp
    render/GlCaps.cpp
    render/GlProber.cpp
    render/GlProfile.cpp
    render/Compute.cpp
    render/ProgramBinaryCache.cpp
    render/Program.cpp
    render/Texture.cpp
    render/Framebuffer.cpp
    render/Quad.cpp
)
target_link_libraries(geode_render PUBLIC GLESv3 EGL android PRIVATE geode_util)

geode_core_library(geode_analysis analysis
    ${GEODE_ANALYSIS_PURE_SOURCES}
)
target_link_libraries(geode_analysis PUBLIC geode_api_types kissfft::kissfft)

geode_core_library(geode_audio audio
    ${GEODE_AUDIO_PURE_SOURCES}
    audio/dsp/geode_dsp_api.cpp
    audio/player/Resampler.cpp
    audio/player/Decoder.cpp
    audio/player/Mixer.cpp
    audio/player/Output.cpp
    audio/player/Player.cpp
)
target_link_libraries(geode_audio PUBLIC geode_api_types oboe mediandk PRIVATE geode_util)

geode_core_library(geode_library library
    library/Tags.cpp
)
# TagLib only exports its include paths for the install tree; the generated taglib_config.h sits in its binary dir.
target_include_directories(geode_library PRIVATE
    ${CMAKE_SOURCE_DIR}/third_party/taglib/taglib
    ${CMAKE_SOURCE_DIR}/third_party/taglib/taglib/toolkit
    ${CMAKE_BINARY_DIR}/third_party/taglib)
target_link_libraries(geode_library PUBLIC tag)

geode_core_library(geode_viz viz
    ${GEODE_VIZ_PURE_SOURCES}
    viz/TouchField.cpp
    viz/Transition.cpp
    viz/CompositeGrade.cpp
    viz/CompositePass.cpp
    viz/TrailPass.cpp
    viz/NoFlashPass.cpp
    viz/SceneRegistry.cpp
    viz/Renderer.cpp
    viz/RendererFrame.cpp
    viz/scenes/ShaderScene.cpp
    viz/BlueNoise.cpp
    viz/Overlays.cpp
    viz/fluid/FluidBuffers.cpp
    viz/fluid/FluidChoreography.cpp
    viz/fluid/FluidEmitters.cpp
    viz/fluid/FluidSim.cpp
    viz/fluid/FluidParticles.cpp
    viz/fluid/FluidLook.cpp
    viz/fluid/RippleSim.cpp
    viz/fluid/FlowField.cpp
    viz/scenes/StyleCatalog.cpp
    viz/scenes/CymaticsScene.cpp
    viz/scenes/LifeScene.cpp
    viz/scenes/MycoScene.cpp
    viz/scenes/AcidScene.cpp
    viz/scenes/FluidSceneBase.cpp
    viz/scenes/FluidScene.cpp
    viz/scenes/CurlFlowScene.cpp
    viz/scenes/WaterScene.cpp
    viz/scenes/MilkdropScene.cpp
)
target_link_libraries(geode_viz
    PUBLIC geode_api_types geode_render projectM GLESv3 EGL android
    PRIVATE geode_analysis geode_util)

# The C ABI the JNI layer calls (geode/api/geode_api.h), implemented over the libraries above.
geode_core_library(geode_api api
    api/geode_api.cpp
    api/geode_viz_api.cpp
    api/geode_tags_api.cpp
    api/geode_player_api.cpp
    api/geode_snapshot_api.cpp
)
target_link_libraries(geode_api
    PUBLIC geode_api_types
    PRIVATE geode_analysis geode_audio geode_bridge geode_library geode_viz geode_util)

foreach(geode_lib geode_util geode_bridge geode_render geode_analysis geode_audio geode_library geode_viz geode_api)
    target_compile_options(${geode_lib} PRIVATE ${GEODE_COMPILE_OPTIONS})
endforeach()

# What the app's JNI library links: the C ABI plus every library and system dependency it pulls in,
# the same link line the single geode_core static library gave it.
add_library(geode_core INTERFACE)
target_link_libraries(geode_core INTERFACE
    geode_api geode_viz geode_render geode_analysis geode_audio geode_library geode_bridge geode_util
    kissfft::kissfft projectM tag oboe mediandk GLESv3 EGL log android)
