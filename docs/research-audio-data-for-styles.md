# Research: the audio data high-end visualizers give their Styles

What projectM/MilkDrop, Butterchurn, Synesthesia, TouchDesigner, Resolume, Shadertoy and the Web Audio
analyser hand their visuals each frame, set against what Geode's Styles get today, and the improvements
that follow. Written after the native audio feed (#276) was undone and the Kotlin rings restored
(`docs/AUDIO_CHAIN.md`): the sound reaches the engine as the mono `PcmRingBuffer`'s new samples
(`geode_viz_push_pcm`) plus the analysis frame (`GeodeFeatureFrame`, `geode_viz_set_features`).

> **How this was checked.** Sources were read on 2026-10-27 and are listed under each section. Where a
> product's documentation does not state a number, the text says so rather than guessing.

---

## 1. What Geode's Styles get today

- **The analysis frame** (`core/api/include/geode/api/geode_api.h`): 33 scalars (rms, bass, mid, treble,
  centroid, flux, onset, beat and its strength and phase, pulse confidence, bpm, tempo stability, bar
  phase and beat-in-bar, downbeat, macro energy, kick/snare/hat, novelty, section boundary, buildup, drop,
  arrival, harmonicity, warmup, stereo width/correlation/pan, chroma confidence), 64 log bands, a
  128-point decimated waveform, 12 chroma bins and 9 sequenced one-hop events.
- **The feature bus** (`core/viz/include/geode/viz/FeatureBus.hpp`) turns that into the shader uniforms of
  `lib_scene_features.glsl` (33 uniforms): rhythm envelopes, trust, structure phases, hits, timbre, space,
  and the three bands against the song's usual level with their hits and presence (#319).
- **The audio texture** (`core/viz/include/geode/viz/AudioInputs.hpp`, `uAudioTex`, 512 x 8): a log
  spectrum 30 Hz to 16 kHz on one dB scale that follows the loudest texel (+3 dB/octave tilt so pink noise
  reads flat), a peak-hold row, an unsmoothed row, and an aligned oscilloscope as mid, left and right.
- **MilkDrop** gets a 576-sample window a frame (`projectm_pcm_add_float`).

Two things changed when the mono ring came back: `awaveL`/`awaveR` now read the mid (the ring is mono
plus side), and projectM gets `PROJECTM_MONO` again, so the upstream stereo-beats patch (#296) has
nothing to split. Chroma and the key have never reached the shaders.

## 2. What the references give

| Reference | Per-frame audio data for visuals | What Geode can take from it |
|---|---|---|
| **projectM 4** (`libprojectM/Audio`) | Stereo kept apart: a 576-sample ring per channel, 480 waveform samples per channel for drawing, a 512-point spectrum per channel; bass/mid/treble are the first three sixths of the (half) spectrum, summed, each as `current / longAverage` ("bass") and `fastAverage / longAverage` ("bass_att"), long average decaying at 0.992 per frame at 30 fps (about 4 s), fast at 0.2 rising and 0.5 falling. Beat bands from the mono average of the two spectra. [AudioConstants](https://raw.githubusercontent.com/projectM-visualizer/projectm/master/src/libprojectM/Audio/AudioConstants.hpp), [PCM.cpp](https://raw.githubusercontent.com/projectM-visualizer/projectm/master/src/libprojectM/Audio/PCM.cpp), [Loudness](https://raw.githubusercontent.com/projectM-visualizer/projectm/master/src/libprojectM/Audio/Loudness.cpp) | Stereo PCM to the engine; per-channel spectra; the relative-to-usual reading Geode already has (#319). |
| **Butterchurn** (WebGL MilkDrop) | Three analysers: the mix, left and right (1024-sample frames, 512-sample downsampled waves per channel). Bands by frequency, not bins: bass 20–320 Hz, mid 320–2800 Hz, treble 2800–11025 Hz, with the same imm/avg/longAvg scheme as projectM, rates raised to `30 / fps` so they are frame-rate independent. [audioProcessor.js](https://raw.githubusercontent.com/jberg/butterchurn/master/src/audio/audioProcessor.js), [audioLevels.js](https://raw.githubusercontent.com/jberg/butterchurn/master/src/audio/audioLevels.js) | Band edges in Hz, independent of the FFT size; L and R analysed as well as the mix. |
| **Synesthesia** (SSF) | Four bands (bass, mid, midhigh, high) each with Level (0..1, smoothed), Presence (slow activity) and Hits (transients); band **Time** clocks that run faster when the band is loud, plus `syn_CurvedTime`; beat: `syn_OnBeat`, toggle and random on beat, `syn_BeatTime`; BPM with confidence and beat-synced sine and triangle waves at 1, 1/2, 1/4, 1/8 the tempo; `syn_Spectrum` as one texture with four channels: raw FFT, "juiced" FFT (bin and temporal smoothing, log scale, high-end boost), a low-resolution smooth FFT, and the waveform; `syn_LevelTrail`, the band levels over time as a texture. [SSF Audio Uniforms](https://app.synesthesia.live/docs/ssf/audio_uniforms.html) | Band clocks; a fourth band; a low-resolution spectrum row; a level-history texture; beat-synced oscillators at bar divisions. |
| **TouchDesigner** `audioAnalysis` | Low/Mid/High levels with threshold, smooth, gain and add per band; kick, snare and rhythm as 0/1 pulses; spectral centroid; slow- and fast-moving partials density. [Palette:audioAnalysis](https://derivative.ca/UserGuide/Palette:audioAnalysis) | A "how much of the spectrum is moving" reading, slow and fast. |
| **Resolume Arena / Wire** | 9 FFT bands per channel, left and right (18 in all), Low/Mid/High as averages of band ranges with per-parameter gain and fall; Wire gets the raw 1024-bin FFT. [Resolume forum, manual quote](https://resolume.com/forum/viewtopic.php?p=9341), [Wire FFT](https://resolume.com/support/de/wire-fft) | Per-channel bands as a first-class input. |
| **Shadertoy** | One 512 x 2 texture: spectrum on the first row, waveform on the second, read at y = 0.25 and 0.75. [shader_fun docs](https://pub.dev/documentation/shader_fun/0.2.0/), [Synthclipse](https://synthclipse.sourceforge.net/user_guide/music.html) | Geode's `uAudioTex` already keeps those rows at those reads. |
| **Web Audio `AnalyserNode`** | dB spectrum with `smoothingTimeConstant` 0.8 by default, bins = fftSize / 2; time-domain data from a separate getter. [MDN](https://developer.mozilla.org/docs/Web/API/AnalyserNode/smoothingTimeConstant) | Nothing new; the analyser is the floor, not the ceiling. |
| **Metering tools** (Calf, NUGEN) | Goniometer (mono on the vertical axis, width on the horizontal), correlation over time and **correlation by frequency**, stereo bias. [Calf Analyzer](https://calf-studio-gear.org/doc/Analyzer.html), [NUGEN Visualizer](https://nugenaudio.com/visualizer) | Stereo per band, not one number for the whole mix. |
| **Spectrum analysers** (Flux, Digilent) | Log 20 Hz–20 kHz, min/max dB, peak hold with decay, a tilt for pink noise. [Flux Spectrum analyzer](https://doc.flux.audio/mira/Spectrum_analyzer.html) | Geode's `AudioInputs` already does all four. |
| **Research** (ProjectM extension, FIT 2025) | RMS, spectral flux, spectral predictivity and harmonic-percussive separation normalised and passed to the shaders. [excel.fit.vutbr.cz](https://excel.fit.vutbr.cz/submissions/2025/002/2.pdf) | A harmonic/percussive split as two inputs. |

## 3. Improvements, in the order they pay off

Each is a slice of its own (one PR per issue), tests first.

1. **Stereo PCM to the engine.** `PcmRingBuffer` already keeps mono and side; hand the engine
   interleaved stereo (`left = mono + side`, `right = mono - side`) through a stereo push, and let the
   renderer mix down for the mono readers. `AudioInputs` gets its `acceptStereo` back on the live path
   (left and right scope rows, a Lissajous that keeps the stereo), and MilkDrop gets `PROJECTM_STEREO`,
   which the stereo-beats patch of #296 was written for. Carry the ring's rate with the push: `AudioInputs`
   assumes 48 kHz and reads 9% high at 44.1 kHz.
2. **Per-channel spectra and stereo by frequency.** Grow `uAudioTex` from 8 rows to 12: spectrum left,
   spectrum right, and a correlation-by-frequency row (−1 wide and out of phase, 0 uncorrelated, 1 mono),
   plus per-band pan for bass, mid and treble as uniforms. This is what projectM, Butterchurn and Resolume
   keep apart and what metering tools draw; a Style can then widen with the width of the bass alone, or
   paint left and right halves from their own channels.
3. **A fourth band and band clocks.** Split the top as Synesthesia does (bass, mid, midhigh, high) and add
   `uBassTime`, `uMidTime`, `uMidHighTime`, `uHighTime`: clocks that advance faster while the band is loud.
   They are the cheapest high-end driver there is: a Style that moves on `uBassTime` instead of `uTime`
   pulses with the bass without any Reaction wired.
4. **Chroma and key to the Styles.** The frame already carries 12 chroma bins and their confidence and the
   analysis knows the key; neither reaches a shader. A 12-texel row in `uAudioTex` (stretched across the
   width, so `achroma(x)` reads a pitch class) and `uChromaConfidence` give palette and geometry a harmonic
   input that none of the references offer.
5. **Spectral motion rows.** A per-texel flux row (what changed since the last frame) and TouchDesigner's
   slow- and fast-moving partials density as two uniforms. Styles can light the texels that moved rather
   than the loud ones.
6. **A low-resolution spectrum row and a level history.** Synesthesia's "smooth FFT" (a 32-texel reading of
   the same spectrum) and a `syn_LevelTrail`-style texture, the four band levels over the last few seconds,
   so a Style can draw a trail or a history without keeping state.
7. **Beat-synced oscillators at bar divisions.** `uBeatPhase` and `uBarPhase` exist; add sine and triangle
   waves at 1, 1/2, 1/4 and 1/8 of the tempo, faded by the rhythm trust, as Synesthesia's `syn_BPMSin*`
   and `syn_BPMTri*`.
8. **Harmonic and percussive energy.** A median-filter HPSS over the spectrogram gives two levels the
   references found worth passing on: the sustained (harmonic) and the struck (percussive) part of the
   music, each 0..1.

What not to copy: projectM's bands by spectrum sixths (they move with the FFT size and the rate) and
Butterchurn's 30 fps-tuned decay constants; Geode's usual-level bands (#319) and `AnalysisHop` already do
this frame-rate and rate independently.

## 4. Sources

- projectM: [AudioConstants.hpp](https://raw.githubusercontent.com/projectM-visualizer/projectm/master/src/libprojectM/Audio/AudioConstants.hpp), [PCM.cpp](https://raw.githubusercontent.com/projectM-visualizer/projectm/master/src/libprojectM/Audio/PCM.cpp), [Loudness.hpp](https://raw.githubusercontent.com/projectM-visualizer/projectm/master/src/libprojectM/Audio/Loudness.hpp), [Loudness.cpp](https://raw.githubusercontent.com/projectM-visualizer/projectm/master/src/libprojectM/Audio/Loudness.cpp), [repository](https://github.com/projectM-visualizer/projectm)
- Butterchurn: [audioProcessor.js](https://raw.githubusercontent.com/jberg/butterchurn/master/src/audio/audioProcessor.js), [audioLevels.js](https://raw.githubusercontent.com/jberg/butterchurn/master/src/audio/audioLevels.js), [repository](https://github.com/jberg/butterchurn)
- Synesthesia: [SSF Audio Uniforms](https://app.synesthesia.live/docs/ssf/audio_uniforms.html), [SSF Best Practices](https://app.synesthesia.live/docs/ssf/best_practices.html)
- TouchDesigner: [Palette:audioAnalysis](https://derivative.ca/UserGuide/Palette:audioAnalysis), [beginner guide](https://interactiveimmersive.io/blog/touchdesigner-lessons/audio-reactive-visuals-a-beginner-guide/)
- Resolume: [audio analyzer forum thread](https://resolume.com/forum/viewtopic.php?p=9341), [Wire FFT](https://resolume.com/support/de/wire-fft)
- Shadertoy layout: [shader_fun](https://pub.dev/documentation/shader_fun/0.2.0/), [Synthclipse music](https://synthclipse.sourceforge.net/user_guide/music.html)
- Web Audio: [AnalyserNode.smoothingTimeConstant](https://developer.mozilla.org/docs/Web/API/AnalyserNode/smoothingTimeConstant)
- Metering: [Calf Analyzer](https://calf-studio-gear.org/doc/Analyzer.html), [NUGEN Visualizer](https://nugenaudio.com/visualizer), [Flux Spectrum analyzer](https://doc.flux.audio/mira/Spectrum_analyzer.html)
- MilkDrop 3: [deepwiki summary](https://deepwiki.com/milkdrop2077/MilkDrop3)
- Research: [An audio-driven system for real-time music visualisation](https://arxiv.org/pdf/2106.10134), [ProjectM extension, Excel@FIT 2025](https://excel.fit.vutbr.cz/submissions/2025/002/2.pdf)
