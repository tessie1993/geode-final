#include "geode/analysis/AnalysisSession.hpp"

#include <algorithm>
#include <cstring>

#include "StereoField.hpp"

namespace geode::analysis {

namespace {
// push()'s buffer_/sideBuffer_ are capped at this many FFT windows so a producer that pushes without ever
// pulling cannot grow them without bound.
constexpr size_t kMaxBufferedWindows = 8;

// Clamped to fftSize so pull() always has at least one full window buffered before it advances past the
// window it just analyzed; without this, a hop rate too low for the sample rate (e.g. 60 Hz at 192 kHz with
// a 2048 FFT) would produce hopSamples_ > fftSize_ and underflow `buffered_ - hop` in pull(). The clamp keeps
// analysis running with a degraded (too-frequent) hop instead of crashing.
int hopFor(int sampleRateHz, float hopRateHz, int fftSize) {
    const int hop = std::max(static_cast<int>(sampleRateHz / hopRateHz), 1);
    return std::min(hop, fftSize);
}

// Sequence numbers cross JNI as floats, which hold every integer below 2^24 exactly; they wrap back to 1 there
// (0 is "not fired yet"), so a reader comparing for inequality still sees every step.
constexpr uint32_t kEventSeqWrap = 1u << 24;
}  // namespace

AnalysisSession::AnalysisSession(int sampleRateHz, int fftSize, float hopRateHz)
    : sampleRateHz_(sampleRateHz),
      fftSize_(fftSize),
      hopRateHz_(hopRateHz),
      hopSamples_(hopFor(sampleRateHz, hopRateHz, fftSize)),
      analyzer_(GEODE_BAND_COUNT, fftSize, sampleRateHz, hopRateHz),
      chroma_(hopRateHz),
      spectrum_(static_cast<size_t>(fftSize / 2)),
      buffer_(static_cast<size_t>(fftSize) * 4),
      sideBuffer_(static_cast<size_t>(fftSize) * 4) {}

void AnalysisSession::setSampleRateHz(int value) {
    if (value <= 0 || value == sampleRateHz_) return;
    sampleRateHz_ = value;
    hopSamples_ = hopFor(value, hopRateHz_, fftSize_);
    analyzer_.setSampleRateHz(value);
}

void AnalysisSession::setTuning(float sensitivity, float refractoryMs, float attackSeconds, float releaseSeconds) {
    analyzer_.setSensitivity(sensitivity);
    analyzer_.setRefractoryMs(refractoryMs);
    analyzer_.setAttackSeconds(attackSeconds);
    analyzer_.setReleaseSeconds(releaseSeconds);
}

void AnalysisSession::reset() {
    analyzer_.reset();
    chroma_.reset();
    buffered_ = 0;
    eventSeq_.fill(0);
    eventStrength_.fill(0.0f);
}

void AnalysisSession::analyze(const float* mid, const float* side, size_t frames, float dtSeconds, GeodeFeatureFrame& out) {
    if (frames < static_cast<size_t>(fftSize_)) return;
    analyzer_.analyze(mid, dtSeconds);
    analyzer_.spectrumInto(spectrum_.data());
    chroma_.step(spectrum_.data(), static_cast<int>(spectrum_.size()), sampleRateHz_, fftSize_);
    fill(mid, side, out);
    sequence(out);
    averagedWaveform(mid, out.waveform);
}

void AnalysisSession::push(const float* interleaved, size_t frames, int channels) {
    if (channels <= 0 || frames == 0) return;
    // Bound how far this can grow: a producer that pushes without ever pulling must not grow the buffer
    // without bound. Cap at a few FFT windows and drop the oldest samples once at the cap.
    const size_t cap = static_cast<size_t>(fftSize_) * kMaxBufferedWindows;
    if (buffer_.size() < cap && buffered_ + frames > buffer_.size()) {
        const size_t grown = std::min(std::max(buffered_ + frames, buffer_.size() * 2), cap);
        buffer_.resize(grown);
        sideBuffer_.resize(grown);
    }
    // A single push larger than the cap: keep only its most recent samples.
    if (frames > buffer_.size()) {
        const size_t skip = frames - buffer_.size();
        interleaved += skip * static_cast<size_t>(channels);
        frames = buffer_.size();
    }
    // Still no room (buffer already at the cap): drop the oldest buffered samples.
    if (buffered_ + frames > buffer_.size()) {
        const size_t keep = buffer_.size() - frames;
        const size_t drop = buffered_ - keep;
        std::memmove(buffer_.data(), buffer_.data() + drop, keep * sizeof(float));
        std::memmove(sideBuffer_.data(), sideBuffer_.data() + drop, keep * sizeof(float));
        buffered_ = keep;
    }
    size_t s = 0;
    for (size_t f = 0; f < frames; f++) {
        const float left = interleaved[s];
        const float right = channels >= 2 ? interleaved[s + 1] : left;
        buffer_[buffered_ + f] = (left + right) * 0.5f;
        sideBuffer_[buffered_ + f] = (left - right) * 0.5f;
        s += static_cast<size_t>(channels);
    }
    buffered_ += frames;
}

bool AnalysisSession::pull(GeodeFeatureFrame& out) {
    if (buffered_ < static_cast<size_t>(fftSize_)) return false;
    const float* mid = buffer_.data();
    const float* side = sideBuffer_.data();
    // The hop is a whole number of samples, so its duration is that many samples, not 1 / hopRateHz_: the
    // analyser's clock then advances by exactly the audio it consumed, live and offline alike.
    analyzer_.analyze(mid, static_cast<float>(hopSamples_) / static_cast<float>(sampleRateHz_));
    analyzer_.spectrumInto(spectrum_.data());
    key_.accumulate(spectrum_.data(), static_cast<int>(spectrum_.size()), sampleRateHz_, fftSize_);
    chroma_.step(spectrum_.data(), static_cast<int>(spectrum_.size()), sampleRateHz_, fftSize_);
    fill(mid, side, out);
    sequence(out);
    decimatedWaveform(mid, out.waveform);

    // hopSamples_ is clamped to fftSize_ at construction/setSampleRateHz, and buffered_ >= fftSize_ here
    // (the guard above), so hop <= buffered_ always; the extra clamp is defense in depth.
    const size_t hop = std::min(static_cast<size_t>(hopSamples_), buffered_);
    const size_t remaining = buffered_ - hop;
    std::memmove(buffer_.data(), buffer_.data() + hop, remaining * sizeof(float));
    std::memmove(sideBuffer_.data(), sideBuffer_.data() + hop, remaining * sizeof(float));
    buffered_ = remaining;
    return true;
}

void AnalysisSession::fill(const float* mid, const float* side, GeodeFeatureFrame& out) const {
    const auto& a = analyzer_;
    out.rms = a.rms();
    out.bass = a.bass();
    out.mid = a.mid();
    out.treble = a.treble();
    out.centroid = a.centroid();
    out.flux = a.fluxValue();
    out.onset = a.onset();
    out.beat = a.beat() ? 1.0f : 0.0f;
    out.beatStrength = a.beatStrength();
    out.transient = a.transient();
    out.beatPhase = a.beatPhase();
    out.pulseConfidence = a.pulseConfidence();
    out.bpm = a.bpm();
    out.tempoStability = a.tempoStability();
    out.barPhase = a.barPhase();
    out.beatInBar = static_cast<float>(a.beatInBar());
    out.downbeat = a.downbeat() ? 1.0f : 0.0f;
    out.downbeatConfidence = a.downbeatConfidence();
    out.macroEnergy = a.macroEnergy();
    out.kick = a.kick();
    out.snare = a.snare();
    out.hat = a.hat();
    out.novelty = a.novelty();
    out.sectionBoundary = a.sectionBoundary() ? 1.0f : 0.0f;
    out.buildup = a.buildup();
    out.drop = a.drop() ? 1.0f : 0.0f;
    out.arrival = a.arrival() ? 1.0f : 0.0f;
    out.harmonicity = a.harmonicity();
    out.warmup = a.warmup();
    const stereo::Reading reading = side ? stereo::of(mid, side, fftSize_) : stereo::kMono;
    out.stereoWidth = reading.width;
    out.stereoCorrelation = reading.correlation;
    out.stereoPan = reading.pan;
    out.chromaConfidence = chroma_.confidence();
    std::copy(a.bands().begin(), a.bands().end(), out.bands);
    std::copy(chroma_.bins().begin(), chroma_.bins().end(), out.chroma);
}

// Counts every one-hop event that fired on this hop and keeps the strength it fired with, so the frame carries
// each occurrence for as long as it is the latest one, not only on its own hop.
void AnalysisSession::sequence(GeodeFeatureFrame& out) {
    std::array<float, GEODE_EVENT_COUNT> fired{};
    fired[GEODE_EVENT_BEAT] = out.beat > 0.0f ? (out.beatStrength > 0.0f ? out.beatStrength : 1.0f) : 0.0f;
    fired[GEODE_EVENT_TRANSIENT] = out.transient;
    fired[GEODE_EVENT_KICK] = out.kick;
    fired[GEODE_EVENT_SNARE] = out.snare;
    fired[GEODE_EVENT_HAT] = out.hat;
    fired[GEODE_EVENT_DOWNBEAT] = out.downbeat;
    fired[GEODE_EVENT_SECTION_BOUNDARY] = out.sectionBoundary;
    fired[GEODE_EVENT_DROP] = out.drop;
    fired[GEODE_EVENT_ARRIVAL] = out.arrival;
    for (size_t e = 0; e < fired.size(); e++) {
        if (fired[e] > 0.0f) {
            eventSeq_[e] = eventSeq_[e] + 1u >= kEventSeqWrap ? 1u : eventSeq_[e] + 1u;
            eventStrength_[e] = fired[e];
        }
        out.events[e].seq = static_cast<float>(eventSeq_[e]);
        out.events[e].strength = eventStrength_[e];
    }
}

void AnalysisSession::averagedWaveform(const float* mid, float* out) const {
    const int step = std::max(fftSize_ / GEODE_WAVEFORM_POINTS, 1);
    for (int i = 0; i < GEODE_WAVEFORM_POINTS; i++) {
        float acc = 0.0f;
        const int base = i * step;
        for (int j = 0; j < step; j++) acc += mid[base + j];
        out[i] = acc / step;
    }
}

void AnalysisSession::decimatedWaveform(const float* mid, float* out) const {
    const int step = std::max(fftSize_ / GEODE_WAVEFORM_POINTS, 1);
    for (int i = 0; i < GEODE_WAVEFORM_POINTS; i++) out[i] = mid[i * step];
}

}  // namespace geode::analysis
