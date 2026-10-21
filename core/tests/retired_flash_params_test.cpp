#include <gtest/gtest.h>

#include <string>

#include "geode/viz/Params.hpp"

namespace geode::viz {
namespace {

// The No-flash rule removed the Strobe and Flash parameters (#89): the wire no longer carries
// them, and a caller still naming them is told the name is unknown rather than crashing.
TEST(RetiredFlashParams, StrobeAndFlashAreNotParametersAnyMore) {
    SceneParams p;
    EXPECT_FALSE(p.set("strobe", 0.5f));
    EXPECT_FALSE(p.set("flash", 0.6f));
    EXPECT_TRUE(p.set("glitch", 0.2f));
    EXPECT_FLOAT_EQ(p.glitch, 0.2f);
}

TEST(RetiredFlashParams, WireOrderHasNeitherName) {
    for (const char* name : SceneParams::fieldNames()) {
        ASSERT_NE(name, nullptr);
        EXPECT_NE(std::string(name), "strobe");
        EXPECT_NE(std::string(name), "flash");
    }
    for (const auto& field : SceneParams::lerpedFloats()) {
        ASSERT_TRUE(field.member != nullptr);
        EXPECT_NE(std::string(field.name), "strobe");
        EXPECT_NE(std::string(field.name), "flash");
    }
}

}  // namespace
}  // namespace geode::viz
