#include "geode/viz/TileFrame.hpp"

#include <algorithm>
#include <cmath>

namespace geode::viz::tile {

namespace {
// composite_frag.glsl's thresholds, each named after the test that uses it.
constexpr float kGateOn = 0.5f;
constexpr float kPaletteGateOn = 1.5f;
constexpr float kEffectEpsilon = 0.001f;
constexpr float kCameraEpsilon = 0.0001f;
constexpr float kShakeEpsilon = 0.000001f;
constexpr float kGradeEpsilon = 0.0001f;
constexpr float kTileOn = 1.01f;
constexpr float kMinSymmetry = 2.0f;

bool on(float gate) { return gate > kGateOn; }

// The Camera: the shake moves every picture; push-in, roll and turn, pan, orbit and the dive only where
// the composite draws them for this picture.
bool cameraStill(const CompositeLook& l) {
    if (std::fabs(l.camShakeX) + std::fabs(l.camShakeY) + std::fabs(l.camShakeRoll) > kShakeEpsilon) return false;
    const bool moves = on(l.camMoves);
    if (moves && l.camPush > kCameraEpsilon) return false;
    if (moves && std::fabs(l.camRoll) > kCameraEpsilon) return false;
    if (moves && std::fabs(l.camPanX) + std::fabs(l.camPanY) > kCameraEpsilon) return false;
    if (moves && std::fabs(l.camTiltYaw) + std::fabs(l.camTiltPitch) > kCameraEpsilon) return false;
    const float dive = (moves ? l.camTravelDive : 0.0f) + (on(l.camEndless) ? l.camEndlessDive : 0.0f);
    return dive - std::floor(dive) < kCameraEpsilon;
}

bool unaffected(const CompositeLook& l) {
    return l.flowStrength <= kEffectEpsilon && l.rippleStrength <= kEffectEpsilon && std::fabs(l.fisheye) <= kEffectEpsilon &&
           l.glitch <= kEffectEpsilon && l.chroma <= kEffectEpsilon && l.scanline <= kEffectEpsilon && l.grain <= kEffectEpsilon &&
           l.vignette <= kEffectEpsilon && l.anaglyph <= kEffectEpsilon && l.underlayAmount <= kGradeEpsilon;
}

bool geometryStill(const CompositeLook& l) {
    if (!on(l.gate[0])) return true;
    return std::fabs(l.sway) <= kEffectEpsilon && l.shake <= kEffectEpsilon && !(l.kaleido && l.symmetry >= kMinSymmetry) &&
           std::fabs(l.twist) <= kEffectEpsilon && l.warp <= kEffectEpsilon && l.ripple <= kEffectEpsilon &&
           std::fabs(l.driftX) + std::fabs(l.driftY) <= kEffectEpsilon && l.tile <= kTileOn && l.pixelate <= kEffectEpsilon &&
           l.bloom <= kEffectEpsilon && l.posterize <= kEffectEpsilon && l.temperature == 0.0f && !l.solarize;
}

bool gradeStill(const CompositeLook& l) {
    if (!on(l.gate[2])) return true;
    if (l.gate[2] > kPaletteGateOn && l.paletteTint > kEffectEpsilon) return false;
    return std::fabs(l.rotation) <= kGradeEpsilon && std::fabs(l.zoom - 1.0f) <= kGradeEpsilon && std::fabs(l.hue) <= kGradeEpsilon &&
           std::fabs(l.saturation - 1.0f) <= kGradeEpsilon && std::fabs(l.contrast - 1.0f) <= kGradeEpsilon &&
           std::fabs(l.gamma - 1.0f) <= kGradeEpsilon && l.brightness == 1.0f;
}
}  // namespace

Size frameSize(Size render, Size output) {
    return Size{std::max(std::min(render.width, output.width), 1), std::max(std::min(render.height, output.height), 1)};
}

Size outgoingSize(Size render) {
    return Size{std::max((render.width + 1) / 2, 1), std::max((render.height + 1) / 2, 1)};
}

bool compositeIsIdentity(const CompositeLook& look) {
    if (look.hasLayer || look.hasOutgoing) return false;
    if (!cameraStill(look) || !unaffected(look) || !geometryStill(look) || !gradeStill(look)) return false;
    if (on(look.gate[1]) && (look.mirror || look.invert)) return false;
    return !(on(look.gate[3]) && look.pulse > kGradeEpsilon);
}

}  // namespace geode::viz::tile
