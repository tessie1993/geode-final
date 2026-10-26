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
#include "geode/viz/BeatEnvelope.hpp"
#include "geode/viz/CompositePass.hpp"
#include "geode/render/Framebuffer.hpp"
#include "geode/render/GlProfile.hpp"
#include "geode/viz/Lfo.hpp"
#include "geode/viz/NoFlashPass.hpp"
#include "geode/viz/Overlays.hpp"
#include "geode/viz/Params.hpp"
#include "geode/viz/RenderScale.hpp"
#include "geode/render/ProgramBinaryCache.hpp"
#include "geode/viz/Scene.hpp"
#include "geode/viz/SceneRegistry.hpp"
#include "geode/viz/SharedCamera.hpp"
#include "geode/viz/SharedEffects.hpp"
#include "geode/viz/SharedFxPass.hpp"
#include "geode/render/ShaderSource.hpp"
#ifdef GEODE_STYLE_HOT_RELOAD
#include "geode/viz/StyleLab.hpp"
#endif
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
    void queueTouchStroke(float nx, float ny, float ndx, float ndy, float dt, float strength);
    void loadMilkPreset(const std::string& path);
    void reloadMilkPreset();
    void setMilkTextureDir(const std::string& dir);
    // The path of the preset the MilkDrop scene last compiled, handed out once.
    std::string takeMilkPresetLoaded();
    void setLfoConfigs(const std::array<LfoConfig, LfoEngine::kSlots>& configs);
    void setAdsrConfigs(const std::array<AdsrConfig, AdsrEngine::kCount>& configs);
    // This frame's evaluated Customization (geode_viz_render runs the ReactionGraph), or nullptr while
    // no snapshot carries a plan; taken at the next frame. The renderer maps it into the active Style's
    // ranges (its pack's Extras) and draws all of it: the Shared core onto every Style and the
    // composite, the Camera (CameraRig), the Shared core effects (SharedFxPass) and the Style's Extras.
    void setCustomization(const mod::Evaluated* evaluated);
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
#ifdef GEODE_STYLE_HOT_RELOAD
    // Any thread, debug builds only (RendererStyleLab.cpp): the Style lab folder to watch, `<dir>/<id>/`
    // per Style; "" stops watching.
    void setStyleLab(const std::string& dir);
#endif

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
    // The render scale the Style's pack declares (its manifest's "renderScale").
    float styleScaleFor(const std::string& sceneId) const;
    Scene* resolveActiveScene();
    SceneParams resolveParams(float dt);
    void resolveLayerScene();
    bool ensureTargets();
    float drawSecondaryTargets(const SceneParams& p, float dt);
    void bindSecondaryTarget();
    void drawSceneTarget(Scene& scene, const SceneParams& p, float dt);
    // The active Style's picture with the Shared core effects drawn over it, or the scene target as
    // it is while every effect is 0.
    GLuint applySharedEffects(float dt);
    // The evaluated Customization in the active Style's units, onto `p`, the Camera and the effects.
    void applyCustomization(SceneParams& p, float dt, bool reducedMotion);
    // The ranges a Style's Customization maps into: the Shared core's, and its pack's Extras.
    const mod::FamilyRanges& rangesFor(const std::string& sceneId);
    // A Style's Extras: its pack's defaults, or `frame`'s values for the ones it declares.
    ExtraValues extrasFor(const std::string& sceneId, const mod::NativeFrame* frame) const;
    // Hands a scene this frame's Camera and its Extras before it draws.
    void prepareScene(Scene& scene, const ExtraValues& extras);
    void composite(Scene& scene, const SceneParams& p, float progress, GLuint sceneTex, GLuint targetFbo, float dt);
    void deliverPcm(Scene& scene);
    void stepOverlays(Scene& scene, const SceneParams& p, float dt);
    void wireFlow(Scene& target, const SceneParams& p);
    void applyMilkRequests();
    void applyOverlayUploads();
    void notePresetLoaded(const std::string& path);
    static double monotonicSeconds();
    void fail(const std::string& message);
#ifdef GEODE_STYLE_HOT_RELOAD
    // GL thread, from beginFrame: reloads each Style whose lab folder changed.
    void pollStyleLab();
    void reloadStyle(const std::string& id, const std::string& folder, const std::string& shaderFile);
    // Frees a built scene so the next frame builds it again from its pack.
    void dropScene(const std::string& id);
#endif

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
    SharedFxPass sharedFx_;
    CompositePass compositePass_;
    CompositePass::Inputs compositeInputs_;
    // The No-flash rule: every finished frame passes through it (CONTEXT.md).
    NoFlashPass noFlash_;
    ThermalGovernor thermal_;
    scale::LoadScaleController loadScale_;
    LfoEngine lfo_;
    AdsrEngine adsr_;
    Framebuffer fboA_{"sceneA"};
    Framebuffer fboB_{"sceneB"};
    GLuint quadVao_ = 0;
    GLuint paletteLutTex_ = 0;

    mutable std::mutex stateLock_;
    SceneParams requestedParams_;
    std::optional<mod::Evaluated> requestedCustomization_;
    GeodeFeatureFrame features_{};
    // Empty until the app's first setScene: the app requests its Style (SceneIds.DEFAULT, the one place
    // the default Style is chosen) before every frame, and until then the renderer draws black.
    std::string requestedSceneId_;
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
    // Never cleared, by design: the scenes keep drawing the last waveform
    // between pushes.
    int pcmCount_ = 0;
    std::vector<float> pcmDeliverScratch_;
    // W00: overlay/underlay layers. Retained (not just "pending") so a surface recreation can
    // re-arm the dirty flag and re-upload; see onSurfaceCreated() and applyOverlayUploads().
    // Empty = cleared.
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
    BeatEnvelope postBeat_;
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
    SharedEffects frameEffects_;
    std::optional<mod::Evaluated> frameCustomization_;
    CameraRig camera_;
    // The active Style's Extras this frame.
    ExtraValues activeExtras_ = ExtraValues::neutral();
    std::string rangesSceneId_;
    mod::FamilyRanges ranges_ = mod::FamilyRanges::sharedCore();
    // Latched once per frame in beginFrame alongside frameFeatures_, so the
    // rest of the frame (composite(), drawSecondaryTargets(), ...) reads a
    // stable snapshot instead of racing setLayer()/setTransition() on
    // stateLock_.
    float frameLayerMix_ = 0.5f;
    int frameLayerBlend_ = static_cast<int>(BlendMode::Screen);
    std::string frameTransitionId_ = "fade";
    int64_t frameTransitionDurationMs_ = 1200;
    std::string lastError_;
#ifdef GEODE_STYLE_HOT_RELOAD
    // Under stateLock_.
    std::string styleLabDir_;
    // GL thread only.
    lab::ReloadWatch styleLab_;
    bool styleLabFailed_ = false;
#endif
};

}  // namespace geode::viz
