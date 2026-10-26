// Capture's effect handlers (ADR-0005): they carry out the reducer's work against the audio adapter
// and Playback (through the MusicControl port) and report what happens as events. Android library.
plugins {
    id("geode.feature-handlers")
}
