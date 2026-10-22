#include "geode/bridge/Guard.hpp"

#include <gtest/gtest.h>

#include <new>
#include <stdexcept>
#include <vector>

using geode::bridge::guarded;

namespace {

// Runs a body that throws `thrown` through guarded() and records what the caught-handler received.
template <typename Exception>
GeodeStatus statusFor(Exception thrown, std::vector<GeodeStatus>& caught) {
    return guarded([&]() -> GeodeStatus { throw thrown; },
                   [&](GeodeStatus status) { caught.push_back(status); });
}

}  // namespace

TEST(BridgeGuard, PassesTheBodysStatusThroughUntouched) {
    std::vector<GeodeStatus> caught;
    for (const GeodeStatus status : {GEODE_STATUS_OK, GEODE_STATUS_NULL_HANDLE, GEODE_STATUS_BUSY}) {
        EXPECT_EQ(guarded([&] { return status; }, [&](GeodeStatus s) { caught.push_back(s); }), status);
    }
    EXPECT_TRUE(caught.empty());
}

TEST(BridgeGuard, ReportsAFailedAllocationAsOutOfMemory) {
    std::vector<GeodeStatus> caught;
    EXPECT_EQ(statusFor(std::bad_alloc(), caught), GEODE_STATUS_OUT_OF_MEMORY);
    EXPECT_EQ(caught, std::vector<GeodeStatus>{GEODE_STATUS_OUT_OF_MEMORY});
}

TEST(BridgeGuard, ReportsABadArgumentAsInvalid) {
    std::vector<GeodeStatus> caught;
    EXPECT_EQ(statusFor(std::invalid_argument("bad"), caught), GEODE_STATUS_INVALID_ARGUMENT);
    EXPECT_EQ(statusFor(std::out_of_range("far"), caught), GEODE_STATUS_INVALID_ARGUMENT);
    EXPECT_EQ(caught, (std::vector<GeodeStatus>{GEODE_STATUS_INVALID_ARGUMENT, GEODE_STATUS_INVALID_ARGUMENT}));
}

TEST(BridgeGuard, ReportsAnyOtherExceptionAsAnEngineFailure) {
    std::vector<GeodeStatus> caught;
    EXPECT_EQ(statusFor(std::runtime_error("boom"), caught), GEODE_STATUS_ENGINE_FAILURE);
    EXPECT_EQ(statusFor(std::logic_error("oops"), caught), GEODE_STATUS_ENGINE_FAILURE);
    EXPECT_EQ(statusFor(42, caught), GEODE_STATUS_ENGINE_FAILURE);
    EXPECT_EQ(caught, (std::vector<GeodeStatus>{GEODE_STATUS_ENGINE_FAILURE, GEODE_STATUS_ENGINE_FAILURE,
                                                GEODE_STATUS_ENGINE_FAILURE}));
}

TEST(BridgeGuard, RunsTheBodyExactlyOnce) {
    int runs = 0;
    guarded([&] { ++runs; return GEODE_STATUS_OK; }, [](GeodeStatus) {});
    EXPECT_EQ(runs, 1);
    guarded([&]() -> GeodeStatus { ++runs; throw std::runtime_error("once"); }, [](GeodeStatus) {});
    EXPECT_EQ(runs, 2);
}
