// The Search feature's handlers (ADR-0005): they follow the Library's songs, Playback's playlists and
// the presets, and send a picked result to the Library, Playback or Customization, through their :api
// only. Search keeps nothing of its own.
plugins {
    id("geode.feature-handlers")
}

dependencies {
    implementation(project(":feature:library:api"))
    implementation(project(":feature:playback:api"))
    implementation(project(":feature:customization:api"))
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlinx.coroutines.test)
}
