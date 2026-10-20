package dev.geode.playback

import android.net.Uri
import dev.geode.engine.types.TrackId

// The Android edge of TrackId: :engine:audio-core is JVM-only, so the Uri conversions live here.

fun TrackId.toUri(): Uri = Uri.parse(value)

fun Uri.toTrackId(): TrackId = TrackId(toString())
