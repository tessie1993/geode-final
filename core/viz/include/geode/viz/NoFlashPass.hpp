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

// The GL side of the No-flash rule. The composite pass draws the finished frame into this pass's
// candidate target instead of the output; present() measures it on a 4x4 grid on the GPU
// (noflash_measure_frag.glsl), reads those 16 texels back, asks FlashLimiter how much of the new
// frame may be shown, and draws the result into the output (noflash_mix_frag.glsl). It keeps the
// frame it showed last as the crossfade source, ping-ponging three output-sized targets so that
// a frame passed through unchanged costs no copy into the history.
class NoFlashPass {
public:
    ~NoFlashPass() { release(); }

    bool create(const ShaderSource& assets, ProgramBinaryCache* cache, const std::string& fadeVert, std::string* error);
    void release();
    // Binds the candidate target at the output size. False when the pass cannot run (no program
    // or no memory for its targets); the caller then draws straight to its output.
    bool bindCandidate(int width, int height);
    // Measures the candidate, limits it and draws the frame to show into `targetFbo`, which is
    // left bound at the output size.
    void present(float dt, GLuint targetFbo, GLuint quadVao);
    // How much of the last candidate was shown (1 = all of it).
    float lastAmount() const { return lastAmount_; }

private:
    void measure(GLuint quadVao);
    void drawMix(GLuint fbo, float amount, GLuint quadVao);

    Framebuffer frameA_{"noFlashA"};
    Framebuffer frameB_{"noFlashB"};
    Framebuffer frameC_{"noFlashC"};
    Framebuffer* candidate_ = &frameA_;
    Framebuffer* shown_ = &frameB_;
    Framebuffer* spare_ = &frameC_;
    Framebuffer grid_{"noFlashGrid"};
    GLuint measureProgram_ = 0;
    GLuint mixProgram_ = 0;
    UniformCache measureUniforms_;
    UniformCache mixUniforms_;
    FlashLimiter limiter_;
    std::array<uint8_t, noflash::kReadbackBytes> readback_{};
    int width_ = 0;
    int height_ = 0;
    float lastAmount_ = 1.0f;
};

}  // namespace geode::viz
