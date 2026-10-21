// Settings' state machine (ADR-0005): the pure reducer that applies a change, keeps it in range and
// asks for it to be saved. Held to the feature-core mutation floor.
plugins {
    id("geode.feature-core")
}
