# The part of each native library that needs neither Android, GLES, projectM, Oboe nor TagLib: audio
# analysis, the DSP chain, the parameter model and the maths behind the Visual. Each library's list is
# kept once here so the device build (core/CMakeLists.txt) and the host test build
# (core/host/CMakeLists.txt) compile exactly the same files per library. Only host-testable pure code
# belongs here. Paths are relative to core/. The libraries and their dependency graph: docs/native-libraries.md.
set(GEODE_UTIL_PURE_SOURCES
    util/Json.cpp
    util/Sha256.cpp
)
set(GEODE_AUDIO_PURE_SOURCES
    audio/dsp/Equalizer.cpp
    audio/dsp/Crossfeed.cpp
    audio/dsp/Limiter.cpp
    audio/player/BufferTuner.cpp
)
set(GEODE_ANALYSIS_PURE_SOURCES
    analysis/WindowTable.cpp
    analysis/Spectrum.cpp
    analysis/AdaptiveRange.cpp
    analysis/LogBands.cpp
    analysis/Chromagram.cpp
    analysis/SuperFlux.cpp
    analysis/HarmonicBalance.cpp
    analysis/OnsetPeakPicker.cpp
    analysis/AdaptiveWhitening.cpp
    analysis/TempoTracker.cpp
    analysis/TempoStability.cpp
    analysis/BeatGrid.cpp
    analysis/BarTracker.cpp
    analysis/DrumChannels.cpp
    analysis/KeyDetector.cpp
    analysis/StructureTracker.cpp
    analysis/StereoField.cpp
    analysis/FrameLevels.cpp
    analysis/PulseReplay.cpp
    analysis/ReactiveAnalyzer.cpp
    analysis/AnalysisSession.cpp
)
set(GEODE_VIZ_PURE_SOURCES
    viz/Params.cpp
    viz/ParamsFields.cpp
    viz/Lfo.cpp
    viz/Adsr.cpp
    viz/VisualSafety.cpp
    viz/FlashLimiter.cpp
    viz/BeatEnvelope.cpp
    viz/MilkdropAdaptation.cpp
    viz/fluid/FluidMath.cpp
    viz/fluid/RippleMath.cpp
    viz/scenes/CymaticsMath.cpp
)
# The render library (GL plumbing) and the library library (TagLib) have no pure part.

# Every pure source, for tools that want the whole set.
set(GEODE_CORE_PURE_SOURCES
    ${GEODE_UTIL_PURE_SOURCES}
    ${GEODE_AUDIO_PURE_SOURCES}
    ${GEODE_ANALYSIS_PURE_SOURCES}
    ${GEODE_VIZ_PURE_SOURCES}
)
