#include "geode/viz/Renderer.hpp"

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <utility>

#include "CompositeGrade.hpp"
#include "SharedParams.hpp"
#include "geode/viz/LiveSignal.hpp"
#include "geode/viz/MilkdropAdaptation.hpp"
#include "geode/viz/StyleProfiles.hpp"
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
        frameTransitionId_ = transitionId_;
        frameTransitionDurationMs_ = transitionDurationMs_;
        frameTransitionSeed_ = transitionSeed_;
    }
    featureBus_.step(frameFeatures_, dt);
    frameBus_ = reducedMotion_.load(std::memory_order_relaxed) ? featurebus::calmed(featureBus_.values(), safety::kReducedMotionScale)
                                                               : featureBus_.values();
    latchFramePcm();
    applyMilkRequests();
    applyOverlayUploads();
#ifdef GEODE_STYLE_HOT_RELOAD
    pollStyleLab();
#endif
    return dt;
}

void Renderer::latchFramePcm() {
    frameSpanFrames_ = std::exchange(pendingPcmFrames_, 0);
    frameSpanFresh_ = std::exchange(pendingPcmFresh_, 0);
    std::copy(pendingPcm_.begin(), pendingPcm_.begin() + static_cast<std::ptrdiff_t>(frameSpanFrames_) * 2, frameSpan_.begin());
    // The window is the newest kPcmWindowFrames of the span.
    framePcmFrames_ = std::min(frameSpanFrames_, kPcmWindowFrames);
    const int first = frameSpanFrames_ - framePcmFrames_;
    for (int i = 0; i < framePcmFrames_; ++i) {
        const float left = frameSpan_[static_cast<size_t>(2 * (first + i))];
        const float right = frameSpan_[static_cast<size_t>(2 * (first + i) + 1)];
        framePcm_[static_cast<size_t>(2 * i)] = left;
        framePcm_[static_cast<size_t>(2 * i + 1)] = right;
        framePcmMono_[static_cast<size_t>(i)] = 0.5f * (left + right);
    }
}

Scene* Renderer::resolveActiveScene(float dt) {
    std::string requestedId;
    {
        std::lock_guard<std::mutex> lock(stateLock_);
        requestedId = requestedSceneId_;
    }
    // A Style shown for the first time is built on the frame it is first asked for (its programs compiled,
    // or read back from the binary cache), and its change can start on a later frame only, so the compile
    // never lands in the transition's first frame, the most visible one (#304). A Render does not wait: its
    // frames are counted, so it changes exactly as the gate says.
    const bool prepared = builtScene(requestedId) != nullptr;
    Scene* requested = sceneFor(requestedId);
    sceneJustSwitched_ = false;
    inheritTrails_ = false;
    if (!requested || requested == activeScene_) {
        switchGate_.cancel();
        return activeScene_;
    }
    // The first Style, or the first after a cut: drawn at once, from a clear picture.
    if (!activeScene_) {
        lastTimeS_ = frameNowS_;
        activeScene_ = requested;
        sceneJustSwitched_ = true;
        requested->beginVisit();
        return activeScene_;
    }
    // A change of Style, automatic or the person's, waits for the next downbeat while a tempo is heard
    // (#309). Its look is drawn when it is asked for, so the look's program is built while it waits.
    if (!switchGate_.waiting()) {
        pendingLookSlot_ = transition::lookSlot(frameTransitionId_, frameTransitionSeed_, looksDrawn_, lookSlot_);
        compositePass_.warmTransition(transition::kSafeLooks[static_cast<size_t>(pendingLookSlot_)]);
        switchGate_.arm();
    }
    // The frame that built the new Style only arms the wait; the downbeat is listened for from the next.
    if (!prepared && !thermal_.offscreen()) return activeScene_;
    if (switchGate_.step(frameFeatures_, dt)) startTransition(requested);
    return activeScene_;
}

void Renderer::startTransition(Scene* next) {
    lastTimeS_ = frameNowS_;
    outgoingScene_ = activeScene_;
    outgoingParams_ = lastFinalParams_;
    lookSlot_ = pendingLookSlot_;
    looksDrawn_++;
    frameLook_ = transition::kSafeLooks[static_cast<size_t>(lookSlot_)];
    transitionProgress_.start(static_cast<float>(frameTransitionDurationMs_) / 1000.0f);
    activeScene_ = next;
    sceneJustSwitched_ = true;
    inheritTrails_ = true;
    next->beginVisit();
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
    // Layer 2 draws its own Style as its pack tunes it, not Layer 1's Customization: the two pictures
    // then move each on their own instead of in lockstep. The Camera and the composite's Shared core
    // still apply to both.
    layerParams_ = p;
    const bool reducedMotion = reducedMotion_.load(std::memory_order_relaxed);
    applyCustomization(p, dt, reducedMotion);
    p = safety::apply(p, reducedMotion);
    layerParams_ = safety::apply(layerParams_, reducedMotion);
    if (!thermalTierInfo(thermal_.tier()).optionalPasses) {
        p.flowEnabled = false;
        p.rippleOverlayEnabled = false;
        layerParams_.flowEnabled = false;
        layerParams_.rippleOverlayEnabled = false;
    }
    lastFinalParams_ = p;
    stepLensEffects(dt);
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

void Renderer::stepLensEffects(float dt) {
    const packs::StylePack* pack = activeScene_ != nullptr ? registry_.pack(activeScene_->id()) : nullptr;
    const lens::LensSpec spec = pack != nullptr ? pack->lensEffects : lens::LensSpec{};
    const lens::ParticleSpec particles = pack != nullptr ? pack->particles3d : lens::ParticleSpec{};
    const int tier = static_cast<int>(thermal_.tier());
    const bool optionalPasses = thermalTierInfo(thermal_.tier()).optionalPasses;
    const bool reducedMotion = reducedMotion_.load(std::memory_order_relaxed);
    const lens::Drive drive{live::level(frameFeatures_), live::hit(frameFeatures_)};
    frameGlare_ = lensDrive_.step(spec, drive, tier, optionalPasses, reducedMotion, dt);
    frameDof_ = lens::dofFor(spec, tier, optionalPasses);
    frameParticles_ = particleFlight_.step(particles, drive, tier, reducedMotion, dt);
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
    if (gravityField_.idle()) {
        scene.setCamera(camera_.pose());
    } else {
        // Tilt turns the camera of a Style that moves one (uCamRot), never the finished picture: a
        // small look on top of the orbit, at most a few degrees and never a roll.
        CameraPose pose = camera_.pose();
        pose.rotation = gravity::multiply(pose.rotation, gravity::lookRotation(gravityField_.tiltX(), gravityField_.tiltY()));
        scene.setCamera(pose);
    }
    scene.setExtras(extras);
    scene.setFeatureBus(frameBus_);
    scene.setQuality(qualityFor(scene.id()));
}

quality::Budget Renderer::qualityFor(const std::string& sceneId) const {
    const packs::StylePack* pack = registry_.pack(sceneId);
    const quality::Budgets& budgets = pack != nullptr ? pack->tiers : quality::defaults();
    return budgets[static_cast<size_t>(thermal_.tier())];
}

void Renderer::resolveLayer(float dt) {
    layer::Request request;
    if (frameCustomization_) {
        const mod::Evaluated& evaluated = *frameCustomization_;
        request.spec = evaluated.layer;
        request.opacity = evaluated[mod::Shared::LayerOpacity];
        request.amount = evaluated[mod::Shared::LayerInfluence];
        request.swap = evaluated[mod::Shared::LayerSwap];
    }
    const std::string activeId = activeScene_ != nullptr ? activeScene_->id() : std::string();
    if (!canBeLayer(layerStyleId(request.spec.style), activeId)) request.spec.style = layer::kNoStyle;
    // At the hottest thermal tier the second Style is the first thing let go, slowly. A Render never
    // adapts (ADR-0005), so it keeps Layer 2.
    if (thermal_.tier() == ThermalTier::Minimal && !thermal_.offscreen() && request.spec.style != layer::kNoStyle) {
        request.spec.style = layer::kNoStyle;
        request.cooling = true;
    }
    // A Style that stops being drawable as Layer 2 (it became the active one) still fades out: its
    // scene is then Layer 1's, and its picture is read from there (layerTexture).
    frameLayer_ = layerMixer_.step(request, dt);
    Scene* const layer = frameLayer_.draws() ? sceneFor(layerStyleId(frameLayer_.spec.style)) : nullptr;
    // A Style that starts to be drawn as Layer 2 starts a visit; Layer 1's own Style is already on one.
    if (layer != nullptr && layer != layerScene_ && layer != activeScene_) layer->beginVisit();
    layerScene_ = layer;
}

const std::string& Renderer::layerStyleId(uint32_t name) {
    static const std::string kNone;
    if (name == layer::kNoStyle) return kNone;
    // The packs never change while the renderer lives, so the names are worked out once.
    if (layerIds_.empty()) {
        for (const std::string& id : registry_.availableIds()) layerIds_.emplace(layer::styleHash(id), id);
    }
    const auto found = layerIds_.find(name);
    return found == layerIds_.end() ? kNone : found->second;
}

bool Renderer::canBeLayer(const std::string& id, const std::string& activeId) const {
    if (id.empty() || id == activeId) return false;
    const packs::StylePack* pack = registry_.pack(id);
    return pack != nullptr && pack->family != styles::family::kMilkdrop;
}

bool Renderer::ensureTargets() {
    // Allocates on first use; after that applyRenderSize has already moved them, keeping the picture.
    if (!fboA_.resizePreserving(renderWidth_, renderHeight_)) return false;
    if (fboA_.width() != renderWidth_ || fboA_.height() != renderHeight_) return false;
    if (!fboB_.resizePreserving(renderWidth_, renderHeight_) || fboB_.width() != renderWidth_ || fboB_.height() != renderHeight_) {
        outgoingScene_ = nullptr;
        outgoingParams_.reset();
    }
    // Layer 2's own target is made when it draws (drawLayerTarget) and let go once it has gone.
    if (layerScene_ == nullptr && fboL_.ok()) {
        fboL_.release();
        layerDrawn_ = nullptr;
    }
    return true;
}

void Renderer::deliverPcm(Scene& scene) {
    if (framePcmFrames_ <= 0) return;
    scene.acceptPcm(framePcmMono_.data(), framePcmFrames_);
    scene.acceptStereoPcm(framePcm_.data(), framePcmFrames_);
    scene.acceptPcmSpan(frameSpan_.data(), frameSpanFrames_, frameSpanFresh_);
}

float Renderer::drawSecondaryTargets(const SceneParams& p, float dt) {
    float progress = 1.0f;
    secondaryTex_ = fboB_.tex();
    if (outgoingScene_) {
        // Eased, on the clock, and a step on with each kick (#309).
        progress = transition::ease(transitionProgress_.step(dt, frameFeatures_.kick));
        if (transitionProgress_.done()) {
            outgoingScene_ = nullptr;
            outgoingParams_.reset();
        } else {
            drawOutgoing(p, dt);
        }
    }
    // After the outgoing picture, which may have just ended: Layer 2 keeps going through a transition.
    drawLayerTarget(p, dt);
    return progress;
}

void Renderer::drawOutgoing(const SceneParams& p, float dt) {
    // The Style on its way out draws at half size each way (#311): a quarter of the fill for a picture
    // that is only fading, warping or sliding away. Full size if that target cannot be had.
    const tile::Size half = tile::outgoingSize({renderWidth_, renderHeight_});
    const bool halved = fboOut_.ensure(half.width, half.height);
    const Framebuffer& target = halved ? fboOut_ : fboB_;
    const SceneParams& op = outgoingParams_ ? *outgoingParams_ : p;
    glBindFramebuffer(GL_FRAMEBUFFER, target.fbo());
    glViewport(0, 0, target.width(), target.height());
    secondaryTex_ = target.tex();
    // A Style that keeps its last frame keeps it on its way out too: on the change's first frame the
    // scene target still holds its last picture, trails and all, which its own target starts from
    // (#309). After that its target is its own.
    const float keep = outgoingScene_->trailRetention(op);
    bool kept = keep > 0.0f && !sceneJustSwitched_;
    if (keep > 0.0f && sceneJustSwitched_ && fboA_.ok() && fboA_.format() == target.format()) {
        glBindFramebuffer(GL_READ_FRAMEBUFFER, fboA_.fbo());
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, target.fbo());
        glBlitFramebuffer(0, 0, fboA_.width(), fboA_.height(), 0, 0, target.width(), target.height(), GL_COLOR_BUFFER_BIT, GL_LINEAR);
        glBindFramebuffer(GL_FRAMEBUFFER, target.fbo());
        kept = true;
    }
    if (kept) {
        trailPass_.drawFadeQuad(TrailPass::fadeAlpha(keep, dt), quadVao_);
    } else {
        glClear(GL_COLOR_BUFFER_BIT);
    }
    prepareScene(*outgoingScene_, extrasFor(outgoingScene_->id(), nullptr));
    outgoingScene_->setParams(op);
    deliverPcm(*outgoingScene_);
    outgoingScene_->update(gainAdjusted(frameFeatures_, op), dt);
    outgoingScene_->setDrawTarget(target.fbo(), target.width(), target.height());
    outgoingScene_->draw(timeSeconds_);
}

void Renderer::drawLayerTarget(const SceneParams& p, float dt) {
    if (layerScene_ == nullptr || layerScene_ == activeScene_ || layerScene_ == outgoingScene_) return;
    if (!fboL_.resizePreserving(renderWidth_, renderHeight_) || fboL_.width() != renderWidth_ || fboL_.height() != renderHeight_) {
        layerScene_ = nullptr;
        return;
    }
    glBindFramebuffer(GL_FRAMEBUFFER, fboL_.fbo());
    glViewport(0, 0, renderWidth_, renderHeight_);
    // A Style that keeps its last frame (Curl Flow) keeps it as Layer 2 too, but never another's.
    const SceneParams& own = layerParams_;
    const float keep = layerScene_->trailRetention(own);
    if (keep > 0.0f && layerDrawn_ == layerScene_) {
        trailPass_.apply(own, keep, timeSeconds_, dt, fboL_, quadVao_, renderWidth_, renderHeight_);
    } else {
        glClear(GL_COLOR_BUFFER_BIT);
    }
    layerDrawn_ = layerScene_;
    wireFlow(*layerScene_, p);
    prepareScene(*layerScene_, extrasFor(layerScene_->id(), nullptr));
    layerScene_->setParams(own);
    deliverPcm(*layerScene_);
    layerScene_->update(gainAdjusted(frameFeatures_, own), dt);
    layerScene_->setDrawTarget(fboL_.fbo(), fboL_.width(), fboL_.height());
    layerScene_->draw(timeSeconds_);
}

GLuint Renderer::layerTexture() const {
    if (layerScene_ == nullptr) return 0;
    if (layerScene_ == activeScene_) return fboA_.tex();
    // The outgoing picture's target: half size while it fades out (#311).
    if (layerScene_ == outgoingScene_) return secondaryTex_ != 0 ? secondaryTex_ : fboB_.tex();
    return fboL_.tex();
}

void Renderer::drawSceneTarget(Scene& scene, const SceneParams& p, float dt) {
    glBindFramebuffer(GL_FRAMEBUFFER, fboA_.fbo());
    glViewport(0, 0, renderWidth_, renderHeight_);
    const float keep = scene.trailRetention(p);
    // A Style switched to through a change starts from the picture the one before it left, its trails
    // included, rather than from black (#309).
    if (keep > 0.0f && (!sceneJustSwitched_ || inheritTrails_)) {
        trailPass_.apply(p, keep, timeSeconds_, dt, fboA_, quadVao_, renderWidth_, renderHeight_);
    } else {
        glClear(GL_COLOR_BUFFER_BIT);
    }
    wireFlow(scene, p);
    prepareScene(scene, activeExtras_);
    scene.setParams(p);
    deliverPcm(scene);
    scene.update(gainAdjusted(frameFeatures_, p), dt);
    scene.setDrawTarget(fboA_.fbo(), renderWidth_, renderHeight_);
    scene.draw(timeSeconds_);
}

GLuint Renderer::applySharedEffects(float dt) {
    // Depth of field first: it reads the depth the Style wrote into the scene target's alpha.
    const GLuint sceneTex = dofPass_.apply(frameDof_, fboA_.tex(), renderWidth_, renderHeight_, quadVao_);
    if (!frameEffects_.active()) {
        sharedFx_.forgetHistory();
        return sceneTex;
    }
    // A Style changed to through a change of Style carries on the echo of the one before it (#309);
    // the first Style, or the first after a cut, starts its trails afresh.
    if (sceneJustSwitched_ && !inheritTrails_) sharedFx_.forgetHistory();
    return sharedFx_.apply(frameEffects_, sceneTex, renderWidth_, renderHeight_, dt, timeSeconds_, quadVao_);
}

void Renderer::composite(Scene& scene, const SceneParams& p, float progress, GLuint sceneTex, GLuint targetFbo, float dt) {
    // The composite draws into the HDR target, the final pass blooms and tonemaps it into the No-flash
    // limiter's candidate target, and the limiter draws the frame that is shown. Only if the HDR
    // target cannot be made does the composite draw (and dither) the final picture itself, and only if
    // the limiter's targets cannot be made does the final picture go straight out. Every pass up to
    // the limiter's last draws at the frame size, which upscales onto the output (TileFrame.hpp).
    const tile::Size frame = tile::frameSize({renderWidth_, renderHeight_}, {width_, height_});
    const bool hdr = hdrPass_.ensureComposite(frame.width, frame.height);
    const bool limited = noFlash_.bindCandidate(frame.width, frame.height);
    const GLuint finalFbo = limited ? noFlash_.candidateFbo() : targetFbo;
    // Without the limiter, the pass that writes the final picture draws straight onto the output.
    const tile::Size finalSize = limited ? frame : tile::Size{width_, height_};
    CompositePass::Inputs& in = compositeInputs_;
    in.texA = sceneTex;
    in.texB = secondaryTex_ != 0 ? secondaryTex_ : fboB_.tex();
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
    in.hasOutgoing = outgoingScene_ != nullptr;
    in.transitionId = frameLook_;
    in.transitionStyle = safety::transitionStyle(TransitionCatalog::builtIn(frameLook_).value_or(TransitionStyle::Fade));
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
    Scene* other = outgoingScene_ ? outgoingScene_ : activeScene_;
    // A Style's own zoom, crossfaded with the transition so it never jumps when the picture changes.
    in.fx.zoom *= milkdrop::crossfadedZoom(activeScene_->pictureZoom(), other->pictureZoom(), progress, outgoingScene_ != nullptr);
    in.gateB = grade::gateFor(other->family()).toVec4();
    // The Camera's flat moves for every picture whose Style does not move a camera of its own, and the
    // flat endless zoom for the ones whose renderer has none (the fluid family, and MilkDrop since its
    // post pass went into the composite, #303).
    in.camera = camera_.pose();
    const auto flatZoom = [](const Scene* s) { return s->family() == SceneFamily::Fluid || s->family() == SceneFamily::Milkdrop; };
    in.cameraGate = {activeScene_->ownsCamera() ? 0.0f : 1.0f, other->ownsCamera() ? 0.0f : 1.0f, flatZoom(activeScene_) ? 1.0f : 0.0f,
                     flatZoom(other) ? 1.0f : 0.0f};
    // Layer 2, with its own gate and Camera pair like the two pictures above.
    Scene* layerPicture = layerScene_ ? layerScene_ : activeScene_;
    in.texL = layerTexture();
    in.layer = frameLayer_;
    in.gateL = grade::gateFor(layerPicture->family()).toVec4();
    in.cameraGateL = {layerPicture->ownsCamera() ? 0.0f : 1.0f, layerPicture->family() == SceneFamily::Fluid ? 1.0f : 0.0f};
    // W00: read this frame's latched underlay straight from CompositePass (it owns the texture -
    // see CompositePass.hpp), the same way uFlow/uRipple above are read from Overlays.
    in.underlayTex = compositePass_.underlayTexOrZero();
    in.underlayBlend = compositePass_.underlayBlendMode();
    in.underlayAmount = compositePass_.underlayAmount();
    anaglyphMix_ = sharedfx::anaglyphMix(anaglyphMix_, frameEffects_.anaglyph, dt);
    in.anaglyph = anaglyphMix_;
    in.dither = !hdr;
    // A composite that would hand the picture on unchanged (the default look of most Styles) is not
    // drawn: the tonemap reads the scene target itself, one full-screen pass fewer (#311). Without the
    // HDR pipeline the composite is the pass that writes the final picture, so it always runs. The 3D
    // particle layer draws into the composite's target, so it keeps the composite on. (Depth of field
    // needs no composite: sceneTex is already its focused picture.)
    const bool skipped = hdr && !frameParticles_.on() && tile::compositeIsIdentity(CompositePass::lookOf(in));
    const GLuint compositeFbo = hdr ? hdrPass_.compositeFbo() : finalFbo;
    const tile::Size compositeSize = hdr ? frame : finalSize;
    if (!skipped) {
        glBindFramebuffer(GL_FRAMEBUFFER, compositeFbo);
        Framebuffer::discardBound(compositeFbo);
        glViewport(0, 0, compositeSize.width, compositeSize.height);
        glDisable(GL_BLEND);
        compositePass_.draw(in);
        // The 3D particle layer, added onto the picture as light, so the bloom catches it.
        particlePass_.draw(frameParticles_, camera_.pose(), compositeFbo, compositeSize.width, compositeSize.height, quadVao_);
    }
    if (hdr) {
        const GLuint source = skipped ? sceneTex : hdrPass_.compositeTex();
        hdrPass_.resolve(tone::bloomFor(p.bloom, thermalTierInfo(thermal_.tier()).optionalPasses), source, finalFbo, finalSize.width,
                         finalSize.height, quadVao_, frameGlare_);
    }
    // The offscreen Render waits for each frame's own measurement, so every frame it writes is the one asked for.
    if (limited) noFlash_.present(dt, targetFbo, width_, height_, quadVao_, thermal_.offscreen());
    // W00: overlay is its own draw call, over whatever composite() just wrote to targetFbo, so it
    // never enters postFx/transitions/Layers and comes out identical live, in the wallpaper and in
    // the offscreen export.
    compositePass_.drawOverlay(quadVao_);
}

void Renderer::stepOverlays(Scene& scene, const SceneParams& p, float dt) {
    if (overlays_.wantsFlow(p, scene.isFluid())) overlays_.stepFlow(gainAdjusted(frameFeatures_, p), dt, p);
    const float smear = touchSmear_.load(std::memory_order_relaxed);
    smearing_ = smear > 0.0f && overlays_.smearing(monotonicSeconds());
    // Stepped once per frame ahead of every draw so each scene reads the same anchor.
    touchField_.step(dt);
    gravityField_.step(dt, reducedMotion_.load(std::memory_order_relaxed));
    overlays_.drainTouchStrokes(scene, smear);
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
    Scene* scene = resolveActiveScene(dt);
    resolveLayer(dt);
    updateRenderSize(dt, scene);
    if (!scene || !ensureTargets()) {
        glBindFramebuffer(GL_FRAMEBUFFER, targetFbo);
        glViewport(0, 0, width_, height_);
        glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
        glClear(GL_COLOR_BUFFER_BIT);
        return;
    }
    const SceneParams p = resolveParams(dt);
    stepOverlays(*scene, p, dt);
    const float progress = drawSecondaryTargets(p, dt);
    drawSceneTarget(*scene, p, dt);
    const GLuint sceneTex = applySharedEffects(dt);
    composite(*scene, p, progress, sceneTex, targetFbo, dt);
}

}  // namespace geode::viz
