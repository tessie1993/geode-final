package dev.geode.architecture

/**
 * One Kotlin source file as the architecture rules see it: where it is, its package, and every
 * fully qualified name it refers to, whether imported or written out in the code.
 */
data class SourceFile(
    val path: String,
    val packageName: String,
    val references: Set<String>,
)

/**
 * The feature rules of ADR-0003, as plain functions over [SourceFile]s so the same rules can run on
 * the real code (through Konsist) and on small fixtures that prove each one reports a violation.
 *
 * A feature is a package `dev.geode.feature.<name>`. Its root package is its public interface;
 * everything in a sub-package (`core`, `ui`, ...) is its own business. The kernel is shared by every
 * feature and is not one of them.
 */
object FeatureRules {
    private const val FEATURES = "dev.geode.feature."
    private const val KERNEL = "kernel"
    private val platformPrefixes = listOf("android.", "androidx.", "org.jetbrains.compose.")

    /** `core` imports no Android, AndroidX or Compose. */
    fun coreIsPlainKotlin(files: List<SourceFile>): List<String> =
        files
            .filter { layerOf(it.packageName) == "core" }
            .flatMap { file ->
                file.references
                    .filter { ref -> platformPrefixes.any { ref.startsWith(it) } }
                    .map { "${file.path}: feature core uses $it" }
            }

    /** A feature uses another only through its public interface, never its core or screens. */
    fun noFeatureReachesIntoAnother(files: List<SourceFile>): List<String> =
        files.flatMap { file ->
            val own = featureOf(file.packageName) ?: return@flatMap emptyList()
            file.references
                .filter { ref ->
                    val other = featureOf(ref)
                    other != null && other != own && other != KERNEL && isInternal(ref)
                }.map { "${file.path}: $own uses the internals of another feature: $it" }
        }

    /** Features depend on each other in one direction only. */
    fun noFeatureCycles(files: List<SourceFile>): List<String> {
        val graph = mutableMapOf<String, MutableSet<String>>()
        for (file in files) {
            val own = featureOf(file.packageName) ?: continue
            val uses = graph.getOrPut(own) { mutableSetOf() }
            file.references.mapNotNullTo(uses) { featureOf(it) }
            uses -= own
        }
        return findCycle(graph)?.let { listOf("features depend on each other in a cycle: ${it.joinToString(" -> ")}") }.orEmpty()
    }

    fun all(files: List<SourceFile>): List<String> =
        coreIsPlainKotlin(files) + noFeatureReachesIntoAnother(files) + noFeatureCycles(files)

    private fun featureOf(name: String): String? =
        name
            .takeIf { it.startsWith(FEATURES) }
            ?.removePrefix(FEATURES)
            ?.substringBefore('.')
            ?.takeIf { it.isNotEmpty() }

    /** `core` for `dev.geode.feature.capture.core.x`; empty for the feature's root package. */
    private fun layerOf(packageName: String): String? {
        val feature = featureOf(packageName) ?: return null
        return packageName.removePrefix("$FEATURES$feature").removePrefix(".").substringBefore('.')
    }

    // `dev.geode.feature.capture.core.Thing` is internal; `dev.geode.feature.capture.Capture`,
    // `...capture.Capture.State` and a top-level `...capture.captureStore` are the public interface.
    private fun isInternal(reference: String): Boolean {
        val feature = featureOf(reference) ?: return false
        val rest = reference.removePrefix("$FEATURES$feature.").split('.')
        return rest.size > 1 && rest.first().first().isLowerCase()
    }

    private fun findCycle(graph: Map<String, Set<String>>): List<String>? {
        val done = mutableSetOf<String>()
        val path = mutableListOf<String>()

        fun visit(node: String): List<String>? {
            if (node in path) return path.subList(path.indexOf(node), path.size) + node
            if (!done.add(node)) return null
            path += node
            val cycle = graph[node].orEmpty().firstNotNullOfOrNull { visit(it) }
            path.removeAt(path.lastIndex)
            return cycle
        }
        return graph.keys.sorted().firstNotNullOfOrNull { visit(it) }
    }
}

private val qualifiedReference = Regex("""(?<![\w.])(?:dev\.geode\.feature|android|androidx|org\.jetbrains\.compose)(?:\.\w+)+""")
private val commentsAndStrings = Regex("""//[^\n]*|/\*[\s\S]*?\*/|"(?:\\.|[^"\\])*"""")

/** Every feature or platform name [text] refers to, comments and string literals aside. */
fun referencesIn(text: String): Set<String> =
    qualifiedReference
        .findAll(commentsAndStrings.replace(text, " "))
        .map { it.value }
        .toSet()
