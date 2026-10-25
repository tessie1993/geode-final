#pragma once
#include <array>
#include <cstddef>
#include <cstdint>
#include <string_view>

#include "geode/viz/ReactionGraph.hpp"
#include "geode/viz/SharedCore.hpp"

namespace geode::viz::mod {

// The Normaliser between Customization and the shaders (#54, #59; owner decision 2026-10-22).
// Customization works only in normalised units: properties, Extras and Macros are fractions 0..1 of
// their range, link amounts -1..1. Each Style family has its own parameters: its manifest (#59's
// Style packs) declares its parameters and Extras with ranges, and a curve where one is needed. Per
// frame, after the ReactionGraph, the engine maps the evaluated fractions into the family's ranges
// before the uniforms are set; the safety clamp (finite, inside the family's range) runs last.

// How a fraction spreads over a range: evenly, slow then fast (x^2), or fast then slow (sqrt x).
enum class RangeCurve : uint8_t { Linear, Exponential, Logarithmic };

// One native range: `min` at 0, `max` at 1 (either way round), along `curve`; `stepped` values land
// on whole numbers.
struct NativeRange {
    float min = 0.0f;
    float max = 1.0f;
    RangeCurve curve = RangeCurve::Linear;
    bool stepped = false;
};

// `fraction` (kept to 0..1; not a number reads as 0) into `range`.
float toNative(const NativeRange& range, float fraction);

// A manifest's curve name (#59's Style packs): "linear", "exponential" or "logarithmic"; anything
// else, or none, is linear.
RangeCurve curveNamed(std::string_view name);

// The place of the Extra a manifest names (ExtraKind in :feature:customization:api, lower case:
// "scale", "count", ... "center_y"), or -1 when there is none of that name.
int extraNamed(std::string_view name);

// The ranges one Style family's Customization maps into.
struct FamilyRanges {
    std::array<NativeRange, kSharedCount> shared{};
    std::array<NativeRange, kExtraCount> extras{};

    // The Shared core's own ranges (SharedCore.cpp), and every Extra over 0..1: what a family that
    // declares nothing of its own maps into. toNative then gives exactly fromUnit.
    static FamilyRanges sharedCore();

    // Declares Extra `name`'s range, as a manifest's `extras` entry does (`name`, `min`, `max`, and
    // optionally `curve` and whole-number steps). False, changing nothing, when no Extra has that name
    // or the range is not finite.
    bool declareExtra(std::string_view name, float min, float max, std::string_view curve = "linear",
                      bool stepped = false);

    // Declares a Shared core property's range for this family. False, changing nothing, when it is
    // not finite.
    bool declareShared(Shared property, const NativeRange& range);
};

// One frame in a family's own units, ready for the uniforms.
struct NativeFrame {
    std::array<float, kSharedCount> shared{};
    std::array<float, kExtraCount> extras{};

    float operator[](Shared p) const { return shared[static_cast<size_t>(p)]; }
};

// `evaluated` (fractions) mapped into `family`'s ranges; then the safety clamp, last: every value
// finite and inside its range.
NativeFrame toNative(const FamilyRanges& family, const Evaluated& evaluated);

}  // namespace geode::viz::mod
