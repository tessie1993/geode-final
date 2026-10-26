#pragma once
#include <array>
#include <cstddef>

#include "geode/viz/Normaliser.hpp"
#include "geode/viz/SharedCore.hpp"
#include "geode/viz/StylePacks.hpp"
#include "geode/viz/StyleProfiles.hpp"

namespace geode::viz {

// A Style's Extras in their native units, for its renderer: what its pack declares (the default, or
// the Customization's value once a plan is live), and a neutral value for every Extra it does not.
struct ExtraValues {
    std::array<float, mod::kExtraCount> value{};
    std::array<bool, mod::kExtraCount> declared{};

    // No Extra declared: every multiplier at 1, the centres at 0, variant 0.
    static ExtraValues neutral();
    // The neutral value of one Extra.
    static float neutralOf(mod::Extra extra);

    float operator[](mod::Extra extra) const { return value[static_cast<size_t>(extra)]; }
    bool has(mod::Extra extra) const { return declared[static_cast<size_t>(extra)]; }
    // A multiplier Extra, never below `floor` (a Style's scale or count must not reach 0).
    float multiplier(mod::Extra extra, float floor = 0.05f) const;
};

// The Extras end to end (docs/style-packs.md, "extras"): the pack declares them, the Customization
// moves them as fractions, the Normaliser maps those into the pack's ranges, and here they become the
// values each family's renderer draws with. A shader Style reads them as uniforms (lib_style.glsl);
// every other family maps them onto its own parameters below, so 1 (0 for a centre or the variant)
// always draws the Style exactly as its pack tunes it.
namespace extras {

// Declares every Extra `pack` names into `ranges`: its range, linear, and the variant on whole numbers.
void declare(mod::FamilyRanges& ranges, const packs::StylePack& pack);

// `pack`'s Extras: each one it declares at its value in `frame` (native units, the Normaliser's
// output), or at its default when no plan is live (`frame` null); neutral for every other. No pack:
// all neutral.
ExtraValues of(const packs::StylePack* pack, const mod::NativeFrame* frame);

// `base` moved by a multiplier Extra at `depth`: base * (1 + depth * (extra - 1)), the factor never
// below 0, so a signed base keeps its sign. At depth 1 it is base * extra; a smaller depth keeps a
// sensitive parameter near where the pack tuned it.
float scaled(float base, float extra, float depth = 1.0f);
// `base` shifted by `span` for each unit a multiplier Extra is off 1, kept to lo..hi: for a parameter
// the pack may leave at 0, where a multiplier would do nothing.
float nudged(float base, float extra, float span, float lo, float hi);
// A retention (the share of a mark that survives a step, 0..1) under a persistence Extra: its loss is
// divided by the Extra, so more of it keeps marks longer. Kept to 0..1.
float persisting(float retention, float extra);
// A retention under a damping Extra: its loss is multiplied by the Extra. Kept to 0..1.
float damped(float retention, float extra);
// A rate under a persistence Extra (a dissipation per second): divided by it, never below 0.
float dissipating(float rate, float extra);
// `base` moved on by the variant Extra's whole number, wrapped into `choices` (at least 1).
int variant(int base, float extra, int choices);
// A count times a COUNT Extra, rounded, kept to 1..most.
int count(int base, float extra, int most);

// The families that draw with profiles, under their Extras.
styles::LifeStyle apply(styles::LifeStyle style, const ExtraValues& x);
styles::MycoStyle apply(styles::MycoStyle style, const ExtraValues& x);
styles::AcidStyle apply(styles::AcidStyle style, const ExtraValues& x);
styles::FluidStyle apply(styles::FluidStyle style, const ExtraValues& x);
styles::CymaticsStyle apply(styles::CymaticsStyle style, const ExtraValues& x);

}  // namespace extras

}  // namespace geode::viz
