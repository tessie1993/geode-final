#include "geode/viz/SharedFxPass.hpp"

#include <utility>

namespace geode::viz {

bool SharedFxPass::create(const ShaderSource& assets, ProgramBinaryCache* cache, const std::string& fadeVert, std::string* error) {
    release();
    const auto frag = assets.load("shared_fx_frag.glsl", error);
    if (!frag) return false;
    program_ = program::build(fadeVert, *frag, cache, error);
    if (program_ == 0) return false;
    uniforms_ = UniformCache(program_);
    return true;
}

void SharedFxPass::release() {
    if (program_ != 0) glDeleteProgram(program_);
    program_ = 0;
    uniforms_ = UniformCache(0);
    frameA_.release();
    frameB_.release();
    historyValid_ = false;
}

GLuint SharedFxPass::apply(const SharedEffects& fx, GLuint sceneTex, int width, int height, float dt, float timeSeconds,
                           GLuint quadVao) {
    if (program_ == 0) return sceneTex;
    // The history is on screen in the trails, so a resize keeps its picture; the output is redrawn whole.
    if (!out_->ensure(width, height)) return sceneTex;
    if (!history_->resizePreserving(width, height) || history_->width() != width || history_->height() != height) return sceneTex;
    glBindFramebuffer(GL_FRAMEBUFFER, out_->fbo());
    out_->discardContents();
    glViewport(0, 0, width, height);
    glDisable(GL_BLEND);
    glUseProgram(program_);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, sceneTex);
    glUniform1i(uniforms_.loc("uScene"), 0);
    glActiveTexture(GL_TEXTURE1);
    glBindTexture(GL_TEXTURE_2D, history_->tex());
    glUniform1i(uniforms_.loc("uHistory"), 1);
    glUniform2f(uniforms_.loc("uTexel"), 1.0f / static_cast<float>(width), 1.0f / static_cast<float>(height));
    glUniform1f(uniforms_.loc("uAspect"), static_cast<float>(width) / static_cast<float>(height));
    glUniform1f(uniforms_.loc("uTime"), timeSeconds);
    glUniform1f(uniforms_.loc("uSoftFocus"), fx.softFocus);
    glUniform1f(uniforms_.loc("uParticles"), fx.particles);
    glUniform1f(uniforms_.loc("uParticleKind"), static_cast<float>(fx.particleKind));
    glUniform1f(uniforms_.loc("uParticleSize"), fx.particleSize);
    glUniform1f(uniforms_.loc("uEchoKeep"), historyValid_ ? sharedfx::echoKeep(fx.trails, dt) : 0.0f);
    glUniform1f(uniforms_.loc("uEchoZoom"), sharedfx::echoZoom(fx.trailZoom, dt));
    glUniform1f(uniforms_.loc("uEchoWarp"), sharedfx::echoWarp(fx.trailWarp, dt));
    glUniform1f(uniforms_.loc("uMotionKeep"), historyValid_ ? sharedfx::motionKeep(fx.motionBlur, dt) : 0.0f);
    glBindVertexArray(quadVao);
    glDrawArrays(GL_TRIANGLES, 0, 3);
    glBindVertexArray(0);
    glActiveTexture(GL_TEXTURE0);
    const GLuint drawn = out_->tex();
    std::swap(out_, history_);
    historyValid_ = true;
    return drawn;
}

}  // namespace geode::viz
