#pragma once
#include <GLES3/gl3.h>

#include <string>

namespace geode::viz {

// Port of RenderTarget.kt: one RGBA8 colour texture behind one FBO, cleared to opaque black on creation.
//
// Two ways to change its size. ensure() reallocates and clears to black: for a target whose old
// contents mean nothing. resizePreserving() copies the old picture into the new size with linear
// filtering and never clears once there is a picture: for every target whose contents are on screen
// or feed the next frame (the scene and trail buffers).
class Framebuffer {
public:
    explicit Framebuffer(std::string label) : label_(std::move(label)) {}
    ~Framebuffer() { release(); }
    Framebuffer(const Framebuffer&) = delete;
    Framebuffer& operator=(const Framebuffer&) = delete;

    GLuint fbo() const { return fbo_; }
    GLuint tex() const { return tex_; }
    int width() const { return width_; }
    int height() const { return height_; }
    bool ok() const { return fbo_ != 0 && tex_ != 0; }
    const std::string& label() const { return label_; }

    bool ensure(int w, int h);
    // Resizes to w x h keeping the picture: the old texture is blitted (GL_LINEAR) into the new one
    // before it is deleted. The first allocation, with nothing to keep, is ensure()'s. If the new
    // target cannot be allocated the old one is kept at its old size, so check width()/height().
    // Leaves GL_FRAMEBUFFER bound to 0, as ensure() does.
    bool resizePreserving(int w, int h);
    void release();
    void forget();
    void discardContents(GLenum bindTarget = GL_FRAMEBUFFER) const;
    static void discardColorAttachments(GLenum bindTarget, int count);

private:
    bool alreadyAt(int w, int h) const { return ok() && width_ == w && height_ == h; }
    // A new RGBA8 texture and FBO of w x h, uncleared; false (and nothing allocated) if incomplete.
    static bool allocate(int w, int h, GLuint* tex, GLuint* fbo);

    std::string label_;
    GLuint fbo_ = 0;
    GLuint tex_ = 0;
    int width_ = 0;
    int height_ = 0;
};

}  // namespace geode::viz
