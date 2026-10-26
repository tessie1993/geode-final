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
