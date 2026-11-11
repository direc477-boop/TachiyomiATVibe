from __future__ import annotations

import json
import os
import queue
import re
import threading
import tkinter as tk
from html.parser import HTMLParser
from pathlib import Path
from tkinter import filedialog, messagebox, ttk
from urllib.error import HTTPError, URLError
from urllib.parse import urljoin, urlparse
from urllib.request import Request, urlopen

GLOBAL_CONFIG_PATH = Path.home() / ".tachiyomi" / "downloader_settings.json"
LOCAL_CONFIG_PATH = Path(__file__).resolve().parent / ".settings.json"
HEADERS = {"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"}


def find_repo_root(start: Path) -> Path | None:
    curr = start.resolve()
    for parent in [curr] + list(curr.parents):
        if (parent / ".git").exists() or (parent / "settings.gradle.kts").exists():
            return parent
    return None


def get_adaptive_save_root() -> Path:
    """Finds or adapts to the most appropriate saved directory."""
    # 1. Global user config
    if GLOBAL_CONFIG_PATH.exists():
        try:
            cfg = json.loads(GLOBAL_CONFIG_PATH.read_text(encoding="utf-8"))
            if cfg.get("dest") and Path(cfg["dest"]).exists():
                return Path(cfg["dest"])
        except Exception:
            pass

    # 2. Local config
    if LOCAL_CONFIG_PATH.exists():
        try:
            cfg = json.loads(LOCAL_CONFIG_PATH.read_text(encoding="utf-8"))
            if cfg.get("dest") and Path(cfg["dest"]).exists():
                return Path(cfg["dest"])
        except Exception:
            pass

    # 3. Environment variables
    for env_var in ("MANGA_DOWNLOAD_DIR", "RAWKUMA_DOWNLOAD_DIR", "TACHIYOMI_DOWNLOAD_DIR"):
        val = os.environ.get(env_var)
        if val and Path(val).exists():
            return Path(val)

    # 4. Check repo folders if present
    repo_root = find_repo_root(Path(__file__))
    if repo_root:
        calib_route = repo_root / "npu_route" / "calibration" / "rawkuma"
        if calib_route.exists():
            return calib_route
        calib_saved = repo_root / "saved_models" / "calibration_dataset" / "rawkuma"
        if calib_saved.exists():
            return calib_saved

    # 5. User Downloads folder
    downloads = Path.home() / "Downloads"
    if downloads.exists():
        manga_test = downloads / "manga_test_chapters"
        return manga_test

    return Path.home() / "Downloads" / "manga_test_chapters"


def save_settings(dest: Path | str) -> None:
    dest_str = str(Path(dest))
    try:
        GLOBAL_CONFIG_PATH.parent.mkdir(parents=True, exist_ok=True)
        GLOBAL_CONFIG_PATH.write_text(json.dumps({"dest": dest_str}, indent=2), encoding="utf-8")
    except Exception:
        pass
    try:
        LOCAL_CONFIG_PATH.write_text(json.dumps({"dest": dest_str}, indent=2), encoding="utf-8")
    except Exception:
        pass


class ChapterImages(HTMLParser):
    def __init__(self):
        super().__init__()
        self.depth = 0
        self.urls: list[str] = []

    def handle_starttag(self, tag, attrs):
        attr_map = dict(attrs)
        if tag == "section" and "data-image-data" in attr_map:
            self.depth = 1
        elif self.depth and tag == "section":
            self.depth += 1
        elif (self.depth or (tag == "div" and attr_map.get("id") in ("reading-content", "readerarea"))) and tag == "img":
            source = attr_map.get("data-src") or attr_map.get("data-lazy-src") or attr_map.get("data-original") or attr_map.get("src")
            if source:
                self.urls.append(source)
        elif tag == "img" and any(k in attr_map for k in ("data-src", "data-lazy-src", "data-original")):
            source = attr_map.get("data-src") or attr_map.get("data-lazy-src") or attr_map.get("data-original")
            if source and not any(ch in source for ch in ("logo", "avatar", "icon", "banner", "favicon")):
                self.urls.append(source)

    def handle_endtag(self, tag):
        if self.depth and tag == "section":
            self.depth -= 1


def chapter_location(url: str, base_root: Path | None = None) -> Path:
    if base_root is None:
        base_root = get_adaptive_save_root()
    clean_url = url.strip()
    if clean_url and not urlparse(clean_url).scheme:
        clean_url = "https://" + clean_url
    parsed = urlparse(clean_url)
    if not parsed.netloc:
        raise ValueError("Invalid URL: must have a valid hostname.")
    parts = [part for part in parsed.path.split("/") if part]
    if not parts:
        raise ValueError("Expected a chapter link like: /manga/title/chapter-number/.")

    if "manga" in parts:
        idx = parts.index("manga")
        slug = parts[idx + 1] if len(parts) > idx + 1 else "manga"
        chapter = parts[-1] if len(parts) > idx + 2 else "chapter"
    elif len(parts) >= 2:
        slug = parts[0]
        chapter = parts[-1]
    else:
        slug = "manga"
        chapter = parts[0]

    slug = re.sub(r'[<>:"/\\|?*\x00-\x1f]', "_", slug).strip(" .")
    chapter = re.sub(r'[<>:"/\\|?*\x00-\x1f]', "_", chapter).strip(" .")
    return base_root / (slug or "manga") / (chapter or "chapter")


def request(url: str, referer: str | None = None):
    headers = dict(HEADERS)
    if referer:
        headers["Referer"] = referer
    return urlopen(Request(url, headers=headers), timeout=30)


def chapter_pages(url: str) -> list[str]:
    with request(url) as response:
        content = response.read(12 * 1024 * 1024 + 1)
    if len(content) > 12 * 1024 * 1024:
        raise ValueError("Chapter page is unexpectedly large.")
    decoded = content.decode("utf-8", errors="replace")
    parser = ChapterImages()
    parser.feed(decoded)
    urls = list(dict.fromkeys(urljoin(url, item) for item in parser.urls))

    if not urls:
        # Check WordPress ts_reader.run({...}) style readers
        m = re.search(r"ts_reader\.run\((\{.*?\})\)", decoded, re.S)
        if m:
            try:
                data = json.loads(m.group(1))
                for source in data.get("sources", []):
                    for u in source.get("images", []) or []:
                        u_str = str(u).strip()
                        if u_str:
                            urls.append(urljoin(url, u_str))
            except Exception:
                pass

    if not urls:
        raise ValueError("No chapter images found. The site may have changed or blocked the request.")
    return urls


def save_chapter(url: str, report, base_root: Path | None = None) -> None:
    target = chapter_location(url, base_root)
    pages = chapter_pages(url)
    target.mkdir(parents=True, exist_ok=True)
    width = max(3, len(str(len(pages))))
    for number, page in enumerate(pages, 1):
        suffix = Path(urlparse(page).path).suffix.lower()
        if suffix not in (".jpg", ".jpeg", ".png", ".webp", ".avif", ".gif"):
            suffix = ".jpg"
        destination = target / f"{number:0{width}d}{suffix}"
        if destination.is_file() and destination.stat().st_size:
            report(f"Skipped existing {destination.name} ({number}/{len(pages)})")
            continue
        temporary = destination.with_suffix(destination.suffix + ".part")
        try:
            with request(page, referer=url) as response:
                content_type = response.headers.get_content_type()
                if not content_type.startswith("image/") and content_type != "application/octet-stream":
                    raise ValueError(f"Page {number} did not return an image.")
                with temporary.open("wb") as output:
                    while chunk := response.read(256 * 1024):
                        output.write(chunk)
            if not temporary.stat().st_size:
                raise ValueError(f"Page {number} was empty.")
            os.replace(temporary, destination)
        finally:
            temporary.unlink(missing_ok=True)
        report(f"Saved {destination.name} ({number}/{len(pages)})")
    report(f"Finished: {target}")


class DownloaderWindow:
    def __init__(self):
        self.window = tk.Tk()
        self.window.title("Rawkuma Chapter Downloader")
        self.window.geometry("780x580")
        self.messages: queue.Queue = queue.Queue()
        self.save_root_var = tk.StringVar(value=str(get_adaptive_save_root()))

        frame = ttk.Frame(self.window, padding=16)
        frame.pack(fill="both", expand=True)

        ttk.Label(frame, text="Chapter links (one per line):").pack(anchor="w")
        self.links = tk.Text(frame, height=5, wrap="word")
        self.links.pack(fill="x", pady=(4, 10))

        # Adaptive Save Folder UI
        folder_frame = ttk.Frame(frame)
        folder_frame.pack(fill="x", pady=(0, 10))
        ttk.Label(folder_frame, text="Save directory:").pack(side="left")
        self.folder_entry = ttk.Entry(folder_frame, textvariable=self.save_root_var)
        self.folder_entry.pack(side="left", fill="x", expand=True, padx=6)
        ttk.Button(folder_frame, text="Browse…", command=self.browse_folder).pack(side="left")
        ttk.Button(folder_frame, text="Open folder", command=self.open_folder).pack(side="left", padx=6)

        bar = ttk.Frame(frame)
        bar.pack(fill="x", pady=(0, 10))
        self.button = ttk.Button(bar, text="Download chapters", command=self.start)
        self.button.pack(side="left")

        ttk.Label(frame, text="Progress & Logs:").pack(anchor="w")
        self.log = tk.Text(frame, state="disabled", wrap="word", font=("Consolas", 9))
        self.log.pack(fill="both", expand=True, pady=(4, 0))
        self.window.after(100, self.drain)

    def browse_folder(self):
        initial = self.save_root_var.get()
        if not Path(initial).exists():
            initial = str(Path.home() / "Downloads")
        chosen = filedialog.askdirectory(
            parent=self.window,
            title="Choose folder to save chapters into",
            initialdir=initial,
            mustexist=False,
        )
        if chosen:
            self.save_root_var.set(chosen)
            save_settings(chosen)

    def open_folder(self):
        dest = Path(self.save_root_var.get().strip())
        dest.mkdir(parents=True, exist_ok=True)
        try:
            os.startfile(dest)
        except Exception as e:
            messagebox.showerror("Cannot open folder", str(e))

    def start(self):
        urls = [line.strip() for line in self.links.get("1.0", "end").splitlines() if line.strip()]
        if not urls:
            messagebox.showinfo("Chapter links", "Paste at least one chapter link.")
            return
        save_root = Path(self.save_root_var.get().strip() or get_adaptive_save_root())
        save_settings(save_root)
        self.button.config(state="disabled")
        threading.Thread(target=self.download, args=(urls, save_root), daemon=True).start()

    def download(self, urls: list[str], save_root: Path):
        for url in urls:
            self.messages.put(f"Reading {url}")
            try:
                save_chapter(url, self.messages.put, save_root)
            except (ValueError, OSError, HTTPError, URLError) as error:
                self.messages.put(f"Failed: {error}")
        self.messages.put(None)

    def drain(self):
        while not self.messages.empty():
            item = self.messages.get_nowait()
            if item is None:
                self.button.config(state="normal")
                continue
            self.log.config(state="normal")
            self.log.insert("end", item + "\n")
            self.log.see("end")
            self.log.config(state="disabled")
        self.window.after(100, self.drain)


def main():
    DownloaderWindow().window.mainloop()


if __name__ == "__main__":
    main()
