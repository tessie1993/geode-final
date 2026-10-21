#include "geode/render/Framebuffer.hpp"

namespace geode::viz {

namespace {
constexpr GLenum kColorAttachments[2] = {GL_COLOR_ATTACHMENT0, GL_COLOR_ATTACHMENT1};
}

bool Framebuffer::allocate(int w, int h, GLuint* tex, GLuint* fbo) {
    glGenTextures(1, tex);
    glBindTexture(GL_TEXTURE_2D, *tex);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
    glGenFramebuffers(1, fbo);
    glBindFramebuffer(GL_FRAMEBUFFER, *fbo);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, *tex, 0);
    if (glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE) return true;
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    glDeleteFramebuffers(1, fbo);
    glDeleteTextures(1, tex);
    *fbo = 0;
    *tex = 0;
    return false;
}

bool Framebuffer::ensure(int w, int h) {
    if (w <= 0 || h <= 0 || alreadyAt(w, h)) return ok();
    release();
    if (!allocate(w, h, &tex_, &fbo_)) {
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        return false;
    }
    glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
    glClear(GL_COLOR_BUFFER_BIT);
    width_ = w;
    height_ = h;
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    return true;
}

bool Framebuffer::resizePreserving(int w, int h) {
    if (w <= 0 || h <= 0 || alreadyAt(w, h)) return ok();
    if (!ok()) return ensure(w, h);
    GLuint tex = 0;
    GLuint fbo = 0;
    if (!allocate(w, h, &tex, &fbo)) {
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        return true;  // the old target, and its picture, stay
    }
    glBindFramebuffer(GL_READ_FRAMEBUFFER, fbo_);
    glBindFramebuffer(GL_DRAW_FRAMEBUFFER, fbo);
    glBlitFramebuffer(0, 0, width_, height_, 0, 0, w, h, GL_COLOR_BUFFER_BIT, GL_LINEAR);
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    release();
    tex_ = tex;
    fbo_ = fbo;
    width_ = w;
    height_ = h;
    return true;
}

void Framebuffer::release() {
    if (fbo_ != 0) glDeleteFramebuffers(1, &fbo_);
    if (tex_ != 0) glDeleteTextures(1, &tex_);
    forget();
}

void Framebuffer::forget() {
    fbo_ = 0;
    tex_ = 0;
    width_ = 0;
    height_ = 0;
}

void Framebuffer::discardContents(GLenum bindTarget) const {
    if (fbo_ == 0) return;
    discardColorAttachments(bindTarget, 1);
}

void Framebuffer::discardColorAttachments(GLenum bindTarget, int count) {
    glInvalidateFramebuffer(bindTarget, count, kColorAttachments);
}

}  // namespace geode::viz
