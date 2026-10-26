package dev.geode.studio

import dev.geode.feature.studio.core.TrackCues
import dev.geode.render.offscreen.RenderCues
import dev.geode.render.offscreen.RenderSwitch
import dev.geode.render.offscreen.StyleSwitches

/**
 * One continuous video's changes of look (#326): it moves to each track's own Customization at the moment
 * [cues] names ([switchTo] builds the change to that track's look), and while a track plays, that track's
 * own Style switching (#343) runs, if its Customization has any ([stylesOf], built once per track). Asked
 * on the Render's own thread, in frame order.
 */
internal class PlaylistSwitches(
    private val cues: TrackCues,
    private val switchTo: (track: Int) -> RenderSwitch,
    private val stylesOf: (track: Int) -> StyleSwitches?,
) : RenderCues,
    StyleSwitches {
    private val styles = HashMap<Int, StyleSwitches?>()

    override fun dueBy(untilMs: Long): RenderSwitch? = cues.dueBy(untilMs)?.let(switchTo)

    override fun due(): RenderSwitch? = styles.getOrPut(cues.track) { stylesOf(cues.track) }?.due()
}
