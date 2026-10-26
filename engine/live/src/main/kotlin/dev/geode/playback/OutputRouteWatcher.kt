package dev.geode.playback

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresApi
import dev.geode.engine.audio.OutputRoute
import dev.geode.engine.audio.VisualFeed
import dev.geode.util.bestEffort

/**
 * Tells the Visual's feed where the player's sound comes out (#295), so an output that cannot time
 * itself is still drawn in step: on a Bluetooth headset a couple of hundred milliseconds later than on
 * the speaker. Outputs that report timestamps (most AAudio streams, and AudioTrack under ExoPlayer)
 * already count the route and are unaffected. Devices come and go on the main thread.
 */
internal class OutputRouteWatcher(
    context: Context,
    private val feed: VisualFeed,
) {
    private val audioManager: AudioManager? = context.applicationContext.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var started = false

    private val callback =
        object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = update()

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = update()
        }

    fun start() {
        val manager = audioManager ?: return
        if (started) return
        started = true
        // The callback reports every device present on registering, which sets the first route.
        bestEffort(TAG, "registerAudioDeviceCallback") { manager.registerAudioDeviceCallback(callback, main) }
        update()
    }

    fun stop() {
        if (!started) return
        started = false
        bestEffort(TAG, "unregisterAudioDeviceCallback") { audioManager?.unregisterAudioDeviceCallback(callback) }
    }

    private fun update() {
        val manager = audioManager ?: return
        runCatching { mediaOutputTypes(manager) }
            .onSuccess { feed.setRoute(routeOf(it)) }
            .onFailure { Log.w(TAG, "reading the media output failed", it) }
    }

    private fun mediaOutputTypes(manager: AudioManager): List<Int> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            routedTypes(manager).ifEmpty { allOutputTypes(manager) }
        } else {
            allOutputTypes(manager)
        }

    private fun allOutputTypes(manager: AudioManager): List<Int> = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.type }

    // The devices media is routed to right now, as the platform says (API 33).
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun routedTypes(manager: AudioManager): List<Int> {
        val media =
            AudioAttributes
                .Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
        return manager.getAudioDevicesForAttributes(media).map { it.type }
    }

    internal companion object {
        private const val TAG = "OutputRouteWatcher"

        /** The device kinds of each route, in the order media prefers them when several are connected. */
        private val PREFERENCE: List<Pair<OutputRoute, Set<Int>>> =
            listOf(
                OutputRoute.BLUETOOTH to setOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP),
                OutputRoute.BLUETOOTH_LE to setOf(AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER),
                OutputRoute.USB to
                    setOf(AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_ACCESSORY),
                OutputRoute.WIRED to
                    setOf(
                        AudioDeviceInfo.TYPE_WIRED_HEADSET,
                        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                        AudioDeviceInfo.TYPE_LINE_ANALOG,
                        AudioDeviceInfo.TYPE_LINE_DIGITAL,
                        AudioDeviceInfo.TYPE_AUX_LINE,
                    ),
                OutputRoute.HDMI to
                    setOf(AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_HDMI_ARC, AudioDeviceInfo.TYPE_HDMI_EARC),
                OutputRoute.SPEAKER to setOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE),
            )

        /**
         * The route media plays on, from the device kinds ([AudioDeviceInfo.getType]) it may go to: the
         * most preferred one present, or [OutputRoute.UNKNOWN] when none is known.
         */
        fun routeOf(types: Collection<Int>): OutputRoute =
            PREFERENCE.firstOrNull { (_, kinds) -> types.any { it in kinds } }?.first ?: OutputRoute.UNKNOWN
    }
}
