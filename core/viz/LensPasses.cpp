#include "geode/viz/LensPasses.hpp"

#include <algorithm>

namespace geode::viz {

namespace {
// Six vertices, two triangles, per particle.
constexpr GLsizei kParticleVertices = 6;
}  // namespace

bool DofPass::create(const ShaderSource& assets, ProgramBinaryCache* cache, const std::string& fadeVert,
                     std::string* error) {
    release();
    const auto frag = assets.load("dof_frag.glsl", error);
    if (!frag) return false;
    program_ = program::build(fadeVert, *frag, cache, error);
    if (program_ == 0) return false;
    uniforms_ = UniformCache(program_);
    return true;
}

void DofPass::release() {
    if (program_ != 0) glDeleteProgram(program_);
    program_ = 0;
    uniforms_ = UniformCache(0);
    target_.release();
}

GLuint DofPass::apply(const lens::Dof& dof, GLuint sceneTex, int width, int height, GLuint quadVao) {
    if (!dof.on() || program_ == 0) {
        target_.release();
        return sceneTex;
    }
    // Redrawn whole every frame, so a resize keeps nothing.
    if (!target_.ensure(width, height)) return sceneTex;
    glBindFramebuffer(GL_FRAMEBUFFER, target_.fbo());
    glViewport(0, 0, width, height);
    glDisable(GL_BLEND);
    glUseProgram(program_);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, sceneTex);
    glUniform1i(uniforms_.loc("uScene"), 0);
    glUniform2f(uniforms_.loc("uTexel"), 1.0f / static_cast<float>(width), 1.0f / static_cast<float>(height));
    glUniform1f(uniforms_.loc("uAperture"), dof.aperture);
    glUniform1f(uniforms_.loc("uFocus"), dof.focus);
    glUniform1f(uniforms_.loc("uMaxRadius"), lens::kDofMaxRadius * static_cast<float>(height));
    glUniform1f(uniforms_.loc("uSamples"), static_cast<float>(dof.samples));
    glBindVertexArray(quadVao);
    glDrawArrays(GL_TRIANGLES, 0, 3);
    glBindVertexArray(0);
    return target_.tex();
}

bool ParticlePass::create(const ShaderSource& assets, ProgramBinaryCache* cache, std::string* error) {
    release();
    const auto vert = assets.load("particles3d_vert.glsl", error);
    const auto frag = assets.load("particles3d_frag.glsl", error);
    if (!vert || !frag) return false;
    program_ = program::build(*vert, *frag, cache, error);
    if (program_ == 0) return false;
    uniforms_ = UniformCache(program_);
    return true;
}

void ParticlePass::release() {
    if (program_ != 0) glDeleteProgram(program_);
    program_ = 0;
    uniforms_ = UniformCache(0);
}

void ParticlePass::draw(const lens::Particles& particles, const CameraPose& pose, GLuint targetFbo, int width,
                        int height, GLuint quadVao) {
    if (!particles.on() || program_ == 0 || width <= 0 || height <= 0) return;
    glBindFramebuffer(GL_FRAMEBUFFER, targetFbo);
    glViewport(0, 0, width, height);
    glEnable(GL_BLEND);
    glBlendFunc(GL_ONE, GL_ONE);
    glUseProgram(program_);
    glUniform1f(uniforms_.loc("uPhase"), particles.phase);
    glUniform1f(uniforms_.loc("uTail"), particles.tail);
    glUniform1f(uniforms_.loc("uEmission"), std::clamp(particles.emission, 0.0f, 1.0f));
    glUniform1f(uniforms_.loc("uSize"), particles.size);
    glUniform1f(uniforms_.loc("uKind"), static_cast<float>(static_cast<int>(particles.kind)));
    glUniform1f(uniforms_.loc("uAspect"), static_cast<float>(width) / static_cast<float>(height));
    glUniform1f(uniforms_.loc("uPixel"), 2.0f / static_cast<float>(height));
    glUniformMatrix3fv(uniforms_.loc("uCamRot"), 1, GL_FALSE, pose.rotation.data());
    glUniform3f(uniforms_.loc("uTint"), particles.tint[0], particles.tint[1], particles.tint[2]);
    glBindVertexArray(quadVao);
    glDrawArraysInstanced(GL_TRIANGLES, 0, kParticleVertices, std::min(particles.count, lens::kMaxParticles));
    glBindVertexArray(0);
    glDisable(GL_BLEND);
}

}  // namespace geode::viz
