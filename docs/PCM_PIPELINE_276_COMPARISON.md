# The PCM pipeline before and after #276

Commit `00fd8343` ("Give the Visual one native audio feed (#276)", 2026-10-26) replaced the
Kotlin PCM pipeline with a native "feed". Everything since (#277-#301, #342, #366) built on the
feed. This note compares the two on `main` only, so a rebuild can take the old shape and keep what
the new one got right. The research into how MilkDrop, projectM, Butterchurn and others do it is in
`PCM_PIPELINE_RESEARCH.md`.

Scale: `git diff --stat 00fd8343^ main` over `core/audio core/api core/analysis engine/audio-core
engine/live engine/scenes/.../analysis engine/scenes/.../render app/src/main/cpp` is 110 files,
+6503 / -1357.

## Before #276: one sink, two rings, the GL thread pulls

```
ExoPlayer ─ TeeAudioProcessor(PcmTap) ─┐
Geode player ─ NativeTapPump thread ───┤ captureSink (PcmSink)
Microphone ─ MicCapture/AudioCapturePump┘      │
                                   ┌───────────┴────────────┐
                                   ▼                        ▼
                     PcmRingBuffer (Kotlin)         SampleRing (Kotlin)
                     mono mix + side, 2^16         per-channel stereo, 2^16 frames,
                     @Volatile write index         epochs, AtomicLong write index
                                   │                        │
                   GL thread, each frame:          AnalysisEngine loop (Dispatchers.Default,
                   LivePcm.next() =                16 ms tick): SampleCursor reads every frame
                   ring.copyNewSince(cursor)       once, pushes to native ReactiveAnalyzer
                   up to 4096 mono samples         (JNI push/pull), publishes AudioFeatures
                                   │                        │
                   nativeViz.pushPcm(mono)         PlaybackEngine collects → AudioBus.publish
                                   │               (@Volatile latest, 1.5 s stale window,
                   Renderer::pushPcm keeps          consumer count gates the loop)
                   newest 4096 under stateLock_             │
                                   │               GL thread, each frame:
                   deliverPcm → scene.acceptPcm    featureSource() = AudioBus.features()
                   (mono); MilkDrop adds it as      → DisplayEvents.frame() → setFeatures
                   PROJECTM_MONO, else waveform
```

Files (at `00fd8343^`): `engine/live/.../audio/PcmRingBuffer.kt`, `engine/audio-core/.../SampleRing.kt`
(still on main, now unused by the live path), `engine/live/.../audio/AudioBus.kt`,
`engine/live/.../playback/NativeTapPump.kt`, `engine/scenes/.../analysis/AnalysisEngine.kt`,
`engine/scenes/.../render/VisualizerRenderer.kt` (`pcmProvider`, `featureSource`),
`app/.../ui/LiveVisual.kt` (`LivePcm`), `core/viz/Renderer.cpp` (`pushPcm`, `deliverPcm`).

What it got right:

- **One sink, one shape for every source.** The native player, ExoPlayer and the microphone all
  wrote through the same `PcmSink`; the Visual never knew which. `NativeTapPump` made the native
  player look exactly like ExoPlayer's tap.
- **The Visual pulls.** The GL thread took "what arrived since my last frame" (`copyNewSince`) and
  handed it to the scenes. No frame was pushed twice, no frame was skipped, and nothing had to know
  the display rate.
- **Few knobs.** Attack 0.6, decay 0.12 (through `BeatTuning`), beat sensitivity and minimum
  interval from Settings. That is the whole tuning surface.
- **Testable in the JVM.** The rings, the cursor and the engine loop ran without a device.

What it lacked (the reasons #276 was written, and still real):

- Scenes got **mono only**; MilkDrop was fed `PROJECTM_MONO`, so stereo presets could not react.
- **No presentation alignment.** ExoPlayer's tap sits about half a second ahead of the speaker, so
  the picture led the sound. (This is the one problem #276 set out to solve, and it is a real one.)
- Two rings written per sample, and each frame crossed JNI twice (features in, PCM in).
- The analysis ran on a coroutine with a 16 ms tick, so its latency was the tick, and the main
  thread was a hop on the features' way (`PlaybackEngine` collecting the flow into `AudioBus`).

## After #276 (main): one native ring, the engine decides what you hear

```
Geode player audio callback ──────────────┐ FeedRing::write(source, …)   (core/audio/feed)
ExoPlayer PcmTap ─ VisualFeed.Sink ─ JNI ─┤ stereo, 2^17 frames, one writer at a time
Microphone ─ MicCapture ─ VisualFeed.Sink ─┘ (test_and_set: a second writer is DROPPED),
                                             epochs on select/flush/rate change,
                                             per-source running/delay/speed/measured,
                                             route fallback, user offset, Sensitivity gain,
                                             per-source window gain
        │                                                       │
        ▼ Feed worker thread (core/api/Feed.cpp), 5 ms look     ▼ GL thread, geode_viz_render → readFeed
  copyRange 256 frames at a time → geode_analysis_push     locate(now, presentAhead) → copy 4096-frame span
  → pull → FeatureHistory.publish(epoch, stamp)            → heardSince → DisplayEvents → SignalHold
  (128 slots, mutex)                                        → Renderer::setFramePcm(span, fresh) → latch
                                                            → scenes: mono 576, stereo 576, span
                                                            MilkDrop: loudestWindow(span) → PROJECTM_STEREO
```

Plus `FeedClockSink` (wraps ExoPlayer's sink: pause, flush, tap lead via `PresentationLead`),
`HeardClock` (route latency guesses), `OutputRouteWatcher`, `FeedTap` (Recording), and
`LiveInputPolicy.windowGain`.

What it got right, and should survive a rebuild:

- **Stereo to the scenes**, and MilkDrop fed `PROJECTM_STEREO`.
- **The idea of a "heard" point**: read the audio that is at the speaker, not the newest written.
- **Epochs** on seek, source change and rate change, so a reader never spans two streams.
- **Analysis off the main thread** with no coroutine tick, and features stamped with the audio
  they describe.
- The native player writing from its callback with no allocation (the old `NativeTapPump` thread
  and its 10 ms sleep are gone).

Where it went wrong:

1. **The engine now decides what is heard, with guesses.** `FeedRing::locate` combines the source's
   reported delay, a route fallback table (`HeardClock.cpp`: speaker 15 ms, wired 10, USB 25,
   Bluetooth 200, LE 80, HDMI 60, unknown 20), a measured packet interval smoothed as
   `packet - (packet - gap) / 8`, the wall-clock age of the last write, the frame's present time
   (capped at 100 ms ahead) and the person's offset (capped at ±500 ms), then walks back from the
   newest frame. Any of these being off moves the window the scenes draw. Before, the window was
   simply "what arrived since the last frame".
2. **Hardcoded level biases on what the scenes draw, not on what the analysis hears.**
   `LiveInputPolicy.windowGain` multiplies the microphone's window by 2 (voice path) or **16**
   (UNPROCESSED) and `FeedRing::copy` clamps the result to ±1. The analysis reads the unboosted
   signal (`copyRange`). So for Live input the waveform MilkDrop and the Styles draw is a clipped,
   boosted version of what the beat detector heard; picture and reaction disagree by design.
   The Sensitivity (`setGainDb`, −12..+24 dB) is applied on *read* to both, which is fine, but it
   means every adaptive normaliser in the analysis sees a gain step when the setting moves.
3. **MilkDrop no longer gets the heard window; it gets the loudest one.** `MilkdropScene::acceptPcmSpan`
   runs `milkdrop::loudestWindow` over the span heard since the last frame and adds the 576 frames
   with the most energy (#296). That is an energy bias toward transients: a steady passage and a
   passage with one click in it are drawn the same way. No visualizer in the research does this;
   they add every sample once, in order, and let the preset's own smoothing decide.
4. **Writes are dropped, not queued.** `FeedRing::write` returns 0 when another write holds the
   flag or when the source is not the selected one. Around a source change, and whenever two
   writers overlap, audio is silently lost. The old `PcmSink` accepted every write.
5. **Twenty-three magic constants across the feed** (`Feed.hpp`, `FeedRing.hpp`, `HeardClock.*`,
   `FeedTap.hpp`, `PresentationLead.kt`, `LiveInputPolicy.kt`): 25 ms initial packet, 250 ms max
   packet, 2 s max delay, 0.25 s wake lookback, 48 kHz initial rate, 256-frame push, 128 history
   slots, 2 ms lead step, 250 ms lead refresh, 2 s max lead, the route table, the two gains. None
   is tunable, none is measured, and several (route table, window gains) are opinions about hardware.
6. **The scenes' window is latched and dropped per frame** (`latchFramePcm`), so a frame with no
   new read draws silence rather than the last window, and `SignalHold` exists to paper over that
   for freezes. Before, `pushPcm` kept the newest 4096 samples until replaced.
7. **The analysis is reachable only through the feed.** `AnalysisEngine(feed)` is a thin wrapper
   over `feedSetTuning`; the JVM tests that drove the loop with a `SampleRing` are gone, and the
   feed is tested only in C++ (`feed_ring_test.cpp`, 366 lines, mostly timing arithmetic).

## What a rebuild keeps from each

| Keep from before #276 | Keep from after #276 |
|---|---|
| One `PcmSink` every source writes to, no "selected source" gate on write | Stereo frames to the scenes, `PROJECTM_STEREO` |
| The Visual pulls "since my last frame" from a ring with a cursor | A native ring written from the audio callback without allocation |
| Analysis reads the same ring with its own cursor, every frame exactly once | Epochs on seek / source change / rate change |
| Tuning surface: attack, decay, sensitivity, min interval, nothing else | Features stamped with the frame they describe |
| JVM-testable ring, cursor and engine loop | A presentation *offset* the reader subtracts, reported by the sink, not guessed from a route table |
| Scenes keep the last window until a new one arrives | Analysis on its own thread, woken by data rather than a tick |

What goes: the route fallback table, per-source window gains, `loudestWindow`, the dropped-write
flag, the per-frame latch-and-drop, `SignalHold` as a workaround, and the wall-clock extrapolation
of the heard point. The presentation lead stays as a single number the sink reports (ExoPlayer:
`FeedClockSink`'s measurement; the native player: its stream timestamp); when nothing reports one,
the reader uses zero, as before #276, and says so.
