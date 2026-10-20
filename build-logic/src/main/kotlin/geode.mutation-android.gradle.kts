// Mutation testing for Android modules (the app and the Android libraries). The same full layer
// as geode.mutation-jvm, through the Android fork of the plugin: on AGP 9 unit tests exist for the
// debug build type only, so the task is pitestDebug. See docs/ci.md.
plugins {
    id("pl.droidsonroids.pitest")
}

val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")

extensions.configure<pl.droidsonroids.gradle.pitest.PitestPluginExtension> {
    pitestVersion = catalog.findVersion("pitest").get().requiredVersion
    targetClasses = setOf("dev.geode.*")
    threads = Runtime.getRuntime().availableProcessors()
    outputFormats = setOf("XML", "HTML")
    timestampedReports = false
    failWhenNoMutations = false
    avoidCallsTo = setOf("kotlin.jvm.internal", "kotlinx.coroutines")
    // Generated code is not ours to test: Hilt factories and modules, Compose singletons, BuildConfig.
    excludedClasses =
        setOf(
            "*_Factory",
            "*_Factory\$*",
            "*_MembersInjector",
            "*_HiltModules*",
            "Hilt_*",
            "*ComposableSingletons*",
            "*.BuildConfig",
            "*.R",
            "*.R\$*",
            // One Compose function here is too large for PIT's bytecode library (ASM "Too many
            // operations"), which aborts the whole run. It is the old Studio timeline, which the
            // Studio redo (#13) replaces.
            "dev.geode.ui.studio.TimelineLanesKt",
        )
    // Screenshot tests render the App UI for Roborazzi; they assert pixels, not behaviour, and are
    // far too slow to rerun per mutant.
    excludedTestClasses = setOf("*ScreenshotTest")
    // The tests PIT runs are plain JVM tests (Robolectric ones are excluded above), so they keep the
    // same mockable android.jar the unit-test task gives them.
    excludeMockableAndroidJar = false
}
