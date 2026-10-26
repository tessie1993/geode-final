#include <gtest/gtest.h>

#include <fstream>
#include <optional>
#include <regex>
#include <set>
#include <sstream>
#include <string>
#include <utility>
#include <vector>

#include "geode/viz/FeatureBus.hpp"
#include "geode/viz/StylePacks.hpp"

// No dead features and no dead uniforms (#294): the uniforms ShaderScene uploads from the feature bus are
// exactly what lib_scene_features.glsl declares, at least one shipped Style reads each one, and a Style
// that includes the library reads something from it.

namespace geode::viz {
namespace {

std::optional<std::string> readFile(const std::string& path) {
    std::ifstream in(path, std::ios::binary);
    if (!in) return std::nullopt;
    std::ostringstream text;
    text << in.rdbuf();
    return text.str();
}

std::optional<std::string> readShipped(const std::string& path) {
    return readFile(std::string(GEODE_STYLE_PACK_ROOT) + "/" + path);
}

// The code without its comments, scanned by hand: a regular expression over a whole shader recurses
// once per character in libstdc++.
std::string withoutComments(const std::string& glsl) {
    std::string out;
    out.reserve(glsl.size());
    size_t i = 0;
    while (i < glsl.size()) {
        if (glsl.compare(i, 2, "//") == 0) {
            const size_t end = glsl.find('\n', i);
            i = end == std::string::npos ? glsl.size() : end;
        } else if (glsl.compare(i, 2, "/*") == 0) {
            const size_t end = glsl.find("*/", i + 2);
            i = end == std::string::npos ? glsl.size() : end + 2;
            out += ' ';
        } else {
            out += glsl[i++];
        }
    }
    return out;
}

bool reads(const std::string& code, const std::string& name) {
    return std::regex_search(code, std::regex("\\b" + name + "\\b"));
}

// Whether a line is exactly `//#include lib_scene_features`, as ShaderAssembly reads an include.
bool includesFeatures(const std::string& glsl) {
    std::istringstream lines(glsl);
    std::string line;
    while (std::getline(lines, line)) {
        if (!line.empty() && line.back() == '\r') line.pop_back();
        const size_t start = line.find_first_not_of(" \t");
        if (start == std::string::npos || line.compare(start, 10, "//#include") != 0) continue;
        std::istringstream words(line.substr(start + 10));
        std::string name;
        std::string rest;
        if (line.size() > start + 10 && (line[start + 10] == ' ' || line[start + 10] == '\t') && (words >> name) &&
            !(words >> rest) && name == "lib_scene_features") {
            return true;
        }
    }
    return false;
}

std::set<std::string> declaredUniforms(const std::string& glsl) {
    static const std::regex kUniform(R"(uniform\s+(?:(?:highp|mediump|lowp)\s+)?\w+\s+(\w+)\s*;)");
    const std::string code = withoutComments(glsl);
    std::set<std::string> out;
    for (auto it = std::sregex_iterator(code.begin(), code.end(), kUniform); it != std::sregex_iterator(); ++it)
        out.insert((*it)[1]);
    return out;
}

// The shipped shader Styles' sources, by id.
std::vector<std::pair<std::string, std::string>> shaderStyles() {
    const auto registry = packs::StylePackRegistry::load(readShipped);
    std::vector<std::pair<std::string, std::string>> out;
    for (const auto& pack : registry.styles()) {
        if (pack.shaderPath.empty()) continue;
        const auto source = readShipped(pack.shaderPath);
        EXPECT_TRUE(source.has_value()) << pack.shaderPath;
        if (source) out.emplace_back(pack.id, *source);
    }
    return out;
}

TEST(FeatureUniforms, TheLibraryDeclaresExactlyTheUploadedUniforms) {
    const auto lib = readFile(std::string(GEODE_SHADER_ROOT) + "/lib_scene_features.glsl");
    ASSERT_TRUE(lib.has_value());
    const std::set<std::string> declared = declaredUniforms(*lib);
    const std::set<std::string> uploaded(kFeatureUniforms.begin(), kFeatureUniforms.end());
    EXPECT_EQ(uploaded.size(), kFeatureUniforms.size()) << "a uniform is uploaded twice";
    for (const auto& name : uploaded)
        EXPECT_TRUE(declared.count(name)) << name << " is uploaded but lib_scene_features does not declare it";
    for (const auto& name : declared)
        EXPECT_TRUE(uploaded.count(name)) << name << " is declared but ShaderScene never uploads it";
}

TEST(FeatureUniforms, EveryUniformIsReadByAShippedStyle) {
    const auto styles = shaderStyles();
    ASSERT_FALSE(styles.empty());
    for (const char* name : kFeatureUniforms) {
        bool read = false;
        for (const auto& [id, source] : styles)
            read = read || (includesFeatures(source) && reads(withoutComments(source), name));
        EXPECT_TRUE(read) << name << " is exposed to every shader Style and no Style reads it";
    }
}

TEST(FeatureUniforms, AStyleThatIncludesTheLibraryReadsFromIt) {
    int including = 0;
    for (const auto& [id, source] : shaderStyles()) {
        if (!includesFeatures(source)) continue;
        ++including;
        const std::string code = withoutComments(source);
        bool readsAny = false;
        for (const char* name : kFeatureUniforms) readsAny = readsAny || reads(code, name);
        EXPECT_TRUE(readsAny) << id << " includes lib_scene_features and reads none of it";
    }
    EXPECT_GT(including, 0);
}

TEST(FeatureUniforms, TheChecksSeeWhatTheyCheck) {
    EXPECT_TRUE(includesFeatures("//#include lib_style\n//#include lib_scene_features\nvoid main() {}"));
    EXPECT_FALSE(includesFeatures("// //#include lib_scene_features\n"));
    EXPECT_FALSE(includesFeatures("//#include lib_scene_featuresx\n"));
    EXPECT_TRUE(reads(withoutComments("float k = uKick;"), "uKick"));
    EXPECT_FALSE(reads(withoutComments("float k = uKickSmooth; // uKick"), "uKick"));
    EXPECT_FALSE(reads(withoutComments("/* uKick */ float k = 0.0;"), "uKick"));
    EXPECT_EQ(declaredUniforms("uniform float uA;\n// uniform float uB;\nuniform highp float uC;\n"),
              (std::set<std::string>{"uA", "uC"}));
}

}  // namespace
}  // namespace geode::viz
