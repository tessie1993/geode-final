// Mutation testing for plain JVM modules. It is a full layer of the test suite, not a diff check:
// every PR runs it over the whole module, and tools/ci/mutation_gate.py holds the score to the
// floor in config/mutation/thresholds.properties. See docs/ci.md.
plugins {
    id("info.solidsoft.pitest")
}

val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")

extensions.configure<info.solidsoft.gradle.pitest.PitestPluginExtension> {
    pitestVersion = catalog.findVersion("pitest").get().requiredVersion
    targetClasses = setOf("dev.geode.*")
    threads = Runtime.getRuntime().availableProcessors()
    outputFormats = setOf("XML", "HTML")
    timestampedReports = false
    // The gate script decides pass or fail, so a module with no tests yet reports 0 instead of
    // stopping the build before the report exists.
    failWhenNoMutations = false
    avoidCallsTo = setOf("kotlin.jvm.internal", "kotlinx.coroutines")
}
