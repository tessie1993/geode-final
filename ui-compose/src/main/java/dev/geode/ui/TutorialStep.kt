package dev.geode.ui

import androidx.annotation.StringRes
import dev.geode.presentation.TourStop
import dev.geode.resources.R

/** Resource-only appearance; order, availability and navigation belong to the state machine. */
data class TutorialStep(
    @param:StringRes val titleRes: Int,
    @param:StringRes val bodyRes: Int,
)

fun TourStop.appearance(): TutorialStep =
    when (this) {
        TourStop.PLAYER -> TutorialStep(R.string.tutorial_player_title, R.string.tutorial_player_body)
        TourStop.LIBRARY -> TutorialStep(R.string.tutorial_library_title, R.string.tutorial_library_body)
        TourStop.VISUALS -> TutorialStep(R.string.tutorial_visuals_title, R.string.tutorial_visuals_body)
        TourStop.TOUCH -> TutorialStep(R.string.tutorial_touch_title, R.string.tutorial_touch_body)
        TourStop.STUDIO -> TutorialStep(R.string.tutorial_studio_title, R.string.tutorial_studio_body)
        TourStop.SETTINGS -> TutorialStep(R.string.tutorial_settings_title, R.string.tutorial_settings_body)
    }
