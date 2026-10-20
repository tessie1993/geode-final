package dev.geode.architecture

/** One Gradle module and the modules its build file depends on, by path (`:feature:capture:core`). */
data class Module(
    val path: String,
    val dependsOn: Set<String>,
)

/**
 * One Kotlin source file as the rules see it: its module, where it is, and every platform name it
 * refers to, whether imported or written out in the code.
 */
data class SourceFile(
    val module: String,
    val path: String,
    val references: Set<String>,
)

/**
 * The rules of ADR-0005 that the compiler cannot enforce, as plain functions so the same rules run on
 * the real build (FeatureArchitectureKonsistTest) and on small fixtures that prove each one reports a
 * break (FeatureRulesTest). There is no exceptions list (ADR-0004).
 *
 * Each feature is four modules: `:feature:x:api`, `:core`, `:handlers` and `:ui`. What each may
 * depend on is the table in ADR-0005; `:app` only wires, and may depend on anything.
 */
object FeatureRules {
    private val platformPrefixes = listOf("android.", "androidx.", "org.jetbrains.compose.")
    private val pureParts = setOf("api", "core")

    /** `:arch:*` and every feature's `:api` and `:core` are plain Kotlin: no Android, AndroidX or Compose. */
    fun pureModulesUseNoAndroid(files: List<SourceFile>): List<String> =
        files
            .filter { isPure(it.module) }
            .flatMap { file ->
                file.references
                    .filter { ref -> platformPrefixes.any { ref.startsWith(it) } }
                    .map { "${file.path}: ${file.module} uses $it" }
            }

    /** Every module depends only on what the ADR-0005 table allows. */
    fun dependenciesFollowTheTable(modules: List<Module>): List<String> =
        modules.flatMap { module ->
            module.dependsOn
                .filterNot { allowed(module.path, it) }
                .map { "${module.path} may not depend on $it" }
        }

    /** Features depend on each other in one direction only. */
    fun noFeatureCycles(modules: List<Module>): List<String> {
        val graph = mutableMapOf<String, MutableSet<String>>()
        for (module in modules) {
            val own = featureOf(module.path) ?: continue
            val uses = graph.getOrPut(own) { mutableSetOf() }
            module.dependsOn.mapNotNullTo(uses) { featureOf(it) }
            uses -= own
        }
        return findCycle(graph)
            ?.let { listOf("features depend on each other in a cycle: ${it.joinToString(" -> ")}") }
            .orEmpty()
    }

    fun all(
        modules: List<Module>,
        files: List<SourceFile>,
    ): List<String> = pureModulesUseNoAndroid(files) + dependenciesFollowTheTable(modules) + noFeatureCycles(modules)

    private fun allowed(
        from: String,
        to: String,
    ): Boolean {
        val feature = featureOf(from)
        val target = featureOf(to)
        return when {
            feature != null -> allowedFromFeature(feature, from.substringAfterLast(':'), to)
            from.startsWith(":arch:") -> target == null
            else -> target == null || from == ":app"
        }
    }

    private fun allowedFromFeature(
        feature: String,
        part: String,
        to: String,
    ): Boolean {
        val own = ":feature:$feature"
        val otherApi = featureOf(to) != null && to.endsWith(":api")
        return when (part) {
            "api" -> to == ":arch:kernel"
            "core" -> to == ":arch:kernel" || otherApi
            "handlers" -> to == "$own:core" || to == "$own:api" || to.startsWith(":arch:")
            "ui" -> to == "$own:api"
            else -> false
        }
    }

    private fun isPure(module: String): Boolean =
        module.startsWith(":arch:") || (featureOf(module) != null && module.substringAfterLast(':') in pureParts)

    /** `capture` for `:feature:capture:core`; null for anything that is not part of a feature. */
    private fun featureOf(module: String): String? =
        module
            .takeIf { it.startsWith(":feature:") }
            ?.removePrefix(":feature:")
            ?.substringBefore(':')

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

private val platformReference = Regex("""(?<![\w.])(?:android|androidx|org\.jetbrains\.compose)(?:\.\w+)+""")
private val comments = Regex("""//[^\n]*|/\*[\s\S]*?\*/""")
private val commentsAndStrings = Regex("""//[^\n]*|/\*[\s\S]*?\*/|"(?:\\.|[^"\\])*"""")
private val includeCall = Regex("""include\(([^)]*)\)""")
private val includedModule = Regex(""""(:[\w:-]+)"""")
private val projectDependency = Regex("""project\(\s*"(:[\w:-]+)"\s*\)""")

/** Every platform name [code] refers to, comments and string literals aside. */
fun referencesIn(code: String): Set<String> =
    platformReference
        .findAll(commentsAndStrings.replace(code, " "))
        .map { it.value }
        .toSet()

/** The modules a `settings.gradle.kts` includes. */
fun modulesIn(settings: String): List<String> =
    includeCall
        .findAll(comments.replace(settings, " "))
        .flatMap { include -> includedModule.findAll(include.groupValues[1]).map { it.groupValues[1] } }
        .toList()

/** The project dependencies a module's build file declares, whatever the configuration. */
fun dependenciesIn(buildFile: String): Set<String> =
    projectDependency
        .findAll(comments.replace(buildFile, " "))
        .map { it.groupValues[1] }
        .toSet()
