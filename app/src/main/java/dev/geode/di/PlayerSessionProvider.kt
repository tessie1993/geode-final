package dev.geode.di

import android.app.Application
import dev.geode.feature.customization.api.CustomizationFeature
import dev.geode.feature.playback.api.Playback
import dev.geode.ui.OldScreenPlayback
import dev.geode.ui.PlayerSession
import dev.geode.ui.SettingsForOldScreens
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Holds the one [PlayerSession] every screen shares.
 *
 * The session is deliberately NOT a Hilt-managed singleton binding. Its teardown flushes pending
 * store writes, releases the audio engine's UI hold and cancels its scope, all of which must happen
 * when the UI goes away, not when the process dies. The music is not part of it: Playback's store is
 * app-scoped and outlives every screen (#17).
 *
 * Exactly one ViewModel owns that moment. `PlayerViewModel` is created unconditionally by
 * `AppRoot`, and every ViewModel here lives in the Activity's store (this app navigates by state,
 * not by `NavHost`, so there are no per-destination stores), which means all of them are cleared
 * together when the Activity finishes for real. So `PlayerViewModel.onCleared` calls [shutdown]
 * and the rest simply stop referring to the session — no holder counting, and no screen able to
 * tear down a session another screen is still reading.
 */
@Singleton
// Feature's Command and Fact are in/out, so Kotlin gives constructor parameters of Playback and
// Settings Java wildcards the provided stores lack; without this Dagger finds no binding for them.
@JvmSuppressWildcards
class PlayerSessionProvider
    @Inject
    constructor(
        private val application: Application,
        private val playback: Playback,
        private val oldScreens: OldScreenPlayback,
        private val oldSettings: SettingsForOldScreens,
        private val customization: CustomizationFeature,
    ) {
        private var instance: PlayerSession? = null

        /** The shared session, built on first use. Safe for any ViewModel to call. */
        @Synchronized
        fun get(): PlayerSession =
            instance ?: PlayerSession(application, playback, oldScreens, oldSettings, customization).also { instance = it }

        /**
         * Tears the session down and forgets it. Only the owning ViewModel may call this; a later
         * [get] builds a fresh session, which is what a new Activity should get.
         */
        @Synchronized
        fun shutdown() {
            instance?.shutdown()
            instance = null
        }
    }
