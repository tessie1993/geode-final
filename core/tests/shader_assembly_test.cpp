#include "geode/util/ShaderAssembly.hpp"

#include <gtest/gtest.h>

#include <map>

namespace shader = geode::shader;

namespace {

constexpr const char* kRoot = "styles/x/shader.glsl";

const std::vector<std::string> kKnown = {"lib_a", "lib_b", "lib_c", "lib_loop", "lib_bad"};

// The libraries, keyed by the path the assembler asks for.
const std::map<std::string, std::string> kLibraries = {
    {"shaders/lib_a.glsl", "// a\n//#include lib_b\nfloat a;"},
    {"shaders/lib_b.glsl", "float b;"},
    {"shaders/lib_loop.glsl", "//#include lib_loop"},
    {"shaders/lib_bad.glsl", "float bad;\n//#include lib_nowhere"},
    // lib_c is known but missing.
};

std::optional<std::string> readLibrary(const std::string& path) {
    const auto it = kLibraries.find(path);
    if (it == kLibraries.end()) return std::nullopt;
    return it->second;
}

std::optional<std::string> assemble(const std::string& source, std::string* error = nullptr) {
    return shader::assemble(kRoot, source, kKnown, readLibrary, error);
}

std::string failureOf(const std::string& source) {
    std::string error;
    EXPECT_FALSE(assemble(source, &error).has_value());
    return error;
}

}  // namespace

// ---- include lines -------------------------------------------------------------------------------

TEST(ShaderAssembly, ReadsTheIncludeName) {
    EXPECT_EQ(shader::includeNameOf("//#include lib_a"), "lib_a");
    EXPECT_EQ(shader::includeNameOf(" \t//#include \t lib_a \t"), "lib_a");
    // Every word character, at each end of its ranges.
    EXPECT_EQ(shader::includeNameOf("//#include azAZ09_"), "azAZ09_");
}

TEST(ShaderAssembly, RejectsAnythingElse) {
    EXPECT_FALSE(shader::includeNameOf("").has_value());
    EXPECT_FALSE(shader::includeNameOf("//#include").has_value());
    EXPECT_FALSE(shader::includeNameOf("//#include   ").has_value());
    EXPECT_FALSE(shader::includeNameOf("//#includelib_a").has_value());
    EXPECT_FALSE(shader::includeNameOf("// #include lib_a").has_value());
    EXPECT_FALSE(shader::includeNameOf("x//#include lib_a").has_value());
    EXPECT_FALSE(shader::includeNameOf("//#include lib_a x").has_value());
    EXPECT_FALSE(shader::includeNameOf("//#include lib_a;").has_value());
    // The characters just outside each word range.
    for (const char* outside : {"`", "{", "@", "[", "/", ":"}) {
        EXPECT_FALSE(shader::includeNameOf(std::string("//#include ") + outside).has_value()) << outside;
        EXPECT_FALSE(shader::includeNameOf(std::string("//#include a") + outside).has_value()) << outside;
    }
}

TEST(ShaderAssembly, LibrariesLiveInShaders) { EXPECT_EQ(shader::libraryFile("lib_a"), "shaders/lib_a.glsl"); }

// ---- assembling ----------------------------------------------------------------------------------

TEST(ShaderAssembly, NumbersLinesAfterTheVersion) {
    EXPECT_EQ(assemble("#version 300 es\nvoid main() {}\n"),
              "#version 300 es\n#line 2 0\nvoid main() {}\n\n// geode-sources: 0=styles/x/shader.glsl\n");
}

TEST(ShaderAssembly, FindsTheVersionWhereverItIs) {
    EXPECT_EQ(assemble("// a\n// b\n  # \tversion 300 es\nx"),
              "// a\n// b\n  # \tversion 300 es\n#line 4 0\nx\n// geode-sources: 0=styles/x/shader.glsl\n");
    // Other directives, and text before the '#', are not the version.
    EXPECT_EQ(assemble("#define version 1\nx #version\n#versio\n#\n"),
              "#define version 1\nx #version\n#versio\n#\n\n// geode-sources: 0=styles/x/shader.glsl\n");
}

TEST(ShaderAssembly, NumbersEachLibraryAsItsOwnSource) {
    EXPECT_EQ(assemble("#version 300 es\n//#include lib_b\nvoid main() {}"),
              "#version 300 es\n#line 2 0\n"
              "#line 1 1\nfloat b;\n#line 3 0\n"
              "void main() {}\n// geode-sources: 0=styles/x/shader.glsl 1=shaders/lib_b.glsl\n");
}

TEST(ShaderAssembly, ResolvesNestedIncludesAndReusesNumbers) {
    EXPECT_EQ(assemble("#version 300 es\n//#include lib_a\n//#include lib_b\nvoid main() {}"),
              "#version 300 es\n#line 2 0\n"
              "#line 1 1\n// a\n#line 1 2\nfloat b;\n#line 3 1\nfloat a;\n#line 3 0\n"
              "#line 1 2\nfloat b;\n#line 4 0\n"
              "void main() {}\n// geode-sources: 0=styles/x/shader.glsl 1=shaders/lib_a.glsl 2=shaders/lib_b.glsl\n");
}

TEST(ShaderAssembly, KeepsALibraryEndingInANewlineOnItsOwnLines) {
    const auto withNewline = shader::assemble(kRoot, "//#include lib_b\nx", kKnown,
                                              [](const std::string&) { return std::optional<std::string>("float b;\n"); }, nullptr);
    EXPECT_EQ(withNewline, "#line 1 1\nfloat b;\n\n#line 2 0\nx\n// geode-sources: 0=styles/x/shader.glsl 1=shaders/lib_b.glsl\n");
}

TEST(ShaderAssembly, ReadsWindowsLineEndings) {
    EXPECT_EQ(assemble("#version 300 es\r\n//#include lib_b\r\nx\r\n"),
              "#version 300 es\r\n#line 2 0\n#line 1 1\nfloat b;\n#line 3 0\nx\r\n\n"
              "// geode-sources: 0=styles/x/shader.glsl 1=shaders/lib_b.glsl\n");
}

TEST(ShaderAssembly, NamesTheFileAndLineOfABadInclude) {
    EXPECT_EQ(failureOf("x\n//#include lib_zzz"), "unknown shader include 'lib_zzz' (styles/x/shader.glsl:2)");
    EXPECT_EQ(failureOf("//#include lib_c"), "missing shader include 'lib_c' (styles/x/shader.glsl:1)");
    EXPECT_EQ(failureOf("x\ny\n//#include lib_loop"), "circular shader include 'lib_loop' (shaders/lib_loop.glsl:1)");
    EXPECT_EQ(failureOf("//#include lib_bad"), "unknown shader include 'lib_nowhere' (shaders/lib_bad.glsl:2)");
}

TEST(ShaderAssembly, FailsWithoutAnErrorToFill) { EXPECT_FALSE(assemble("//#include lib_zzz", nullptr).has_value()); }

// ---- the sources comment -------------------------------------------------------------------------

TEST(ShaderAssembly, ReadsTheSourcesBack) {
    const auto assembled = assemble("//#include lib_a\n");
    ASSERT_TRUE(assembled.has_value());
    EXPECT_EQ(shader::sourcesOf(*assembled), (std::vector<std::string>{kRoot, "shaders/lib_a.glsl", "shaders/lib_b.glsl"}));
}

TEST(ShaderAssembly, ReadsTheLastSourcesComment) {
    EXPECT_TRUE(shader::sourcesOf("void main() {}\n").empty());
    EXPECT_EQ(shader::sourcesOf("// geode-sources: 0=old\nx\n// geode-sources:  0=a   1=b\n"), (std::vector<std::string>{"a", "b"}));
    EXPECT_EQ(shader::sourcesOf("x\n// geode-sources: junk 0=a"), (std::vector<std::string>{"a"}));
    EXPECT_EQ(shader::sourcesOf("// geode-sources: 0=a\n1=b"), (std::vector<std::string>{"a"}));
}

// ---- compile logs --------------------------------------------------------------------------------

TEST(ShaderAssembly, NamesTheFileInEachLocation) {
    const std::string assembled = "x\n// geode-sources: 0=styles/x/shader.glsl 1=shaders/lib_a.glsl\n";
    EXPECT_EQ(shader::remapLog("ERROR: 0:12: 'x' : undeclared", assembled), "ERROR: styles/x/shader.glsl:12: 'x' : undeclared");
    EXPECT_EQ(shader::remapLog("1:3(5): error: bad", assembled), "shaders/lib_a.glsl:3(5): error: bad");
    EXPECT_EQ(shader::remapLog("\t0:3: x", assembled), "\tstyles/x/shader.glsl:3: x");
    EXPECT_EQ(shader::remapLog("0:1: a\n1:2: b\n", assembled), "styles/x/shader.glsl:1: a\nshaders/lib_a.glsl:2: b\n");
    // Only the first location on a line: the message may quote another.
    EXPECT_EQ(shader::remapLog("0:1: near 1:2", assembled), "styles/x/shader.glsl:1: near 1:2");
    EXPECT_EQ(shader::remapLog("ERROR 12 things 0:5: y", assembled), "ERROR 12 things styles/x/shader.glsl:5: y");
}

TEST(ShaderAssembly, LeavesAnythingElseAlone) {
    const std::string assembled = "x\n// geode-sources: 0=f0 1=f1\n";
    EXPECT_EQ(shader::remapLog("ERROR: 2:4: x", assembled), "ERROR: 2:4: x");
    EXPECT_EQ(shader::remapLog("x0:12: y", assembled), "x0:12: y");
    EXPECT_EQ(shader::remapLog("ERROR:0:12: y", assembled), "ERROR:0:12: y");
    EXPECT_EQ(shader::remapLog("abc 0:", assembled), "abc 0:");
    EXPECT_EQ(shader::remapLog("abc 0:x", assembled), "abc 0:x");
    EXPECT_EQ(shader::remapLog("abc 0", assembled), "abc 0");
    EXPECT_EQ(shader::remapLog("99999999999999999999999:1: x", assembled), "99999999999999999999999:1: x");
    EXPECT_EQ(shader::remapLog("0:1: x", "no sources here"), "0:1: x");
    EXPECT_EQ(shader::remapLog("", assembled), "");
}

TEST(ShaderAssembly, ReadsSourceNumbersPastNine) {
    std::string assembled = "x\n// geode-sources:";
    for (int i = 0; i < 12; i++) assembled += " " + std::to_string(i) + "=f" + std::to_string(i);
    EXPECT_EQ(shader::remapLog("10:5: x", assembled), "f10:5: x");
    EXPECT_EQ(shader::remapLog("ERROR: 11:2: y", assembled), "ERROR: f11:2: y");
    EXPECT_EQ(shader::remapLog("12:2: z", assembled), "12:2: z");
}
