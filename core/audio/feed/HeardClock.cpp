#include "geode/audio/feed/HeardClock.hpp"

#include <algorithm>

namespace geode::audio::feed::heard {

namespace {

constexpr int64_t kMs = 1'000'000;

}  // namespace

OutputRoute routeOf(int code) {
    switch (code) {
        case static_cast<int>(OutputRoute::Speaker): return OutputRoute::Speaker;
        case static_cast<int>(OutputRoute::Wired): return OutputRoute::Wired;
        case static_cast<int>(OutputRoute::Usb): return OutputRoute::Usb;
        case static_cast<int>(OutputRoute::Bluetooth): return OutputRoute::Bluetooth;
        case static_cast<int>(OutputRoute::BluetoothLe): return OutputRoute::BluetoothLe;
        case static_cast<int>(OutputRoute::Hdmi): return OutputRoute::Hdmi;
        default: return OutputRoute::Unknown;
    }
}

int64_t routeFallbackNs(OutputRoute route) {
    switch (route) {
        // The phone's own speaker and a headphone jack: the mixer and the DAC.
        case OutputRoute::Speaker: return 15 * kMs;
        case OutputRoute::Wired: return 10 * kMs;
        // A USB DAC buffers a few packets of its own.
        case OutputRoute::Usb: return 25 * kMs;
        // A2DP: the codec's frames, the radio and the headset's own jitter buffer.
        case OutputRoute::Bluetooth: return 200 * kMs;
        // LE Audio's isochronous channels run a much shorter presentation delay.
        case OutputRoute::BluetoothLe: return 80 * kMs;
        // A TV or receiver adds its own processing.
        case OutputRoute::Hdmi: return 60 * kMs;
        case OutputRoute::Unknown:
        default: return 20 * kMs;
    }
}

int64_t presentAheadNs(int64_t nowNs, int64_t presentNs) {
    if (presentNs <= 0 || presentNs <= nowNs) return 0;
    return std::min(presentNs - nowNs, kMaxPresentAheadNs);
}

int64_t clampUserOffsetNs(int64_t offsetNs) {
    return std::clamp(offsetNs, -kMaxUserOffsetNs, kMaxUserOffsetNs);
}

}  // namespace geode::audio::feed::heard
