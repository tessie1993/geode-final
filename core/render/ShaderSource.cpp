#include "geode/render/ShaderSource.hpp"

#include <array>
#include <memory>
#include <vector>

#include "geode/util/ShaderAssembly.hpp"

namespace geode::viz {

namespace {

// The libraries a shader may include (shaders/<name>.glsl). tools/glslcheck/check.mjs reads this list.
constexpr std::array<const char*, 13> kIncludes = {
    "lib_palette",         "lib_scene_uniforms", "lib_scene_grade",  "lib_sdf3", "lib_touch", "lib_psrdnoise2",
    "lib_particle_common", "lib_particle_shade", "lib_scene_motion", "lib_dmt",  "lib_style", "lib_camera",
    "lib_layers",
};

const std::vector<std::string>& knownIncludes() {
    static const std::vector<std::string> known(kIncludes.begin(), kIncludes.end());
    return known;
}

struct AssetClose {
    void operator()(AAsset* asset) const { AAsset_close(asset); }
};

}  // namespace

std::optional<std::string> ShaderSource::load(const std::string& name, std::string* error) const {
    const std::string path = "shaders/" + name;
    const auto raw = readAsset(path);
    if (!raw) {
        if (error) *error = "missing shader asset '" + name + "'";
        return std::nullopt;
    }
    return resolveIncludes(path, *raw, error);
}

std::optional<std::string> ShaderSource::loadAsset(const std::string& path, std::string* error) const {
    const auto raw = readAsset(path);
    if (!raw) {
        if (error) *error = "missing shader asset '" + path + "'";
        return std::nullopt;
    }
    return resolveIncludes(path, *raw, error);
}

std::optional<std::string> ShaderSource::readAsset(const std::string& path) const {
    if (!assets_) return std::nullopt;
    std::unique_ptr<AAsset, AssetClose> asset(AAssetManager_open(assets_, path.c_str(), AASSET_MODE_BUFFER));
    if (!asset) return std::nullopt;
    const off_t length = AAsset_getLength(asset.get());
    std::string out(static_cast<size_t>(length), '\0');
    if (length > 0 && AAsset_read(asset.get(), out.data(), static_cast<size_t>(length)) != length) return std::nullopt;
    return out;
}

std::optional<std::string> ShaderSource::resolveIncludes(const std::string& file, const std::string& source, std::string* error) const {
    return geode::shader::assemble(file, source, knownIncludes(), [this](const std::string& path) { return readAsset(path); }, error);
}

}  // namespace geode::viz
