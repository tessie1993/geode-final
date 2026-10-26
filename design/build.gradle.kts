// The App UI's design tokens, theme and common components (ADR-0005). Never anything of the Visual.
plugins {
    id("geode.design")
}

dependencies {
    // Frosted glass over Compose content (#324): Haze records what lies behind a glass bar or sheet
    // and blurs it. Kept behind :design's own GlassSource, so no screen sees Haze's types.
    implementation(libs.haze)
    implementation(libs.haze.blur)
}
