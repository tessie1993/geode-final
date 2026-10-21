#include "geode/viz/SceneRegistry.hpp"

#include <array>
#include <functional>
#include <string_view>
#include <utility>

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
};

using Factory = std::function<std::unique_ptr<Scene>(const Build&)>;

std::unique_ptr<Scene> buildShader(const Build& b) {
    std::string error;
    const auto fragment = b.loader.assets.loadAsset(b.pack.shaderPath, &error);
    if (!fragment) {
        b.host.onShaderError(error);
        return nullptr;
    }
    return std::make_unique<ShaderScene>(b.pack.id, b.quadVert, *fragment, b.loader.cache, b.host);
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
    return (*factory)(Build{*found, loader_, host_, quadVert});
}

}  // namespace geode::viz
