package dev.geode.ui

import androidx.annotation.StringRes
import dev.geode.R
import dev.geode.ui.shell.Place

/**
 * One stop on the walkthrough.
 *
 * [destination] is the point: the tour NAVIGATES the app rather than describing it, so the thing
 * being explained is on screen behind the card while it is explained. A slideshow of screenshots
 * would go stale the first time a tab moved; this cannot, because it is the app.
 *
 * Every place is on the Bar for everyone (PR #186), so every step applies to everyone. The player's
 * controls are in Music now there is no Player tab (#17).
 */
enum class TutorialStep(
    val destination: Place,
    @param:StringRes val titleRes: Int,
    @param:StringRes val bodyRes: Int,
) {
    PLAYER(Place.MUSIC, R.string.tutorial_player_title, R.string.tutorial_player_body),
    LIBRARY(Place.MUSIC, R.string.tutorial_library_title, R.string.tutorial_library_body),
    VISUALS(Place.VISUALS, R.string.tutorial_visuals_title, R.string.tutorial_visuals_body),

    /**
     * Touch gets its own step on the Visuals tab rather than sharing the one above.
     *
     * It is the least discoverable thing in the app — nothing on screen suggests that a finger
     * does anything — and it is the feature most likely to be missed entirely.
     */
    TOUCH(Place.VISUALS, R.string.tutorial_touch_title, R.string.tutorial_touch_body),
    STUDIO(Place.STUDIO, R.string.tutorial_studio_title, R.string.tutorial_studio_body),
    SETTINGS(Place.SETTINGS, R.string.tutorial_settings_title, R.string.tutorial_settings_body),
}
