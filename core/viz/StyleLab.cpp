#include "geode/viz/StyleLab.hpp"

#include <algorithm>
#include <vector>

#include "geode/viz/StylePacks.hpp"

namespace geode::viz {

namespace lab {

bool ReloadWatch::due(double nowSeconds) {
    if (lastPoll_ && nowSeconds >= *lastPoll_ && nowSeconds - *lastPoll_ < kPollSeconds) return false;
    lastPoll_ = nowSeconds;
    return true;
}

bool ReloadWatch::changed(const std::string& id, const FolderStamp& now) {
    FolderStamp& last = seen_[id];
    if (now == last) return false;
    last = now;
    return !now.empty();
}

}  // namespace lab

namespace packs {

// Here rather than in StylePacks.cpp so that only a build with the Style lab links it.
std::optional<PackProblem> StylePackRegistry::replace(const std::string& id, const std::string& manifestText) {
    const auto at = std::find_if(styles_.begin(), styles_.end(), [&](const StylePack& pack) { return pack.id == id; });
    if (at == styles_.end()) return PackProblem{PackError::MissingFile, id, "no Style with this id ships in the packs"};
    std::vector<PackProblem> problems;
    auto pack = parseManifest(id, manifestText, families_, problems);
    // parseManifest reports every manifest it turns down.
    if (!pack) return problems.back();
    // In place: a pointer find() handed out stays valid.
    *at = std::move(*pack);
    return std::nullopt;
}

}  // namespace packs

}  // namespace geode::viz
