package dev.geode.ui

import android.app.Application
import android.net.Uri
import dev.geode.feature.playback.api.Playback
import dev.geode.feature.playback.api.currentQueue
import dev.geode.geodeContainer
import dev.geode.playback.toUri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The playing track's lyrics, looked up from its `.lrc` sidecar or its own tags, for the Studio
 * timeline's lyric captions (#17). Geode shows no lyrics anywhere else; the old full-screen lyric
 * panel is gone. Follows Playback so a new song loads its own lyrics, and drops them when nothing
 * plays.
 */
@Singleton
// Playback's Command and Fact are in/out, so Kotlin gives the store parameter Java wildcards the
// provided store lacks; without this Dagger finds no binding for it.
@JvmSuppressWildcards
class LyricsController internal constructor(
    private val application: Application,
    private val playback: Playback,
    private val scope: CoroutineScope,
) {
    @Inject
    constructor(application: Application, playback: Playback) :
        this(application, playback, application.geodeContainer.appScope)

    private val _lyrics = MutableStateFlow<Lyrics?>(null)

    val lyrics: StateFlow<Lyrics?> = _lyrics

    init {
        scope.launch {
            playback.state
                .map {
                    it.player.currentQueue
                        ?.current
                        ?.id
                }.distinctUntilChanged()
                .collect { loadLyricsFor(it?.toUri()) }
        }
    }

    private fun loadLyricsFor(uri: Uri?) {
        _lyrics.value = null
        if (uri == null) return
        scope.launch(Dispatchers.IO) {
            val found = LyricsLoader.load(application, uri)
            withContext(Dispatchers.Main) { if (currentUri == uri) _lyrics.value = found }
        }
    }

    private val currentUri: Uri? get() =
        playback.state.value.player.currentQueue
            ?.current
            ?.id
            ?.toUri()
}
