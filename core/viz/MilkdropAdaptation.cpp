#include "geode/viz/MilkdropAdaptation.hpp"

#include <algorithm>
#include <cmath>

namespace geode::viz::milkdrop {

namespace {

int clampFps(float fps) {
    return std::clamp(static_cast<int>(std::lround(fps)), kMinEngineFps, kMaxEngineFps);
}

}  // namespace

int engineFps(float pacedFps, float measuredFps) {
    if (pacedFps > 0.0f) return clampFps(pacedFps);
    if (measuredFps > 0.0f) return clampFps(measuredFps);
    return kDefaultEngineFps;
}

int renderFps(float frameSeconds) {
    return frameSeconds > 0.0f ? clampFps(1.0f / frameSeconds) : kDefaultEngineFps;
}

float beatSensitivity(float beatResponse) {
    return std::isfinite(beatResponse) ? std::clamp(beatResponse, 0.0f, kMaxBeatSensitivity) : 1.0f;
}

namespace {

double framePower(const float* stereo, int frame) {
    const double left = stereo[2 * frame];
    const double right = stereo[2 * frame + 1];
    return left * left + right * right;
}

}  // namespace

int loudestWindow(const float* stereo, int frames, int window, int fresh) {
    if (stereo == nullptr || window <= 0 || frames <= window) return 0;
    const int newest = frames - window;
    const int oldest = newest - std::min(std::max(fresh, 0), newest);
    double energy = 0.0;
    for (int i = oldest; i < oldest + window; ++i) energy += framePower(stereo, i);
    int best = oldest;
    double bestEnergy = energy;
    for (int start = oldest + 1; start <= newest; ++start) {
        energy += framePower(stereo, start + window - 1) - framePower(stereo, start - 1);
        if (energy >= bestEnergy) {
            bestEnergy = energy;
            best = start;
        }
    }
    return best;
}

int heardSince(bool wasLive, uint32_t wasEpoch, uint64_t wasEnd, bool live, uint32_t epoch, uint64_t end, int most) {
    if (!wasLive || !live || epoch != wasEpoch || end <= wasEnd || most <= 0) return 0;
    return static_cast<int>(std::min<uint64_t>(end - wasEnd, static_cast<uint64_t>(most)));
}

BufferSize bufferSize(int windowWidth, int windowHeight, float scale) {
    const auto side = [scale](int window) { return std::max(static_cast<int>(std::lround(static_cast<float>(window) * scale)), 1); };
    return {side(windowWidth), side(windowHeight)};
}

bool resizeNow(BufferSize current, BufferSize wanted) {
    const float shape = static_cast<float>(current.width) / static_cast<float>(current.height);
    const float wantedShape = static_cast<float>(wanted.width) / static_cast<float>(wanted.height);
    // An engine with no size yet has no shape (0/0 is NaN, a zero side an infinity or 0): resize it.
    return !(std::fabs(shape / wantedShape - 1.0f) <= kAspectTolerance);
}

float pictureZoom(float leanIn) { return 1.0f + std::max(leanIn, 0.0f); }

float crossfadedZoom(float incoming, float outgoing, float progress, bool transitioning) {
    if (!transitioning) return incoming;
    const float t = std::clamp(progress, 0.0f, 1.0f);
    return outgoing + (incoming - outgoing) * t;
}

Pointer pointerFromTouch(int fingersDown, float ndcX, float ndcY) {
    if (fingersDown <= 0) return {-1.0f, -1.0f, false};
    return {std::clamp(0.5f * (ndcX + 1.0f), 0.0f, 1.0f), std::clamp(0.5f * (1.0f - ndcY), 0.0f, 1.0f), true};
}

void LoadAdapter::onFrame(float frameSeconds, float pacedFps) {
    if (frameSeconds <= 0.0f || frameSeconds > kMaxFrameSeconds) return;
    averageSeconds_ = averageSeconds_ > 0.0f ? averageSeconds_ + kSmoothing * (frameSeconds - averageSeconds_) : frameSeconds;
    const float target = pacedFps > 0.0f ? pacedFps : kFreeRunningTargetFps;
    const float fps = averageFps();
    if (fps < target * kBehindFraction) {
        headroomSeconds_ = 0.0f;
        behindSeconds_ += frameSeconds;
        if (behindSeconds_ >= kStepDownSeconds) stepDown();
    } else if (fps >= target * kHeadroomFraction) {
        behindSeconds_ = 0.0f;
        headroomSeconds_ += frameSeconds;
        if (headroomSeconds_ >= kStepUpSeconds) stepUp();
    } else {
        behindSeconds_ = 0.0f;
        headroomSeconds_ = 0.0f;
    }
}

// The mesh goes first: it is cheap to change and safe at any time. Only once it is at its coarsest
// does the buffer take a step, and that step waits for the next preset change.
void LoadAdapter::stepDown() {
    behindSeconds_ = 0.0f;
    if (meshStep_ < kLastMeshStep) {
        ++meshStep_;
    } else if (pendingBufferStep_ < kLastBufferStep) {
        ++pendingBufferStep_;
    }
}

// Back up in the reverse order: the buffer that was given up last returns first.
void LoadAdapter::stepUp() {
    headroomSeconds_ = 0.0f;
    if (pendingBufferStep_ > 0) {
        --pendingBufferStep_;
    } else if (meshStep_ > 0) {
        --meshStep_;
    }
}

bool LoadAdapter::onPresetChange() {
    if (bufferStep_ == pendingBufferStep_) return false;
    bufferStep_ = pendingBufferStep_;
    return true;
}

void LoadAdapter::reset() {
    *this = LoadAdapter{};
}

}  // namespace geode::viz::milkdrop
