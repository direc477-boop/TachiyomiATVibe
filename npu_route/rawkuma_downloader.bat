@echo off
py -3 -I "%~dp0rawkuma_downloader.py"
if errorlevel 1 pause
