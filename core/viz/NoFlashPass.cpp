#include "geode/viz/NoFlashPass.hpp"

#include <utility>

namespace geode::viz {

bool NoFlashPass::create(const ShaderSource& assets, ProgramBinaryCache* cache, const std::string& fadeVert, std::string* error) {
    release();
    const auto measureFrag = assets.load("noflash_measure_frag.glsl", error);
    const auto mixFrag = assets.load("noflash_mix_frag.glsl", error);
    if (!measureFrag || !mixFrag) return false;
    measureProgram_ = program::build(fadeVert, *measureFrag, cache, error);
    if (measureProgram_ == 0) return false;
    mixProgram_ = program::build(fadeVert, *mixFrag, cache, error);
    if (mixProgram_ == 0) return false;
    measureUniforms_ = UniformCache(measureProgram_);
    mixUniforms_ = UniformCache(mixProgram_);
    return true;
}

void NoFlashPass::release() {
    if (measureProgram_ != 0) glDeleteProgram(measureProgram_);
    if (mixProgram_ != 0) glDeleteProgram(mixProgram_);
    measureProgram_ = 0;
    mixProgram_ = 0;
    measureUniforms_ = UniformCache(0);
    mixUniforms_ = UniformCache(0);
    frameA_.release();
    frameB_.release();
    frameC_.release();
    grid_.release();
    width_ = 0;
    height_ = 0;
    limiter_.reset();
    lastAmount_ = 1.0f;
}

bool NoFlashPass::bindCandidate(int width, int height) {
    if (measureProgram_ == 0 || mixProgram_ == 0) return false;
    const bool ready = candidate_->ensure(width, height) && shown_->ensure(width, height) && spare_->ensure(width, height) &&
                       grid_.ensure(noflash::kGridColumns, noflash::kGridRows);
    if (!ready) return false;
    // New targets hold nothing that was shown, so the limiter starts again from the next frame.
    if (width != width_ || height != height_) limiter_.reset();
    width_ = width;
    height_ = height;
    glBindFramebuffer(GL_FRAMEBUFFER, candidate_->fbo());
    glViewport(0, 0, width_, height_);
    return true;
}

void NoFlashPass::measure(GLuint quadVao) {
    glBindFramebuffer(GL_FRAMEBUFFER, grid_.fbo());
    glViewport(0, 0, noflash::kGridColumns, noflash::kGridRows);
    glDisable(GL_BLEND);
    glUseProgram(measureProgram_);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, candidate_->tex());
    glUniform1i(measureUniforms_.loc("uFrame"), 0);
    glUniform2f(measureUniforms_.loc("uGrid"), static_cast<float>(noflash::kGridColumns), static_cast<float>(noflash::kGridRows));
    glBindVertexArray(quadVao);
    glDrawArrays(GL_TRIANGLES, 0, 3);
    glBindVertexArray(0);
    glReadPixels(0, 0, noflash::kGridColumns, noflash::kGridRows, GL_RGBA, GL_UNSIGNED_BYTE, readback_.data());
}

void NoFlashPass::drawMix(GLuint fbo, float amount, GLuint quadVao) {
    glBindFramebuffer(GL_FRAMEBUFFER, fbo);
    glViewport(0, 0, width_, height_);
    glDisable(GL_BLEND);
    glUseProgram(mixProgram_);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, shown_->tex());
    glUniform1i(mixUniforms_.loc("uShown"), 0);
    glActiveTexture(GL_TEXTURE1);
    glBindTexture(GL_TEXTURE_2D, candidate_->tex());
    glUniform1i(mixUniforms_.loc("uNext"), 1);
    glUniform1f(mixUniforms_.loc("uAmount"), amount);
    glBindVertexArray(quadVao);
    glDrawArrays(GL_TRIANGLES, 0, 3);
    glBindVertexArray(0);
    glActiveTexture(GL_TEXTURE0);
}

void NoFlashPass::present(float dt, GLuint targetFbo, GLuint quadVao) {
    measure(quadVao);
    lastAmount_ = limiter_.step(dt, LumaGrid::fromReadback(readback_));
    if (lastAmount_ < 1.0f) {
        // The damped frame becomes the next frame's crossfade source.
        drawMix(spare_->fbo(), lastAmount_, quadVao);
        drawMix(targetFbo, lastAmount_, quadVao);
        std::swap(shown_, spare_);
    } else {
        // Shown as it is: the candidate itself becomes the history, with no copy.
        drawMix(targetFbo, lastAmount_, quadVao);
        std::swap(shown_, candidate_);
    }
}

}  // namespace geode::viz
