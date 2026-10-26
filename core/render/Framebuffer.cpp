#include "geode/render/Framebuffer.hpp"

namespace geode::viz {

namespace {
constexpr GLenum kColorAttachments[2] = {GL_COLOR_ATTACHMENT0, GL_COLOR_ATTACHMENT1};
}

bool Framebuffer::allocate(int w, int h, Format format, GLuint* tex, GLuint* fbo) {
    glGenTextures(1, tex);
    glBindTexture(GL_TEXTURE_2D, *tex);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    if (format == Format::Rgba16F) {
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA16F, w, h, 0, GL_RGBA, GL_HALF_FLOAT, nullptr);
    } else if (format == Format::Rgb10A2) {
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGB10_A2, w, h, 0, GL_RGBA, GL_UNSIGNED_INT_2_10_10_10_REV, nullptr);
    } else {
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
    }
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
    if (allocate(w, h, format_, &tex_, &fbo_)) {
        allocated_ = format_;
    } else if (format_ != Format::Rgba8 && allocate(w, h, Format::Rgba8, &tex_, &fbo_)) {
        // Not colour-renderable here after all: RGBA8 from now on, so a resize keeps the picture.
        format_ = Format::Rgba8;
        allocated_ = Format::Rgba8;
    } else {
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
    // A new format cannot be blitted into from the old one, so it starts afresh.
    if (!ok() || allocated_ != format_) return ensure(w, h);
    GLuint tex = 0;
    GLuint fbo = 0;
    if (!allocate(w, h, allocated_, &tex, &fbo)) {
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

void Framebuffer::discardBound(GLuint fbo) {
    // The window names its colour buffer GL_COLOR; a framebuffer object names its attachment.
    const GLenum attachment = fbo == 0 ? GL_COLOR : GL_COLOR_ATTACHMENT0;
    glInvalidateFramebuffer(GL_FRAMEBUFFER, 1, &attachment);
}

}  // namespace geode::viz
