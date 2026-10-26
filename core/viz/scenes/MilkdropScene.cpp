#include "scenes/MilkdropScene.hpp"

#include <algorithm>
#include <array>
#include <chrono>
#include <cmath>

#include "geode/util/Log.hpp"
#include "geode/render/Quad.hpp"

namespace geode::viz {

namespace {

constexpr const char* kTag = "milkdrop-jni";

std::string parentDir(const std::string& path) {
    const size_t slash = path.find_last_of('/');
    return slash == std::string::npos || slash == 0 ? "/" : path.substr(0, slash);
}

std::string stemOf(const std::string& path) {
    const size_t slash = path.find_last_of('/');
    const std::string name = slash == std::string::npos ? path : path.substr(slash + 1);
    const size_t dot = name.find_last_of('.');
    return dot == std::string::npos ? name : name.substr(0, dot);
}

}  // namespace

void MilkdropScene::EngineDeleter::operator()(projectm_handle h) const {
    projectm_destroy(h);
}

void MilkdropScene::onPresetSwitchFailed(const char* presetFilename, const char* message, void* userData) {
    auto* self = static_cast<MilkdropScene*>(userData);
    if (!self) return;
    std::lock_guard<std::mutex> lock(self->errorLock_);
    self->lastError_ = std::string(presetFilename ? presetFilename : "?") + ": " + (message ? message : "unknown error");
    // projectM can raise this from inside a render as well as from a load, so the same broken
    // preset reports once per frame. Only a message that has changed is worth a line.
    if (self->lastLoggedError_ == self->lastError_) return;
    self->lastLoggedError_ = self->lastError_;
    GEODE_LOGE(kTag, "preset switch failed: %s", self->lastError_.c_str());
}

bool MilkdropScene::loadProgram(const char* vertexSource, const char* fragmentSource, uint32_t program, void* userData) {
    auto* self = static_cast<MilkdropScene*>(userData);
    ProgramBinaryCache* cache = self ? self->loader_.cache : nullptr;
    if (!cache || !vertexSource || !fragmentSource) return false;
    const auto key = cache->keyFor(vertexSource, fragmentSource);
    if (!key) return false;
    if (cache->loadInto(*key, program)) return true;
    // Compiled from source next: asked to keep its binary, which only a hint before linking ensures.
    cache->markRetrievable(program);
    return false;
}

void MilkdropScene::storeProgram(const char* vertexSource, const char* fragmentSource, uint32_t program, void* userData) {
    auto* self = static_cast<MilkdropScene*>(userData);
    ProgramBinaryCache* cache = self ? self->loader_.cache : nullptr;
    if (!cache || !vertexSource || !fragmentSource) return;
    if (const auto key = cache->keyFor(vertexSource, fragmentSource)) cache->store(*key, program);
}

const char* MilkdropScene::lookupText(const char* key, size_t keyLength, void* userData) {
    auto* self = static_cast<MilkdropScene*>(userData);
    ProgramBinaryCache* cache = self ? self->loader_.cache : nullptr;
    if (!cache || !key) return nullptr;
    auto text = cache->loadText(std::string(key, keyLength));
    if (!text) return nullptr;
    self->keptText_ = std::move(*text);
    return self->keptText_.c_str();
}

void MilkdropScene::storeText(const char* key, size_t keyLength, const char* text, void* userData) {
    auto* self = static_cast<MilkdropScene*>(userData);
    ProgramBinaryCache* cache = self ? self->loader_.cache : nullptr;
    if (!cache || !key || !text) return;
    cache->storeText(std::string(key, keyLength), text);
}

MilkdropScene::CachesInUse::CachesInUse(MilkdropScene* scene) {
    projectm_opengl_set_shader_caches(&loadProgram, &storeProgram, &lookupText, &storeText, scene);
}

// Cleared again, so nothing compiled later on this thread reaches a scene that may be gone by then.
MilkdropScene::CachesInUse::~CachesInUse() {
    projectm_opengl_set_shader_caches(nullptr, nullptr, nullptr, nullptr, nullptr);
}

double MilkdropScene::nowSeconds() {
    return std::chrono::duration<double>(std::chrono::steady_clock::now().time_since_epoch()).count();
}

std::optional<std::string> MilkdropScene::takeError() {
    std::lock_guard<std::mutex> lock(errorLock_);
    if (lastError_.empty()) return std::nullopt;
    std::string out;
    out.swap(lastError_);
    return out;
}

void MilkdropScene::acceptPcm(const float* samples, int count) {
    const int n = std::min(count, kPcmCapacity);
    if (n <= 0) return;
    if (pcmCount_ + n > kPcmCapacity) pcmCount_ = 0;
    std::copy(samples + (count - n), samples + count, pcm_.begin() + pcmCount_);
    pcmCount_ += n;
}

void MilkdropScene::acceptStereoPcm(const float* interleaved, int frames) {
    const int n = std::min(frames, kEnginePcmSamples);
    if (!interleaved || n <= 0) return;
    // The newest frames of the window, replacing the last one: a window is added to projectM once.
    std::copy(interleaved + static_cast<size_t>(frames - n) * 2, interleaved + static_cast<size_t>(frames) * 2, stereo_.begin());
    stereoFrames_ = n;
}

void MilkdropScene::queueMilkPreset(const std::string& path) {
    std::lock_guard<std::mutex> lock(presetLock_);
    pendingPresetPath_ = path;
}

void MilkdropScene::reloadMilkPreset() {
    std::lock_guard<std::mutex> lock(presetLock_);
    if (lastPresetPath_.empty()) return;
    pendingPresetPath_ = lastPresetPath_;
    lastLoadSeconds_ = 0.0;
}

void MilkdropScene::setMilkTextureDir(const std::string& dir) {
    std::lock_guard<std::mutex> lock(presetLock_);
    sharedTextureDir_ = dir;
}

void MilkdropScene::init() {
    release();
    reportedCreateFailure_ = false;
    reportedEngineGlError_ = false;
    lastCreateAttemptSeconds_ = 0.0;
    // A new context is a new chance for a preset that failed under the old one, and the scene
    // must not inherit the old context's load debounce either.
    lastLoadSeconds_ = 0.0;
    {
        std::lock_guard<std::mutex> lock(errorLock_);
        lastLoggedError_.clear();
    }
    diagFrames_ = 0;
    diagDone_ = false;
    errorChecksLeft_ = kErrorCheckFrames;
}

void MilkdropScene::resize(int width, int height) {
    width_ = width;
    height_ = height;
}

void MilkdropScene::setDrawTarget(GLuint fbo, int width, int height) {
    drawTargetHanded_ = true;
    drawFbo_ = fbo;
    drawWidth_ = width;
    drawHeight_ = height;
}

// The engine renders at the scene target's size (resize()) times the load adapter's buffer step. A
// new size waits for the next preset change, where the incoming preset starts at it and blends from the
// old picture, so the restart never shows; only a first size or a new shape applies at once.
void MilkdropScene::applyEngineSize(bool atPresetChange) {
    const milkdrop::BufferSize size = milkdrop::bufferSize(width_, height_, adapter_.bufferScale());
    if (size.width == engineWidth_ && size.height == engineHeight_) return;
    if (!atPresetChange && !milkdrop::resizeNow({engineWidth_, engineHeight_}, size)) return;
    projectm_set_window_size(engine_.get(), static_cast<size_t>(size.width), static_cast<size_t>(size.height));
    engineWidth_ = size.width;
    engineHeight_ = size.height;
}

void MilkdropScene::ensureEngine(double now) {
    if (width_ <= 1 || height_ <= 1) return;
    if (!engine_) {
        // Between attempts the scene draws its (empty) frame rather than hammering a create that
        // has just failed. The wait is also what makes recovery possible at all: the failure is
        // usually the context, not the engine, so the next attempt after one is worth making.
        if (reportedCreateFailure_ && now - lastCreateAttemptSeconds_ < kCreateRetrySeconds) return;
        lastCreateAttemptSeconds_ = now;
        engine_.reset(projectm_create());
        if (!engine_) {
            // Once, not once per frame: ensureEngine runs from draw().
            if (!reportedCreateFailure_) {
                reportedCreateFailure_ = true;
                host_.onShaderError("projectM engine failed to initialize (adb logcat -s milkdrop-jni)");
                GEODE_LOGE(kTag, "projectm_create returned NULL; retrying every %.0fs", kCreateRetrySeconds);
            }
            return;
        }
        reportedCreateFailure_ = false;
        errorChecksLeft_ = kErrorCheckFrames;
        if (rendering_) {
            // Before the texture paths and the first preset, which draw from the seed.
            projectm_set_random_seed(engine_.get(), milkdrop::kRenderSeed);
            projectm_set_frame_time(engine_.get(), renderClock_.seconds());
        }
        appliedMesh_ = {0, 0};
        appliedFps_ = 0;
        presetSinceCreate_ = false;
        projectm_set_soft_cut_duration(engine_.get(), 3.0);
        projectm_set_preset_duration(engine_.get(), 999999.0);
        projectm_set_preset_locked(engine_.get(), true);
        projectm_set_aspect_correction(engine_.get(), true);
        projectm_set_preset_switch_failed_event_callback(engine_.get(), onPresetSwitchFailed, this);
        armLastPreset();
        engineWidth_ = 0;
        engineHeight_ = 0;
    }
    applyEngineSize(false);
}

// The host raises the flag for the whole of a Render. Either way the engine starts over: a Render
// needs a fresh one, seeded before anything random is drawn, and the live Visual must not inherit
// the render clock.
void MilkdropScene::followRenderMode() {
    const bool rendering = host_.rendering && host_.rendering();
    if (rendering == rendering_) return;
    rendering_ = rendering;
    engine_.reset();
    engineWidth_ = 0;
    engineHeight_ = 0;
    reportedCreateFailure_ = false;
    adapter_.reset();
    renderClock_.reset();
}

void MilkdropScene::applyFrameSettings() {
    const milkdrop::MeshSize mesh = adapter_.mesh();
    if (mesh != appliedMesh_) {
        projectm_set_mesh_size(engine_.get(), static_cast<size_t>(mesh.columns), static_cast<size_t>(mesh.rows));
        appliedMesh_ = mesh;
    }
    const int fps = rendering_ ? milkdrop::renderFps(lastDt_)
                               : milkdrop::engineFps(host_.pacedFps ? host_.pacedFps() : 0.0f, adapter_.averageFps());
    if (fps != appliedFps_) {
        projectm_set_fps(engine_.get(), fps);
        appliedFps_ = fps;
    }
    if (rendering_) projectm_set_frame_time(engine_.get(), renderClock_.seconds());
}

// The engine is destroyed and rebuilt whenever the context is - a trip to the background and
// back is the common case - and a rebuilt engine holds no preset. lastPresetPath_ outlives it,
// so the scene comes back on the preset it went away on.
void MilkdropScene::armLastPreset() {
    std::lock_guard<std::mutex> lock(presetLock_);
    if (lastPresetPath_.empty()) return;
    pendingPresetPath_ = lastPresetPath_;
    lastLoadSeconds_ = 0.0;
}

void MilkdropScene::update(const GeodeFeatureFrame& features, float dt) {
    followRenderMode();
    lastDt_ = dt;
    if (rendering_) {
        renderClock_.advance(dt);
    } else {
        adapter_.onFrame(dt, host_.pacedFps ? host_.pacedFps() : 0.0f);
    }
    if (!engine_) return;
    if (stereoFrames_ > 0) {
        projectm_pcm_add_float(engine_.get(), stereo_.data(), static_cast<unsigned int>(stereoFrames_), PROJECTM_STEREO);
        stereoFrames_ = 0;
        pcmCount_ = 0;
    } else if (pcmCount_ > 0) {
        const int n = std::min(pcmCount_, kEnginePcmSamples);
        if (n < pcmCount_) std::copy(pcm_.begin() + (pcmCount_ - n), pcm_.begin() + pcmCount_, pcm_.begin());
        pcmCount_ = 0;
        projectm_pcm_add_float(engine_.get(), pcm_.data(), static_cast<unsigned int>(n), PROJECTM_MONO);
    } else {
        const int n = std::min(GEODE_WAVEFORM_POINTS, kEnginePcmSamples);
        projectm_pcm_add_float(engine_.get(), features.waveform + (GEODE_WAVEFORM_POINTS - n), static_cast<unsigned int>(n), PROJECTM_MONO);
    }
}

void MilkdropScene::loadPendingPreset(double now) {
    std::string path;
    std::string shared;
    {
        std::lock_guard<std::mutex> lock(presetLock_);
        // A Render loads its preset on the frame it asks for it: the debounce is for a person
        // scrolling through presets, and waiting on the wall clock would make frames differ.
        if (pendingPresetPath_.empty() || (!rendering_ && now - lastLoadSeconds_ < kLoadDebounceSeconds)) return;
        path.swap(pendingPresetPath_);
        shared = sharedTextureDir_;
    }
    lastLoadSeconds_ = now;
    const std::string dir = parentDir(path);
    // The per-preset link directory goes first: search order is the only precedence projectM has.
    std::vector<std::string> dirs = {dir + "/textures/.links/" + stemOf(path), dir, dir + "/textures"};
    if (!shared.empty()) dirs.push_back(shared);
    std::vector<const char*> paths;
    for (const auto& d : dirs) paths.push_back(d.c_str());
    projectm_set_texture_search_paths(engine_.get(), paths.data(), paths.size());
    {
        std::lock_guard<std::mutex> lock(errorLock_);
        lastError_.clear();
    }
    // The preset change is where a new buffer step and a new target size land: the incoming preset
    // starts at the new size and blends from the old picture, so the resize never shows.
    adapter_.onPresetChange();
    applyEngineSize(true);
    const bool blend = params_.milkdropBlendPresets && !(rendering_ && !presetSinceCreate_);
    projectm_load_preset_file(engine_.get(), path.c_str(), blend);
    errorChecksLeft_ = kErrorCheckFrames;
    projectm_set_preset_locked(engine_.get(), true);
    const auto error = takeError();
    host_.onShaderError(error.value_or(""));
    if (!error) {
        presetSinceCreate_ = true;
        {
            std::lock_guard<std::mutex> lock(presetLock_);
            lastPresetPath_ = path;
        }
        {
            // A preset that loads clears the dedupe, so if this one later starts failing the
            // first failure is still logged.
            std::lock_guard<std::mutex> lock(errorLock_);
            lastLoggedError_.clear();
        }
        if (host_.onMilkPresetLoaded) host_.onMilkPresetLoaded(path);
    }
}

void MilkdropScene::draw(float timeSeconds) {
    (void) timeSeconds;
    GLint target = static_cast<GLint>(drawFbo_);
    if (!drawTargetHanded_) glGetIntegerv(GL_DRAW_FRAMEBUFFER_BINDING, &target);
    const int targetWidth = drawTargetHanded_ ? drawWidth_ : width_;
    const int targetHeight = drawTargetHanded_ ? drawHeight_ : height_;
    // Reset before the engine as well as after: projectM assumes default GL state and restores little.
    resetFrameState();
    // Making the engine and loading a preset compile shaders: from the caches when they hold them.
    const CachesInUse caches(this);
    const double now = nowSeconds();
    ensureEngine(now);
    if (!engine_) return;
    // Mesh, rate and clock first: a preset loaded this frame initialises against them.
    applyFrameSettings();
    loadPendingPreset(now);
    if (engineWidth_ <= 0 || engineHeight_ <= 0) return;
    projectm_set_beat_sensitivity(engine_.get(), std::clamp(0.2f + params_.beatResponse, 0.2f, 3.0f));

    // projectM draws its frame onto the renderer's target itself when the two are the same size: no
    // copy of its own, and the composite grades it (#303). Otherwise, and while the first frames are
    // checked for a black picture (diagnoseBlackFrame reads the scene's own 8-bit target), it draws into
    // the scene's framebuffer, which is then scaled onto the target.
    const bool direct = diagDone_ && engineWidth_ == targetWidth && engineHeight_ == targetHeight;
    if (!direct && !frame_.ensure(engineWidth_, engineHeight_)) return;
    projectm_opengl_render_frame_fbo(engine_.get(), direct ? static_cast<uint32_t>(target) : frame_.fbo());
    if (const auto error = takeError()) host_.onShaderError(*error);
    if (!direct) diagnoseBlackFrame();
    drainEngineErrors();

    resetFrameState();
    if (!direct) {
        glBindFramebuffer(GL_READ_FRAMEBUFFER, frame_.fbo());
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, static_cast<GLuint>(target));
        glBlitFramebuffer(0, 0, engineWidth_, engineHeight_, 0, 0, targetWidth, targetHeight, GL_COLOR_BUFFER_BIT, GL_LINEAR);
    }
    glBindFramebuffer(GL_FRAMEBUFFER, static_cast<GLuint>(target));
    glViewport(0, 0, targetWidth, targetHeight);
}

// Samples the scene's own target, i.e. what the post pass is about to display; a black result here
// means the engine produced nothing, not that a copy failed somewhere in between.
void MilkdropScene::diagnoseBlackFrame() {
    if (diagDone_ || diagFrames_ >= kDiagFrames) return;
    diagFrames_++;
    if (diagFrames_ <= kDiagWarmup) return;
    const int w = frame_.width();
    const int h = frame_.height();
    std::array<unsigned char, 8> px{};
    glBindFramebuffer(GL_READ_FRAMEBUFFER, frame_.fbo());
    glReadPixels(w / 2, h / 2, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, px.data());
    glReadPixels(w / 3, h / 3, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, px.data() + 4);
    const bool sawLight = px[0] > 8 || px[1] > 8 || px[2] > 8 || px[4] > 8 || px[5] > 8 || px[6] > 8;
    if (sawLight) {
        diagDone_ = true;
    } else if (diagFrames_ >= kDiagFrames) {
        diagDone_ = true;
        std::string preset;
        {
            std::lock_guard<std::mutex> lock(presetLock_);
            preset = lastPresetPath_.empty() ? "idle" : lastPresetPath_.substr(lastPresetPath_.find_last_of('/') + 1);
        }
        host_.onShaderError("MilkDrop diagnostic: the engine painted a black frame for " + std::to_string(kDiagFrames) + " frames at " +
                            std::to_string(w) + "x" + std::to_string(h) + " (preset=" + preset +
                            "). adb logcat -s milkdrop-jni for the native side.");
    }
}

// projectM leaves whatever GL errors its preset pipeline raised in the queue. Clear them so the
// renderer's own probes stay attributable, but report the first one: a silent drain is how the
// previous copy-from-window failure went unnoticed. Read only in the first frames of an engine or a
// preset (kErrorCheckFrames); an error raised later stays queued, and the probes drain the queue first.
void MilkdropScene::drainEngineErrors() {
    if (errorChecksLeft_ <= 0) return;
    errorChecksLeft_--;
    GLenum first = GL_NO_ERROR;
    for (int drained = 0; drained < 8; ++drained) {
        const GLenum err = glGetError();
        if (err == GL_NO_ERROR) break;
        if (first == GL_NO_ERROR) first = err;
    }
    if (first != GL_NO_ERROR && !reportedEngineGlError_) {
        reportedEngineGlError_ = true;
        GEODE_LOGW(kTag, "projectM left GL error 0x%x pending after rendering (reported once)", first);
    }
}

void MilkdropScene::release() {
    engine_.reset();
    engineWidth_ = 0;
    engineHeight_ = 0;
    adapter_.reset();
    frame_.release();
}

}  // namespace geode::viz
