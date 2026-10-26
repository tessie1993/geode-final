package dev.geode.feature.studio.handlers

import org.json.JSONArray
import org.json.JSONException

/**
 * The frozen look of each track of a continuous video of a playlist (#326) as Studio's database keeps it
 * beside the Render: a JSON array of the looks' own text, in the tracks' order.
 */
internal object TrackLooksFormat {
    fun encode(looks: List<FrozenLook>): String = JSONArray(looks.map { it.text }).toString()

    /** The looks in [text]; none when there is no text, or it is not a list of looks. */
    fun decode(text: String?): List<FrozenLook> {
        if (text == null) return emptyList()
        return try {
            val array = JSONArray(text)
            List(array.length()) { FrozenLook(array.getString(it)) }
        } catch (unreadable: JSONException) {
            emptyList()
        }
    }
}
