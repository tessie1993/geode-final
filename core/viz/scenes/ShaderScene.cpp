#include "scenes/ShaderScene.hpp"

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstring>

#include "geode/viz/HdrPass.hpp"
#include "geode/viz/LiveSignal.hpp"
#include "geode/viz/ToneMap.hpp"
#include "scenes/SceneCommon.hpp"

namespace geode::viz {

namespace {

constexpr int kPaletteRows = 5;
constexpr int kMarchMaxSteps = 128;
constexpr int kMarchFloorSteps = 64;
constexpr float kMarchMinDetail = 0.25f;
constexpr float kMarchMaxDetail = 1.5f;

float paletteRowCoordinate(int index) {
    return (static_cast<float>(std::clamp(index, 0, kPaletteRows - 1)) + 0.5f) / static_cast<float>(kPaletteRows);
}

float flag(bool on) { return on ? 1.0f : 0.0f; }

constexpr float kTwoPiF = 6.2831853f;

// Each Extra's uniform, in ExtraKind's order (lib_style.glsl declares them).
constexpr std::array<const char*, mod::kExtraCount> kExtraUniforms = {
    "uXScale", "uXCount",      "uXSize", "uXThickness", "uXFlight",  "uXSpin",      "uXOrbit",   "uXSwirl",
    "uXDrift", "uXComplexity", "uXEdge", "uXFog",       "uXDecay",   "uXViscosity", "uXGrowth",  "uXSpread",
    "uXForce", "uXReach",      "uXTurn", "uXHold",      "uXVariant", "uXCenterX",   "uXCenterY",
};

// The rate Extras and their clocks (lib_style.glsl): uTime's own step, run at the rate each one sets.
constexpr std::array<mod::Extra, 3> kRateExtras = {mod::Extra::Flight, mod::Extra::Spin, mod::Extra::Drift};
constexpr std::array<const char*, 3> kRateUniforms = {"uXFlightTime", "uXSpinTime", "uXDriftTime"};

// The shared inputs' samplers (lib_inputs.glsl), each on its own unit above the audio, flow and palette.
struct InputSampler {
    inputs::Input input;
    const char* uniform;
    GLenum target;
    GLint unit;
};
constexpr std::array<InputSampler, 9> kInputSamplers = {{
    {inputs::Input::NoiseLq, "uNoiseLq", GL_TEXTURE_2D, 3},
    {inputs::Input::NoiseMq, "uNoiseMq", GL_TEXTURE_2D, 4},
    {inputs::Input::NoiseHq, "uNoiseHq", GL_TEXTURE_2D, 5},
    {inputs::Input::NoiseVolume, "uNoiseVol", GL_TEXTURE_3D, 6},
    {inputs::Input::BlueNoise, "uBlueNoise", GL_TEXTURE_2D, 7},
    {inputs::Input::PreviousFrame, "uPrevFrame", GL_TEXTURE_2D, 8},
    {inputs::Input::Blur1, "uBlur1", GL_TEXTURE_2D, 9},
    {inputs::Input::Blur2, "uBlur2", GL_TEXTURE_2D, 10},
    {inputs::Input::Blur3, "uBlur3", GL_TEXTURE_2D, 11},
}};

// The engine's passes (MultiPass.hpp): the resolve's four textures. The Style's coarse prepass takes the
// first unit above the pack's images (coarseUnit()), within the 16 every GLES 3 device has.
constexpr GLint kGuaranteedUnits = 16;
constexpr GLint kResolveMarchUnit = 0;
constexpr GLint kResolveHistoryUnit = 1;
constexpr GLint kResolveCamNowUnit = 2;
constexpr GLint kResolveCamPrevUnit = 3;
// lib_multipass's uMpPass.
constexpr float kPassPicture = 0.0f;
constexpr float kPassCoarse = 1.0f;
constexpr float kPassCamera = 2.0f;

// A pass target of w x h that holds half floats, which the depth in alpha and the camera's numbers need.
bool halfFloatTarget(Framebuffer& target, int width, int height) {
    return target.ensure(width, height) && target.format() == Framebuffer::Format::Rgba16F;
}

// The feedback loop's buffer: half float where the HDR targets are, else RGB10_A2, ten bits a channel,
// which every GLES 3 device renders (FeedbackWarp.hpp).
Framebuffer::Format feedbackFormat(Framebuffer::Format hdr) {
    return hdr == Framebuffer::Format::Rgba16F ? Framebuffer::Format::Rgba16F : Framebuffer::Format::Rgb10A2;
}

// What the warp's dither rounds to: the format the warped picture was actually allocated in.
feedback::Precision precisionOf(Framebuffer::Format format) {
    switch (format) {
        case Framebuffer::Format::Rgba16F: return feedback::Precision::Half;
        case Framebuffer::Format::Rgb10A2: return feedback::Precision::Unorm10;
        case Framebuffer::Format::Rgba8: return feedback::Precision::Unorm8;
    }
    return feedback::Precision::Unorm8;
}

// Copies the whole of `fbo` (width x height) into `target`, scaled to its size.
void blitInto(GLuint fbo, int width, int height, const Framebuffer& target, GLenum filter) {
    glBindFramebuffer(GL_READ_FRAMEBUFFER, fbo);
    glBindFramebuffer(GL_DRAW_FRAMEBUFFER, target.fbo());
    glBlitFramebuffer(0, 0, width, height, 0, 0, target.width(), target.height(), GL_COLOR_BUFFER_BIT, filter);
}

// Shortest signed distance from `from` to `to` on a unit-period circle.
float wrappedDelta(float from, float to, float period) {
    const float half = period * 0.5f;
    float d = std::fmod(to - from + half, period);
    if (d < 0.0f) d += period;
    return d - half;
}

}  // namespace

ShaderScene::ShaderScene(std::string id, std::string vertexSrc, std::string fragmentSrc, ProgramBinaryCache* cache, SceneHost host,
                         inputs::Set inputs, std::optional<ShaderFeedback> feedback, std::vector<ShaderImage> images,
                         std::optional<ShaderMultipass> multipass)
    : id_(std::move(id)),
      vertexSrc_(std::move(vertexSrc)),
      currentFragment_(std::move(fragmentSrc)),
      cache_(cache),
      host_(std::move(host)),
      inputs_(inputs),
      feedback_(std::move(feedback)),
      multipass_(std::move(multipass)),
      images_(std::move(images)) {
    pendingFragment_ = currentFragment_;
    if (images_.size() > imageTex_.size()) images_.resize(imageTex_.size());
}

ShaderScene::~ShaderScene() { release(); }

void ShaderScene::acceptStereoPcm(const float* interleaved, int frames) { audioInputs_.acceptStereo(interleaved, frames); }

void ShaderScene::setFlow(GLuint texture, float strength) {
    flowTex_ = texture;
    flowStrength_ = strength;
}

void ShaderScene::init() {
    program_ = 0;
    uniforms_ = UniformCache(0);
    warpProgram_ = 0;
    warpUniforms_ = UniformCache(0);
    warpBuilt_ = false;
    resolveProgram_ = 0;
    resolveUniforms_ = UniformCache(0);
    resolveBuilt_ = false;
    {
        std::lock_guard<std::mutex> lock(pendingLock_);
        pendingFragment_ = currentFragment_;
    }
    glGenVertexArrays(1, &vao_);
    const char* extensions = reinterpret_cast<const char*>(glGetString(GL_EXTENSIONS));
    const bool floatLinear = extensions && std::strstr(extensions, "OES_texture_float_linear") != nullptr;
    audioTex_.createImage(GL_R32F, GL_RED, GL_FLOAT, kAudioTexWidth, kAudioTexRows, floatLinear ? GL_LINEAR : GL_NEAREST,
                          GL_CLAMP_TO_EDGE);
    // A Render draws each visit's random vector from the Render seed, so it repeats; the live Visual
    // from the clock, so every visit looks a little different.
    const bool rendering = host_.rendering && host_.rendering();
    const auto live = static_cast<std::uint32_t>(std::chrono::steady_clock::now().time_since_epoch().count());
    visitSeed_ = inputs::visitSeed(rendering ? inputs::kNoiseSeed : live, id_);
    createImages();
}

namespace {

GLint glWrap(packs::TextureWrap wrap) {
    switch (wrap) {
        case packs::TextureWrap::Repeat: return GL_REPEAT;
        case packs::TextureWrap::Mirror: return GL_MIRRORED_REPEAT;
        case packs::TextureWrap::Clamp: return GL_CLAMP_TO_EDGE;
    }
    return GL_REPEAT;
}

}  // namespace

void ShaderScene::createImages() {
    for (size_t i = 0; i < images_.size(); ++i) {
        const ShaderImage& image = images_[i];
        const bool nearest = image.filter == packs::TextureFilter::Nearest;
        // Made on its own unit, so making it never replaces what unit 0 holds.
        glActiveTexture(static_cast<GLenum>(GL_TEXTURE0 + kFirstImageUnit + static_cast<GLint>(i)));
        imageTex_[i].createFromPixels(image.image.rgba.data(), image.image.width, image.image.height, glWrap(image.wrap),
                                      nearest ? GL_NEAREST : GL_LINEAR, image.filter == packs::TextureFilter::Mipmap);
    }
    glActiveTexture(GL_TEXTURE0);
}

void ShaderScene::uploadImages() {
    if (images_.empty()) return;
    for (size_t i = 0; i < images_.size(); ++i) {
        if (!imageTex_[i].ok()) continue;
        const GLint unit = kFirstImageUnit + static_cast<GLint>(i);
        imageTex_[i].bind(unit);
        glUniform1i(uniforms_.loc(images_[i].uniform), unit);
    }
    glActiveTexture(GL_TEXTURE0);
}

void ShaderScene::setSharedInputs(SharedInputs* shared) {
    sharedInputs_ = shared;
    // Once, not every frame: a target that fell back to RGBA8 here stays so instead of being made again.
    const Framebuffer::Format format = shared != nullptr ? shared->format() : Framebuffer::Format::Rgba8;
    previous_.setFormat(format);
    for (Framebuffer& level : blur_) level.setFormat(format);
    kept_.setFormat(feedbackFormat(format));
    warped_.setFormat(feedbackFormat(format));
    passMarch_.setFormat(format);
    passCoarse_.setFormat(format);
    for (Framebuffer& camera : passCamera_) camera.setFormat(format);
    passResolved_.setFormat(format);
}

void ShaderScene::beginVisit() {
    visitClock_.beginVisit(visitSeed_);
    // The frames it kept belong to its last visit, and so does the warp's clock.
    releaseHistory();
    warpMotion_.reset();
    warpFrame_ = 0;
    // The jitter starts its cycle again and nothing of the last visit is reprojected.
    passHistory_.reset();
}

void ShaderScene::setDrawTarget(GLuint fbo, int width, int height) {
    drawFbo_ = fbo;
    drawWidth_ = width;
    drawHeight_ = height;
}

void ShaderScene::resize(int width, int height) {
    width_ = width;
    height_ = height;
}

// exp(-dt*hz) rather than a fixed per-frame fraction: the same wall-clock rise
// on a 30fps device and a 120fps one, so the look does not change with the
// frame rate the thermal governor happens to be pacing at.
float ShaderScene::slew(float current, float target, float dt, float riseHz, float fallHz) {
    const float hz = target > current ? riseHz : fallHz;
    const float k = 1.0f - std::exp(-std::max(dt, 0.0f) * hz);
    return current + (target - current) * k;
}

// A 32-bit LCG. Good enough to pick a plateau and a direction, and cheap enough
// to sit in a per-frame path.
float ShaderScene::nextSeed() {
    seedState_ = seedState_ * 1664525u + 1013904223u;
    return static_cast<float>((seedState_ >> 8) & 0xFFFFFFu) / static_cast<float>(0x1000000u);
}

// What a transient is allowed to change. NOT the picture: a spike moves
// TARGETS, and the values the styles read glide toward them over the best part
// of a second. The three things it can pick are the three the user asked a
// spike to mean - a new travel direction, a new spawn, and a new fractal.
void ShaderScene::stepMotion(float hit, float dt) {
    // Capped rather than free-running: spawnAge_ is only ever read through a
    // smoothstep with a horizon of a second or two, and an unbounded float would
    // lose its low bits over a long session for no gain.
    spawnAge_ = std::min(spawnAge_ + dt, kSpawnAgeMax);
    spikeLockout_ = std::max(spikeLockout_ - dt, 0.0f);

    const bool spiked = hit > kSpikeThreshold && spikeLockout_ <= 0.0f;
    if (spiked) {
        spikeLockout_ = kSpikeRefractorySeconds;
        spikeAttackLeft_ = kSpikeAttackSeconds;
        // New spawn: a fresh seed and an age of zero, so a style can grow the
        // new thing in from nothing instead of cutting to it.
        spawnSeed_ = nextSeed();
        spawnAge_ = 0.0f;
        // New fractal: the next plateau on the golden-ratio walk.
        formTarget_ = std::fmod(formTarget_ + kFormStep, 1.0f);
        // New direction: a bounded turn, never a reversal. Wrapped so the target
        // cannot walk off into the range where a float has no fraction left.
        dirTarget_ = std::fmod(dirTarget_ + (nextSeed() * 2.0f - 1.0f) * kDirMaxTurn, kTwoPiF);
    }
    // The envelope rises rather than steps, so even a style that keys
    // brightness straight off it cannot flash; it does reach 1.0, over the
    // attack, before it starts to leave.
    if (spikeAttackLeft_ > 0.0f) {
        spikeAttackLeft_ -= dt;
        spikeEnv_ = std::min(spikeEnv_ + dt / kSpikeAttackSeconds, 1.0f);
    } else {
        spikeEnv_ *= std::exp(-dt * kSpikeFallHz);
    }

    formPhase_ = std::fmod(formPhase_ + wrappedDelta(formPhase_, formTarget_, 1.0f) * (1.0f - std::exp(-dt * kFormGlideHz)) + 1.0f, 1.0f);
    dirAngle_ += wrappedDelta(dirAngle_, dirTarget_, kTwoPiF) * (1.0f - std::exp(-dt * kDirGlideHz));
    dirAngle_ = std::fmod(dirAngle_, kTwoPiF);
    // Monotonic: loudness sets the rate, never the sign.
    flowPhase_ = std::fmod(flowPhase_ + dt * (kFlowBaseHz + kFlowEnergyHz * smoothEnergy_), kTimeWrapSeconds);
}

void ShaderScene::update(const GeodeFeatureFrame& features, float dt) {
    const SceneParams& p = params_;
    shaderTime_ = std::fmod(shaderTime_ + p.speed * dt, kTimeWrapSeconds);
    // Integrated like shaderTime_, so moving a rate Extra changes the pace and never jumps the
    // picture; at the neutral 1 each clock is shaderTime_ exactly.
    for (size_t r = 0; r < kRateExtras.size(); ++r) {
        rateClocks_[r] = std::fmod(rateClocks_[r] + p.speed * dt * extras_.multiplier(kRateExtras[r], 0.0f), kTimeWrapSeconds);
    }
    rotationAngle_ = std::fmod(rotationAngle_ + p.rotation * dt, kTwoPi);
    zoomPhase_ = p.endlessZoom ? std::fmod(zoomPhase_ + p.endlessZoomSpeed * dt, 1.0f) : 0.0f;
    if (p.colorCycle) cyclePhase_ = std::fmod(cyclePhase_ + p.cycleSpeed * dt, 1.0f);
    const float drive = p.audioDrive;
    bass_ = std::clamp(features.bass * drive, 0.0f, kAudioClamp);
    mid_ = std::clamp(features.mid * drive, 0.0f, kAudioClamp);
    treble_ = std::clamp(features.treble * drive, 0.0f, kAudioClamp);
    energy_ = std::clamp(features.rms * drive, 0.0f, kAudioClamp);
    smoothBass_ = slew(smoothBass_, bass_, dt, kBandRiseHz, kBandFallHz);
    smoothMid_ = slew(smoothMid_, mid_, dt, kBandRiseHz, kBandFallHz);
    smoothTreble_ = slew(smoothTreble_, treble_, dt, kBandRiseHz, kBandFallHz);
    smoothEnergy_ = slew(smoothEnergy_, energy_, dt, kBandRiseHz, kBandFallHz);
    swell_ = slew(swell_, energy_, dt, kSwellRiseHz, kSwellFallHz);
    if (inputs::has(inputs_, inputs::Input::Clocks)) visitClock_.step(dt);
    if (inputs::has(inputs_, inputs::Input::Bar)) barClock_.step(features.barPhase, features.downbeatConfidence, features.bpm, dt);
    const float hit = live::hit(features);
    stepMotion(hit, dt);
    energyPhase_ = framerate::advancePhase(energyPhase_, p.speed * smoothEnergy_, dt, kTimeWrapSeconds);
    const float flowRate = kFlowBaseHz + kFlowEnergyHz * smoothEnergy_;
    // Its uTime share runs at the FLIGHT Extra's rate, as uXFlightTime does (neutral 1: unchanged).
    const float flightTimeRate = kFlightTimeRate * p.speed * extras_.multiplier(mod::Extra::Flight, 0.0f);
    flight_ = framerate::advancePhase(flight_, flightTimeRate + kFlightFlowRate * flowRate, dt, kFlightWrap);
    beatPulse_ = beatEnvelope_.step(hit, dt);
    // The heard transient resets the shared pulse ramp; between hits it free-runs.
    pulsePhase_ = hit > 0.0f ? 0.0f : std::fmod(pulsePhase_ + dt * kPulsePhaseHz, 1.0f);
    // The spectrum, its peaks and the steady scope, from this frame's window (#300).
    audioInputs_.update(dt, drive, features, texData_.data());
    if (feedback_) {
        // The warp moves at the Style's pace and the music's swell, and the Extras it maps onto.
        feedback::Drive warp;
        warp.speed = p.speed;
        warp.swell = swell_;
        warp.beat = beatPulse_;
        warp.decay = extras_.multiplier(mod::Extra::Decay, 0.0f);
        warp.flight = extras_.multiplier(mod::Extra::Flight, 0.0f);
        warp.spin = extras_.multiplier(mod::Extra::Spin, 0.0f);
        warp.drift = extras_.multiplier(mod::Extra::Drift, 0.0f);
        warp.swirl = extras_.multiplier(mod::Extra::Swirl, 0.0f);
        warpStep_ = warpMotion_.step(feedback_->spec, warp, dt);
    }
}

void ShaderScene::draw(float timeSeconds) {
    (void) timeSeconds;
    compilePendingIfAny();
    if (program_ == 0) return;
    buildWarpIfNeeded();
    buildResolveIfNeeded();
    feeding_ = feedback_.has_value() && warpFeedback();
    glDisable(GL_BLEND);
    glUseProgram(program_);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, audioTex_.id());
    glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, kAudioTexWidth, kAudioTexRows, GL_RED, GL_FLOAT, texData_.data());
    uploadParams();
    glUniform2f(uniforms_.loc("uResolution"), static_cast<float>(width_), static_cast<float>(height_));
    glUniform1i(uniforms_.loc("uAudioTex"), 0);
    if (flowTex_ != 0) {
        glActiveTexture(GL_TEXTURE1);
        glBindTexture(GL_TEXTURE_2D, flowTex_);
        glUniform1i(uniforms_.loc("uFlow"), 1);
        set1f("uFlowStrength", flowStrength_);
        glActiveTexture(GL_TEXTURE0);
    }
    const SceneParams& p = params_;
    const bool lutSelected = p.paletteLut >= 0 && paletteLutTex_ != 0;
    if (paletteLutTex_ != 0) {
        glActiveTexture(GL_TEXTURE2);
        glBindTexture(GL_TEXTURE_2D, paletteLutTex_);
        glUniform1i(uniforms_.loc("uPalLut"), 2);
        glActiveTexture(GL_TEXTURE0);
    }
    set1f("uPalLutMix", flag(lutSelected));
    set1f("uPalLutRow", paletteRowCoordinate(std::max(p.paletteLut, 0)));
    // The Detail budget, cut to the share the thermal tier allows, and the effects it switches off.
    set1f("uSteps", static_cast<float>(quality::marchSteps(static_cast<int>(marchSteps(p.marchDetail)), quality_.stepScale)));
    glUniform1i(uniforms_.loc("uFxOff"), static_cast<GLint>(quality_.effectsOff));
    uploadMotion();
    uploadTouch();
    uploadGravity();
    uploadCamera();
    uploadExtras();
    uploadFeatures();
    uploadInputs();
    uploadImages();
    // A single pass draws the picture from the camera, as a Style without passes does (lib_multipass).
    set1f("uMpPass", kPassPicture);
    set1f("uMpUseCoarse", 0.0f);
    glUniform2f(uniforms_.loc("uMpJitter"), 0.0f, 0.0f);
    const multipass::Plan passes = passPlan();
    if (!passes.passes || !drawPasses(passes)) {
        // Drawn in one pass at the target's size: nothing is kept for the next frame's resolve.
        if (multipass_) passHistory_.lost();
        glBindVertexArray(vao_);
        glDrawArrays(GL_TRIANGLES, 0, 3);
        glBindVertexArray(0);
    }
    if (feeding_) presentFeedback();
    captureHistory(feeding_ ? kept_.fbo() : drawFbo_);
}

void ShaderScene::uploadParams() {
    const SceneParams& p = params_;
    set1f("uTime", shaderTime_);
    set1f("uBass", bass_);
    set1f("uMid", mid_);
    set1f("uTreble", treble_);
    set1f("uEnergy", energy_);
    set1f("uBeat", beatPulse_);
    set1f("uSpeed", p.speed);
    set1f("uZoom", p.zoom);
    set1f("uRotation", rotationAngle_);
    set1f("uZoomPhase", zoomPhase_);
    set1f("uColorShift", p.colorShift + cyclePhase_);
    set1f("uHueRange", p.hueRange);
    set1f("uSat", p.saturation);
    set1f("uBright", p.brightness);
    set1f("uInvert", flag(p.invert));
    set1f("uIntensity", p.intensity);
    set1f("uMirrorX", flag(p.mirror));
    set1f("uBeatResponse", p.beatResponse);
    set1f("uTurbulence", p.turbulence);
    set1f("uPalBase", p.paletteBase());
    set1f("uPalRange", p.paletteRange());
    set1f("uPal2Base", p.palette2Base());
    set1f("uPal2Range", p.palette2Range());
    set1f("uPaletteMix", p.paletteMix);
    set1f("uDuotone", flag(p.duotone));
    set1f("uBloom", p.bloom);
    set1f("uWarp", p.warp);
    set1f("uRipple", p.ripple);
    set1f("uSymmetry", static_cast<float>(p.symmetry));
    set1f("uKaleido", flag(p.kaleidoscope));
    set1f("uMorph", p.morph);
    set1f("uPixelate", p.pixelate);
    set1f("uPosterize", p.posterize);
    set1f("uSway", p.sway);
    set1f("uPulse", p.pulse);
    set1f("uBeatPhase", pulsePhase_);
    set1f("uDriftX", p.driftX);
    set1f("uDriftY", p.driftY);
    set1f("uShake", p.shake);
    set1f("uTile", p.tile);
    set1f("uTwist", p.twist);
    set1f("uTemperature", p.temperature);
    set1f("uSolarize", flag(p.solarize));
    set1f("uContrast", p.contrast);
    set1f("uGamma", p.gamma);
}

// Every style is handed these; the ones that read none of them link them away
// and glUniform on a -1 location is a no-op, so this costs nothing on a style
// that has not been converted.
void ShaderScene::uploadMotion() {
    set1f("uBassSmooth", smoothBass_);
    set1f("uMidSmooth", smoothMid_);
    set1f("uTrebleSmooth", smoothTreble_);
    set1f("uEnergySmooth", smoothEnergy_);
    set1f("uSwell", swell_);
    set1f("uSpike", spikeEnv_);
    set1f("uSpawnSeed", spawnSeed_);
    set1f("uSpawnAge", spawnAge_);
    set1f("uFormPhase", formPhase_);
    set1f("uFlowPhase", flowPhase_);
    set1f("uEnergyPhase", energyPhase_);
    set1f("uFlight", flight_);
    glUniform2f(uniforms_.loc("uMoveDir"), std::cos(dirAngle_), std::sin(dirAngle_));
}

void ShaderScene::uploadTouch() {
    if (touchField_) {
        touchAnchor_ = {touchField_->anchorX(), touchField_->anchorY(), touchField_->anchorStrength(), touchField_->anchorAge()};
        touchPoints_ = touchField_->points();
    } else {
        touchAnchor_.fill(0.0f);
        touchPoints_.fill(0.0f);
    }
    glUniform4fv(uniforms_.loc("uTouchAnchor"), 1, touchAnchor_.data());
    // Only as many elements as survived linking: uploading past the live length is an INVALID_OPERATION.
    glUniform4fv(uniforms_.loc("uTouchPoints"), uniforms_.arrayCount("uTouchPoints", TouchField::kMaxPoints), touchPoints_.data());
    glUniform1i(uniforms_.loc("uTouchCount"), touchField_ ? touchField_->count() : 0);
    glUniform1i(uniforms_.loc("uTouchGesture"), touchField_ ? touchField_->gesture() : TouchField::kGestureNone);
    glUniform2f(uniforms_.loc("uTouchAxis"), touchField_ ? touchField_->axisX() : 0.0f, touchField_ ? touchField_->axisY() : 0.0f);
    set1f("uTouchSpin", touchField_ ? touchField_->spin() : 0.0f);
}

void ShaderScene::uploadGravity() {
    const GravityField* g = gravityField_;
    glUniform3f(uniforms_.loc("uGravity"), g ? g->x() : 0.0f, g ? g->y() : 0.0f, g ? g->z() : 0.0f);
    glUniform2f(uniforms_.loc("uTilt"), g ? g->tiltX() : 0.0f, g ? g->tiltY() : 0.0f);
    set1f("uTiltWeight", g ? g->weight() : 0.0f);
}

void ShaderScene::uploadCamera() {
    glUniformMatrix3fv(uniforms_.loc("uCamRot"), 1, GL_FALSE, camera_.rotation.data());
    set1f("uCamPush", camera_.push);
    set1f("uCamTravel", camera_.travel);
    glUniform2f(uniforms_.loc("uCamOrbit"), camera_.yaw, camera_.pitch);
}

void ShaderScene::uploadExtras() {
    for (size_t e = 0; e < mod::kExtraCount; ++e) set1f(kExtraUniforms[e], extras_.value[e]);
    for (size_t r = 0; r < kRateUniforms.size(); ++r) set1f(kRateUniforms[r], rateClocks_[r]);
}

void ShaderScene::uploadFeatures() {
    const auto values = uniformValues(features_);
    for (size_t u = 0; u < kFeatureUniforms.size(); ++u) set1f(kFeatureUniforms[u], values[u]);
}

// Nothing here runs for a Style that declares no inputs, and nothing of an input it does not declare.
void ShaderScene::uploadInputs() {
    if (inputs_ == 0) return;
    if (inputs::has(inputs_, inputs::Input::Clocks)) {
        set1f("uFrame", static_cast<float>(visitClock_.frame()));
        set1f("uFrameDt", visitClock_.dt());
        glUniform4fv(uniforms_.loc("uVisitRand"), 1, visitClock_.random().data());
        set1f("uStyleAge", visitClock_.age());
    }
    if (inputs::has(inputs_, inputs::Input::Bar)) {
        set1f("uBarPhase", barClock_.phase());
        set1f("uBarConfidence", barClock_.confidence());
    }
    if (sharedInputs_ == nullptr) return;
    for (const InputSampler& sampler : kInputSamplers) {
        if (!inputs::has(inputs_, sampler.input)) continue;
        // The sampler's own unit is made active first: making a texture (the noise on its first use, the
        // black stand-in) binds it on the active unit, which was unit 0 and so replaced uAudioTex for the
        // frame it happened in.
        glActiveTexture(static_cast<GLenum>(GL_TEXTURE0 + sampler.unit));
        glBindTexture(sampler.target, inputTexture(sampler.input));
        glUniform1i(uniforms_.loc(sampler.uniform), sampler.unit);
    }
    glActiveTexture(GL_TEXTURE0);
}

GLuint ShaderScene::inputTexture(inputs::Input input) {
    switch (input) {
        case inputs::Input::PreviousFrame:
            // A Style with feedback reads the kept picture carried through the warp; one whose warp cannot
            // run this frame reads black rather than a picture that would no longer fade.
            if (feedback_) return feeding_ ? warped_.tex() : sharedInputs_->black();
            return previous_.ok() ? previous_.tex() : sharedInputs_->black();
        case inputs::Input::Blur1:
        case inputs::Input::Blur2:
        case inputs::Input::Blur3: {
            const int level = 1 + static_cast<int>(input) - static_cast<int>(inputs::Input::Blur1);
            const int source = inputs::blurSource(level, blurBuilt_);
            return source >= 0 ? blur_[static_cast<size_t>(source)].tex() : sharedInputs_->black();
        }
        default: return sharedInputs_->texture(input);
    }
}

void ShaderScene::captureHistory(GLuint source) {
    if (!inputs::needsHistory(inputs_) || sharedInputs_ == nullptr || drawWidth_ <= 0 || drawHeight_ <= 0) return;
    const int width = drawWidth_;
    const int height = drawHeight_;
    // A Style with feedback keeps its frame in kept_ itself.
    if (inputs::has(inputs_, inputs::Input::PreviousFrame) && !feedback_) {
        // Kept across a resize, so a Style that feeds back on its last frame never starts again from black.
        if (previous_.resizePreserving(width, height) && previous_.width() == width && previous_.height() == height) {
            blitInto(source, width, height, previous_, GL_NEAREST);
        }
    }
    const int length = inputs::blurChainLength(inputs_);
    HdrPass* hdr = sharedInputs_->hdr();
    blurBuilt_ = 0;
    if (length > 0 && hdr != nullptr) {
        std::array<tone::LevelSize, tone::kMaxLevels> sizes{};
        const int count = std::min({tone::bloomLevels(width, height, sizes), length, kBlurChain});
        std::array<Framebuffer*, kBlurChain> chain{};
        int built = 0;
        for (int i = 0; i < count; ++i) {
            Framebuffer& level = blur_[static_cast<size_t>(i)];
            if (!level.ensure(sizes[static_cast<size_t>(i)].width, sizes[static_cast<size_t>(i)].height)) break;
            chain[static_cast<size_t>(i)] = &level;
            built = i + 1;
        }
        if (built > 0) {
            // The step between: this frame halved by a linear blit; then the bloom's down filter, which
            // blurs as it halves, makes each blur level from the one above it.
            blitInto(source, width, height, blur_[0], GL_LINEAR);
            const bool filtered = hdr->downsample(blur_[0].tex(), blur_[0].width(), blur_[0].height(), chain.data() + 1, built - 1, vao_);
            blurBuilt_ = filtered ? built : 1;
        }
    }
    glBindFramebuffer(GL_FRAMEBUFFER, drawFbo_);
    glViewport(0, 0, width, height);
}

void ShaderScene::releaseHistory() {
    previous_.release();
    for (Framebuffer& level : blur_) level.release();
    blurBuilt_ = 0;
    kept_.release();
    warped_.release();
    feeding_ = false;
    releasePasses();
}

void ShaderScene::buildWarpIfNeeded() {
    if (!feedback_ || warpBuilt_) return;
    // Once: a warp stage that does not compile is reported and the Style draws on without its loop.
    warpBuilt_ = true;
    std::string error;
    warpProgram_ = program::build(feedback_->vertex, feedback_->fragment, cache_, &error);
    if (warpProgram_ == 0) {
        host_.onShaderError(error);
        return;
    }
    warpUniforms_ = UniformCache(warpProgram_);
}

bool ShaderScene::warpFeedback() {
    if (warpProgram_ == 0 || sharedInputs_ == nullptr || drawWidth_ <= 0 || drawHeight_ <= 0) return false;
    const int width = drawWidth_;
    const int height = drawHeight_;
    // The kept picture follows the target's size keeping what it holds, as every picture that feeds the
    // next frame does (ADR-0005); both calls leave the framebuffer unbound.
    const bool kept = kept_.resizePreserving(width, height) && kept_.width() == width && kept_.height() == height;
    if (!kept || !warped_.ensure(width, height)) {
        glBindFramebuffer(GL_FRAMEBUFFER, drawFbo_);
        glViewport(0, 0, width, height);
        return false;
    }
    glBindFramebuffer(GL_FRAMEBUFFER, warped_.fbo());
    warped_.discardContents();
    glViewport(0, 0, width, height);
    glDisable(GL_BLEND);
    glUseProgram(warpProgram_);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, kept_.tex());
    glUniform1i(warpUniforms_.loc("uFbPrev"), 0);
    uploadWarp(width, height);
    glBindVertexArray(vao_);
    glDrawArrays(GL_TRIANGLES, 0, feedback::kGridVertices);
    glBindVertexArray(0);
    warpFrame_ = (warpFrame_ + 1u) % inputs::kFrameWrap;
    // The Style draws over the warped picture into kept_, which nothing reads any more this frame.
    glBindFramebuffer(GL_FRAMEBUFFER, kept_.fbo());
    glViewport(0, 0, width, height);
    return true;
}

void ShaderScene::uploadWarp(int width, int height) {
    const feedback::Step& step = warpStep_;
    UniformCache& u = warpUniforms_;
    glUniform2f(u.loc("uFbGrid"), static_cast<float>(feedback::kGridColumns), static_cast<float>(feedback::kGridRows));
    const auto& map = step.source.map;
    glUniform4f(u.loc("uFbMap"), map[0], map[1], map[2], map[3]);
    glUniform2f(u.loc("uFbShift"), step.source.shift[0], step.source.shift[1]);
    glUniform2f(u.loc("uFbStretch"), step.stretch[0], step.stretch[1]);
    glUniform2f(u.loc("uFbCenter"), step.center[0], step.center[1]);
    glUniform1f(u.loc("uFbAspect"), static_cast<float>(width) / static_cast<float>(height));
    glUniform1f(u.loc("uFbWarp"), step.warp);
    glUniform1f(u.loc("uFbPhase"), step.phase);
    glUniform2f(u.loc("uFbTexel"), 1.0f / static_cast<float>(width), 1.0f / static_cast<float>(height));
    glUniform1f(u.loc("uFbDt"), step.dt);
    glUniform3f(u.loc("uFbKeep"), step.keep[0], step.keep[1], step.keep[2]);
    glUniform1f(u.loc("uFbFloor"), step.floor);
    const feedback::Precision precision = precisionOf(warped_.format());
    const bool half = precision == feedback::Precision::Half;
    glUniform1f(u.loc("uFbHalf"), flag(half));
    glUniform1f(u.loc("uFbStep"), half ? 0.0f : feedback::quantum(0.0f, precision));
    glUniform1f(u.loc("uFbSeed"), static_cast<float>(warpFrame_));
    glUniform4f(u.loc("uFbOuter"), step.outer.color[0], step.outer.color[1], step.outer.color[2], step.outer.cover);
    glUniform1f(u.loc("uFbOuterSize"), step.outer.size);
    glUniform4f(u.loc("uFbInner"), step.inner.color[0], step.inner.color[1], step.inner.color[2], step.inner.cover);
    glUniform1f(u.loc("uFbInnerSize"), step.inner.size);
    glUniform4f(u.loc("uFbStreak"), step.streaks.color[0], step.streaks.color[1], step.streaks.color[2], step.streaks.cover);
    glUniform1f(u.loc("uFbStreakCell"), step.streaks.size);
}

void ShaderScene::presentFeedback() {
    // What the Style drew is kept for the next frame and shown: copied onto the renderer's target.
    glBindFramebuffer(GL_READ_FRAMEBUFFER, kept_.fbo());
    glBindFramebuffer(GL_DRAW_FRAMEBUFFER, drawFbo_);
    glBlitFramebuffer(0, 0, kept_.width(), kept_.height(), 0, 0, drawWidth_, drawHeight_, GL_COLOR_BUFFER_BIT, GL_NEAREST);
    glBindFramebuffer(GL_FRAMEBUFFER, drawFbo_);
    glViewport(0, 0, drawWidth_, drawHeight_);
}

void ShaderScene::buildResolveIfNeeded() {
    if (!multipass_ || resolveBuilt_) return;
    // Once: a resolve that does not compile is reported and the Style draws in one pass.
    resolveBuilt_ = true;
    std::string error;
    resolveProgram_ = program::build(multipass_->vertex, multipass_->resolve, cache_, &error);
    if (resolveProgram_ == 0) {
        host_.onShaderError(error);
        return;
    }
    resolveUniforms_ = UniformCache(resolveProgram_);
}

multipass::Plan ShaderScene::passPlan() const {
    if (!multipass_ || feedback_ || resolveProgram_ == 0 || sharedInputs_ == nullptr) return multipass::Plan{};
    const bool halfFloat = sharedInputs_->format() == Framebuffer::Format::Rgba16F;
    multipass::Plan plan = multipass::plan(multipass_->spec, drawWidth_, drawHeight_, halfFloat);
    // A pack with four images leaves no unit for the prepass: its rays start at the camera.
    if (coarseUnit() >= kGuaranteedUnits) plan.coarse = false;
    return plan;
}

bool ShaderScene::drawPasses(const multipass::Plan& plan) {
    const int width = drawWidth_;
    const int height = drawHeight_;
    bool ready = halfFloatTarget(passMarch_, plan.march.width, plan.march.height);
    ready = ready && (!plan.coarse || halfFloatTarget(passCoarse_, plan.coarseSize.width, plan.coarseSize.height));
    bool temporal = plan.temporal && ready;
    if (temporal) {
        // The history follows the target's size; a new size has nothing to reproject (History::begin).
        temporal = halfFloatTarget(passCamera_[0], multipass::kCameraTexels, 1) && halfFloatTarget(passCamera_[1], multipass::kCameraTexels, 1) &&
                   halfFloatTarget(passResolved_, width, height);
    }
    if (!ready) {
        glBindFramebuffer(GL_FRAMEBUFFER, drawFbo_);
        glViewport(0, 0, width, height);
        return false;
    }
    const bool historyOn = temporal && passHistory_.begin(width, height);
    const int now = static_cast<int>(passHistory_.frame() & 1u);
    const std::array<float, 2> still{0.0f, 0.0f};
    const std::array<float, 2> jitter = temporal ? multipass::jitter(passHistory_.frame()) : still;
    glBindVertexArray(vao_);
    if (temporal) drawPass(passCamera_[static_cast<size_t>(now)].fbo(), multipass::kCameraTexels, 1, kPassCamera, false, still);
    if (plan.coarse) drawPass(passCoarse_.fbo(), plan.coarseSize.width, plan.coarseSize.height, kPassCoarse, false, still);
    const std::array<float, 2> jitterUv{jitter[0] / static_cast<float>(plan.march.width), jitter[1] / static_cast<float>(plan.march.height)};
    if (plan.coarse) {
        glActiveTexture(static_cast<GLenum>(GL_TEXTURE0 + coarseUnit()));
        glBindTexture(GL_TEXTURE_2D, passCoarse_.tex());
        glActiveTexture(GL_TEXTURE0);
    }
    drawPass(passMarch_.fbo(), plan.march.width, plan.march.height, kPassPicture, plan.coarse, jitterUv);
    glBindVertexArray(0);
    resolvePasses(historyOn, jitter, now);
    if (temporal) {
        // What was resolved is kept for the next frame's reprojection.
        blitInto(drawFbo_, width, height, passResolved_, GL_NEAREST);
        passHistory_.kept();
    } else {
        passHistory_.lost();
    }
    glBindFramebuffer(GL_FRAMEBUFFER, drawFbo_);
    glViewport(0, 0, width, height);
    glUseProgram(program_);
    return true;
}

void ShaderScene::drawPass(GLuint fbo, int width, int height, float pass, bool useCoarse, const std::array<float, 2>& jitterUv) {
    glBindFramebuffer(GL_FRAMEBUFFER, fbo);
    Framebuffer::discardBound(fbo);
    glViewport(0, 0, width, height);
    glUniform2f(uniforms_.loc("uResolution"), static_cast<float>(width), static_cast<float>(height));
    set1f("uMpPass", pass);
    set1f("uMpUseCoarse", flag(useCoarse));
    glUniform1i(uniforms_.loc("uMpCoarse"), std::min(coarseUnit(), kGuaranteedUnits - 1));
    glUniform2f(uniforms_.loc("uMpJitter"), jitterUv[0], jitterUv[1]);
    glDrawArrays(GL_TRIANGLES, 0, 3);
}

void ShaderScene::resolvePasses(bool historyOn, const std::array<float, 2>& jitter, int now) {
    const int width = drawWidth_;
    const int height = drawHeight_;
    glBindFramebuffer(GL_FRAMEBUFFER, drawFbo_);
    Framebuffer::discardBound(drawFbo_);
    glViewport(0, 0, width, height);
    glUseProgram(resolveProgram_);
    UniformCache& u = resolveUniforms_;
    const GLuint black = sharedInputs_->black();
    const auto bind = [](GLint unit, GLuint texture) {
        glActiveTexture(static_cast<GLenum>(GL_TEXTURE0 + unit));
        glBindTexture(GL_TEXTURE_2D, texture);
    };
    bind(kResolveMarchUnit, passMarch_.tex());
    bind(kResolveHistoryUnit, historyOn ? passResolved_.tex() : black);
    bind(kResolveCamNowUnit, historyOn ? passCamera_[static_cast<size_t>(now)].tex() : black);
    bind(kResolveCamPrevUnit, historyOn ? passCamera_[static_cast<size_t>(1 - now)].tex() : black);
    glActiveTexture(GL_TEXTURE0);
    glUniform1i(u.loc("uMpMarch"), kResolveMarchUnit);
    glUniform1i(u.loc("uMpHistory"), kResolveHistoryUnit);
    glUniform1i(u.loc("uMpCamNow"), kResolveCamNowUnit);
    glUniform1i(u.loc("uMpCamPrev"), kResolveCamPrevUnit);
    glUniform1f(u.loc("uMpHistoryOn"), flag(historyOn));
    glUniform2f(u.loc("uMpJitter"), jitter[0], jitter[1]);
    glUniform1f(u.loc("uMpAspect"), static_cast<float>(width) / static_cast<float>(height));
    glUniform2f(u.loc("uMpTexel"), 1.0f / static_cast<float>(width), 1.0f / static_cast<float>(height));
    glBindVertexArray(vao_);
    glDrawArrays(GL_TRIANGLES, 0, 3);
    glBindVertexArray(0);
}

void ShaderScene::releasePasses() {
    passMarch_.release();
    passCoarse_.release();
    for (Framebuffer& camera : passCamera_) camera.release();
    passResolved_.release();
    passHistory_.lost();
}

void ShaderScene::set1f(const char* name, float value) {
    glUniform1f(uniforms_.loc(name), value);
}

float ShaderScene::marchSteps(float detail) {
    if (detail != stepsDetail_) {
        stepsDetail_ = detail;
        const float t = std::clamp((detail - kMarchMinDetail) / (kMarchMaxDetail - kMarchMinDetail), 0.0f, 1.0f);
        stepsBudget_ = static_cast<float>(std::lround(kMarchFloorSteps + (kMarchMaxSteps - kMarchFloorSteps) * t));
    }
    return stepsBudget_;
}

void ShaderScene::compilePendingIfAny() {
    std::optional<std::string> pending;
    {
        std::lock_guard<std::mutex> lock(pendingLock_);
        pending.swap(pendingFragment_);
    }
    if (!pending) return;
    std::string error;
    const GLuint built = program::build(vertexSrc_, *pending, cache_, &error);
    if (built == 0) {
        host_.onShaderError(error);
        return;
    }
    if (program_ != 0) glDeleteProgram(program_);
    program_ = built;
    uniforms_ = UniformCache(built);
    ownsCamera_ = glGetUniformLocation(built, "uCamRot") >= 0;
    currentFragment_ = *pending;
    host_.onShaderError("");
}

#ifdef GEODE_STYLE_HOT_RELOAD
void ShaderScene::replaceFragment(std::string fragment) {
    std::lock_guard<std::mutex> lock(pendingLock_);
    pendingFragment_ = std::move(fragment);
}
#endif

void ShaderScene::release() {
    if (program_ != 0) glDeleteProgram(program_);
    if (warpProgram_ != 0) glDeleteProgram(warpProgram_);
    warpProgram_ = 0;
    warpUniforms_ = UniformCache(0);
    warpBuilt_ = false;
    if (resolveProgram_ != 0) glDeleteProgram(resolveProgram_);
    resolveProgram_ = 0;
    resolveUniforms_ = UniformCache(0);
    resolveBuilt_ = false;
    if (vao_ != 0) glDeleteVertexArrays(1, &vao_);
    audioTex_.release();
    for (Texture& image : imageTex_) image.release();
    releaseHistory();
    program_ = 0;
    vao_ = 0;
    uniforms_ = UniformCache(0);
    ownsCamera_ = false;
}

}  // namespace geode::viz
