#pragma once
#include <cstdint>
#include <string_view>

namespace geode::viz::layer {

// Layer 2 (CONTEXT.md, Layer): a second Style drawn over the active one and composited with it in one
// pass (composite_frag.glsl, lib_layers.glsl) with the W3C Compositing and Blending Level 1 formulas, in
// display space on values kept to 0..1, except Add, which adds in linear light under a soft shoulder.
// The Customization owns it (Layer in :feature:customization:api); the plan carries its Style, blend
// and influence (PlanCodec), and its opacity, influence amount and swap are Shared core properties, so every Reaction, Modulator, Macro and lane can move them. This is the part that
// needs no GL: the mode tables, the Style's name in the plan and the per-frame mixing.

// How Layer 2 is laid over Layer 1, in LayerBlend's order; the shader's uLayerBlend reads the same.
// Hard Light and Color are Overlay and Luminosity with the layers swapped (Shared::LayerSwap at 1).
enum class Blend : uint8_t { Normal, Add, Screen, Lighten, Multiply, Overlay, SoftLight, Difference, Exclusion, Luminosity };
constexpr int kBlendCount = 10;

// How Layer 2 acts on Layer 1 before it is laid over it, in LayerInfluence's order: its luminance
// gradient bends Layer 1's sampling away from its edges (Bend, a refraction) or along them (Swirl), its
// luminance gates Layer 1 (CutOut, a luma key), its colour recolours Layer 1 at Layer 1's own luminance
// (Tint), or Layer 1 shows only along Layer 2's edges (Outline).
enum class Influence : uint8_t { None, Bend, Swirl, CutOut, Tint, Outline };
constexpr int kInfluenceCount = 6;

// The mode at `ordinal`, rounded; anything out of range or not a number is Normal / None.
Blend blendAt(float ordinal);
Influence influenceAt(float ordinal);

// The No-flash rule on blending: the modes that invert where the two pictures move against each other
// (Difference, Exclusion) are held to the flash depth, VisualSafety's kMaxFlashDepth; every other mode,
// Add under its shoulder included, reaches full opacity.
float opacityCap(Blend mode);

// How the plan names Layer 2's Style: FNV-1a over the id's bytes, folded to 24 bits so the plan's
// float holds it exactly. Never kNoStyle. StyleHash in :feature:customization:core gives the same.
constexpr uint32_t kNoStyle = 0;
uint32_t styleHash(std::string_view id);

// What the Customization asks of Layer 2.
struct Spec {
    uint32_t style = kNoStyle;
    Blend blend = Blend::Normal;
    Influence influence = Influence::None;

    bool operator==(const Spec&) const = default;
};

// One frame's request: the Spec, and the opacity, influence amount and swap (0..1) the Customization
// evaluated this frame. `cooling` says Layer 2 is being let go because the phone is at its hottest:
// it then fades out over kCoolingFadeSeconds rather than kFadeSeconds.
struct Request {
    Spec spec;
    float opacity = 0.0f;
    float amount = 0.0f;
    float swap = 0.0f;
    bool cooling = false;
};

// What the composite draws this frame. `presence` 0..1 is how far Layer 2 is faded in: the composite is
// mix(Layer 1, layered, presence). `opacity` is already held to the mode's cap.
struct Mix {
    Spec spec;
    float presence = 0.0f;
    float opacity = 0.0f;
    float amount = 0.0f;
    float swap = 0.0f;

    bool draws() const { return spec.style != kNoStyle && presence > 0.0f; }
};

// A change of Style, blend or influence never cuts: Layer 2 fades out over kFadeSeconds, changes, and
// fades back in (turning it on or off is the same fade). Opacity, influence amount and swap never move
// faster than a full swing over kSlewSeconds whatever drives them, so no loop or Reaction can strobe
// them (the No-flash rule). Both count real time, so they are the same at any frame rate.
constexpr float kFadeSeconds = 0.35f;
constexpr float kCoolingFadeSeconds = 1.2f;
constexpr float kSlewSeconds = 0.5f;
// The longest frame stepped over: a stall does not jump a fade to its end.
constexpr float kMaxStepSeconds = 0.1f;

class Mixer {
public:
    // Advances by `dt` seconds towards `request`. The first step after construction or reset() takes
    // the request at once, so a Render, which starts a fresh renderer, has Layer 2 from its first frame.
    const Mix& step(const Request& request, float dt);
    const Mix& current() const { return out_; }
    void reset();

private:
    Mix out_;
    bool primed_ = false;
};

}  // namespace geode::viz::layer
