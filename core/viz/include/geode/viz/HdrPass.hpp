#pragma once
#include <GLES3/gl3.h>

#include <array>
#include <string>

#include "geode/render/Framebuffer.hpp"
#include "geode/render/Program.hpp"
#include "geode/render/ShaderSource.hpp"
#include "geode/viz/LensFx.hpp"
#include "geode/viz/ToneMap.hpp"

namespace geode::viz {

// The end of the HDR frame graph (ToneMap.hpp). The composite draws into this pass's HDR target
// instead of the output; resolve() gathers the light above a soft threshold into a dual-filter mip
// chain (bloom_down_frag.glsl, then bloom_up_frag.glsl added back up the chain), and draws the
// composite plus that bloom through the highlight roll-off and the blue-noise dither into the 8-bit
// target the No-flash limiter measures (tonemap_frag.glsl). It runs only where its targets are
// RGBA16F (the device renders and blends them); elsewhere the frame graph stays 8-bit as before.
//
// The lens effects' glare (LensFx.hpp) rides on the same chain: while it is on, the chain gathers the
// light above the lower of the lens's and the bloom's thresholds (the bloom's own share in alpha), and
// between the chain's way down and its way up lens_frag.glsl draws the flare ghosts, god rays and
// streaks from its quarter-size level into a target of their own, which the tonemap adds as light.
class HdrPass {
public:
    ~HdrPass() { release(); }

    // Builds the programs and textures. Call release() first on a context that held them before.
    bool create(const ShaderSource& assets, ProgramBinaryCache* cache, const std::string& fadeVert, std::string* error);
    void release();
    // The format of the composite and bloom targets, from the next allocation on.
    void setTargetFormat(Framebuffer::Format format);
    // Makes the composite target at the frame size (tile::frameSize). False when it cannot be made in RGBA16F (no
    // program, no memory, or no half-float targets here); the composite then draws straight into the
    // final target, dithered there.
    bool ensureComposite(int width, int height);
    GLuint compositeFbo() const { return composite_.fbo(); }
    GLuint compositeTex() const { return composite_.tex(); }
    // Blooms and tonemaps `source` (the composite, or the scene target itself when the composite
    // would not change it; TileFrame.hpp) into `targetFbo`, drawn at targetWidth x targetHeight and
    // left bound, with the lens effects' glare when it is on (it needs the bloom chain, so it is off
    // while bloom is). The bloom chain, and so the glare, follow the size ensureComposite() was given.
    void resolve(const tone::BloomSettings& bloom, GLuint source, GLuint targetFbo, int targetWidth, int targetHeight,
                 GLuint quadVao, const lens::Glare& glare = {});
    // The bloom chain's down filter without its threshold, run down `count` targets: the first drawn from
    // `source` with its taps a texel of sourceWidth x sourceHeight apart, each next one from the one
    // before. A shader Style's blur levels are made with it (StyleInputs.hpp), so they blur exactly as
    // the bloom chain does. Leaves the last target bound; false when the pass has no program.
    bool downsample(GLuint source, int sourceWidth, int sourceHeight, Framebuffer* const* targets, int count, GLuint quadVao);

private:
    // Draws the down filter into each of `count` targets in turn (see downsample), the first through the
    // threshold (uCurve: curveThreshold and knee; uBloomThreshold: the bloom's own share) when `prefilter`.
    void drawDown(GLuint source, int sourceWidth, int sourceHeight, Framebuffer* const* targets, int count, bool prefilter,
                  float curveThreshold, float bloomThreshold, float knee);
    // Draws the bloom chain, and the glare between its way down and its way up; returns how many
    // levels it holds (0 when there is none this frame).
    int drawBloom(const tone::BloomSettings& bloom, const lens::Glare& glare, GLuint source, GLuint quadVao);
    // Draws the glare from the chain's quarter-size level into lens_; false when it cannot.
    bool drawGlare(const lens::Glare& glare, int levels);
    static GLuint createBlackTexture();

    Framebuffer::Format format_ = Framebuffer::Format::Rgba8;
    Framebuffer composite_{"hdrComposite"};
    std::array<Framebuffer, tone::kMaxLevels> levels_{Framebuffer("bloom0"), Framebuffer("bloom1"), Framebuffer("bloom2"),
                                                      Framebuffer("bloom3"), Framebuffer("bloom4"), Framebuffer("bloom5")};
    Framebuffer lens_{"lens"};
    GLuint downProgram_ = 0;
    GLuint upProgram_ = 0;
    GLuint toneProgram_ = 0;
    GLuint lensProgram_ = 0;
    UniformCache downUniforms_;
    UniformCache upUniforms_;
    UniformCache toneUniforms_;
    UniformCache lensUniforms_;
    // Whether lens_ holds this frame's glare.
    bool glareDrawn_ = false;
    GLuint noiseTex_ = 0;
    GLuint blackTex_ = 0;
    int width_ = 0;
    int height_ = 0;
};

}  // namespace geode::viz
