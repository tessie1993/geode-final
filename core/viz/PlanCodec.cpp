#include "geode/viz/PlanCodec.hpp"

#include <algorithm>
#include <cmath>

#include "geode/viz/LiveSignal.hpp"

namespace geode::viz::mod {

namespace {
constexpr float kSecondsPerMinute = 60.0f;
// How sure the live tempo must be before beat-timed things use it: the analysis's own lock (BeatGrid).
constexpr float kTempoHeardConfidence = 0.4f;
constexpr size_t kTargetKinds = static_cast<size_t>(Target::Kind::Modulator) + 1;
constexpr size_t kSourceKinds = static_cast<size_t>(Source::Kind::Modulator) + 1;
// The largest 24-bit Style name (layer::styleHash).
constexpr float kMaxStyleName = 16777215.0f;

// Reads the floats in order.
class Cursor {
public:
    explicit Cursor(const float* data) : data_(data) {}

    float next() { return data_[at_++]; }
    // A whole number 0..count-1 (not a number: 0).
    size_t index(size_t count) {
        const float v = next();
        if (!std::isfinite(v)) return 0;
        return static_cast<size_t>(std::lround(std::clamp(v, 0.0f, static_cast<float>(count - 1))));
    }
    template <typename E>
    E as(size_t count) { return static_cast<E>(index(count)); }
    bool flag() { return next() > 0.5f; }
    int count(int max) { return static_cast<int>(index(static_cast<size_t>(max) + 1)); }
    // An index into an array, or 255 (out of every array) when it is not one.
    uint8_t slot() { return static_cast<uint8_t>(index(256)); }

    Target target() {
        Target t;
        t.kind = as<Target::Kind>(kTargetKinds);
        t.index = slot();
        t.field = as<ModulatorField>(kModulatorFieldCount);
        return t;
    }
    void steps(std::array<float, kMaxSteps>& out) {
        for (auto& s : out) s = next();
    }
    // A Style's 24-bit name (layer::styleHash) is a whole float; anything else names no Style.
    uint32_t styleName() {
        const float v = next();
        const bool named = std::isfinite(v) && v >= 1.0f && v <= kMaxStyleName && v == std::floor(v);
        return named ? static_cast<uint32_t>(v) : layer::kNoStyle;
    }

private:
    const float* data_;
    size_t at_ = 0;
};
}  // namespace

bool decodePlan(const float* data, size_t count, CustomizationPlan& out) {
    if (!data || count != kPlanFloats || !(data[0] > 0.5f)) return false;
    Cursor in(data + 1);
    CustomizationPlan plan;
    plan.reactionCount = in.count(kMaxReactions);
    plan.modulatorCount = in.count(kMaxModulators);
    plan.macroLinkCount = in.count(kMaxMacroLinks);
    plan.automationCount = in.count(kMaxAutomations);
    for (auto& v : plan.shared) v = in.next();
    for (auto& v : plan.extras) v = in.next();
    for (auto& v : plan.macros) v = in.next();
    for (auto& t : plan.trims) {
        t.gain = in.next();
        t.floor = in.next();
    }
    for (auto& r : plan.reactions) {
        r.source.kind = in.as<Source::Kind>(kSourceKinds);
        r.source.index = in.slot();
        r.target = in.target();
        r.shape.amount = in.next();
        r.shape.invert = in.flag();
        r.shape.low = in.next();
        r.shape.high = in.next();
        r.shape.curve = in.as<Curve>(static_cast<size_t>(Curve::SCurve) + 1);
        r.riseSeconds = in.next();
        r.fallSeconds = in.next();
    }
    for (auto& m : plan.modulators) {
        m.kind = in.as<ModulatorKind>(static_cast<size_t>(ModulatorKind::RandomOnBeat) + 1);
        m.wave = in.as<Wave>(static_cast<size_t>(Wave::Random) + 1);
        m.periodSeconds = in.next();
        m.beatSync = in.flag();
        m.signal = in.as<SoundSignal>(kSoundSignalCount);
        m.attackSeconds = in.next();
        m.releaseSeconds = in.next();
        m.threshold = in.next();
        in.steps(m.steps);
        m.stepCount = in.count(kMaxSteps);
        m.stepSeconds = in.next();
        m.start = in.as<StartMode>(static_cast<size_t>(StartMode::ByHand) + 1);
        m.startSignal = in.as<SoundSignal>(kSoundSignalCount);
        m.playing = in.flag();
        m.rate = in.next();
        m.depth = in.next();
        const float seed = in.next();
        m.seed = std::isfinite(seed) && seed >= 1.0f ? static_cast<uint32_t>(std::min(seed, 16777215.0f)) : 1u;
    }
    for (auto& l : plan.macroLinks) {
        l.macro = static_cast<uint8_t>(in.index(kMacroCount + 1));
        l.target = in.target();
        l.low = in.next();
        l.high = in.next();
    }
    for (auto& a : plan.automations) {
        a.kind = in.as<AutomationKind>(static_cast<size_t>(AutomationKind::Lane) + 1);
        a.target = in.target();
        a.amount = in.next();
        a.everyBeats = static_cast<int>(std::max<size_t>(in.index(1025), 1));
        a.trigger = in.as<TriggerKind>(static_cast<size_t>(TriggerKind::BigMoments) + 1);
        a.threshold = in.next();
        a.spacingSeconds = in.next();
        a.fadeSeconds = in.next();
        a.alternate = in.flag();
        a.held = in.flag();
        a.stepCount = in.count(kMaxSteps);
        in.steps(a.steps);
    }
    plan.layer.style = in.styleName();
    plan.layer.blend = layer::blendAt(in.next());
    plan.layer.influence = layer::influenceAt(in.next());
    plan.style = in.styleName();
    out = plan;
    return true;
}

uint32_t planStyle(const float* data, size_t count) {
    if (!data || count != kPlanFloats || !(data[0] > 0.5f)) return layer::kNoStyle;
    Cursor in(data + kPlanFloats - kStyleFloats);
    return in.styleName();
}

SignalFrame signalFrame(const GeodeFeatureFrame& f) {
    SignalFrame s;
    const auto set = [&s](SoundSignal signal, float value) {
        s.level[static_cast<size_t>(signal)] = std::isfinite(value) ? std::clamp(value, 0.0f, 1.0f) : 0.0f;
    };
    set(SoundSignal::Level, live::level(f));
    set(SoundSignal::Bass, f.bass);
    set(SoundSignal::Mid, f.mid);
    set(SoundSignal::Treble, f.treble);
    set(SoundSignal::Brightness, live::brightness(f));
    set(SoundSignal::Hit, live::hit(f));
    set(SoundSignal::Kick, f.kick);
    set(SoundSignal::MidHit, f.snare);
    set(SoundSignal::Hat, f.hat);
    set(SoundSignal::Beat, live::beatImpulse(f));
    set(SoundSignal::Energy, f.macroEnergy);
    set(SoundSignal::BuildUp, f.buildup);
    set(SoundSignal::Surge, f.drop);
    set(SoundSignal::Change, f.sectionBoundary);
    set(SoundSignal::StereoWidth, live::width(f));
    // Pan is -1 (left) .. 1 (right); as a Sound signal, 0.5 is the centre.
    set(SoundSignal::StereoPan, (live::pan(f) + 1.0f) * 0.5f);
    // A beat length only from a tempo clearly heard: a guess is no tempo, and beat-timed things hold.
    const bool heard = f.bpm > 0.0f && std::isfinite(f.bpm) && f.pulseConfidence >= kTempoHeardConfidence;
    s.beatSeconds = heard ? kSecondsPerMinute / f.bpm : 0.0f;
    return s;
}

}  // namespace geode::viz::mod
