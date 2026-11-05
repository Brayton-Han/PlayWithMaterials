#!/usr/bin/env python3
"""T-021: generate seed covers, preset result images and demo upload samples.

Every image is drawn programmatically by this script (no third-party artwork),
so the output is original work released under CC0 1.0 (see README "素材来源").

Usage (requires Pillow, only needed when regenerating):
    python3 scripts/gen-seed-assets.py

Outputs:
    backend/src/main/resources/seed/covers/{id}.jpg   template covers (public)
    backend/src/main/resources/seed/results/{id}.png  preset results copied by RESULT_ORGANIZING (OQ-08)
    samples/*                                         files for demoing upload validation
"""
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parent.parent
SEED = ROOT / "backend/src/main/resources/seed"
SAMPLES = ROOT / "samples"

# Must match cover_url / sample_result_path in V2__seed_data.sql.
TEMPLATES = {
    "postcard": ("POSTCARD", (255, 183, 77), (229, 57, 53)),
    "poster": ("POSTER", (126, 87, 194), (0, 188, 212)),
    "id-photo": ("ID PHOTO", (144, 202, 249), (25, 118, 210)),
    "collage": ("COLLAGE", (255, 138, 128), (255, 214, 0)),
}


def font(size):
    return ImageFont.load_default(size)


def gradient(w, h, top, bottom):
    img = Image.new("RGB", (w, h))
    draw = ImageDraw.Draw(img)
    for y in range(h):
        t = y / max(h - 1, 1)
        draw.line([(0, y), (w, y)], fill=tuple(round(a + (b - a) * t) for a, b in zip(top, bottom)))
    return img


def label(img, text, sub=None):
    draw = ImageDraw.Draw(img)
    w, h = img.size
    draw.text((w / 2, h / 2), text, font=font(h // 9), fill="white", anchor="mm",
              stroke_width=max(h // 160, 2), stroke_fill=(0, 0, 0, 96))
    if sub:
        draw.text((w / 2, h / 2 + h // 8), sub, font=font(h // 22), fill="white", anchor="mm")
    return img


def shapes(img, seed):
    draw = ImageDraw.Draw(img, "RGBA")
    w, h = img.size
    for i in range(6):
        r = (h // 10) * (1 + (i + seed) % 3)
        cx = (w * (i * 37 + seed * 13) // 211) % w
        cy = (h * (i * 53 + seed * 7) // 97) % h
        draw.ellipse([cx - r, cy - r, cx + r, cy + r], fill=(255, 255, 255, 40))
    return img


def main():
    (SEED / "covers").mkdir(parents=True, exist_ok=True)
    (SEED / "results").mkdir(parents=True, exist_ok=True)
    SAMPLES.mkdir(exist_ok=True)

    for i, (tid, (name, c1, c2)) in enumerate(TEMPLATES.items()):
        cover = label(shapes(gradient(800, 600, c1, c2), i), name, "xiaoying template")
        cover.save(SEED / "covers" / f"{tid}.jpg", "JPEG", quality=85, optimize=True)
        result = label(shapes(gradient(1024, 768, c2, c1), i + 5), name, "simulated result")
        ImageDraw.Draw(result).rectangle([16, 16, 1007, 751], outline="white", width=8)
        result.save(SEED / "results" / f"{tid}.png", "PNG", optimize=True)

    # Valid uploads (all well under 1 MB so they pass every template's limit).
    palette = [((102, 187, 106), (27, 94, 32)), ((79, 195, 247), (1, 87, 155)), ((255, 202, 40), (230, 81, 0))]
    for n, (c1, c2) in enumerate(palette, start=1):
        img = label(shapes(gradient(640, 480, c1, c2), n), f"SAMPLE {n}")
        img.save(SAMPLES / f"sample-{n}.jpg", "JPEG", quality=85)
        img.save(SAMPLES / f"sample-{n}.png", "PNG", optimize=True)
    label(gradient(640, 480, (240, 98, 146), (136, 14, 79)), "WEBP").save(SAMPLES / "sample.webp", "WEBP", quality=80)

    # Valid PNG between 1 MB and 5 MB: rejected only by portrait-id-photo (1 MB limit).
    # Valid PNG over 5 MB: rejected by every template. Uncompressed so the size is deterministic.
    label(gradient(800, 600, (161, 136, 127), (62, 39, 35)), "1.4 MB PNG").save(
        SAMPLES / "over-1mb.png", "PNG", compress_level=0)
    label(gradient(1600, 1200, (120, 144, 156), (38, 50, 56)), "5.8 MB PNG").save(
        SAMPLES / "over-5mb.png", "PNG", compress_level=0)

    # Text file disguised as an image: extension says jpg, magic bytes say otherwise.
    (SAMPLES / "fake-image.jpg").write_text(
        "This is a plain text file renamed to .jpg.\n"
        "The backend must reject it by magic-byte detection (FILE_TYPE_NOT_ACCEPTED).\n",
        encoding="utf-8")

    for p in sorted([*SEED.rglob("*.*"), *SAMPLES.glob("*.*")]):
        if p.suffix != ".md":
            print(f"{p.stat().st_size:>10,}  {p.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
