#pragma once
#include <optional>

#include "geode/render/Quad.hpp"
#include "geode/viz/fluid/FluidBuffers.hpp"
#include "fluid/FluidParticles.hpp"
#include "scenes/FluidSceneBase.hpp"

namespace geode::viz {

// Port of CurlFlowScene.kt: particles carried by a curl-noise field re-evaluated every audio frame.
class CurlFlowScene : public FluidSceneBase {
public:
    CurlFlowScene(ProgramLoader loader, SceneHost host)
        : FluidSceneBase("curlflow", kWallWrapSeconds, loader, std::move(host)), particles_(loader_) {}
    ~CurlFlowScene() override { release(); }

    void init() override;
    void resize(int width, int height) override;
    void update(const GeodeFeatureFrame& features, float dt) override;
    void draw(float timeSeconds) override;
    void release() override;
    float trailRetention(const SceneParams& params) const override;

protected:
    GeodeFeatureFrame idleFeatures(float dt) override { (void) dt; return idleAudioFeatures(0.0f, 0.0f, 0.0f, 0.0f); }
    void onApplyQualityTier(int index, bool userChanged) override { (void) index; (void) userChanged; }

private:
    static constexpr float kNoiseWrapSeconds = 628.31853f;
    static constexpr float kWallWrapSeconds = 7100.0f;
    // The declared grid of the curl field (short side, in cells) and the particles riding it.
    static constexpr int kFieldRes = 96;
    static constexpr int kParticleCount = 49152;

    fluid::Particles particles_;
    fluid::Formats formats_;
    std::optional<fluid::Fbo> field_;
    UniformCache fieldUniforms_{0};
    FullscreenTriangle quad_;
    float noiseTime_ = 0.0f;
    float wallTime_ = 0.0f;
    float beatEnv_ = 0.0f;
    float beatDrive_ = 0.0f;
    float pcmKick_ = 0.0f;
    float aspect_ = 1.0f;
    bool available_ = false;
};

}  // namespace geode::viz
