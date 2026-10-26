#pragma once
#include <GLES3/gl3.h>
#include <projectM-4/projectM.h>

#include <memory>
#include <mutex>
#include <optional>
#include <string>
#include <type_traits>
#include <vector>

#include "geode/render/Framebuffer.hpp"
#include "geode/render/Program.hpp"
#include "geode/viz/MilkdropAdaptation.hpp"
#include "geode/viz/Scene.hpp"
#include "geode/viz/scenes/ProgramLoader.hpp"
#include "geode/viz/BeatEnvelope.hpp"

namespace geode::viz {

// One projectM instance rendering into a scene-owned framebuffer (projectm_opengl_render_frame_fbo,
// backported onto v4.1.8 by tools/projectm-v4.1.8-render-fbo-backport.patch), graded by pm_post_frag.
// The engine never touches the window surface, so the scene is independent of the EGL config.
//
// The engine buffer follows the window size (setWindowSize), not the supersampled scene target.
// Live, it adapts to load mesh-first: the warp mesh steps down at once, the buffer only at the next
// preset change (milkdrop::LoadAdapter), and projectM is told the real paced frame rate. During a
// Render the engine is rebuilt seeded and driven by the render clock (projectm_set_frame_time,
// tools/projectm-v4.1.8-render-clock.patch) at full detail, so the same input gives the same frames.
class MilkdropScene : public Scene {
public:
    MilkdropScene(std::string id, ProgramLoader loader, SceneHost host)
        : id_(std::move(id)), loader_(loader), host_(std::move(host)), pcm_(kPcmCapacity, 0.0f),
          stereo_(static_cast<size_t>(kEnginePcmSamples) * 2, 0.0f) {}
    ~MilkdropScene() override { release(); }

    const std::string& id() const override { return id_; }
    SceneFamily family() const override { return SceneFamily::Milkdrop; }
    void init() override;
    void setParams(const SceneParams& params) override { params_ = params; }
    void resize(int width, int height) override;
    void setWindowSize(int width, int height) override;
    void update(const GeodeFeatureFrame& features, float dt) override;
    void draw(float timeSeconds) override;
    void release() override;
    void acceptPcm(const float* samples, int count) override;
    // The frame's audio as projectM wants it: both channels, so wave modes that split left and right show
    // two, in the loudest window of what was heard since the last frame (milkdrop::loudestWindow).
    void acceptPcmSpan(const float* interleaved, int frames, int fresh) override;
    void queueMilkPreset(const std::string& path) override;
    void reloadMilkPreset() override;
    void setMilkTextureDir(const std::string& dir) override;
    void setDrawTarget(GLuint fbo, int width, int height) override;
    void setFeatureBus(const FeatureValues& values) override { bus_ = values; }

private:
    static constexpr double kLoadDebounceSeconds = 0.4;
    // How long a failed projectm_create() is left alone before the next attempt. ensureEngine()
    // runs once per frame, so without a wait a context that cannot host the engine - the state a
    // return from the background can leave behind - is retried sixty times a second forever.
    static constexpr double kCreateRetrySeconds = 2.0;
    static constexpr int kDiagFrames = 90;
    static constexpr int kDiagWarmup = 20;
    // How many frames after the engine is made or a preset loads projectM's leftover GL errors are
    // read: that is when a preset's pipeline raises them. Reading glGetError every frame would make a
    // threaded driver wait for its queue each time (#311).
    static constexpr int kErrorCheckFrames = 8;
    static constexpr int kPcmCapacity = 8192;
    static constexpr int kEnginePcmSamples = milkdrop::kWindowFrames;
    static constexpr float kTwoPi = 6.2831853f;

    struct EngineDeleter {
        void operator()(projectm_handle h) const;
    };
    using Engine = std::unique_ptr<std::remove_pointer_t<projectm_handle>, EngineDeleter>;

    static void onPresetSwitchFailed(const char* presetFilename, const char* message, void* userData);
    static double nowSeconds();
    void ensureEngine(double now);
    // Re-queues the preset the scene last had on screen and clears the load debounce, so a
    // freshly created engine loads it on the very next frame instead of waiting one window out.
    void armLastPreset();
    void loadPendingPreset(double now);
    // Enters or leaves Render mode when the host's flag changes; the engine is rebuilt either way.
    void followRenderMode();
    // Sizes the engine buffer from the window at the adapter's buffer step.
    void applyEngineSize();
    // Mesh, frame rate and clock for the frame about to render.
    void applyFrameSettings();
    std::optional<std::string> takeError();
    void diagnoseBlackFrame();
    void drainEngineErrors();
    void set1f(const char* name, float value) { glUniform1f(postLocs_.loc(name), value); }

    std::string id_;
    ProgramLoader loader_;
    SceneHost host_;
    std::vector<float> pcm_;
    int pcmCount_ = 0;
    // This frame's stereo window; preferred over the mono samples when a frame brings both.
    std::vector<float> stereo_;
    int stereoFrames_ = 0;
    Engine engine_;
    int width_ = 0;
    int height_ = 0;
    bool reportedCreateFailure_ = false;
    double lastCreateAttemptSeconds_ = 0.0;
    Framebuffer frame_{"milkdrop"};
    int engineWidth_ = 0;
    int engineHeight_ = 0;
    int windowWidth_ = 0;
    int windowHeight_ = 0;
    milkdrop::LoadAdapter adapter_;
    milkdrop::RenderClock renderClock_;
    milkdrop::MeshSize appliedMesh_{0, 0};
    int appliedFps_ = 0;
    float lastDt_ = 0.0f;
    FeatureValues bus_;
    bool rendering_ = false;
    // Whether the engine has loaded a preset since it was created: a Render's first preset is a
    // hard cut, since the unseeded idle preset it would blend from differs from run to run.
    bool presetSinceCreate_ = false;
    GLuint postProgram_ = 0;
    bool postProgramOk_ = false;
    UniformCache postLocs_{0};
    GLuint postVao_ = 0;
    float rotationAngle_ = 0.0f;
    float zoomPhase_ = 0.0f;
    float cyclePhase_ = 0.0f;
    float beatPulse_ = 0.0f;
    BeatEnvelope beatEnvelope_;
    double lastLoadSeconds_ = 0.0;
    SceneParams params_;
    int diagFrames_ = 0;
    bool diagDone_ = false;
    bool reportedEngineGlError_ = false;
    int errorChecksLeft_ = 0;
    // The renderer's target for this draw (Scene::setDrawTarget); asked of the driver until handed.
    bool drawTargetHanded_ = false;
    GLuint drawFbo_ = 0;
    int drawWidth_ = 0;
    int drawHeight_ = 0;
    std::mutex presetLock_;
    std::string pendingPresetPath_;
    std::string lastPresetPath_;
    std::string sharedTextureDir_;
    std::mutex errorLock_;
    std::string lastError_;
    // The last message written to logcat, so a preset that fails on every frame it is retried
    // writes one line rather than one line per frame. Guarded by errorLock_.
    std::string lastLoggedError_;
};

}  // namespace geode::viz
