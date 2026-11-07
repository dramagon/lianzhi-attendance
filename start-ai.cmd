@echo off
setlocal
cd /d "%~dp0"
set "AI_PYTHON=%~dp0artifacts\c-ai-venv\Scripts\python.exe"
if not exist "%AI_PYTHON%" (
  echo Local AI environment is missing. See docs/modules/c-media-services.md.
  pause
  exit /b 1
)
if exist "%~dp0artifacts\classroom-demo\demo-data\lianzhi.mv.db" (
  "%AI_PYTHON%" tools\c-package\run-full.py --port 18080 --reuse-classroom-data "%~dp0artifacts\classroom-demo"
) else (
  "%AI_PYTHON%" tools\c-package\run-full.py --port 18080
)
pause
