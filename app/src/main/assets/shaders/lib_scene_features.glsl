// The music beyond the bands (#294): its structure, rhythm, timbre and space,
// from the one feature bus every family reads (core/viz FeatureBus.hpp).
//
// Everything here is already shaped on the CPU, because a fragment shader has
// no frame-to-frame state: slow values glide, hits rise over an attack and
// decay, timbre is latched at phrase rate, and the tempo-locked values fall
// away while the tracker is unsure. Silence settles every one of them to a
// steady value. A Style that includes this and reads nothing costs nothing:
// the linker drops the uniforms and the uploads become no-ops.
//
// Mapping, so the picture follows the music at the scale it happens at
// (docs/style-packs.md, "The music in a Style"):
//
//   structure  novelty, section, build-up, drop, arrival -> long-form change:
//              palette, density, camera travel. Never a per-frame flicker.
//   rhythm     downbeat, beat in the bar -> bar-scale symmetry and phase;
//              kick, snare, hat -> each its own layer (low body, mid crack,
//              high sparkle), never one shared bump.
//   timbre     centroid, harmonicity, flux, onset -> material: rough or
//              smooth, metal or organic, bloom or crisp.
//   space      stereo width and pan -> true left/right asymmetry, only where
//              the Style is stereo-meaningful; correlation -> phase or
//              duplication.
//   bands      each band against its own usual level in the song (#319):
//              level -> what that band moves now, hits -> a ripple or swell
//              when it jumps, presence -> a slow lean while the passage has
//              more or less of it than usual.
//
// Not everything on everything: a Style picks the few that mean something for
// its picture, and reads them here by name (a host test holds every uniform
// to at least one Style that reads it). A tempo-locked use fades by
// min(uPulseConfidence, uTempoStability) x uDownbeatConfidence, so an unsure
// tracker leaves the picture to the energy. Hits move, swell, ripple or shift colour; none of them may
// brighten the whole frame (the No-flash rule). The bar's phase is the bar
// shared input (uBarPhase), not part of this library.

// ---- rhythm -----------------------------------------------------------------

/** Each drum's own envelope, 0..1: the kick's low body, the snare's crack, the hat's sparkle. */
uniform float uKick;
uniform float uSnare;
uniform float uHat;
/** The beat within the bar, 0..3, held where it was while the bar is not trusted. */
uniform float uBeatInBar;
/** A trusted downbeat as a hit envelope 0..1: zero whenever the bar is uncertain. */
uniform float uDownbeat;
/** How sure the tracker is of the bar, of the pulse and of the tempo, 0..1 each, gliding. */
uniform float uDownbeatConfidence;
uniform float uPulseConfidence;
uniform float uTempoStability;

// ---- structure --------------------------------------------------------------

/** How much the music is changing right now, 0..1, slow. */
uniform float uNovelty;
/**
 * A phase 0..1 that steps on to a new place at each section boundary and
 * glides there over a few seconds. Read it through a periodic function (a hue
 * offset, a sine), never as a level: it wraps.
 */
uniform float uSection;
/** Rising energy before a drop, 0..1. */
uniform float uBuildup;
/** The long glow after a drop and an arrival, 0..1, over several seconds. */
uniform float uDrop;
uniform float uArrival;
/** How loud the passage is, 0..1, slower than uSwell. */
uniform float uMacro;
/** One-shot hits that rise and decay within about a second: a section boundary, a drop, an arrival. */
uniform float uSectionHit;
uniform float uDropHit;
uniform float uArrivalHit;

// ---- timbre (latched at phrase rate) ----------------------------------------

/** How bright the sound is, 0 dark .. 1 bright. */
uniform float uCentroid;
/** How tonal it is, 0 noisy .. 1 harmonic (0.5 undecided). */
uniform float uHarmonicity;
/** How much the spectrum moves (against its own recent peak) and how strong the onsets are, 0..1. */
uniform float uFlux;
uniform float uOnset;

// ---- space ------------------------------------------------------------------

/** 0 mono .. 1 wide. */
uniform float uStereoWidth;
/** -1 left .. 1 right. */
uniform float uStereoPan;
/** 1 in phase .. -1 out of phase. */
uniform float uStereoCorrelation;

// ---- the bands against the song's usual level (#319) ------------------------

/** Bass, mid and treble against their own usual level: 0.5 is usual, 1 twice it; quick, never a step. */
uniform float uBassLevel;
uniform float uMidLevel;
uniform float uTrebleLevel;
/** A hit envelope, 0..1, when a band jumps well above its usual level. */
uniform float uBassHits;
uniform float uMidHits;
uniform float uTrebleHits;
/** The same reading as the level, followed over seconds: how much of the band the passage has. */
uniform float uBassPresence;
uniform float uMidPresence;
uniform float uTreblePresence;
