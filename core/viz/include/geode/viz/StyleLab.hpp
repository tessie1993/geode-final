#pragma once
#include <cstdint>
#include <map>
#include <optional>
#include <string>

namespace geode::viz::lab {

// The Style lab, in debug builds only (docs/style-packs.md, "Authoring loop"): a folder on the device,
// `<lab>/<id>/`, that an author pushes a Style pack's manifest and shader into with adb. The renderer
// looks at it every kPollSeconds and reloads a Style whose files changed, without a restart. This is
// the part with no files and no GL in it; the renderer's side is RendererStyleLab.cpp, and the
// registry's is StylePackRegistry::replace (StyleLab.cpp). A release build links none of it.

// How often the renderer looks at the lab folder: an edit shows within about a second.
inline constexpr double kPollSeconds = 0.25;

// One file as the file system describes it, enough to tell that it changed.
struct FileStamp {
    int64_t modifiedNs = 0;
    int64_t size = 0;

    bool operator==(const FileStamp&) const = default;
};

// What one Style's lab folder holds now: nullopt for a file that is not there.
struct FolderStamp {
    std::optional<FileStamp> manifest;
    std::optional<FileStamp> shader;

    bool operator==(const FolderStamp&) const = default;
    bool empty() const { return !manifest && !shader; }
};

class ReloadWatch {
public:
    // True on the first call and then once every kPollSeconds of `nowSeconds`; a clock that went
    // backwards counts as due.
    bool due(double nowSeconds);
    // True when `id`'s folder holds a file and differs from what it held when last asked.
    bool changed(const std::string& id, const FolderStamp& now);

private:
    std::optional<double> lastPoll_;
    std::map<std::string, FolderStamp> seen_;
};

}  // namespace geode::viz::lab
