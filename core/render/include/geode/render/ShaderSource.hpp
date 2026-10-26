#pragma once
#include <android/asset_manager.h>

#include <optional>
#include <string>

namespace geode::viz {

// Shader sources from the APK, assembled by geode/util/ShaderAssembly.hpp: includes resolved, and
// `#line` directives in, so a compile error names the file and line the author wrote.
class ShaderSource {
public:
    explicit ShaderSource(AAssetManager* assets) : assets_(assets) {}

    // Reads shaders/<name> from the APK and resolves its //#include lines; nullopt when the asset is missing.
    std::optional<std::string> load(const std::string& name, std::string* error) const;
    // Reads the asset at `path` (a Style pack's shader, styles/<id>/...) and resolves its //#include lines
    // against shaders/; nullopt when the asset is missing.
    std::optional<std::string> loadAsset(const std::string& path, std::string* error) const;
    std::optional<std::string> readAsset(const std::string& path) const;
    // Resolves the //#include lines of `source`, reported as `file` in compile errors, against shaders/.
    std::optional<std::string> resolveIncludes(const std::string& file, const std::string& source, std::string* error) const;

private:
    AAssetManager* assets_;
};

}  // namespace geode::viz
