#include "geode/viz/DisplayEvents.hpp"

#include <gtest/gtest.h>

using geode::viz::DisplayEvents;

namespace {

// A heard frame whose events have fired `seq` times, last with `strength`, and whose one-hop fields hold
// stale impulses the display frame must not repeat.
GeodeFeatureFrame heard(float seq, float strength) {
    GeodeFeatureFrame f{};
    f.rms = 0.4f;
    f.bass = 0.6f;
    f.beat = 1.0f;
    f.beatStrength = 0.9f;
    f.transient = 0.9f;
    f.kick = 0.9f;
    f.snare = 0.9f;
    f.hat = 0.9f;
    f.downbeat = 1.0f;
    f.sectionBoundary = 1.0f;
    f.drop = 1.0f;
    f.arrival = 1.0f;
    for (int i = 0; i < GEODE_EVENT_COUNT; ++i) {
        f.events[i].seq = seq;
        f.events[i].strength = strength + 0.01f * static_cast<float>(i);
    }
    return f;
}

void expectNoEvent(const GeodeFeatureFrame& f) {
    EXPECT_EQ(f.beat, 0.0f);
    EXPECT_EQ(f.beatStrength, 0.0f);
    EXPECT_EQ(f.transient, 0.0f);
    EXPECT_EQ(f.kick, 0.0f);
    EXPECT_EQ(f.snare, 0.0f);
    EXPECT_EQ(f.hat, 0.0f);
    EXPECT_EQ(f.downbeat, 0.0f);
    EXPECT_EQ(f.sectionBoundary, 0.0f);
    EXPECT_EQ(f.drop, 0.0f);
    EXPECT_EQ(f.arrival, 0.0f);
}

}  // namespace

TEST(DisplayEventsTest, EachEventFiresOnExactlyOneDisplayFrame) {
    DisplayEvents events;
    const GeodeFeatureFrame first = events.frame(heard(1.0f, 0.5f), true);
    EXPECT_EQ(first.beat, 1.0f);
    EXPECT_FLOAT_EQ(first.beatStrength, 0.5f);
    EXPECT_FLOAT_EQ(first.transient, 0.51f);
    EXPECT_FLOAT_EQ(first.kick, 0.52f);
    EXPECT_FLOAT_EQ(first.snare, 0.53f);
    EXPECT_FLOAT_EQ(first.hat, 0.54f);
    EXPECT_EQ(first.downbeat, 1.0f);
    EXPECT_EQ(first.sectionBoundary, 1.0f);
    EXPECT_EQ(first.drop, 1.0f);
    EXPECT_EQ(first.arrival, 1.0f);
    expectNoEvent(events.frame(heard(1.0f, 0.5f), true));
    EXPECT_EQ(events.frame(heard(2.0f, 0.7f), true).beat, 1.0f);
}

TEST(DisplayEventsTest, EverythingElseIsCarriedThrough) {
    DisplayEvents events;
    const GeodeFeatureFrame shown = events.frame(heard(0.0f, 0.5f), true);
    EXPECT_FLOAT_EQ(shown.rms, 0.4f);
    EXPECT_FLOAT_EQ(shown.bass, 0.6f);
    EXPECT_FLOAT_EQ(shown.events[GEODE_EVENT_KICK].strength, 0.52f);
}

TEST(DisplayEventsTest, ASequenceAtZeroIsAResetNotAnEvent) {
    DisplayEvents events;
    expectNoEvent(events.frame(heard(0.0f, 0.5f), true));
    events.frame(heard(3.0f, 0.5f), true);
    expectNoEvent(events.frame(heard(0.0f, 0.5f), true));
    // After the reset the count starts over, and its first event fires.
    EXPECT_EQ(events.frame(heard(1.0f, 0.5f), true).beat, 1.0f);
}

TEST(DisplayEventsTest, SilenceFiresNothingAndForgetsNothing) {
    DisplayEvents events;
    events.frame(heard(4.0f, 0.5f), true);
    expectNoEvent(events.frame(heard(5.0f, 0.5f), false));
    expectNoEvent(events.frame(GeodeFeatureFrame{}, false));
    // Heard again with nothing new: no event. With one new: it fires once.
    expectNoEvent(events.frame(heard(4.0f, 0.5f), true));
    EXPECT_EQ(events.frame(heard(5.0f, 0.5f), true).beat, 1.0f);
}

TEST(DisplayEventsTest, AResetReaderSeesTheNextEventAgain) {
    DisplayEvents events;
    events.frame(heard(1.0f, 0.5f), true);
    events.reset();
    EXPECT_EQ(events.frame(heard(1.0f, 0.5f), true).beat, 1.0f);
}
