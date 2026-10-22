#include "Wire.hpp"

#include <bit>
#include <cmath>

namespace geode::bridge::wire {

namespace {
constexpr unsigned kBitsPerByte = 8;
constexpr uint64_t kByteMask = 0xFF;
constexpr uint8_t kFlagOff = 0;
constexpr uint8_t kFlagOn = 1;
}  // namespace

uint64_t Reader::take(size_t width) noexcept {
    if (remaining() < width) return 0;
    uint64_t value = 0;
    for (size_t i = 0; i < width; ++i) {
        value |= static_cast<uint64_t>(bytes_[offset_ + i]) << (kBitsPerByte * i);
    }
    offset_ += width;
    return value;
}

float Reader::f32() noexcept {
    return std::bit_cast<float>(u32());
}

void Writer::put(uint64_t value, size_t width) noexcept {
    if (bytes_.size() - offset_ < width) return;
    for (size_t i = 0; i < width; ++i) {
        bytes_[offset_ + i] = static_cast<uint8_t>((value >> (kBitsPerByte * i)) & kByteMask);
    }
    offset_ += width;
}

void Writer::f32(float value) noexcept {
    u32(std::bit_cast<uint32_t>(value));
}

bool readFlag(Reader& in, bool& out) noexcept {
    const uint8_t raw = in.u8();
    if (raw != kFlagOff && raw != kFlagOn) return false;
    out = raw == kFlagOn;
    return true;
}

bool readFinite(Reader& in, float& out) noexcept {
    const float value = in.f32();
    if (!std::isfinite(value)) return false;
    out = value;
    return true;
}

bool readFinite(Reader& in, std::span<float> out) noexcept {
    for (float& value : out) {
        if (!readFinite(in, value)) return false;
    }
    return true;
}

}  // namespace geode::bridge::wire
