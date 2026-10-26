#pragma once
#include <cstdint>
#include <optional>
#include <string>
#include <vector>

namespace geode::viz {

// A decoded image as GL uploads it: 8-bit RGBA, not premultiplied, its rows from the bottom up, so texture
// coordinate (0, 0) is the picture's bottom-left corner, as everywhere else in the shaders.
struct Image {
    int width = 0;
    int height = 0;
    std::vector<std::uint8_t> rgba;
};

// Decodes the PNG (or any other format the platform decodes) held in `bytes`, with the platform's image
// decoder; nullopt, with the reason in `error`, when it cannot be read or a side is longer than `maxSide`.
std::optional<Image> decodeImage(const std::string& bytes, int maxSide, std::string* error);

}  // namespace geode::viz
