"""Inspect the litert-npu-runtime-qualcomm AAR: entries, .so list, bundled QNN version strings."""
import re
import sys
import zipfile

aar_path = sys.argv[1]
z = zipfile.ZipFile(aar_path)
print("=== entries ===")
entries = [i for i in z.infolist() if not i.filename.endswith("/")]
for info in sorted(entries, key=lambda i: i.filename):
    print(f"{info.file_size:>12}  {info.filename}")

print("=== .so files ===")
for n in sorted(z.namelist()):
    if n.endswith(".so"):
        data = z.read(n)
        # Grab QNN / QAIRT version-ish strings from the ELF
        strings = re.findall(rb"[ -~]{6,}", data)
        hits = sorted({s.decode("ascii", "ignore") for s in strings
                       if re.search(rb"(QNN|qairt|QAIRT|V75|v75|HTP|Qualcomm_SDK|2\.\d+\.\d+)", s)
                       and len(s) < 80})[:40]
        print(f"-- {n} ({len(data)} bytes)")
        for h in hits:
            print(f"   {h}")
