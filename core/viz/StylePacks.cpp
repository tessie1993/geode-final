#include "geode/viz/StylePacks.hpp"

#include <algorithm>
#include <cmath>
#include <set>

#include "geode/util/Json.hpp"
#include "geode/viz/RenderScale.hpp"

namespace geode::viz::packs {

namespace {

using json::Value;

// Keys of the three files. docs/style-packs.md describes each one.
constexpr const char* kVersionKey = "version";
constexpr const char* kStylesKey = "styles";
constexpr const char* kFamiliesKey = "families";
constexpr const char* kNameKey = "name";
constexpr const char* kParametersKey = "parameters";
constexpr const char* kKeyKey = "key";
constexpr const char* kTypeKey = "type";
constexpr const char* kMinKey = "min";
constexpr const char* kMaxKey = "max";
constexpr const char* kDefaultKey = "default";
constexpr const char* kIdKey = "id";
constexpr const char* kLabelKey = "label";
constexpr const char* kFamilyKey = "family";
constexpr const char* kShaderKey = "shader";
constexpr const char* kRenderScaleKey = "renderScale";
constexpr const char* kDetailKey = "detail";
constexpr const char* kExtrasKey = "extras";

constexpr const char* kTypeInt = "int";
constexpr const char* kTypeFloat = "float";
constexpr const char* kDetailNone = "none";
constexpr const char* kDetailMarch = "march";

constexpr char kPathSeparator = '/';

void report(std::vector<PackProblem>& problems, PackError error, const std::string& where, std::string detail) {
    problems.push_back(PackProblem{error, where, std::move(detail)});
}

std::optional<Value> parseObject(const std::string& text, const std::string& where, std::vector<PackProblem>& problems) {
    auto doc = json::parse(text);
    if (!doc || doc->kind() != Value::Kind::Object) {
        report(problems, PackError::NotJson, where, "not a JSON object");
        return std::nullopt;
    }
    const Value* version = doc->get(kVersionKey);
    if (version == nullptr || version->kind() != Value::Kind::Number || version->number() != kFormatVersion) {
        report(problems, PackError::WrongVersion, where, "\"version\" must be " + std::to_string(kFormatVersion));
        return std::nullopt;
    }
    return doc;
}

const Value* field(const Value& object, const char* key, Value::Kind kind) {
    const Value* value = object.get(key);
    return value != nullptr && value->kind() == kind ? value : nullptr;
}

const std::string* stringField(const Value& object, const char* key) {
    const Value* value = field(object, key, Value::Kind::String);
    return value != nullptr && !value->string().empty() ? &value->string() : nullptr;
}

std::optional<double> numberField(const Value& object, const char* key) {
    const Value* value = field(object, key, Value::Kind::Number);
    if (value == nullptr || !std::isfinite(value->number())) return std::nullopt;
    return value->number();
}

std::optional<ParameterSpec> parseParameterSpec(const Value& entry, const std::string& where, std::vector<PackProblem>& problems) {
    const std::string* key = entry.kind() == Value::Kind::Object ? stringField(entry, kKeyKey) : nullptr;
    const std::string* type = key != nullptr ? stringField(entry, kTypeKey) : nullptr;
    const auto min = key != nullptr ? numberField(entry, kMinKey) : std::nullopt;
    const auto max = key != nullptr ? numberField(entry, kMaxKey) : std::nullopt;
    if (key == nullptr || type == nullptr || !min || !max) {
        report(problems, PackError::MissingField, where, "a parameter needs \"key\", \"type\", \"min\" and \"max\"");
        return std::nullopt;
    }
    ParameterSpec spec;
    spec.key = *key;
    if (*type == kTypeInt) {
        spec.type = ParameterType::Int;
    } else if (*type == kTypeFloat) {
        spec.type = ParameterType::Float;
    } else {
        report(problems, PackError::BadValue, where, "parameter \"" + *key + "\" has unknown type \"" + *type + "\"");
        return std::nullopt;
    }
    spec.min = static_cast<float>(*min);
    spec.max = static_cast<float>(*max);
    if (spec.min > spec.max) {
        report(problems, PackError::BadValue, where, "parameter \"" + *key + "\" has min above max");
        return std::nullopt;
    }
    if (const auto def = numberField(entry, kDefaultKey)) spec.defaultValue = static_cast<float>(*def);
    return spec;
}

float resolve(const ParameterSpec& spec, float value) {
    const float clamped = std::clamp(value, spec.min, spec.max);
    return spec.type == ParameterType::Int ? std::round(clamped) : clamped;
}

std::optional<std::map<std::string, float>> parseParameters(const Value& manifest, const FamilySchema& family,
                                                            const std::string& where, std::vector<PackProblem>& problems) {
    const Value* declared = manifest.get(kParametersKey);
    if (declared != nullptr && declared->kind() != Value::Kind::Object) {
        report(problems, PackError::MissingField, where, "\"parameters\" must be an object");
        return std::nullopt;
    }
    if (declared != nullptr) {
        for (const auto& [key, value] : declared->object()) {
            if (family.find(key) == nullptr) {
                report(problems, PackError::UnknownParameter, where, "family \"" + family.name + "\" has no parameter \"" + key + "\"");
                return std::nullopt;
            }
            if (value.kind() != Value::Kind::Number || !std::isfinite(value.number())) {
                report(problems, PackError::BadValue, where, "parameter \"" + key + "\" is not a number");
                return std::nullopt;
            }
        }
    }
    std::map<std::string, float> resolved;
    for (const ParameterSpec& spec : family.parameters) {
        const Value* value = declared != nullptr ? declared->get(spec.key) : nullptr;
        if (value != nullptr) {
            resolved[spec.key] = resolve(spec, static_cast<float>(value->number()));
        } else if (spec.defaultValue) {
            resolved[spec.key] = resolve(spec, *spec.defaultValue);
        } else {
            report(problems, PackError::MissingField, where, "parameter \"" + spec.key + "\" has no default and must be set");
            return std::nullopt;
        }
    }
    return resolved;
}

std::optional<std::vector<ExtraSpec>> parseExtras(const Value& manifest, const std::string& where, std::vector<PackProblem>& problems) {
    std::vector<ExtraSpec> extras;
    const Value* declared = manifest.get(kExtrasKey);
    if (declared == nullptr) return extras;
    if (declared->kind() != Value::Kind::Array) {
        report(problems, PackError::MissingField, where, "\"extras\" must be an array");
        return std::nullopt;
    }
    for (const Value& entry : declared->array()) {
        const std::string* name = entry.kind() == Value::Kind::Object ? stringField(entry, kNameKey) : nullptr;
        const auto min = name != nullptr ? numberField(entry, kMinKey) : std::nullopt;
        const auto max = name != nullptr ? numberField(entry, kMaxKey) : std::nullopt;
        const auto def = name != nullptr ? numberField(entry, kDefaultKey) : std::nullopt;
        if (name == nullptr || !min || !max || !def) {
            report(problems, PackError::MissingField, where, "an extra needs \"name\", \"min\", \"max\" and \"default\"");
            return std::nullopt;
        }
        if (*min > *max || *def < *min || *def > *max) {
            report(problems, PackError::BadValue, where, "extra \"" + *name + "\" has a default outside its range");
            return std::nullopt;
        }
        extras.push_back(ExtraSpec{*name, static_cast<float>(*min), static_cast<float>(*max), static_cast<float>(*def)});
    }
    return extras;
}

std::optional<DetailUse> parseDetail(const Value& manifest, const std::string& where, std::vector<PackProblem>& problems) {
    const Value* declared = manifest.get(kDetailKey);
    if (declared == nullptr) return DetailUse::None;
    if (declared->kind() == Value::Kind::String && declared->string() == kDetailNone) return DetailUse::None;
    if (declared->kind() == Value::Kind::String && declared->string() == kDetailMarch) return DetailUse::March;
    report(problems, PackError::BadValue, where, "\"detail\" must be \"none\" or \"march\"");
    return std::nullopt;
}

}  // namespace

std::string stylesPath(std::string_view name) {
    std::string path(kStylesDir);
    path.append(name);
    return path;
}

std::string packFilePath(std::string_view styleId, std::string_view file) {
    std::string path(kStylesDir);
    path.append(styleId);
    path.push_back(kPathSeparator);
    path.append(file);
    return path;
}

std::string manifestPath(std::string_view styleId) { return packFilePath(styleId, kManifestFile); }

const ParameterSpec* FamilySchema::find(std::string_view key) const {
    for (const ParameterSpec& spec : parameters) {
        if (spec.key == key) return &spec;
    }
    return nullptr;
}

float StylePack::parameter(std::string_view key) const {
    const auto it = parameters.find(std::string(key));
    return it != parameters.end() ? it->second : 0.0f;
}

int StylePack::intParameter(std::string_view key) const { return static_cast<int>(std::lround(parameter(key))); }

const char* describe(PackError error) {
    switch (error) {
        case PackError::MissingFile: return "missing file";
        case PackError::NotJson: return "not JSON";
        case PackError::WrongVersion: return "wrong version";
        case PackError::MissingField: return "missing field";
        case PackError::BadValue: return "bad value";
        case PackError::IdMismatch: return "id mismatch";
        case PackError::DuplicateId: return "duplicate id";
        case PackError::UnknownFamily: return "unknown family";
        case PackError::UnknownParameter: return "unknown parameter";
    }
    return "unknown error";
}

std::optional<std::vector<FamilySchema>> parseFamilies(const std::string& text, std::vector<PackProblem>& problems) {
    const std::string where(kFamiliesFile);
    const auto doc = parseObject(text, where, problems);
    if (!doc) return std::nullopt;
    const Value* list = field(*doc, kFamiliesKey, Value::Kind::Array);
    if (list == nullptr) {
        report(problems, PackError::MissingField, where, "\"families\" must be an array");
        return std::nullopt;
    }
    std::vector<FamilySchema> families;
    for (const Value& entry : list->array()) {
        const std::string* name = entry.kind() == Value::Kind::Object ? stringField(entry, kNameKey) : nullptr;
        const Value* parameters = name != nullptr ? field(entry, kParametersKey, Value::Kind::Array) : nullptr;
        if (name == nullptr || parameters == nullptr) {
            report(problems, PackError::MissingField, where, "a family needs \"name\" and \"parameters\"");
            return std::nullopt;
        }
        FamilySchema family;
        family.name = *name;
        for (const Value& parameter : parameters->array()) {
            auto spec = parseParameterSpec(parameter, where, problems);
            if (!spec) return std::nullopt;
            family.parameters.push_back(std::move(*spec));
        }
        families.push_back(std::move(family));
    }
    return families;
}

std::optional<std::vector<std::string>> parseIndex(const std::string& text, std::vector<PackProblem>& problems) {
    const std::string where(kIndexFile);
    const auto doc = parseObject(text, where, problems);
    if (!doc) return std::nullopt;
    const Value* list = field(*doc, kStylesKey, Value::Kind::Array);
    if (list == nullptr) {
        report(problems, PackError::MissingField, where, "\"styles\" must be an array");
        return std::nullopt;
    }
    std::vector<std::string> ids;
    std::set<std::string> seen;
    for (const Value& entry : list->array()) {
        if (entry.kind() != Value::Kind::String || entry.string().empty()) {
            report(problems, PackError::BadValue, where, "every entry of \"styles\" must be a Style id");
            continue;
        }
        if (!seen.insert(entry.string()).second) {
            report(problems, PackError::DuplicateId, where, "\"" + entry.string() + "\" is listed twice");
            continue;
        }
        ids.push_back(entry.string());
    }
    return ids;
}

std::optional<StylePack> parseManifest(const std::string& folderId, const std::string& text,
                                       const std::vector<FamilySchema>& families, std::vector<PackProblem>& problems) {
    const auto doc = parseObject(text, folderId, problems);
    if (!doc) return std::nullopt;
    const std::string* id = stringField(*doc, kIdKey);
    const std::string* label = stringField(*doc, kLabelKey);
    const std::string* familyName = stringField(*doc, kFamilyKey);
    if (id == nullptr || label == nullptr || familyName == nullptr) {
        report(problems, PackError::MissingField, folderId, "a manifest needs \"id\", \"label\" and \"family\"");
        return std::nullopt;
    }
    if (*id != folderId) {
        report(problems, PackError::IdMismatch, folderId, "manifest says \"" + *id + "\"");
        return std::nullopt;
    }
    const auto family = std::find_if(families.begin(), families.end(), [&](const FamilySchema& f) { return f.name == *familyName; });
    if (family == families.end()) {
        report(problems, PackError::UnknownFamily, folderId, "\"" + *familyName + "\"");
        return std::nullopt;
    }
    StylePack pack;
    pack.id = *id;
    pack.label = *label;
    pack.family = *familyName;
    if (const Value* shader = doc->get(kShaderKey)) {
        if (shader->kind() != Value::Kind::String || shader->string().empty()) {
            report(problems, PackError::BadValue, folderId, "\"shader\" must name a file in the pack");
            return std::nullopt;
        }
        pack.shaderPath = packFilePath(pack.id, shader->string());
    }
    const Value* declaredScale = doc->get(kRenderScaleKey);
    if (declaredScale != nullptr && declaredScale->kind() != Value::Kind::Number) {
        report(problems, PackError::BadValue, folderId, "\"renderScale\" must be a number");
        return std::nullopt;
    }
    pack.renderScale = scale::styleRenderScale(declaredScale != nullptr ? std::optional<double>(declaredScale->number()) : std::nullopt);
    const auto detail = parseDetail(*doc, folderId, problems);
    if (!detail) return std::nullopt;
    pack.detail = *detail;
    auto parameters = parseParameters(*doc, *family, folderId, problems);
    if (!parameters) return std::nullopt;
    pack.parameters = std::move(*parameters);
    auto extras = parseExtras(*doc, folderId, problems);
    if (!extras) return std::nullopt;
    pack.extras = std::move(*extras);
    return pack;
}

StylePackRegistry StylePackRegistry::load(const AssetReader& read) {
    StylePackRegistry registry;
    const auto familiesText = read(stylesPath(kFamiliesFile));
    const auto indexText = read(stylesPath(kIndexFile));
    if (!familiesText) report(registry.problems_, PackError::MissingFile, std::string(kFamiliesFile), stylesPath(kFamiliesFile));
    if (!indexText) report(registry.problems_, PackError::MissingFile, std::string(kIndexFile), stylesPath(kIndexFile));
    if (!familiesText || !indexText) return registry;
    auto families = parseFamilies(*familiesText, registry.problems_);
    const auto ids = parseIndex(*indexText, registry.problems_);
    if (!families || !ids) return registry;
    registry.families_ = std::move(*families);
    for (const std::string& id : *ids) {
        const auto text = read(manifestPath(id));
        if (!text) {
            report(registry.problems_, PackError::MissingFile, id, manifestPath(id));
            continue;
        }
        if (auto pack = parseManifest(id, *text, registry.families_, registry.problems_)) {
            registry.styles_.push_back(std::move(*pack));
        }
    }
    return registry;
}

const StylePack* StylePackRegistry::find(std::string_view id) const {
    for (const StylePack& pack : styles_) {
        if (pack.id == id) return &pack;
    }
    return nullptr;
}

std::vector<std::string> StylePackRegistry::ids() const {
    std::vector<std::string> out;
    out.reserve(styles_.size());
    for (const StylePack& pack : styles_) out.push_back(pack.id);
    return out;
}

const FamilySchema* StylePackRegistry::family(std::string_view name) const {
    for (const FamilySchema& family : families_) {
        if (family.name == name) return &family;
    }
    return nullptr;
}

}  // namespace geode::viz::packs
