#include "geode/audio/feed/HeardClock.hpp"

#include <gtest/gtest.h>

#include <cstdint>

using geode::audio::feed::OutputRoute;
namespace heard = geode::audio::feed::heard;

namespace {

constexpr int64_t kMs = 1'000'000;

}  // namespace

TEST(HeardClockTest, EveryRouteCodeReadsAsItsRoute) {
    EXPECT_EQ(heard::routeOf(0), OutputRoute::Unknown);
    EXPECT_EQ(heard::routeOf(1), OutputRoute::Speaker);
    EXPECT_EQ(heard::routeOf(2), OutputRoute::Wired);
    EXPECT_EQ(heard::routeOf(3), OutputRoute::Usb);
    EXPECT_EQ(heard::routeOf(4), OutputRoute::Bluetooth);
    EXPECT_EQ(heard::routeOf(5), OutputRoute::BluetoothLe);
    EXPECT_EQ(heard::routeOf(6), OutputRoute::Hdmi);
}

TEST(HeardClockTest, AnUnknownCodeIsAnUnknownRoute) {
    EXPECT_EQ(heard::routeOf(-1), OutputRoute::Unknown);
    EXPECT_EQ(heard::routeOf(7), OutputRoute::Unknown);
    EXPECT_EQ(heard::routeOf(1000), OutputRoute::Unknown);
}

TEST(HeardClockTest, EachRouteAddsItsOwnLatency) {
    EXPECT_EQ(heard::routeFallbackNs(OutputRoute::Unknown), 20 * kMs);
    EXPECT_EQ(heard::routeFallbackNs(OutputRoute::Speaker), 15 * kMs);
    EXPECT_EQ(heard::routeFallbackNs(OutputRoute::Wired), 10 * kMs);
    EXPECT_EQ(heard::routeFallbackNs(OutputRoute::Usb), 25 * kMs);
    EXPECT_EQ(heard::routeFallbackNs(OutputRoute::Bluetooth), 200 * kMs);
    EXPECT_EQ(heard::routeFallbackNs(OutputRoute::BluetoothLe), 80 * kMs);
    EXPECT_EQ(heard::routeFallbackNs(OutputRoute::Hdmi), 60 * kMs);
    EXPECT_EQ(heard::routeFallbackNs(static_cast<OutputRoute>(42)), 20 * kMs);
}

TEST(HeardClockTest, AFrameIsDrawnForWhenItReachesTheScreen) {
    EXPECT_EQ(heard::presentAheadNs(100 * kMs, 116 * kMs), 16 * kMs);
    EXPECT_EQ(heard::presentAheadNs(0, 1), 1);
}

TEST(HeardClockTest, AnUnknownOrPastPresentTimeIsNow) {
    EXPECT_EQ(heard::presentAheadNs(100 * kMs, 0), 0);
    EXPECT_EQ(heard::presentAheadNs(100 * kMs, -5), 0);
    EXPECT_EQ(heard::presentAheadNs(100 * kMs, 100 * kMs), 0);
    EXPECT_EQ(heard::presentAheadNs(100 * kMs, 99 * kMs), 0);
}

TEST(HeardClockTest, APresentTimeFarAheadIsHeldToTheLimit) {
    EXPECT_EQ(heard::presentAheadNs(kMs, kMs + heard::kMaxPresentAheadNs), heard::kMaxPresentAheadNs);
    EXPECT_EQ(heard::presentAheadNs(kMs, kMs + heard::kMaxPresentAheadNs + 1), heard::kMaxPresentAheadNs);
    EXPECT_EQ(heard::presentAheadNs(kMs, kMs + heard::kMaxPresentAheadNs - 1), heard::kMaxPresentAheadNs - 1);
}

TEST(HeardClockTest, TheUserOffsetIsHeldWithinHalfASecondEitherWay) {
    EXPECT_EQ(heard::clampUserOffsetNs(40 * kMs), 40 * kMs);
    EXPECT_EQ(heard::clampUserOffsetNs(-40 * kMs), -40 * kMs);
    EXPECT_EQ(heard::clampUserOffsetNs(heard::kMaxUserOffsetNs), heard::kMaxUserOffsetNs);
    EXPECT_EQ(heard::clampUserOffsetNs(heard::kMaxUserOffsetNs + 1), heard::kMaxUserOffsetNs);
    EXPECT_EQ(heard::clampUserOffsetNs(-heard::kMaxUserOffsetNs - 1), -heard::kMaxUserOffsetNs);
}
