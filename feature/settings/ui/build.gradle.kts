// Settings' screens (ADR-0005): the stateless SettingsScreen(model, onAction), its previews and its route. It
// depends on Settings' :api only.
plugins {
    id("geode.feature-ui")
}

dependencies {
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.activity.compose)
    implementation(libs.compose.material.icons.extended)
}
