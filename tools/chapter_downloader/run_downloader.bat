@echo off
setlocal
cd /d "%~dp0"
start "" pythonw chapter_downloader.py
if errorlevel 1 (
    py -3 -I chapter_downloader.py
    if errorlevel 1 pause
)
