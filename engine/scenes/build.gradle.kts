plugins {
    id("geode.android-library")
}

android {
    namespace = "dev.geode.engine.scenes"
}

// The Style packs (styles/<id>/manifest.json, its shader, styles/index.json, styles/families.json; see
// docs/style-packs.md) live once, in src/main/stylepacks, and ship twice: as assets, which the native
// renderer reads from the APK, and as Java resources, which StylePacks.kt reads on the device and in
// unit tests alike.
val stylePacks = "src/main/stylepacks"
androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addStaticSourceDirectory(stylePacks)
        variant.sources.resources?.addStaticSourceDirectory(stylePacks)
    }
}

dependencies {
    api(project(":engine:audio-core"))
    implementation(libs.kotlinx.coroutines.android)
    // The real org.json: the android.jar stub would make every pack-parsing test throw.
    testImplementation(libs.org.json)
}
