#!/usr/bin/env python3
"""Fail when an App UI screenshot shows glassmorphism, as judged by Claude looking at it.

The owner's rule (22 Oct 2026, #102): the App UI has no glass anywhere. The static check
(tools/ci/check_no_glass.py) catches live blur in the source; this one looks at what is drawn. It
sends the App UI screenshot goldens (app/src/test/screenshots and feature/*/ui/src/test/screenshots,
which the screenshots job proves match the code) to Claude with a strict rubric, in batches, and
asks for one structured verdict per image. Any "fail", and any image left without a verdict (an API
error, a refusal, a malformed answer), fails the job. Verdicts go to the job summary and failures
become annotations on the image files.

    python3 tools/ci/review_glass_screens.py                          # review every screenshot
    python3 tools/ci/review_glass_screens.py --changed-since <sha>    # only those changed since <sha>
    python3 tools/ci/review_glass_screens.py --self-test              # the parsing and verdict logic,
                                                                      # with canned responses, no API

The API key comes from ANTHROPIC_API_KEY (the repository secret of that name). Without it the job
fails and says so; it never passes unreviewed.
"""

from __future__ import annotations

import base64
import json
import os
import subprocess
import sys
import tempfile
from pathlib import Path
from types import SimpleNamespace
from typing import Any

MODEL = "claude-opus-5-5"
SCREENSHOT_DIRS = ("app/src/test/screenshots", "feature/*/ui/src/test/screenshots")
BATCH_SIZE = 20

RUBRIC = """\
You review screenshots of the App UI of Geode, an Android music player, for exactly one rule:
the App UI has no glass. No glassmorphism of any kind.

FAIL an image if any App UI surface (panel, card, sheet, dialog, bar, button, chip, orb) shows:
- a frosted or blurred translucent surface;
- a background or content visibly blurred through a surface;
- a smoked, tinted translucent bar or card that what lies behind it shows through;
- glass-card styling: a translucent fill with a light hairline rim or sheen imitating glass;
- a translucent glass orb or bubble used as a control.

PASS an image when its surfaces are:
- opaque, or textured and opaque (stone, wood, fabric and the like);
- gradient scrims (clear fading to dark) that let text read over content;
- natural-material art drawn as an image or object, such as sea glass, translucent stone pebbles,
  water or ice, as long as it is not a UI surface the background is seen through;
- the audio-reactive Visual itself (abstract animated graphics, which may be soft or blurry).

Judge only what is visible. A plain flat background is not glass. If a UI surface is translucent
and you can see something behind it through it, it fails; when in doubt about such a surface, fail.
Give one verdict for every image, using its path exactly as given, with a one-sentence reason that
names the surface and what you see."""

SCHEMA: dict[str, Any] = {
    "type": "object",
    "properties": {
        "verdicts": {
            "type": "array",
            "items": {
                "type": "object",
                "properties": {
                    "image": {"type": "string"},
                    "verdict": {"type": "string", "enum": ["pass", "fail"]},
                    "reason": {"type": "string"},
                },
                "required": ["image", "verdict", "reason"],
                "additionalProperties": False,
            },
        },
    },
    "required": ["verdicts"],
    "additionalProperties": False,
}

Result = tuple[str, str, str]  # (image path, "pass" | "fail" | "error", reason)


def all_screenshots(root: Path) -> list[str]:
    return sorted(p.relative_to(root).as_posix() for d in SCREENSHOT_DIRS for p in root.glob(f"{d}/*.png"))


def select(screenshots: list[str], changed: list[str] | None) -> list[str]:
    """Every screenshot, or only those in [changed] (added or modified in the pull request)."""
    return screenshots if changed is None else sorted(set(screenshots) & set(changed))


def changed_since(root: Path, base: str) -> list[str]:
    out = subprocess.run(["git", "diff", "--name-only", "--diff-filter=AMR", f"{base}...HEAD"],
                         cwd=root, check=True, capture_output=True, text=True).stdout
    return [line.strip() for line in out.splitlines() if line.strip()]


def request(root: Path, images: list[str]) -> dict[str, Any]:
    """The Messages API request for one batch: each image follows a text block naming its path."""
    content: list[dict[str, Any]] = []
    for image in images:
        data = base64.standard_b64encode((root / image).read_bytes()).decode("ascii")
        content.append({"type": "text", "text": f"Image: {image}"})
        content.append({"type": "image", "source": {"type": "base64", "media_type": "image/png", "data": data}})
    content.append({"type": "text", "text": f"Give a verdict for each of these {len(images)} images."})
    return {
        "model": MODEL,
        "max_tokens": 16000,
        "system": RUBRIC,
        "messages": [{"role": "user", "content": content}],
        # Claude Opus 5.5 takes no temperature; effort is its only control. Verdicts are structured.
        "output_config": {"effort": "high", "format": {"type": "json_schema", "schema": SCHEMA}},
        # On a safety decline the API re-runs the request on a fallback model, inside the same call.
        "betas": ["server-side-fallback-2026-07-01"],
        "fallbacks": "default",
    }


def parse_verdicts(message: Any, images: list[str]) -> list[Result]:
    """One result per image of the batch; anything missing, unknown or malformed is an error."""
    if message.stop_reason != "end_turn":
        why = f"the review stopped early ({message.stop_reason})"
        return [(image, "error", why) for image in images]
    text = "".join(block.text for block in message.content if getattr(block, "type", None) == "text")
    try:
        verdicts = json.loads(text)["verdicts"]
    except (ValueError, KeyError, TypeError) as error:
        return [(image, "error", f"the review's answer was not valid JSON ({error})") for image in images]
    found: dict[str, Result] = {}
    results: list[Result] = []
    for item in verdicts if isinstance(verdicts, list) else []:
        image = item.get("image") if isinstance(item, dict) else None
        verdict, reason = (item.get("verdict"), str(item.get("reason", ""))) if isinstance(item, dict) else (None, "")
        if image not in images:
            results.append((str(image), "error", "the review named an image it was not given"))
        elif image in found:
            found[image] = (image, "error", "the review gave this image more than one verdict")
        elif verdict not in ("pass", "fail"):
            found[image] = (image, "error", f"the review gave an unknown verdict {verdict!r}")
        else:
            found[image] = (image, verdict, reason)
    for image in images:
        results.append(found.get(image, (image, "error", "the review gave this image no verdict")))
    return results


def review(client: Any, root: Path, images: list[str], api_errors: tuple[type[BaseException], ...] = ()) -> list[Result]:
    results: list[Result] = []
    for start in range(0, len(images), BATCH_SIZE):
        batch = images[start:start + BATCH_SIZE]
        try:
            message = client.beta.messages.create(**request(root, batch))
        except api_errors as error:
            results += [(image, "error", f"the API call failed: {type(error).__name__}") for image in batch]
            continue
        results += parse_verdicts(message, batch)
    return results


def escape(text: str) -> str:
    return text.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")


def report(results: list[Result]) -> tuple[str, list[str], int]:
    """(job summary in Markdown, workflow annotations, exit code)."""
    fails = [r for r in results if r[1] == "fail"]
    errors = [r for r in results if r[1] == "error"]
    if not results:
        headline = "No App UI screenshots to review."
    elif fails or errors:
        headline = f"**{len(fails)} glass, {len(errors)} not reviewed** of {len(results)} App UI screenshots."
    else:
        headline = f"No glass in {len(results)} App UI screenshots."
    rows = [f"| `{image}` | {verdict} | {reason.replace('|', '/')} |" for image, verdict, reason in results]
    summary = "\n".join(["## No glass in the App UI (screens)", "", headline, ""]
                        + (["| Screenshot | Verdict | Reason |", "|---|---|---|", *rows] if rows else []))
    notes = [f"::error file={image},title=Glass in the App UI::{escape(reason)}" for image, _, reason in fails]
    notes += [f"::error file={image},title=Screenshot not reviewed::{escape(reason)}" for image, _, reason in errors]
    return summary, notes, 1 if fails or errors else 0


def self_test() -> int:
    """Canned API responses through the parser, the batching and the report; no network."""
    def msg(text: str, stop: str = "end_turn") -> SimpleNamespace:
        return SimpleNamespace(stop_reason=stop, content=[SimpleNamespace(type="text", text=text)])

    def answer(*verdicts: tuple[str, str]) -> str:
        return json.dumps({"verdicts": [{"image": i, "verdict": v, "reason": f"{i} {v}"} for i, v in verdicts]})

    imgs = ["a.png", "b.png"]
    failures: list[str] = []

    def expect(name: str, got: Any, want: Any) -> None:
        if got != want:
            failures.append(f"{name}: got {got!r}, want {want!r}")

    verdicts = lambda results: [(r[0], r[1]) for r in results]  # noqa: E731
    expect("pass and fail", verdicts(parse_verdicts(msg(answer(("a.png", "pass"), ("b.png", "fail"))), imgs)),
           [("a.png", "pass"), ("b.png", "fail")])
    expect("missing verdict", verdicts(parse_verdicts(msg(answer(("a.png", "pass"))), imgs)),
           [("a.png", "pass"), ("b.png", "error")])
    expect("unknown image", verdicts(parse_verdicts(msg(answer(("a.png", "pass"), ("b.png", "pass"), ("c.png", "pass"))),
                                                    imgs)), [("c.png", "error"), ("a.png", "pass"), ("b.png", "pass")])
    expect("duplicate", verdicts(parse_verdicts(msg(answer(("a.png", "pass"), ("a.png", "fail"), ("b.png", "pass"))),
                                                imgs)), [("a.png", "error"), ("b.png", "pass")])
    expect("bad verdict", verdicts(parse_verdicts(msg(answer(("a.png", "maybe"), ("b.png", "pass"))), imgs)),
           [("a.png", "error"), ("b.png", "pass")])
    expect("not JSON", verdicts(parse_verdicts(msg("Looks fine to me."), imgs)), [("a.png", "error"), ("b.png", "error")])
    expect("wrong shape", verdicts(parse_verdicts(msg('{"verdicts": "pass"}'), imgs)),
           [("a.png", "error"), ("b.png", "error")])
    expect("refusal", verdicts(parse_verdicts(msg("", "refusal"), imgs)), [("a.png", "error"), ("b.png", "error")])
    expect("cut off", verdicts(parse_verdicts(msg('{"verd', "max_tokens"), imgs)), [("a.png", "error"), ("b.png", "error")])

    class FakeError(Exception):
        pass

    class FakeClient:
        def __init__(self) -> None:
            self.calls: list[dict[str, Any]] = []
            self.beta = SimpleNamespace(messages=SimpleNamespace(create=self.create))

        def create(self, **kwargs: Any) -> SimpleNamespace:
            self.calls.append(kwargs)
            if len(self.calls) == 3:
                raise FakeError("boom")
            names = [b["text"].removeprefix("Image: ") for b in kwargs["messages"][0]["content"]
                     if b["type"] == "text" and b["text"].startswith("Image: ")]
            return msg(answer(*[(n, "fail" if n == "s07.png" else "pass") for n in names]))

    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        shots = [f"s{i:02}.png" for i in range(BATCH_SIZE * 2 + 1)]
        for name in shots:
            (root / name).write_bytes(b"\x89PNG fake")
        client = FakeClient()
        results = review(client, root, shots, (FakeError,))
    expect("batches", [sum(b["type"] == "image" for b in c["messages"][0]["content"]) for c in client.calls],
           [BATCH_SIZE, BATCH_SIZE, 1])
    first = client.calls[0]
    expect("model", first["model"], MODEL)
    expect("no sampling parameters", [k for k in ("temperature", "top_p", "top_k") if k in first], [])
    expect("structured verdicts", first["output_config"]["format"]["schema"], SCHEMA)
    expect("fallbacks", (first["fallbacks"], first["betas"]), ("default", ["server-side-fallback-2026-07-01"]))
    expect("results", [(r[0], r[1]) for r in results if r[1] != "pass"], [("s07.png", "fail"), ("s40.png", "error")])

    summary, notes, code = report(results)
    expect("exit on fail or error", code, 1)
    expect("annotations", [n.split("::")[1] for n in notes],
           ["error file=s07.png,title=Glass in the App UI", "error file=s40.png,title=Screenshot not reviewed"])
    expect("all pass", report([("a.png", "pass", "opaque")])[2], 0)
    expect("nothing changed", report([])[2], 0)
    expect("escaping", escape("50%\nnext"), "50%25%0Anext")
    expect("summary row", "| `s07.png` | fail | s07.png fail |" in summary, True)
    expect("select changed", select(["a.png", "b.png"], ["b.png", "x.kt"]), ["b.png"])
    expect("select all", select(["a.png", "b.png"], None), ["a.png", "b.png"])

    expect("missing key", missing_key({}) is not None and missing_key({"ANTHROPIC_API_KEY": ""}) is not None, True)
    expect("key present", missing_key({"ANTHROPIC_API_KEY": "x"}), None)

    for failure in failures:
        print(f"FAILED: {failure}")
    print("self-test " + ("FAILED" if failures else "passed"))
    return 1 if failures else 0


def missing_key(env: Any) -> str | None:
    """Why the review cannot run, or None. Only presence is checked; the key is never printed."""
    if env.get("ANTHROPIC_API_KEY"):
        return None
    return ("The glass review needs the repository secret ANTHROPIC_API_KEY. Add it under Settings > "
            "Secrets and variables > Actions, then re-run this job. Without it the App UI screenshots "
            "are not reviewed, so the check fails.")


def main(argv: list[str]) -> int:
    if "--self-test" in argv:
        return self_test()
    problem = missing_key(os.environ)
    if problem:
        print(f"::error title=ANTHROPIC_API_KEY is missing::{problem}")
        return 1
    root = Path(__file__).resolve().parents[2]
    base = argv[argv.index("--changed-since") + 1] if "--changed-since" in argv else None
    images = select(all_screenshots(root), changed_since(root, base) if base else None)
    import anthropic  # only here, so the self-test runs without the SDK
    client = anthropic.Anthropic()
    results = review(client, root, images, (anthropic.APIStatusError, anthropic.APIConnectionError))
    summary, notes, code = report(results)
    print(summary)
    for note in notes:
        print(note)
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a", encoding="utf-8") as out:
            out.write(summary + "\n")
    return code


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
