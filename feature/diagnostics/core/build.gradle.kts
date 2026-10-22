// Diagnostics' state machine and the pure report it writes (ADR-0005), with the ports the handlers
// read through. Held to the feature-core mutation floor; the fakes of its ports are in its test
// fixtures for the handlers' tests.
plugins {
    id("geode.feature-core")
}
