#include "geode/audio/player/Output.hpp"

#include <algorithm>

namespace geode::audio::player {

namespace {
constexpr int64_t kStateTimeoutNs = 500'000'000;

// Adapts the Oboe stream to the BufferTuner. Both calls are plain AAudio/OpenSL ES queries with no
// allocation or locking, safe from the data callback (Oboe's own LatencyTuner makes them there too).
class OboeTunableStream final : public TunableStream {
public:
    explicit OboeTunableStream(oboe::AudioStream& stream) : stream_(stream) {}

    std::optional<int32_t> xrunCount() override {
        const auto result = stream_.getXRunCount();
        if (!result) return std::nullopt;
        return result.value();
    }

    std::optional<int32_t> setBufferSizeInFrames(int32_t frames) override {
        const auto result = stream_.setBufferSizeInFrames(frames);
        if (!result) return std::nullopt;
        return result.value();
    }

private:
    oboe::AudioStream& stream_;
};
}  // namespace

bool Output::open(std::string& error) {
    close();
    disconnected_.store(false, std::memory_order_release);
    if (!openWith(oboe::SharingMode::Exclusive) && !openWith(oboe::SharingMode::Shared)) {
        error = "audio output failed to open";
        return false;
    }
    sampleRate_ = stream_->getSampleRate();
    // Starts the buffer at the device burst; on InvalidStreamGeometry or Rejected the device keeps its own
    // default size and the tuner stays inactive, which is no worse than an untuned stream.
    OboeTunableStream tunable(*stream_);
    tuner_.reset(tunable, stream_->getFramesPerBurst(), stream_->getBufferCapacityInFrames(), sampleRate_);
    return true;
}

bool Output::openWith(oboe::SharingMode mode) {
    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Output)
        ->setSharingMode(mode)
        ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
        ->setFormat(oboe::AudioFormat::Float)
        ->setFormatConversionAllowed(true)
        ->setChannelCount(Mixer::kChannels)
        ->setChannelConversionAllowed(true)
        ->setUsage(oboe::Usage::Media)
        ->setContentType(oboe::ContentType::Music)
        ->setDataCallback(this)
        ->setErrorCallback(this);
    std::shared_ptr<oboe::AudioStream> stream;
    if (builder.openStream(stream) != oboe::Result::OK || !stream) return false;
    stream_ = std::move(stream);
    return true;
}

void Output::close() {
    if (!stream_) return;
    stream_->requestStop();
    stream_->close();
    stream_.reset();
    running_.store(false, std::memory_order_release);
}

bool Output::start() {
    if (!stream_ || stream_->requestStart() != oboe::Result::OK) return false;
    const bool started = settle(oboe::StreamState::Starting, oboe::StreamState::Started);
    running_.store(started, std::memory_order_release);
    return started;
}

bool Output::pause() {
    if (!stream_) return false;
    if (stream_->requestPause() != oboe::Result::OK) return false;
    const bool paused = settle(oboe::StreamState::Pausing, oboe::StreamState::Paused);
    if (paused) running_.store(false, std::memory_order_release);
    return paused;
}

bool Output::settle(oboe::StreamState leaving, oboe::StreamState wanted) {
    oboe::StreamState next = oboe::StreamState::Unknown;
    stream_->waitForStateChange(leaving, &next, kStateTimeoutNs);
    return stream_->getState() == wanted;
}

oboe::DataCallbackResult Output::onAudioReady(oboe::AudioStream* stream, void* audioData, int32_t numFrames) {
    auto* out = static_cast<float*>(audioData);
    size_t remaining = numFrames > 0 ? static_cast<size_t>(numFrames) : 0;
    while (remaining > 0) {
        const size_t n = std::min(remaining, Mixer::kMaxRenderFrames);
        mixer_.render(out, n);
        out += n * Mixer::kChannels;
        remaining -= n;
    }
    if (stream != nullptr) {
        OboeTunableStream tunable(*stream);
        tuner_.onAudioReady(tunable, numFrames);
    }
    return oboe::DataCallbackResult::Continue;
}

void Output::onErrorAfterClose(oboe::AudioStream*, oboe::Result) {
    running_.store(false, std::memory_order_release);
    disconnected_.store(true, std::memory_order_release);
}

}  // namespace geode::audio::player
