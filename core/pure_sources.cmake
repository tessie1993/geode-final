# The part of the native core that needs neither Android, GLES, projectM, Oboe nor TagLib: audio
# analysis, the DSP chain, the parameter model and the maths behind the Visual. It is listed once
# here so the device build (core/CMakeLists.txt) and the host test build (core/host/CMakeLists.txt)
# compile exactly the same files. Paths are relative to core/.
set(GEODE_CORE_PURE_SOURCES
    audio/dsp/Equalizer.cpp
    audio/dsp/Crossfeed.cpp
    audio/dsp/Limiter.cpp
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
    viz/Sha256.cpp
    viz/Params.cpp
    viz/ParamsFields.cpp
    viz/Lfo.cpp
    viz/Adsr.cpp
    viz/FormDrive.cpp
    viz/VisualSafety.cpp
    viz/fluid/FluidMath.cpp
    viz/fluid/RippleMath.cpp
    viz/scenes/CymaticsMath.cpp
    util/Json.cpp
)
