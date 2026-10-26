#include "geode/viz/Renderer.hpp"

#include <algorithm>
#include <cmath>

#include "CompositeGrade.hpp"
#include "SharedParams.hpp"
#include "geode/viz/LiveSignal.hpp"
#include "geode/render/Quad.hpp"

namespace geode::viz {

namespace {
constexpr float kTouchMinOverlayStrength = 0.35f;

// Port of applyBandGains: the per-scene band trims, clamped as the Kotlin path clamps them.
GeodeFeatureFrame gainAdjusted(const GeodeFeatureFrame& f, const SceneParams& p) {
    if (p.bassGain == 1.0f && p.midGain == 1.0f && p.trebGain == 1.0f) return f;
    GeodeFeatureFrame out = f;
    out.bass = std::clamp(f.bass * p.bassGain, 0.0f, 2.0f);
    out.mid = std::clamp(f.mid * p.midGain, 0.0f, 2.0f);
    out.treble = std::clamp(f.treble * p.trebGain, 0.0f, 2.0f);
    return out;
}
}  // namespace

float Renderer::beginFrame(double timeSeconds) {
    resetFrameState();
    frameNowS_ = timeSeconds;
    const double elapsed = lastTimeS_ < 0.0 ? 1.0 / 60.0 : timeSeconds - lastTimeS_;
    const float dt = static_cast<float>(std::clamp(elapsed, 0.001, 0.1));
    lastTimeS_ = timeSeconds;
    thermal_.onFrame(dt);
    timeSeconds_ = std::fmod(timeSeconds_ + dt, kTimeWrapSeconds);
    {
        std::lock_guard<std::mutex> lock(stateLock_);
        frameFeatures_ = features_;
        frameCustomization_ = requestedCustomization_;
        frameLayerMix_ = layerMix_;
        frameLayerBlend_ = layerBlend_;
        frameTransitionId_ = transitionId_;
        frameTransitionDurationMs_ = transitionDurationMs_;
    }
    applyMilkRequests();
    applyOverlayUploads();
#ifdef GEODE_STYLE_HOT_RELOAD
    pollStyleLab();
#endif
    return dt;
}

Scene* Renderer::resolveActiveScene() {
    std::string requestedId;
    {
        std::lock_guard<std::mutex> lock(stateLock_);
        requestedId = requestedSceneId_;
    }
    Scene* requested = sceneFor(requestedId);
    sceneJustSwitched_ = false;
    if (requested && requested != activeScene_) {
        lastTimeS_ = frameNowS_;
        const bool cuts = TransitionCatalog::builtIn(frameTransitionId_) == TransitionStyle::Cut;
        if (!cuts && activeScene_) {
            outgoingScene_ = activeScene_;
            outgoingParams_ = lastFinalParams_;
            transitionStartS_ = frameNowS_;
        }
        activeScene_ = requested;
        sceneJustSwitched_ = true;
    }
    return activeScene_;
}

SceneParams Renderer::resolveParams(float dt) {
    SceneParams requested;
    float morph;
    {
        std::lock_guard<std::mutex> lock(stateLock_);
        requested = requestedParams_;
        if (morphRemainSec_ > 0.0f) {
            morphRemainSec_ -= dt;
            morph = morphFadeSec_;
        } else {
            morph = 0.0f;
        }
    }
    const float fade = std::max(requested.paramFadeSec, morph);
    displayedParams_ = fade <= 0.01f ? requested : lerpParams(displayedParams_, requested, std::clamp(dt / fade, 0.0f, 1.0f));
    const auto& envValues = adsr_.tick(dt, frameFeatures_);
    AdsrEngine::lfoOffsets(adsr_.configs, envValues, envRate_, envDepth_);
    const auto& lfoValues = lfo_.tick(dt, frameFeatures_, envRate_.data(), envDepth_.data());
    SceneParams p = lfo_.apply(displayedParams_, lfoValues);
    p = AdsrEngine::apply(p, adsr_.configs, envValues);
    const bool reducedMotion = reducedMotion_.load(std::memory_order_relaxed);
    applyCustomization(p, dt, reducedMotion);
    p = safety::apply(p, reducedMotion);
    if (!thermalTierInfo(thermal_.tier()).optionalPasses) {
        p.flowEnabled = false;
        p.rippleOverlayEnabled = false;
    }
    lastFinalParams_ = p;
    postRotationAngle_ = grade::integrateRotation(postRotationAngle_, p.rotation, dt);
    postCyclePhase_ = grade::integrateCyclePhase(postCyclePhase_, p.cycleSpeed, dt, p.colorCycle);
    postBeatPulse_ = postBeat_.step(live::hit(frameFeatures_), dt);
    return p;
}

void Renderer::applyCustomization(SceneParams& p, float dt, bool reducedMotion) {
    const std::string sceneId = activeScene_ != nullptr ? activeScene_->id() : std::string();
    std::optional<mod::NativeFrame> shared;
    if (frameCustomization_) {
        // Normalised to the active Style's ranges before anything is drawn; the clamp runs last.
        shared = mod::toNative(rangesFor(sceneId), *frameCustomization_);
        applySharedCore(p, *shared);
    }
    frameEffects_ = shared ? SharedEffects::from(*shared) : SharedEffects{};
    camera_.step(shared ? CameraInput::from(*shared) : CameraInput{}, live::hit(frameFeatures_), dt, reducedMotion);
    activeExtras_ = extrasFor(sceneId, shared ? &*shared : nullptr);
}

const mod::FamilyRanges& Renderer::rangesFor(const std::string& sceneId) {
    if (sceneId == rangesSceneId_) return ranges_;
    rangesSceneId_ = sceneId;
    ranges_ = mod::FamilyRanges::sharedCore();
    if (const packs::StylePack* pack = registry_.pack(sceneId); pack != nullptr) extras::declare(ranges_, *pack);
    return ranges_;
}

ExtraValues Renderer::extrasFor(const std::string& sceneId, const mod::NativeFrame* frame) const {
    return extras::of(registry_.pack(sceneId), frame);
}

void Renderer::prepareScene(Scene& scene, const ExtraValues& extras) {
    scene.setCamera(camera_.pose());
    scene.setExtras(extras);
    scene.setQuality(qualityFor(scene.id()));
}

quality::Budget Renderer::qualityFor(const std::string& sceneId) const {
    const packs::StylePack* pack = registry_.pack(sceneId);
    const quality::Budgets& budgets = pack != nullptr ? pack->tiers : quality::defaults();
    return budgets[static_cast<size_t>(thermal_.tier())];
}

void Renderer::resolveLayerScene() {
    std::string layerId;
    std::string requestedId;
    {
        std::lock_guard<std::mutex> lock(stateLock_);
        layerId = layerSceneId_;
        requestedId = requestedSceneId_;
    }
    layerScene_ = nullptr;
    if (outgoingScene_ || layerId.empty() || layerId == requestedId) return;
    Scene* layer = sceneFor(layerId);
    if (layer && layer != activeScene_) layerScene_ = layer;
}

bool Renderer::ensureTargets() {
    // Allocates on first use; after that applyRenderSize has already moved them, keeping the picture.
    if (!fboA_.resizePreserving(renderWidth_, renderHeight_)) return false;
    if (fboA_.width() != renderWidth_ || fboA_.height() != renderHeight_) return false;
    if (!fboB_.resizePreserving(renderWidth_, renderHeight_) || fboB_.width() != renderWidth_ || fboB_.height() != renderHeight_) {
        layerScene_ = nullptr;
        outgoingScene_ = nullptr;
        outgoingParams_.reset();
    }
    return true;
}

void Renderer::deliverPcm(Scene& scene) {
    // Copy out under the lock, then hand the scene its own scratch buffer
    // once unlocked: acceptPcm() (a copy of up to 4096 samples plus
    // fillPcmRow) must never run while stateLock_ is held, or pushPcm() on
    // the PCM producer thread blocks behind a scene upload.
    int count = 0;
    {
        std::lock_guard<std::mutex> lock(stateLock_);
        count = pcmCount_;
        if (count > 0) std::copy(pcm_.begin(), pcm_.begin() + count, pcmDeliverScratch_.begin());
    }
    if (count > 0) scene.acceptPcm(pcmDeliverScratch_.data(), count);
}

void Renderer::bindSecondaryTarget() {
    glBindFramebuffer(GL_FRAMEBUFFER, fboB_.fbo());
    glViewport(0, 0, renderWidth_, renderHeight_);
    glClear(GL_COLOR_BUFFER_BIT);
}

float Renderer::drawSecondaryTargets(const SceneParams& p, float dt) {
    float progress = 1.0f;
    if (layerScene_) {
        bindSecondaryTarget();
        wireFlow(*layerScene_, p);
        prepareScene(*layerScene_, extrasFor(layerScene_->id(), nullptr));
        layerScene_->setParams(p);
        deliverPcm(*layerScene_);
        layerScene_->update(gainAdjusted(frameFeatures_, p), dt);
        layerScene_->draw(timeSeconds_);
    }
    if (outgoingScene_) {
        progress = std::clamp(static_cast<float>((frameNowS_ - transitionStartS_) * 1000.0 / frameTransitionDurationMs_), 0.0f, 1.0f);
        if (progress >= 1.0f) {
            outgoingScene_ = nullptr;
            outgoingParams_.reset();
        } else {
            bindSecondaryTarget();
            const SceneParams& op = outgoingParams_ ? *outgoingParams_ : p;
            prepareScene(*outgoingScene_, extrasFor(outgoingScene_->id(), nullptr));
            outgoingScene_->setParams(op);
            deliverPcm(*outgoingScene_);
            outgoingScene_->update(gainAdjusted(frameFeatures_, op), dt);
            outgoingScene_->draw(timeSeconds_);
        }
    }
    return progress;
}

void Renderer::drawSceneTarget(Scene& scene, const SceneParams& p, float dt) {
    glBindFramebuffer(GL_FRAMEBUFFER, fboA_.fbo());
    glViewport(0, 0, renderWidth_, renderHeight_);
    const float keep = scene.trailRetention(p);
    if (keep > 0.0f && !sceneJustSwitched_) {
        trailPass_.apply(p, keep, timeSeconds_, dt, fboA_, quadVao_, renderWidth_, renderHeight_);
    } else {
        glClear(GL_COLOR_BUFFER_BIT);
    }
    wireFlow(scene, p);
    prepareScene(scene, activeExtras_);
    scene.setParams(p);
    deliverPcm(scene);
    scene.update(gainAdjusted(frameFeatures_, p), dt);
    scene.draw(timeSeconds_);
}

GLuint Renderer::applySharedEffects(float dt) {
    if (!frameEffects_.active()) {
        sharedFx_.forgetHistory();
        return fboA_.tex();
    }
    // A new Style starts its trails afresh rather than echoing the old one.
    if (sceneJustSwitched_) sharedFx_.forgetHistory();
    return sharedFx_.apply(frameEffects_, fboA_.tex(), renderWidth_, renderHeight_, dt, timeSeconds_, quadVao_);
}

void Renderer::composite(Scene& scene, const SceneParams& p, float progress, GLuint sceneTex, GLuint targetFbo, float dt) {
    // The composite draws into the HDR target, the final pass blooms and tonemaps it into the No-flash
    // limiter's candidate target, and the limiter draws the frame that is shown. Only if the HDR
    // target cannot be made does the composite draw (and dither) the final picture itself, and only if
    // the limiter's targets cannot be made does the final picture go straight out.
    const bool hdr = hdrPass_.ensureComposite(width_, height_);
    const bool limited = noFlash_.bindCandidate(width_, height_);
    const GLuint finalFbo = limited ? noFlash_.candidateFbo() : targetFbo;
    glBindFramebuffer(GL_FRAMEBUFFER, hdr ? hdrPass_.compositeFbo() : finalFbo);
    glViewport(0, 0, width_, height_);
    glDisable(GL_BLEND);
    CompositePass::Inputs& in = compositeInputs_;
    in.texA = sceneTex;
    in.texB = fboB_.tex();
    GLuint flowTex = compositePass_.zeroTex();
    float flowStrength = 0.0f;
    if (p.flowEnabled) {
        fluid::FlowField* ff = overlays_.flow();
        if (const GLuint own = scene.velocityTexture(); own != 0) {
            flowTex = own;
            flowStrength = p.flowStrength;
        } else if (ff && ff->available()) {
            flowTex = ff->velocityTex();
            flowStrength = p.flowStrength;
        }
    }
    in.flowTex = flowTex;
    in.flowStrength = flowStrength;
    fluid::RippleSim* ripple = overlays_.ripple();
    const bool rippleOn = rippleOverlayOn_ && ripple != nullptr;
    in.rippleTex = rippleOn ? ripple->heightTex() : compositePass_.zeroTex();
    in.rippleTexelW = rippleOn ? ripple->texelW() : 0.0f;
    in.rippleTexelH = rippleOn ? ripple->texelH() : 0.0f;
    const float rippleStrength = smearing_ ? std::max(p.rippleOverlayStrength, kTouchMinOverlayStrength) : p.rippleOverlayStrength;
    in.rippleStrength = rippleOn ? std::clamp(rippleStrength, 0.0f, 1.0f) : 0.0f;
    in.rippleSpecular = rippleOn ? std::clamp(p.rippleOverlaySpecular, 0.0f, 1.0f) : 0.0f;
    in.progress = progress;
    in.layerMix = safety::layerMix(frameLayerMix_, blendModeFromOrdinal(frameLayerBlend_));
    in.blendOrdinal = frameLayerBlend_;
    in.hasLayer = layerScene_ != nullptr;
    in.hasOutgoing = outgoingScene_ != nullptr;
    in.transitionId = frameTransitionId_;
    in.transitionStyle = safety::transitionStyle(TransitionCatalog::builtIn(frameTransitionId_).value_or(TransitionStyle::Fade));
    in.ratio = static_cast<float>(renderWidth_) / static_cast<float>(renderHeight_);
    in.timeSeconds = timeSeconds_;
    const float hit = live::hit(frameFeatures_);
    in.hitImpulse = hit;
    const SceneParams& fx = lastFinalParams_;
    in.postRotationAngle = postRotationAngle_;
    in.postCyclePhase = postCyclePhase_;
    in.postBeatPulse = postBeatPulse_;
    in.quadVao = quadVao_;
    in.fx = fx;
    in.gateA = grade::gateFor(activeScene_->family()).toVec4();
    Scene* other = layerScene_ ? layerScene_ : outgoingScene_ ? outgoingScene_ : activeScene_;
    in.gateB = grade::gateFor(other->family()).toVec4();
    // The Camera's flat moves for every picture whose Style does not move a camera of its own, and the
    // flat endless zoom for the ones whose renderer has none.
    in.camera = camera_.pose();
    in.cameraGate = {activeScene_->ownsCamera() ? 0.0f : 1.0f, other->ownsCamera() ? 0.0f : 1.0f,
                     activeScene_->family() == SceneFamily::Fluid ? 1.0f : 0.0f,
                     other->family() == SceneFamily::Fluid ? 1.0f : 0.0f};
    // W00: read this frame's latched underlay straight from CompositePass (it owns the texture -
    // see CompositePass.hpp), the same way uFlow/uRipple above are read from Overlays.
    in.underlayTex = compositePass_.underlayTexOrZero();
    in.underlayBlend = compositePass_.underlayBlendMode();
    in.underlayAmount = compositePass_.underlayAmount();
    anaglyphMix_ = sharedfx::anaglyphMix(anaglyphMix_, frameEffects_.anaglyph, dt);
    in.anaglyph = anaglyphMix_;
    in.dither = !hdr;
    compositePass_.draw(in);
    if (hdr) hdrPass_.resolve(tone::bloomFor(p.bloom, thermalTierInfo(thermal_.tier()).optionalPasses), finalFbo, quadVao_);
    // The offscreen Render waits for each frame's own measurement, so every frame it writes is the one asked for.
    if (limited) noFlash_.present(dt, targetFbo, quadVao_, thermal_.offscreen());
    // W00: overlay is its own draw call, over whatever composite() just wrote to targetFbo, so it
    // never enters postFx/transitions/Layers and comes out identical live, in the wallpaper and in
    // the offscreen export.
    compositePass_.drawOverlay(quadVao_);
}

void Renderer::stepOverlays(Scene& scene, const SceneParams& p, float dt) {
    if (overlays_.wantsFlow(p, scene.isFluid())) overlays_.stepFlow(gainAdjusted(frameFeatures_, p), dt, p);
    smearing_ = overlays_.smearing(monotonicSeconds());
    // Stepped once per frame ahead of every draw so each scene reads the same anchor.
    touchField_.step(dt);
    overlays_.drainTouchStrokes(scene);
    rippleOverlayOn_ = overlays_.rippleOverlayActive(p, smearing_, scene.isWater());
    if (rippleOverlayOn_) overlays_.stepRippleOverlay(gainAdjusted(frameFeatures_, p), p, dt);
}

void Renderer::wireFlow(Scene& target, const SceneParams& p) {
    fluid::FlowField* ff = overlays_.flow();
    if (p.flowEnabled && ff) {
        target.setFlow(ff->available() ? ff->velocityTex() : compositePass_.zeroTex(), p.flowStrength);
    } else {
        target.setFlow(compositePass_.zeroTex(), 0.0f);
    }
}

void Renderer::render(double timeSeconds, GLuint targetFbo) {
    const float dt = beginFrame(timeSeconds);
    Scene* scene = resolveActiveScene();
    updateRenderSize(dt, scene);
    if (!scene || !ensureTargets()) {
        glBindFramebuffer(GL_FRAMEBUFFER, targetFbo);
        glViewport(0, 0, width_, height_);
        glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
        glClear(GL_COLOR_BUFFER_BIT);
        return;
    }
    const SceneParams p = resolveParams(dt);
    resolveLayerScene();
    stepOverlays(*scene, p, dt);
    const float progress = drawSecondaryTargets(p, dt);
    drawSceneTarget(*scene, p, dt);
    const GLuint sceneTex = applySharedEffects(dt);
    composite(*scene, p, progress, sceneTex, targetFbo, dt);
}

}  // namespace geode::viz
