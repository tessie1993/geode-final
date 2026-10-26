#include "geode/viz/NoFlashPass.hpp"

#include <cstring>
#include <initializer_list>
#include <utility>

namespace geode::viz {

namespace {
// How long a measurement may take to arrive before the frame is held instead. A frame's
// measurement is issued a whole frame before it is read, so it has normally arrived long since;
// the synchronous path waits for its own, which is still far below this.
constexpr GLuint64 kFenceTimeoutNs = 1000000000ull;
}  // namespace

bool NoFlashPass::create(const ShaderSource& assets, ProgramBinaryCache* cache, const std::string& fadeVert, std::string* error) {
    // Buffers and fences of a lost context are gone with it; their names must not be deleted in the new one.
    for (Slot* slot : {&slotA_, &slotB_, &slotC_, &slotD_}) {
        slot->pbo = 0;
        slot->fence = nullptr;
    }
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
    for (Slot* slot : {&slotA_, &slotB_, &slotC_, &slotD_}) {
        dropFence(*slot);
        if (slot->pbo != 0) glDeleteBuffers(1, &slot->pbo);
        slot->pbo = 0;
        slot->frame.release();
    }
    grid_.release();
    width_ = 0;
    height_ = 0;
    limiter_.reset();
    schedule_.reset();
    lastAmount_ = 1.0f;
}

bool NoFlashPass::ensureSlot(Slot& slot, int width, int height) {
    if (!slot.frame.ensure(width, height)) return false;
    if (slot.pbo != 0) return true;
    glGenBuffers(1, &slot.pbo);
    glBindBuffer(GL_PIXEL_PACK_BUFFER, slot.pbo);
    glBufferData(GL_PIXEL_PACK_BUFFER, noflash::kReadbackBytes, nullptr, GL_STREAM_READ);
    glBindBuffer(GL_PIXEL_PACK_BUFFER, 0);
    return slot.pbo != 0;
}

bool NoFlashPass::bindCandidate(int width, int height) {
    if (measureProgram_ == 0 || mixProgram_ == 0) return false;
    const bool ready = ensureSlot(slotA_, width, height) && ensureSlot(slotB_, width, height) && ensureSlot(slotC_, width, height) &&
                       ensureSlot(slotD_, width, height) && grid_.ensure(noflash::kGridColumns, noflash::kGridRows);
    if (!ready) return false;
    // New targets hold nothing that was shown or is pending, so the limiter starts again from the next frame.
    if (width != width_ || height != height_) {
        limiter_.reset();
        schedule_.reset();
        for (Slot* slot : {&slotA_, &slotB_, &slotC_, &slotD_}) dropFence(*slot);
    }
    width_ = width;
    height_ = height;
    glBindFramebuffer(GL_FRAMEBUFFER, candidate_->frame.fbo());
    glViewport(0, 0, width_, height_);
    return true;
}

void NoFlashPass::measure(Slot& slot, GLuint quadVao) {
    glBindFramebuffer(GL_FRAMEBUFFER, grid_.fbo());
    glViewport(0, 0, noflash::kGridColumns, noflash::kGridRows);
    glDisable(GL_BLEND);
    glUseProgram(measureProgram_);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, slot.frame.tex());
    glUniform1i(measureUniforms_.loc("uFrame"), 0);
    glUniform2f(measureUniforms_.loc("uGrid"), static_cast<float>(noflash::kGridColumns), static_cast<float>(noflash::kGridRows));
    glBindVertexArray(quadVao);
    glDrawArrays(GL_TRIANGLES, 0, 3);
    glBindVertexArray(0);
    // Into the slot's pixel-pack buffer: queued behind the draw, returning at once.
    glBindBuffer(GL_PIXEL_PACK_BUFFER, slot.pbo);
    glReadPixels(0, 0, noflash::kGridColumns, noflash::kGridRows, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
    glBindBuffer(GL_PIXEL_PACK_BUFFER, 0);
    dropFence(slot);
    slot.fence = glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
}

bool NoFlashPass::readMeasurement(Slot& slot) {
    if (slot.fence == nullptr) return false;
    const GLenum waited = glClientWaitSync(slot.fence, GL_SYNC_FLUSH_COMMANDS_BIT, kFenceTimeoutNs);
    dropFence(slot);
    if (waited != GL_ALREADY_SIGNALED && waited != GL_CONDITION_SATISFIED) return false;
    glBindBuffer(GL_PIXEL_PACK_BUFFER, slot.pbo);
    const void* mapped = glMapBufferRange(GL_PIXEL_PACK_BUFFER, 0, noflash::kReadbackBytes, GL_MAP_READ_BIT);
    if (mapped != nullptr) std::memcpy(readback_.data(), mapped, readback_.size());
    const bool read = mapped != nullptr && glUnmapBuffer(GL_PIXEL_PACK_BUFFER) == GL_TRUE;
    glBindBuffer(GL_PIXEL_PACK_BUFFER, 0);
    return read;
}

void NoFlashPass::dropFence(Slot& slot) {
    if (slot.fence != nullptr) glDeleteSync(slot.fence);
    slot.fence = nullptr;
}

void NoFlashPass::drawMix(GLuint fbo, GLuint shownTex, GLuint nextTex, float amount, GLuint quadVao) {
    glBindFramebuffer(GL_FRAMEBUFFER, fbo);
    glViewport(0, 0, width_, height_);
    glDisable(GL_BLEND);
    glUseProgram(mixProgram_);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, shownTex);
    glUniform1i(mixUniforms_.loc("uShown"), 0);
    glActiveTexture(GL_TEXTURE1);
    glBindTexture(GL_TEXTURE_2D, nextTex);
    glUniform1i(mixUniforms_.loc("uNext"), 1);
    glUniform1f(mixUniforms_.loc("uAmount"), amount);
    glBindVertexArray(quadVao);
    glDrawArrays(GL_TRIANGLES, 0, 3);
    glBindVertexArray(0);
    glActiveTexture(GL_TEXTURE0);
}

void NoFlashPass::show(Slot*& next, float dt, GLuint targetFbo, GLuint quadVao) {
    lastAmount_ = limiter_.step(dt, LumaGrid::fromReadback(readback_));
    if (lastAmount_ < 1.0f) {
        // The damped frame becomes the next frame's crossfade source.
        drawMix(spare_->frame.fbo(), shown_->frame.tex(), next->frame.tex(), lastAmount_, quadVao);
        drawMix(targetFbo, shown_->frame.tex(), next->frame.tex(), lastAmount_, quadVao);
        std::swap(shown_, spare_);
    } else {
        // Shown as it is: the frame itself becomes the history, with no copy.
        drawMix(targetFbo, shown_->frame.tex(), next->frame.tex(), lastAmount_, quadVao);
        std::swap(shown_, next);
    }
}

void NoFlashPass::present(float dt, GLuint targetFbo, GLuint quadVao, bool synchronous) {
    measure(*candidate_, quadVao);
    const DelayedPresent::Step step = schedule_.onCandidate(dt, synchronous);
    // A frame whose measurement cannot be read is never shown unjudged: the last frame is held.
    const GLuint heldTex = shown_->frame.tex();
    switch (step.action) {
        case DelayedPresent::Action::MeasureAndShow:
            if (step.droppedPending) dropFence(*pending_);
            if (readMeasurement(*candidate_)) {
                show(candidate_, step.dt, targetFbo, quadVao);
            } else {
                drawMix(targetFbo, heldTex, heldTex, 1.0f, quadVao);
            }
            return;
        case DelayedPresent::Action::ShowPending:
            if (readMeasurement(*pending_)) {
                show(pending_, step.dt, targetFbo, quadVao);
            } else {
                drawMix(targetFbo, heldTex, heldTex, 1.0f, quadVao);
            }
            break;
        case DelayedPresent::Action::HoldShown:
            drawMix(targetFbo, heldTex, heldTex, 1.0f, quadVao);
            break;
    }
    // This frame's candidate waits as the pending frame; the slot it replaces is free to draw into.
    std::swap(pending_, candidate_);
}

}  // namespace geode::viz
