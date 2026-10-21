// Two Renders of the same input give byte-identical frames (ADR-0005, "Renders are frame-exact").
//
// Builds Geode's copy of projectM (the v4.1.7 submodule plus tools/projectm-*.patch) on the host and
// renders the same song twice, the way MilkdropScene does during a Render: a fresh engine seeded
// with projectm_set_random_seed() and driven by projectm_set_frame_time() from the render clock.
// The two runs are interleaved frame by frame with a third, live-style engine (wall clock, no
// seed) on the same thread and GL context, which is how a Render shares the process with the live
// Visual: nothing either Render draws may depend on the other engines' random state or on the time.
//
// The fixtures draw randomness from everywhere projectM has it: rand() in per-frame and per-pixel
// code, rand_frame and rand_preset in the warp and composite shaders, the noise textures, and the
// random transition between two presets, which runs on projectM's clock.
//
// Checks, each a failure on its own:
//   - every frame of run A equals the same frame of run B, byte for byte;
//   - a run with another seed differs from A (the seed reaches the picture, so the check above is
//     not passing on a picture without randomness in it);
//   - the picture is not black and moves (the presets really render);
//   - neither preset failed to load.
//
//   milkdrop_determinism <first.milk> <second.milk> <texture dir>
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl3.h>
#include <projectM-4/projectM.h>

#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

namespace {

constexpr int kWidth = 160;
constexpr int kHeight = 96;
constexpr int kBytesPerPixel = 4;
constexpr size_t kFrameBytes = static_cast<size_t>(kWidth) * kHeight * kBytesPerPixel;
constexpr int kFps = 30;
constexpr int kFrames = 150;
// The second preset arrives here with a soft cut, so the transition runs between this frame and
// kSwitchFrame + kSoftCutSeconds * kFps.
constexpr int kSwitchFrame = 60;
constexpr double kSoftCutSeconds = 1.5;
constexpr int kSampleRate = 44100;
constexpr int kSamplesPerFrame = kSampleRate / kFps;
constexpr uint32_t kRenderSeed = 0x6e0de;
constexpr uint32_t kOtherSeed = kRenderSeed + 1;
constexpr int kMeshColumns = 48;
constexpr int kMeshRows = 32;
constexpr double kPresetDurationSeconds = 999999.0;
constexpr float kTwoPi = 6.28318530718f;
// A channel above this counts as lit.
constexpr unsigned char kLitThreshold = 8;
// The first and last frames compared to prove the picture moves.
constexpr int kEarlyFrame = 20;
constexpr int kLateFrame = kSwitchFrame - 1;
constexpr EGLenum kPlatformSurfacelessMesa = 0x31DD;

std::string g_loadError;

void onPresetSwitchFailed(const char* file, const char* message, void*) {
    g_loadError = std::string(file ? file : "?") + ": " + (message ? message : "?");
}

// The same song for every run: a bass line with a kick, a mid tone and a treble shimmer.
void song(int frame, std::vector<float>& out) {
    out.resize(kSamplesPerFrame);
    for (int i = 0; i < kSamplesPerFrame; ++i) {
        const float t = static_cast<float>(frame * kSamplesPerFrame + i) / kSampleRate;
        float s = 0.35f * std::sin(kTwoPi * 55.0f * t) + 0.2f * std::sin(kTwoPi * 440.0f * t) + 0.1f * std::sin(kTwoPi * 3520.0f * t);
        if (frame % 15 < 2) s += 0.5f * std::sin(kTwoPi * 60.0f * t);
        out[static_cast<size_t>(i)] = s;
    }
}

// What the live Visual hears meanwhile: something else entirely.
void otherSong(int frame, std::vector<float>& out) {
    out.resize(kSamplesPerFrame);
    for (int i = 0; i < kSamplesPerFrame; ++i) {
        const float t = static_cast<float>(frame * kSamplesPerFrame + i) / kSampleRate;
        out[static_cast<size_t>(i)] = 0.6f * std::sin(kTwoPi * 97.0f * t) * std::sin(kTwoPi * 0.5f * t);
    }
}

// projectM assumes the default GL state; every engine starts its frame from it, whoever drew last.
void resetGlState() {
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    glUseProgram(0);
    glBindVertexArray(0);
    glDisable(GL_BLEND);
    glDisable(GL_DEPTH_TEST);
    glDisable(GL_SCISSOR_TEST);
    glDisable(GL_CULL_FACE);
    glDisable(GL_STENCIL_TEST);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, 0);
    glViewport(0, 0, kWidth, kHeight);
}

struct Engine {
    projectm_handle pm = nullptr;
    GLuint fbo = 0;
    GLuint tex = 0;
    bool renderClock = false;
    std::vector<std::vector<unsigned char>> frames;
};

bool makeTarget(Engine& e) {
    glGenFramebuffers(1, &e.fbo);
    glBindFramebuffer(GL_FRAMEBUFFER, e.fbo);
    glGenTextures(1, &e.tex);
    glBindTexture(GL_TEXTURE_2D, e.tex);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, kWidth, kHeight, 0, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, e.tex, 0);
    const bool ok = glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE;
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    return ok;
}

// The order MilkdropScene uses for a Render: seed and clock first, then the texture paths (which
// rebuild the noise textures from the seed), then the first preset with a hard cut.
bool start(Engine& e, bool renderClock, uint32_t seed, const std::string& preset, const std::string& textureDir) {
    e.renderClock = renderClock;
    e.pm = projectm_create();
    if (!e.pm || !makeTarget(e)) return false;
    if (renderClock) {
        projectm_set_random_seed(e.pm, seed);
        projectm_set_frame_time(e.pm, 0.0);
    }
    projectm_set_preset_switch_failed_event_callback(e.pm, onPresetSwitchFailed, nullptr);
    projectm_set_fps(e.pm, kFps);
    projectm_set_mesh_size(e.pm, kMeshColumns, kMeshRows);
    projectm_set_soft_cut_duration(e.pm, kSoftCutSeconds);
    projectm_set_preset_duration(e.pm, kPresetDurationSeconds);
    projectm_set_preset_locked(e.pm, true);
    projectm_set_window_size(e.pm, kWidth, kHeight);
    const char* paths[] = {textureDir.c_str()};
    projectm_set_texture_search_paths(e.pm, paths, 1);
    projectm_load_preset_file(e.pm, preset.c_str(), false);
    projectm_set_preset_locked(e.pm, true);
    return true;
}

void renderFrame(Engine& e, int frame, const std::vector<float>& pcm, bool keep) {
    if (e.renderClock) projectm_set_frame_time(e.pm, static_cast<double>(frame) / kFps);
    projectm_pcm_add_float(e.pm, pcm.data(), static_cast<unsigned int>(pcm.size()), PROJECTM_MONO);
    resetGlState();
    projectm_opengl_render_frame_fbo(e.pm, e.fbo);
    if (!keep) return;
    std::vector<unsigned char> px(kFrameBytes);
    glBindFramebuffer(GL_READ_FRAMEBUFFER, e.fbo);
    glReadPixels(0, 0, kWidth, kHeight, GL_RGBA, GL_UNSIGNED_BYTE, px.data());
    glBindFramebuffer(GL_READ_FRAMEBUFFER, 0);
    e.frames.push_back(std::move(px));
}

void stop(Engine& e) {
    if (e.pm) projectm_destroy(e.pm);
    if (e.tex) glDeleteTextures(1, &e.tex);
    if (e.fbo) glDeleteFramebuffers(1, &e.fbo);
    e = Engine{};
}

bool initEgl() {
    EGLDisplay dpy = EGL_NO_DISPLAY;
    auto getPlatformDisplay = reinterpret_cast<PFNEGLGETPLATFORMDISPLAYEXTPROC>(eglGetProcAddress("eglGetPlatformDisplayEXT"));
    if (getPlatformDisplay) dpy = getPlatformDisplay(kPlatformSurfacelessMesa, EGL_DEFAULT_DISPLAY, nullptr);
    if (dpy == EGL_NO_DISPLAY) dpy = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (dpy == EGL_NO_DISPLAY || !eglInitialize(dpy, nullptr, nullptr)) {
        std::fprintf(stderr, "FAIL: no EGL display\n");
        return false;
    }
    eglBindAPI(EGL_OPENGL_ES_API);
    const EGLint configAttribs[] = {EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT, EGL_SURFACE_TYPE, 0, EGL_NONE};
    EGLConfig config;
    EGLint count = 0;
    if (!eglChooseConfig(dpy, configAttribs, &config, 1, &count) || count < 1) {
        std::fprintf(stderr, "FAIL: no GLES3 EGL config\n");
        return false;
    }
    const EGLint contextAttribs[] = {EGL_CONTEXT_MAJOR_VERSION, 3, EGL_NONE};
    EGLContext ctx = eglCreateContext(dpy, config, EGL_NO_CONTEXT, contextAttribs);
    if (ctx == EGL_NO_CONTEXT || !eglMakeCurrent(dpy, EGL_NO_SURFACE, EGL_NO_SURFACE, ctx)) {
        std::fprintf(stderr, "FAIL: no surfaceless GLES3 context\n");
        return false;
    }
    std::printf("GL_RENDERER: %s\nGL_VERSION: %s\n", glGetString(GL_RENDERER), glGetString(GL_VERSION));
    return true;
}

size_t differingBytes(const std::vector<unsigned char>& a, const std::vector<unsigned char>& b) {
    size_t n = 0;
    for (size_t i = 0; i < a.size(); ++i) n += a[i] != b[i] ? 1 : 0;
    return n;
}

size_t litPixels(const std::vector<unsigned char>& px) {
    size_t n = 0;
    for (size_t i = 0; i < px.size(); i += kBytesPerPixel) {
        if (px[i] > kLitThreshold || px[i + 1] > kLitThreshold || px[i + 2] > kLitThreshold) ++n;
    }
    return n;
}

}  // namespace

int main(int argc, char** argv) {
    if (argc != 4) {
        std::fprintf(stderr, "usage: %s <first.milk> <second.milk> <texture dir>\n", argv[0]);
        return 2;
    }
    const std::string first = argv[1];
    const std::string second = argv[2];
    const std::string textureDir = argv[3];
    if (!initEgl()) return 1;

    Engine a, live, b, other;
    if (!start(a, true, kRenderSeed, first, textureDir) || !start(live, false, 0, second, textureDir) ||
        !start(b, true, kRenderSeed, first, textureDir) || !start(other, true, kOtherSeed, first, textureDir)) {
        std::fprintf(stderr, "FAIL: could not create the engines\n");
        return 1;
    }
    if (!g_loadError.empty()) {
        std::fprintf(stderr, "FAIL: preset did not load: %s\n", g_loadError.c_str());
        return 1;
    }

    std::vector<float> pcm;
    std::vector<float> livePcm;
    for (int f = 0; f < kFrames; ++f) {
        if (f == kSwitchFrame) {
            for (Engine* e : {&a, &b, &other}) {
                projectm_load_preset_file(e->pm, second.c_str(), true);
                projectm_set_preset_locked(e->pm, true);
            }
            projectm_load_preset_file(live.pm, first.c_str(), true);
            if (!g_loadError.empty()) {
                std::fprintf(stderr, "FAIL: preset did not load: %s\n", g_loadError.c_str());
                return 1;
            }
        }
        song(f, pcm);
        otherSong(f, livePcm);
        renderFrame(a, f, pcm, true);
        renderFrame(live, f, livePcm, false);
        renderFrame(b, f, pcm, true);
        renderFrame(other, f, pcm, true);
    }
    if (!g_loadError.empty()) {
        std::fprintf(stderr, "FAIL: preset failed while rendering: %s\n", g_loadError.c_str());
        return 1;
    }

    int failures = 0;
    int firstMismatch = -1;
    size_t mismatchedFrames = 0;
    for (int f = 0; f < kFrames; ++f) {
        const size_t diff = differingBytes(a.frames[static_cast<size_t>(f)], b.frames[static_cast<size_t>(f)]);
        if (diff == 0) continue;
        ++mismatchedFrames;
        if (firstMismatch < 0) {
            firstMismatch = f;
            std::fprintf(stderr, "frame %d: %zu of %zu bytes differ\n", f, diff, kFrameBytes);
        }
    }
    if (mismatchedFrames > 0) {
        std::fprintf(stderr, "FAIL: the two Renders differ in %zu of %d frames (first: %d)\n", mismatchedFrames, kFrames, firstMismatch);
        ++failures;
    } else {
        std::printf("ok: the two Renders are byte-identical over %d frames\n", kFrames);
    }

    size_t seedDifference = 0;
    for (int f = 0; f < kFrames; ++f) seedDifference += differingBytes(a.frames[static_cast<size_t>(f)], other.frames[static_cast<size_t>(f)]);
    if (seedDifference == 0) {
        std::fprintf(stderr, "FAIL: another seed renders the same frames; the fixtures draw no randomness\n");
        ++failures;
    } else {
        std::printf("ok: another seed differs in %zu bytes\n", seedDifference);
    }

    const auto& last = a.frames.back();
    const size_t lit = litPixels(last);
    if (lit == 0) {
        std::fprintf(stderr, "FAIL: the last frame is black\n");
        ++failures;
    } else {
        std::printf("ok: %zu of %d pixels lit in the last frame\n", lit, kWidth * kHeight);
    }
    if (differingBytes(a.frames[kEarlyFrame], a.frames[kLateFrame]) == 0) {
        std::fprintf(stderr, "FAIL: the picture does not move between frames %d and %d\n", kEarlyFrame, kLateFrame);
        ++failures;
    } else {
        std::printf("ok: the picture moves\n");
    }

    for (Engine* e : {&a, &live, &b, &other}) stop(*e);
    return failures == 0 ? 0 : 1;
}
