// The public API of an interface module (:arch:kernel, :arch:audio, each feature's :api), dumped to
// api/ by the Kotlin Gradle plugin's ABI validation and checked on every pull request
// (checkKotlinAbi), so a change to the public API shows in the PR as a change to the dump. Dumps are
// written by CI, never by hand: a commit whose message contains [api-dump] runs updateKotlinAbi and
// commits the result (docs/ci.md).
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation

pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
    extensions.configure<KotlinJvmProjectExtension> {
        @OptIn(ExperimentalAbiValidation::class)
        abiValidation {
            referenceDumpDir.set(layout.projectDirectory.dir("api"))
        }
    }
}
