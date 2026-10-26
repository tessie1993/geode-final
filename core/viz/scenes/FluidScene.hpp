#pragma once
#include <array>
#include <vector>

#include "geode/viz/fluid/FluidEmitters.hpp"
#include "fluid/FluidLook.hpp"
#include "fluid/FluidParticles.hpp"
#include "geode/viz/fluid/FluidSim.hpp"
#include "scenes/FluidSceneBase.hpp"
#include "geode/viz/StyleProfiles.hpp"

namespace geode::viz {

// Port of FluidScene.kt: dye, particles and the look pass over one FluidSim.
// One instance per fluid STYLE: the style picks the display look and biases
// the solver and emitter values the user's params arrive with.
class FluidScene : public FluidSceneBase {
public:
    FluidScene(const styles::FluidStyle& style, ProgramLoader loader, SceneHost host)
        : FluidSceneBase(style.id, kTimeWrapSeconds, loader, std::move(host)),
          base_(style),
          style_(style),
          sim_(loader_),
          look_(loader_),
          particles_(loader_) {
        emitters_.choreography = &choreography_;
    }
    ~FluidScene() override { release(); }

    void init() override;
    void resize(int width, int height) override;
    void draw(float timeSeconds) override;
    void release() override;
    GLuint velocityTexture() const override { return sim_.available() ? sim_.velocityTex() : 0; }
    bool isFluid() const override { return true; }
    // SWIRL the curl, VISCOSITY the velocity's dissipation, DECAY how long the dye lasts, SIZE and FORCE
    // the splats.
    void setExtras(const ExtraValues& values) override {
        FluidSceneBase::setExtras(values);
        style_ = extras::apply(base_, values);
    }
    // A finger stirs the fluid and lays a thread of ink along its path (#315).
    bool ownsTouchStrokes() const override { return true; }
    void queueTouchStroke(float nx, float ny, float ndx, float ndy, float dt, float strength) override;

protected:
    GeodeFeatureFrame idleFeatures(float dt) override;
    bool tierApplied() const override { return appliedParticleSide_ != 0; }
    void onApplyQualityTier(int index, bool userChanged) override;

private:
    static constexpr float kTimeWrapSeconds = 7100.0f;
    static constexpr float kIdleWrapSeconds = 628.31853f;
    // A finger's splat: its radius, the share of its speed it gives the fluid (as the stirrers do),
    // the fastest it may push, and how much ink it lays (dim: a finger is not a light).
    static constexpr float kTouchRadius = 0.07f;
    static constexpr float kTouchForce = 0.22f;
    static constexpr float kTouchMaxSpeed = 12.0f;
    static constexpr float kTouchInk = 0.35f;
    static constexpr int kMaxTouchStrokes = 24;
    // Tilt: how fast a unit of ink sinks (grid units per second squared) and particles fall.
    static constexpr float kBuoyancy = 0.8f;
    static constexpr float kParticleFall = 6.0f;

    void splatTouchStrokes(float hueBase, float hueSpan);

    // The Style as its pack tunes it, and as its Extras move it this frame.
    styles::FluidStyle base_;
    styles::FluidStyle style_;
    fluid::FluidSim sim_;
    fluid::Look look_;
    fluid::Particles particles_;
    fluid::Emitters emitters_;
    std::vector<fluid::Splat> splats_;
    // Filled and drained on the GL thread (Overlays hands strokes over before the scene draws).
    std::vector<std::array<float, 6>> touchStrokes_;
    int appliedParticleSide_ = 0;
    float idlePhase_ = 0.0f;
};

}  // namespace geode::viz
