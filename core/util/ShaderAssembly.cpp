#include "geode/util/ShaderAssembly.hpp"

#include <algorithm>

namespace geode::shader {

namespace {

constexpr std::string_view kIncludeTag = "//#include";
constexpr std::string_view kVersion = "version";

bool isBlank(char c) { return c == ' ' || c == '\t'; }
bool isDigit(char c) { return c >= '0' && c <= '9'; }
bool isWord(char c) { return isDigit(c) || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_'; }

size_t skipBlanks(std::string_view text, size_t at) {
    while (at < text.size() && isBlank(text[at])) at++;
    return at;
}

// A `#version` directive, with any blanks before and after the '#'.
bool isVersionLine(std::string_view line) {
    const size_t hash = skipBlanks(line, 0);
    if (hash == line.size() || line[hash] != '#') return false;
    return line.substr(skipBlanks(line, hash + 1)).substr(0, kVersion.size()) == kVersion;
}

std::string lineDirective(size_t line, size_t sourceString) {
    return "#line " + std::to_string(line) + " " + std::to_string(sourceString);
}

class Assembler {
public:
    Assembler(const std::string& file, const std::vector<std::string>& known, const FileReader& read, std::string* error)
        : known_(known), read_(read), error_(error), files_{file} {}

    const std::vector<std::string>& files() const { return files_; }

    // Appends `source`, source string `index`, to `out`, without a newline after its last line.
    bool expand(const std::string& source, size_t index, std::string& out) {
        size_t pos = 0;
        size_t lineNumber = 0;
        while (true) {
            const size_t eol = source.find('\n', pos);
            const std::string_view raw = std::string_view(source).substr(pos, eol == std::string::npos ? std::string::npos : eol - pos);
            std::string_view line = raw;
            if (!line.empty() && line.back() == '\r') line.remove_suffix(1);
            lineNumber++;
            if (const auto name = includeNameOf(line)) {
                if (!include(*name, index, lineNumber, out)) return false;
            } else {
                out += raw;
                // Anything spliced in after the version line (a define, a library) is counted from here.
                if (isVersionLine(line)) out += "\n" + lineDirective(lineNumber + 1, index);
            }
            if (eol == std::string::npos) return true;
            out += '\n';
            pos = eol + 1;
        }
    }

private:
    bool fail(const std::string& what, const std::string& name, size_t into, size_t lineNumber) {
        if (error_) *error_ = what + " '" + name + "' (" + files_[into] + ":" + std::to_string(lineNumber) + ")";
        return false;
    }

    size_t numberOf(const std::string& file) {
        const auto at = std::find(files_.begin(), files_.end(), file);
        if (at != files_.end()) return static_cast<size_t>(at - files_.begin());
        files_.push_back(file);
        return files_.size() - 1;
    }

    bool include(const std::string& name, size_t into, size_t lineNumber, std::string& out) {
        if (std::find(known_.begin(), known_.end(), name) == known_.end()) return fail("unknown shader include", name, into, lineNumber);
        if (std::find(open_.begin(), open_.end(), name) != open_.end()) return fail("circular shader include", name, into, lineNumber);
        const std::string file = libraryFile(name);
        const auto text = read_(file);
        if (!text) return fail("missing shader include", name, into, lineNumber);
        const size_t index = numberOf(file);
        out += lineDirective(1, index) + "\n";
        open_.push_back(name);
        const bool expanded = expand(*text, index, out);
        open_.pop_back();
        if (!expanded) return false;
        out += "\n" + lineDirective(lineNumber + 1, into);
        return true;
    }

    const std::vector<std::string>& known_;
    const FileReader& read_;
    std::string* error_;
    std::vector<std::string> files_;
    // The libraries being expanded, outermost first: one of them included again is a cycle.
    std::vector<std::string> open_;
};

// `line` with its first "<n>:<digit>" location, at the start or after a blank, rewritten to "<file>:".
std::string remapLine(std::string_view line, const std::vector<std::string>& files) {
    for (size_t start = 0; start < line.size(); start++) {
        if (!isDigit(line[start]) || (start > 0 && !isBlank(line[start - 1]))) continue;
        size_t end = start;
        size_t number = 0;
        // Saturates at files.size(), which no file has: no overflow, and no match.
        while (end < line.size() && isDigit(line[end])) number = std::min(number * 10 + static_cast<size_t>(line[end++] - '0'), files.size());
        if (end + 1 < line.size() && line[end] == ':' && isDigit(line[end + 1]) && number < files.size()) {
            return std::string(line.substr(0, start)) + files[number] + std::string(line.substr(end));
        }
        start = end;
    }
    return std::string(line);
}

}  // namespace

std::optional<std::string> includeNameOf(std::string_view line) {
    size_t i = skipBlanks(line, 0);
    if (line.substr(i, kIncludeTag.size()) != kIncludeTag) return std::nullopt;
    i += kIncludeTag.size();
    const size_t start = skipBlanks(line, i);
    if (start == i) return std::nullopt;
    size_t end = start;
    while (end < line.size() && isWord(line[end])) end++;
    if (end == start || skipBlanks(line, end) != line.size()) return std::nullopt;
    return std::string(line.substr(start, end - start));
}

std::string libraryFile(std::string_view name) { return "shaders/" + std::string(name) + ".glsl"; }

std::optional<std::string> assemble(const std::string& file, const std::string& source, const std::vector<std::string>& known,
                                    const FileReader& read, std::string* error) {
    Assembler assembler(file, known, read, error);
    std::string out;
    out.reserve(source.size());
    if (!assembler.expand(source, 0, out)) return std::nullopt;
    out += "\n";
    out += kSourcesTag;
    const std::vector<std::string>& files = assembler.files();
    for (size_t i = 0; i < files.size(); i++) out += " " + std::to_string(i) + "=" + files[i];
    out += "\n";
    return out;
}

std::vector<std::string> sourcesOf(std::string_view assembled) {
    std::vector<std::string> files;
    const size_t tag = assembled.rfind(kSourcesTag);
    if (tag == std::string_view::npos) return files;
    std::string_view rest = assembled.substr(tag + kSourcesTag.size());
    rest = rest.substr(0, rest.find('\n'));
    // "<n>=<file>" entries in number order, so each file's position is its number.
    size_t at = skipBlanks(rest, 0);
    while (at < rest.size()) {
        const size_t end = std::min(rest.find(' ', at), rest.size());
        const std::string_view entry = rest.substr(at, end - at);
        const size_t equals = entry.find('=');
        if (equals != std::string_view::npos) files.emplace_back(entry.substr(equals + 1));
        at = skipBlanks(rest, end);
    }
    return files;
}

std::string remapLog(std::string_view log, std::string_view assembled) {
    const std::vector<std::string> files = sourcesOf(assembled);
    std::string out;
    out.reserve(log.size());
    size_t pos = 0;
    while (true) {
        const size_t eol = log.find('\n', pos);
        out += remapLine(log.substr(pos, eol == std::string_view::npos ? std::string_view::npos : eol - pos), files);
        if (eol == std::string_view::npos) return out;
        out += '\n';
        pos = eol + 1;
    }
}

}  // namespace geode::shader
