#pragma once
#include <utility>

namespace geode::viz::fluid {

// The size of a simulation grid, in cells.
struct GridSize {
    int width = 0;
    int height = 0;

    bool empty() const { return width <= 0 || height <= 0; }
    bool operator==(const GridSize&) const = default;
};

// How far either side of a grid may drift before it counts as a real change (see gridChanges).
constexpr int kGridToleranceCells = 1;

// Port of FluidBuffers.resolution: a grid whose short side is `res` cells and whose long side follows
// the target's aspect. Only the aspect of (width, height) matters, never its pixel count, so a change
// of render scale alone leaves the grid where it was.
std::pair<int, int> resolution(int res, int width, int height);
GridSize gridFor(int res, int width, int height);

// Whether a simulation that holds `allocated` must rebuild its state to draw into `wanted`.
//
// A simulation keeps its declared grid: it is rebuilt (and so reseeded) only when that grid really
// changes, as on a rotation or a fold. Scaling the render target by a non-integer factor rounds its
// two sides independently, which can move the aspect - and so the long side of the grid - by a cell;
// nobody can see that, and resetting a simulation for it would be seen. An empty `allocated` (no
// state yet) always needs building.
bool gridChanges(GridSize allocated, GridSize wanted);

}  // namespace geode::viz::fluid
