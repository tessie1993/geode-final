// The Visuals feature's stateless screens (ADR-0003, ADR-0005): the Visuals tab and full-screen
// visual mode, with their routes, previews and Roborazzi screenshots. App UI only: the Visual itself is
// drawn by the engine behind them. Built alongside the old Visuals hub and wired into the app at the
// switch-over (#17).
plugins {
    id("geode.feature-ui")
}

dependencies {
    implementation(libs.compose.material.icons.extended)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.activity.compose)
    implementation(libs.androidx.core)
    testImplementation(libs.kotlinx.coroutines.test)
}
