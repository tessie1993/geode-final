#pragma once
#include <memory>
#include <string>
#include <vector>

#include "geode/render/ProgramBinaryCache.hpp"
#include "geode/render/ShaderSource.hpp"
#include "geode/viz/Scene.hpp"
#include "geode/viz/StylePacks.hpp"
#include "geode/viz/scenes/ProgramLoader.hpp"

namespace geode::viz {

// The Styles native code can draw: every Style pack in the APK (styles/index.json and each
// styles/<id>/manifest.json, read once at construction) whose family has a renderer here. The Kotlin
// side builds its list from the same packs (StylePacks.kt), so neither side keeps a list of its own.
class SceneRegistry {
public:
    SceneRegistry(const ShaderSource& assets, ProgramBinaryCache* cache, SceneHost host);

    bool knows(const std::string& id) const;
    // Every drawable id, in the packs' index order.
    std::vector<std::string> availableIds() const;
    // The Style's pack; nullptr for an id no pack declares.
    const packs::StylePack* pack(const std::string& id) const { return packs_.find(id); }
    // Builds an uninitialised scene; nullptr for an id native code cannot draw.
    std::unique_ptr<Scene> create(const std::string& id, const std::string& quadVert) const;

private:
    ProgramLoader loader_;
    SceneHost host_;
    packs::StylePackRegistry packs_;
};

}  // namespace geode::viz
