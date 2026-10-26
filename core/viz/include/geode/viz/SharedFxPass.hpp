#pragma once
#include <GLES3/gl3.h>

#include <string>

#include "geode/render/Framebuffer.hpp"
#include "geode/render/Program.hpp"
#include "geode/render/ShaderSource.hpp"
#include "geode/viz/SharedEffects.hpp"

namespace geode::viz {

// The GL side of SharedEffects: draws shared_fx_frag.glsl over the active Style's scene target into
// a target of its own, which the composite then reads instead of the scene target. It keeps what it
// drew as the next frame's history for Trails and Motion blur, ping-ponging two targets so the
// history costs no copy. While every effect is 0 the renderer does not call it at all, so a Style
// with no shared effect costs nothing and looks exactly as before.
class SharedFxPass {
public:
    ~SharedFxPass() { release(); }

    bool create(const ShaderSource& assets, ProgramBinaryCache* cache, const std::string& fadeVert, std::string* error);
    void release();
    // The format of its targets (the scene target's, so the effects keep the picture's headroom).
    void setTargetFormat(Framebuffer::Format format) {
        frameA_.setFormat(format);
        frameB_.setFormat(format);
    }
    // Draws the effects over `sceneTex` and returns the texture to composite, or `sceneTex` itself
    // when the pass cannot run (no program, or no memory for its targets).
    GLuint apply(const SharedEffects& fx, GLuint sceneTex, int width, int height, float dt, float timeSeconds, GLuint quadVao);
    // The next apply() starts with no history, as after a Style change or when the effects were off.
    void forgetHistory() { historyValid_ = false; }

private:
    Framebuffer frameA_{"sharedFxA"};
    Framebuffer frameB_{"sharedFxB"};
    Framebuffer* out_ = &frameA_;
    Framebuffer* history_ = &frameB_;
    GLuint program_ = 0;
    UniformCache uniforms_;
    bool historyValid_ = false;
    // The echo copy's flip and fade (sharedfx::stepEchoCopy), stepped once per apply().
    sharedfx::EchoCopyFade echoCopy_;
};

}  // namespace geode::viz
