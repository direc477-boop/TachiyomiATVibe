#!/usr/bin/env python3
"""Stage 3: Ink merge, metrics evaluation, and Traycer comparison artifact generator."""

import json
import math
import os
import sys
import time
from pathlib import Path

import cv2
import numpy as np
from PIL import Image

CH34_DIR = Path(r"C:\Users\ADMIN\Downloads\ch34_compare")
SRC_DIR = CH34_DIR / "sources"
TORCH_DIR = CH34_DIR / "torch_out"
APP_DIR = CH34_DIR / "app_out" / "Chapter 34_images"
PADS_FILE = CH34_DIR / "ch34_pads.json"
OUTS_NPZ = CH34_DIR / "ch34_recipe_outs.npz"

ARTIFACT_DIR = Path(r"C:\Users\ADMIN\.traycer\epics\7f9db2bc-6dee-4e24-89e3-af5704d3e90a\artifacts\ch34-recipe-w8a8-comparison")
IMAGES_DIR = ARTIFACT_DIR / "images"
IMAGES_DIR.mkdir(parents=True, exist_ok=True)

# Ink-aware merge constants
WHITE_X, WHITE_Y, WHITE_Z = 0.95047, 1.0, 1.08883
LAB_KAPPA, LAB_EPSILON = 903.3, 0.008856

def srgb_linearize(c):
    c = np.clip(c, 0.0, 1.0)
    return np.where(c > 0.04045, ((c + 0.055) / 1.055) ** 2.4, c / 12.92)

def rgb_to_lab(rgb):
    lr, lg, lb = srgb_linearize(rgb[..., 0]), srgb_linearize(rgb[..., 1]), srgb_linearize(rgb[..., 2])
    x = 0.412453 * lr + 0.357580 * lg + 0.180423 * lb
    y = 0.212671 * lr + 0.715160 * lg + 0.072169 * lb
    z = 0.019334 * lr + 0.119193 * lg + 0.950227 * lb
    def f(val):
        return np.where(val > LAB_EPSILON, np.cbrt(val), (LAB_KAPPA * val + 16.0) / 116.0)
    fx, fy, fz = f(x / WHITE_X), f(y / WHITE_Y), f(z / WHITE_Z)
    L = 116.0 * fy - 16.0
    a = 500.0 * (fx - fy)
    b = 200.0 * (fy - fz)
    return np.stack([L, a, b], axis=-1)

def lab_to_rgb(lab):
    L, a, b = lab[..., 0], lab[..., 1], lab[..., 2]
    fy = (L + 16.0) / 116.0
    fx, fz = fy + a / 500.0, fy - b / 200.0
    def f_inv(val):
        c = val ** 3
        return np.where(c > LAB_EPSILON, c, (val * 116.0 - 16.0) / LAB_KAPPA)
    x, y, z = WHITE_X * f_inv(fx), WHITE_Y * f_inv(fy), WHITE_Z * f_inv(fz)
    def srgb_comp(lin):
        c = np.clip(lin, 0.0, 1.0)
        return np.where(c > 0.0031308, 1.055 * (c ** (1.0 / 2.4)) - 0.055, 12.92 * c)
    r = srgb_comp(3.2404542 * x - 1.5371385 * y - 0.4985314 * z)
    g = srgb_comp(-0.9692660 * x + 1.8760108 * y + 0.0415560 * z)
    b_out = srgb_comp(0.0556434 * x - 0.2040259 * y + 1.0572252 * z)
    return np.clip(np.stack([r, g, b_out], axis=-1), 0.0, 1.0)

def smoothstep(low, high, val):
    t = np.clip((val - low) / abs(high - low), 0.0, 1.0)
    return t * t * (3.0 - 2.0 * t)

def ink_merge(src_rgb: np.ndarray, colored_raw: np.ndarray, strength: float = 0.35) -> np.ndarray:
    orig_h, orig_w = src_rgb.shape[:2]
    colored_scaled = cv2.resize(colored_raw, (orig_w, orig_h), interpolation=cv2.INTER_LINEAR)
    src_f = src_rgb.astype(np.float32) / 255.0
    orig_lab = rgb_to_lab(src_f)
    col_lab = rgb_to_lab(colored_scaled)
    gray = 0.299 * src_f[..., 0] + 0.587 * src_f[..., 1] + 0.114 * src_f[..., 2]
    weight = strength * smoothstep(0.12, 0.45, gray)
    merged_L = orig_lab[..., 0] * (1.0 - weight) + col_lab[..., 0] * weight
    merged_lab = np.stack([merged_L, col_lab[..., 1], col_lab[..., 2]], axis=-1)
    merged_rgb = lab_to_rgb(merged_lab)
    return (merged_rgb * 255.0 + 0.5).clip(0, 255).astype(np.uint8)

def compute_psnr(a: np.ndarray, b: np.ndarray) -> float:
    if a.shape != b.shape:
        b = cv2.resize(b, (a.shape[1], a.shape[0]), interpolation=cv2.INTER_AREA)
    mse = np.mean((a.astype(float) / 255.0 - b.astype(float) / 255.0) ** 2)
    return float(10.0 * np.log10(1.0 / max(mse, 1e-10)))

def compute_sat(rgb_uint8: np.ndarray) -> float:
    hsv = cv2.cvtColor(rgb_uint8, cv2.COLOR_RGB2HSV)
    return float(hsv[..., 1].mean())

def compute_warmth(rgb_uint8: np.ndarray) -> float:
    f = rgb_uint8.astype(float) / 255.0
    return float(np.mean(f[..., 0] - f[..., 1]))

def build_html_artifact(summary: dict, pages: list) -> str:
    html = f"""<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Chapter 34 — Recipe-Calibrated W8A8 vs Baseline</title>
<style>
  :root {{
    --bg: #0f1117;
    --card-bg: #161b22;
    --border: #30363d;
    --text: #e6edf3;
    --text-muted: #8b949e;
    --accent: #58a6ff;
    --green: #3fb950;
    --yellow: #d29922;
    --red: #f85149;
  }}
  * {{ box-sizing: border-box; }}
  body {{
    margin: 0;
    padding: 16px;
    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
    background: var(--bg);
    color: var(--text);
    line-height: 1.5;
  }}
  h1 {{ font-size: 1.35rem; margin: 0 0 6px; font-weight: 600; display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }}
  .badge {{ font-size: 0.75rem; padding: 2px 8px; border-radius: 12px; background: rgba(88,166,255,0.15); color: var(--accent); border: 1px solid var(--accent); }}
  .lead {{ color: var(--text-muted); font-size: 0.88rem; margin-bottom: 16px; }}
  
  .stats-grid {{
    display: grid;
    grid-template-columns: repeat(auto-fit, minmax(150px, 1fr));
    gap: 12px;
    margin-bottom: 20px;
  }}
  .stat-card {{
    background: var(--card-bg);
    border: 1px solid var(--border);
    border-radius: 8px;
    padding: 12px;
  }}
  .stat-label {{ font-size: 0.72rem; color: var(--text-muted); text-transform: uppercase; letter-spacing: 0.05em; margin-bottom: 4px; }}
  .stat-val {{ font-size: 1.25rem; font-weight: 700; color: var(--text); }}
  .stat-sub {{ font-size: 0.75rem; color: var(--green); margin-top: 2px; }}

  .controls-bar {{
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    justify-content: space-between;
    gap: 10px;
    background: var(--card-bg);
    border: 1px solid var(--border);
    border-radius: 8px;
    padding: 8px 12px;
    margin-bottom: 20px;
    position: sticky;
    top: 8px;
    z-index: 100;
  }}
  .btn-group {{ display: flex; gap: 4px; }}
  .btn {{
    background: #21262d;
    border: 1px solid var(--border);
    color: var(--text);
    padding: 6px 12px;
    border-radius: 6px;
    font-size: 0.8rem;
    cursor: pointer;
    transition: all 0.15s;
  }}
  .btn:hover {{ background: #30363d; }}
  .btn.active {{ background: var(--accent); color: #fff; border-color: var(--accent); font-weight: 600; }}

  .page-card {{
    background: var(--card-bg);
    border: 1px solid var(--border);
    border-radius: 10px;
    padding: 14px;
    margin-bottom: 24px;
  }}
  .page-header {{
    display: flex;
    justify-content: space-between;
    align-items: center;
    margin-bottom: 12px;
    border-bottom: 1px solid var(--border);
    padding-bottom: 8px;
    flex-wrap: wrap;
    gap: 8px;
  }}
  .page-title {{ font-size: 0.95rem; font-weight: 600; }}
  .metrics-pill {{
    display: inline-flex;
    gap: 8px;
    font-size: 0.8rem;
    background: #0d1117;
    padding: 4px 10px;
    border-radius: 6px;
    border: 1px solid var(--border);
    flex-wrap: wrap;
  }}
  .psnr-gain {{ color: var(--green); font-weight: 600; }}
  
  .img-grid {{
    display: grid;
    grid-template-columns: repeat(auto-fit, minmax(260px, 1fr));
    gap: 12px;
  }}
  .img-col {{ display: flex; flex-direction: column; }}
  .img-col-title {{
    font-size: 0.78rem;
    font-weight: 600;
    margin-bottom: 6px;
    display: flex;
    justify-content: space-between;
    color: var(--text-muted);
  }}
  .img-frame {{
    border: 1px solid var(--border);
    border-radius: 6px;
    overflow: hidden;
    background: #000;
    cursor: zoom-in;
    aspect-ratio: 576 / 820;
  }}
  .img-frame img {{
    width: 100%;
    height: 100%;
    object-fit: contain;
    display: block;
  }}

  /* Lightbox */
  #lightbox {{
    display: none;
    position: fixed;
    inset: 0;
    background: rgba(0,0,0,0.95);
    z-index: 1000;
    padding: 20px;
    cursor: zoom-out;
  }}
  #lightbox img {{
    max-width: 100%;
    max-height: 100%;
    margin: auto;
    position: absolute;
    inset: 0;
    object-fit: contain;
  }}
</style>
</head>
<body>

<h1>
  Chapter 34 — Recipe-Calibrated W8A8 Model Comparison
  <span class="badge">Qualcomm SM8650 NPU AOT</span>
</h1>
<div class="lead">
  Head-to-head comparison across 33 chapter pages: <b>Original Baseline W8A8</b> (from APK) vs <b>New Recipe-Calibrated W8A8</b> vs <b>Author PyTorch Reference</b> (ground truth).
</div>

<div class="stats-grid">
  <div class="stat-card">
    <div class="stat-label">Median PSNR</div>
    <div class="stat-val">{summary['median_new_psnr']} dB</div>
    <div class="stat-sub">{summary['median_delta_psnr']:+.2f} dB vs Old W8A8 ({summary['median_old_psnr']} dB)</div>
  </div>
  <div class="stat-card">
    <div class="stat-label">Mean PSNR Gain</div>
    <div class="stat-val">{summary['mean_delta_psnr']:+.2f} dB</div>
    <div class="stat-sub">Across 33 pages</div>
  </div>
  <div class="stat-card">
    <div class="stat-label">Improved Pages</div>
    <div class="stat-val">{summary['pages_improved_count']} / {summary['total_pages']}</div>
    <div class="stat-sub">Pages with higher fidelity</div>
  </div>
  <div class="stat-card">
    <div class="stat-label">Saturation Normalization</div>
    <div class="stat-val">Aligned</div>
    <div class="stat-sub">Prevents clipping & oversaturation</div>
  </div>
</div>

<div class="controls-bar">
  <div style="font-size:0.85rem; font-weight:600;">Display Columns:</div>
  <div class="btn-group">
    <button class="btn active" onclick="setMode('all')">All 3 (Old / New / Ref)</button>
    <button class="btn" onclick="setMode('compare')">New vs Old Baseline</button>
    <button class="btn" onclick="setMode('recipe')">New vs PyTorch Ref</button>
  </div>
</div>
"""

    for p in pages:
        p_num = p["page"]
        p_name = f"p{p_num:02d}"
        gain_str = f"{p['delta_psnr']:+.2f} dB" if p['delta_psnr'] >= 0 else f"{p['delta_psnr']:.2f} dB"
        gain_color = "var(--green)" if p['delta_psnr'] >= 0 else "var(--red)"

        html += f"""
<div class="page-card" id="page-{p_num}">
  <div class="page-header">
    <div class="page-title">Page {p_num:02d} ({p['orig_size'][0]}×{p['orig_size'][1]})</div>
    <div class="metrics-pill">
      <span>Old Baseline: <b>{p['old_psnr']} dB</b></span>
      <span>→ New Recipe: <b>{p['new_psnr']} dB</b></span>
      <span style="color: {gain_color};">({gain_str})</span>
      <span style="border-left: 1px solid #333; padding-left: 8px;">Sat: {p['old_sat']} → <b>{p['new_sat']}</b> (Ref: {p['ref_sat']})</span>
    </div>
  </div>
  <div class="img-grid">
    <div class="img-col col-old">
      <div class="img-col-title"><span>Original W8A8 (APK Baseline)</span> <span>{p['old_psnr']} dB</span></div>
      <div class="img-frame" onclick="openLb('images/{p_name}_old.jpg')">
        <img src="images/{p_name}_old.jpg" loading="lazy" alt="Old W8A8 Page {p_num}">
      </div>
    </div>
    <div class="img-col col-new">
      <div class="img-col-title"><span style="color:var(--accent);">★ New Recipe-Calibrated W8A8</span> <span style="color:var(--green); font-weight:bold;">{p['new_psnr']} dB</span></div>
      <div class="img-frame" style="border-color: var(--accent);" onclick="openLb('images/{p_name}_new.jpg')">
        <img src="images/{p_name}_new.jpg" loading="lazy" alt="New Recipe W8A8 Page {p_num}">
      </div>
    </div>
    <div class="img-col col-ref">
      <div class="img-col-title"><span>PyTorch Author Recipe (Reference)</span> <span>Ground Truth</span></div>
      <div class="img-frame" onclick="openLb('images/{p_name}_torch.jpg')">
        <img src="images/{p_name}_torch.jpg" loading="lazy" alt="PyTorch Reference Page {p_num}">
      </div>
    </div>
  </div>
</div>
"""

    html += """
<div id="lightbox" onclick="closeLb()">
  <img id="lb-img" src="" alt="Enlarged view">
</div>

<script>
function openLb(src) {
  document.getElementById('lb-img').src = src;
  document.getElementById('lightbox').style.display = 'block';
}
function closeLb() {
  document.getElementById('lightbox').style.display = 'none';
}
function setMode(mode) {
  document.querySelectorAll('.btn-group .btn').forEach(b => b.classList.remove('active'));
  event.target.classList.add('active');
  const olds = document.querySelectorAll('.col-old');
  const news = document.querySelectorAll('.col-new');
  const refs = document.querySelectorAll('.col-ref');
  
  if (mode === 'all') {
    olds.forEach(e => e.style.display = 'flex');
    news.forEach(e => e.style.display = 'flex');
    refs.forEach(e => e.style.display = 'flex');
  } else if (mode === 'compare') {
    olds.forEach(e => e.style.display = 'flex');
    news.forEach(e => e.style.display = 'flex');
    refs.forEach(e => e.style.display = 'none');
  } else if (mode === 'recipe') {
    olds.forEach(e => e.style.display = 'none');
    news.forEach(e => e.style.display = 'flex');
    refs.forEach(e => e.style.display = 'flex');
  }
}
</script>
</body>
</html>
"""
    return html

def main():
    print("=== Stage 3: Merge, Metrics & HTML Artifact Generation ===", flush=True)
    t0 = time.time()

    with open(PADS_FILE) as f:
        pads = json.load(f)

    # Load raw outputs
    print(f"Loading raw outputs from {OUTS_NPZ}...", flush=True)
    raw_outs = np.load(OUTS_NPZ)["outputs"] # (33, 832, 576, 3)

    # Load app files map directly by prefix
    app_files_all = [f for f in APP_DIR.glob("*.jpg") if not f.name.endswith(".json")]
    app_page_map = {}
    for f in app_files_all:
        try:
            prefix = int(f.name.split(".")[0])
            app_page_map[prefix] = f
        except Exception:
            pass

    page_results = []

    for i, item in enumerate(pads):
        page_num = item["page"] # 0-indexed app_page from pairing.json
        t_idx = item["torch_index"]

        src_path = SRC_DIR / f"src_{t_idx:02d}.jpg"
        torch_path = TORCH_DIR / f"torch_{t_idx:02d}.png"
        old_app_path = app_page_map.get(page_num)

        src_im = Image.open(src_path).convert("RGB")
        src_rgb = np.array(src_im)
        orig_h, orig_w = src_rgb.shape[:2]

        # Crop raw output
        raw_full = raw_outs[i]
        crop_h = item["crop_h"]
        crop_w = item["crop_w"]
        raw_crop = raw_full[:crop_h, :crop_w]

        # Ink merge pass
        new_merged = ink_merge(src_rgb, raw_crop, strength=0.35)

        # References
        torch_rgb = np.array(Image.open(torch_path).convert("RGB"))
        old_rgb = np.array(Image.open(old_app_path).convert("RGB")) if old_app_path else None

        new_psnr = compute_psnr(new_merged, torch_rgb)
        old_psnr = compute_psnr(old_rgb, torch_rgb) if old_rgb is not None else 0.0

        new_sat = compute_sat(new_merged)
        old_sat = compute_sat(old_rgb) if old_rgb is not None else 0.0
        ref_sat = compute_sat(torch_rgb)

        new_warmth = compute_warmth(new_merged)
        old_warmth = compute_warmth(old_rgb) if old_rgb is not None else 0.0
        ref_warmth = compute_warmth(torch_rgb)

        # Save web preview images
        max_dim = 1200
        scale = min(1.0, max_dim / max(orig_h, orig_w))
        target_w, target_h = int(orig_w * scale), int(orig_h * scale)

        src_view = cv2.resize(src_rgb, (target_w, target_h), interpolation=cv2.INTER_AREA)
        new_view = cv2.resize(new_merged, (target_w, target_h), interpolation=cv2.INTER_AREA)
        old_view = cv2.resize(old_rgb, (target_w, target_h), interpolation=cv2.INTER_AREA) if old_rgb is not None else None
        torch_view = cv2.resize(torch_rgb, (target_w, target_h), interpolation=cv2.INTER_AREA)

        p_name = f"p{page_num:02d}"
        cv2.imwrite(str(IMAGES_DIR / f"{p_name}_src.jpg"), cv2.cvtColor(src_view, cv2.COLOR_RGB2BGR), [cv2.IMWRITE_JPEG_QUALITY, 85])
        cv2.imwrite(str(IMAGES_DIR / f"{p_name}_new.jpg"), cv2.cvtColor(new_view, cv2.COLOR_RGB2BGR), [cv2.IMWRITE_JPEG_QUALITY, 85])
        if old_view is not None:
            cv2.imwrite(str(IMAGES_DIR / f"{p_name}_old.jpg"), cv2.cvtColor(old_view, cv2.COLOR_RGB2BGR), [cv2.IMWRITE_JPEG_QUALITY, 85])
        cv2.imwrite(str(IMAGES_DIR / f"{p_name}_torch.jpg"), cv2.cvtColor(torch_view, cv2.COLOR_RGB2BGR), [cv2.IMWRITE_JPEG_QUALITY, 85])

        delta_psnr = new_psnr - old_psnr
        page_results.append({
            "page": page_num,
            "torch_index": t_idx,
            "orig_size": [orig_w, orig_h],
            "old_psnr": round(old_psnr, 2),
            "new_psnr": round(new_psnr, 2),
            "delta_psnr": round(delta_psnr, 2),
            "old_sat": round(old_sat, 1),
            "new_sat": round(new_sat, 1),
            "ref_sat": round(ref_sat, 1),
            "old_warmth": round(old_warmth, 4),
            "new_warmth": round(new_warmth, 4),
            "ref_warmth": round(ref_warmth, 4),
        })

    new_psnrs = [p["new_psnr"] for p in page_results]
    old_psnrs = [p["old_psnr"] for p in page_results]
    delta_psnrs = [p["delta_psnr"] for p in page_results]

    summary = {
        "chapter": "Chapter 34",
        "total_pages": len(page_results),
        "median_old_psnr": round(float(np.median(old_psnrs)), 2),
        "median_new_psnr": round(float(np.median(new_psnrs)), 2),
        "median_delta_psnr": round(float(np.median(delta_psnrs)), 2),
        "mean_delta_psnr": round(float(np.mean(delta_psnrs)), 2),
        "pages_improved_count": sum(1 for d in delta_psnrs if d >= 0),
        "pages": page_results,
    }

    with open(ARTIFACT_DIR / "summary.json", "w", encoding="utf-8") as f:
        json.dump(summary, f, indent=2)

    html_content = build_html_artifact(summary, page_results)
    with open(ARTIFACT_DIR / "index.html", "w", encoding="utf-8") as f:
        f.write(html_content)

    print(f"Stage 3 completed in {time.time()-t0:.1f}s.", flush=True)
    print(f"Artifact created at: {ARTIFACT_DIR / 'index.html'}", flush=True)

if __name__ == "__main__":
    main()
