package dev.geode.render.scene

import dev.geode.engine.types.SceneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StylePackFormatTest {
    private val familiesText =
        """
        {"version": 1, "families": [
          {"name": "shader", "parameters": []},
          {"name": "cymatics", "parameters": [
            {"key": "mode", "type": "int", "min": 0, "max": 4},
            {"key": "gain", "type": "float", "min": 0, "max": 2, "default": 1.25},
            {"key": "cap", "type": "int", "min": 1, "max": 8, "default": 8}
          ]}
        ]}
        """.trimIndent()

    private val families: List<FamilySchema> =
        checkNotNull(StylePackFormat.parseFamilies(familiesText, mutableListOf()))

    private fun parse(
        folder: String,
        text: String,
    ): Pair<StylePack?, List<PackProblem>> {
        val problems = mutableListOf<PackProblem>()
        return StylePackFormat.parseManifest(folder, text, families, problems) to problems
    }

    private fun rejected(
        folder: String,
        text: String,
    ): PackError {
        val (pack, problems) = parse(folder, text)
        assertNull(pack)
        assertEquals(1, problems.size)
        assertEquals(folder, problems.single().where)
        return problems.single().error
    }

    @Test
    fun `paths sit in the Style folder`() {
        assertEquals("styles/kifs/manifest.json", StylePackFormat.manifestPath("kifs"))
        assertEquals("styles/kifs/shader.glsl", StylePackFormat.packFilePath("kifs", "shader.glsl"))
        assertEquals("styles/index.json", StylePackFormat.stylesPath(StylePackFormat.INDEX_FILE))
    }

    @Test
    fun `a manifest reads every field`() {
        val (pack, problems) =
            parse(
                "orb",
                """
                {"version": 1, "id": "orb", "label": "Orb", "family": "shader", "shader": "shader.glsl",
                 "renderScale": 0.75, "detail": "march",
                 "extras": [{"name": "density", "min": 0, "max": 2, "default": 0.5}],
                 "macros": [{"name": "energy", "links": [{"target": "density", "amount": 0.8}]}],
                 "reactions": [{"signal": "bass", "target": "density", "amount": -0.5, "riseMs": 30, "fallMs": 400,
                   "curve": "smooth"}]}
                """.trimIndent(),
            )
        assertTrue(problems.toString(), problems.isEmpty())
        checkNotNull(pack)
        assertEquals(SceneId("orb"), pack.id)
        assertEquals("Orb", pack.label)
        assertEquals(SceneKind.SHADER, pack.kind)
        assertEquals("styles/orb/shader.glsl", pack.shaderPath)
        assertEquals(0.75f, pack.renderScale)
        assertEquals(DetailUse.MARCH, pack.detail)
        assertEquals(listOf(StyleExtra("density", 0f, 2f, 0.5f)), pack.extras)
        assertEquals(listOf(StyleMacro("energy", listOf(MacroLink("density", 0.8f)))), pack.macros)
        assertEquals(listOf(StyleReaction("bass", "density", -0.5f, 30f, 400f, ReactionCurve.SMOOTH)), pack.reactions)
    }

    @Test
    fun `optional fields default`() {
        val (pack, _) = parse("plain", """{"version": 1, "id": "plain", "label": "Plain", "family": "shader"}""")
        checkNotNull(pack)
        assertNull(pack.shaderPath)
        assertEquals(StylePackFormat.DEFAULT_RENDER_SCALE, pack.renderScale)
        assertEquals(DetailUse.NONE, pack.detail)
        assertTrue(pack.parameters.isEmpty() && pack.extras.isEmpty() && pack.macros.isEmpty() && pack.reactions.isEmpty())
    }

    @Test
    fun `render scale is clamped`() {
        val (low, _) = parse("s", """{"version": 1, "id": "s", "label": "S", "family": "shader", "renderScale": 0.1}""")
        assertEquals(StylePackFormat.MIN_RENDER_SCALE, checkNotNull(low).renderScale)
        val (high, _) = parse("s", """{"version": 1, "id": "s", "label": "S", "family": "shader", "renderScale": 3}""")
        assertEquals(StylePackFormat.MAX_RENDER_SCALE, checkNotNull(high).renderScale)
    }

    @Test
    fun `parameters resolve against the family`() {
        val (pack, _) =
            parse("p", """{"version": 1, "id": "p", "label": "P", "family": "cymatics", "parameters": {"mode": 2.6, "gain": 9}}""")
        assertEquals(mapOf("mode" to 3f, "gain" to 2f, "cap" to 8f), checkNotNull(pack).parameters)
    }

    @Test
    fun `the forced Cymatics geometry is read from the pack and a negative one means none`() {
        assertEquals(null, SceneCapabilities.cymaticsGeometryOverride(SceneId("no such style")))
        StylePacks.registry.styles.filter { it.kind == SceneKind.CYMATICS }.forEach { pack ->
            val declared = Math.round(pack.parameters.getValue("geometryOverride"))
            assertEquals(declared.takeIf { it >= 0 }, SceneCapabilities.cymaticsGeometryOverride(pack.id))
        }
    }

    /** A shader manifest for folder `x` with [fields] added. */
    private fun shaderWith(fields: String): String = """{"version": 1, "id": "x", "label": "X", "family": "shader", $fields}"""

    private fun reaction(
        riseMs: Int,
        curve: String,
    ): String = """"reactions": [{"signal": "s", "target": "t", "amount": 1, "riseMs": $riseMs, "fallMs": 0, "curve": "$curve"}]"""

    @Test
    fun `broken manifests are rejected with a typed error`() {
        val cases =
            mapOf(
                """{"version": 1, "id": "x", "label": "X", "family": "cymatics"}""" to PackError.MISSING_FIELD,
                """{"version": 1, "id": "x", "label": "X", "family": "cymatics", "parameters": {"mode": 1, "hue": 1}}""" to
                    PackError.UNKNOWN_PARAMETER,
                """{"version": 1, "id": "x", "label": "X", "family": "cymatics", "parameters": {"mode": "two"}}""" to
                    PackError.BAD_VALUE,
                """{"version": 1, "id": "x", "label": "X", "family": "hologram"}""" to PackError.UNKNOWN_FAMILY,
                """{"version": 1, "id": "y", "label": "Y", "family": "shader"}""" to PackError.ID_MISMATCH,
                """{"version": 2, "id": "x", "label": "X", "family": "shader"}""" to PackError.WRONG_VERSION,
                "not json" to PackError.NOT_JSON,
                """{"version": 1, "id": "x", "family": "shader"}""" to PackError.MISSING_FIELD,
                shaderWith(""""shader": 3""") to PackError.BAD_VALUE,
                shaderWith(""""renderScale": "half"""") to PackError.BAD_VALUE,
                shaderWith(""""detail": "max"""") to PackError.BAD_VALUE,
                shaderWith(""""extras": [{"name": "a", "min": 1, "max": 0, "default": 0}]""") to PackError.BAD_VALUE,
                shaderWith(""""extras": [{"name": "a"}]""") to PackError.MISSING_FIELD,
                shaderWith(""""extras": {}""") to PackError.MISSING_FIELD,
                shaderWith(""""macros": [{"name": "a", "links": [{"target": "t", "amount": 2}]}]""") to PackError.BAD_VALUE,
                shaderWith(""""macros": [{"name": "a"}]""") to PackError.MISSING_FIELD,
                shaderWith(""""macros": [{"name": "a", "links": []}, {"name": "a", "links": []}]""") to PackError.BAD_VALUE,
                shaderWith(reaction(riseMs = -1, curve = "linear")) to PackError.BAD_VALUE,
                shaderWith(reaction(riseMs = 0, curve = "zigzag")) to PackError.BAD_VALUE,
            )
        cases.forEach { (text, error) -> assertEquals(text, error, rejected("x", text)) }
    }

    @Test
    fun `more than the shared set of Macros is rejected`() {
        val macros = (0..StylePackFormat.MAX_MACROS).joinToString(",") { """{"name": "m$it", "links": []}""" }
        assertEquals(PackError.BAD_VALUE, rejected("x", shaderWith(""""macros": [$macros]""")))
    }

    @Test
    fun `families read types ranges and defaults`() {
        val cymatics = families.single { it.kind == SceneKind.CYMATICS }
        assertEquals(ParameterSpec("mode", ParameterType.INT, 0f, 4f, null), cymatics.parameters[0])
        assertEquals(ParameterSpec("gain", ParameterType.FLOAT, 0f, 2f, 1.25f), cymatics.parameters[1])
    }

    private fun familyWith(parameter: String): String =
        """{"version": 1, "families": [{"name": "shader", "parameters": [$parameter]}]}"""

    @Test
    fun `broken families are rejected with a typed error`() {
        val cases =
            mapOf(
                """{"version": 1, "families": [{"name": "nope", "parameters": []}]}""" to PackError.UNKNOWN_FAMILY,
                """{"version": 1, "families": [{"name": "shader"}]}""" to PackError.MISSING_FIELD,
                """{"version": 1}""" to PackError.MISSING_FIELD,
                familyWith("""{"key": "k", "type": "bool", "min": 0, "max": 1}""") to PackError.BAD_VALUE,
                familyWith("""{"key": "k", "type": "int", "min": 2, "max": 1}""") to PackError.BAD_VALUE,
            )
        cases.forEach { (text, error) ->
            val problems = mutableListOf<PackProblem>()
            assertNull(text, StylePackFormat.parseFamilies(text, problems))
            assertEquals(text, listOf(error), problems.map { it.error })
        }
    }

    @Test
    fun `the index keeps its order and reports duplicates and blanks`() {
        val problems = mutableListOf<PackProblem>()
        val ids = StylePackFormat.parseIndex("""{"version": 1, "styles": ["b", "a", "b", ""]}""", problems)
        assertEquals(listOf(SceneId("b"), SceneId("a")), ids)
        assertEquals(listOf(PackError.DUPLICATE_ID, PackError.BAD_VALUE), problems.map { it.error })
        assertNull(StylePackFormat.parseIndex("""{"version": 1}""", mutableListOf()))
    }

    @Test
    fun `the registry keeps index order and leaves out broken packs`() {
        val files =
            mapOf(
                "styles/families.json" to familiesText,
                "styles/index.json" to """{"version": 1, "styles": ["b", "gone", "a", "broken"]}""",
                "styles/a/manifest.json" to """{"version": 1, "id": "a", "label": "A", "family": "shader"}""",
                "styles/b/manifest.json" to
                    """{"version": 1, "id": "b", "label": "B", "family": "cymatics", "parameters": {"mode": 1}}""",
                "styles/broken/manifest.json" to
                    """{"version": 1, "id": "broken", "label": "Broken", "family": "nope"}""",
            )
        val registry = StylePackRegistry.load { files[it] }
        assertEquals(listOf(SceneId("b"), SceneId("a")), registry.ids)
        assertEquals(listOf(SceneId("a")), registry.idsOf(SceneKind.SHADER))
        assertEquals(1f, checkNotNull(registry.find(SceneId("b"))).parameters["mode"])
        assertNull(registry.find(SceneId("gone")))
        assertEquals(listOf(PackError.MISSING_FILE, PackError.UNKNOWN_FAMILY), registry.problems.map { it.error })
        assertEquals("gone", registry.problems.first().where)
    }

    @Test
    fun `the registry is empty without its index or families`() {
        val noIndex = StylePackRegistry.load { if (it == "styles/families.json") familiesText else null }
        assertTrue(noIndex.styles.isEmpty())
        assertEquals(listOf(PackError.MISSING_FILE), noIndex.problems.map { it.error })
        val nothing = StylePackRegistry.load { null }
        assertEquals(listOf(PackError.MISSING_FILE, PackError.MISSING_FILE), nothing.problems.map { it.error })
    }
}
