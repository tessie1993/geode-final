// The Style lab: the renderer's side of the debug build's authoring loop (docs/style-packs.md,
// "Authoring loop"; geode/viz/StyleLab.hpp). Compiled only with GEODE_STYLE_HOT_RELOAD, which the
// debug build type alone sets: a release build has no hot-reload path.
#ifdef GEODE_STYLE_HOT_RELOAD

#include <dirent.h>
#include <sys/stat.h>

#include <fstream>
#include <iterator>
#include <vector>

#include "geode/util/Log.hpp"
#include "geode/viz/Renderer.hpp"
#include "geode/viz/StyleProfiles.hpp"
#include "scenes/ShaderScene.hpp"

namespace geode::viz {

namespace {

constexpr const char* kTag = "StyleLab";
constexpr int64_t kNanosPerSecond = 1000000000;

std::optional<lab::FileStamp> stampOf(const std::string& path) {
    struct stat info {};
    if (::stat(path.c_str(), &info) != 0 || !S_ISREG(info.st_mode)) return std::nullopt;
    return lab::FileStamp{static_cast<int64_t>(info.st_mtim.tv_sec) * kNanosPerSecond + static_cast<int64_t>(info.st_mtim.tv_nsec),
                          static_cast<int64_t>(info.st_size)};
}

std::optional<std::string> readFile(const std::string& path) {
    std::ifstream in(path, std::ios::binary);
    if (!in) return std::nullopt;
    return std::string(std::istreambuf_iterator<char>(in), std::istreambuf_iterator<char>());
}

// The folders in `dir`: one directory read, so a lab holding one Style costs a handful of stats per
// poll however many Styles ship.
std::vector<std::string> foldersIn(const std::string& dir) {
    std::vector<std::string> out;
    DIR* listing = ::opendir(dir.c_str());
    if (listing == nullptr) return out;
    while (const dirent* entry = ::readdir(listing)) {
        const std::string name = entry->d_name;
        if (name != "." && name != "..") out.push_back(name);
    }
    ::closedir(listing);
    return out;
}

// "styles/kifs/shader.glsl" -> "shader.glsl".
std::string fileNameOf(const std::string& path) {
    const size_t slash = path.rfind('/');
    return slash == std::string::npos ? path : path.substr(slash + 1);
}

}  // namespace

void Renderer::setStyleLab(const std::string& dir) {
    std::lock_guard<std::mutex> lock(stateLock_);
    styleLabDir_ = dir;
}

void Renderer::pollStyleLab() {
    std::string dir;
    {
        std::lock_guard<std::mutex> lock(stateLock_);
        dir = styleLabDir_;
    }
    if (dir.empty() || !styleLab_.due(frameNowS_)) return;
    for (const std::string& id : foldersIn(dir)) {
        // A folder no Style ships under is reported once per change, by the reload it fails.
        const packs::StylePack* pack = registry_.pack(id);
        const std::string folder = dir + "/" + id + "/";
        const std::string shaderFile = pack == nullptr || pack->shaderPath.empty() ? std::string() : fileNameOf(pack->shaderPath);
        lab::FolderStamp stamp;
        stamp.manifest = stampOf(folder + std::string(packs::kManifestFile));
        if (!shaderFile.empty()) stamp.shader = stampOf(folder + shaderFile);
        if (styleLab_.changed(id, stamp)) reloadStyle(id, folder, shaderFile);
    }
}

void Renderer::reloadStyle(const std::string& id, const std::string& folder, const std::string& shaderFile) {
    const std::optional<std::string> manifest = readFile(folder + std::string(packs::kManifestFile));
    std::optional<std::string> shader;
    if (!shaderFile.empty()) shader = readFile(folder + shaderFile);
    const packs::StylePack* before = registry_.pack(id);
    const bool wasShader = before != nullptr && before->family == styles::family::kShader;
    std::string error;
    if (!registry_.reload(id, manifest, shader, &error)) {
        styleLabFailed_ = true;
        fail("Style lab: " + error);
        return;
    }
    GEODE_LOGI(kTag, "reloaded %s from %s", id.c_str(), folder.c_str());
    if (styleLabFailed_) {
        styleLabFailed_ = false;
        fail("");
    }
    // The Extras' ranges come from the pack: read them again.
    rangesSceneId_.clear();
    Scene* built = builtScene(id);
    if (built == nullptr) return;
    const packs::StylePack* after = registry_.pack(id);
    if (wasShader && after != nullptr && after->family == styles::family::kShader) {
        const auto fragment = registry_.fragment(*after, &error);
        if (!fragment) {
            styleLabFailed_ = true;
            fail("Style lab: " + error);
            return;
        }
        // A shader Style's scene is a ShaderScene (SceneRegistry's buildShader). It swaps the program
        // at its next draw and reports a compile error, naming the file and line, through fail().
        static_cast<ShaderScene*>(built)->replaceFragment(*fragment);
        return;
    }
    // Any other family reads its pack when it is built.
    dropScene(id);
}

void Renderer::dropScene(const std::string& id) {
    for (auto entry = scenes_.begin(); entry != scenes_.end(); ++entry) {
        if (entry->first != id) continue;
        Scene* scene = entry->second.get();
        if (activeScene_ == scene) activeScene_ = nullptr;
        if (outgoingScene_ == scene) {
            outgoingScene_ = nullptr;
            outgoingParams_.reset();
        }
        if (layerScene_ == scene) layerScene_ = nullptr;
        scene->release();
        scenes_.erase(entry);
        return;
    }
}

}  // namespace geode::viz

#endif  // GEODE_STYLE_HOT_RELOAD
