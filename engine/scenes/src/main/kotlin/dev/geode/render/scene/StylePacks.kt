package dev.geode.render.scene

import dev.geode.engine.types.SceneId

/** Every Style the packs declare, in index order. Packs that cannot be read are left out, with a [PackProblem] each. */
class StylePackRegistry private constructor(
    val families: List<FamilySchema>,
    val styles: List<StylePack>,
    val problems: List<PackProblem>,
) {
    private val byId: Map<SceneId, StylePack> = styles.associateBy { it.id }

    val ids: List<SceneId> = styles.map { it.id }

    fun find(id: SceneId): StylePack? = byId[id]

    /** The ids of every Style drawn by one of [kinds], in index order. */
    fun idsOf(vararg kinds: SceneKind): List<SceneId> = styles.filter { it.kind in kinds }.map { it.id }

    companion object {
        /** Reads `styles/families.json`, `styles/index.json` and every listed manifest through [read]. */
        fun load(read: (String) -> String?): StylePackRegistry {
            val problems = mutableListOf<PackProblem>()
            val familiesText = read(StylePackFormat.stylesPath(StylePackFormat.FAMILIES_FILE))
            val indexText = read(StylePackFormat.stylesPath(StylePackFormat.INDEX_FILE))
            if (familiesText == null) problems += missing(StylePackFormat.FAMILIES_FILE, StylePackFormat.FAMILIES_FILE)
            if (indexText == null) problems += missing(StylePackFormat.INDEX_FILE, StylePackFormat.INDEX_FILE)
            val families = familiesText?.let { StylePackFormat.parseFamilies(it, problems) }
            val ids = indexText?.let { StylePackFormat.parseIndex(it, problems) }
            if (families == null || ids == null) return StylePackRegistry(families.orEmpty(), emptyList(), problems)
            val styles =
                ids.mapNotNull { id ->
                    val text = read(StylePackFormat.manifestPath(id.value))
                    if (text == null) {
                        problems += missing(id.value, StylePackFormat.manifestPath(id.value))
                        null
                    } else {
                        StylePackFormat.parseManifest(id.value, text, families, problems)
                    }
                }
            return StylePackRegistry(families, styles, problems)
        }

        private fun missing(
            where: String,
            path: String,
        ) = PackProblem(PackError.MISSING_FILE, where, path)
    }
}

/**
 * The Styles that ship: the packs under `styles/` in this module's resources, which are also its
 * assets, so the native renderer reads the very same files from the APK.
 *
 * Adding or removing a Style is a change to the packs alone. Nothing in code lists the Styles; the one
 * Style code names is [SceneIds.DEFAULT].
 */
object StylePacks {
    val registry: StylePackRegistry by lazy { StylePackRegistry.load(::readResource) }

    fun find(id: SceneId): StylePack? = registry.find(id)

    /** Every Style, in picker order. */
    val ids: List<SceneId> get() = registry.ids

    fun idsOf(vararg kinds: SceneKind): List<SceneId> = registry.idsOf(*kinds)

    private fun readResource(path: String): String? =
        StylePacks::class.java.classLoader
            ?.getResourceAsStream(path)
            ?.bufferedReader()
            ?.use { it.readText() }
}
