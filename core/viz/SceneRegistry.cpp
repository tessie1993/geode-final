#include "geode/viz/SceneRegistry.hpp"

#include <array>
#include <functional>
#include <optional>
#include <string_view>
#include <utility>

#include "geode/render/ImageDecoder.hpp"
#include "geode/util/Log.hpp"
#include "geode/viz/StyleProfiles.hpp"
#include "scenes/AcidScene.hpp"
#include "scenes/CurlFlowScene.hpp"
#include "scenes/CymaticsScene.hpp"
#include "scenes/FluidScene.hpp"
#include "scenes/LifeScene.hpp"
#include "scenes/MilkdropScene.hpp"
#include "scenes/MycoScene.hpp"
#include "scenes/ShaderScene.hpp"
#include "scenes/WaterScene.hpp"

namespace geode::viz {

namespace {

constexpr const char* kTag = "SceneRegistry";

struct Build {
    const packs::StylePack& pack;
    const ProgramLoader& loader;
    const SceneHost& host;
    const std::string& quadVert;
    // The shader the Style lab pushed for this Style (debug builds only); null for the APK's.
    const std::string* labShader;
};

using Factory = std::function<std::unique_ptr<Scene>(const Build&)>;

std::optional<std::string> fragmentOf(const packs::StylePack& pack, const ShaderSource& assets, const std::string* labShader,
                                      std::string* error) {
#ifdef GEODE_STYLE_HOT_RELOAD
    if (labShader != nullptr) return assets.resolveIncludes(pack.shaderPath, *labShader, error);
#else
    (void) labShader;
#endif
    return assets.loadAsset(pack.shaderPath, error);
}

// The feedback warp's stage for a pack that opts into it (FeedbackWarp.hpp): the shared motion grid,
// and the pack's own warp fragment or the shared one.
std::optional<ShaderFeedback> feedbackOf(const packs::StylePack& pack, const ShaderSource& assets, std::string* error) {
    const auto vertex = assets.load("feedback_warp_vert.glsl", error);
    if (!vertex) return std::nullopt;
    const auto fragment =
        pack.feedbackShaderPath.empty() ? assets.load("feedback_warp_frag.glsl", error) : assets.loadAsset(pack.feedbackShaderPath, error);
    if (!fragment) return std::nullopt;
    return ShaderFeedback{pack.feedback, *vertex, *fragment};
}

// The images a pack ships ("textures", #391), decoded, each with the sampler its shader reads it by; nullopt,
// with the reason, when one is missing or cannot be decoded, as for a missing shader.
std::optional<std::vector<ShaderImage>> imagesOf(const packs::StylePack& pack, const ShaderSource& assets, std::string* error) {
    std::vector<ShaderImage> images;
    for (const packs::TextureSpec& spec : pack.textures) {
        const auto bytes = assets.readAsset(spec.path);
        if (!bytes) {
            *error = "missing image asset '" + spec.path + "'";
            return std::nullopt;
        }
        std::string why;
        auto image = decodeImage(*bytes, packs::kMaxTextureSide, &why);
        if (!image) {
            *error = "image '" + spec.path + "': " + why;
            return std::nullopt;
        }
        images.push_back(ShaderImage{packs::textureUniform(spec.name), spec.wrap, spec.filter, std::move(*image)});
    }
    return images;
}

std::unique_ptr<Scene> buildShader(const Build& b) {
    std::string error;
    const auto fragment = fragmentOf(b.pack, b.loader.assets, b.labShader, &error);
    if (!fragment) {
        b.host.onShaderError(error);
        return nullptr;
    }
    std::optional<ShaderFeedback> feedback;
    if (b.pack.feedback.on) {
        feedback = feedbackOf(b.pack, b.loader.assets, &error);
        if (!feedback) {
            b.host.onShaderError(error);
            return nullptr;
        }
    }
    auto images = imagesOf(b.pack, b.loader.assets, &error);
    if (!images) {
        b.host.onShaderError(error);
        return nullptr;
    }
    std::optional<ShaderMultipass> passes;
    if (b.pack.multipass.on) {
        // The engine's resolve, on the same fullscreen triangle as the Style (MultiPass.hpp).
        const auto resolve = b.loader.assets.load("multipass_resolve_frag.glsl", &error);
        if (!resolve) {
            b.host.onShaderError(error);
            return nullptr;
        }
        passes = ShaderMultipass{b.pack.multipass, b.quadVert, *resolve};
    }
    return std::make_unique<ShaderScene>(b.pack.id, b.quadVert, *fragment, b.loader.cache, b.host, b.pack.inputs, std::move(feedback),
                                         std::move(*images), std::move(passes));
}

// One renderer per family. A Style is a pack naming one of these; nothing here names a Style.
const std::array<std::pair<std::string_view, Factory>, 9>& factories() {
    static const std::array<std::pair<std::string_view, Factory>, 9> table = {{
        {styles::family::kShader, buildShader},
        {styles::family::kLife,
         [](const Build& b) { return std::make_unique<LifeScene>(styles::lifeProfile(b.pack), b.loader, b.host); }},
        {styles::family::kMycelium,
         [](const Build& b) { return std::make_unique<MycoScene>(styles::mycoProfile(b.pack), b.loader, b.host); }},
        {styles::family::kAcid,
         [](const Build& b) { return std::make_unique<AcidScene>(styles::acidProfile(b.pack), b.loader, b.host); }},
        {styles::family::kMilkdrop, [](const Build& b) { return std::make_unique<MilkdropScene>(b.pack.id, b.loader, b.host); }},
        {styles::family::kFluid,
         [](const Build& b) { return std::make_unique<FluidScene>(styles::fluidProfile(b.pack), b.loader, b.host); }},
        {styles::family::kCurlFlow, [](const Build& b) { return std::make_unique<CurlFlowScene>(b.pack.id, b.loader, b.host); }},
        {styles::family::kWater, [](const Build& b) { return std::make_unique<WaterScene>(b.pack.id, b.loader, b.host); }},
        {styles::family::kCymatics,
         [](const Build& b) { return std::make_unique<CymaticsScene>(styles::cymaticsProfile(b.pack), b.loader, b.host); }},
    }};
    return table;
}

const Factory* factoryFor(const std::string& family) {
    for (const auto& [name, factory] : factories()) {
        if (name == family) return &factory;
    }
    return nullptr;
}

}  // namespace

SceneRegistry::SceneRegistry(const ShaderSource& assets, ProgramBinaryCache* cache, SceneHost host)
    : loader_{assets, cache},
      host_(std::move(host)),
      packs_(packs::StylePackRegistry::load([&assets](const std::string& path) { return assets.readAsset(path); })) {
    for (const packs::PackProblem& problem : packs_.problems()) {
        GEODE_LOGW(kTag, "style pack %s: %s %s", problem.where.c_str(), packs::describe(problem.error), problem.detail.c_str());
    }
}

bool SceneRegistry::knows(const std::string& id) const {
    const packs::StylePack* found = packs_.find(id);
    return found != nullptr && factoryFor(found->family) != nullptr;
}

std::vector<std::string> SceneRegistry::availableIds() const {
    std::vector<std::string> out;
    for (const packs::StylePack& style : packs_.styles()) {
        if (factoryFor(style.family) != nullptr) out.push_back(style.id);
    }
    return out;
}

std::unique_ptr<Scene> SceneRegistry::create(const std::string& id, const std::string& quadVert) const {
    const packs::StylePack* found = packs_.find(id);
    if (found == nullptr) return nullptr;
    const Factory* factory = factoryFor(found->family);
    if (factory == nullptr) return nullptr;
    const std::string* labShader = nullptr;
#ifdef GEODE_STYLE_HOT_RELOAD
    if (const auto lab = labShaders_.find(id); lab != labShaders_.end()) labShader = &lab->second;
#endif
    return (*factory)(Build{*found, loader_, host_, quadVert, labShader});
}

#ifdef GEODE_STYLE_HOT_RELOAD
bool SceneRegistry::reload(const std::string& id, const std::optional<std::string>& manifest, const std::optional<std::string>& shader,
                           std::string* error) {
    if (packs_.find(id) == nullptr) {
        if (error) *error = "no Style '" + id + "' ships in the packs";
        return false;
    }
    if (manifest) {
        if (const auto problem = packs_.replace(id, *manifest)) {
            if (error) *error = problem->where + ": " + packs::describe(problem->error) + " " + problem->detail;
            return false;
        }
    }
    if (shader) labShaders_[id] = *shader;
    return true;
}

std::optional<std::string> SceneRegistry::fragment(const packs::StylePack& pack, std::string* error) const {
    const auto lab = labShaders_.find(pack.id);
    return fragmentOf(pack, loader_.assets, lab != labShaders_.end() ? &lab->second : nullptr, error);
}
#endif

}  // namespace geode::viz
