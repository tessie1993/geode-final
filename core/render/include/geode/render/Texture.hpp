#pragma once
#include <GLES3/gl3.h>

#include <cstdint>

namespace geode::viz {

class Texture {
public:
    Texture() = default;
    ~Texture() { release(); }
    Texture(const Texture&) = delete;
    Texture& operator=(const Texture&) = delete;

    GLuint id() const { return id_; }
    int width() const { return width_; }
    int height() const { return height_; }
    bool ok() const { return id_ != 0; }

    // Immutable storage via glTexStorage2D with the given filter and clamp-to-edge wrap.
    void createStorage(GLenum internalFormat, int width, int height, GLint filter);
    // Mutable storage via glTexImage2D, for the RGBA8/R32F uploads the Kotlin scenes make.
    void createImage(GLenum internalFormat, GLenum format, GLenum type, int width, int height, GLint filter, GLint wrap);
    // Immutable RGBA8 storage holding `rgba` (width x height, rows from the bottom up), wrapping with `wrap`
    // on both axes and filtered with `filter`; with `mipmaps`, a full chain of halved copies, minified
    // trilinearly, so a far or small use of the image does not shimmer.
    void createFromPixels(const std::uint8_t* rgba, int width, int height, GLint wrap, GLint filter, bool mipmaps);
    void bind(int unit) const;
    void upload(GLenum format, GLenum type, const void* pixels, int width, int height, int x = 0, int y = 0) const;
    void release();
    void forget();

private:
    void allocate(int width, int height, GLint filter, GLint wrap);

    GLuint id_ = 0;
    int width_ = 0;
    int height_ = 0;
};

}  // namespace geode::viz
