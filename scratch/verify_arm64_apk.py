import zipfile
import hashlib

apk_path = "app/build/outputs/apk/standard/debug/app-standard-arm64-v8a-debug.apk"
with zipfile.ZipFile(apk_path, "r") as z:
    for name in z.namelist():
        if any(k in name for k in ["manga_colorize_npu", "quicksrnet", "libQnn"]):
            info = z.getinfo(name)
            data = z.read(name)
            h = hashlib.sha256(data).hexdigest()
            print(f"{name}: {info.file_size} bytes, sha256={h}")
