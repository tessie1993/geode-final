#include "SharedParams.hpp"

#include <cmath>

#include "geode/viz/VisualSafety.hpp"

namespace geode::viz {

namespace {
bool on(float value) { return value >= 0.5f; }
int whole(float value) { return static_cast<int>(std::lround(value)); }
}  // namespace

void applySharedCore(SceneParams& p, const mod::NativeFrame& f) {
    using mod::Shared;
    p.zoom = f[Shared::Zoom];
    p.rotation = f[Shared::Rotation];
    p.sway = f[Shared::Sway];
    p.shake = 0.0f;
    p.driftX = f[Shared::DriftX];
    p.driftY = f[Shared::DriftY];
    p.endlessZoom = on(f[Shared::EndlessZoom]);
    p.endlessZoomSpeed = f[Shared::DiveSpeed];

    p.palette = whole(f[Shared::Palette]);
    p.palette2 = whole(f[Shared::Palette2]);
    p.paletteMix = f[Shared::PaletteMix];
    // A plan's palettes are whole palettes: no custom base or range left over from the scene parameters.
    p.paletteBaseOverride = SceneParams::kUnsetOverride;
    p.paletteRangeOverride = SceneParams::kUnsetOverride;
    p.palette2BaseOverride = SceneParams::kUnsetOverride;
    p.palette2RangeOverride = SceneParams::kUnsetOverride;
    p.duotone = on(f[Shared::Duotone]);
    p.paletteLut = whole(f[Shared::Lut]);
    p.morph = f[Shared::Morph];
    p.colorShift = f[Shared::HueShift];
    p.hueRange = f[Shared::HueRange];
    p.colorCycle = on(f[Shared::ColourCycle]);
    p.cycleSpeed = f[Shared::CycleSpeed];

    p.speed = f[Shared::Speed];
    p.audioDrive = f[Shared::AudioDrive];
    p.turbulence = f[Shared::Turbulence];
    p.marchDetail = f[Shared::Detail];
    // GLOW's whole range is live: it spans what the safety clamp lets bloom reach, so its top is the
    // cap rather than three quarters of the slider doing nothing past it.
    p.bloom = f[Shared::Glow] * safety::kMaxFlashDepth;

    p.kaleidoscope = on(f[Shared::Kaleidoscope]);
    p.symmetry = whole(f[Shared::Symmetry]);
    p.mirror = on(f[Shared::Mirror]);

    p.grain = f[Shared::Grain];
    p.vignette = f[Shared::Vignette];
    p.chromaAb = f[Shared::Chromatic];
    p.scanlines = f[Shared::Scanlines];
    p.fisheye = f[Shared::Fisheye];

    p.warp = f[Shared::Warp];
    p.ripple = f[Shared::Ripple];
    p.twist = f[Shared::Twist];
    p.tile = f[Shared::Tile];

    p.glitch = f[Shared::Glitch];
    p.pixelate = f[Shared::Pixelate];
    p.posterize = f[Shared::Posterize];
    p.solarize = on(f[Shared::Solarize]);

    p.saturation = f[Shared::Saturation];
    p.brightness = f[Shared::Brightness];
    p.intensity = f[Shared::Intensity];
    p.contrast = f[Shared::Contrast];
    p.gamma = f[Shared::Gamma];
    p.temperature = f[Shared::Temperature];
    p.invert = on(f[Shared::Invert]);
}

}  // namespace geode::viz
