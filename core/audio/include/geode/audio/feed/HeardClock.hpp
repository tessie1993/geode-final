#pragma once
#include <cstdint>

namespace geode::audio::feed {

// Where the player's sound comes out, as far as the latency past the output buffer goes. The codes are
// GEODE_FEED_ROUTE_* in geode_api.h.
enum class OutputRoute : int {
    Unknown = 0,
    Speaker = 1,
    Wired = 2,
    Usb = 3,
    Bluetooth = 4,
    BluetoothLe = 5,
    Hdmi = 6,
};

// The heard clock's small rules: what a route adds when the output stream cannot say how far behind the
// speaker is, and how far ahead of now a Visual frame will be on screen.
namespace heard {

// The furthest ahead of now a frame's expected present time is believed: a later one is a stale or
// foreign clock, and the frame is drawn for now instead.
constexpr int64_t kMaxPresentAheadNs = 100'000'000;

// The furthest the person may move the picture against the music, either way.
constexpr int64_t kMaxUserOffsetNs = 500'000'000;

// An unknown code reads as Unknown.
OutputRoute routeOf(int code);

// The latency between the end of the output buffer and the ear for a stream that gives no timestamp:
// the HAL, the DAC and, for wireless routes, the codec and the radio. A measured stream already has it.
int64_t routeFallbackNs(OutputRoute route);

// How long after nowNs a frame drawn now will be on screen: presentNs - nowNs, within 0 and
// kMaxPresentAheadNs; 0 when presentNs is unknown (0 or less) or already past.
int64_t presentAheadNs(int64_t nowNs, int64_t presentNs);

// The person's offset, within kMaxUserOffsetNs either way. Positive draws the picture later.
int64_t clampUserOffsetNs(int64_t offsetNs);

}  // namespace heard

}  // namespace geode::audio::feed
