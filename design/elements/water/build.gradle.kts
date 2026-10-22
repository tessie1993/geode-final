// The Water Element's own component library (#180, decision 3): Water's touch, motion and fluids,
// drawn in code. It only draws, from plain inputs (a progress, a touch point, reduced motion): no
// state machine, no app logic, never audio. It depends on :design alone, and only :app uses it
// (FeatureRules).
plugins {
    id("geode.android-library")
    id("org.jetbrains.kotlin.plugin.compose")
}

extensions.configure<com.android.build.api.dsl.LibraryExtension> {
    namespace = "dev.geode.design.elements.water"
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(project(":design"))
}
