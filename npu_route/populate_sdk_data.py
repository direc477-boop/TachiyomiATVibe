"""Populate ai_edge_litert_sdk_qualcomm/data from the pre-downloaded QAIRT zip.

Replicates setup.py's CustomBuildPy extraction exactly: members under
'qairt/<ver>/' are copied (prefix stripped) into <site>/ai_edge_litert_sdk_qualcomm/data.
Deterministic replacement for the sdist's silent-failure download.
"""
import os
import sys
import zipfile

ZIP = sys.argv[1]
PREFIX = "qairt/2.50.0.260828"
SP = "/scratch/venv/lib/python3.10/site-packages"
TARGET = os.path.join(SP, "ai_edge_litert_sdk_qualcomm", "data")

os.makedirs(TARGET, exist_ok=True)
count = 0
bad = 0
with zipfile.ZipFile(ZIP) as z:
    names = z.namelist()
    wanted = [n for n in names if n.startswith(PREFIX + "/") and n != PREFIX + "/"]
    print(f"[populate] zip members: {len(names)}, under prefix: {len(wanted)}")
    if not wanted:
        print("[populate] FATAL: prefix not found in zip; sample names:")
        for n in names[:10]:
            print("   ", n)
        sys.exit(2)
    for info in z.infolist():
        if not info.filename.startswith(PREFIX + "/") or info.filename == PREFIX + "/":
            continue
        rel = info.filename[len(PREFIX) + 1:]
        norm = os.path.normpath(rel)
        if norm.startswith("..") or os.path.isabs(norm):
            bad += 1
            continue
        dst = os.path.join(TARGET, norm)
        if info.is_dir():
            os.makedirs(dst, exist_ok=True)
            continue
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        with z.open(info) as src, open(dst, "wb") as out:
            while True:
                chunk = src.read(1 << 22)
                if not chunk:
                    break
                out.write(chunk)
        count += 1
print(f"[populate] extracted {count} files ({bad} skipped)")
libs = os.path.join(TARGET, "lib", "x86_64-linux-clang")
if os.path.isdir(libs):
    so = sorted(f for f in os.listdir(libs) if f.endswith(".so"))
    print(f"[populate] host libs ({len(so)}):", ", ".join(so[:12]))
else:
    print("[populate] WARN: expected host lib dir missing:", libs)
    for d, _, fs in os.walk(TARGET):
        if "libQnnHtp.so" in fs:
            print("[populate] libQnnHtp.so found at:", d)
            break
