#include "geode/bridge/TripleBuffer.hpp"

#include <gtest/gtest.h>

#include <array>
#include <atomic>
#include <cstdint>
#include <thread>

using geode::bridge::TripleBuffer;

namespace {

// Big enough that a torn copy (half old, half new) would show up as a mismatch between its words.
struct Frame {
    uint32_t sequence = 0;
    std::array<uint32_t, 64> words{};

    void fill(uint32_t value) {
        sequence = value;
        words.fill(value);
    }
    bool whole() const {
        for (const uint32_t word : words) {
            if (word != sequence) return false;
        }
        return true;
    }
};

constexpr uint32_t kStressValues = 200000;

}  // namespace

TEST(TripleBuffer, HasNothingFreshBeforeTheFirstPublish) {
    TripleBuffer<int> buffer;
    EXPECT_FALSE(buffer.refresh());
    EXPECT_EQ(buffer.front(), 0);
}

TEST(TripleBuffer, HandsOverAPublishedValueOnce) {
    TripleBuffer<int> buffer;
    buffer.back() = 5;
    buffer.publish();
    EXPECT_TRUE(buffer.refresh());
    EXPECT_EQ(buffer.front(), 5);
    EXPECT_FALSE(buffer.refresh());
    EXPECT_EQ(buffer.front(), 5);
}

TEST(TripleBuffer, KeepsTheFrontUntilTheReaderRefreshes) {
    TripleBuffer<int> buffer;
    buffer.back() = 1;
    buffer.publish();
    ASSERT_TRUE(buffer.refresh());
    buffer.back() = 2;
    buffer.publish();
    EXPECT_EQ(buffer.front(), 1);
    EXPECT_TRUE(buffer.refresh());
    EXPECT_EQ(buffer.front(), 2);
}

TEST(TripleBuffer, ASlowReaderSkipsToTheNewestValue) {
    TripleBuffer<int> buffer;
    for (int value = 1; value <= 5; ++value) {
        buffer.back() = value;
        buffer.publish();
    }
    EXPECT_TRUE(buffer.refresh());
    EXPECT_EQ(buffer.front(), 5);
    EXPECT_FALSE(buffer.refresh());
}

TEST(TripleBuffer, TheWriterNeverWritesIntoTheReadersSlot) {
    TripleBuffer<int> buffer;
    buffer.back() = 1;
    buffer.publish();
    ASSERT_TRUE(buffer.refresh());
    const int* front = &buffer.front();
    for (int value = 2; value < 10; ++value) {
        EXPECT_NE(&buffer.back(), front);
        buffer.back() = value;
        buffer.publish();
        EXPECT_EQ(buffer.front(), 1);
    }
}

TEST(TripleBuffer, TheWriterGetsADifferentSlotAfterEachPublish) {
    TripleBuffer<int> buffer;
    int* before = &buffer.back();
    buffer.publish();
    EXPECT_NE(&buffer.back(), before);
}

TEST(TripleBuffer, ReaderAndWriterUseAllThreeSlots) {
    TripleBuffer<int> buffer;
    buffer.back() = 1;
    buffer.publish();
    buffer.back() = 2;
    ASSERT_TRUE(buffer.refresh());
    EXPECT_EQ(buffer.front(), 1);
    EXPECT_NE(&buffer.back(), &buffer.front());
    buffer.publish();
    ASSERT_TRUE(buffer.refresh());
    EXPECT_EQ(buffer.front(), 2);
}

// One writer and one reader at full speed. Run under AddressSanitizer/UBSan with the rest of the suite and
// under ThreadSanitizer in its own CI job (docs/ci.md): the reader must only ever see whole values, in order.
TEST(TripleBufferStress, ReaderSeesWholeValuesInOrderUntilTheLast) {
    TripleBuffer<Frame> buffer;
    std::atomic<bool> done{false};
    std::thread writer([&] {
        for (uint32_t value = 1; value <= kStressValues; ++value) {
            buffer.back().fill(value);
            buffer.publish();
        }
        done.store(true, std::memory_order_release);
    });
    uint32_t last = 0;
    uint32_t torn = 0;
    uint32_t backwards = 0;
    uint32_t seen = 0;
    for (;;) {
        // Read before refreshing: once the writer is seen as done, a refresh that finds nothing new
        // means the newest value has already been read.
        const bool finished = done.load(std::memory_order_acquire);
        if (buffer.refresh()) {
            const Frame& frame = buffer.front();
            if (!frame.whole()) ++torn;
            if (frame.sequence <= last) ++backwards;
            last = frame.sequence;
            ++seen;
        } else if (finished) {
            break;
        }
    }
    writer.join();
    EXPECT_EQ(torn, 0u);
    EXPECT_EQ(backwards, 0u);
    EXPECT_GT(seen, 0u);
    EXPECT_EQ(last, kStressValues);
}
