// Settings' public interface (ADR-0005): every setting the app has today as typed values, and the
// commands that change them. Other features read Settings' state through this module only.
plugins {
    id("geode.feature-api")
}
