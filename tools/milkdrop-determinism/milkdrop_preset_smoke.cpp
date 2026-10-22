// Every bundled MilkDrop preset really runs on Geode's copy of projectM (docs/ci.md, "MilkDrop
// presets run as written").
//
// projectM never reports a preset shader that fails: when a warp or composite shader cannot be
// translated from HLSL or compiled, it quietly swaps in its default shader (or none) and the preset
// keeps playing, just not as written. Only a build with MILKDROP_PRESET_DEBUG says so, on stderr.
// This check's CMakeLists builds projectM that way, sends stderr to a file while each preset loads
// and renders, and fails the preset if projectM wrote a shader error.
//
// Each preset is loaded into a fresh engine, seeded and on the render clock like a Render, and
// rendered for a few seconds of the same song. Checks, each a failure on its own:
//   - the preset loads (its equations compile; projectM reports those through the load callback);
//   - neither its warp nor its composite shader fell back to the default;
//   - the picture is lit and moves.
//
//   milkdrop_preset_smoke <texture dir> <preset.milk or folder of presets>...
#include <projectM-4/projectM.h>
#include <sys/stat.h>
#include <unistd.h>

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <filesystem>
#include <string>
#include <vector>

#include "host_gl.hpp"

namespace {

constexpr int kWidth = 160;
constexpr int kHeight = 96;
constexpr int kBytesPerPixel = 4;
constexpr size_t kFrameBytes = static_cast<size_t>(kWidth) * kHeight * kBytesPerPixel;
constexpr int kFps = 30;
// Three seconds of music per preset.
constexpr int kFrames = 90;
// The frame the motion check compares the last frame with, after the first second has settled.
constexpr int kEarlyFrame = kFps;
constexpr int kSampleRate = 44100;
constexpr int kSamplesPerFrame = kSampleRate / kFps;
constexpr uint32_t kRenderSeed = 0x6e0de;
constexpr int kMeshColumns = 48;
constexpr int kMeshRows = 32;
constexpr double kPresetDurationSeconds = 999999.0;
constexpr float kTwoPi = 6.28318530718f;
// A channel above this counts as lit.
constexpr unsigned char kLitThreshold = 8;
// What projectM writes when it drops a preset's shader (MilkdropPreset/PerPixelMesh.cpp and
// FinalComposite.cpp in v4.1.7).
constexpr const char* kWarpShaderError = "[Warp Shader] Error";
constexpr const char* kCompositeShaderError = "[Composite Shader] Error";
// How much of projectM's message to show; the rest is the whole shader source.
constexpr size_t kMessagePreview = 300;
constexpr const char* kPresetExtension = ".milk";

std::string g_loadError;

void onPresetSwitchFailed(const char* file, const char* message, void*) {
    g_loadError = std::string(file ? file : "?") + ": " + (message ? message : "?");
}

// A bass line with a kick, a mid tone and a treble shimmer, as in the frame-exactness check.
void song(int frame, std::vector<float>& out) {
    out.resize(kSamplesPerFrame);
    for (int i = 0; i < kSamplesPerFrame; ++i) {
        const float t = static_cast<float>(frame * kSamplesPerFrame + i) / kSampleRate;
        float s = 0.35f * std::sin(kTwoPi * 55.0f * t) + 0.2f * std::sin(kTwoPi * 440.0f * t) + 0.1f * std::sin(kTwoPi * 3520.0f * t);
        if (frame % 15 < 2) s += 0.5f * std::sin(kTwoPi * 60.0f * t);
        out[static_cast<size_t>(i)] = s;
    }
}

// Sends stderr to a temporary file and hands back what was written since the last call.
class StderrCapture {
public:
    bool start() {
        file_ = std::tmpfile();
        if (!file_) return false;
        std::fflush(stderr);
        saved_ = dup(STDERR_FILENO);
        return saved_ >= 0 && dup2(fileno(file_), STDERR_FILENO) >= 0;
    }

    std::string drain() {
        std::fflush(stderr);
        struct stat st {};
        if (fstat(fileno(file_), &st) != 0 || st.st_size <= consumed_) return {};
        std::string text(static_cast<size_t>(st.st_size - consumed_), '\0');
        const ssize_t got = pread(fileno(file_), text.data(), text.size(), consumed_);
        if (got <= 0) return {};
        text.resize(static_cast<size_t>(got));
        consumed_ += got;
        return text;
    }

    void stop() {
        std::fflush(stderr);
        if (saved_ >= 0) dup2(saved_, STDERR_FILENO);
        if (saved_ >= 0) close(saved_);
        if (file_) std::fclose(file_);
        saved_ = -1;
        file_ = nullptr;
    }

private:
    FILE* file_ = nullptr;
    int saved_ = -1;
    off_t consumed_ = 0;
};

// The first line of projectM's message after `marker`, shortened.
std::string firstMessage(const std::string& log, const char* marker) {
    const size_t at = log.find(marker);
    if (at == std::string::npos) return {};
    const size_t end = log.find('\n', at);
    std::string line = log.substr(at, end == std::string::npos ? std::string::npos : end - at);
    if (line.size() > kMessagePreview) line = line.substr(0, kMessagePreview) + "...";
    return line;
}

size_t litPixels(const std::vector<unsigned char>& px) {
    size_t n = 0;
    for (size_t i = 0; i < px.size(); i += kBytesPerPixel) {
        if (px[i] > kLitThreshold || px[i + 1] > kLitThreshold || px[i + 2] > kLitThreshold) ++n;
    }
    return n;
}

// Renders one preset and returns how many of the checks it failed.
int checkPreset(const std::filesystem::path& preset, const std::string& textureDir, StderrCapture& capture) {
    const std::string name = preset.filename().string();
    g_loadError.clear();
    capture.drain();

    GLuint fbo = 0;
    GLuint tex = 0;
    projectm_handle pm = projectm_create();
    if (!pm || !geode_host::makeTarget(kWidth, kHeight, fbo, tex)) {
        std::printf("FAIL: %s: could not create the engine\n", name.c_str());
        if (pm) projectm_destroy(pm);
        return 1;
    }
    projectm_set_random_seed(pm, kRenderSeed);
    projectm_set_frame_time(pm, 0.0);
    projectm_set_preset_switch_failed_event_callback(pm, onPresetSwitchFailed, nullptr);
    projectm_set_fps(pm, kFps);
    projectm_set_mesh_size(pm, kMeshColumns, kMeshRows);
    projectm_set_preset_duration(pm, kPresetDurationSeconds);
    projectm_set_preset_locked(pm, true);
    projectm_set_window_size(pm, kWidth, kHeight);
    const char* paths[] = {textureDir.c_str()};
    projectm_set_texture_search_paths(pm, paths, 1);
    projectm_load_preset_file(pm, preset.string().c_str(), false);
    projectm_set_preset_locked(pm, true);

    std::vector<float> pcm;
    std::vector<unsigned char> early(kFrameBytes);
    std::vector<unsigned char> frame(kFrameBytes);
    size_t mostLit = 0;
    for (int f = 0; f < kFrames && g_loadError.empty(); ++f) {
        projectm_set_frame_time(pm, static_cast<double>(f) / kFps);
        song(f, pcm);
        projectm_pcm_add_float(pm, pcm.data(), static_cast<unsigned int>(pcm.size()), PROJECTM_MONO);
        geode_host::resetGlState(kWidth, kHeight);
        projectm_opengl_render_frame_fbo(pm, fbo);
        glBindFramebuffer(GL_READ_FRAMEBUFFER, fbo);
        glReadPixels(0, 0, kWidth, kHeight, GL_RGBA, GL_UNSIGNED_BYTE, frame.data());
        glBindFramebuffer(GL_READ_FRAMEBUFFER, 0);
        mostLit = std::max(mostLit, litPixels(frame));
        if (f == kEarlyFrame) early = frame;
    }
    projectm_destroy(pm);
    glDeleteTextures(1, &tex);
    glDeleteFramebuffers(1, &fbo);
    const std::string log = capture.drain();

    int failures = 0;
    if (!g_loadError.empty()) {
        std::printf("FAIL: %s: did not load: %s\n", name.c_str(), g_loadError.c_str());
        return 1;
    }
    for (const auto& [marker, which] : {std::pair{kWarpShaderError, "warp"}, std::pair{kCompositeShaderError, "composite"}}) {
        const std::string message = firstMessage(log, marker);
        if (message.empty()) continue;
        std::printf("FAIL: %s: the %s shader failed and projectM fell back to its default\n  %s\n", name.c_str(), which, message.c_str());
        ++failures;
    }
    if (mostLit == 0) {
        std::printf("FAIL: %s: every frame is black\n", name.c_str());
        ++failures;
    }
    if (early == frame) {
        std::printf("FAIL: %s: the picture does not move between frames %d and %d\n", name.c_str(), kEarlyFrame, kFrames - 1);
        ++failures;
    }
    if (failures == 0) std::printf("ok: %s\n", name.c_str());
    std::fflush(stdout);
    return failures;
}

std::vector<std::filesystem::path> presetsIn(const std::vector<std::string>& args) {
    std::vector<std::filesystem::path> out;
    for (const auto& arg : args) {
        const std::filesystem::path path(arg);
        if (!std::filesystem::is_directory(path)) {
            out.push_back(path);
            continue;
        }
        std::vector<std::filesystem::path> found;
        for (const auto& entry : std::filesystem::directory_iterator(path)) {
            if (entry.is_regular_file() && entry.path().extension() == kPresetExtension) found.push_back(entry.path());
        }
        std::sort(found.begin(), found.end());
        out.insert(out.end(), found.begin(), found.end());
    }
    return out;
}

}  // namespace

int main(int argc, char** argv) {
    if (argc < 3) {
        std::fprintf(stderr, "usage: %s <texture dir> <preset.milk or folder>...\n", argv[0]);
        return 2;
    }
    const std::string textureDir = argv[1];
    const auto presets = presetsIn(std::vector<std::string>(argv + 2, argv + argc));
    if (presets.empty()) {
        std::printf("FAIL: no presets found\n");
        return 1;
    }
    if (!geode_host::initEgl()) return 1;

    StderrCapture capture;
    if (!capture.start()) {
        std::printf("FAIL: could not capture stderr\n");
        return 1;
    }
    int failedPresets = 0;
    for (const auto& preset : presets) {
        if (checkPreset(preset, textureDir, capture) > 0) ++failedPresets;
    }
    capture.stop();

    if (failedPresets > 0) {
        std::printf("FAIL: %d of %zu presets do not run as written\n", failedPresets, presets.size());
        return 1;
    }
    std::printf("ok: all %zu presets run as written\n", presets.size());
    return 0;
}
