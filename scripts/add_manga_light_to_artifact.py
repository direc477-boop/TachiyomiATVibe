#!/usr/bin/env python3
"""Add Manga Light 4th column into Chapter 34 comparison artifact."""

import json
from pathlib import Path
import cv2
import numpy as np
from PIL import Image

CH34_DIR = Path(r"C:\Users\ADMIN\Downloads\ch34_compare")
ML_OUT_DIR = CH34_DIR / "manga_light_out"
ML_SUMMARY = CH34_DIR / "manga_light_ch34_summary.json"

ARTIFACT_DIR = Path(r"C:\Users\ADMIN\.traycer\epics\7f9db2bc-6dee-4e24-89e3-af5704d3e90a\artifacts\ch34-recipe-w8a8-comparison")
IMAGES_DIR = ARTIFACT_DIR / "images"
SUMMARY_FILE = ARTIFACT_DIR / "summary.json"
HTML_FILE = ARTIFACT_DIR / "index.html"

def build_html_artifact_4col(summary: dict, pages: list) -> str:
    html = f"""<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Chapter 34 — 4-Way Colorization Comparison</title>
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
    --purple: #bc8cff;
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
  .badge-purple {{ background: rgba(188,140,255,0.15); color: var(--purple); border-color: var(--purple); }}
  .lead {{ color: var(--text-muted); font-size: 0.88rem; margin-bottom: 16px; }}
  
  .stats-grid {{
    display: grid;
    grid-template-columns: repeat(auto-fit, minmax(160px, 1fr));
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
  .btn-group {{ display: flex; gap: 4px; flex-wrap: wrap; }}
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
    font-size: 0.78rem;
    background: #0d1117;
    padding: 4px 10px;
    border-radius: 6px;
    border: 1px solid var(--border);
    flex-wrap: wrap;
    align-items: center;
  }}
  .psnr-gain {{ color: var(--green); font-weight: 600; }}
  
  .img-grid {{
    display: grid;
    grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
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
  Chapter 34 — 4-Model Head-to-Head Comparison
  <span class="badge">NPU vs GPU vs PyTorch</span>
  <span class="badge badge-purple">Manga Light Added</span>
</h1>
<div class="lead">
  Side-by-side evaluation across all 33 pages: <b>Original W8A8 Baseline</b> (from APK) vs <b>New Recipe-Calibrated W8A8</b> vs <b>Manga Light (SAM + V6)</b> vs <b>Author PyTorch Reference</b>.
</div>

<div class="stats-grid">
  <div class="stat-card">
    <div class="stat-label">MCV2 Recipe W8A8</div>
    <div class="stat-val">{summary['median_new_psnr']} dB</div>
    <div class="stat-sub">{summary['median_delta_psnr']:+.2f} dB vs Old Baseline ({summary['median_old_psnr']} dB)</div>
  </div>
  <div class="stat-card">
    <div class="stat-label">Manga Light PSNR</div>
    <div class="stat-val">{summary['manga_light']['median_psnr']} dB</div>
    <div class="stat-sub">High-vibrancy anime style</div>
  </div>
  <div class="stat-card">
    <div class="stat-label">Median Saturation</div>
    <div class="stat-val">10.4 <span style="font-size:0.8rem; color:var(--text-muted); font-weight:normal;">(Ref: 10.1)</span></div>
    <div class="stat-sub">Manga Light: {summary['manga_light']['median_sat']} | Old: {summary['median_old_sat']}</div>
  </div>
  <div class="stat-card">
    <div class="stat-label">Hardware Profile</div>
    <div class="stat-val">Hybrid NPU/GPU</div>
    <div class="stat-sub">MCV2: 100% NPU | ML: SAM NPU + Gen GPU</div>
  </div>
</div>

<div class="controls-bar">
  <div style="font-size:0.85rem; font-weight:600;">Display Columns:</div>
  <div class="btn-group">
    <button class="btn active" onclick="setMode('all')">All 4 Columns</button>
    <button class="btn" onclick="setMode('mcv2_ab')">MCV2 New vs Old Baseline</button>
    <button class="btn" onclick="setMode('ml_compare')">MCV2 Recipe vs Manga Light</button>
    <button class="btn" onclick="setMode('ref_compare')">MCV2 Recipe vs PyTorch Ground Truth</button>
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
      <span>Old Base: <b>{p['old_psnr']} dB</b></span>
      <span>→ New Recipe: <b>{p['new_psnr']} dB</b> <span style="color:{gain_color};">({gain_str})</span></span>
      <span style="border-left: 1px solid #333; padding-left: 8px;">MangaLight: <b style="color:var(--purple);">{p.get('ml_psnr', 0.0)} dB</b></span>
      <span style="border-left: 1px solid #333; padding-left: 8px;">Sat: Old <b>{p['old_sat']}</b> → New <b>{p['new_sat']}</b> | ML <b style="color:var(--purple);">{p.get('ml_sat', 0.0)}</b> (Ref: {p['ref_sat']})</span>
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
      <div class="img-col-title"><span style="color:var(--accent);">★ New Recipe W8A8</span> <span style="color:var(--green); font-weight:bold;">{p['new_psnr']} dB</span></div>
      <div class="img-frame" style="border-color: var(--accent);" onclick="openLb('images/{p_name}_new.jpg')">
        <img src="images/{p_name}_new.jpg" loading="lazy" alt="New Recipe W8A8 Page {p_num}">
      </div>
    </div>
    <div class="img-col col-ml">
      <div class="img-col-title"><span style="color:var(--purple);">Manga Light (SAM + V6)</span> <span style="color:var(--purple);">{p.get('ml_psnr', 0.0)} dB</span></div>
      <div class="img-frame" style="border-color: rgba(188,140,255,0.4);" onclick="openLb('images/{p_name}_ml.jpg')">
        <img src="images/{p_name}_ml.jpg" loading="lazy" alt="Manga Light Page {p_num}">
      </div>
    </div>
    <div class="img-col col-ref">
      <div class="img-col-title"><span>PyTorch Reference</span> <span>Ground Truth</span></div>
      <div class="img-frame" onclick="openLb('images/{p_name}_torch.jpg')">
        <img src="images/{p_name}_torch.jpg" loading="lazy" alt="PyTorch Reference Page {p_num}">
      </div>
    </div>
  </div>
</div>
"""

    html += """
<div id=\"lightbox\" onclick=\"closeLb()\">
  <img id=\"lb-img\" src=\"\" alt=\"Enlarged view\">
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
  const mls = document.querySelectorAll('.col-ml');
  const refs = document.querySelectorAll('.col-ref');
  
  if (mode === 'all') {
    olds.forEach(e => e.style.display = 'flex');
    news.forEach(e => e.style.display = 'flex');
    mls.forEach(e => e.style.display = 'flex');
    refs.forEach(e => e.style.display = 'flex');
  } else if (mode === 'mcv2_ab') {
    olds.forEach(e => e.style.display = 'flex');
    news.forEach(e => e.style.display = 'flex');
    mls.forEach(e => e.style.display = 'none');
    refs.forEach(e => e.style.display = 'none');
  } else if (mode === 'ml_compare') {
    olds.forEach(e => e.style.display = 'none');
    news.forEach(e => e.style.display = 'flex');
    mls.forEach(e => e.style.display = 'flex');
    refs.forEach(e => e.style.display = 'none');
  } else if (mode === 'ref_compare') {
    olds.forEach(e => e.style.display = 'none');
    news.forEach(e => e.style.display = 'flex');
    mls.forEach(e => e.style.display = 'none');
    refs.forEach(e => e.style.display = 'flex');
  }
}
</script>
</body>
</html>
"""
    return html

def main():
    print("=== Adding Manga Light to Chapter 34 Comparison Artifact ===", flush=True)

    with open(SUMMARY_FILE, "r", encoding="utf-8") as f:
        existing_summary = json.load(f)

    with open(ML_SUMMARY, "r", encoding="utf-8") as f:
        ml_summary = json.load(f)

    ml_pages_map = {item["page"]: item for item in ml_summary["pages"]}

    # Process and save Manga Light preview JPEGs
    pages = existing_summary["pages"]
    for p in pages:
        p_num = p["page"]
        p_name = f"p{p_num:02d}"

        ml_src = ML_OUT_DIR / f"ml_{p_num:02d}.jpg"
        if ml_src.exists():
            # Get target preview dimension from existing p_new image
            ref_view = cv2.imread(str(IMAGES_DIR / f"{p_name}_new.jpg"))
            target_h, target_w = ref_view.shape[:2]

            ml_img = cv2.imread(str(ml_src))
            ml_view = cv2.resize(ml_img, (target_w, target_h), interpolation=cv2.INTER_AREA)

            dst = IMAGES_DIR / f"{p_name}_ml.jpg"
            cv2.imwrite(str(dst), ml_view, [cv2.IMWRITE_JPEG_QUALITY, 85])

        ml_item = ml_pages_map.get(p_num, {})
        p["ml_psnr"] = ml_item.get("ml_psnr_vs_torch", 0.0)
        p["ml_sat"] = ml_item.get("ml_sat", 0.0)

    existing_summary["manga_light"] = {
        "median_psnr": ml_summary["median_psnr"],
        "mean_psnr": ml_summary["mean_psnr"],
        "median_sat": ml_summary["median_sat"],
    }
    existing_summary["median_old_sat"] = round(float(np.median([p["old_sat"] for p in pages])), 1)

    with open(SUMMARY_FILE, "w", encoding="utf-8") as f:
        json.dump(existing_summary, f, indent=2)

    html_content = build_html_artifact_4col(existing_summary, pages)
    with open(HTML_FILE, "w", encoding="utf-8") as f:
        f.write(html_content)

    print("Successfully updated artifact with 4-column Manga Light comparison!", flush=True)

if __name__ == "__main__":
    main()
