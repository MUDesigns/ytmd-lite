@echo off
REM Build the bundled ytmusicapi Flask backend as a Tauri sidecar.
setlocal
cd /d "%~dp0"

python -m venv .venv
call .venv\Scripts\activate.bat
python -m pip install --upgrade pip --quiet
pip install -r requirements.txt pyinstaller --quiet

if not exist "..\src-tauri\binaries" mkdir "..\src-tauri\binaries"

pyinstaller --noconfirm --onefile ^
  --name ytmd-backend-x86_64-pc-windows-msvc ^
  --distpath ..\src-tauri\binaries ^
  --workpath .\build_tmp ^
  --specpath .\build_tmp ^
  --hidden-import=ytmusicapi ^
  --hidden-import=flask ^
  --hidden-import=flask_cors ^
  --hidden-import=yt_dlp ^
  --hidden-import=pykakasi ^
  --collect-all ytmusicapi ^
  --collect-all yt_dlp ^
  --collect-all pykakasi ^
  server.py

if exist "..\src-tauri\binaries\ytmd-backend-x86_64-pc-windows-msvc.exe" (
  echo Built src-tauri\binaries\ytmd-backend-x86_64-pc-windows-msvc.exe
) else (
  echo ERROR: sidecar was not created
  exit /b 1
)
