#pragma once
// The little-endian primitives the generated snapshot codec (CustomizationSnapshot.cpp) is written in.
// Private to the bridge library; the format itself is described in tools/bridge_schema/generate.py.

#include <cstddef>
#include <cstdint>
#include <span>

namespace geode::bridge::wire {

// Reads values front to back. A read past the end returns 0 and consumes nothing, so a caller that
// checked remaining() first can read without checking each value.
class Reader {
public:
    explicit Reader(std::span<const uint8_t> bytes) noexcept : bytes_(bytes) {}

    size_t remaining() const noexcept { return bytes_.size() - offset_; }
    uint8_t u8() noexcept { return static_cast<uint8_t>(take(sizeof(uint8_t))); }
    uint16_t u16() noexcept { return static_cast<uint16_t>(take(sizeof(uint16_t))); }
    uint32_t u32() noexcept { return static_cast<uint32_t>(take(sizeof(uint32_t))); }
    float f32() noexcept;

private:
    uint64_t take(size_t width) noexcept;

    std::span<const uint8_t> bytes_;
    size_t offset_ = 0;
};

// Writes values front to back. A write that does not fit is dropped, so written() tells the caller
// whether everything landed.
class Writer {
public:
    explicit Writer(std::span<uint8_t> bytes) noexcept : bytes_(bytes) {}

    size_t written() const noexcept { return offset_; }
    void u8(uint8_t value) noexcept { put(value, sizeof(uint8_t)); }
    void u16(uint16_t value) noexcept { put(value, sizeof(uint16_t)); }
    void u32(uint32_t value) noexcept { put(value, sizeof(uint32_t)); }
    void f32(float value) noexcept;

private:
    void put(uint64_t value, size_t width) noexcept;

    std::span<uint8_t> bytes_;
    size_t offset_ = 0;
};

// A bool is one byte, 0 or 1; any other byte is malformed and returns false with `out` untouched.
bool readFlag(Reader& in, bool& out) noexcept;
// A float the engine can use: false (and `out` untouched) for NaN or an infinity.
bool readFinite(Reader& in, float& out) noexcept;
// Every element in turn; false at the first non-finite one.
bool readFinite(Reader& in, std::span<float> out) noexcept;

}  // namespace geode::bridge::wire
