#include "geode/render/Texture.hpp"

#include <algorithm>

namespace geode::viz {

void Texture::allocate(int width, int height, GLint filter, GLint wrap) {
    release();
    glGenTextures(1, &id_);
    glBindTexture(GL_TEXTURE_2D, id_);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, filter);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, filter);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, wrap);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, wrap);
    width_ = width;
    height_ = height;
}

void Texture::createStorage(GLenum internalFormat, int width, int height, GLint filter) {
    allocate(width, height, filter, GL_CLAMP_TO_EDGE);
    glTexStorage2D(GL_TEXTURE_2D, 1, internalFormat, width, height);
}

void Texture::createImage(GLenum internalFormat, GLenum format, GLenum type, int width, int height, GLint filter, GLint wrap) {
    allocate(width, height, filter, wrap);
    glTexImage2D(GL_TEXTURE_2D, 0, static_cast<GLint>(internalFormat), width, height, 0, format, type, nullptr);
}

void Texture::createFromPixels(const std::uint8_t* rgba, int width, int height, GLint wrap, GLint filter, bool mipmaps) {
    allocate(width, height, filter, wrap);
    int levels = 1;
    for (int side = std::max(width, height); mipmaps && side > 1; side /= 2) ++levels;
    glTexStorage2D(GL_TEXTURE_2D, levels, GL_RGBA8, width, height);
    glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, rgba);
    if (!mipmaps) return;
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR);
    glGenerateMipmap(GL_TEXTURE_2D);
}

void Texture::bind(int unit) const {
    glActiveTexture(GL_TEXTURE0 + static_cast<GLenum>(unit));
    glBindTexture(GL_TEXTURE_2D, id_);
}

void Texture::upload(GLenum format, GLenum type, const void* pixels, int width, int height, int x, int y) const {
    glBindTexture(GL_TEXTURE_2D, id_);
    glTexSubImage2D(GL_TEXTURE_2D, 0, x, y, width, height, format, type, pixels);
}

void Texture::release() {
    if (id_ != 0) glDeleteTextures(1, &id_);
    forget();
}

void Texture::forget() {
    id_ = 0;
    width_ = 0;
    height_ = 0;
}

}  // namespace geode::viz
