#include "geode/viz/HdrPass.hpp"

#include <initializer_list>

#include "BlueNoise.hpp"

namespace geode::viz {

static_assert(tone::kMaxLevels == 6, "HdrPass names one target per bloom level");

bool HdrPass::create(const ShaderSource& assets, ProgramBinaryCache* cache, const std::string& fadeVert, std::string* error) {
    const auto downFrag = assets.load("bloom_down_frag.glsl", error);
    const auto upFrag = assets.load("bloom_up_frag.glsl", error);
    const auto toneFrag = assets.load("tonemap_frag.glsl", error);
    const auto lensFrag = assets.load("lens_frag.glsl", error);
    if (!downFrag || !upFrag || !toneFrag || !lensFrag) return false;
    downProgram_ = program::build(fadeVert, *downFrag, cache, error);
    if (downProgram_ == 0) return false;
    upProgram_ = program::build(fadeVert, *upFrag, cache, error);
    if (upProgram_ == 0) return false;
    toneProgram_ = program::build(fadeVert, *toneFrag, cache, error);
    if (toneProgram_ == 0) return false;
    lensProgram_ = program::build(fadeVert, *lensFrag, cache, error);
    if (lensProgram_ == 0) return false;
    downUniforms_ = UniformCache(downProgram_);
    upUniforms_ = UniformCache(upProgram_);
    toneUniforms_ = UniformCache(toneProgram_);
    lensUniforms_ = UniformCache(lensProgram_);
    noiseTex_ = blue_noise::createTexture(assets);
    blackTex_ = createBlackTexture();
    return true;
}

void HdrPass::release() {
    for (GLuint* program : {&downProgram_, &upProgram_, &toneProgram_, &lensProgram_}) {
        if (*program != 0) glDeleteProgram(*program);
        *program = 0;
    }
    downUniforms_ = UniformCache(0);
    upUniforms_ = UniformCache(0);
    toneUniforms_ = UniformCache(0);
    lensUniforms_ = UniformCache(0);
    for (GLuint* tex : {&noiseTex_, &blackTex_}) {
        if (*tex != 0) glDeleteTextures(1, tex);
        *tex = 0;
    }
    composite_.release();
    for (Framebuffer& level : levels_) level.release();
    lens_.release();
    glareDrawn_ = false;
    width_ = 0;
    height_ = 0;
}

void HdrPass::setTargetFormat(Framebuffer::Format format) {
    format_ = format;
    composite_.setFormat(format);
    for (Framebuffer& level : levels_) level.setFormat(format);
    lens_.setFormat(format);
}

bool HdrPass::ensureComposite(int width, int height) {
    if (downProgram_ == 0 || upProgram_ == 0 || toneProgram_ == 0) return false;
    // On RGBA8 there is no headroom to bloom or roll off, and the curve would only dim every Style's
    // white: such a device keeps the 8-bit frame graph, the composite dithering the final picture.
    if (format_ != Framebuffer::Format::Rgba16F || !composite_.ensure(width, height)) return false;
    if (composite_.format() != Framebuffer::Format::Rgba16F) {
        format_ = Framebuffer::Format::Rgba8;
        composite_.release();
        return false;
    }
    width_ = width;
    height_ = height;
    return true;
}

int HdrPass::drawBloom(const tone::BloomSettings& bloom, const lens::Glare& glare, GLuint source, GLuint quadVao) {
    std::array<tone::LevelSize, tone::kMaxLevels> sizes{};
    const int count = tone::bloomLevels(width_, height_, sizes);
    for (int i = 0; i < count; ++i) {
        const tone::LevelSize& size = sizes[static_cast<size_t>(i)];
        if (!levels_[static_cast<size_t>(i)].ensure(size.width, size.height)) return 0;
    }
    if (count == 0) return 0;
    glBindVertexArray(quadVao);
    // Down the chain: the composite's light above the threshold into level 0, then each level halved.
    // While the glare is on the chain gathers from the lens's threshold too, the bloom's share in alpha.
    // The first level's taps are a frame texel apart whatever the source's own size, so a skipped
    // composite (the supersampled scene target read directly) blurs exactly as the composite would.
    std::array<Framebuffer*, tone::kMaxLevels> targets{};
    for (int i = 0; i < count; ++i) targets[static_cast<size_t>(i)] = &levels_[static_cast<size_t>(i)];
    drawDown(source, width_, height_, targets.data(), count, true, lens::chainThreshold(bloom.threshold, glare), bloom.threshold,
             bloom.knee);
    // The glare reads the quarter-size level before the way up adds the wider ones onto it.
    glareDrawn_ = glare.on() && drawGlare(glare, count);
    // Back up: each smaller level added onto the one above it, so level 0 gathers them all.
    glUseProgram(upProgram_);
    glUniform1i(upUniforms_.loc("uSource"), 0);
    glEnable(GL_BLEND);
    glBlendFunc(GL_ONE, GL_ONE);
    for (int i = count - 2; i >= 0; --i) {
        const Framebuffer& smaller = levels_[static_cast<size_t>(i + 1)];
        const Framebuffer& level = levels_[static_cast<size_t>(i)];
        glBindFramebuffer(GL_FRAMEBUFFER, level.fbo());
        glViewport(0, 0, level.width(), level.height());
        glBindTexture(GL_TEXTURE_2D, smaller.tex());
        glUniform2f(upUniforms_.loc("uTexel"), 1.0f / static_cast<float>(smaller.width()), 1.0f / static_cast<float>(smaller.height()));
        glDrawArrays(GL_TRIANGLES, 0, 3);
    }
    glDisable(GL_BLEND);
    glBindVertexArray(0);
    return count;
}

void HdrPass::drawDown(GLuint source, int sourceWidth, int sourceHeight, Framebuffer* const* targets, int count, bool prefilter,
                       float curveThreshold, float bloomThreshold, float knee) {
    glDisable(GL_BLEND);
    glActiveTexture(GL_TEXTURE0);
    glUseProgram(downProgram_);
    glUniform1i(downUniforms_.loc("uSource"), 0);
    glUniform2f(downUniforms_.loc("uCurve"), curveThreshold, knee);
    glUniform1f(downUniforms_.loc("uBloomThreshold"), bloomThreshold);
    for (int i = 0; i < count; ++i) {
        const Framebuffer& level = *targets[i];
        glBindFramebuffer(GL_FRAMEBUFFER, level.fbo());
        level.discardContents();
        glViewport(0, 0, level.width(), level.height());
        glBindTexture(GL_TEXTURE_2D, source);
        glUniform1i(downUniforms_.loc("uPrefilter"), prefilter && i == 0 ? 1 : 0);
        glUniform2f(downUniforms_.loc("uTexel"), 1.0f / static_cast<float>(sourceWidth), 1.0f / static_cast<float>(sourceHeight));
        glDrawArrays(GL_TRIANGLES, 0, 3);
        source = level.tex();
        sourceWidth = level.width();
        sourceHeight = level.height();
    }
}

bool HdrPass::downsample(GLuint source, int sourceWidth, int sourceHeight, Framebuffer* const* targets, int count, GLuint quadVao) {
    if (downProgram_ == 0 || sourceWidth <= 0 || sourceHeight <= 0) return false;
    if (count <= 0) return true;
    glBindVertexArray(quadVao);
    const tone::BloomSettings plain;
    drawDown(source, sourceWidth, sourceHeight, targets, count, false, plain.threshold, plain.threshold, plain.knee);
    glBindVertexArray(0);
    return true;
}

bool HdrPass::drawGlare(const lens::Glare& glare, int levels) {
    if (lensProgram_ == 0 || levels < 2) return false;
    const Framebuffer& light = levels_[1];
    const Framebuffer& soft = levels_[levels > 2 ? 2 : 1];
    if (!lens_.ensure(light.width(), light.height())) return false;
    glBindFramebuffer(GL_FRAMEBUFFER, lens_.fbo());
    lens_.discardContents();
    glViewport(0, 0, lens_.width(), lens_.height());
    glDisable(GL_BLEND);
    glUseProgram(lensProgram_);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, light.tex());
    glUniform1i(lensUniforms_.loc("uLight"), 0);
    glActiveTexture(GL_TEXTURE1);
    glBindTexture(GL_TEXTURE_2D, soft.tex());
    glUniform1i(lensUniforms_.loc("uLightSoft"), 1);
    glUniform1f(lensUniforms_.loc("uAspect"), static_cast<float>(width_) / static_cast<float>(height_));
    glUniform1f(lensUniforms_.loc("uFlare"), glare.flare);
    glUniform1f(lensUniforms_.loc("uFlareGhosts"), static_cast<float>(glare.ghosts));
    glUniform1f(lensUniforms_.loc("uFlareHalo"), glare.halo);
    glUniform1f(lensUniforms_.loc("uFlareDispersion"), glare.dispersion);
    glUniform1f(lensUniforms_.loc("uRays"), glare.rays);
    glUniform2f(lensUniforms_.loc("uRaySource"), glare.rayX, glare.rayY);
    glUniform1f(lensUniforms_.loc("uRayLength"), glare.rayLength);
    glUniform1f(lensUniforms_.loc("uRayDecay"), glare.rayDecay);
    glUniform1f(lensUniforms_.loc("uRaySamples"), static_cast<float>(glare.raySamples));
    glUniform1f(lensUniforms_.loc("uStreaks"), glare.streakSamples > 0 ? glare.streaks : 0.0f);
    glUniform1f(lensUniforms_.loc("uStreakShape"), static_cast<float>(static_cast<int>(glare.streakShape)));
    glUniform1f(lensUniforms_.loc("uStreakSpokes"), static_cast<float>(glare.spokes));
    glUniform1f(lensUniforms_.loc("uStreakLength"), glare.streakLength);
    glUniform1f(lensUniforms_.loc("uStreakSamples"), static_cast<float>(glare.streakSamples));
    glDrawArrays(GL_TRIANGLES, 0, 3);
    glActiveTexture(GL_TEXTURE0);
    return true;
}

void HdrPass::resolve(const tone::BloomSettings& bloom, GLuint source, GLuint targetFbo, int targetWidth, int targetHeight,
                      GLuint quadVao, const lens::Glare& glare) {
    glareDrawn_ = false;
    const int levels = bloom.on && bloom.strength > 0.0f ? drawBloom(bloom, glare, source, quadVao) : 0;
    glBindFramebuffer(GL_FRAMEBUFFER, targetFbo);
    Framebuffer::discardBound(targetFbo);
    glViewport(0, 0, targetWidth, targetHeight);
    glDisable(GL_BLEND);
    glUseProgram(toneProgram_);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, source);
    glUniform1i(toneUniforms_.loc("uScene"), 0);
    glActiveTexture(GL_TEXTURE1);
    glBindTexture(GL_TEXTURE_2D, levels > 0 ? levels_[0].tex() : blackTex_);
    glUniform1i(toneUniforms_.loc("uBloom"), 1);
    // Level 0 holds the sum of every level, so the strength is shared out between them.
    glUniform1f(toneUniforms_.loc("uBloomStrength"), levels > 0 ? bloom.strength / static_cast<float>(levels) : 0.0f);
    glUniform1f(toneUniforms_.loc("uCeiling"), tone::kShoulderCeiling);
    glActiveTexture(GL_TEXTURE2);
    glBindTexture(GL_TEXTURE_2D, noiseTex_);
    glUniform1i(toneUniforms_.loc("uNoise"), 2);
    glUniform1f(toneUniforms_.loc("uDither"), noiseTex_ != 0 ? blue_noise::kDitherAmount : 0.0f);
    glActiveTexture(GL_TEXTURE3);
    glBindTexture(GL_TEXTURE_2D, glareDrawn_ ? lens_.tex() : blackTex_);
    glUniform1i(toneUniforms_.loc("uLens"), 3);
    glUniform1f(toneUniforms_.loc("uLensStrength"), glareDrawn_ ? 1.0f : 0.0f);
    glBindVertexArray(quadVao);
    glDrawArrays(GL_TRIANGLES, 0, 3);
    glBindVertexArray(0);
    glActiveTexture(GL_TEXTURE0);
}

GLuint HdrPass::createBlackTexture() {
    GLuint tex = 0;
    glGenTextures(1, &tex);
    glBindTexture(GL_TEXTURE_2D, tex);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    const unsigned char black[4] = {0, 0, 0, 255};
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, 1, 1, 0, GL_RGBA, GL_UNSIGNED_BYTE, black);
    return tex;
}

}  // namespace geode::viz
