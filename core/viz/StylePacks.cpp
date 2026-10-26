#include "geode/viz/StylePacks.hpp"

#include <algorithm>
#include <array>
#include <cmath>
#include <set>
#include <string>

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
constexpr const char* kEffectsKey = "effects";
constexpr const char* kTiersKey = "tiers";
constexpr const char* kTierStepsKey = "steps";
constexpr const char* kTierOffKey = "off";
constexpr const char* kInputsKey = "inputs";
constexpr const char* kLensKey = "lens";
constexpr const char* kParticlesKey = "particles3d";
constexpr const char* kFeedbackKey = "feedback";
constexpr const char* kMultipassKey = "multipass";
constexpr const char* kTexturesKey = "textures";
constexpr const char* kBuffersKey = "buffers";
constexpr const char* kScaleKey = "scale";
constexpr const char* kFileKey = "file";
constexpr const char* kWrapKey = "wrap";
constexpr const char* kFilterKey = "filter";
constexpr const char* kImageUniformPrefix = "uImage";
constexpr const char* kShaderFamily = "shader";

constexpr const char* kTypeInt = "int";
constexpr const char* kTypeFloat = "float";
constexpr const char* kDetailNone = "none";
constexpr const char* kDetailMarch = "march";

constexpr char kPathSeparator = '/';
constexpr char kWordSeparator = '_';

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

std::optional<std::vector<std::string>> parseEffects(const Value& manifest, const std::string& where, std::vector<PackProblem>& problems) {
    std::vector<std::string> effects;
    const Value* declared = manifest.get(kEffectsKey);
    if (declared == nullptr) return effects;
    if (declared->kind() != Value::Kind::Array || declared->array().size() > static_cast<size_t>(quality::kMaxEffects)) {
        report(problems, PackError::BadValue, where,
               "\"effects\" must be an array of at most " + std::to_string(quality::kMaxEffects) + " names");
        return std::nullopt;
    }
    for (const Value& entry : declared->array()) {
        if (entry.kind() != Value::Kind::String || entry.string().empty()) {
            report(problems, PackError::BadValue, where, "every entry of \"effects\" must be a name");
            return std::nullopt;
        }
        if (std::find(effects.begin(), effects.end(), entry.string()) != effects.end()) {
            report(problems, PackError::BadValue, where, "effect \"" + entry.string() + "\" is named twice");
            return std::nullopt;
        }
        effects.push_back(entry.string());
    }
    return effects;
}

// One tier's entry of "tiers": {"steps": share, "off": [effect names]}, both optional.
std::optional<quality::Declared> parseTier(const std::string& tierName, const Value& entry, const std::vector<std::string>& effects,
                                           const std::string& where, std::vector<PackProblem>& problems) {
    const std::string what = "tier \"" + tierName + "\"";
    if (entry.kind() != Value::Kind::Object) {
        report(problems, PackError::BadValue, where, what + " must be an object");
        return std::nullopt;
    }
    quality::Declared declared;
    for (const auto& [key, value] : entry.object()) {
        if (key == kTierStepsKey) {
            if (value.kind() != Value::Kind::Number || !std::isfinite(value.number())) {
                report(problems, PackError::BadValue, where, what + " \"steps\" must be a number");
                return std::nullopt;
            }
            declared.stepScale = static_cast<float>(value.number());
        } else if (key == kTierOffKey && value.kind() == Value::Kind::Array) {
            for (const Value& name : value.array()) {
                const auto at = name.kind() == Value::Kind::String ? std::find(effects.begin(), effects.end(), name.string()) : effects.end();
                if (at == effects.end()) {
                    report(problems, PackError::BadValue, where, what + " switches off an effect \"effects\" does not name");
                    return std::nullopt;
                }
                declared.effectsOff |= 1u << static_cast<unsigned>(at - effects.begin());
            }
        } else {
            report(problems, PackError::BadValue, where, what + " may hold only \"steps\" and an \"off\" array");
            return std::nullopt;
        }
    }
    return declared;
}

std::optional<quality::Budgets> parseTiers(const Value& manifest, const std::vector<std::string>& effects, const std::string& where,
                                           std::vector<PackProblem>& problems) {
    const Value* declared = manifest.get(kTiersKey);
    if (declared == nullptr) return quality::defaults();
    if (declared->kind() != Value::Kind::Object) {
        report(problems, PackError::BadValue, where, "\"tiers\" must be an object");
        return std::nullopt;
    }
    std::array<quality::Declared, quality::kTierCount> tiers{};
    for (const auto& [name, entry] : declared->object()) {
        const auto tier = quality::tierNamed(name);
        // The coolest tier is always the whole budget, so only the hotter three can be declared.
        if (!tier || *tier == 0) {
            report(problems, PackError::BadValue, where, "\"tiers\" may declare only \"eased\", \"reduced\" and \"minimal\"");
            return std::nullopt;
        }
        auto parsed = parseTier(name, entry, effects, where, problems);
        if (!parsed) return std::nullopt;
        tiers[static_cast<size_t>(*tier)] = *parsed;
    }
    return quality::resolve(tiers);
}

// "inputs": the shared Style inputs the shader reads, each named once (StyleInputs.hpp).
std::optional<inputs::Set> parseInputs(const Value& manifest, const std::string& where, std::vector<PackProblem>& problems) {
    const Value* declared = manifest.get(kInputsKey);
    if (declared == nullptr) return inputs::Set{0};
    if (declared->kind() != Value::Kind::Array) {
        report(problems, PackError::BadValue, where, "\"inputs\" must be an array of input names");
        return std::nullopt;
    }
    inputs::Set set = 0;
    for (const Value& entry : declared->array()) {
        const auto input = entry.kind() == Value::Kind::String ? inputs::named(entry.string()) : std::nullopt;
        if (!input) {
            report(problems, PackError::BadValue, where, "every entry of \"inputs\" must name a shared input");
            return std::nullopt;
        }
        if (inputs::has(set, *input)) {
            report(problems, PackError::BadValue, where, "input \"" + entry.string() + "\" is named twice");
            return std::nullopt;
        }
        set |= inputs::bit(*input);
    }
    return set;
}

// A number of a lens or particle object into `out`, which keeps its default when the key is absent;
// false, with the problem reported, when it is present but not a number.
bool readNumber(const Value& object, const char* key, float& out, const std::string& where, std::vector<PackProblem>& problems) {
    const Value* value = object.get(key);
    if (value == nullptr) return true;
    if (value->kind() != Value::Kind::Number || !std::isfinite(value->number())) {
        report(problems, PackError::BadValue, where, std::string("\"") + key + "\" must be a number");
        return false;
    }
    out = static_cast<float>(value->number());
    return true;
}

bool readWhole(const Value& object, const char* key, int& out, const std::string& where, std::vector<PackProblem>& problems) {
    float value = static_cast<float>(out);
    if (!readNumber(object, key, value, where, problems)) return false;
    out = static_cast<int>(std::lround(value));
    return true;
}

// Fills `out` from an array of numbers of exactly its size; false when the key holds anything else.
template <size_t N>
bool readNumbers(const Value& object, const char* key, std::array<float, N>& out, const std::string& where,
                 std::vector<PackProblem>& problems) {
    const Value* value = object.get(key);
    if (value == nullptr) return true;
    const bool shaped = value->kind() == Value::Kind::Array && value->array().size() == N &&
                        std::all_of(value->array().begin(), value->array().end(), [](const Value& v) {
                            return v.kind() == Value::Kind::Number && std::isfinite(v.number());
                        });
    if (!shaped) {
        report(problems, PackError::BadValue, where, std::string("\"") + key + "\" must be " + std::to_string(N) + " numbers");
        return false;
    }
    for (size_t i = 0; i < N; ++i) out[i] = static_cast<float>(value->array()[i].number());
    return true;
}

// One effect's object inside "lens" (or "particles3d" itself): nullptr when absent; `ok` turns false,
// with the problem reported, when the key holds something that is not an object.
const Value* section(const Value& parent, const char* key, bool& ok, const std::string& where, std::vector<PackProblem>& problems) {
    const Value* value = parent.get(key);
    if (value == nullptr) return nullptr;
    if (value->kind() != Value::Kind::Object) {
        report(problems, PackError::BadValue, where, std::string("\"") + key + "\" must be an object");
        ok = false;
        return nullptr;
    }
    return value;
}

std::optional<lens::StreakShape> streakShape(const Value& streaks, const std::string& where, std::vector<PackProblem>& problems) {
    const Value* value = streaks.get("shape");
    if (value == nullptr) return lens::StreakShape::Star;
    const std::string name = value->kind() == Value::Kind::String ? value->string() : std::string();
    if (name == "star") return lens::StreakShape::Star;
    if (name == "zoom") return lens::StreakShape::Zoom;
    if (name == "anamorphic") return lens::StreakShape::Anamorphic;
    report(problems, PackError::BadValue, where, "\"shape\" must be \"star\", \"zoom\" or \"anamorphic\"");
    return std::nullopt;
}

// "lens": {"threshold", "flare", "godRays", "streaks", "depthOfField"}, every part optional.
std::optional<lens::LensSpec> parseLens(const Value& manifest, const std::string& family, const std::string& where,
                                        std::vector<PackProblem>& problems) {
    lens::LensSpec spec;
    bool ok = true;
    const Value* declared = section(manifest, kLensKey, ok, where, problems);
    if (!ok) return std::nullopt;
    if (declared == nullptr) return spec;
    ok = readNumber(*declared, "threshold", spec.threshold, where, problems);
    if (const Value* flare = section(*declared, "flare", ok, where, problems)) {
        ok = ok && readNumber(*flare, "amount", spec.flare.amount, where, problems) &&
             readWhole(*flare, "ghosts", spec.flare.ghosts, where, problems) &&
             readNumber(*flare, "halo", spec.flare.halo, where, problems) &&
             readNumber(*flare, "dispersion", spec.flare.dispersion, where, problems);
    }
    if (const Value* rays = section(*declared, "godRays", ok, where, problems)) {
        std::array<float, 2> source{spec.godRays.x, spec.godRays.y};
        ok = ok && readNumber(*rays, "amount", spec.godRays.amount, where, problems) &&
             readNumbers(*rays, "source", source, where, problems) &&
             readNumber(*rays, "length", spec.godRays.length, where, problems) &&
             readNumber(*rays, "decay", spec.godRays.decay, where, problems);
        spec.godRays.x = source[0];
        spec.godRays.y = source[1];
    }
    if (const Value* streaks = section(*declared, "streaks", ok, where, problems)) {
        const auto shape = ok ? streakShape(*streaks, where, problems) : std::nullopt;
        ok = shape.has_value() && readNumber(*streaks, "amount", spec.streaks.amount, where, problems) &&
             readWhole(*streaks, "spokes", spec.streaks.spokes, where, problems) &&
             readNumber(*streaks, "length", spec.streaks.length, where, problems);
        spec.streaks.shape = shape.value_or(lens::StreakShape::Star);
    }
    if (const Value* dof = section(*declared, "depthOfField", ok, where, problems)) {
        // Only a shader Style writes its own alpha, so only a shader Style can hand over its depth.
        if (ok && family != kShaderFamily) {
            report(problems, PackError::BadValue, where, "only a shader Style can declare \"depthOfField\"");
            ok = false;
        }
        ok = ok && readNumber(*dof, "aperture", spec.depthOfField.aperture, where, problems) &&
             readNumber(*dof, "focus", spec.depthOfField.focus, where, problems);
    }
    if (!ok) return std::nullopt;
    return lens::sanitized(spec);
}

// "particles3d": {"kind", "amount", "count", "speed", "size", "tint"}.
std::optional<lens::ParticleSpec> parseParticles(const Value& manifest, const std::string& where, std::vector<PackProblem>& problems) {
    lens::ParticleSpec spec;
    bool ok = true;
    const Value* declared = section(manifest, kParticlesKey, ok, where, problems);
    if (!ok) return std::nullopt;
    if (declared == nullptr) return spec;
    if (const Value* kind = declared->get("kind")) {
        const std::string name = kind->kind() == Value::Kind::String ? kind->string() : std::string();
        if (name == "streaks") {
            spec.kind = lens::ParticleKind::Streaks;
        } else if (name == "sparks") {
            spec.kind = lens::ParticleKind::Sparks;
        } else {
            report(problems, PackError::BadValue, where, "\"kind\" must be \"streaks\" or \"sparks\"");
            return std::nullopt;
        }
    }
    ok = readNumber(*declared, "amount", spec.amount, where, problems) && readNumber(*declared, "count", spec.count, where, problems) &&
         readNumber(*declared, "speed", spec.speed, where, problems) && readNumber(*declared, "size", spec.size, where, problems) &&
         readNumbers(*declared, "tint", spec.tint, where, problems);
    if (!ok) return std::nullopt;
    return lens::sanitized(spec);
}

struct Feedback {
    feedback::WarpSpec spec;
    std::string shaderPath;
};

// One border painted into the loop, {"size", "color", "alpha"}, each part optional; true when absent.
bool readBorder(const Value& declared, const char* key, feedback::Border& out, const std::string& where,
                std::vector<PackProblem>& problems) {
    bool ok = true;
    const Value* border = section(declared, key, ok, where, problems);
    if (border == nullptr) return ok;
    return readNumber(*border, "size", out.size, where, problems) && readNumbers(*border, "color", out.color, where, problems) &&
           readNumber(*border, "alpha", out.alpha, where, problems);
}

// The streaks along the motion field, {"amount", "color", "cells"}, each part optional; true when absent.
bool readStreaks(const Value& declared, feedback::Streaks& out, const std::string& where, std::vector<PackProblem>& problems) {
    bool ok = true;
    const Value* streaks = section(declared, "streaks", ok, where, problems);
    if (streaks == nullptr) return ok;
    return readNumber(*streaks, "amount", out.amount, where, problems) && readNumbers(*streaks, "color", out.color, where, problems) &&
           readWhole(*streaks, "cells", out.cells, where, problems);
}

// "feedback": the warp layer (FeedbackWarp.hpp), {"decay", "zoom", "rotate", "flow", "stretch", "warp",
// "tint", "center", "react", "outer", "inner", "streaks", "shader"}, every part optional. Only a shader Style draws it, and it reads
// the warped picture as its previous frame, so it must declare that input.
std::optional<Feedback> parseFeedback(const Value& manifest, const StylePack& pack, const std::string& where,
                                      std::vector<PackProblem>& problems) {
    Feedback out;
    bool ok = true;
    const Value* declared = section(manifest, kFeedbackKey, ok, where, problems);
    if (!ok) return std::nullopt;
    if (declared == nullptr) return out;
    if (pack.family != kShaderFamily) {
        report(problems, PackError::BadValue, where, "only a shader Style can declare \"feedback\"");
        return std::nullopt;
    }
    if (!inputs::has(pack.inputs, inputs::Input::PreviousFrame)) {
        report(problems, PackError::BadValue, where, "a Style with \"feedback\" reads it as \"previous_frame\", which \"inputs\" must declare");
        return std::nullopt;
    }
    feedback::WarpSpec& spec = out.spec;
    ok = readNumber(*declared, "decay", spec.decay, where, problems) && readNumber(*declared, "zoom", spec.zoom, where, problems) &&
         readNumber(*declared, "rotate", spec.rotate, where, problems) && readNumbers(*declared, "flow", spec.flow, where, problems) &&
         readNumbers(*declared, "stretch", spec.stretch, where, problems) && readNumber(*declared, "warp", spec.warp, where, problems) &&
         readNumbers(*declared, "tint", spec.tint, where, problems) && readNumbers(*declared, "center", spec.center, where, problems) &&
         readNumber(*declared, "react", spec.react, where, problems) && readBorder(*declared, "outer", spec.outer, where, problems) &&
         readBorder(*declared, "inner", spec.inner, where, problems) && readStreaks(*declared, spec.streaks, where, problems);
    if (!ok) return std::nullopt;
    if (const Value* shader = declared->get(kShaderKey)) {
        if (shader->kind() != Value::Kind::String || shader->string().empty()) {
            report(problems, PackError::BadValue, where, "the feedback \"shader\" must name a file in the pack");
            return std::nullopt;
        }
        out.shaderPath = packFilePath(pack.id, shader->string());
    }
    spec.on = true;
    spec = feedback::sanitized(spec);
    return out;
}

// A boolean of the "multipass" object into `out`, which keeps its default when the key is absent.
bool readFlag(const Value& object, const char* key, bool& out, const std::string& where, std::vector<PackProblem>& problems) {
    const Value* value = object.get(key);
    if (value == nullptr) return true;
    if (value->kind() != Value::Kind::Bool) {
        report(problems, PackError::BadValue, where, std::string("\"") + key + "\" must be true or false");
        return false;
    }
    out = value->boolValue();
    return true;
}

// "multipass": the engine's passes (MultiPass.hpp), {"scale", "coarse", "temporal"}, every part optional.
// Only a shader Style runs them, and a feedback loop already keeps a picture of its own at the target's size.
std::optional<multipass::Spec> parseMultipass(const Value& manifest, const StylePack& pack, const std::string& where,
                                              std::vector<PackProblem>& problems) {
    multipass::Spec spec;
    bool ok = true;
    const Value* declared = section(manifest, kMultipassKey, ok, where, problems);
    if (!ok) return std::nullopt;
    if (declared == nullptr) return spec;
    if (pack.family != kShaderFamily) {
        report(problems, PackError::BadValue, where, "only a shader Style can declare \"multipass\"");
        return std::nullopt;
    }
    if (pack.feedback.on) {
        report(problems, PackError::BadValue, where, "a Style with \"feedback\" cannot also declare \"multipass\"");
        return std::nullopt;
    }
    ok = readNumber(*declared, "scale", spec.scale, where, problems) && readFlag(*declared, "coarse", spec.coarse, where, problems) &&
         readFlag(*declared, "temporal", spec.temporal, where, problems);
    if (!ok) return std::nullopt;
    spec.on = true;
    return multipass::sanitized(spec);
}

// A name made of lower-case letters, digits and _, starting with a letter: an image's name, which its
// sampler's name is made from.
bool isImageName(const std::string& name) {
    if (name.empty() || name.front() < 'a' || name.front() > 'z') return false;
    return std::all_of(name.begin(), name.end(), [](char c) { return (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == kWordSeparator; });
}

std::optional<TextureWrap> wrapNamed(const std::string& name) {
    if (name == "repeat") return TextureWrap::Repeat;
    if (name == "mirror") return TextureWrap::Mirror;
    if (name == "clamp") return TextureWrap::Clamp;
    return std::nullopt;
}

std::optional<TextureFilter> filterNamed(const std::string& name) {
    if (name == "linear") return TextureFilter::Linear;
    if (name == "nearest") return TextureFilter::Nearest;
    if (name == "mipmap") return TextureFilter::Mipmap;
    return std::nullopt;
}

// One entry of "textures": {"name", "file", "wrap", "filter"}, the last two optional.
std::optional<TextureSpec> parseTexture(const Value& entry, const std::string& styleId, const std::string& where,
                                        std::vector<PackProblem>& problems) {
    const std::string* name = entry.kind() == Value::Kind::Object ? stringField(entry, kNameKey) : nullptr;
    const std::string* file = name != nullptr ? stringField(entry, kFileKey) : nullptr;
    if (name == nullptr || file == nullptr) {
        report(problems, PackError::MissingField, where, "an image needs \"name\" and \"file\"");
        return std::nullopt;
    }
    const std::string what = "image \"" + *name + "\"";
    if (!isImageName(*name)) {
        report(problems, PackError::BadValue, where, what + " needs a name of lower-case letters, digits and _, starting with a letter");
        return std::nullopt;
    }
    if (file->find(kPathSeparator) != std::string::npos) {
        report(problems, PackError::BadValue, where, what + " must be a file in the Style's own folder");
        return std::nullopt;
    }
    TextureSpec spec;
    spec.name = *name;
    spec.path = packFilePath(styleId, *file);
    if (const Value* wrap = entry.get(kWrapKey)) {
        const auto mode = wrap->kind() == Value::Kind::String ? wrapNamed(wrap->string()) : std::nullopt;
        if (!mode) {
            report(problems, PackError::BadValue, where, what + " \"wrap\" must be \"repeat\", \"mirror\" or \"clamp\"");
            return std::nullopt;
        }
        spec.wrap = *mode;
    }
    if (const Value* filter = entry.get(kFilterKey)) {
        const auto mode = filter->kind() == Value::Kind::String ? filterNamed(filter->string()) : std::nullopt;
        if (!mode) {
            report(problems, PackError::BadValue, where, what + " \"filter\" must be \"linear\", \"nearest\" or \"mipmap\"");
            return std::nullopt;
        }
        spec.filter = *mode;
    }
    return spec;
}

// "textures": the images the Style ships in its pack (#391), each named once, at most kMaxTextures, and
// only a shader Style samples them.
std::optional<std::vector<TextureSpec>> parseTextures(const Value& manifest, const StylePack& pack, const std::string& where,
                                                      std::vector<PackProblem>& problems) {
    std::vector<TextureSpec> textures;
    const Value* declared = manifest.get(kTexturesKey);
    if (declared == nullptr) return textures;
    if (pack.family != kShaderFamily) {
        report(problems, PackError::BadValue, where, "only a shader Style can declare \"textures\"");
        return std::nullopt;
    }
    if (declared->kind() != Value::Kind::Array || declared->array().size() > static_cast<size_t>(kMaxTextures)) {
        report(problems, PackError::BadValue, where, "\"textures\" must be an array of at most " + std::to_string(kMaxTextures) + " images");
        return std::nullopt;
    }
    for (const Value& entry : declared->array()) {
        auto spec = parseTexture(entry, pack.id, where, problems);
        if (!spec) return std::nullopt;
        const bool named = std::any_of(textures.begin(), textures.end(), [&](const TextureSpec& t) { return t.name == spec->name; });
        if (named) {
            report(problems, PackError::BadValue, where, "image \"" + spec->name + "\" is declared twice");
            return std::nullopt;
        }
        textures.push_back(std::move(*spec));
    }
    return textures;
}

struct Buffers {
    bufferpass::Spec spec;
    std::vector<std::string> shaderPaths;
};

// What a problem calls the pack's buffer `index`: buffer A to buffer D.
std::string bufferName(size_t index) {
    std::string name("buffer ");
    name.push_back(static_cast<char>('A' + index));
    return name;
}

// The fragment shader of one entry of "buffers": a file of its own in the Style's folder, never the image
// pass's.
std::optional<std::string> parseBufferShader(const Value& entry, const std::string& what, const StylePack& pack, const std::string& where,
                                             std::vector<PackProblem>& problems) {
    const std::string* shader = entry.kind() == Value::Kind::Object ? stringField(entry, kShaderKey) : nullptr;
    if (shader == nullptr) {
        report(problems, PackError::MissingField, where, what + " needs \"shader\", its own fragment shader");
        return std::nullopt;
    }
    if (shader->find(kPathSeparator) != std::string::npos) {
        report(problems, PackError::BadValue, where, what + " must be a file in the Style's own folder");
        return std::nullopt;
    }
    std::string path = packFilePath(pack.id, *shader);
    if (path == pack.shaderPath) {
        report(problems, PackError::BadValue, where, what + " needs a shader of its own, not the image pass's");
        return std::nullopt;
    }
    return path;
}

// "buffers": the Style's own buffer passes (#387, BufferPasses.hpp), A to D in order, each {"shader",
// "scale"}, the scale optional. Only a shader Style runs them, never beside a feedback loop or the engine's
// multi-pass march (each already keeps pictures of its own), and they take the sampler units after the
// Style's images, so the two together are at most kMaxTextures.
std::optional<Buffers> parseBuffers(const Value& manifest, const StylePack& pack, const std::string& where,
                                    std::vector<PackProblem>& problems) {
    Buffers out;
    const Value* declared = manifest.get(kBuffersKey);
    if (declared == nullptr) return out;
    if (pack.family != kShaderFamily) {
        report(problems, PackError::BadValue, where, "only a shader Style can declare \"buffers\"");
        return std::nullopt;
    }
    if (pack.feedback.on || pack.multipass.on) {
        report(problems, PackError::BadValue, where, "a Style with \"feedback\" or \"multipass\" cannot also declare \"buffers\"");
        return std::nullopt;
    }
    const size_t units = static_cast<size_t>(kMaxTextures);
    const size_t room = std::min(units - std::min(pack.textures.size(), units), static_cast<size_t>(bufferpass::kMaxBuffers));
    if (declared->kind() != Value::Kind::Array || declared->array().empty() || declared->array().size() > room) {
        const std::string shape = room == 0 ? std::string("no unit is left for \"buffers\"")
                                            : "\"buffers\" must be an array of 1 to " + std::to_string(room) + " passes";
        report(problems, PackError::BadValue, where, shape + ": the images and the buffers share " + std::to_string(kMaxTextures) + " units");
        return std::nullopt;
    }
    const auto& entries = declared->array();
    for (size_t i = 0; i < entries.size(); ++i) {
        const std::string what = bufferName(i);
        auto path = parseBufferShader(entries[i], what, pack, where, problems);
        if (!path) return std::nullopt;
        if (std::find(out.shaderPaths.begin(), out.shaderPaths.end(), *path) != out.shaderPaths.end()) {
            report(problems, PackError::BadValue, where, what + " shares its shader with another buffer");
            return std::nullopt;
        }
        if (!readNumber(entries[i], kScaleKey, out.spec.scale[i], where, problems)) return std::nullopt;
        out.shaderPaths.push_back(std::move(*path));
    }
    out.spec.count = static_cast<int>(out.shaderPaths.size());
    out.spec = bufferpass::sanitized(out.spec);
    return out;
}

}  // namespace

std::string textureUniform(std::string_view name) {
    std::string uniform(kImageUniformPrefix);
    bool wordStart = true;
    for (const char c : name) {
        if (c == kWordSeparator) {
            wordStart = true;
            continue;
        }
        uniform.push_back(wordStart && c >= 'a' && c <= 'z' ? static_cast<char>(c - 'a' + 'A') : c);
        wordStart = false;
    }
    return uniform;
}

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
    auto effects = parseEffects(*doc, folderId, problems);
    if (!effects) return std::nullopt;
    pack.effects = std::move(*effects);
    const auto tiers = parseTiers(*doc, pack.effects, folderId, problems);
    if (!tiers) return std::nullopt;
    pack.tiers = *tiers;
    const auto declaredInputs = parseInputs(*doc, folderId, problems);
    if (!declaredInputs) return std::nullopt;
    pack.inputs = *declaredInputs;
    const auto lensEffects = parseLens(*doc, pack.family, folderId, problems);
    if (!lensEffects) return std::nullopt;
    pack.lensEffects = *lensEffects;
    const auto particles = parseParticles(*doc, folderId, problems);
    if (!particles) return std::nullopt;
    pack.particles3d = *particles;
    auto warp = parseFeedback(*doc, pack, folderId, problems);
    if (!warp) return std::nullopt;
    pack.feedback = warp->spec;
    pack.feedbackShaderPath = std::move(warp->shaderPath);
    auto textures = parseTextures(*doc, pack, folderId, problems);
    if (!textures) return std::nullopt;
    pack.textures = std::move(*textures);
    const auto passes = parseMultipass(*doc, pack, folderId, problems);
    if (!passes) return std::nullopt;
    pack.multipass = *passes;
    auto buffers = parseBuffers(*doc, pack, folderId, problems);
    if (!buffers) return std::nullopt;
    pack.buffers = buffers->spec;
    pack.bufferShaderPaths = std::move(buffers->shaderPaths);
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
