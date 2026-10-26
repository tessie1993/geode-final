package dev.geode.feature.customization.handlers

import dev.geode.engine.bridge.CustomizationSnapshot
import dev.geode.engine.bridge.FailureRelay
import dev.geode.engine.bridge.NativeStatus
import dev.geode.engine.bridge.SnapshotPublisher
import dev.geode.engine.types.PresetId
import dev.geode.engine.types.SceneId
import dev.geode.feature.customization.api.Automations
import dev.geode.feature.customization.api.CustomizationCommand
import dev.geode.feature.customization.api.CustomizationFeature
import dev.geode.feature.customization.api.PRESET_VERSION
import dev.geode.feature.customization.api.PresetPart
import dev.geode.feature.customization.api.SharedProperty
import dev.geode.feature.customization.api.StylePool
import dev.geode.feature.customization.api.StyleSwitching
import dev.geode.feature.customization.core.BuiltInStyles
import dev.geode.feature.customization.core.Defaults
import dev.geode.kernel.FeatureFailure
import dev.geode.kernel.inPlaceRuntime
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Customization's store with its real handlers, as `:app` builds it: the engine behind a real
 * [SnapshotPublisher] whose native side is faked, and the preset folders in memory.
 */
class CustomizationStoreTest {
    private val julia = SceneId("julia")
    private val tunnel = SceneId("tunnel")
    private val failures = mutableListOf<FeatureFailure>()

    /** What the fake native side answers, one status per publish; OK once they run out. */
    private val answers = ArrayDeque<NativeStatus>()

    /** Every snapshot offered, and the bytes that crossed the bridge. */
    private val offered = mutableListOf<CustomizationSnapshot>()
    private val wire = mutableListOf<ByteArray>()

    private val publisher =
        SnapshotPublisher(
            publish = { bytes ->
                wire += bytes
                (answers.removeFirstOrNull() ?: NativeStatus.OK).code
            },
            failures = FailureRelay(),
        )

    private val places = MemoryPlaces()

    private val cues = StyleCues()

    private fun store(
        appStorage: Map<String, String> = emptyMap(),
        startStyle: SceneId = julia,
    ): CustomizationFeature {
        places.appStorage.contents.putAll(appStorage)
        val engine =
            EnginePublisher({ customization, _ -> zoomSnapshot(customization) }) { snapshot ->
                offered += snapshot
                publisher.offer(snapshot)
            }
        val library = PresetLibrary(places, LegacyModulation.None, BuiltInStyles.catalogue, fallbackStyle = julia)
        return customizationStore(CustomizationHandlers(engine, library, cues), startStyle, inPlaceRuntime(failures = failures))
    }

    /** The snapshot the engine has now. */
    private fun accepted(): CustomizationSnapshot = checkNotNull(publisher.accepted()) { "the engine has no snapshot" }

    private fun messages(): List<String> = failures.map { "${it.feature}: ${it.message}" }

    @Test
    fun `a new store hands its starting Customization to the engine`() {
        val store = store(startStyle = tunnel)

        assertEquals(Defaults.customization(BuiltInStyles.catalogue.manifestOf(tunnel)), store.state.value.current)
        assertEquals(1, offered.size)
        assertEquals(1, offered.single().revision)
        assertEquals(offered.single(), publisher.accepted())
        assertTrue(wire.single().contentEquals(offered.single().encode()))
    }

    @Test
    fun `the live Visual's cue moves Style switching on, unless it is held`() {
        val store = store()
        store.send(CustomizationCommand.UseVisualizerStyles(listOf(julia, tunnel)))
        store.send(CustomizationCommand.SetAutomations(Automations(style = StyleSwitching(pool = StylePool.VISUALIZER))))

        cues.raise()

        assertEquals(tunnel, store.state.value.current.style)

        store.send(CustomizationCommand.HoldStyle(true))
        cues.raise()

        assertEquals(tunnel, store.state.value.current.style)

        store.send(CustomizationCommand.StyleBack)

        assertEquals(julia, store.state.value.current.style)
    }

    @Test
    fun `a change in the store reaches the engine snapshot`() {
        val store = store()

        store.send(CustomizationCommand.SetShared(SharedProperty.ZOOM, 2f))

        val sent = accepted()
        assertEquals(2f, sent.params[0])
        assertEquals(2, sent.revision)
        assertTrue(wire.last().contentEquals(sent.encode()))
    }

    @Test
    fun `undo reaches the engine too`() {
        val store = store()
        store.send(CustomizationCommand.SetShared(SharedProperty.ZOOM, 2f))

        store.send(CustomizationCommand.Undo)

        assertEquals(1f, accepted().params[0])
        assertEquals(3, accepted().revision)
    }

    @Test
    fun `a change the snapshot does not carry is not sent again`() {
        val store = store()

        store.send(CustomizationCommand.SetShared(SharedProperty.SPEED, 2f))

        assertEquals(1, offered.size)
        assertEquals(1, wire.size)
    }

    @Test
    fun `a snapshot the engine refuses becomes a named failure and is offered again with the next change`() {
        val store = store()
        answers += NativeStatus.MALFORMED_SNAPSHOT

        store.send(CustomizationCommand.SetShared(SharedProperty.ZOOM, 2f))

        assertEquals(listOf("customization: the engine did not take the Customization"), messages())
        assertEquals(1f, accepted().params[0])

        store.send(CustomizationCommand.SetShared(SharedProperty.ZOOM, 2.5f))

        assertEquals(2.5f, accepted().params[0])
        assertEquals(1, failures.size)
    }

    @Test
    fun `a snapshot that cannot even be built is reported as refused`() {
        val engine = EnginePublisher({ _, _ -> error("no engine yet") }) { publisher.offer(it) }
        val library = PresetLibrary(places, LegacyModulation.None, BuiltInStyles.catalogue, fallbackStyle = julia)

        customizationStore(CustomizationHandlers(engine, library), julia, inPlaceRuntime(failures = failures))

        assertEquals(listOf("customization: the engine did not take the Customization"), messages())
        assertTrue(wire.isEmpty())
    }

    @Test
    fun `a busy engine is offered the same snapshot again`() {
        val store = store()
        answers += listOf(NativeStatus.BUSY, NativeStatus.BUSY)

        store.send(CustomizationCommand.SetShared(SharedProperty.ZOOM, 2f))

        assertEquals(2f, accepted().params[0])
        assertEquals(4, wire.size)
        assertTrue(failures.isEmpty())
    }

    @Test
    fun `an engine that stays busy is reported once the tries run out`() {
        val store = store()
        answers += listOf(NativeStatus.BUSY, NativeStatus.BUSY, NativeStatus.BUSY)

        store.send(CustomizationCommand.SetShared(SharedProperty.ZOOM, 2f))

        assertEquals(listOf("customization: the engine did not take the Customization"), messages())
        assertEquals(4, wire.size)
    }

    @Test
    fun `a saved preset is loaded back from its file`() {
        val store = store()
        store.send(CustomizationCommand.SetShared(SharedProperty.ZOOM, 2f))
        store.send(CustomizationCommand.SavePreset("Deep zoom", setOf(PresetPart.CAMERA)))
        store.send(CustomizationCommand.Undo)

        store.send(CustomizationCommand.LoadPreset(PresetId("Deep zoom")))

        assertEquals(2f, store.state.value.current.shared[SharedProperty.ZOOM])
        assertEquals(2f, accepted().params[0])
        val file = JSONObject(places.appStorage.read("Deep zoom.json"))
        assertEquals(PRESET_VERSION, file.getInt("version"))
        assertTrue(failures.isEmpty())
    }

    @Test
    fun `presets go to the folder Settings chose and are copied to the mirror folder`() {
        val chosen = MemoryPresetFolder()
        val mirror = MemoryPresetFolder()
        places.saved = chosen
        places.mirror = mirror
        val store = store()

        store.send(CustomizationCommand.SavePreset("Mine", setOf(PresetPart.CAMERA)))

        assertEquals(listOf("Mine.json"), chosen.files())
        assertEquals(chosen.contents, mirror.contents)
        assertTrue(places.appStorage.files().isEmpty())
        assertTrue(failures.isEmpty())
    }

    @Test
    fun `a preset that cannot be saved becomes a named failure`() {
        places.saved = MemoryPresetFolder(broken = true)
        val store = store()

        store.send(CustomizationCommand.SavePreset("Mine", setOf(PresetPart.CAMERA)))

        assertEquals(listOf("customization: could not save the preset"), messages())
    }

    @Test
    fun `a preset saved but not mirrored says so`() {
        places.mirror = MemoryPresetFolder(broken = true)
        val store = store()

        store.send(CustomizationCommand.SavePreset("Mine", setOf(PresetPart.CAMERA)))

        assertEquals(listOf("Mine.json"), places.appStorage.files())
        assertEquals(listOf("customization: saved the preset, but could not copy it to the mirror folder"), messages())
    }

    @Test
    fun `a preset that is not there becomes a named failure`() {
        val store = store()

        store.send(CustomizationCommand.LoadPreset(PresetId("Nothing")))

        assertEquals(listOf("customization: could not load the preset"), messages())
    }

    @Test
    fun `old presets are upgraded once when the store is built, and the originals are kept`() {
        val original = oldPresetFile("Old tunnel", "tunnel", zoom = 2.0)
        store(appStorage = mapOf("Old tunnel.json" to original, "trance/Gone.json" to oldPresetFile("Gone", "silk_web")))

        assertEquals(mapOf("Old tunnel.json" to original, "trance/Gone.json" to oldPresetFile("Gone", "silk_web")), places.backup.contents)
        val upgraded = JSONObject(places.appStorage.read("Old tunnel.json"))
        assertEquals(PRESET_VERSION, upgraded.getInt("version"))
        assertEquals("tunnel", upgraded.getString("style"))
        assertEquals("julia", JSONObject(places.appStorage.read("trance/Gone.json")).getString("style"))
        assertTrue(failures.isEmpty())

        val converted = places.appStorage.contents.toMap()
        places.backup.contents.clear()
        store()

        assertEquals(converted, places.appStorage.contents)
        assertTrue(places.backup.contents.isEmpty())
    }

    @Test
    fun `an upgraded old preset keeps its MilkDrop preset choice`() {
        store(appStorage = mapOf("Old.json" to oldPresetFile("Old", "milkdrop", extra = ",\n\"milkPreset\": \"Cosmic.milk\"")))

        assertEquals("Cosmic.milk", JSONObject(places.appStorage.read("Old.json")).getString("milkPreset"))
    }

    @Test
    fun `a preset from a newer Geode loads what it can, says so, and keeps the rest when saved again`() {
        val newer =
            JSONObject()
                .put("version", PRESET_VERSION + 1)
                .put("name", "Comet")
                .put("style", "julia")
                .put("parts", JSONArray(listOf("CAMERA", "COMETS")))
                .put("shared", JSONObject().put("ZOOM", 2.0).put("COMET_TAIL", 0.5))
                .put("comet", JSONObject().put("tail", 3))
        val store = store(appStorage = mapOf("Comet.json" to newer.toString()))

        store.send(CustomizationCommand.LoadPreset(PresetId("Comet")))

        assertEquals(2f, store.state.value.current.shared[SharedProperty.ZOOM])
        assertEquals(listOf("customization: some parts of this preset need a newer Geode: they are kept, but not applied"), messages())

        store.send(CustomizationCommand.SetShared(SharedProperty.ZOOM, 2.5f))
        store.send(CustomizationCommand.SavePreset("Comet", setOf(PresetPart.CAMERA)))

        val saved = JSONObject(places.appStorage.read("Comet.json"))
        assertEquals(PRESET_VERSION + 1, saved.getInt("version"))
        assertEquals(3, saved.getJSONObject("comet").getInt("tail"))
        assertEquals(0.5, saved.getJSONObject("shared").getDouble("COMET_TAIL"), 0.0)
        assertEquals(2.5, saved.getJSONObject("shared").getDouble("ZOOM"), 0.0)
        assertEquals("COMETS", saved.getJSONArray("parts").getString(1))
    }

    @Test
    fun `an upgraded old preset loads with its old values`() {
        val store = store(appStorage = mapOf("Old.json" to oldPresetFile("Old", "tunnel", zoom = 2.0)))

        store.send(CustomizationCommand.LoadPreset(PresetId("Old")))

        assertEquals(tunnel, store.state.value.current.style)
        assertEquals(2f, store.state.value.current.shared[SharedProperty.ZOOM])
        assertEquals(1f, store.state.value.current.shared[SharedProperty.KALEIDOSCOPE])
    }

    @Test
    fun `an old preset found later in the preset folder is upgraded as it is opened`() {
        val chosen = MemoryPresetFolder(mutableMapOf("Found.json" to oldPresetFile("Found", "tunnel", zoom = 2.0)))
        places.saved = chosen
        val store = store()

        store.send(CustomizationCommand.LoadPreset(PresetId("Found")))

        assertEquals(2f, store.state.value.current.shared[SharedProperty.ZOOM])
        assertEquals(oldPresetFile("Found", "tunnel", zoom = 2.0), chosen.read("Found.json"))
    }

    @Test
    fun `old presets that cannot be upgraded become a named failure, and the rest still are`() {
        places.backup.broken = true

        store(appStorage = mapOf("Old.json" to oldPresetFile("Old", "tunnel")))

        assertEquals(listOf("customization: could not upgrade the presets saved by an older Geode"), messages())
        assertEquals(oldPresetFile("Old", "tunnel"), places.appStorage.read("Old.json"))
    }

    @Test
    fun `a file that is not a preset is left as it was`() {
        store(appStorage = mapOf("notes.json" to "not json at all"))

        assertEquals("not json at all", places.appStorage.read("notes.json"))
        assertTrue(places.backup.contents.isEmpty())
        assertTrue(failures.isEmpty())
    }
}
