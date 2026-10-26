#pragma once
#include <algorithm>
#include <array>

#include "geode/viz/Scene.hpp"

namespace geode::viz::grade {

// Which of the composite's groups apply to a picture (composite_frag.glsl, uGateA/uGateB). The
// palette tint rides on the grade's component: 2 there means the grade with the palette tint first.
struct Gate {
    bool geo;
    bool mirrorInvert;
    bool grade;
    bool pulse;
    bool palette = false;
    std::array<float, 4> toVec4() const {
        const float gradeGate = grade ? (palette ? 2.0f : 1.0f) : 0.0f;
        return {geo ? 1.0f : 0.0f, mirrorInvert ? 1.0f : 0.0f, gradeGate, pulse ? 1.0f : 0.0f};
    }
};

constexpr float kMinZoom = 0.05f;
constexpr float kMinGamma = 0.05f;
constexpr float kPulseGain = 0.22f;
constexpr float kTau = 6.2831855f;

// Shader Styles grade and move themselves; the fluid family applies nothing of its own. MilkDrop
// draws projectM's frame straight into its scene target (#303), so the composite grades it as it does
// the fluid family, with the palette tint that steers a preset's own colours.
inline Gate gateFor(SceneFamily family) {
    const bool milkdrop = family == SceneFamily::Milkdrop;
    const bool composited = family == SceneFamily::Fluid || milkdrop;
    return Gate{family != SceneFamily::Shader, composited, composited, composited, milkdrop};
}

float integrateRotation(float angle, float rotation, float dt);
float integrateCyclePhase(float phase, float cycleSpeed, float dt, bool enabled);
float pulseAmount(float pulse, float envelope);
inline float pulseScale(float amount) { return 1.0f + (amount > 0.0f ? amount : 0.0f) * kPulseGain; }
inline float brightness(float brightness, float intensity) { return brightness * intensity; }
inline float paletteSpan(float hueRange, float paletteRange) { return std::max(hueRange, 0.0f) * std::clamp(paletteRange, 0.0f, 1.0f); }
inline float paletteTintAmount(float tint) { return std::clamp(tint, 0.0f, 1.0f); }

}  // namespace geode::viz::grade
