// The No-flash rule against the worst a Customization can do: Modulators in a loop and a strobing
// Sound signal, all driving every luminous Shared core property at full amount. What reaches the
// screen goes through the same three stages as on the device: the ReactionGraph's safety clamp,
// the per-parameter caps (safety::apply) and the No-flash limiter on the finished frame.
#include <gtest/gtest.h>

#include <algorithm>
#include <array>
#include <cmath>
#include <vector>

#include "geode/viz/FlashLimiter.hpp"
#include "geode/viz/ReactionGraph.hpp"
#include "geode/viz/VisualSafety.hpp"

namespace geode::viz {
namespace {

using mod::CustomizationPlan;
using mod::Evaluated;
using mod::ModulatorField;
using mod::ModulatorSpec;
using mod::ReactionGraph;
using mod::ReactionSpec;
using mod::Shared;
using mod::SignalFrame;
using mod::SoundSignal;
using mod::Source;
using mod::Target;

constexpr double kSeconds = 10.0;
constexpr int kMaxTransitionsPerSecond = noflash::kMaxTransitionsPerWindow;

ReactionSpec link(Source source, Target target, bool invert = false) {
    ReactionSpec r;
    r.source = source;
    r.target = target;
    r.shape.amount = 1.0f;
    r.shape.invert = invert;
    return r;
}

ModulatorSpec square(float periodSeconds) {
    ModulatorSpec m;
    m.kind = mod::ModulatorKind::Lfo;
    m.wave = mod::Wave::Square;
    m.periodSeconds = periodSeconds;
    return m;
}

// A 4 Hz square on brightness and intensity from their darkest setting: eight swings a second
// from near black to bright, each long enough to rise most of the way even at the No-flash rise.
CustomizationPlan strobe() {
    CustomizationPlan plan = CustomizationPlan::defaults();
    plan.shared[static_cast<size_t>(Shared::Brightness)] = 0.0f;  // its darkest: a fraction of its range
    plan.shared[static_cast<size_t>(Shared::Intensity)] = 0.0f;
    const int c = plan.add(square(0.25f));
    plan.add(link(Source::modulator(c), Target::shared(Shared::Brightness)));
    plan.add(link(Source::modulator(c), Target::shared(Shared::Intensity)));
    return plan;
}

// The strobe plus two Modulators feeding each other's rate, depth and pattern, a strobing hit,
// and all of them on every luminous property, some of them inverted.
CustomizationPlan worstCase() {
    CustomizationPlan plan = strobe();
    const int a = plan.add(square(mod::kMinPeriodSeconds));
    const int b = plan.add(square(mod::kMinPeriodSeconds));
    for (const ModulatorField f : {ModulatorField::Rate, ModulatorField::Depth, ModulatorField::Pattern}) {
        plan.add(link(Source::modulator(a), Target::modulator(b, f)));
        plan.add(link(Source::modulator(b), Target::modulator(a, f), true));
        plan.add(link(Source::modulator(a), Target::modulator(a, f)));
    }
    bool invert = false;
    for (size_t p = 0; p < mod::kSharedCount; ++p) {
        if (!mod::sharedSpecs()[p].luminous) continue;
        const Target target = Target::shared(static_cast<Shared>(p));
        plan.add(link(Source::modulator(a), target, invert));
        plan.add(link(Source::modulator(b), target, !invert));
        plan.add(link(Source::sound(SoundSignal::Hit), target));
        invert = !invert;
    }
    return plan;
}

// The parts of the finished frame the luminous properties reach, through today's caps.
SceneParams graded(const Evaluated& out) {
    SceneParams p;
    p.brightness = out[Shared::Brightness];
    p.intensity = out[Shared::Intensity];
    p.contrast = out[Shared::Contrast];
    p.gamma = out[Shared::Gamma];
    p.bloom = out[Shared::Glow];
    p.glitch = out[Shared::Glitch];
    p.solarize = out[Shared::Solarize] >= 0.5f;
    p.invert = out[Shared::Invert] >= 0.5f;
    return safety::apply(p);
}

// The composite's colour chain (composite_frag.glsl postFx) on one grey sRGB value.
float grade(float c, const SceneParams& p) {
    c += p.bloom * c * c;
    c = (c - 0.5f) * p.contrast + 0.5f;
    c = std::pow(std::max(c, 0.0f), 1.0f / std::max(p.gamma, 0.05f));
    if (p.solarize) c = std::fabs(1.0f - 2.0f * c);
    c *= p.brightness * p.intensity;
    if (p.invert) c = std::max(1.0f - c, 0.0f);
    return std::clamp(c, 0.0f, 1.0f);
}

// A mid-grey picture with some structure, so the regions differ.
LumaGrid frameFor(const SceneParams& p) {
    LumaGrid grid;
    for (int cell = 0; cell < noflash::kCellCount; ++cell) {
        const float scene = 0.4f + 0.025f * static_cast<float>(cell);
        grid.luminance[static_cast<size_t>(cell)] = noflash::srgbToLinear(grade(scene, p));
    }
    return grid;
}

// Counts WCAG general-flash transitions in one area's series on its own, without the limiter.
class IndependentCounter {
public:
    void feed(double time, float value) {
        if (!started_) {
            started_ = true;
            low_ = high_ = value;
            return;
        }
        const bool rises = direction_ != 1 && value - low_ >= noflash::kGeneralFlashDelta && low_ < noflash::kGeneralFlashDarkLimit;
        const bool falls = direction_ != -1 && high_ - value >= noflash::kGeneralFlashDelta && value < noflash::kGeneralFlashDarkLimit;
        if (rises || falls) {
            direction_ = rises ? 1 : -1;
            times_.push_back(time);
            low_ = high_ = value;
            return;
        }
        low_ = std::min(low_, value);
        high_ = std::max(high_, value);
    }

    int mostInAnyWindow(double seconds) const {
        int most = 0;
        for (size_t i = 0; i < times_.size(); ++i) {
            int n = 0;
            for (size_t j = i; j < times_.size() && times_[j] - times_[i] <= seconds; ++j) n++;
            most = std::max(most, n);
        }
        return most;
    }

private:
    bool started_ = false;
    float low_ = 0.0f;
    float high_ = 0.0f;
    int direction_ = 0;
    std::vector<double> times_;
};

struct LoopRun {
    // The most transitions any area made in any one second.
    int mostPerSecond = 0;
    // The most the limiter itself counted at any frame.
    int limiterMost = 0;
};

// Plays the plan for kSeconds at `fps` with a hit strobing at 10 Hz, and counts what is shown:
// the candidate frame as it is, or what the limiter lets through of it.
LoopRun play(const CustomizationPlan& plan, int fps, bool limited) {
    ReactionGraph graph;
    FlashLimiter limiter;
    std::array<IndependentCounter, noflash::kAreaCount> counters{};
    LumaGrid shown;
    bool first = true;
    LoopRun run;
    const float dt = 1.0f / static_cast<float>(fps);
    const int frames = static_cast<int>(kSeconds * fps);
    for (int frame = 0; frame < frames; ++frame) {
        const double time = frame * static_cast<double>(dt);
        SignalFrame heard;
        heard.level[static_cast<size_t>(SoundSignal::Hit)] = static_cast<int>(time * 20.0) % 2 == 0 ? 1.0f : 0.0f;
        const LumaGrid candidate = frameFor(graded(graph.evaluate(plan, heard, dt)));
        if (first || !limited) {
            shown = candidate;
            if (limited) limiter.step(dt, candidate);
            first = false;
        } else {
            const float amount = limiter.step(dt, candidate);
            for (size_t c = 0; c < static_cast<size_t>(noflash::kCellCount); ++c) {
                shown.luminance[c] += (candidate.luminance[c] - shown.luminance[c]) * amount;
            }
            run.limiterMost = std::max(run.limiterMost, limiter.transitionsInWindow());
        }
        std::array<float, noflash::kAreaCount> areas{};
        FlashLimiter::areaMeans(shown.luminance, areas);
        for (size_t a = 0; a < areas.size(); ++a) counters[a].feed(time, areas[a]);
    }
    for (const auto& c : counters) run.mostPerSecond = std::max(run.mostPerSecond, c.mostInAnyWindow(noflash::kWindowSeconds));
    return run;
}

TEST(NoFlashLoop, TheStrobeReallyFlashesWithoutTheLimiter) {
    // The worst case has teeth: the clamp alone lets it through at more than three flashes a second.
    EXPECT_GT(play(strobe(), 60, false).mostPerSecond, kMaxTransitionsPerSecond);
}

TEST(NoFlashLoop, TheWorstCaseLoopNeverBreaksTheBrightnessLimitLive) {
    const LoopRun run = play(worstCase(), 60, true);
    EXPECT_LE(run.mostPerSecond, kMaxTransitionsPerSecond);
    EXPECT_LE(run.limiterMost, kMaxTransitionsPerSecond);
}

TEST(NoFlashLoop, TheWorstCaseLoopNeverBreaksTheBrightnessLimitInARender) {
    // A Render runs at its own frame rate; the limit holds at each of them.
    for (const int fps : {24, 30, 120}) {
        const LoopRun run = play(worstCase(), fps, true);
        EXPECT_LE(run.mostPerSecond, kMaxTransitionsPerSecond) << fps;
        EXPECT_LE(run.limiterMost, kMaxTransitionsPerSecond) << fps;
    }
}

TEST(NoFlashLoop, NoLuminousPropertyRisesFasterThanTheNoFlashMinimum) {
    const CustomizationPlan plan = worstCase();
    ReactionGraph graph;
    constexpr float kDt = 1.0f / 60.0f;
    std::array<float, mod::kSharedCount> last{};
    for (int frame = 0; frame < 600; ++frame) {
        SignalFrame heard;
        heard.level[static_cast<size_t>(SoundSignal::Hit)] = frame % 3 == 0 ? 1.0f : 0.0f;
        const Evaluated& out = graph.evaluate(plan, heard, kDt);
        for (size_t p = 0; p < mod::kSharedCount; ++p) {
            const mod::SharedSpec& s = mod::sharedSpecs()[p];
            const float u = mod::toUnit(static_cast<Shared>(p), out.shared[p]);
            // A switch flips only once its fraction has risen past one half, so its step is allowed.
            if (frame > 0 && s.luminous && s.kind == mod::ValueKind::Continuous) {
                ASSERT_LE(u - last[p], kDt / mod::kNoFlashMinRiseSeconds + 1e-4f) << s.name << " at frame " << frame;
            }
            last[p] = u;
        }
    }
}

}  // namespace
}  // namespace geode::viz
