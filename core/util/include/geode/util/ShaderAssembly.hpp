#pragma once
#include <functional>
#include <optional>
#include <string>
#include <string_view>
#include <vector>

namespace geode::shader {

// A shader is assembled from the file an author wrote and the shared libraries (shaders/lib_*.glsl)
// its `//#include <name>` lines pull in. The assembled GLSL carries `#line` directives that number
// every file as a source string of its own (0 the file itself, then each library in the order it is
// first included) and ends with a comment naming those files by number, so a driver's compile log can
// be turned back into the author's file and line (remapLog). tools/glslcheck/check.mjs assembles
// shaders the same way, for the render check and for glslang.

// The comment the assembled source ends with: "// geode-sources: 0=<file> 1=<library> ...".
inline constexpr std::string_view kSourcesTag = "// geode-sources:";

// The library name on an `//#include <name>` line (^[ \t]*//#include[ \t]+(\w+)[ \t]*$), else nullopt.
std::optional<std::string> includeNameOf(std::string_view line);

// The file a library is read from and reported as: "shaders/<name>.glsl".
std::string libraryFile(std::string_view name);

// Reads a file by path; nullopt when it does not exist.
using FileReader = std::function<std::optional<std::string>(const std::string& path)>;

// `source`, read from `file`, with every include line replaced by the library it names (recursively),
// `#line` directives around each library and after the `#version` line, and the sources comment last.
// nullopt, with the reason and the file and line of the include in `error`, for an include not in
// `known`, a library that includes itself through others, or a library `read` cannot find.
std::optional<std::string> assemble(const std::string& file, const std::string& source, const std::vector<std::string>& known,
                                    const FileReader& read, std::string* error);

// The files the sources comment of `assembled` names, indexed by source string number; empty without one.
std::vector<std::string> sourcesOf(std::string_view assembled);

// `log` with the location opening each line ("0:12: ...", "ERROR: 2:7: ...") rewritten from source
// string number to file ("styles/kifs/shader.glsl:12: ..."), for every number `assembled` names.
std::string remapLog(std::string_view log, std::string_view assembled);

}  // namespace geode::shader
