package dev.geode.playback

import android.media.AudioDeviceInfo
import dev.geode.engine.audio.OutputRoute
import org.junit.Assert.assertEquals
import org.junit.Test

/** Which route media plays on, from the kinds of output device present. */
class OutputRouteWatcherTest {
    private fun routeOf(vararg types: Int) = OutputRouteWatcher.routeOf(types.toList())

    @Test
    fun `each kind of device names its route`() {
        assertEquals(OutputRoute.BLUETOOTH, routeOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP))
        assertEquals(OutputRoute.BLUETOOTH_LE, routeOf(AudioDeviceInfo.TYPE_BLE_HEADSET))
        assertEquals(OutputRoute.BLUETOOTH_LE, routeOf(AudioDeviceInfo.TYPE_BLE_SPEAKER))
        assertEquals(OutputRoute.USB, routeOf(AudioDeviceInfo.TYPE_USB_HEADSET))
        assertEquals(OutputRoute.USB, routeOf(AudioDeviceInfo.TYPE_USB_DEVICE))
        assertEquals(OutputRoute.USB, routeOf(AudioDeviceInfo.TYPE_USB_ACCESSORY))
        assertEquals(OutputRoute.WIRED, routeOf(AudioDeviceInfo.TYPE_WIRED_HEADSET))
        assertEquals(OutputRoute.WIRED, routeOf(AudioDeviceInfo.TYPE_WIRED_HEADPHONES))
        assertEquals(OutputRoute.WIRED, routeOf(AudioDeviceInfo.TYPE_LINE_ANALOG))
        assertEquals(OutputRoute.WIRED, routeOf(AudioDeviceInfo.TYPE_LINE_DIGITAL))
        assertEquals(OutputRoute.WIRED, routeOf(AudioDeviceInfo.TYPE_AUX_LINE))
        assertEquals(OutputRoute.HDMI, routeOf(AudioDeviceInfo.TYPE_HDMI))
        assertEquals(OutputRoute.HDMI, routeOf(AudioDeviceInfo.TYPE_HDMI_ARC))
        assertEquals(OutputRoute.HDMI, routeOf(AudioDeviceInfo.TYPE_HDMI_EARC))
        assertEquals(OutputRoute.SPEAKER, routeOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER))
        assertEquals(OutputRoute.SPEAKER, routeOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE))
    }

    @Test
    fun `a connected headset wins over the speaker`() {
        assertEquals(OutputRoute.BLUETOOTH, routeOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP))
        assertEquals(OutputRoute.WIRED, routeOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_WIRED_HEADPHONES))
        assertEquals(OutputRoute.BLUETOOTH, routeOf(AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP))
        assertEquals(OutputRoute.USB, routeOf(AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_USB_HEADSET))
    }

    @Test
    fun `nothing known is an unknown route`() {
        assertEquals(OutputRoute.UNKNOWN, routeOf())
        assertEquals(OutputRoute.UNKNOWN, routeOf(AudioDeviceInfo.TYPE_TELEPHONY))
    }
}
