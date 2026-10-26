#include <algorithm>
#include <array>
#include <atomic>
#include <cmath>
#include <cstring>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include "geode/api/geode_api.h"
#include "geode/viz/DisplayEvents.hpp"
#include "geode/viz/Normaliser.hpp"
#include "geode/viz/PlanCodec.hpp"
#include "geode/viz/Renderer.hpp"
#include "FeedHandle.hpp"
#include "SnapshotHandle.hpp"

struct geode_viz {
    geode_viz(AAssetManager* assets, std::string cacheDir) : renderer(assets, std::move(cacheDir)) {}

    geode::viz::Renderer renderer;
    std::mutex configLock;
    std::array<geode::viz::LfoConfig, geode::viz::LfoEngine::kSlots> lfo{};
    std::array<geode::viz::AdsrConfig, geode::viz::AdsrEngine::kCount> adsr{};
    // The Customization snapshots this renderer reads at the start of each frame; null when none.
    std::atomic<geode_snapshots*> snapshots{nullptr};
    // The last feature frame, under configLock, for the Customization's evaluation.
    GeodeFeatureFrame features{};
    // The Customization the last snapshot carried and its per-frame evaluation (ADR-0002), both
    // touched on the drawing thread only.
    geode::viz::mod::CustomizationPlan plan;
    bool hasPlan = false;
    geode::viz::mod::ReactionGraph graph;
    double lastFrameSeconds = -1.0;
    // The live sound this Visual reads at the start of each frame; null when it is handed features instead.
    // Drawing thread only, as are the frame's window and the edge detection on the feed's events.
    std::shared_ptr<geode::api::Feed> feed;
    std::array<float, GEODE_FEED_WINDOW_FRAMES * 2> feedPcm{};
    geode::viz::DisplayEvents feedEvents;
};

namespace {

using namespace geode::viz;

static_assert(LfoEngine::kSlots == GEODE_LFO_SLOTS);
static_assert(Renderer::kPcmWindowFrames == GEODE_FEED_WINDOW_FRAMES);
static_assert(AdsrEngine::kCount == GEODE_ADSR_SLOTS);
// The snapshot schema (core/bridge/schema/native_bridge.json) must describe exactly what the Visual reads.
static_assert(geode::bridge::kSnapshotParamsCount == static_cast<size_t>(SceneParams::kFieldCount));
static_assert(geode::bridge::kSnapshotLfoCount == static_cast<size_t>(GEODE_LFO_SLOTS * GEODE_LFO_CONFIG_FLOATS));
static_assert(geode::bridge::kSnapshotAdsrCount == static_cast<size_t>(GEODE_ADSR_SLOTS * GEODE_ADSR_CONFIG_FLOATS));
static_assert(geode::bridge::kSnapshotPlanCount == geode::viz::mod::kPlanFloats);

constexpr int kModSourceCount = static_cast<int>(ModSource::StereoPan) + 1;
constexpr int kLfoTargetCount = static_cast<int>(LfoTarget::Lfo3Depth) + 1;
constexpr int kLfoWaveCount = static_cast<int>(LfoWave::Random) + 1;
constexpr int kModPolarityCount = static_cast<int>(ModPolarity::Negative) + 1;
constexpr int kModCurveCount = static_cast<int>(ModCurve::Smooth) + 1;
constexpr int kEnvBandCount = static_cast<int>(EnvBand::Width) + 1;

template <typename E>
E enumAt(float value, int count) {
    return static_cast<E>(std::clamp(static_cast<int>(std::lround(value)), 0, count - 1));
}

bool flag(float value) { return value > 0.5f; }

std::string text(const char* s) { return s ? std::string(s) : std::string(); }

}  // namespace

extern "C" {

geode_viz* geode_viz_create(AAssetManager* assets, const char* cache_dir) {
    if (!assets || !cache_dir) return nullptr;
    return std::make_unique<geode_viz>(assets, std::string(cache_dir)).release();
}

void geode_viz_destroy(geode_viz* v) {
    std::unique_ptr<geode_viz> owned(v);
}

int geode_viz_param_count(void) {
    return SceneParams::kFieldCount;
}

const char* geode_viz_param_name(int index) {
    if (index < 0 || index >= SceneParams::kFieldCount) return nullptr;
    return SceneParams::fieldNames()[static_cast<size_t>(index)];
}

void geode_viz_set_params(geode_viz* v, const float* values, int count) {
    if (!v || !values || count != SceneParams::kFieldCount) return;
    const auto& names = SceneParams::fieldNames();
    SceneParams p;
    for (int i = 0; i < count; ++i) p.set(names[static_cast<size_t>(i)], values[i]);
    v->renderer.setParams(p);
}

int geode_viz_set_param(geode_viz* v, const char* name, float value) {
    return (v && name && v->renderer.setParam(name, value)) ? 1 : 0;
}

void geode_viz_set_features(geode_viz* v, const GeodeFeatureFrame* frame) {
    if (!v || !frame) return;
    v->renderer.setFeatures(*frame);
    std::lock_guard<std::mutex> lock(v->configLock);
    v->features = *frame;
}

void geode_viz_set_reduced_motion(geode_viz* v, int on) {
    if (v) v->renderer.setReducedMotion(on != 0);
}

void geode_viz_set_layer(geode_viz* v, const char* scene_id, float mix, int blend_mode) {
    if (v) v->renderer.setLayer(text(scene_id), mix, blend_mode);
}

void geode_viz_set_transition(geode_viz* v, const char* id, int64_t duration_ms) {
    if (v) v->renderer.setTransition(text(id), duration_ms);
}

void geode_viz_begin_param_morph(geode_viz* v, float seconds) {
    if (v) v->renderer.beginParamMorph(seconds);
}

void geode_viz_set_touch(geode_viz* v, const float* xy_ndc, int points) {
    if (!v) return;
    v->renderer.submitTouchPoints(xy_ndc ? xy_ndc : nullptr, xy_ndc ? std::max(points, 0) : 0);
}

void geode_viz_queue_touch_stroke(geode_viz* v, float nx, float ny, float ndx, float ndy, float dt, float strength) {
    if (v) v->renderer.queueTouchStroke(nx, ny, ndx, ndy, dt, strength);
}

void geode_viz_load_milk_preset(geode_viz* v, const char* path) {
    if (v && path) v->renderer.loadMilkPreset(path);
}

void geode_viz_reload_milk_preset(geode_viz* v) {
    if (v) v->renderer.reloadMilkPreset();
}

void geode_viz_set_milk_texture_dir(geode_viz* v, const char* dir) {
    if (v && dir) v->renderer.setMilkTextureDir(dir);
}

size_t geode_viz_take_milk_preset_loaded(geode_viz* v, char* out, size_t capacity) {
    if (!v) return 0;
    const std::string path = v->renderer.takeMilkPresetLoaded();
    if (out && capacity > 0) {
        const size_t n = std::min(capacity - 1, path.size());
        std::memcpy(out, path.data(), n);
        out[n] = '\0';
    }
    return path.size();
}

void geode_viz_set_lfo(geode_viz* v, int slot, const float* c, int count) {
    if (!v || !c || slot < 0 || slot >= GEODE_LFO_SLOTS || count < GEODE_LFO_CONFIG_FLOATS) return;
    LfoConfig cfg;
    cfg.enabled = flag(c[0]);
    cfg.source = enumAt<ModSource>(c[1], kModSourceCount);
    cfg.target = enumAt<LfoTarget>(c[2], kLfoTargetCount);
    cfg.wave = enumAt<LfoWave>(c[3], kLfoWaveCount);
    cfg.rateSeconds = c[4];
    cfg.depth = c[5];
    cfg.polarity = enumAt<ModPolarity>(c[6], kModPolarityCount);
    cfg.curve = enumAt<ModCurve>(c[7], kModCurveCount);
    std::lock_guard<std::mutex> lock(v->configLock);
    v->lfo[static_cast<size_t>(slot)] = cfg;
    v->renderer.setLfoConfigs(v->lfo);
}

void geode_viz_set_adsr(geode_viz* v, int slot, const float* c, int count) {
    if (!v || !c || slot < 0 || slot >= GEODE_ADSR_SLOTS || count < GEODE_ADSR_CONFIG_FLOATS) return;
    AdsrConfig cfg;
    cfg.enabled = flag(c[0]);
    cfg.attack = c[1];
    cfg.decay = c[2];
    cfg.sustain = c[3];
    cfg.release = c[4];
    cfg.amount = c[5];
    cfg.band = enumAt<EnvBand>(c[6], kEnvBandCount);
    cfg.gateThreshold = c[7];
    cfg.sustainTrack = flag(c[8]);
    cfg.retrigger = flag(c[9]);
    for (int i = GEODE_ADSR_CONFIG_FLOATS; i < count; ++i) {
        cfg.targets.push_back(enumAt<LfoTarget>(c[i], kLfoTargetCount));
    }
    std::lock_guard<std::mutex> lock(v->configLock);
    v->adsr[static_cast<size_t>(slot)] = std::move(cfg);
    v->renderer.setAdsrConfigs(v->adsr);
}

void geode_viz_set_thermal(geode_viz* v, int platform_status, float headroom) {
    if (!v) return;
    if (platform_status >= 0) v->renderer.thermal().setPlatformStatus(platform_status);
    v->renderer.thermal().setThermalHeadroom(headroom);
}

void geode_viz_set_paced_fps(geode_viz* v, float fps) {
    if (v) v->renderer.thermal().setPacedFps(fps);
}

void geode_viz_set_quality_ceiling(geode_viz* v, float ceiling) {
    if (v) v->renderer.thermal().setQualityCeiling(ceiling);
}

void geode_viz_set_frame_load(geode_viz* v, int frames, float p95_ms) {
    if (v) v->renderer.setFrameLoad(frames, p95_ms);
}

void geode_viz_set_offscreen(geode_viz* v, int on) {
    if (!v) return;
    if (on) {
        v->renderer.thermal().beginOffscreenRender();
    } else {
        v->renderer.thermal().endOffscreenRender();
    }
}

int geode_viz_knows(geode_viz* v, const char* scene_id) {
    return (v && scene_id && v->renderer.knows(scene_id)) ? 1 : 0;
}

size_t geode_viz_scene_ids(geode_viz* v, char* out, size_t capacity) {
    if (!v) return 0;
    std::string joined;
    for (const auto& id : v->renderer.availableSceneIds()) {
        if (!joined.empty()) joined += '\n';
        joined += id;
    }
    if (out && capacity > 0) {
        const size_t n = std::min(capacity - 1, joined.size());
        std::memcpy(out, joined.data(), n);
        out[n] = '\0';
    }
    return joined.size();
}

void geode_viz_set_overlay_rgba(geode_viz* v, const uint32_t* pixels, int width, int height) {
    if (v) v->renderer.setOverlayRgba(pixels, width, height);
}

void geode_viz_set_underlay_rgba(geode_viz* v, const uint32_t* pixels, int width, int height, int blend, float amount) {
    if (v) v->renderer.setUnderlayRgba(pixels, width, height, blend, amount);
}

// Any thread (above the GL-thread divider). Renderer::lastError() copies the
// string out under its own lock, so this never reads a Renderer-owned buffer
// that Renderer::fail() (GL thread) could be mutating concurrently. The
// cache is thread_local (not per-`v`) so two threads calling this
// concurrently, even on the same `v`, never race each other's returned
// pointer: the returned pointer stays valid until the *calling thread's*
// next call to geode_viz_last_error (on any instance).
const char* geode_viz_last_error(geode_viz* v) {
    thread_local std::string cache;
    if (!v) return "";
    cache = v->renderer.lastError();
    return cache.c_str();
}

void geode_viz_surface_created(geode_viz* v) {
    if (v) v->renderer.onSurfaceCreated();
}

void geode_viz_surface_changed(geode_viz* v, int width, int height) {
    if (v) v->renderer.surfaceChanged(std::max(width, 1), std::max(height, 1));
}

int geode_viz_set_scene(geode_viz* v, const char* scene_id) {
    return (v && scene_id && v->renderer.setScene(scene_id)) ? 1 : 0;
}

void geode_viz_warm_transition(geode_viz* v, const char* id) {
    if (v && id) v->renderer.warmTransition(id);
}

void geode_viz_cut(geode_viz* v) {
    if (v) v->renderer.cut();
}

// The drawing thread's side of the hand-off: the newest Customization, if a new one came, before the frame.
static void applySnapshot(geode_viz* v) {
    geode_snapshots* attached = v->snapshots.load(std::memory_order_acquire);
    if (!attached || !attached->exchange.refresh(geode::bridge::SnapshotReader::Draw)) return;
    const geode::bridge::CustomizationSnapshot& snapshot = attached->exchange.current(geode::bridge::SnapshotReader::Draw);
    geode_viz_set_params(v, snapshot.params.data(), static_cast<int>(snapshot.params.size()));
    geode_viz_set_reduced_motion(v, snapshot.reducedMotion ? 1 : 0);
    for (int slot = 0; slot < GEODE_LFO_SLOTS; ++slot) {
        geode_viz_set_lfo(v, slot, snapshot.lfo.data() + slot * GEODE_LFO_CONFIG_FLOATS, GEODE_LFO_CONFIG_FLOATS);
    }
    for (int slot = 0; slot < GEODE_ADSR_SLOTS; ++slot) {
        geode_viz_set_adsr(v, slot, snapshot.adsr.data() + slot * GEODE_ADSR_CONFIG_FLOATS, GEODE_ADSR_CONFIG_FLOATS);
    }
    v->hasPlan = geode::viz::mod::decodePlan(snapshot.plan.data(), snapshot.plan.size(), v->plan);
    if (!v->hasPlan) {
        v->graph.reset();
        v->renderer.setCustomization(nullptr);
    }
}

// The Customization's Reactions, Modulators, Macro links and Automations for this frame, handed to
// the renderer before it draws: it maps them into the active Style's ranges and draws all of them.
static void evaluateCustomization(geode_viz* v, double timeSeconds) {
    const auto dt = v->lastFrameSeconds < 0.0 ? 0.0f : static_cast<float>(timeSeconds - v->lastFrameSeconds);
    v->lastFrameSeconds = timeSeconds;
    if (!v->hasPlan) return;
    GeodeFeatureFrame features;
    {
        std::lock_guard<std::mutex> lock(v->configLock);
        features = v->features;
    }
    const geode::viz::mod::Evaluated& evaluated = v->graph.evaluate(v->plan, geode::viz::mod::signalFrame(features), dt);
    v->renderer.setCustomization(&evaluated);
}

// The sound heard now, read straight from the feed on the drawing thread: the features, with each event on
// exactly one frame, and the stereo window for the scenes. Before the Customization is evaluated, so its
// Reactions see the same frame the scenes draw.
static void readFeed(geode_viz* v) {
    if (!v->feed) return;
    GeodeFeatureFrame heard{};
    const bool live = v->feed->read(heard, v->feedPcm.data(), GEODE_FEED_WINDOW_FRAMES);
    const GeodeFeatureFrame shown = v->feedEvents.frame(heard, live);
    v->renderer.setFeatures(shown);
    v->renderer.setFramePcm(v->feedPcm.data(), GEODE_FEED_WINDOW_FRAMES);
    std::lock_guard<std::mutex> lock(v->configLock);
    v->features = shown;
}

GeodeStatus geode_viz_attach_feed(geode_viz* v, geode_feed* f) {
    if (!v) return GEODE_STATUS_NULL_HANDLE;
    v->feed = f ? f->feed : nullptr;
    v->feedEvents.reset();
    return GEODE_STATUS_OK;
}

void geode_viz_render(geode_viz* v, double time_seconds, uint32_t target_fbo) {
    if (!v) return;
    applySnapshot(v);
    readFeed(v);
    evaluateCustomization(v, time_seconds);
    v->renderer.render(time_seconds, target_fbo);
}

GeodeStatus geode_viz_attach_snapshots(geode_viz* v, geode_snapshots* snapshots) {
    if (!v) return GEODE_STATUS_NULL_HANDLE;
    v->snapshots.store(snapshots, std::memory_order_release);
    return GEODE_STATUS_OK;
}

void geode_viz_release_scenes(geode_viz* v) {
    if (v) v->renderer.releaseScenes();
}

}
