#!/usr/bin/env python3
"""Helper script to crop, frame, and export 300 DPI A4 children's coloring pages to PDF & PNG."""

import argparse
import sys
from PIL import Image, ImageDraw


def crop_to_a4_ratio(
    image_path: str, output_path: str, cx: float = 0.5, cy: float = 0.5, scale: float = 1.0
):
  im = Image.open(image_path)
  w, h = im.size
  # Target A4 ratio: 210 / 297 ≈ 0.7071
  a4_ratio = 210.0 / 297.0

  if w / h > a4_ratio:
    target_h = int(h * scale)
    target_w = int(target_h * a4_ratio)
  else:
    target_w = int(w * scale)
    target_h = int(target_w / a4_ratio)

  center_x = int(w * cx)
  center_y = int(h * cy)
  x0 = max(0, min(w - target_w, center_x - target_w // 2))
  y0 = max(0, min(h - target_h, center_y - target_h // 2))

  cropped = im.crop((x0, y0, x0 + target_w, y0 + target_h))
  cropped.convert("RGB").save(output_path, quality=95)
  print(f"Saved cropped image to {output_path} ({target_w}x{target_h})")
  return output_path


def format_a4_page(
    image_path: str,
    output_pdf: str,
    output_png: str = None,
    with_frame: bool = True,
    border_px: int = 8,
):
  src = Image.open(image_path).convert("L")

  A4_W, A4_H = 2480, 3508
  # Content area with standard margins
  target_w = 2160
  target_h = int(target_w * (src.height / src.width))
  if target_h > 3100:
    target_h = 3100
    target_w = int(target_h * (src.width / src.height))

  # Resample and clean line contrast
  scaled = src.resize((target_w, target_h), Image.Resampling.LANCZOS)
  cleaned = scaled.point(
      lambda p: 255
      if p > 220
      else (0 if p < 80 else int((p - 80) * 255 / (220 - 80)))
  )

  canvas = Image.new("RGB", (A4_W, A4_H), color=(255, 255, 255))
  offset_x = (A4_W - target_w) // 2
  offset_y = (A4_H - target_h) // 2
  canvas.paste(cleaned.convert("RGB"), (offset_x, offset_y))

  if with_frame:
    draw = ImageDraw.Draw(canvas)
    x0, y0 = offset_x, offset_y
    x1, y1 = x0 + target_w, y0 + target_h
    for i in range(border_px):
      draw.rectangle([x0 - i, y0 - i, x1 + i, y1 + i], outline=(0, 0, 0))

  canvas.save(output_pdf, "PDF", resolution=300.0)
  print(f"Saved A4 PDF: {output_pdf}")
  if output_png:
    canvas.save(output_png, dpi=(300, 300))
    print(f"Saved A4 PNG: {output_png}")


if __name__ == "__main__":
  parser = argparse.ArgumentParser(
      description="Format coloring page into A4 PDF/PNG."
  )
  parser.add_argument("input_image", help="Input image path")
  parser.add_argument("--pdf", required=True, help="Output PDF path")
  parser.add_argument("--png", help="Output PNG path")
  parser.add_argument(
      "--no-frame", action="store_true", help="Omit outer drawing-book frame"
  )
  args = parser.parse_args()

  format_a4_page(
      args.input_image, args.pdf, args.png, with_frame=not args.no_frame
  )
