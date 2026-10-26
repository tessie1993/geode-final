#include "geode/viz/SharedInputs.hpp"

#include "BlueNoise.hpp"

namespace geode::viz {

namespace {

GLuint makePlane(int lattice) {
    const auto pixels = inputs::noisePlane(inputs::kNoiseSeed, inputs::kNoiseSize, lattice);
    if (pixels.empty()) return 0;
    GLuint tex = 0;
    glGenTextures(1, &tex);
    glBindTexture(GL_TEXTURE_2D, tex);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_REPEAT);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_REPEAT);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, inputs::kNoiseSize, inputs::kNoiseSize, 0, GL_RGBA, GL_UNSIGNED_BYTE, pixels.data());
    return tex;
}

GLuint makeVolume() {
    const auto voxels = inputs::noiseVolume(inputs::kNoiseSeed, inputs::kNoiseVolumeSize);
    if (voxels.empty()) return 0;
    GLuint tex = 0;
    glGenTextures(1, &tex);
    glBindTexture(GL_TEXTURE_3D, tex);
    glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_S, GL_REPEAT);
    glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_T, GL_REPEAT);
    glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_R, GL_REPEAT);
    const int side = inputs::kNoiseVolumeSize;
    glTexImage3D(GL_TEXTURE_3D, 0, GL_RGBA8, side, side, side, 0, GL_RGBA, GL_UNSIGNED_BYTE, voxels.data());
    glBindTexture(GL_TEXTURE_3D, 0);
    return tex;
}

}  // namespace

void SharedInputs::attach(const ShaderSource* assets, HdrPass* hdr, Framebuffer::Format format) {
    assets_ = assets;
    hdr_ = hdr;
    format_ = format;
}

void SharedInputs::release() {
    for (GLuint& tex : textures_) {
        if (tex != 0) glDeleteTextures(1, &tex);
        tex = 0;
    }
    if (black_ != 0) glDeleteTextures(1, &black_);
    black_ = 0;
}

GLuint SharedInputs::texture(inputs::Input input) {
    GLuint& tex = textures_[static_cast<size_t>(input)];
    if (tex == 0) tex = make(input);
    return tex;
}

GLuint SharedInputs::make(inputs::Input input) {
    switch (input) {
        case inputs::Input::NoiseLq:
        case inputs::Input::NoiseMq:
        case inputs::Input::NoiseHq: return makePlane(inputs::lattice(input));
        case inputs::Input::NoiseVolume: return makeVolume();
        case inputs::Input::BlueNoise: return assets_ != nullptr ? blue_noise::createTexture(*assets_) : 0;
        default: return 0;
    }
}

GLuint SharedInputs::black() {
    if (black_ != 0) return black_;
    glGenTextures(1, &black_);
    glBindTexture(GL_TEXTURE_2D, black_);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    const unsigned char pixel[4] = {0, 0, 0, 255};
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, 1, 1, 0, GL_RGBA, GL_UNSIGNED_BYTE, pixel);
    return black_;
}

}  // namespace geode::viz
