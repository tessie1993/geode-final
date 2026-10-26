#include "geode/render/ImageDecoder.hpp"

#include <android/bitmap.h>
#include <android/imagedecoder.h>

#include <cstring>
#include <memory>

namespace geode::viz {

namespace {

constexpr size_t kBytesPerPixel = 4;

struct DecoderDelete {
    void operator()(AImageDecoder* decoder) const { AImageDecoder_delete(decoder); }
};

std::optional<Image> failed(std::string* error, const std::string& why) {
    if (error) *error = why;
    return std::nullopt;
}

}  // namespace

std::optional<Image> decodeImage(const std::string& bytes, int maxSide, std::string* error) {
    if (bytes.empty()) return failed(error, "the image is empty");
    AImageDecoder* raw = nullptr;
    if (AImageDecoder_createFromBuffer(bytes.data(), bytes.size(), &raw) != ANDROID_IMAGE_DECODER_SUCCESS || raw == nullptr) {
        return failed(error, "not an image this device can decode");
    }
    std::unique_ptr<AImageDecoder, DecoderDelete> decoder(raw);
    if (AImageDecoder_setAndroidBitmapFormat(decoder.get(), ANDROID_BITMAP_FORMAT_RGBA_8888) != ANDROID_IMAGE_DECODER_SUCCESS) {
        return failed(error, "the image cannot be decoded as RGBA");
    }
    // A shader reads colour and alpha apart, as the pack's author painted them; an opaque image has
    // nothing to premultiply, so refusing this changes nothing for it.
    AImageDecoder_setUnpremultipliedRequired(decoder.get(), true);
    const AImageDecoderHeaderInfo* info = AImageDecoder_getHeaderInfo(decoder.get());
    const int width = AImageDecoderHeaderInfo_getWidth(info);
    const int height = AImageDecoderHeaderInfo_getHeight(info);
    if (width <= 0 || height <= 0 || width > maxSide || height > maxSide) {
        return failed(error, "the image is " + std::to_string(width) + "x" + std::to_string(height) + ", over " + std::to_string(maxSide) +
                                 " a side");
    }
    const size_t stride = AImageDecoder_getMinimumStride(decoder.get());
    const size_t row = static_cast<size_t>(width) * kBytesPerPixel;
    const auto rows = static_cast<size_t>(height);
    std::vector<std::uint8_t> decoded(stride * rows);
    if (AImageDecoder_decodeImage(decoder.get(), decoded.data(), stride, decoded.size()) != ANDROID_IMAGE_DECODER_SUCCESS) {
        return failed(error, "the image could not be decoded");
    }
    // The decoder writes the top row first; GL's first row is the bottom one.
    Image image{width, height, std::vector<std::uint8_t>(row * rows)};
    for (size_t y = 0; y < rows; ++y) std::memcpy(image.rgba.data() + (rows - 1 - y) * row, decoded.data() + y * stride, row);
    return image;
}

}  // namespace geode::viz
