#include "geode/viz/FlashLimiter.hpp"

#include <algorithm>
#include <cmath>
#include <utility>

namespace geode::viz {

namespace noflash {

namespace {
// The sRGB transfer function (IEC 61966-2-1), as WCAG defines relative luminance on it.
constexpr float kSrgbLinearKnee = 0.04045f;
constexpr float kSrgbLinearSlope = 12.92f;
constexpr float kSrgbOffset = 0.055f;
constexpr float kSrgbGamma = 2.4f;
}  // namespace

float srgbToLinear(float encoded) {
    const float c = std::clamp(encoded, 0.0f, 1.0f);
    if (c <= kSrgbLinearKnee) return c / kSrgbLinearSlope;
    return std::pow((c + kSrgbOffset) / (1.0f + kSrgbOffset), kSrgbGamma);
}

float relativeLuminance(float r, float g, float b) { return kLumaRed * r + kLumaGreen * g + kLumaBlue * b; }

float saturatedRed(float r, float g, float b) {
    const float sum = r + g + b;
    if (sum <= 0.0f || r < kSaturatedRedShare * sum) return 0.0f;
    return std::max(r - g - b, 0.0f);
}

}  // namespace noflash

LumaGrid LumaGrid::uniform(float luminance, float red) {
    LumaGrid grid;
    grid.luminance.fill(luminance);
    grid.red.fill(red);
    return grid;
}

LumaGrid LumaGrid::fromReadback(const std::array<uint8_t, noflash::kReadbackBytes>& rgba) {
    LumaGrid grid;
    for (int cell = 0; cell < noflash::kCellCount; ++cell) {
        const int base = cell * noflash::kReadbackChannels;
        grid.red[static_cast<size_t>(cell)] = static_cast<float>(rgba[static_cast<size_t>(base + noflash::kReadbackRedChannel)]) / noflash::kByteMax;
        grid.luminance[static_cast<size_t>(cell)] =
            static_cast<float>(rgba[static_cast<size_t>(base + noflash::kReadbackLumaChannel)]) / noflash::kByteMax;
    }
    return grid;
}

void TransitionTracker::reset(float value) {
    low_ = value;
    high_ = value;
    last_ = Direction::None;
}

bool TransitionTracker::feed(float value) {
    if (last_ != Direction::Rise && low_ < darkLimit_ && value - low_ >= delta_) {
        last_ = Direction::Rise;
        low_ = value;
        high_ = value;
        return true;
    }
    if (last_ != Direction::Fall && value < darkLimit_ && high_ - value >= delta_) {
        last_ = Direction::Fall;
        low_ = value;
        high_ = value;
        return true;
    }
    low_ = std::min(low_, value);
    high_ = std::max(high_, value);
    return false;
}

float TransitionTracker::ceiling() const {
    if (last_ == Direction::Rise || low_ >= darkLimit_) return std::numeric_limits<float>::infinity();
    return low_ + delta_;
}

float TransitionTracker::floor() const {
    if (last_ == Direction::Fall) return -std::numeric_limits<float>::infinity();
    return std::min(high_ - delta_, darkLimit_);
}

namespace {
template <size_t... I>
std::array<TransitionTracker, sizeof...(I)> makeTrackers(float delta, float darkLimit, std::index_sequence<I...>) {
    return {{((void) I, TransitionTracker(delta, darkLimit))...}};
}
}  // namespace

FlashLimiter::FlashLimiter()
    : general_(makeTrackers(noflash::kGeneralFlashDelta, noflash::kGeneralFlashDarkLimit, std::make_index_sequence<noflash::kAreaCount>{})),
      red_(makeTrackers(noflash::kRedFlashDelta, noflash::kNoDarkLimit, std::make_index_sequence<noflash::kAreaCount>{})) {}

void TransitionWindow::reset() {
    head_ = 0;
    count_ = 0;
}

void TransitionWindow::record(double time) {
    times_[static_cast<size_t>(head_)] = time;
    head_ = (head_ + 1) % kCapacity;
    count_ = std::min(count_ + 1, kCapacity);
}

void TransitionWindow::dropBefore(double cutoff) {
    while (count_ > 0 && times_[static_cast<size_t>((head_ - count_ + kCapacity) % kCapacity)] < cutoff) count_--;
}

void FlashLimiter::reset() {
    primed_ = false;
    now_ = 0.0;
    for (auto& w : generalWindows_) w.reset();
    for (auto& w : redWindows_) w.reset();
}

int FlashLimiter::transitionsInWindow() const {
    int most = 0;
    for (size_t a = 0; a < static_cast<size_t>(noflash::kAreaCount); ++a) {
        most = std::max(most, std::max(generalWindows_[a].count(), redWindows_[a].count()));
    }
    return most;
}

void FlashLimiter::areaMeans(const std::array<float, noflash::kCellCount>& cells, std::array<float, noflash::kAreaCount>& out) {
    float total = 0.0f;
    for (const float v : cells) total += v;
    out[noflash::kWholeFrame] = total / static_cast<float>(noflash::kCellCount);
    constexpr float kRegionArea = static_cast<float>(noflash::kRegionCells * noflash::kRegionCells);
    int area = noflash::kWholeFrame + 1;
    for (int row = 0; row < noflash::kRegionRows; ++row) {
        for (int col = 0; col < noflash::kRegionColumns; ++col) {
            float sum = 0.0f;
            for (int dy = 0; dy < noflash::kRegionCells; ++dy) {
                for (int dx = 0; dx < noflash::kRegionCells; ++dx) {
                    sum += cells[static_cast<size_t>((row + dy) * noflash::kGridColumns + col + dx)];
                }
            }
            out[static_cast<size_t>(area++)] = sum / kRegionArea;
        }
    }
}

float FlashLimiter::amountWithin(const TransitionTracker& tracker, float shown, float candidate) {
    const float change = candidate - shown;
    if (change > 0.0f) {
        const float room = tracker.ceiling() - noflash::kBandMargin - shown;
        return std::isinf(room) ? 1.0f : std::clamp(room / change, 0.0f, 1.0f);
    }
    if (change < 0.0f) {
        const float room = tracker.floor() + noflash::kBandMargin - shown;
        return std::isinf(room) ? 1.0f : std::clamp(room / change, 0.0f, 1.0f);
    }
    return 1.0f;
}

float FlashLimiter::safeAmount(const LumaGrid& candidate) const {
    std::array<float, noflash::kAreaCount> shownLuma{};
    std::array<float, noflash::kAreaCount> shownRed{};
    std::array<float, noflash::kAreaCount> nextLuma{};
    std::array<float, noflash::kAreaCount> nextRed{};
    areaMeans(shown_.luminance, shownLuma);
    areaMeans(shown_.red, shownRed);
    areaMeans(candidate.luminance, nextLuma);
    areaMeans(candidate.red, nextRed);
    float amount = 1.0f;
    for (size_t a = 0; a < static_cast<size_t>(noflash::kAreaCount); ++a) {
        if (generalWindows_[a].spent()) amount = std::min(amount, amountWithin(general_[a], shownLuma[a], nextLuma[a]));
        if (redWindows_[a].spent()) amount = std::min(amount, amountWithin(red_[a], shownRed[a], nextRed[a]));
    }
    return amount;
}

void FlashLimiter::feedShown() {
    std::array<float, noflash::kAreaCount> luma{};
    std::array<float, noflash::kAreaCount> red{};
    areaMeans(shown_.luminance, luma);
    areaMeans(shown_.red, red);
    for (size_t a = 0; a < static_cast<size_t>(noflash::kAreaCount); ++a) {
        if (general_[a].feed(luma[a])) generalWindows_[a].record(now_);
        if (red_[a].feed(red[a])) redWindows_[a].record(now_);
    }
}

float FlashLimiter::step(float dt, const LumaGrid& candidate) {
    if (!primed_) {
        primed_ = true;
        shown_ = candidate;
        std::array<float, noflash::kAreaCount> luma{};
        std::array<float, noflash::kAreaCount> red{};
        areaMeans(candidate.luminance, luma);
        areaMeans(candidate.red, red);
        for (size_t a = 0; a < static_cast<size_t>(noflash::kAreaCount); ++a) {
            general_[a].reset(luma[a]);
            red_[a].reset(red[a]);
        }
        return 1.0f;
    }
    now_ += static_cast<double>(std::max(dt, 0.0f));
    // A transition counts while it is no more than the window's length old.
    const double cutoff = now_ - noflash::kWindowSeconds;
    for (auto& w : generalWindows_) w.dropBefore(cutoff);
    for (auto& w : redWindows_) w.dropBefore(cutoff);
    const float amount = safeAmount(candidate);
    for (size_t c = 0; c < static_cast<size_t>(noflash::kCellCount); ++c) {
        shown_.luminance[c] += (candidate.luminance[c] - shown_.luminance[c]) * amount;
        shown_.red[c] += (candidate.red[c] - shown_.red[c]) * amount;
    }
    feedShown();
    return amount;
}

}  // namespace geode::viz
