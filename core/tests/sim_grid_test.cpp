#include "geode/viz/fluid/SimGrid.hpp"

#include <gtest/gtest.h>

#include <cmath>

namespace fluid = geode::viz::fluid;

namespace {
constexpr int kSimRes = 288;
constexpr int kPanelWidth = 1080;
constexpr int kPanelHeight = 2400;
}  // namespace

TEST(SimGrid, ShortSideIsTheDeclaredResolution) {
    const fluid::GridSize grid = fluid::gridFor(kSimRes, kPanelWidth, kPanelHeight);
    EXPECT_EQ(grid.width, kSimRes);
    EXPECT_EQ(grid.height, 640);
    const fluid::GridSize landscape = fluid::gridFor(kSimRes, kPanelHeight, kPanelWidth);
    EXPECT_EQ(landscape.width, 640);
    EXPECT_EQ(landscape.height, kSimRes);
}

TEST(SimGrid, NoTargetIsSquare) {
    EXPECT_EQ(fluid::gridFor(kSimRes, 0, 0), (fluid::GridSize{kSimRes, kSimRes}));
}

TEST(SimGrid, NoGridYetAlwaysNeedsBuilding) {
    EXPECT_TRUE(fluid::gridChanges({}, fluid::gridFor(kSimRes, kPanelWidth, kPanelHeight)));
}

// Every render scale the load and thermal ladders can reach leaves the grid where it was, so a
// simulation never resets because the phone got busy.
TEST(SimGrid, RenderScaleAloneNeverChangesTheGrid) {
    const fluid::GridSize base = fluid::gridFor(kSimRes, kPanelWidth, kPanelHeight);
    for (int percent = 30; percent <= 140; ++percent) {
        const float s = static_cast<float>(percent) / 100.0f;
        const int w = static_cast<int>(std::lround(kPanelWidth * s));
        const int h = static_cast<int>(std::lround(kPanelHeight * s));
        EXPECT_FALSE(fluid::gridChanges(base, fluid::gridFor(kSimRes, w, h))) << "scale " << s;
    }
}

TEST(SimGrid, RotationChangesTheGrid) {
    const fluid::GridSize portrait = fluid::gridFor(kSimRes, kPanelWidth, kPanelHeight);
    EXPECT_TRUE(fluid::gridChanges(portrait, fluid::gridFor(kSimRes, kPanelHeight, kPanelWidth)));
}

TEST(SimGrid, AFoldChangesTheGrid) {
    const fluid::GridSize folded = fluid::gridFor(kSimRes, kPanelWidth, kPanelHeight);
    EXPECT_TRUE(fluid::gridChanges(folded, fluid::gridFor(kSimRes, 2160, 1840)));
}

TEST(SimGrid, ADifferentDeclaredResolutionChangesTheGrid) {
    EXPECT_TRUE(fluid::gridChanges(fluid::gridFor(kSimRes, kPanelWidth, kPanelHeight), fluid::gridFor(384, kPanelWidth, kPanelHeight)));
}

TEST(SimGrid, OneCellOfRoundingIsNotAChange) {
    EXPECT_FALSE(fluid::gridChanges({288, 640}, {288, 641}));
    EXPECT_TRUE(fluid::gridChanges({288, 640}, {288, 642}));
}
