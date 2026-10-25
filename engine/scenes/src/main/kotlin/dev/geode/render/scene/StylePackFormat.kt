package dev.geode.render.scene

import dev.geode.engine.types.SceneId
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Reads the three pack files: `styles/index.json`, `styles/families.json` and each
 * `styles/<id>/manifest.json`. Mirrors `core/viz/StylePacks.cpp` rule for rule; docs/style-packs.md
 * describes the format.
 */
object StylePackFormat {
    const val STYLES_DIR = "styles/"
    const val INDEX_FILE = "index.json"
    const val FAMILIES_FILE = "families.json"
    const val MANIFEST_FILE = "manifest.json"

    /** The pack format version this build reads. Each of the three files carries it. */
    const val FORMAT_VERSION = 1

    /** The render scale a Style draws at when its manifest declares none, and the range it may declare. */
    const val DEFAULT_RENDER_SCALE = 1f
    const val MIN_RENDER_SCALE = 0.5f
    const val MAX_RENDER_SCALE = 1f

    /** How many default Macros a Style may ship: the shared set every Style maps. */
    const val MAX_MACROS = 6

    /** The range of a Macro link's or a Reaction's amount. */
    const val MIN_AMOUNT = -1f
    const val MAX_AMOUNT = 1f

    fun stylesPath(name: String): String = STYLES_DIR + name

    fun packFilePath(
        styleId: String,
        file: String,
    ): String = "$STYLES_DIR$styleId/$file"

    fun manifestPath(styleId: String): String = packFilePath(styleId, MANIFEST_FILE)

    fun parseFamilies(
        text: String,
        problems: MutableList<PackProblem>,
    ): List<FamilySchema>? =
        reading(FAMILIES_FILE, problems) {
            val doc = document(text, FAMILIES_FILE)
            val list = doc.optJSONArray("families") ?: fail(PackError.MISSING_FIELD, FAMILIES_FILE, "\"families\" must be an array")
            objects(list, FAMILIES_FILE, "family").map { family ->
                val name = string(family, "name", FAMILIES_FILE)
                val kind = SceneKind.forFamily(name) ?: fail(PackError.UNKNOWN_FAMILY, FAMILIES_FILE, "\"$name\" has no renderer")
                val parameters =
                    family.optJSONArray("parameters")
                        ?: fail(PackError.MISSING_FIELD, FAMILIES_FILE, "family \"$name\" needs \"parameters\"")
                FamilySchema(kind, objects(parameters, FAMILIES_FILE, "parameter").map { parameterSpec(it) })
            }
        }

    fun parseIndex(
        text: String,
        problems: MutableList<PackProblem>,
    ): List<SceneId>? =
        reading(INDEX_FILE, problems) {
            val doc = document(text, INDEX_FILE)
            val list = doc.optJSONArray("styles") ?: fail(PackError.MISSING_FIELD, INDEX_FILE, "\"styles\" must be an array")
            val ids = LinkedHashSet<String>()
            for (i in 0 until list.length()) {
                val id = list.opt(i) as? String
                if (id.isNullOrEmpty()) {
                    problems += PackProblem(PackError.BAD_VALUE, INDEX_FILE, "entry $i is not a Style id")
                } else if (!ids.add(id)) {
                    problems += PackProblem(PackError.DUPLICATE_ID, INDEX_FILE, "\"$id\" is listed twice")
                }
            }
            ids.map(::SceneId)
        }

    /** Parses the manifest read from folder [folderId] against the declared [families]. */
    fun parseManifest(
        folderId: String,
        text: String,
        families: List<FamilySchema>,
        problems: MutableList<PackProblem>,
    ): StylePack? =
        reading(folderId, problems) {
            val doc = document(text, folderId)
            val id = string(doc, "id", folderId)
            val label = string(doc, "label", folderId)
            val familyName = string(doc, "family", folderId)
            if (id != folderId) fail(PackError.ID_MISMATCH, folderId, "manifest says \"$id\"")
            val family =
                families.firstOrNull { it.kind.familyName == familyName }
                    ?: fail(PackError.UNKNOWN_FAMILY, folderId, "\"$familyName\"")
            StylePack(
                id = SceneId(id),
                label = label,
                kind = family.kind,
                shaderPath = shaderPath(doc, id),
                renderScale = renderScale(doc, id),
                detail = detail(doc, id),
                parameters = parameters(doc, family, id),
                extras = extras(doc, id),
                macros = macros(doc, id),
                reactions = reactions(doc, id),
            )
        }

    private class PackFailure(
        val problem: PackProblem,
    ) : Exception(problem.detail)

    private fun fail(
        error: PackError,
        where: String,
        detail: String,
    ): Nothing = throw PackFailure(PackProblem(error, where, detail))

    private fun <T> reading(
        where: String,
        problems: MutableList<PackProblem>,
        block: () -> T,
    ): T? =
        try {
            block()
        } catch (failure: PackFailure) {
            problems += failure.problem
            null
        } catch (bad: JSONException) {
            problems += PackProblem(PackError.NOT_JSON, where, bad.message.orEmpty())
            null
        }

    private fun document(
        text: String,
        where: String,
    ): JSONObject {
        val doc = JSONObject(text)
        val version = doc.opt("version") as? Number
        if (version == null || version.toDouble() != FORMAT_VERSION.toDouble()) {
            fail(PackError.WRONG_VERSION, where, "\"version\" must be $FORMAT_VERSION")
        }
        return doc
    }

    private fun objects(
        array: JSONArray,
        where: String,
        what: String,
    ): List<JSONObject> =
        (0 until array.length()).map { i ->
            array.opt(i) as? JSONObject ?: fail(PackError.MISSING_FIELD, where, "every $what must be an object")
        }

    private fun string(
        obj: JSONObject,
        key: String,
        where: String,
    ): String {
        val value = obj.opt(key) as? String
        if (value.isNullOrEmpty()) fail(PackError.MISSING_FIELD, where, "\"$key\" must be a non-empty string")
        return value
    }

    private fun number(
        obj: JSONObject,
        key: String,
        where: String,
    ): Float = optNumber(obj, key, where) ?: fail(PackError.MISSING_FIELD, where, "\"$key\" must be a number")

    private fun optNumber(
        obj: JSONObject,
        key: String,
        where: String,
    ): Float? {
        if (!obj.has(key)) return null
        val value = (obj.opt(key) as? Number)?.toFloat()
        if (value == null || !value.isFinite()) fail(PackError.BAD_VALUE, where, "\"$key\" must be a number")
        return value
    }

    private fun parameterSpec(entry: JSONObject): ParameterSpec {
        val key = string(entry, "key", FAMILIES_FILE)
        val typeName = string(entry, "type", FAMILIES_FILE)
        val type =
            ParameterType.entries.firstOrNull { it.manifestName == typeName }
                ?: fail(PackError.BAD_VALUE, FAMILIES_FILE, "parameter \"$key\" has unknown type \"$typeName\"")
        val min = number(entry, "min", FAMILIES_FILE)
        val max = number(entry, "max", FAMILIES_FILE)
        if (min > max) fail(PackError.BAD_VALUE, FAMILIES_FILE, "parameter \"$key\" has min above max")
        return ParameterSpec(key, type, min, max, optNumber(entry, "default", FAMILIES_FILE))
    }

    private fun shaderPath(
        doc: JSONObject,
        id: String,
    ): String? {
        if (!doc.has("shader")) return null
        val file = doc.opt("shader") as? String
        if (file.isNullOrEmpty()) fail(PackError.BAD_VALUE, id, "\"shader\" must name a file in the pack")
        return packFilePath(id, file)
    }

    private fun renderScale(
        doc: JSONObject,
        id: String,
    ): Float = optNumber(doc, "renderScale", id)?.coerceIn(MIN_RENDER_SCALE, MAX_RENDER_SCALE) ?: DEFAULT_RENDER_SCALE

    private fun detail(
        doc: JSONObject,
        id: String,
    ): DetailUse {
        if (!doc.has("detail")) return DetailUse.NONE
        val name = doc.opt("detail") as? String
        return DetailUse.entries.firstOrNull { it.manifestName == name }
            ?: fail(PackError.BAD_VALUE, id, "\"detail\" must be one of ${DetailUse.entries.map { it.manifestName }}")
    }

    private fun parameters(
        doc: JSONObject,
        family: FamilySchema,
        id: String,
    ): Map<String, Float> {
        val declared =
            when {
                !doc.has("parameters") -> JSONObject()
                else -> doc.opt("parameters") as? JSONObject ?: fail(PackError.MISSING_FIELD, id, "\"parameters\" must be an object")
            }
        declared.keys().forEach { key ->
            if (family.parameters.none { it.key == key }) {
                fail(PackError.UNKNOWN_PARAMETER, id, "family \"${family.kind.familyName}\" has no parameter \"$key\"")
            }
        }
        return family.parameters.associate { spec ->
            val value =
                optNumber(declared, spec.key, id)
                    ?: spec.default
                    ?: fail(PackError.MISSING_FIELD, id, "parameter \"${spec.key}\" has no default and must be set")
            spec.key to resolve(spec, value)
        }
    }

    private fun resolve(
        spec: ParameterSpec,
        value: Float,
    ): Float {
        val clamped = value.coerceIn(spec.min, spec.max)
        return if (spec.type == ParameterType.INT) Math.round(clamped).toFloat() else clamped
    }

    private fun list(
        doc: JSONObject,
        key: String,
        id: String,
    ): List<JSONObject> {
        if (!doc.has(key)) return emptyList()
        val array = doc.opt(key) as? JSONArray ?: fail(PackError.MISSING_FIELD, id, "\"$key\" must be an array")
        return objects(array, id, key)
    }

    private fun extras(
        doc: JSONObject,
        id: String,
    ): List<StyleExtra> =
        list(doc, "extras", id).map { entry ->
            val extra =
                StyleExtra(
                    name = string(entry, "name", id),
                    min = number(entry, "min", id),
                    max = number(entry, "max", id),
                    default = number(entry, "default", id),
                )
            if (extra.min > extra.max || extra.default !in extra.min..extra.max) {
                fail(PackError.BAD_VALUE, id, "extra \"${extra.name}\" has a default outside its range")
            }
            extra
        }

    private fun amount(
        entry: JSONObject,
        id: String,
    ): Float {
        val amount = number(entry, "amount", id)
        if (amount !in MIN_AMOUNT..MAX_AMOUNT) fail(PackError.BAD_VALUE, id, "\"amount\" must be within $MIN_AMOUNT..$MAX_AMOUNT")
        return amount
    }

    private fun macros(
        doc: JSONObject,
        id: String,
    ): List<StyleMacro> {
        val macros =
            list(doc, "macros", id).map { entry ->
                val links = entry.optJSONArray("links") ?: fail(PackError.MISSING_FIELD, id, "a Macro needs \"links\"")
                StyleMacro(
                    string(entry, "name", id),
                    objects(links, id, "link").map { MacroLink(string(it, "target", id), amount(it, id)) },
                )
            }
        if (macros.size > MAX_MACROS) fail(PackError.BAD_VALUE, id, "at most $MAX_MACROS Macros")
        if (macros.map { it.name }.toSet().size != macros.size) fail(PackError.BAD_VALUE, id, "two Macros share a name")
        return macros
    }

    private fun reactions(
        doc: JSONObject,
        id: String,
    ): List<StyleReaction> =
        list(doc, "reactions", id).map { entry ->
            val curveName = string(entry, "curve", id)
            val curve =
                ReactionCurve.entries.firstOrNull { it.manifestName == curveName }
                    ?: fail(PackError.BAD_VALUE, id, "unknown Reaction curve \"$curveName\"")
            val riseMs = number(entry, "riseMs", id)
            val fallMs = number(entry, "fallMs", id)
            if (riseMs < 0f || fallMs < 0f) fail(PackError.BAD_VALUE, id, "a Reaction's rise and fall are not negative")
            StyleReaction(string(entry, "signal", id), string(entry, "target", id), amount(entry, id), riseMs, fallMs, curve)
        }
}
