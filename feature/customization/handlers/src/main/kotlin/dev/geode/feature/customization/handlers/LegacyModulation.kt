package dev.geode.feature.customization.handlers

import android.content.Context
import dev.geode.feature.customization.core.LegacyEnvelope
import dev.geode.feature.customization.core.LegacyLfo
import org.json.JSONArray
import org.json.JSONObject

/**
 * The app-wide LFO slots and envelopes an older Geode kept beside its presets, which every preset was
 * played with. The upgrade turns them into each preset's Modulators and Reactions.
 */
interface LegacyModulation {
    fun lfos(): List<LegacyLfo>

    fun envelopes(): List<LegacyEnvelope>

    /** For a phone that never ran an older Geode, and for tests. */
    object None : LegacyModulation {
        override fun lfos(): List<LegacyLfo> = emptyList()

        override fun envelopes(): List<LegacyEnvelope> = emptyList()
    }
}

/**
 * Reads the old slots as an older Geode wrote them (`LfoStore`: JSON arrays under `lfos` and `adsrs`),
 * with its fallbacks for a missing field. A rate saved in Hz by an even older build is read as the
 * period it described.
 */
object LegacyModulationFormat {
    fun lfos(json: String?): List<LegacyLfo> = objects(json).map(::lfo)

    fun envelopes(json: String?): List<LegacyEnvelope> = objects(json).map(::envelope)

    private fun lfo(o: JSONObject): LegacyLfo =
        LegacyLfo(
            enabled = o.optBoolean("enabled", false),
            source = o.optString("source", "LFO"),
            target = o.optString("target", "NONE"),
            wave = o.optString("wave", "SINE"),
            periodSeconds = periodSeconds(o),
            depth = o.optDouble("depth", DEFAULT_DEPTH),
            polarity = o.optString("polarity", "BIPOLAR"),
        )

    private fun envelope(o: JSONObject): LegacyEnvelope {
        val targets = o.optJSONArray("targets") ?: JSONArray()
        return LegacyEnvelope(
            enabled = o.optBoolean("enabled", false),
            targets = List(targets.length()) { targets.getString(it) },
            attackSeconds = o.optDouble("attack", DEFAULT_ATTACK),
            releaseSeconds = o.optDouble("release", DEFAULT_RELEASE),
            amount = o.optDouble("amount", DEFAULT_AMOUNT),
            band = o.optString("band", "BASS"),
        )
    }

    private fun periodSeconds(o: JSONObject): Double {
        if (o.has("rateSeconds")) return o.optDouble("rateSeconds", DEFAULT_PERIOD).coerceIn(MIN_PERIOD, MAX_PERIOD)
        val hz = o.optDouble("rateHz", 0.0)
        return if (hz > 0.0) (1.0 / hz).coerceIn(MIN_PERIOD, MAX_PERIOD) else DEFAULT_PERIOD
    }

    private fun objects(json: String?): List<JSONObject> {
        if (json.isNullOrBlank()) return emptyList()
        val array = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        return List(array.length()) { array.optJSONObject(it) }.filterNotNull()
    }

    // LfoConfig's and AdsrConfig's own defaults and limits.
    private const val DEFAULT_DEPTH = 0.3
    private const val DEFAULT_PERIOD = 2.0
    private const val MIN_PERIOD = 0.05
    private const val MAX_PERIOD = 60.0
    private const val DEFAULT_ATTACK = 0.05
    private const val DEFAULT_RELEASE = 0.35
    private const val DEFAULT_AMOUNT = 0.5
}

/** The old slots in the preferences file an older Geode kept them in. */
class SharedPreferencesLegacyModulation(
    context: Context,
) : LegacyModulation {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override fun lfos(): List<LegacyLfo> = LegacyModulationFormat.lfos(prefs.getString(LFOS, null))

    override fun envelopes(): List<LegacyEnvelope> = LegacyModulationFormat.envelopes(prefs.getString(ENVELOPES, null))

    private companion object {
        const val FILE = "geode-prefs"
        const val LFOS = "lfos"
        const val ENVELOPES = "adsrs"
    }
}
