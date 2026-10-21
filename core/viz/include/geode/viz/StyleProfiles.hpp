#pragma once
#include <string>
#include <string_view>
#include <vector>

#include "geode/viz/StylePacks.hpp"

namespace geode::viz::styles {

// The families a Style pack may name (`"family"` in its manifest). A family is a renderer, and so
// code; a Style is data: one more Style of an existing family is one more pack and nothing else.
namespace family {
inline constexpr std::string_view kShader = "shader";
inline constexpr std::string_view kLife = "life";
inline constexpr std::string_view kMycelium = "mycelium";
inline constexpr std::string_view kAcid = "acid";
inline constexpr std::string_view kMilkdrop = "milkdrop";
inline constexpr std::string_view kFluid = "fluid";
inline constexpr std::string_view kCurlFlow = "curl_flow";
inline constexpr std::string_view kWater = "water";
inline constexpr std::string_view kCymatics = "cymatics";
}  // namespace family

// The settings a family's renderer reads from the Style it draws, filled from the Style's pack. The
// values, their defaults and their ranges live in the packs (styles/<id>/manifest.json and
// styles/families.json); the zeros below are never what a Style draws with.
struct CymaticsStyle {
    std::string id;
    int shaderStyle = 0;
    int geometryOverride = 0;  // -1 = the user's cymaticsGeometry
    float scale = 0.0f;
    float fill = 0.0f;
    float line = 0.0f;
    float glow = 0.0f;
    float iridescence = 0.0f;
    float caustic = 0.0f;
    float flow = 0.0f;
    float swirl = 0.0f;
    float hueOffset = 0.0f;
    float hueSpan = 0.0f;
    int modeCap = 0;
};

struct LifeStyle {
    std::string id;
    int rule = 0;
    float dt = 0.0f;
    int core = 0;
    int growth = 0;
    float mu = 0.0f;
    float sigma = 0.0f;
    float radius = 0.0f;
    int rings = 0;
    float b1 = 0.0f;
    float b2 = 0.0f;
    float b3 = 0.0f;
    float feed = 0.0f;
    float kill = 0.0f;
    float aniso = 0.0f;
    int substeps = 0;
    int look = 0;
    float seedJitter = 0.0f;
    float hueOffset = 0.0f;
    float hueSpan = 0.0f;
};

struct AcidStyle {
    std::string id;
    int mode = 0;
    int source = 0;
    float zoom = 0.0f;
    float rotate = 0.0f;
    float hueRate = 0.0f;
    float feedback = 0.0f;
    float modulate = 0.0f;
    float glitch = 0.0f;
    float overdrive = 0.0f;
    float liquid = 0.0f;
    float scanline = 0.0f;
    float curve = 0.0f;
    float saturation = 0.0f;
    float hueOffset = 0.0f;
    float hueSpan = 0.0f;
};

struct MycoStyle {
    std::string id;
    int agentRes = 0;
    float sensorDist = 0.0f;
    float sensorAngle = 0.0f;
    float turnAngle = 0.0f;
    float moveStep = 0.0f;
    float jitter = 0.0f;
    float deposit = 0.0f;
    float decay = 0.0f;
    float speciesMix = 0.0f;
    float selfA = 0.0f;
    float crossAb = 0.0f;
    float crossBa = 0.0f;
    float selfB = 0.0f;
    float snap = 0.0f;
    float reaim = 0.0f;
    float aniso = 0.0f;
    int look = 0;
    float exposure = 0.0f;
    float hueOffset = 0.0f;
    float hueSpan = 0.0f;
};

// A profile over the FluidScene engine. Every scalar is a MULTIPLIER on the
// user's own Customize value for that quantity rather than a replacement, so
// the Fluid tab's controls keep working on every style; `look` selects the
// display-shader branch (fluid_display_frag's LOOK keyword) and `hueOffset`
// turns the emitters' base hue.
struct FluidStyle {
    std::string id;
    int look = 0;
    float curl = 0.0f;
    float velocityDissipation = 0.0f;
    float densityDissipation = 0.0f;
    float splatRadius = 0.0f;
    float splatForce = 0.0f;
    float bloom = 0.0f;
    float hueOffset = 0.0f;
};


CymaticsStyle cymaticsProfile(const packs::StylePack& pack);
LifeStyle lifeProfile(const packs::StylePack& pack);
AcidStyle acidProfile(const packs::StylePack& pack);
MycoStyle mycoProfile(const packs::StylePack& pack);
FluidStyle fluidProfile(const packs::StylePack& pack);

// The parameter keys the profile of `familyName` reads, in declaration order; empty for a family
// whose renderer reads none. CI checks them against the family's parameters in styles/families.json.
std::vector<std::string> profileKeys(std::string_view familyName);

}  // namespace geode::viz::styles
