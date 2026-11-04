@echo off
cd /d "%~dp0"
if not exist "artifacts\c-media-venv\Scripts\python.exe" (
  echo Local services are not installed. See docs\modules\c-media-services.md.
  pause
  exit /b 1
)
"artifacts\c-media-venv\Scripts\python.exe" "tools\c-package\run-full.py" %*
if errorlevel 1 pause
