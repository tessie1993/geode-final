#pragma once
#include <GLES3/gl3.h>

#include <array>

#include "geode/render/Framebuffer.hpp"
#include "geode/render/ShaderSource.hpp"
#include "geode/viz/StyleInputs.hpp"

namespace geode::viz {

class HdrPass;

// The shared Style inputs one renderer holds for every shader Style (StyleInputs.hpp): the seeded noise
// textures and the blue-noise mask, each made the first time a Style that declares it draws and never
// otherwise, plus what a Style needs to keep its own history (its previous frame and blur levels, which
// are the Style's: ShaderScene): the HDR pass whose down filter makes the blur levels, and the format
// of the scene targets the history copies.
class SharedInputs {
public:
    SharedInputs() = default;
    ~SharedInputs() { release(); }
    SharedInputs(const SharedInputs&) = delete;
    SharedInputs& operator=(const SharedInputs&) = delete;

    // On a new context: where the blue-noise mask is read from, the HDR pass and the scene targets'
    // format. Call release() first on a context that held textures before.
    void attach(const ShaderSource* assets, HdrPass* hdr, Framebuffer::Format format);
    void release();

    // The texture of a noise input (noise_lq, noise_mq, noise_hq, noise_vol or blue_noise), made on its
    // first use: GL_TEXTURE_3D for the volume, GL_TEXTURE_2D for the rest. 0 for any other input or one
    // that could not be made.
    GLuint texture(inputs::Input input);
    // A 1x1 opaque black texture, for a history input that holds nothing yet.
    GLuint black();

    HdrPass* hdr() const { return hdr_; }
    Framebuffer::Format format() const { return format_; }

private:
    GLuint make(inputs::Input input);

    const ShaderSource* assets_ = nullptr;
    HdrPass* hdr_ = nullptr;
    Framebuffer::Format format_ = Framebuffer::Format::Rgba8;
    std::array<GLuint, inputs::kInputCount> textures_{};
    GLuint black_ = 0;
};

}  // namespace geode::viz
