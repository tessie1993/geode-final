#include "geode/viz/StyleLab.hpp"

#include <gtest/gtest.h>

#include <map>

#include "geode/viz/StylePacks.hpp"

namespace lab = geode::viz::lab;
namespace packs = geode::viz::packs;

namespace {

lab::FolderStamp stamp(std::optional<lab::FileStamp> manifest, std::optional<lab::FileStamp> shader) {
    lab::FolderStamp out;
    out.manifest = manifest;
    out.shader = shader;
    return out;
}

const std::map<std::string, std::string> kFiles = {
    {"styles/families.json", R"({"version": 1, "families": [
        {"name": "shader", "parameters": []},
        {"name": "plate", "parameters": [{"key": "mode", "type": "int", "min": 0, "max": 4}]}
    ]})"},
    {"styles/index.json", R"({"version": 1, "styles": ["kifs", "plate_one"]})"},
    {"styles/kifs/manifest.json", R"({"version": 1, "id": "kifs", "label": "Kifs", "family": "shader", "shader": "shader.glsl"})"},
    {"styles/plate_one/manifest.json", R"({"version": 1, "id": "plate_one", "label": "Plate", "family": "plate", "parameters": {"mode": 2}})"},
};

packs::StylePackRegistry registry() {
    return packs::StylePackRegistry::load([](const std::string& path) -> std::optional<std::string> {
        const auto it = kFiles.find(path);
        if (it == kFiles.end()) return std::nullopt;
        return it->second;
    });
}

}  // namespace

// ---- when to look --------------------------------------------------------------------------------

TEST(StyleLab, LooksOnceEveryPollInterval) {
    lab::ReloadWatch watch;
    EXPECT_TRUE(watch.due(10.0));
    EXPECT_FALSE(watch.due(10.0));
    EXPECT_FALSE(watch.due(10.1));
    EXPECT_TRUE(watch.due(10.0 + lab::kPollSeconds));
    EXPECT_FALSE(watch.due(10.4));
    EXPECT_TRUE(watch.due(11.0));
}

TEST(StyleLab, LooksAgainWhenTheClockGoesBack) {
    lab::ReloadWatch watch;
    EXPECT_TRUE(watch.due(10.0));
    EXPECT_TRUE(watch.due(9.9));
    EXPECT_FALSE(watch.due(10.0));
}

// ---- what changed --------------------------------------------------------------------------------

TEST(StyleLab, ReloadsWhenAFileArrivesOrChanges) {
    lab::ReloadWatch watch;
    const lab::FileStamp manifest{100, 40};
    const lab::FileStamp shader{200, 900};
    EXPECT_FALSE(watch.changed("kifs", stamp(std::nullopt, std::nullopt)));
    EXPECT_TRUE(watch.changed("kifs", stamp(manifest, shader)));
    EXPECT_FALSE(watch.changed("kifs", stamp(manifest, shader)));
    EXPECT_TRUE(watch.changed("kifs", stamp(manifest, lab::FileStamp{201, 900})));
    EXPECT_TRUE(watch.changed("kifs", stamp(manifest, lab::FileStamp{201, 901})));
    EXPECT_TRUE(watch.changed("kifs", stamp(lab::FileStamp{101, 40}, lab::FileStamp{201, 901})));
    EXPECT_TRUE(watch.changed("kifs", stamp(std::nullopt, lab::FileStamp{201, 901})));
    EXPECT_TRUE(watch.changed("kifs", stamp(manifest, std::nullopt)));
}

TEST(StyleLab, AnEmptiedFolderReloadsNothingUntilFilesReturn) {
    lab::ReloadWatch watch;
    const lab::FileStamp shader{200, 900};
    EXPECT_TRUE(watch.changed("kifs", stamp(std::nullopt, shader)));
    EXPECT_FALSE(watch.changed("kifs", stamp(std::nullopt, std::nullopt)));
    EXPECT_FALSE(watch.changed("kifs", stamp(std::nullopt, std::nullopt)));
    EXPECT_TRUE(watch.changed("kifs", stamp(std::nullopt, shader)));
}

TEST(StyleLab, WatchesEachStyleOnItsOwn) {
    lab::ReloadWatch watch;
    const lab::FileStamp manifest{100, 40};
    EXPECT_TRUE(watch.changed("kifs", stamp(manifest, std::nullopt)));
    EXPECT_TRUE(watch.changed("julia", stamp(manifest, std::nullopt)));
    EXPECT_FALSE(watch.changed("kifs", stamp(manifest, std::nullopt)));
}

TEST(StyleLab, AFolderWithEitherFileIsNotEmpty) {
    EXPECT_TRUE(stamp(std::nullopt, std::nullopt).empty());
    EXPECT_FALSE(stamp(lab::FileStamp{}, std::nullopt).empty());
    EXPECT_FALSE(stamp(std::nullopt, lab::FileStamp{}).empty());
}

// ---- replacing a pack ----------------------------------------------------------------------------

TEST(StyleLab, ReplacesAStyleInPlace) {
    auto packsNow = registry();
    ASSERT_TRUE(packsNow.problems().empty());
    const packs::StylePack* before = packsNow.find("kifs");
    ASSERT_NE(before, nullptr);
    EXPECT_FALSE(packsNow.replace("kifs", R"({"version": 1, "id": "kifs", "label": "Kifs II", "family": "shader",
        "shader": "lab.glsl", "renderScale": 0.5, "detail": "march"})").has_value());
    const packs::StylePack* after = packsNow.find("kifs");
    EXPECT_EQ(after, before);
    EXPECT_EQ(after->label, "Kifs II");
    EXPECT_EQ(after->shaderPath, "styles/kifs/lab.glsl");
    EXPECT_FLOAT_EQ(after->renderScale, 0.5f);
    EXPECT_EQ(after->detail, packs::DetailUse::March);
    EXPECT_EQ(packsNow.ids(), (std::vector<std::string>{"kifs", "plate_one"}));
}

TEST(StyleLab, ReplacesAFamilysParameters) {
    auto packsNow = registry();
    EXPECT_FALSE(packsNow.replace("plate_one", R"({"version": 1, "id": "plate_one", "label": "Plate", "family": "plate",
        "parameters": {"mode": 3}})").has_value());
    EXPECT_EQ(packsNow.find("plate_one")->intParameter("mode"), 3);
    EXPECT_EQ(packsNow.find("kifs")->label, "Kifs");
}

TEST(StyleLab, TurnsDownAnUnknownStyle) {
    auto packsNow = registry();
    const auto problem = packsNow.replace("nope", R"({"version": 1, "id": "nope", "label": "N", "family": "shader"})");
    ASSERT_TRUE(problem.has_value());
    EXPECT_EQ(problem->error, packs::PackError::MissingFile);
    EXPECT_EQ(problem->where, "nope");
    EXPECT_EQ(packsNow.find("nope"), nullptr);
}

TEST(StyleLab, TurnsDownABadManifestAndKeepsThePack) {
    auto packsNow = registry();
    const auto mismatch = packsNow.replace("kifs", R"({"version": 1, "id": "julia", "label": "J", "family": "shader"})");
    ASSERT_TRUE(mismatch.has_value());
    EXPECT_EQ(mismatch->error, packs::PackError::IdMismatch);
    const auto notJson = packsNow.replace("kifs", "{ not json");
    ASSERT_TRUE(notJson.has_value());
    EXPECT_EQ(notJson->error, packs::PackError::NotJson);
    const auto badParameter =
        packsNow.replace("plate_one", R"({"version": 1, "id": "plate_one", "label": "P", "family": "plate", "parameters": {"gain": 1}})");
    ASSERT_TRUE(badParameter.has_value());
    EXPECT_EQ(badParameter->error, packs::PackError::UnknownParameter);
    EXPECT_EQ(packsNow.find("kifs")->label, "Kifs");
    EXPECT_EQ(packsNow.find("kifs")->shaderPath, "styles/kifs/shader.glsl");
    EXPECT_EQ(packsNow.find("plate_one")->intParameter("mode"), 2);
}
