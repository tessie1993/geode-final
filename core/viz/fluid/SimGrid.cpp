#include "geode/viz/fluid/SimGrid.hpp"

#include <algorithm>
#include <cmath>
#include <cstdlib>

namespace geode::viz::fluid {

namespace {
// Neither side of a grid ever drops below this, however extreme the aspect.
constexpr int kMinSideCells = 2;
}  // namespace

std::pair<int, int> resolution(int res, int width, int height) {
    if (width <= 0 || height <= 0) return {res, res};
    const float aspect = static_cast<float>(width) / static_cast<float>(height);
    if (aspect >= 1.0f) return {std::max(static_cast<int>(std::lround(res * aspect)), kMinSideCells), res};
    return {res, std::max(static_cast<int>(std::lround(res / aspect)), kMinSideCells)};
}

GridSize gridFor(int res, int width, int height) {
    const auto [w, h] = resolution(res, width, height);
    return {w, h};
}

bool gridChanges(GridSize allocated, GridSize wanted) {
    if (allocated.empty()) return true;
    return std::abs(allocated.width - wanted.width) > kGridToleranceCells ||
           std::abs(allocated.height - wanted.height) > kGridToleranceCells;
}

}  // namespace geode::viz::fluid
