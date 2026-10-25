#include "geode/viz/StylePacks.hpp"

#include <gtest/gtest.h>

#include <fstream>
#include <map>
#include <set>
#include <sstream>

#include "geode/viz/RenderScale.hpp"
#include "geode/viz/StyleProfiles.hpp"

namespace packs = geode::viz::packs;
namespace styles = geode::viz::styles;

namespace {

// The packs as they ship (GEODE_STYLE_PACK_ROOT/styles/...), read the way the APK reader reads them.
std::optional<std::string> readShipped(const std::string& path) {
    std::ifstream in(std::string(GEODE_STYLE_PACK_ROOT) + "/" + path, std::ios::binary);
    if (!in) return std::nullopt;
    std::ostringstream text;
    text << in.rdbuf();
    return text.str();
}

packs::StylePackRegistry shipped() { return packs::StylePackRegistry::load(readShipped); }

std::string problemsOf(const packs::StylePackRegistry& registry) {
    std::string out;
    for (const auto& p : registry.problems()) out += p.where + ": " + packs::describe(p.error) + " " + p.detail + "\n";
    return out;
}

constexpr const char* kFamilies = R"({
  "version": 1,
  "families": [
    {"name": "shader", "parameters": []},
    {"name": "plate", "parameters": [
      {"key": "mode", "type": "int", "min": 0, "max": 4},
      {"key": "gain", "type": "float", "min": 0, "max": 2, "default": 1.25},
      {"key": "cap", "type": "int", "min": 1, "max": 8, "default": 8}
    ]}
  ]
})";

std::vector<packs::FamilySchema> families() {
    std::vector<packs::PackProblem> problems;
    auto parsed = packs::parseFamilies(kFamilies, problems);
    EXPECT_TRUE(parsed.has_value());
    EXPECT_TRUE(problems.empty());
    return parsed.value_or(std::vector<packs::FamilySchema>{});
}

std::optional<packs::StylePack> manifest(const std::string& folder, const std::string& text, std::vector<packs::PackProblem>& problems) {
    return packs::parseManifest(folder, text, families(), problems);
}

packs::PackError onlyError(const std::vector<packs::PackProblem>& problems) {
    EXPECT_EQ(problems.size(), 1u);
    return problems.empty() ? packs::PackError::MissingFile : problems.front().error;
}

// A registry over in-memory files, keyed by asset path.
packs::StylePackRegistry registryOf(std::map<std::string, std::string> files) {
    return packs::StylePackRegistry::load([files](const std::string& path) -> std::optional<std::string> {
        const auto it = files.find(path);
        if (it == files.end()) return std::nullopt;
        return it->second;
    });
}

}  // namespace

// ---- paths ---------------------------------------------------------------------------------------

TEST(StylePackPaths, ManifestSitsInTheStyleFolder) {
    EXPECT_EQ(packs::manifestPath("kifs"), "styles/kifs/manifest.json");
    EXPECT_EQ(packs::packFilePath("kifs", "shader.glsl"), "styles/kifs/shader.glsl");
    EXPECT_EQ(packs::stylesPath(packs::kIndexFile), "styles/index.json");
    EXPECT_EQ(packs::stylesPath(packs::kFamiliesFile), "styles/families.json");
}

// ---- manifests -----------------------------------------------------------------------------------

TEST(StyleManifest, ReadsEveryField) {
    std::vector<packs::PackProblem> problems;
    const auto pack = manifest("orb", R"({"version": 1, "id": "orb", "label": "Orb", "family": "shader",
        "shader": "shader.glsl", "renderScale": 0.75, "detail": "march",
        "extras": [{"name": "density", "min": 0, "max": 2, "default": 0.5}]})", problems);
    ASSERT_TRUE(pack.has_value()) << (problems.empty() ? "" : problems.front().detail);
    EXPECT_EQ(pack->id, "orb");
    EXPECT_EQ(pack->label, "Orb");
    EXPECT_EQ(pack->family, "shader");
    EXPECT_EQ(pack->shaderPath, "styles/orb/shader.glsl");
    EXPECT_FLOAT_EQ(pack->renderScale, 0.75f);
    EXPECT_EQ(pack->detail, packs::DetailUse::March);
    ASSERT_EQ(pack->extras.size(), 1u);
    EXPECT_EQ(pack->extras[0].name, "density");
    EXPECT_FLOAT_EQ(pack->extras[0].min, 0.0f);
    EXPECT_FLOAT_EQ(pack->extras[0].max, 2.0f);
    EXPECT_FLOAT_EQ(pack->extras[0].defaultValue, 0.5f);
    EXPECT_TRUE(problems.empty());
}

TEST(StyleManifest, OptionalFieldsDefault) {
    std::vector<packs::PackProblem> problems;
    const auto pack = manifest("plain", R"({"version": 1, "id": "plain", "label": "Plain", "family": "shader"})", problems);
    ASSERT_TRUE(pack.has_value());
    EXPECT_EQ(pack->shaderPath, "");
    EXPECT_FLOAT_EQ(pack->renderScale, geode::viz::scale::kDefaultStyleRenderScale);
    EXPECT_EQ(pack->detail, packs::DetailUse::None);
    EXPECT_TRUE(pack->extras.empty());
    EXPECT_TRUE(pack->parameters.empty());
}

TEST(StyleManifest, RenderScaleIsClamped) {
    std::vector<packs::PackProblem> problems;
    const auto pack = manifest("s", R"({"version": 1, "id": "s", "label": "S", "family": "shader", "renderScale": 0.1})", problems);
    ASSERT_TRUE(pack.has_value());
    EXPECT_FLOAT_EQ(pack->renderScale, geode::viz::scale::kMinStyleRenderScale);
}

TEST(StyleManifest, ParametersResolveAgainstTheFamily) {
    std::vector<packs::PackProblem> problems;
    const auto pack = manifest("p", R"({"version": 1, "id": "p", "label": "P", "family": "plate",
        "parameters": {"mode": 2.6, "gain": 9}})", problems);
    ASSERT_TRUE(pack.has_value());
    EXPECT_FLOAT_EQ(pack->parameter("mode"), 3.0f);  // an int parameter is rounded
    EXPECT_EQ(pack->intParameter("mode"), 3);
    EXPECT_FLOAT_EQ(pack->parameter("gain"), 2.0f);  // clamped to the family's range
    EXPECT_EQ(pack->intParameter("cap"), 8);          // the family default
    EXPECT_FLOAT_EQ(pack->parameter("unknown"), 0.0f);
}

TEST(StyleManifest, ADefaultlessParameterMustBeSet) {
    std::vector<packs::PackProblem> problems;
    EXPECT_FALSE(manifest("p", R"({"version": 1, "id": "p", "label": "P", "family": "plate"})", problems));
    EXPECT_EQ(onlyError(problems), packs::PackError::MissingField);
}

TEST(StyleManifest, RejectsAParameterTheFamilyLacks) {
    std::vector<packs::PackProblem> problems;
    EXPECT_FALSE(manifest("p", R"({"version": 1, "id": "p", "label": "P", "family": "plate",
        "parameters": {"mode": 1, "hue": 0.5}})", problems));
    EXPECT_EQ(onlyError(problems), packs::PackError::UnknownParameter);
}

TEST(StyleManifest, RejectsANonNumericParameter) {
    std::vector<packs::PackProblem> problems;
    EXPECT_FALSE(manifest("p", R"({"version": 1, "id": "p", "label": "P", "family": "plate", "parameters": {"mode": "two"}})", problems));
    EXPECT_EQ(onlyError(problems), packs::PackError::BadValue);
}

TEST(StyleManifest, RejectsAnUnknownFamily) {
    std::vector<packs::PackProblem> problems;
    EXPECT_FALSE(manifest("x", R"({"version": 1, "id": "x", "label": "X", "family": "hologram"})", problems));
    EXPECT_EQ(onlyError(problems), packs::PackError::UnknownFamily);
}

TEST(StyleManifest, RejectsAnIdThatIsNotItsFolder) {
    std::vector<packs::PackProblem> problems;
    EXPECT_FALSE(manifest("x", R"({"version": 1, "id": "y", "label": "Y", "family": "shader"})", problems));
    EXPECT_EQ(onlyError(problems), packs::PackError::IdMismatch);
}

TEST(StyleManifest, RejectsAnotherVersion) {
    std::vector<packs::PackProblem> problems;
    EXPECT_FALSE(manifest("x", R"({"version": 2, "id": "x", "label": "X", "family": "shader"})", problems));
    EXPECT_EQ(onlyError(problems), packs::PackError::WrongVersion);
}

TEST(StyleManifest, RejectsText) {
    std::vector<packs::PackProblem> problems;
    EXPECT_FALSE(manifest("x", "not json", problems));
    EXPECT_EQ(onlyError(problems), packs::PackError::NotJson);
}

TEST(StyleManifest, RejectsMissingFields) {
    std::vector<packs::PackProblem> problems;
    EXPECT_FALSE(manifest("x", R"({"version": 1, "id": "x", "family": "shader"})", problems));
    EXPECT_EQ(onlyError(problems), packs::PackError::MissingField);
}

TEST(StyleManifest, RejectsBadOptionalFields) {
    const char* bad[] = {
        R"({"version": 1, "id": "x", "label": "X", "family": "shader", "shader": 3})",
        R"({"version": 1, "id": "x", "label": "X", "family": "shader", "renderScale": "half"})",
        R"({"version": 1, "id": "x", "label": "X", "family": "shader", "detail": "max"})",
        R"({"version": 1, "id": "x", "label": "X", "family": "shader", "extras": [{"name": "a", "min": 1, "max": 0, "default": 0.5}]})",
    };
    for (const char* text : bad) {
        std::vector<packs::PackProblem> problems;
        EXPECT_FALSE(manifest("x", text, problems)) << text;
        EXPECT_EQ(onlyError(problems), packs::PackError::BadValue) << text;
    }
}

TEST(StyleManifest, RejectsAnIncompleteExtra) {
    std::vector<packs::PackProblem> problems;
    EXPECT_FALSE(manifest("x", R"({"version": 1, "id": "x", "label": "X", "family": "shader", "extras": [{"name": "a"}]})", problems));
    EXPECT_EQ(onlyError(problems), packs::PackError::MissingField);
}

// ---- families and the index ----------------------------------------------------------------------

TEST(StyleFamilies, ReadsTypesRangesAndDefaults) {
    const auto parsed = families();
    ASSERT_EQ(parsed.size(), 2u);
    const packs::ParameterSpec* mode = parsed[1].find("mode");
    ASSERT_NE(mode, nullptr);
    EXPECT_EQ(mode->type, packs::ParameterType::Int);
    EXPECT_FALSE(mode->defaultValue.has_value());
    const packs::ParameterSpec* gain = parsed[1].find("gain");
    ASSERT_NE(gain, nullptr);
    EXPECT_EQ(gain->type, packs::ParameterType::Float);
    EXPECT_FLOAT_EQ(gain->max, 2.0f);
    EXPECT_FLOAT_EQ(*gain->defaultValue, 1.25f);
    EXPECT_EQ(parsed[1].find("hue"), nullptr);
}

TEST(StyleFamilies, RejectsAnUnknownTypeAndAnUpsideDownRange) {
    std::vector<packs::PackProblem> problems;
    EXPECT_FALSE(packs::parseFamilies(
        R"({"version": 1, "families": [{"name": "f", "parameters": [{"key": "k", "type": "bool", "min": 0, "max": 1}]}]})", problems));
    EXPECT_EQ(onlyError(problems), packs::PackError::BadValue);
    problems.clear();
    EXPECT_FALSE(packs::parseFamilies(
        R"({"version": 1, "families": [{"name": "f", "parameters": [{"key": "k", "type": "int", "min": 2, "max": 1}]}]})", problems));
    EXPECT_EQ(onlyError(problems), packs::PackError::BadValue);
}

TEST(StyleIndex, SkipsDuplicates) {
    std::vector<packs::PackProblem> problems;
    const auto ids = packs::parseIndex(R"({"version": 1, "styles": ["a", "b", "a"]})", problems);
    ASSERT_TRUE(ids.has_value());
    EXPECT_EQ(*ids, (std::vector<std::string>{"a", "b"}));
    EXPECT_EQ(onlyError(problems), packs::PackError::DuplicateId);
}

TEST(StylePackRegistry, KeepsIndexOrderAndLeavesOutBrokenPacks) {
    const auto registry = registryOf({
        {"styles/families.json", kFamilies},
        {"styles/index.json", R"({"version": 1, "styles": ["b", "gone", "a", "broken"]})"},
        {"styles/a/manifest.json", R"({"version": 1, "id": "a", "label": "A", "family": "shader"})"},
        {"styles/b/manifest.json", R"({"version": 1, "id": "b", "label": "B", "family": "plate", "parameters": {"mode": 1}})"},
        {"styles/broken/manifest.json", R"({"version": 1, "id": "broken", "label": "Broken", "family": "nope"})"},
    });
    EXPECT_EQ(registry.ids(), (std::vector<std::string>{"b", "a"}));
    ASSERT_NE(registry.find("b"), nullptr);
    EXPECT_EQ(registry.find("b")->intParameter("mode"), 1);
    EXPECT_EQ(registry.find("gone"), nullptr);
    ASSERT_NE(registry.family("plate"), nullptr);
    EXPECT_EQ(registry.family("nope"), nullptr);
    ASSERT_EQ(registry.problems().size(), 2u);
    EXPECT_EQ(registry.problems()[0].error, packs::PackError::MissingFile);
    EXPECT_EQ(registry.problems()[0].where, "gone");
    EXPECT_EQ(registry.problems()[1].error, packs::PackError::UnknownFamily);
}

TEST(StylePackRegistry, IsEmptyWithoutItsIndex) {
    const auto registry = registryOf({{"styles/families.json", kFamilies}});
    EXPECT_TRUE(registry.styles().empty());
    ASSERT_EQ(registry.problems().size(), 1u);
    EXPECT_EQ(registry.problems()[0].error, packs::PackError::MissingFile);
}

TEST(StylePackError, EveryErrorIsDescribed) {
    for (auto error : {packs::PackError::MissingFile, packs::PackError::NotJson, packs::PackError::WrongVersion,
                       packs::PackError::MissingField, packs::PackError::BadValue, packs::PackError::IdMismatch,
                       packs::PackError::DuplicateId, packs::PackError::UnknownFamily, packs::PackError::UnknownParameter}) {
        EXPECT_STRNE(packs::describe(error), "unknown error");
    }
}

// ---- profiles ------------------------------------------------------------------------------------

TEST(StyleProfiles, FillEveryFieldFromThePack) {
    packs::StylePack pack;
    pack.id = "drum";
    pack.parameters = {{"shaderStyle", 2.0f}, {"geometryOverride", -1.0f}, {"scale", 0.92f}, {"fill", 1.15f},
                       {"line", 0.88f},       {"glow", 0.85f},             {"iridescence", 0.45f}, {"caustic", 0.9f},
                       {"flow", 1.0f},        {"swirl", 1.0f},             {"hueOffset", -0.04f},  {"hueSpan", 0.7f},
                       {"modeCap", 2.0f}};
    const styles::CymaticsStyle style = styles::cymaticsProfile(pack);
    EXPECT_EQ(style.id, "drum");
    EXPECT_EQ(style.shaderStyle, 2);
    EXPECT_EQ(style.geometryOverride, -1);
    EXPECT_FLOAT_EQ(style.scale, 0.92f);
    EXPECT_FLOAT_EQ(style.fill, 1.15f);
    EXPECT_FLOAT_EQ(style.hueOffset, -0.04f);
    EXPECT_EQ(style.modeCap, 2);
}

// ---- the packs that ship -------------------------------------------------------------------------

TEST(ShippedStylePacks, EveryPackReads) {
    const auto registry = shipped();
    EXPECT_TRUE(registry.problems().empty()) << problemsOf(registry);
    EXPECT_FALSE(registry.styles().empty());
}

TEST(ShippedStylePacks, EveryFamilyHasARendererProfileMatchingItsParameters) {
    const auto registry = shipped();
    const std::set<std::string> renderers = {
        std::string(styles::family::kShader),   std::string(styles::family::kLife),  std::string(styles::family::kMycelium),
        std::string(styles::family::kAcid),     std::string(styles::family::kMilkdrop), std::string(styles::family::kFluid),
        std::string(styles::family::kCurlFlow), std::string(styles::family::kWater), std::string(styles::family::kCymatics)};
    ASSERT_FALSE(registry.families().empty());
    for (const packs::FamilySchema& family : registry.families()) {
        EXPECT_TRUE(renderers.count(family.name)) << family.name << " has no renderer";
        std::vector<std::string> declared;
        for (const auto& spec : family.parameters) declared.push_back(spec.key);
        EXPECT_EQ(styles::profileKeys(family.name), declared) << family.name;
    }
}

TEST(ShippedStylePacks, EveryShaderStyleNamesItsShader) {
    const auto registry = shipped();
    for (const packs::StylePack& pack : registry.styles()) {
        if (pack.family != styles::family::kShader) continue;
        EXPECT_FALSE(pack.shaderPath.empty()) << pack.id;
        EXPECT_TRUE(readShipped(pack.shaderPath).has_value()) << pack.shaderPath;
    }
}
