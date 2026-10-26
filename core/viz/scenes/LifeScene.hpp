#pragma once
#include <GLES3/gl3.h>

#include <array>
#include <optional>
#include <string>

#include "geode/render/Program.hpp"
#include "geode/viz/Scene.hpp"
#include "geode/viz/fluid/FluidBuffers.hpp"
#include "geode/viz/scenes/ProgramLoader.hpp"
#include "scenes/SceneCommon.hpp"
#include "geode/viz/StyleProfiles.hpp"
#include "geode/viz/BeatEnvelope.hpp"
#include "geode/viz/FrameRate.hpp"

namespace geode::viz {

// Port of LifeScene.kt: Lenia and Gray-Scott cellular fields, reseeded when a census finds them dead.
class LifeScene : public Scene {
public:
    LifeScene(const styles::LifeStyle& style, ProgramLoader loader, SceneHost host)
        : id_(style.id), style_(style), loader_(loader), host_(std::move(host)) {}
    ~LifeScene() override { release(); }

    const std::string& id() const override { return id_; }
    SceneFamily family() const override { return SceneFamily::Fluid; }
    void init() override;
    void setParams(const SceneParams& params) override { params_ = params; }
    void resize(int width, int height) override;
    void update(const GeodeFeatureFrame& features, float dt) override;
    void draw(float timeSeconds) override;
    void release() override;
    void acceptPcm(const float* samples, int count) override { pcmPulse_.accept(samples, count); }
    void setTouchField(const TouchField* field) override { touch_ = field; }

private:
    static constexpr int kSimRes = 288;
    static constexpr float kSeedSeconds = 0.5f;
    static constexpr float kGoldenAngle = 2.399963f;
    static constexpr float kBeatThreshold = 0.3f;
    static constexpr float kCensusSeconds = 4.0f;
    static constexpr float kStarved = 0.004f;
    static constexpr float kOvergrown = 0.985f;
    static constexpr float kEnvRisePerSec = 9.0f;
    static constexpr float kEnvFallPerSec = 2.4f;
    // The field advances in ticks of a 60 fps frame, each of the Style's substeps, so it grows at
    // the same speed at 30, 60 and 120 Hz (#280). Four ticks covers the longest frame dt allows.
    static constexpr float kTicksPerSecond = framerate::kReferenceFps;
    static constexpr int kMaxTicksPerFrame = 4;

    fluid::DoubleFbo* ensureState();
    void census(fluid::DoubleFbo& field, GLint restoreFbo);

    std::string id_;
    styles::LifeStyle style_;
    ProgramLoader loader_;
    SceneHost host_;
    SceneParams params_;
    bool hasPending_ = false;
    GeodeFeatureFrame pending_{};
    int width_ = 1;
    int height_ = 1;
    float time_ = 0.0f;
    float lastDt_ = 1.0f / 60.0f;
    GLuint stepProgram_ = 0;
    GLuint showProgram_ = 0;
    UniformCache stepLocs_{0};
    UniformCache showLocs_{0};
    bool programOk_ = false;
    GLuint vao_ = 0;
    std::optional<fluid::Formats> formats_;
    std::optional<fluid::DoubleFbo> state_;
    bool byteState_ = false;
    PcmPulse pcmPulse_;
    float pcmStrike_ = 0.0f;
    float envTreble_ = 0.0f;
    float beatPulse_ = 0.0f;
    BeatEnvelope beatEnvelope_;
    float seedRemain_ = 0.0f;
    float kick_ = 0.0f;
    float kickAngle_ = 0.0f;
    float kickX_ = 0.5f;
    float kickY_ = 0.5f;
    float censusAge_ = 0.0f;
    framerate::FixedStepClock ticks_{kTicksPerSecond, kMaxTicksPerFrame};
    const TouchField* touch_ = nullptr;
};

}  // namespace geode::viz
