"""Unit tests for the Element asset pipeline (element_assets.py), on synthetic images in a temp folder.

gflow and ffmpeg are never run: generate takes a stub runner, and the sound commands are checked as
argument lists.

    python -m unittest discover -s tools/element_assets -p 'test_*.py' -v
"""

from __future__ import annotations

import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

import numpy as np
from PIL import Image

sys.path.insert(0, str(Path(__file__).resolve().parent))
import element_assets as ea  # noqa: E402

TEAL = (0.1, 0.5, 0.5)
APPROVAL = "https://github.com/tessie1993/geode-final/issues/105#issuecomment-42"


def flat(value: float, size: int = 64) -> np.ndarray:
    return np.full((size, size, 3), value, dtype=np.float32)


def disc(background: float, colour: tuple[float, float, float], size: int = 64, radius: int = 16) -> np.ndarray:
    rgb = flat(background, size)
    yy, xx = np.mgrid[:size, :size]
    rgb[(yy - size / 2) ** 2 + (xx - size / 2) ** 2 <= radius**2] = colour
    return rgb


def glow(size: int = 64) -> np.ndarray:
    yy, xx = np.mgrid[:size, :size]
    level = np.clip(1.0 - np.hypot(yy - size / 2, xx - size / 2) / (size / 2), 0.0, 1.0) * 0.8
    return np.dstack([level * 0.3, level, level * 0.9]).astype(np.float32)


def save_png(rgb: np.ndarray, path: Path) -> Path:
    path.parent.mkdir(parents=True, exist_ok=True)
    Image.fromarray(np.round(rgb * 255).astype(np.uint8)).save(path)
    return path


def sample_manifest(**changes: object) -> ea.ElementManifest:
    doc = ea.sample_element()
    doc.update(changes)
    return ea.parse_element(doc)


class PromptTest(unittest.TestCase):
    def test_an_image_prompt_is_the_subject_then_the_anchor_then_the_suffix(self) -> None:
        manifest = sample_manifest()
        spec = manifest.slots["H1"]
        self.assertEqual(
            ea.compose_prompt(manifest, spec),
            f"a test subject for H1, a calm test scene, {ea.SUFFIXES[ea.WHITE]}",
        )

    def test_each_backdrop_takes_its_own_suffix(self) -> None:
        manifest = sample_manifest()
        for slot, backdrop in (("H0", ea.FULL_BLEED), ("B1", ea.TILE), ("T1", ea.BLACK), ("N1", ea.WHITE)):
            with self.subTest(slot=slot):
                self.assertTrue(ea.compose_prompt(manifest, manifest.slots[slot]).endswith(ea.SUFFIXES[backdrop]))
        self.assertIn("pure black background", ea.SUFFIXES[ea.BLACK])

    def test_an_extra_clause_comes_last(self) -> None:
        doc = ea.sample_element()
        doc["slots"]["H0"]["extra"] = "leave open space"
        prompt = ea.compose_prompt(ea.parse_element(doc), ea.parse_element(doc).slots["H0"])
        self.assertTrue(prompt.endswith(f"{ea.SUFFIXES[ea.FULL_BLEED]}, leave open space"))

    def test_a_sound_prompt_is_the_black_screen_template(self) -> None:
        manifest = sample_manifest()
        prompt = ea.compose_prompt(manifest, manifest.slots["A1"])
        self.assertTrue(prompt.startswith("A completely black screen, nothing visible. Audio only: a soft tap. No music"))

    def test_images_are_text_to_image_until_the_anchor_is_approved(self) -> None:
        manifest = sample_manifest()
        argv = ea.gflow_command(manifest, manifest.slots["N1"], Path("raw"), ea.reference_for(manifest, manifest.slots["N1"], ea.Record("water"), Path("w")))
        self.assertEqual(argv[:3], ["gflow", "image", "t2i"])
        for flag, value in (("--model", "nano-pro"), ("-n", "4"), ("--aspect", "1:1"), ("--project", ea.TEST_UUID)):
            self.assertEqual(argv[argv.index(flag) + 1], value)
        self.assertIn("--json", argv)

    def test_after_the_anchor_images_are_made_from_it(self) -> None:
        anchor = {"slot": "H0", "mediaId": ea.TEST_UUID, "project": ea.TEST_UUID, "prompt": "p", "approval": APPROVAL}
        manifest = sample_manifest(anchor=anchor)
        ref = ea.reference_for(manifest, manifest.slots["N1"], ea.Record("water"), Path("w"))
        argv = ea.gflow_command(manifest, manifest.slots["N1"], Path("raw"), ref)
        self.assertEqual(argv[:3], ["gflow", "image", "i2i"])
        self.assertEqual(argv[argv.index("--ref") + 1], ea.TEST_UUID)
        self.assertIsNone(ea.reference_for(manifest, manifest.slots["H0"], ea.Record("water"), Path("w")))

    def test_sounds_and_videos_are_veo_clips(self) -> None:
        manifest = sample_manifest()
        sound = ea.gflow_command(manifest, manifest.slots["A1"], Path("raw"), None)
        self.assertEqual(sound[:3], ["gflow", "video", "t2v"])
        self.assertEqual(sound[sound.index("--duration") + 1], "4")
        video = ea.gflow_command(manifest, manifest.slots["V1"], Path("raw"), "start.png")
        self.assertEqual(video[:5], ["gflow", "video", "i2v", "--initial-frame", "start.png"])
        self.assertEqual((video[video.index("--duration") + 1], video[video.index("--aspect") + 1]), ("8", "9:16"))

    def test_floating_assets_take_the_ambient_name(self) -> None:
        manifest = sample_manifest()
        self.assertEqual(ea.resource_name("water", manifest.slots["F1"]), "ambient_water_mote_f1")
        self.assertEqual(ea.resource_name("water", manifest.slots["H1"]), "element_water_h1")
        self.assertEqual(ea.resource_name("water", manifest.slots["A4"]), "element_water_a4")

    def test_the_water_manifest_composes_every_image_prompt(self) -> None:
        manifest = ea.read_element(ea.Paths(ea.ROOT), "water")
        for spec in manifest.slots.values():
            if spec.kind == ea.IMAGE:
                with self.subTest(slot=spec.slot):
                    prompt = ea.compose_prompt(manifest, spec)
                    self.assertIn(manifest.style_anchor, prompt)
                    self.assertIn(ea.SUFFIXES[spec.backdrop], prompt)

    def test_picks_parse_and_reject_nonsense(self) -> None:
        self.assertEqual(ea.parse_picks("H0=2, n1=1"), {"H0": 2, "N1": 1})
        for bad in ("H0", "Z1=1", "H0=two"):
            with self.subTest(bad=bad), self.assertRaises(ea.PipelineError):
                ea.parse_picks(bad)


class GenerateTest(unittest.TestCase):
    def test_generate_records_each_variant_from_the_gflow_result(self) -> None:
        names = [f"0000000{n}-0000-4000-8000-000000000000" for n in range(1, 5)]

        def fake_gflow(argv: list[str]) -> subprocess.CompletedProcess[str]:
            out = Path(argv[argv.index("--out") + 1])
            for n, name in enumerate(names, 1):
                save_png(disc(1.0, TEAL), out / f"{name}_{n}.png")
            payload = [{"media_name": name, "seed": 100 + n, "model_name_type": None} for n, name in enumerate(names, 1)]
            return subprocess.CompletedProcess(argv, 0, json.dumps(payload), "")

        with tempfile.TemporaryDirectory() as tmp:
            paths = ea.Paths(Path(tmp))
            manifest = sample_manifest()
            record = ea.Record("water")
            variants = ea.generate_slot(paths, manifest, record, manifest.slots["N1"], None, fake_gflow)
            self.assertEqual([v.media_id for v in variants], names)
            self.assertEqual([v.seed for v in variants], [101, 102, 103, 104])
            self.assertEqual({v.model for v in variants}, {"nano-pro"})
            self.assertTrue(all(v.file.startswith("raw/N1/") for v in variants))
            self.assertEqual(len(record.slots["N1"].variants), 4)

    def test_a_failed_gflow_run_stops(self) -> None:
        def failing(argv: list[str]) -> subprocess.CompletedProcess[str]:
            return subprocess.CompletedProcess(argv, 10, json.dumps({"title": "WAF", "detail": "rejected"}), "")

        with tempfile.TemporaryDirectory() as tmp:
            manifest = sample_manifest()
            with self.assertRaisesRegex(ea.ToolFailed, "rejected"):
                ea.generate_slot(ea.Paths(Path(tmp)), manifest, ea.Record("water"), manifest.slots["N1"], None, failing)


class BackdropTest(unittest.TestCase):
    def test_a_flat_backdrop_passes(self) -> None:
        ea.check_backdrop(disc(1.0, TEAL), ea.WHITE, "white")
        ea.check_backdrop(disc(0.0, TEAL), ea.BLACK, "black")

    def test_the_wrong_colour_fails(self) -> None:
        with self.assertRaises(ea.NonUniformBackdrop):
            ea.check_backdrop(disc(0.0, TEAL), ea.WHITE, "black on white")

    def test_a_shaded_corner_fails(self) -> None:
        rgb = disc(1.0, TEAL)
        rgb[:8, :8] = 0.7
        with self.assertRaisesRegex(ea.NonUniformBackdrop, "top left"):
            ea.check_backdrop(rgb, ea.WHITE, "vignette")

    def test_a_gradient_fails(self) -> None:
        rgb = np.repeat(np.linspace(0.6, 1.0, 64, dtype=np.float32)[None, :, None], 64, axis=0).repeat(3, axis=2)
        with self.assertRaises(ea.NonUniformBackdrop):
            ea.check_backdrop(rgb, ea.WHITE, "gradient")


class MattingTest(unittest.TestCase):
    def test_white_matting_keeps_an_opaque_centre_and_clears_the_backdrop(self) -> None:
        rgba = ea.matte_white(disc(1.0, TEAL))
        self.assertAlmostEqual(float(rgba[32, 32, 3]), 1.0, places=5)
        np.testing.assert_allclose(rgba[32, 32, :3], TEAL, atol=1e-5)
        for y, x in ((0, 0), (0, 63), (63, 0), (63, 63)):
            self.assertEqual(float(rgba[y, x, 3]), 0.0)

    def test_black_matting_keeps_soft_translucency(self) -> None:
        rgba = ea.matte_black(glow())
        alpha = rgba[..., 3]
        self.assertGreater(int(((alpha > 0.1) & (alpha < 0.9)).sum()), 100)
        self.assertEqual(float(alpha[0, 0]), 0.0)
        lit = alpha > 0.2
        np.testing.assert_allclose(rgba[..., :3].max(axis=2)[lit], 1.0, atol=0.05)

    def test_an_empty_sprite_fails(self) -> None:
        with self.assertRaises(ea.EmptySprite):
            ea.check_not_empty(ea.matte_white(flat(1.0)), "blank")
        with self.assertRaises(ea.EmptySprite):
            ea.check_not_empty(ea.matte_black(flat(0.0)), "blank")

    def test_finishing_a_blank_image_fails(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            source = save_png(flat(1.0), Path(tmp) / "blank.png")
            with self.assertRaises(ea.EmptySprite):
                ea.finish_image(source, ea.WHITE, Path(tmp) / "out", "element_water_n1", "blank")

    def test_trim_crops_to_the_subject_with_padding(self) -> None:
        trimmed = ea.trim(ea.matte_white(disc(1.0, TEAL, radius=10)), "disc", padding=4)
        self.assertEqual(trimmed.shape[:2], (21 + 8, 21 + 8))
        self.assertEqual(float(trimmed[0, 0, 3]), 0.0)


class DensityTest(unittest.TestCase):
    def test_density_sizes_scale_down_from_the_xxxhdpi_master(self) -> None:
        self.assertEqual(
            ea.density_sizes(400, 200),
            {"mdpi": (100, 50), "hdpi": (150, 75), "xhdpi": (200, 100), "xxhdpi": (300, 150), "xxxhdpi": (400, 200)},
        )

    def test_finishing_writes_webp_with_alpha_at_every_density(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            source = save_png(disc(1.0, TEAL, size=256, radius=60), Path(tmp) / "pebble.png")
            written = ea.finish_image(source, ea.WHITE, Path(tmp) / "out", "element_water_n5", "pebble")
            self.assertEqual(set(written), set(ea.DENSITIES))
            with Image.open(written["xxxhdpi"]) as master:
                expected = ea.density_sizes(*master.size)
                self.assertEqual(master.mode, "RGBA")
                self.assertEqual(master.getpixel((0, 0))[3], 0)
            for density, path in written.items():
                self.assertEqual(path.parent.name, f"drawable-{density}")
                with Image.open(path) as image:
                    self.assertEqual(image.size, expected[density])

    def test_tiles_are_exported_without_matting(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            source = save_png(flat(1.0, size=128), Path(tmp) / "tile.png")
            written = ea.finish_image(source, ea.TILE, Path(tmp) / "out", "element_water_b1", "tile")
            with Image.open(written["xxxhdpi"]) as master:
                self.assertEqual(master.size, (128, 128))
            with Image.open(written["mdpi"]) as small:
                self.assertEqual(small.size, (32, 32))


class SoundCommandTest(unittest.TestCase):
    def test_a_one_shot_is_trimmed_faded_normalised_and_mono_ogg(self) -> None:
        argv = ea.one_shot_command(Path("in.mp4"), Path("out.ogg"))
        chain = argv[argv.index("-af") + 1]
        for part in ("silenceremove", "loudnorm", "afade=t=in"):
            self.assertIn(part, chain)
        self.assertEqual(argv[argv.index("-ac") + 1], "1")
        self.assertEqual(argv[argv.index("-c:a") + 1], "libvorbis")

    def test_the_loop_crossfades_its_tail_into_its_head(self) -> None:
        argv = ea.loop_command(Path("in.mp4"), Path("out.ogg"), duration=8.0, crossfade=1.5)
        graph = argv[argv.index("-filter_complex") + 1]
        self.assertIn("atrim=start=6.5", graph)
        self.assertIn("amix=inputs=2", graph)
        self.assertIn("concat=n=2", graph)
        with self.assertRaises(ea.PipelineError):
            ea.loop_command(Path("in.mp4"), Path("out.ogg"), duration=3.0, crossfade=1.5)


class ShippedManifestTest(unittest.TestCase):
    def run_check(self, entries: list[dict], files: list[str]) -> list[str]:
        with tempfile.TemporaryDirectory() as tmp:
            shipped = {"version": 1, "entries": entries}
            return ea.check(ea.write_check_tree(Path(tmp), {"water": ea.sample_element()}, shipped, files))

    def entry(self) -> dict:
        return ea.sample_entry("water", "H1", "element_water_h1", 105)

    def test_an_approved_entry_passes(self) -> None:
        entry = self.entry()
        self.assertEqual(self.run_check([entry], entry["files"]), [])

    def test_a_duplicate_id_fails(self) -> None:
        entry = self.entry()
        errors = self.run_check([entry, dict(entry)], entry["files"])
        self.assertTrue(any("used twice" in error for error in errors), errors)

    def test_a_missing_field_fails(self) -> None:
        entry = self.entry()
        del entry["licence"]
        errors = self.run_check([entry], entry["files"])
        self.assertTrue(any("missing fields ['licence']" in error for error in errors), errors)

    def test_a_null_seed_is_allowed_but_the_key_is_required(self) -> None:
        entry = self.entry()
        self.assertIsNone(entry["seed"])
        del entry["seed"]
        errors = self.run_check([entry], entry["files"])
        self.assertTrue(any("seed" in error for error in errors), errors)

    def test_an_unlisted_asset_fails(self) -> None:
        errors = self.run_check([], ["drawable-mdpi/element_water_n1.webp"])
        self.assertTrue(any("ships without an approved entry" in error for error in errors), errors)

    def test_the_repository_is_sound(self) -> None:
        self.assertEqual(ea.check(ea.Paths(ea.ROOT)), [])

    def test_the_self_test_catches_every_violation(self) -> None:
        self.assertEqual(ea.self_test(), 0)


class ShipTest(unittest.TestCase):
    def finished_slot(self, work: Path, name: str, approval: str) -> ea.SlotRecord:
        source = save_png(disc(1.0, TEAL, size=128, radius=40), work / "raw" / "x.png")
        written = ea.finish_image(source, ea.WHITE, work / "finished", name, name)
        variant = ea.Variant("raw/x.png", ea.TEST_UUID, None, "nano-pro", "a test prompt")
        finished = {density: path.relative_to(work).as_posix() for density, path in written.items()}
        return ea.SlotRecord([variant], 1, finished, approval)

    def test_ship_copies_only_approved_entries(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            paths = ea.Paths(Path(tmp))
            paths.elements.mkdir(parents=True)
            (paths.elements / "water.json").write_text(json.dumps(ea.sample_element()), encoding="utf-8")
            manifest = ea.read_element(paths, "water")
            work = paths.work("water")
            record = ea.Record("water", {
                "H1": self.finished_slot(work, "element_water_h1", APPROVAL),
                "H2": self.finished_slot(work, "element_water_h2", ""),
            })
            record.slots["V1"] = ea.SlotRecord([ea.Variant("raw/v.mp4", ea.TEST_UUID, None, "veo", "p")], 1, {"video": "raw/v.mp4"}, APPROVAL)

            self.assertEqual(ea.ship(paths, manifest, record), ["element_water_h1"])

            for density in ea.DENSITIES:
                self.assertTrue((paths.res / f"drawable-{density}" / "element_water_h1.webp").is_file())
                self.assertFalse((paths.res / f"drawable-{density}" / "element_water_h2.webp").exists())
            shipped = json.loads(paths.shipped.read_text(encoding="utf-8"))
            self.assertEqual([entry["id"] for entry in shipped["entries"]], ["element_water_h1"])
            self.assertEqual(shipped["entries"][0]["approval"], APPROVAL)
            self.assertEqual(ea.check(paths), [])
            rows = ea.apk_weight(paths, "water")
            self.assertEqual(len(rows), len(ea.DENSITIES))
            self.assertIn("**Total**", ea.report_markdown("water", rows))

    def test_approval_must_be_on_the_elements_ticket(self) -> None:
        manifest = sample_manifest()
        with self.assertRaises(ea.PipelineError):
            ea.approve(manifest, ea.Record("water"), "https://github.com/tessie1993/geode-final/issues/106#issuecomment-1", [])


if __name__ == "__main__":
    unittest.main()
