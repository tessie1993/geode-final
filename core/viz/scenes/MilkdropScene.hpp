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
// backported onto v4.1.7 by tools/projectm-v4.1.7-render-fbo-backport.patch), graded by pm_post_frag.
// The engine never touches the window surface, so the scene is independent of the EGL config.
//
// The engine buffer follows the window size (setWindowSize), not the supersampled scene target.
// Live, it adapts to load mesh-first: the warp mesh steps down at once, the buffer only at the next
// preset change (milkdrop::LoadAdapter), and projectM is told the real paced frame rate. During a
// Render the engine is rebuilt seeded and driven by the render clock (projectm_set_frame_time,
// tools/projectm-v4.1.7-render-clock.patch) at full detail, so the same input gives the same frames.
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
    // The frame's window as projectM wants it: both channels, so wave modes that split left and right show two.
    void acceptStereoPcm(const float* interleaved, int frames) override;
    void queueMilkPreset(const std::string& path) override;
    void reloadMilkPreset() override;
    void setMilkTextureDir(const std::string& dir) override;

private:
    static constexpr double kLoadDebounceSeconds = 0.4;
    // How long a failed projectm_create() is left alone before the next attempt. ensureEngine()
    // runs once per frame, so without a wait a context that cannot host the engine - the state a
    // return from the background can leave behind - is retried sixty times a second forever.
    static constexpr double kCreateRetrySeconds = 2.0;
    static constexpr int kDiagFrames = 90;
    static constexpr int kDiagWarmup = 20;
    static constexpr int kPcmCapacity = 8192;
    static constexpr int kEnginePcmSamples = 576;
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
