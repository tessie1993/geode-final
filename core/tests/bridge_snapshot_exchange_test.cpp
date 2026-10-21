#include "geode/bridge/SnapshotExchange.hpp"

#include <gtest/gtest.h>

#include <atomic>
#include <cstdint>
#include <thread>
#include <vector>

using geode::bridge::CustomizationSnapshot;
using geode::bridge::SnapshotExchange;
using geode::bridge::SnapshotReader;

namespace {

constexpr uint32_t kStressRevisions = 20000;
constexpr int kPublishesPerPublisher = 5000;

// Every float field carries the revision, so a reader can tell a whole snapshot from a torn one.
CustomizationSnapshot snapshotAt(uint32_t revision) {
    const float value = static_cast<float>(revision);
    CustomizationSnapshot s;
    s.revision = revision;
    s.reducedMotion = revision % 2 == 1;
    s.params.fill(value);
    s.lfo.fill(value);
    s.adsr.fill(value);
    s.soundSensitivity = value;
    s.soundRefractoryMs = value;
    s.soundAttackSeconds = value;
    s.soundReleaseSeconds = value;
    return s;
}

std::vector<uint8_t> wireAt(uint32_t revision) {
    std::vector<uint8_t> wire(geode::bridge::kSnapshotWireBytes);
    geode::bridge::encodeSnapshot(snapshotAt(revision), wire);
    return wire;
}

bool whole(const CustomizationSnapshot& s) {
    return s == snapshotAt(s.revision);
}

// Reads `reader` until the publisher is done and nothing newer is left, checking every snapshot it sees.
struct ReaderLog {
    uint32_t last = 0;
    uint32_t torn = 0;
    uint32_t backwards = 0;
};

ReaderLog readUntilDone(SnapshotExchange& exchange, SnapshotReader reader, const std::atomic<bool>& done) {
    ReaderLog log;
    for (;;) {
        const bool finished = done.load(std::memory_order_acquire);
        if (exchange.refresh(reader)) {
            const CustomizationSnapshot& s = exchange.current(reader);
            if (!whole(s)) ++log.torn;
            if (s.revision <= log.last) ++log.backwards;
            log.last = s.revision;
        } else if (finished) {
            return log;
        }
    }
}

}  // namespace

TEST(SnapshotExchange, StartsWithNothingPublished) {
    SnapshotExchange exchange;
    EXPECT_EQ(exchange.publishedRevision(), 0u);
    EXPECT_FALSE(exchange.refresh(SnapshotReader::Draw));
    EXPECT_FALSE(exchange.refresh(SnapshotReader::Audio));
}

TEST(SnapshotExchange, HandsAPublishedSnapshotToEveryReader) {
    SnapshotExchange exchange;
    ASSERT_EQ(exchange.publish(wireAt(3)), GEODE_STATUS_OK);
    EXPECT_EQ(exchange.publishedRevision(), 3u);
    ASSERT_TRUE(exchange.refresh(SnapshotReader::Draw));
    EXPECT_EQ(exchange.current(SnapshotReader::Draw), snapshotAt(3));
    ASSERT_TRUE(exchange.refresh(SnapshotReader::Audio));
    EXPECT_EQ(exchange.current(SnapshotReader::Audio), snapshotAt(3));
}

TEST(SnapshotExchange, ReadersRefreshIndependently) {
    SnapshotExchange exchange;
    ASSERT_EQ(exchange.publish(wireAt(1)), GEODE_STATUS_OK);
    ASSERT_TRUE(exchange.refresh(SnapshotReader::Draw));
    EXPECT_FALSE(exchange.refresh(SnapshotReader::Draw));
    ASSERT_EQ(exchange.publish(wireAt(2)), GEODE_STATUS_OK);
    ASSERT_TRUE(exchange.refresh(SnapshotReader::Audio));
    EXPECT_EQ(exchange.current(SnapshotReader::Audio).revision, 2u);
    EXPECT_EQ(exchange.current(SnapshotReader::Draw).revision, 1u);
    ASSERT_TRUE(exchange.refresh(SnapshotReader::Draw));
    EXPECT_EQ(exchange.current(SnapshotReader::Draw).revision, 2u);
}

TEST(SnapshotExchange, EachReaderSeesTheNewestOfSeveralPublishes) {
    SnapshotExchange exchange;
    for (uint32_t revision = 1; revision <= 4; ++revision) ASSERT_EQ(exchange.publish(wireAt(revision)), GEODE_STATUS_OK);
    ASSERT_TRUE(exchange.refresh(SnapshotReader::Draw));
    EXPECT_EQ(exchange.current(SnapshotReader::Draw), snapshotAt(4));
    ASSERT_TRUE(exchange.refresh(SnapshotReader::Audio));
    EXPECT_EQ(exchange.current(SnapshotReader::Audio), snapshotAt(4));
}

TEST(SnapshotExchange, ARejectedSnapshotChangesNothing) {
    SnapshotExchange exchange;
    ASSERT_EQ(exchange.publish(wireAt(1)), GEODE_STATUS_OK);
    ASSERT_TRUE(exchange.refresh(SnapshotReader::Draw));
    std::vector<uint8_t> bad = wireAt(2);
    bad.pop_back();
    EXPECT_EQ(exchange.publish(bad), GEODE_STATUS_MALFORMED_SNAPSHOT);
    EXPECT_EQ(exchange.publishedRevision(), 1u);
    EXPECT_FALSE(exchange.refresh(SnapshotReader::Draw));
    EXPECT_EQ(exchange.current(SnapshotReader::Draw), snapshotAt(1));
    ASSERT_TRUE(exchange.refresh(SnapshotReader::Audio));
    EXPECT_EQ(exchange.current(SnapshotReader::Audio), snapshotAt(1));
}

TEST(SnapshotExchange, ReportsWhyASnapshotWasRejected) {
    SnapshotExchange exchange;
    std::vector<uint8_t> otherVersion = wireAt(1);
    otherVersion[4] = static_cast<uint8_t>(geode::bridge::kSnapshotVersion + 1);
    EXPECT_EQ(exchange.publish(otherVersion), GEODE_STATUS_UNSUPPORTED_VERSION);
    EXPECT_EQ(exchange.publish({}), GEODE_STATUS_MALFORMED_SNAPSHOT);
}

TEST(SnapshotExchange, PublishesAgainAfterARejection) {
    SnapshotExchange exchange;
    EXPECT_EQ(exchange.publish({}), GEODE_STATUS_MALFORMED_SNAPSHOT);
    EXPECT_EQ(exchange.publish(wireAt(2)), GEODE_STATUS_OK);
    EXPECT_EQ(exchange.publishedRevision(), 2u);
}

// One publisher and both reader threads at full speed (ASan/UBSan here, ThreadSanitizer in its own CI job):
// each reader sees only whole snapshots, in order, and ends on the last one.
TEST(SnapshotExchangeStress, BothReadersSeeWholeSnapshotsInOrderUntilTheLast) {
    SnapshotExchange exchange;
    std::atomic<bool> done{false};
    std::thread publisher([&] {
        for (uint32_t revision = 1; revision <= kStressRevisions; ++revision) {
            if (exchange.publish(wireAt(revision)) != GEODE_STATUS_OK) ADD_FAILURE() << "revision " << revision;
        }
        done.store(true, std::memory_order_release);
    });
    ReaderLog audio;
    std::thread audioThread([&] { audio = readUntilDone(exchange, SnapshotReader::Audio, done); });
    const ReaderLog draw = readUntilDone(exchange, SnapshotReader::Draw, done);
    audioThread.join();
    publisher.join();
    for (const ReaderLog& log : {draw, audio}) {
        EXPECT_EQ(log.torn, 0u);
        EXPECT_EQ(log.backwards, 0u);
        EXPECT_EQ(log.last, kStressRevisions);
    }
}

// Two publishers racing: neither ever waits; the loser of each race is told BUSY and nothing tears.
TEST(SnapshotExchangeStress, RacingPublishersAreToldBusyAndNeverTear) {
    SnapshotExchange exchange;
    std::atomic<int> unexpected{0};
    std::atomic<int> accepted{0};
    std::atomic<int> publishersLeft{2};
    std::atomic<bool> done{false};
    auto publish = [&](uint32_t base) {
        for (int i = 0; i < kPublishesPerPublisher; ++i) {
            const GeodeStatus status = exchange.publish(wireAt(base + static_cast<uint32_t>(i)));
            if (status == GEODE_STATUS_OK) {
                accepted.fetch_add(1, std::memory_order_relaxed);
            } else if (status != GEODE_STATUS_BUSY) {
                unexpected.fetch_add(1, std::memory_order_relaxed);
            }
        }
        if (publishersLeft.fetch_sub(1, std::memory_order_acq_rel) == 1) done.store(true, std::memory_order_release);
    };
    std::thread first(publish, 1u);
    std::thread second(publish, 1u + kPublishesPerPublisher);
    uint32_t torn = 0;
    for (;;) {
        const bool finished = done.load(std::memory_order_acquire);
        if (exchange.refresh(SnapshotReader::Draw)) {
            if (!whole(exchange.current(SnapshotReader::Draw))) ++torn;
        } else if (finished) {
            break;
        }
    }
    first.join();
    second.join();
    EXPECT_EQ(unexpected.load(), 0);
    EXPECT_GT(accepted.load(), 0);
    EXPECT_EQ(torn, 0u);
    EXPECT_EQ(exchange.current(SnapshotReader::Draw).revision, exchange.publishedRevision());
}
