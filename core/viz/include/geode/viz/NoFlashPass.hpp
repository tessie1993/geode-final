#pragma once
#include <GLES3/gl3.h>

#include <array>
#include <cstdint>
#include <string>

#include "geode/render/Framebuffer.hpp"
#include "geode/render/Program.hpp"
#include "geode/render/ShaderSource.hpp"
#include "geode/viz/FlashLimiter.hpp"

namespace geode::viz {

// The GL side of the No-flash rule. The final pass (HdrPass, after the composite) draws the
// finished, tonemapped frame into this pass's candidate target instead of the output; present()
// measures it on a 4x4 grid on the GPU (noflash_measure_frag.glsl) and starts reading those 16
// texels back into a pixel-pack buffer behind a fence, without waiting for them. The frame is shown
// one frame later (DelayedPresent), when its measurement has arrived: FlashLimiter decides how much
// of it may be shown and the result is drawn into the output (noflash_mix_frag.glsl). So nothing
// stalls on the GPU, and the limiter still judges every frame before any of it reaches the screen.
// The frame shown last is kept as the crossfade source; four frame-sized targets rotate (the one
// being drawn, the one pending, the one shown, a spare) so that a frame passed through unchanged
// costs no copy into the history. They are drawn at the frame size (tile::frameSize: the scene's
// where it is below the output) and the last draw into the output upscales, so a reduced render
// scale saves its pixels in every pass up to the screen (#311).
class NoFlashPass {
public:
    ~NoFlashPass() { release(); }

    bool create(const ShaderSource& assets, ProgramBinaryCache* cache, const std::string& fadeVert, std::string* error);
    void release();
    // Binds the candidate target at the frame size. False when the pass cannot run (no program
    // or no memory for its targets); the caller then draws straight to its output. A new frame size
    // moves every target keeping its picture, so the frames pending and shown stay valid and the
    // limiter keeps judging against them.
    bool bindCandidate(int width, int height);
    // The candidate target bindCandidate() made, for a pass that draws into it later in the frame.
    GLuint candidateFbo() const { return candidate_->frame.fbo(); }
    // Measures the candidate, limits the frame due now and draws it into `targetFbo`, which is left
    // bound at targetWidth x targetHeight (the output size). `synchronous` (the offscreen Render)
    // waits for this candidate's own measurement and shows it in this frame, so every rendered frame
    // is the one asked for.
    void present(float dt, GLuint targetFbo, int targetWidth, int targetHeight, GLuint quadVao, bool synchronous);
    // How much of the last frame the limiter judged was shown (1 = all of it).
    float lastAmount() const { return lastAmount_; }

private:
    // One frame-sized target and the pixel-pack buffer its measurement is read into.
    struct Slot {
        explicit Slot(const char* label) : frame(label) {}
        Framebuffer frame;
        GLuint pbo = 0;
        GLsync fence = nullptr;
    };

    bool ensureSlot(Slot& slot, int width, int height);
    void measure(Slot& slot, GLuint quadVao);
    // Waits for the slot's measurement (normally long arrived) and copies it into readback_.
    bool readMeasurement(Slot& slot);
    static void dropFence(Slot& slot);
    // Limits `next` against the frame shown last and draws the result into `targetFbo`; `next`
    // or the damped frame becomes the frame shown last.
    void show(Slot*& next, float dt, GLuint targetFbo, int targetWidth, int targetHeight, GLuint quadVao);
    // Draws the whole of `fbo`, width x height, from the two frames.
    void drawMix(GLuint fbo, int width, int height, GLuint shownTex, GLuint nextTex, float amount, GLuint quadVao);

    Slot slotA_{"noFlashA"};
    Slot slotB_{"noFlashB"};
    Slot slotC_{"noFlashC"};
    Slot slotD_{"noFlashD"};
    Slot* candidate_ = &slotA_;
    Slot* pending_ = &slotB_;
    Slot* shown_ = &slotC_;
    Slot* spare_ = &slotD_;
    Framebuffer grid_{"noFlashGrid"};
    GLuint measureProgram_ = 0;
    GLuint mixProgram_ = 0;
    UniformCache measureUniforms_;
    UniformCache mixUniforms_;
    FlashLimiter limiter_;
    DelayedPresent schedule_;
    std::array<uint8_t, noflash::kReadbackBytes> readback_{};
    int width_ = 0;
    int height_ = 0;
    float lastAmount_ = 1.0f;
};

}  // namespace geode::viz
