#pragma once
#include <array>
#include <mutex>
#include <random>
#include <vector>

#include "geode/render/Quad.hpp"
#include "geode/viz/fluid/FluidEmitters.hpp"
#include "geode/viz/fluid/RippleSim.hpp"
#include "scenes/FluidSceneBase.hpp"

namespace geode::viz {

// Port of WaterScene.kt: the ripple height field with an ink layer, fed by the emitters and the fingers.
class WaterScene : public FluidSceneBase {
public:
    WaterScene(std::string id, ProgramLoader loader, SceneHost host)
        : FluidSceneBase(std::move(id), kTimeWrapSeconds, loader, std::move(host)), sim_(loader_) {
        sim_.inkEnabled = true;
        emitters_.choreography = &choreography_;
    }
    ~WaterScene() override { release(); }

    void init() override;
    void resize(int width, int height) override { sim_.resize(width, height); }
    void draw(float timeSeconds) override;
    void release() override;
    bool isWater() const override { return true; }
    void queueTouchStroke(float nx, float ny, float ndx, float ndy, float dt, float strength) override;

protected:
    GeodeFeatureFrame idleFeatures(float dt) override;
    void onApplyQualityTier(int index, bool userChanged) override;

private:
    static constexpr float kTouchRadius = 0.11f;
    static constexpr int kMaxTouchBacklog = 24;
    static constexpr float kInkGain = 0.8f;
    static constexpr float kTimeWrapSeconds = 628.31853f;
    // Tilt: how fast ink runs downhill (uv per second), and the slosh a change of tilt gives. A slosh
    // is one drop at the downhill edge, only when the tilt has moved by kSloshStep since the last,
    // and never more than once per kSloshRefractory: the water answers a tilt and then settles, so it
    // never sways on its own at the slow rates that make people queasy.
    static constexpr float kRunoff = 0.05f;
    static constexpr float kSloshStep = 0.25f;
    static constexpr float kSloshRefractorySeconds = 0.6f;
    static constexpr float kSloshAmplitude = 0.18f;
    static constexpr float kSloshRadius = 0.18f;
    // The ring a trusted downbeat drops at the centre of the pool.
    static constexpr float kRingRadius = 0.16f;

    void tilt(float dt, float rippleStrength);

    static int gridResFor(int tierIndex);
    void queueIdleRain(float dt);
    void drainTouchStrokes(float rippleStrength, float baseHue);
    float nextFloat() { return uniform_(rng_); }

    fluid::RippleSim sim_;
    fluid::Emitters emitters_;
    std::vector<fluid::Splat> splats_;
    UniformCache display_{0};
    bool displayOk_ = false;
    FullscreenTriangle quad_;
    float idlePhase_ = 0.0f;
    float rainAccum_ = 0.0f;
    std::mt19937 rng_{std::random_device{}()};
    std::uniform_real_distribution<float> uniform_{0.0f, 1.0f};
    std::mutex strokeLock_;
    std::vector<std::array<float, 6>> touchStrokes_;
    std::vector<std::array<float, 6>> drainedStrokes_;
    std::array<float, 3> rgb_{};
    std::array<float, 2> sloshTilt_{};
    float sloshWait_ = 0.0f;
};

}  // namespace geode::viz
