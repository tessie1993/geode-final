# Element asset pipeline

One command line, `element_assets.py`, turns an Element's prompt manifest into finished App UI assets, and ships only the ones the owner has approved (#104).

- **Prompt manifests:** `elements/<element>.json`, one per Element, with every slot: H0–H3, N1–N5, I1–I5, L1–L2, B1, S1, T1, F1–F9 (images), A1–A5 (sound FX) and V1–V6 (reference videos). An image prompt is the slot's subject, then the Element's style anchor, then the suffix for its backdrop (`isolated-white`, `isolated-black`, `tile` or `full-bleed`).
- **Working record:** `build/element-assets/<element>/`: the raw gflow output, the finished files, `record.json` and the contact sheet. `build/` is ignored by git, so none of it can be committed.
- **Shipped manifest:** `design/element-assets.json`: every asset in the app, each with its slot, model, prompt, seed, gflow media id, licence and the URL of the owner's approval.

## Setup

```
python -m pip install -r tools/element_assets/requirements.txt
```

Image work needs Pillow and NumPy; sounds need `ffmpeg` and `ffprobe` on PATH; `generate` needs `gflow`. `check` needs only Python.

## The workflow, in order

1. **Plan.** `plan water` prints every prompt and the gflow command that would make it.
2. **Generate.** `generate water --slots H1,H2 --go` runs gflow with `--json` into `raw/`. Without `--go` it is a dry run: every real run spends Flow credits. Images are `nano-pro`, 4 variants, in the Element's Flow project; once the Element has an approved anchor, they are `i2i` against it. Sounds are 4 s Veo `t2v` clips of a black screen (the A4 ambient loop is 8 s). Videos are 8 s, 9:16, `i2v` from their source slot or `t2v`. An image made outside the tool, such as the anchor after its `upscale`, is recorded with `adopt water H0 <file>`.
3. **Finish.** `finish water --pick H1=2,H2=1` takes the picked variant of each slot:
   - isolated images must have a flat white or black backdrop, or the run stops. White backdrops are matted by distance to white; black ones (glows and bubbles) are keyed on brightness so soft translucency survives. A sprite that is empty after matting stops the run. Sprites are trimmed with padding.
   - every image is exported as WebP at mdpi to xxxhdpi. Sprites are drawn at 4× their dp size, so the master is xxxhdpi.
   - sounds are cut to mono OGG: silence trimmed, faded, loudness-normalised. The A4 loop is crossfaded tail into head so it repeats without a seam.
   - videos are kept as motion references and never ship.
4. **Sheet.** `sheet water` writes `sheet.html`: every image over transparency, a dark ground and a light ground, the sounds and the videos, each with its slot, id, prompt and media id. Post it on the Element's asset ticket.
5. **Owner approval.** The owner approves the sheet in a comment on the Element's asset ticket (Water: #105).
6. **Mark approved.** `approve water --url <comment URL> [--slots ...]`.
7. **Ship.** `ship water` copies the approved slots into `design/src/main/res` (`drawable-<density>/element_<element>_<slot>.webp`, floating assets as `ambient_<element>_<name>.webp`, sounds as `raw/element_<element>_<slot>.ogg`) and updates `design/element-assets.json`.
8. **Report.** `report water` prints the Element's APK weight as a markdown table for the asset ticket.

## The check

`check` validates every prompt manifest and the shipped manifest: every slot present, valid fields, unique ids across all Elements, every entry approved on its Element's ticket, every file present at every density, no video shipped, and no `element_*` or `ambient_*` resource without an approved entry. `check --self-test` proves it still catches each of those. Both run in CI ("Element asset pipeline", `docs/ci.md`), with the unit tests:

```
python tools/element_assets/element_assets.py check --self-test
python tools/element_assets/element_assets.py check
python -m unittest discover -s tools/element_assets -p 'test_*.py' -v
```
