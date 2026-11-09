import os
import queue
import re
import threading
import tkinter as tk
from html.parser import HTMLParser
from pathlib import Path
from tkinter import messagebox, ttk
from urllib.error import HTTPError, URLError
from urllib.parse import urljoin, urlparse
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parent / "calibration" / "rawkuma"
HEADERS = {"User-Agent": "Mozilla/5.0 (compatible; CalibrationPageDownloader/1.0)"}


class ChapterImages(HTMLParser):
    def __init__(self):
        super().__init__()
        self.depth = 0
        self.urls = []

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        if tag == "section" and "data-image-data" in attrs:
            self.depth = 1
        elif self.depth and tag == "section":
            self.depth += 1
        elif self.depth and tag == "img":
            source = attrs.get("data-src") or attrs.get("src")
            if source:
                self.urls.append(source)

    def handle_endtag(self, tag):
        if self.depth and tag == "section":
            self.depth -= 1


def chapter_location(url):
    parsed = urlparse(url.strip())
    if parsed.scheme != "https" or parsed.hostname not in ("rawkuma.net", "www.rawkuma.net"):
        raise ValueError("Use an HTTPS chapter link from rawkuma.net.")
    parts = [part for part in parsed.path.split("/") if part]
    if len(parts) != 3 or parts[0] != "manga" or not parts[2].startswith("chapter-"):
        raise ValueError("Expected a chapter link: /manga/title/chapter-number.id/.")
    if not all(re.fullmatch(r"[a-zA-Z0-9._-]+", part) and part not in (".", "..") for part in parts[1:]):
        raise ValueError("The chapter link contains an unsupported folder name.")
    return ROOT / parts[1] / parts[2]


def request(url, referer=None):
    headers = dict(HEADERS)
    if referer:
        headers["Referer"] = referer
    return urlopen(Request(url, headers=headers), timeout=30)


def chapter_pages(url):
    chapter_location(url)
    with request(url) as response:
        content = response.read(8 * 1024 * 1024 + 1)
    if len(content) > 8 * 1024 * 1024:
        raise ValueError("Chapter page is unexpectedly large.")
    parser = ChapterImages()
    parser.feed(content.decode("utf-8", errors="replace"))
    urls = list(dict.fromkeys(urljoin(url, item) for item in parser.urls))
    if not urls:
        raise ValueError("No chapter images found. The site may have changed or blocked the request.")
    if any(urlparse(item).scheme != "https" for item in urls):
        raise ValueError("The chapter contains a non-HTTPS image URL.")
    return urls


def save_chapter(url, report):
    target = chapter_location(url)
    pages = chapter_pages(url)
    target.mkdir(parents=True, exist_ok=True)
    for number, page in enumerate(pages, 1):
        suffix = Path(urlparse(page).path).suffix.lower()
        if suffix not in (".jpg", ".jpeg", ".png", ".webp", ".avif"):
            raise ValueError(f"Page {number} has an unsupported image extension.")
        destination = target / f"{number:03d}{suffix}"
        if destination.is_file() and destination.stat().st_size:
            report(f"Skipped existing {destination.name} ({number}/{len(pages)})")
            continue
        temporary = destination.with_suffix(destination.suffix + ".part")
        try:
            with request(page, referer=url) as response:
                if not response.headers.get_content_type().startswith("image/"):
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
        self.window.title("Rawkuma chapter downloader")
        self.window.geometry("760x540")
        self.messages = queue.Queue()
        frame = ttk.Frame(self.window, padding=16)
        frame.pack(fill="both", expand=True)
        ttk.Label(frame, text="Chapter links (one per line)").pack(anchor="w")
        self.links = tk.Text(frame, height=5, wrap="word")
        self.links.pack(fill="x", pady=(5, 12))
        ttk.Label(frame, text=f"Save folder: {ROOT}", wraplength=700).pack(anchor="w")
        bar = ttk.Frame(frame)
        bar.pack(fill="x", pady=12)
        self.button = ttk.Button(bar, text="Download chapters", command=self.start)
        self.button.pack(side="left")
        ttk.Button(bar, text="Open save folder", command=self.open_folder).pack(side="left", padx=8)
        ttk.Label(frame, text="Progress").pack(anchor="w")
        self.log = tk.Text(frame, state="disabled", wrap="word")
        self.log.pack(fill="both", expand=True, pady=(5, 0))
        self.window.after(100, self.drain)

    def open_folder(self):
        ROOT.mkdir(parents=True, exist_ok=True)
        os.startfile(ROOT)

    def start(self):
        urls = [line.strip() for line in self.links.get("1.0", "end").splitlines() if line.strip()]
        if not urls:
            messagebox.showinfo("Chapter links", "Paste at least one chapter link.")
            return
        self.button.config(state="disabled")
        threading.Thread(target=self.download, args=(urls,), daemon=True).start()

    def download(self, urls):
        for url in urls:
            self.messages.put(f"Reading {url}")
            try:
                save_chapter(url, self.messages.put)
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


if __name__ == "__main__":
    DownloaderWindow().window.mainloop()
