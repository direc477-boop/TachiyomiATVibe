@echo off
setlocal
cd /d "%~dp0"
if exist "tools\chapter_downloader\chapter_downloader.py" (
    start "" pythonw "tools\chapter_downloader\chapter_downloader.py"
    if not errorlevel 1 exit /b 0
)
if exist "npu_route\rawkuma_downloader.py" (
    start "" pythonw "npu_route\rawkuma_downloader.py"
    if not errorlevel 1 exit /b 0
)
py -3 "tools\chapter_downloader\chapter_downloader.py"
if errorlevel 1 pause
