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
#include "geode/viz/MilkdropAdaptation.hpp"
#include "geode/viz/Scene.hpp"
#include "geode/viz/scenes/ProgramLoader.hpp"

namespace geode::viz {

// One projectM instance drawing its frame straight into the scene target the renderer hands it
// (projectm_opengl_render_frame_fbo, backported onto v4.1.8 by
// tools/projectm-v4.1.8-render-fbo-backport.patch). The composite grades it like the fluid family,
// palette tint included (CompositeGrade.hpp): there is no post pass of its own (#303). The engine never
// touches the window surface, so the scene is independent of the EGL config.
//
// The engine buffer is the scene target's size (resize(), which follows the quality and thermal
// scale and is held to scale::kMilkdropMaxMegapixels) times the load adapter's buffer step. Live, it
// adapts to load mesh-first: the warp mesh steps down at once, the buffer only at the next preset change
// (milkdrop::LoadAdapter), and a new target size waits for one too unless its shape changed
// (milkdrop::resizeNow). While the engine and the target differ in size, the frame goes through the
// scene's own framebuffer and is scaled onto the target; otherwise projectM draws onto the target
// itself. projectM is told the real paced frame rate. During a Render the engine is rebuilt seeded and
// driven by the render clock (projectm_set_frame_time, tools/projectm-v4.1.8-render-clock.patch) at full
// detail, so the same input gives the same frames.
class MilkdropScene : public Scene {
public:
    MilkdropScene(std::string id, ProgramLoader loader, SceneHost host)
        : id_(std::move(id)), loader_(loader), host_(std::move(host)), pcm_(kPcmCapacity, 0.0f) {}
    ~MilkdropScene() override { release(); }

    const std::string& id() const override { return id_; }
    SceneFamily family() const override { return SceneFamily::Milkdrop; }
    void init() override;
    void setParams(const SceneParams& params) override { params_ = params; }
    void resize(int width, int height) override;
    void update(const GeodeFeatureFrame& features, float dt) override;
    void draw(float timeSeconds) override;
    void release() override;
    // The samples heard since the last frame; update() hands projectM the loudest window of them
    // (milkdrop::loudestWindow), so no kick falls between the windows it measures.
    void acceptPcm(const float* samples, int count) override;
    void queueMilkPreset(const std::string& path) override;
    void reloadMilkPreset() override;
    void setMilkTextureDir(const std::string& dir) override;
    void setDrawTarget(GLuint fbo, int width, int height) override;
    // The fingers on the Visual: presets that read `mouse` follow the first one (projectm_set_pointer).
    void setTouchField(const TouchField* field) override { touch_ = field; }
    void setFeatureBus(const FeatureValues& values) override { bus_ = values; }
    // Geode's lean-in on a trusted downbeat and a drop (#294), which the composite applies now that
    // MilkDrop's own post pass went into it (#303); the preset's variables are left alone.
    float pictureZoom() const override;

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
    // How much of projectM's reason for a shader fallback is kept.
    static constexpr size_t kFallbackReasonLength = 160;
    static constexpr int kPcmCapacity = 8192;
    static constexpr int kEnginePcmSamples = milkdrop::kWindowFrames;

    struct EngineDeleter {
        void operator()(projectm_handle h) const;
    };
    using Engine = std::unique_ptr<std::remove_pointer_t<projectm_handle>, EngineDeleter>;

    static void onPresetSwitchFailed(const char* presetFilename, const char* message, void* userData);
    // A preset that loads but plays without its warp or composite shader (projectM's shader fallback
    // callback, tools/projectm-v4.1.8-shader-extras.patch): reported, never silent.
    static void onShaderFallback(const char* presetFilename, const char* shader, const char* message, void* userData);
    // projectM's shader caches (projectm_opengl_set_shader_caches, tools/projectm-v4.1.8-program-cache.patch),
    // over the renderer's ProgramBinaryCache: the linked programs as binaries, the translated text as
    // files, so a preset seen before loads without translating or compiling (#304).
    static bool loadProgram(const char* vertexSource, const char* fragmentSource, uint32_t program, void* userData);
    static void storeProgram(const char* vertexSource, const char* fragmentSource, uint32_t program, void* userData);
    static const char* lookupText(const char* key, size_t keyLength, void* userData);
    static void storeText(const char* key, size_t keyLength, const char* text, void* userData);
    // Points projectM's caches on this thread at this scene for as long as it lives (the length of a draw).
    class CachesInUse {
    public:
        explicit CachesInUse(MilkdropScene* scene);
        ~CachesInUse();
        CachesInUse(const CachesInUse&) = delete;
        CachesInUse& operator=(const CachesInUse&) = delete;
    };
    static double nowSeconds();
    void ensureEngine(double now);
    // Re-queues the preset the scene last had on screen and clears the load debounce, so a
    // freshly created engine loads it on the very next frame instead of waiting one window out.
    void armLastPreset();
    void loadPendingPreset(double now);
    // Enters or leaves Render mode when the host's flag changes; the engine is rebuilt either way.
    void followRenderMode();
    // Sizes the engine buffer from the scene target at the adapter's buffer step: at once when the
    // engine has no size or the target's shape changed, otherwise only `atPresetChange`.
    void applyEngineSize(bool atPresetChange);
    // Mesh, frame rate and clock for the frame about to render.
    void applyFrameSettings();
    std::optional<std::string> takeError();
    std::optional<std::string> takeFallback();
    void diagnoseBlackFrame();
    void drainEngineErrors();

    std::string id_;
    ProgramLoader loader_;
    SceneHost host_;
    std::vector<float> pcm_;
    int pcmCount_ = 0;
    Engine engine_;
    int width_ = 0;
    int height_ = 0;
    bool reportedCreateFailure_ = false;
    double lastCreateAttemptSeconds_ = 0.0;
    Framebuffer frame_{"milkdrop"};
    int engineWidth_ = 0;
    int engineHeight_ = 0;
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
    // The text lookupText handed projectM last; it must outlive the call.
    std::string keptText_;
    const TouchField* touch_ = nullptr;
    std::mutex errorLock_;
    std::string lastError_;
    // What the last preset load fell back from, guarded by errorLock_.
    std::string lastFallback_;
    // The last message written to logcat, so a preset that fails on every frame it is retried
    // writes one line rather than one line per frame. Guarded by errorLock_.
    std::string lastLoggedError_;
};

}  // namespace geode::viz
