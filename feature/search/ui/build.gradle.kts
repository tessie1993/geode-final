// Global Search's stateless screen (ADR-0003, ADR-0005): the search field and the songs, playlists and
// presets it finds, grouped, with its route, previews and Roborazzi screenshots. Built alongside the old
// Search overlay and wired into the app, from Home's Search shortcut, at the switch-over (#17).
plugins {
    id("geode.feature-ui")
}

dependencies {
    implementation(libs.compose.material.icons.extended)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.activity.compose)
}
