# Runtime decisions for the feature architecture

These decisions extend ADR-0003 (structure: ADR-0005) with how the features behave at runtime. The owner settled them on 2026-10-20.

- **Android's media session is the single source of truth for playback.** Playback's state, position and queue are what the app, the lock screen, the notification, Bluetooth and the widget all read. The audio adapter **announces changes** to Playback: started, paused, seeked, track changed, ended and error. Playback works out the position from the clock between announcements, so there is no polling (today's 500 ms poll goes).
- **Playback owns the queue.** Library, Studio, the widget and the notification only send it commands: play this, play next, add to queue.
- **There is one shared live Visual.** Full-screen visual mode, the Visuals tab and Recordings show the same running Visual, and moving between them never restarts it. The live wallpaper runs its own Visual only while the app is closed.
- **Renders use a separate engine instance**, so you can keep listening and watching while a video exports.
- **Restoring after Android kills the app:** each feature saves enough to restore the queue and position (paused), the look with its Customization and undo history, the open screen and sheet, and the Studio render queue. Settings persist on their own and are never "restored over".
- **Errors:** a failure in one feature appears in **one app-wide banner**, which every feature reports to through a single shared channel. Audio and the Visual keep running. Failures are also written to the local log; nothing leaves the device.
- **Idle engine:** whether the audio engine shuts down when idle in the background is a **preference** (#28).
- **Mic while music plays:** when Capture starts during playback, the user is **asked each time** whether to pause the song or keep both.
- **The widget and the live wallpaper stay.** They talk to Playback and the Visual through their public interfaces only.
- **Architecture rules are hard gates.** The Konsist architecture test fails CI on any break, with no exceptions list.
- **Storage:**
  - settings in **DataStore**;
  - the library index, history, play counts, playlists, Projects and the render queue in a **Room** database;
  - presets as small **versioned files** that can be shared.
- **Every saved format carries a version** and is migrated step by step. CI loads real old samples to prove that nothing is lost.
- **Navigation:** type-safe **Navigation Compose**. Every place (a tab, a sheet, full-screen visual mode) is a typed route, and the back stack is saved and restored.
- **Renders** run in a foreground service of the media-processing type, with a progress notification. The queue lives in the database, so unfinished renders resume after Android kills the app.
- **Library scanning:** Android's MediaStore finds the files. Geode's TagLib reader fills in the missing tags (composer, album artist, ReplayGain, embedded art) in the background, cached in Room.
- **Customization to C++:** one compact, versioned snapshot is sent whenever the Customization changes. Nothing crosses per frame (ADR-0002).
- **Minimum Android version: 12** (API 31). The old-version workarounds are removed.
- **Quality gates:** mutation scores are the test-quality gate, and line coverage stays a report only. There are no performance budgets or Baseline Profiles for now.
- **End-to-end journeys on the emulator** in CI:
  - play a song and switch tabs and full-screen mode, without a restart;
  - Record the Visual;
  - render a short video;
  - Android kills the app and it restores;
  - each Style family renders.

## Considered options

- **The app polls the engine:** simple, but it is the poll we are removing. Streaming the position from the engine is precise but chatters.
- **Library owns the queue:** this splits playback truth across two features.
- **One Visual per place:** simpler, but every switch restarts the picture and costs battery.
- **Renders share the live engine:** faster on weak phones, but the music would pause during every export.
- **Errors only in the part that failed:** rejected by the owner in favour of one consistent banner.
