plugins {
    id("org.jetbrains.kotlin.jvm")
    id("geode.kotlin-common")
    id("geode.mutation-jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    "testImplementation"("junit:junit:4.13.2")
    "testImplementation"("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}

// The Android modules' unit-test task name, so CI runs every module's tests by that one name
// instead of listing each JVM module.
tasks.register("testDebugUnitTest") { dependsOn("test") }
