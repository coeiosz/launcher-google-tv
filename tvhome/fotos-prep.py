"""Prepares screensaver photos: a 1920x1080 JPEG per photo, cropped around the faces when possible.

  py fotos-prep.py <pasta_fotos> <pasta_saida> [--preview]

- Widescreen photos fill the screen (crop centred on the faces, if any).
- Taller photos are cropped to 16:9 around the detected faces when that keeps every face with
  some headroom; otherwise they stay whole, centred over a blurred copy of themselves.
- cortes.json next to the photos can override per file: {"foto.jpg": "inteira"} or {"foto.jpg": 0.3}
  (0.3 = vertical position of the crop, 0 = top, 1 = bottom).
With --preview it also writes a contact sheet (_previa.jpg) to check the results.
"""
import json
import os
import sys

# Pillow and opencv-python-headless are installed here (pip install --target), plus the YuNet model.
sys.path.insert(0, os.path.join(os.environ["LOCALAPPDATA"], "tvhome-py"))

import cv2
import numpy as np
from PIL import Image, ImageFilter, ImageOps

W, H = 1920, 1080
TARGET = W / H
# Fraction of the photo height a 16:9 crop may keep and still count as a crop worth making.
MIN_KEEP = 0.40
MODEL = os.path.join(os.path.dirname(cv2.__file__), "..", "face_detection_yunet_2023mar.onnx")


def faces(img: Image.Image):
    """Face boxes (x, y, w, h) in image pixels, detected on a ~1000 px copy."""
    scale = 1000 / max(img.size)
    small = img.resize((round(img.width * scale), round(img.height * scale)))
    bgr = cv2.cvtColor(np.asarray(small.convert("RGB")), cv2.COLOR_RGB2BGR)
    det = cv2.FaceDetectorYN.create(MODEL, "", (small.width, small.height), 0.8, 0.3)
    _, found = det.detect(bgr)
    boxes = []
    for f in [] if found is None else found:
        x, y, w, h = (float(v) / scale for v in f[:4])
        if w >= img.width * 0.04:  # ignore tiny background faces
            boxes.append((x, y, w, h))
    return boxes


def crop_box(img: Image.Image, boxes, override):
    """Region to scale to 16:9, or None to show the photo whole."""
    iw, ih = img.size
    if override == "inteira":
        return None
    if iw / ih >= TARGET:  # wider than the screen: crop the sides
        cw = ih * TARGET
        cx = iw / 2
        if boxes:
            cx = (min(b[0] for b in boxes) + max(b[0] + b[2] for b in boxes)) / 2
        left = min(max(cx - cw / 2, 0), iw - cw)
        return (left, 0, left + cw, ih)

    ch = iw / TARGET  # full width, cut top and bottom
    if isinstance(override, (int, float)):
        top = (ih - ch) * float(override)
        return (0, top, iw, top + ch)
    if ch / ih >= 0.72:  # 4:3 and similar: little is lost even without faces
        if not boxes:
            top = (ih - ch) * 0.4
            return (0, top, iw, top + ch)
    elif not boxes or ch / ih < MIN_KEEP:
        return None
    # Keep every face plus hair above and chin/neck below; faces sit in the upper part.
    top_need = min(b[1] - b[3] * 0.45 for b in boxes)
    bottom_need = max(b[1] + b[3] * 1.35 for b in boxes)
    if bottom_need - top_need > ch:
        return None
    face_mid = (top_need + bottom_need) / 2
    top = face_mid - ch * 0.42
    top = min(max(top, 0), ih - ch)
    top = min(top, top_need)  # never cut a head off
    return (0, top, iw, top + ch)


def render(img: Image.Image, box) -> Image.Image:
    if box is not None:
        return img.crop(tuple(round(v) for v in box)).resize((W, H), Image.LANCZOS)
    # Whole photo at 90% height over a blurred, darkened copy of itself.
    bg = ImageOps.fit(img, (W // 8, H // 8)).filter(ImageFilter.GaussianBlur(3)).resize((W, H), Image.BICUBIC)
    bg = Image.blend(bg, Image.new("RGB", (W, H)), 0.45)
    s = min(H * 0.9 / img.height, W * 0.9 / img.width)
    fg = img.resize((round(img.width * s), round(img.height * s)), Image.LANCZOS)
    x, y = (W - fg.width) // 2, (H - fg.height) // 2
    shadow = Image.new("L", (W, H), 0)
    shadow.paste(110, (x - 4, y + 4, x + fg.width + 4, y + fg.height + 10))
    shadow = shadow.filter(ImageFilter.GaussianBlur(14))
    bg.paste(Image.new("RGB", (W, H)), (0, 0), shadow)
    bg.paste(fg, (x, y))
    return bg


def main():
    src, out = sys.argv[1], sys.argv[2]
    preview = "--preview" in sys.argv
    overrides = {}
    cfg = os.path.join(src, "cortes.json")
    if os.path.exists(cfg):
        with open(cfg, encoding="utf-8") as f:
            overrides = json.load(f)
    os.makedirs(out, exist_ok=True)
    thumbs, n = [], 0
    for name in sorted(os.listdir(src)):
        if not name.lower().endswith((".jpg", ".jpeg", ".png", ".bmp", ".webp")):
            continue
        try:
            img = ImageOps.exif_transpose(Image.open(os.path.join(src, name))).convert("RGB")
        except OSError:
            print(f"ignorada (não abre): {name}")
            continue
        boxes = faces(img)
        box = crop_box(img, boxes, overrides.get(name))
        result = render(img, box)
        n += 1
        result.save(os.path.join(out, f"foto_{n:04d}.jpg"), quality=88)
        mode = "inteira" if box is None else "cortada"
        print(f"{name}: {len(boxes)} rosto(s), {mode}")
        if preview:
            thumbs.append(result.resize((480, 270)))
    if preview and thumbs:
        cols = 4
        sheet = Image.new("RGB", (cols * 484, ((len(thumbs) + cols - 1) // cols) * 274), (20, 20, 20))
        for i, t in enumerate(thumbs):
            sheet.paste(t, ((i % cols) * 484 + 2, (i // cols) * 274 + 2))
        sheet.save(os.path.join(out, "_previa.jpg"), quality=85)
    print(f"{n} foto(s) preparadas")


if __name__ == "__main__":
    main()
