#pragma once
#include <GLES3/gl3.h>

#include <string>

#include "geode/render/Framebuffer.hpp"
#include "geode/render/Program.hpp"
#include "geode/render/ShaderSource.hpp"
#include "geode/viz/LensFx.hpp"
#include "geode/viz/SharedCamera.hpp"

namespace geode::viz {

// The GL side of depth of field (LensFx.hpp): dof_frag.glsl over the active Style's scene target, into
// a target of its own that the Shared core effects and the composite then read instead. It runs only
// while the Style declares depth of field and its tier allows it; otherwise the scene target goes on
// as it is, and the pass holds no target.
class DofPass {
public:
    ~DofPass() { release(); }

    bool create(const ShaderSource& assets, ProgramBinaryCache* cache, const std::string& fadeVert, std::string* error);
    void release();
    // The scene target's format, so the picture keeps its headroom.
    void setTargetFormat(Framebuffer::Format format) { target_.setFormat(format); }
    // The texture to go on with: `sceneTex` focused, or `sceneTex` itself while depth of field is off
    // or the pass cannot run.
    GLuint apply(const lens::Dof& dof, GLuint sceneTex, int width, int height, GLuint quadVao);

private:
    Framebuffer target_{"dof"};
    GLuint program_ = 0;
    UniformCache uniforms_;
};

// The GL side of the 3D particle layer (LensFx.hpp): one instanced draw of motion-stretched streaks
// (particles3d_vert.glsl, particles3d_frag.glsl), added onto the composite's target before the bloom
// and the tonemap. Its cost is the particle count, which the thermal tier sets, and the few pixels
// each streak covers; nothing is drawn while the layer is off.
class ParticlePass {
public:
    ~ParticlePass() { release(); }

    bool create(const ShaderSource& assets, ProgramBinaryCache* cache, std::string* error);
    void release();
    // Adds the particles onto `targetFbo` (width x height), seen through the Camera's orbit.
    void draw(const lens::Particles& particles, const CameraPose& pose, GLuint targetFbo, int width, int height,
              GLuint quadVao);

private:
    GLuint program_ = 0;
    UniformCache uniforms_;
};

}  // namespace geode::viz
