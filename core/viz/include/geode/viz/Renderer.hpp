#pragma once
#include <GLES3/gl3.h>
#include <android/asset_manager.h>

#include <array>
#include <atomic>
#include <memory>
#include <mutex>
#include <optional>
#include <string>
#include <vector>

#include "geode/api/geode_api.h"
#include "geode/viz/Adsr.hpp"
#include "geode/viz/CompositePass.hpp"
#include "geode/viz/FormDrive.hpp"
#include "geode/render/Framebuffer.hpp"
#include "geode/render/GlProfile.hpp"
#include "geode/viz/Lfo.hpp"
#include "geode/viz/Overlays.hpp"
#include "geode/viz/Params.hpp"
#include "geode/viz/RenderScale.hpp"
#include "geode/render/ProgramBinaryCache.hpp"
#include "geode/viz/Scene.hpp"
#include "geode/viz/SceneRegistry.hpp"
#include "geode/render/ShaderSource.hpp"
#include "geode/viz/ThermalGovernor.hpp"
#include "geode/viz/TouchField.hpp"
#include "geode/viz/TrailPass.hpp"
#include "geode/viz/VisualSafety.hpp"
#include "geode/viz/scenes/ProgramLoader.hpp"

namespace geode::viz {

// Port of VisualizerRenderer.kt's frame graph: scene pass -> trail -> composite -> target.
class Renderer {
public:
    static constexpr float kTimeWrapSeconds = 7100.0f;

    Renderer(AAssetManager* assets, std::string cacheDir);
    ~Renderer();

    // Any thread.
    void setParams(const SceneParams& params);
    bool setParam(const std::string& key, float value);
    void setFeatures(const GeodeFeatureFrame& features);
    void setReducedMotion(bool on) { reducedMotion_.store(on, std::memory_order_relaxed); }
    void setLayer(const std::string& sceneId, float mix, int blendOrdinal);
    void setTransition(const std::string& id, int64_t durationMs);
    void beginParamMorph(float seconds);
    void submitTouchPoints(const float* xy, int n) { touchField_.submit(xy, n); }
    void pushPcm(const float* samples, int count);
    void setCustomShader(const std::string& sceneId, const std::string& fragmentSource);
    // The user source a scene last compiled successfully; "" when it draws its built-in style.
    std::string customShaderFor(const std::string& sceneId) const;
    void queueTouchStroke(float nx, float ny, float ndx, float ndy, float dt, float strength);
    void setFluidInjectionShaders(const std::string& forceSrc, const std::string& dyeSrc);
    void loadMilkPreset(const std::string& path);
    void reloadMilkPreset();
    void setMilkTextureDir(const std::string& dir);
    // The path of the preset the MilkDrop scene last compiled, handed out once.
    std::string takeMilkPresetLoaded();
    void setLfoConfigs(const std::array<LfoConfig, LfoEngine::kSlots>& configs);
    void setAdsrConfigs(const std::array<AdsrConfig, AdsrEngine::kCount>& configs);
    // W00: full-frame overlay/underlay layers, latched here and uploaded to GL textures at the
    // start of the next frame; see CompositePass.hpp for the pass itself. `pixels` is Android's
    // Bitmap.getPixels ARGB layout; null (or a non-positive size) clears the layer.
    void setOverlayRgba(const uint32_t* pixels, int width, int height);
    void setUnderlayRgba(const uint32_t* pixels, int width, int height, int blend, float amount);
    ThermalGovernor& thermal() { return thermal_; }
    // Any thread: the frame pacer's latest window (frames in it, 95th percentile interval in ms),
    // which the load scale follows. Sampled by the host about once a second.
    void setFrameLoad(int frames, float p95Ms);
    // Any thread: returns a copy (see Renderer.cpp) since fail() mutates lastError_ concurrently.
    std::string lastError() const;
    bool knows(const std::string& sceneId) const { return registry_.knows(sceneId); }
    std::vector<std::string> availableSceneIds() const { return registry_.availableIds(); }

    // GL thread.
    void onSurfaceCreated();
    void surfaceChanged(int width, int height);
    bool setScene(const std::string& sceneId);
    void warmTransition(const std::string& id);
    void cut();
    void render(double timeSeconds, GLuint targetFbo);
    void releaseScenes();
    const GlProfile& profile() const { return profile_; }

private:
    struct Live {
        std::unique_ptr<Scene> scene;
        SceneParams params;
    };

    Scene* sceneFor(const std::string& id);
    Scene* builtScene(const std::string& id);
    std::unique_ptr<Scene> buildScene(const std::string& id);
    float beginFrame(double timeSeconds);
    // Steps the load scale and resizes the scene targets when the render size it gives has moved.
    void updateRenderSize(float dt, const Scene* scene);
    scale::ScaleInputs scaleInputs(const Scene* scene);
    // Moves every scene target to `size`, keeping the picture (Framebuffer::resizePreserving).
    void applyRenderSize(scale::RenderSize size);
    // The render scale the Style's manifest declares; read once per Style.
    float styleScaleFor(const std::string& sceneId);
    Scene* resolveActiveScene();
    SceneParams resolveParams(float dt);
    void feedFormDrive();
    void resolveLayerScene();
    bool ensureTargets();
    float drawSecondaryTargets(const SceneParams& p, float dt);
    void bindSecondaryTarget();
    void drawSceneTarget(Scene& scene, const SceneParams& p, float dt);
    void composite(Scene& scene, const SceneParams& p, float progress, GLuint targetFbo);
    void deliverPcm(Scene& scene);
    void stepOverlays(Scene& scene, const SceneParams& p, float dt);
    void wireFlow(Scene& target, const SceneParams& p);
    void applyPendingFluidInjection();
    void applyMilkRequests();
    void applyOverlayUploads();
    void notePresetLoaded(const std::string& path);
    static double monotonicSeconds();
    void fail(const std::string& message);
    void rememberCustomShader(const std::string& sceneId, const std::string& source);

    ShaderSource assets_;
    std::string cacheDir_;
    ProgramBinaryCache programCache_;
    DeviceGl deviceGl_;
    GlProfile profile_ = GlProfile::unprobed();
    ProgramLoader loader_{assets_, &programCache_};
    SceneRegistry registry_;
    Overlays overlays_{loader_};
    std::vector<std::pair<std::string, std::unique_ptr<Scene>>> scenes_;
    std::string quadVert_;

    TouchField touchField_;
    TrailPass trailPass_;
    CompositePass compositePass_;
    CompositePass::Inputs compositeInputs_;
    FlashBudget flashBudget_;
    ThermalGovernor thermal_;
    scale::LoadScaleController loadScale_;
    std::vector<std::pair<std::string, float>> styleScales_;
    LfoEngine lfo_;
    AdsrEngine adsr_;
    FormDrive formDrive_;
    Framebuffer fboA_{"sceneA"};
    Framebuffer fboB_{"sceneB"};
    GLuint quadVao_ = 0;
    GLuint paletteLutTex_ = 0;

    mutable std::mutex stateLock_;
    SceneParams requestedParams_;
    GeodeFeatureFrame features_{};
    std::string requestedSceneId_ = "silk_web";
    std::string layerSceneId_;
    float layerMix_ = 0.5f;
    int layerBlend_ = static_cast<int>(BlendMode::Screen);
    std::string transitionId_ = "fade";
    int64_t transitionDurationMs_ = 1200;
    std::atomic<bool> reducedMotion_{false};
    scale::FrameLoad frameLoad_;
    float morphFadeSec_ = 0.0f;
    float morphRemainSec_ = 0.0f;
    std::vector<float> pcm_;
    int pcmCount_ = 0;
    // Bumped by every pushPcm; what tells the frame a block is new rather than
    // the one it already read (pcmCount_ is never cleared, by design: the
    // scenes keep drawing the last waveform between pushes).
    unsigned int pcmSerial_ = 0;
    unsigned int pcmSerialSeen_ = 0;
    std::vector<float> pcmScratch_;
    std::vector<float> pcmDeliverScratch_;
    std::vector<std::pair<std::string, std::string>> pendingShaders_;
    std::vector<std::pair<std::string, std::string>> customShaders_;
    std::string fluidForceSrc_;
    std::string fluidDyeSrc_;
    bool fluidInjectionDirty_ = false;
    // W00: overlay/underlay layers. Retained (not just "pending") so a surface recreation can
    // re-arm the dirty flag and re-upload, the same way fluidForceSrc_/fluidDyeSrc_ do above;
    // see onSurfaceCreated() and applyOverlayUploads(). Empty = cleared.
    std::vector<uint32_t> overlayPixels_;
    int overlayWidth_ = 0;
    int overlayHeight_ = 0;
    bool overlayDirty_ = false;
    std::vector<uint32_t> underlayPixels_;
    int underlayWidth_ = 0;
    int underlayHeight_ = 0;
    int underlayBlend_ = 0;
    float underlayAmount_ = 0.0f;
    bool underlayDirty_ = false;
    std::string lastMilkPreset_;
    std::string milkPresetRequest_;
    bool milkReloadRequested_ = false;
    std::string milkTextureDir_;
    bool milkTextureDirDirty_ = false;
    std::string milkPresetLoaded_;
    bool rippleOverlayOn_ = false;
    bool smearing_ = false;

    SceneParams displayedParams_;
    SceneParams lastFinalParams_;
    std::array<float, LfoEngine::kSlots> envRate_{};
    std::array<float, LfoEngine::kSlots> envDepth_{};
    float postRotationAngle_ = 0.0f;
    float postCyclePhase_ = 0.0f;
    float postBeatPulse_ = 0.0f;
    Scene* activeScene_ = nullptr;
    Scene* outgoingScene_ = nullptr;
    Scene* layerScene_ = nullptr;
    std::optional<SceneParams> outgoingParams_;
    double transitionStartS_ = 0.0;
    bool sceneJustSwitched_ = false;
    int width_ = 1;
    int height_ = 1;
    int renderWidth_ = 1;
    int renderHeight_ = 1;
    double lastTimeS_ = -1.0;
    double frameNowS_ = 0.0;
    float timeSeconds_ = 0.0f;
    GeodeFeatureFrame frameFeatures_{};
    // Latched once per frame in beginFrame alongside frameFeatures_, so the
    // rest of the frame (composite(), drawSecondaryTargets(), ...) reads a
    // stable snapshot instead of racing setLayer()/setTransition() on
    // stateLock_.
    float frameLayerMix_ = 0.5f;
    int frameLayerBlend_ = static_cast<int>(BlendMode::Screen);
    std::string frameTransitionId_ = "fade";
    int64_t frameTransitionDurationMs_ = 1200;
    std::string lastError_;
};

}  // namespace geode::viz
