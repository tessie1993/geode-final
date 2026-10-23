// Studio's stateless screens (ADR-0003, ADR-0005): the Project list, a Project's page and a video's
// page (#21), with their previews. Built alongside the old Studio screen and
// wired into the app at the switch-over (#17).
plugins {
    id("geode.feature-ui")
}

dependencies {
    implementation(libs.compose.material.icons.extended)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.activity.compose)
}
