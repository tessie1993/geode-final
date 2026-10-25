#include "geode/viz/StyleProfiles.hpp"

#include <array>
#include <variant>

namespace geode::viz::styles {

namespace {

// One profile field and the pack parameter it is read from (the same name).
template <typename Style>
struct Binding {
    const char* key;
    std::variant<int Style::*, float Style::*> member;
};

template <typename Style, size_t N>
Style fill(const packs::StylePack& pack, const std::array<Binding<Style>, N>& bindings) {
    Style style;
    style.id = pack.id;
    for (const Binding<Style>& binding : bindings) {
        if (const auto* asInt = std::get_if<int Style::*>(&binding.member)) {
            style.*(*asInt) = pack.intParameter(binding.key);
        } else {
            style.*std::get<float Style::*>(binding.member) = pack.parameter(binding.key);
        }
    }
    return style;
}

template <typename Style, size_t N>
std::vector<std::string> keys(const std::array<Binding<Style>, N>& bindings) {
    std::vector<std::string> out;
    out.reserve(N);
    for (const Binding<Style>& binding : bindings) out.emplace_back(binding.key);
    return out;
}

const std::array<Binding<CymaticsStyle>, 13> kCymaticsBindings = {{
    {"shaderStyle", &CymaticsStyle::shaderStyle},
    {"geometryOverride", &CymaticsStyle::geometryOverride},
    {"scale", &CymaticsStyle::scale},
    {"fill", &CymaticsStyle::fill},
    {"line", &CymaticsStyle::line},
    {"glow", &CymaticsStyle::glow},
    {"iridescence", &CymaticsStyle::iridescence},
    {"caustic", &CymaticsStyle::caustic},
    {"flow", &CymaticsStyle::flow},
    {"swirl", &CymaticsStyle::swirl},
    {"hueOffset", &CymaticsStyle::hueOffset},
    {"hueSpan", &CymaticsStyle::hueSpan},
    {"modeCap", &CymaticsStyle::modeCap},
}};

const std::array<Binding<LifeStyle>, 19> kLifeBindings = {{
    {"rule", &LifeStyle::rule},
    {"dt", &LifeStyle::dt},
    {"core", &LifeStyle::core},
    {"growth", &LifeStyle::growth},
    {"mu", &LifeStyle::mu},
    {"sigma", &LifeStyle::sigma},
    {"radius", &LifeStyle::radius},
    {"rings", &LifeStyle::rings},
    {"b1", &LifeStyle::b1},
    {"b2", &LifeStyle::b2},
    {"b3", &LifeStyle::b3},
    {"feed", &LifeStyle::feed},
    {"kill", &LifeStyle::kill},
    {"aniso", &LifeStyle::aniso},
    {"substeps", &LifeStyle::substeps},
    {"look", &LifeStyle::look},
    {"seedJitter", &LifeStyle::seedJitter},
    {"hueOffset", &LifeStyle::hueOffset},
    {"hueSpan", &LifeStyle::hueSpan},
}};

const std::array<Binding<AcidStyle>, 15> kAcidBindings = {{
    {"mode", &AcidStyle::mode},
    {"source", &AcidStyle::source},
    {"zoom", &AcidStyle::zoom},
    {"rotate", &AcidStyle::rotate},
    {"hueRate", &AcidStyle::hueRate},
    {"feedback", &AcidStyle::feedback},
    {"modulate", &AcidStyle::modulate},
    {"glitch", &AcidStyle::glitch},
    {"overdrive", &AcidStyle::overdrive},
    {"liquid", &AcidStyle::liquid},
    {"scanline", &AcidStyle::scanline},
    {"curve", &AcidStyle::curve},
    {"saturation", &AcidStyle::saturation},
    {"hueOffset", &AcidStyle::hueOffset},
    {"hueSpan", &AcidStyle::hueSpan},
}};

const std::array<Binding<MycoStyle>, 20> kMycoBindings = {{
    {"agentRes", &MycoStyle::agentRes},
    {"sensorDist", &MycoStyle::sensorDist},
    {"sensorAngle", &MycoStyle::sensorAngle},
    {"turnAngle", &MycoStyle::turnAngle},
    {"moveStep", &MycoStyle::moveStep},
    {"jitter", &MycoStyle::jitter},
    {"deposit", &MycoStyle::deposit},
    {"decay", &MycoStyle::decay},
    {"speciesMix", &MycoStyle::speciesMix},
    {"selfA", &MycoStyle::selfA},
    {"crossAb", &MycoStyle::crossAb},
    {"crossBa", &MycoStyle::crossBa},
    {"selfB", &MycoStyle::selfB},
    {"snap", &MycoStyle::snap},
    {"reaim", &MycoStyle::reaim},
    {"aniso", &MycoStyle::aniso},
    {"look", &MycoStyle::look},
    {"exposure", &MycoStyle::exposure},
    {"hueOffset", &MycoStyle::hueOffset},
    {"hueSpan", &MycoStyle::hueSpan},
}};

const std::array<Binding<FluidStyle>, 8> kFluidBindings = {{
    {"look", &FluidStyle::look},
    {"curl", &FluidStyle::curl},
    {"velocityDissipation", &FluidStyle::velocityDissipation},
    {"densityDissipation", &FluidStyle::densityDissipation},
    {"splatRadius", &FluidStyle::splatRadius},
    {"splatForce", &FluidStyle::splatForce},
    {"bloom", &FluidStyle::bloom},
    {"hueOffset", &FluidStyle::hueOffset},
}};

}  // namespace

CymaticsStyle cymaticsProfile(const packs::StylePack& pack) { return fill(pack, kCymaticsBindings); }
LifeStyle lifeProfile(const packs::StylePack& pack) { return fill(pack, kLifeBindings); }
AcidStyle acidProfile(const packs::StylePack& pack) { return fill(pack, kAcidBindings); }
MycoStyle mycoProfile(const packs::StylePack& pack) { return fill(pack, kMycoBindings); }
FluidStyle fluidProfile(const packs::StylePack& pack) { return fill(pack, kFluidBindings); }

std::vector<std::string> profileKeys(std::string_view familyName) {
    if (familyName == family::kCymatics) return keys(kCymaticsBindings);
    if (familyName == family::kLife) return keys(kLifeBindings);
    if (familyName == family::kAcid) return keys(kAcidBindings);
    if (familyName == family::kMycelium) return keys(kMycoBindings);
    if (familyName == family::kFluid) return keys(kFluidBindings);
    return {};
}

}  // namespace geode::viz::styles
