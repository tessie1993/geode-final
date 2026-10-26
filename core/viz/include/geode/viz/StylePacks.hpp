#pragma once
#include <functional>
#include <map>
#include <optional>
#include <string>
#include <string_view>
#include <vector>

#include "geode/viz/BufferPasses.hpp"
#include "geode/viz/FeedbackWarp.hpp"
#include "geode/viz/LensFx.hpp"
#include "geode/viz/MultiPass.hpp"
#include "geode/viz/QualityTiers.hpp"
#include "geode/viz/StyleInputs.hpp"

namespace geode::viz::packs {

// Every Style is a data pack: a folder `styles/<id>/` in the APK's assets holding a `manifest.json`
// and, for a shader Style, its fragment shader. `styles/index.json` lists the Styles in picker order
// (an asset folder cannot be listed from native code), and `styles/families.json` declares, per
// family, the parameters a Style of that family may set, with their types and ranges. The format is
// described in docs/style-packs.md and checked in CI by tools/ci/check_style_packs.py; the Kotlin
// side reads the same files (StylePacks.kt), so both sides build one registry from one source.

inline constexpr std::string_view kStylesDir = "styles/";
inline constexpr std::string_view kIndexFile = "index.json";
inline constexpr std::string_view kFamiliesFile = "families.json";
inline constexpr std::string_view kManifestFile = "manifest.json";

// The pack format version this build reads. Each of the three files carries it.
inline constexpr int kFormatVersion = 1;

// The asset path of a file inside the styles folder (`styles/<name>`).
std::string stylesPath(std::string_view name);
// The asset path of a file inside one Style's pack (`styles/<id>/<file>`).
std::string packFilePath(std::string_view styleId, std::string_view file);
// The asset path of a Style's manifest (`styles/<id>/manifest.json`).
std::string manifestPath(std::string_view styleId);

enum class ParameterType { Int, Float };

// One parameter a family's renderer reads from the Style it draws.
struct ParameterSpec {
    std::string key;
    ParameterType type = ParameterType::Float;
    float min = 0.0f;
    float max = 0.0f;
    // Absent: every Style of the family must set the parameter itself.
    std::optional<float> defaultValue;
};

struct FamilySchema {
    std::string name;
    std::vector<ParameterSpec> parameters;

    const ParameterSpec* find(std::string_view key) const;
};

// What the user's Detail control drives on this Style beyond the shared quality ladder.
enum class DetailUse {
    None,
    // The Style raymarches and breaks its loop on the Detail step budget (uSteps).
    March,
};

// A Style's own control on top of the Shared core, declared generically: the same name means the
// same control, with the same range, on every Style that has it.
struct ExtraSpec {
    std::string name;
    float min = 0.0f;
    float max = 1.0f;
    float defaultValue = 0.0f;
};

// How a Style's own image repeats past its edges and how it is filtered ("textures", #391).
enum class TextureWrap {
    Repeat,  // tiles: the default
    Mirror,  // tiles, every other copy mirrored, so no edge shows a seam
    Clamp,   // the edge pixels go on
};
enum class TextureFilter {
    Linear,   // blended between the nearest four pixels: the default
    Nearest,  // the nearest pixel, hard-edged
    Mipmap,   // blended across a chain of halved copies too, so a far or small use does not shimmer
};

// At most this many images per Style, each on its own sampler unit above the shared inputs'.
inline constexpr int kMaxTextures = 4;
// The longest side a pack image may have, in pixels (CI holds shipped images to it).
inline constexpr int kMaxTextureSide = 1024;

// One image a Style ships in its own pack ("textures"): a PNG beside its manifest, which its shader samples
// as uImage<Name> (textureUniform) with the wrap and filter the pack chose, and nothing in code names it.
struct TextureSpec {
    std::string name;
    // The image's asset path (`styles/<id>/<file>`).
    std::string path;
    TextureWrap wrap = TextureWrap::Repeat;
    TextureFilter filter = TextureFilter::Linear;
};

// The sampler a shader reads the image `name` by: uImage and the name's words capitalised and run
// together (paper: uImagePaper, fold_ramp: uImageFoldRamp).
std::string textureUniform(std::string_view name);

struct StylePack {
    std::string id;
    std::string label;
    std::string family;
    // Asset path of the fragment shader (`styles/<id>/<file>`); empty for a family that brings its own.
    std::string shaderPath;
    float renderScale = 1.0f;
    DetailUse detail = DetailUse::None;
    // Every parameter of the family, resolved: the Style's value, else the family default, clamped to
    // the family's range and rounded for an int parameter.
    std::map<std::string, float> parameters;
    std::vector<ExtraSpec> extras;
    // The costly effects the Style can switch off when the phone is hot, by name ("effects"); effect i
    // is bit i of a tier's effectsOff, which the shader reads as uFxOff.
    std::vector<std::string> effects;
    // What each thermal tier lets the Style spend ("tiers"): its march step share and the effects off.
    quality::Budgets tiers = quality::defaults();
    // The shared inputs the Style's shader reads ("inputs", StyleInputs.hpp): the engine makes and binds
    // only these.
    inputs::Set inputs = 0;
    // The shared lens effects and the 3D particle layer the Style declares ("lens", "particles3d"),
    // each held to its range; everything is off when the manifest declares neither.
    lens::LensSpec lensEffects;
    lens::ParticleSpec particles3d;
    // The feedback warp layer the Style opts into ("feedback", FeedbackWarp.hpp), held to its ranges; off
    // when the manifest does not declare it. Its own warp stage's asset path (`styles/<id>/<file>`),
    // or empty for the shared one (shaders/feedback_warp_frag.glsl).
    feedback::WarpSpec feedback;
    std::string feedbackShaderPath;
    // The engine's passes the Style opts into ("multipass", MultiPass.hpp): its march at a share of the
    // target, the coarse prepass and the temporal accumulation; off when the manifest does not declare it.
    multipass::Spec multipass;
    // The images the Style ships in its pack ("textures"), in the manifest's order; shader Styles only.
    std::vector<TextureSpec> textures;
    // The Style's own buffer passes ("buffers", BufferPasses.hpp), A to D: how many and each one's share of
    // the target, and each pass's fragment shader (`styles/<id>/<file>`), in the same order. None when the
    // manifest does not declare them. The buffers take the sampler units after the images', so the two
    // together are at most kMaxTextures.
    bufferpass::Spec buffers;
    std::vector<std::string> bufferShaderPaths;

    // The resolved value of `key`; 0 for a key the family does not declare.
    float parameter(std::string_view key) const;
    int intParameter(std::string_view key) const;
};

enum class PackError {
    MissingFile,       // the index, the families file or a manifest is not in the assets
    NotJson,           // the file is not a JSON object
    WrongVersion,      // "version" is not kFormatVersion
    MissingField,      // a required field is absent or has the wrong JSON type
    BadValue,          // a field has a value outside what the format allows
    IdMismatch,        // a manifest's "id" is not the folder it was read from
    DuplicateId,       // the index lists an id twice
    UnknownFamily,     // a manifest names a family families.json does not declare
    UnknownParameter,  // a manifest sets a parameter its family does not declare
};

// A pack that could not be read. The registry leaves that Style out and keeps every other one.
struct PackProblem {
    PackError error;
    // The file it is about: "index.json", "families.json" or the Style id.
    std::string where;
    std::string detail;
};

const char* describe(PackError error);

// Reads one asset by path; nullopt when it does not exist.
using AssetReader = std::function<std::optional<std::string>(const std::string& path)>;

std::optional<std::vector<FamilySchema>> parseFamilies(const std::string& text, std::vector<PackProblem>& problems);
std::optional<std::vector<std::string>> parseIndex(const std::string& text, std::vector<PackProblem>& problems);
// Parses the manifest read from folder `folderId` against the declared families.
std::optional<StylePack> parseManifest(const std::string& folderId, const std::string& text,
                                       const std::vector<FamilySchema>& families, std::vector<PackProblem>& problems);

// Every Style the packs declare, in index order.
class StylePackRegistry {
public:
    StylePackRegistry() = default;

    // Reads index.json, families.json and every listed manifest through `read`.
    static StylePackRegistry load(const AssetReader& read);

    const StylePack* find(std::string_view id) const;
    const std::vector<StylePack>& styles() const { return styles_; }
    std::vector<std::string> ids() const;
    const FamilySchema* family(std::string_view name) const;
    const std::vector<FamilySchema>& families() const { return families_; }
    const std::vector<PackProblem>& problems() const { return problems_; }

    // The debug build's Style lab (geode/viz/StyleLab.hpp; defined in StyleLab.cpp): replaces Style `id`
    // with the pack `manifestText` declares, read as if from folder `id`. The problem, and no change,
    // when no Style has that id or the text is not a valid manifest for it.
    std::optional<PackProblem> replace(const std::string& id, const std::string& manifestText);

private:
    std::vector<FamilySchema> families_;
    std::vector<StylePack> styles_;
    std::vector<PackProblem> problems_;
};

}  // namespace geode::viz::packs
