#pragma once
#include <GLES3/gl3.h>

#include <functional>
#include <memory>
#include <string>
#include <vector>

#include "geode/api/geode_api.h"
#include "geode/viz/GravityField.hpp"
#include "geode/viz/Params.hpp"
#include "geode/viz/QualityTiers.hpp"
#include "geode/viz/SharedCamera.hpp"
#include "geode/viz/TouchField.hpp"

namespace geode::viz {

enum class SceneFamily { Shader, Milkdrop, Fluid };

// Port of Scene.kt plus the optional hooks the renderer wires by capability rather than by class.
class Scene {
public:
    virtual ~Scene() = default;
    virtual const std::string& id() const = 0;
    virtual SceneFamily family() const = 0;
    virtual void init() = 0;
    virtual void setParams(const SceneParams& params) = 0;
    virtual void resize(int width, int height) = 0;
    virtual void update(const GeodeFeatureFrame& features, float dt) = 0;
    virtual void draw(float timeSeconds) = 0;
    virtual void release() = 0;

    // This frame's audio window, as mono and as interleaved stereo; both come every frame the Visual has
    // a live feed (silence while nothing is heard) and never otherwise.
    virtual void acceptPcm(const float* samples, int count) { (void) samples; (void) count; }
    virtual void acceptStereoPcm(const float* interleaved, int frames) { (void) interleaved; (void) frames; }
    virtual void setFlow(GLuint texture, float strength) { (void) texture; (void) strength; }
    virtual void setPaletteLut(GLuint texture) { (void) texture; }
    virtual void setTouchField(const TouchField* field) { (void) field; }
    // Which way is down (GravityField), stepped once per frame before any scene draws.
    virtual void setGravityField(const GravityField* field) { (void) field; }
    // The Camera this frame (SharedCamera.hpp) and the Style's own Extras, handed over before setParams.
    virtual void setCamera(const CameraPose& pose) { (void) pose; }
    virtual void setExtras(const ExtraValues& extras) { (void) extras; }
    // What the current thermal tier lets this Style spend (QualityTiers.hpp), handed over with the Camera.
    virtual void setQuality(const quality::Budget& budget) { (void) budget; }
    // True when the Style moves a camera of its own from the Camera (a raymarched world reading
    // lib_camera.glsl); the composite then leaves push-in, orbit and travel to it.
    virtual bool ownsCamera() const { return false; }
    // > 0 keeps the previous frame in the scene target (Curl Flow); 0 clears it.
    virtual float trailRetention(const SceneParams& params) const { (void) params; return 0.0f; }
    virtual GLuint velocityTexture() const { return 0; }
    // The fluid scene runs its own flow field; the water scene owns the ripples and the touch smears.
    virtual bool isFluid() const { return false; }
    virtual bool isWater() const { return false; }
    // True when the Style draws the finger's strokes itself (Water's ripples); every other Style gets
    // them as the shared ripple smear, and only while the person has that smear on.
    virtual bool ownsTouchStrokes() const { return isWater(); }
    virtual void queueTouchStroke(float nx, float ny, float ndx, float ndy, float dt, float strength) {
        (void) nx; (void) ny; (void) ndx; (void) ndy; (void) dt; (void) strength;
    }
    // The surface size in pixels, unscaled: no supersampling and no thermal render scale. resize()
    // gives the scene target; MilkDrop sizes its engine buffer from this instead (times its load
    // step) and its post pass scales the result onto the scene target.
    virtual void setWindowSize(int width, int height) { (void) width; (void) height; }
    virtual void queueMilkPreset(const std::string& path) { (void) path; }
    virtual void reloadMilkPreset() {}
    virtual void setMilkTextureDir(const std::string& dir) { (void) dir; }
};

struct SceneHost {
    // "" clears the last error, as a successful compile does.
    std::function<void(const std::string&)> onShaderError;
    // The rate the display is being asked for (0 = free-running), for the fluid quality ladder.
    std::function<float()> pacedFps;
    std::function<void(const std::string& path)> onMilkPresetLoaded;
    // True while the engine is producing a Render: frame by frame on the render clock, never
    // adapting to load, so the same input gives the same frames (ADR-0005).
    std::function<bool()> rendering;
};

}  // namespace geode::viz
