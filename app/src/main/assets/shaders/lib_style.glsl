// A Style's own controls: its Extras (SharedCore.hpp's Extra, docs/style-packs.md "extras").
//
// One name is one control, with one range, on every Style that has it. ShaderScene::uploadExtras sets
// every one of them each frame: the default the Style's manifest gives, or the Customization's value
// once a plan is live; an Extra the manifest does not declare reads its neutral value, so a shader may
// read any of them. Every Extra is a multiplier of the Style's own amount (0..2, neutral 1, which draws
// the Style exactly as its pack tunes it), except the centres (offsets -1..1, neutral 0) and the variant
// (a whole number 0..9, neutral 0).
//
// The music reaches these through the manifest's "reactions", which the Customization evaluates every
// frame (ReactionGraph): a shader reads the Extra and leaves the coupling to the data, so a person can
// change or remove it in Customize. A shader keeps its own audio reads only for what is not a control
// (the spectrum it draws, a waveform it traces).
uniform float uXScale;
uniform float uXCount;
uniform float uXSize;
uniform float uXThickness;
uniform float uXFlight;
uniform float uXSpin;
uniform float uXOrbit;
uniform float uXSwirl;
uniform float uXDrift;
uniform float uXComplexity;
uniform float uXEdge;
uniform float uXFog;
uniform float uXDecay;
uniform float uXViscosity;
uniform float uXGrowth;
uniform float uXSpread;
uniform float uXForce;
uniform float uXReach;
uniform float uXTurn;
uniform float uXHold;
uniform float uXVariant;
uniform float uXCenterX;
uniform float uXCenterY;

// The Style's own rates as clocks: uTime (ShaderScene's speed-integrated clock) running at the rate the
// FLIGHT, SPIN and DRIFT Extras set. ShaderScene integrates them, so a Reaction or a hand moving one of
// these changes how fast the Style moves and never jumps the picture, as multiplying uTime by the Extra
// would. At the neutral 1 each one is uTime. A shader reads a rate Extra through its clock.
uniform float uXFlightTime;
uniform float uXSpinTime;
uniform float uXDriftTime;

// A multiplier Extra kept off 0, so a scale, count or size never collapses.
float xm(float extra) { return max(extra, 0.05); }
// A whole count `n` times its COUNT Extra, never below 1.
float xcount(float n) { return max(1.0, floor(n * uXCount + 0.5)); }
// The variant as a whole number, wrapped into `variants` choices.
int xvariant(int variants) { return int(mod(floor(uXVariant + 0.5), float(variants))); }
