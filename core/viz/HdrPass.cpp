#include "geode/viz/HdrPass.hpp"

#include <initializer_list>

#include "BlueNoise.hpp"

namespace geode::viz {

static_assert(tone::kMaxLevels == 6, "HdrPass names one target per bloom level");

bool HdrPass::create(const ShaderSource& assets, ProgramBinaryCache* cache, const std::string& fadeVert, std::string* error) {
    const auto downFrag = assets.load("bloom_down_frag.glsl", error);
    const auto upFrag = assets.load("bloom_up_frag.glsl", error);
    const auto toneFrag = assets.load("tonemap_frag.glsl", error);
    if (!downFrag || !upFrag || !toneFrag) return false;
    downProgram_ = program::build(fadeVert, *downFrag, cache, error);
    if (downProgram_ == 0) return false;
    upProgram_ = program::build(fadeVert, *upFrag, cache, error);
    if (upProgram_ == 0) return false;
    toneProgram_ = program::build(fadeVert, *toneFrag, cache, error);
    if (toneProgram_ == 0) return false;
    downUniforms_ = UniformCache(downProgram_);
    upUniforms_ = UniformCache(upProgram_);
    toneUniforms_ = UniformCache(toneProgram_);
    noiseTex_ = blue_noise::createTexture(assets);
    blackTex_ = createBlackTexture();
    return true;
}

void HdrPass::release() {
    for (GLuint* program : {&downProgram_, &upProgram_, &toneProgram_}) {
        if (*program != 0) glDeleteProgram(*program);
        *program = 0;
    }
    downUniforms_ = UniformCache(0);
    upUniforms_ = UniformCache(0);
    toneUniforms_ = UniformCache(0);
    for (GLuint* tex : {&noiseTex_, &blackTex_}) {
        if (*tex != 0) glDeleteTextures(1, tex);
        *tex = 0;
    }
    composite_.release();
    for (Framebuffer& level : levels_) level.release();
    width_ = 0;
    height_ = 0;
}

void HdrPass::setTargetFormat(Framebuffer::Format format) {
    format_ = format;
    composite_.setFormat(format);
    for (Framebuffer& level : levels_) level.setFormat(format);
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

int HdrPass::drawBloom(const tone::BloomSettings& bloom, GLuint quadVao) {
    std::array<tone::LevelSize, tone::kMaxLevels> sizes{};
    const int count = tone::bloomLevels(width_, height_, sizes);
    for (int i = 0; i < count; ++i) {
        const tone::LevelSize& size = sizes[static_cast<size_t>(i)];
        if (!levels_[static_cast<size_t>(i)].ensure(size.width, size.height)) return 0;
    }
    if (count == 0) return 0;
    glDisable(GL_BLEND);
    glBindVertexArray(quadVao);
    glActiveTexture(GL_TEXTURE0);
    // Down the chain: the composite's light above the threshold into level 0, then each level halved.
    glUseProgram(downProgram_);
    glUniform1i(downUniforms_.loc("uSource"), 0);
    glUniform2f(downUniforms_.loc("uCurve"), bloom.threshold, bloom.knee);
    GLuint source = composite_.tex();
    int sourceWidth = width_;
    int sourceHeight = height_;
    for (int i = 0; i < count; ++i) {
        const Framebuffer& level = levels_[static_cast<size_t>(i)];
        glBindFramebuffer(GL_FRAMEBUFFER, level.fbo());
        glViewport(0, 0, level.width(), level.height());
        glBindTexture(GL_TEXTURE_2D, source);
        glUniform1i(downUniforms_.loc("uPrefilter"), i == 0 ? 1 : 0);
        glUniform2f(downUniforms_.loc("uTexel"), 1.0f / static_cast<float>(sourceWidth), 1.0f / static_cast<float>(sourceHeight));
        glDrawArrays(GL_TRIANGLES, 0, 3);
        source = level.tex();
        sourceWidth = level.width();
        sourceHeight = level.height();
    }
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

void HdrPass::resolve(const tone::BloomSettings& bloom, GLuint targetFbo, GLuint quadVao) {
    const int levels = bloom.on && bloom.strength > 0.0f ? drawBloom(bloom, quadVao) : 0;
    glBindFramebuffer(GL_FRAMEBUFFER, targetFbo);
    glViewport(0, 0, width_, height_);
    glDisable(GL_BLEND);
    glUseProgram(toneProgram_);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, composite_.tex());
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
