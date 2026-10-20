# Export may use whole-song analysis; live stays purely reactive

The live visualizer reacts only to audio already heard: every Sound signal is computed from the recent past, with no song scan and no assumptions about genre or structure. Video export is allowed to use a whole-song analysis for better-timed Reactions. Exported videos may therefore differ from what the user saw live, which reverses the earlier "what you export matches what you see" promise (CHANGELOG v0.9.4). We accepted that because export quality matters more than exact parity, and live has no future audio to look at anyway.

## Consequences

- Reactions must be defined over Sound signals that both a live and a whole-song source can produce, so one Customization drives both.
- The safety clamp still runs on every exported frame; only the signal source differs.
