@echo off
REM Build the bundled ytmusicapi Flask backend as a Tauri sidecar.
setlocal
cd /d "%~dp0"

python ..\scripts\setup-playback.py
if errorlevel 1 exit /b 1
python -m venv .venv
call .venv\Scripts\activate.bat
python -m pip install --upgrade pip --quiet
pip install -r requirements.txt pyinstaller --quiet
if errorlevel 1 exit /b 1

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
  --collect-all yt_dlp_ejs ^
  --collect-all yt_dlp_plugins ^
  --copy-metadata bgutil-ytdlp-pot-provider ^
  --collect-all pykakasi ^
  server.py
if errorlevel 1 exit /b 1

if exist "..\src-tauri\binaries\ytmd-backend-x86_64-pc-windows-msvc.exe" (
  "..\src-tauri\binaries\ytmd-backend-x86_64-pc-windows-msvc.exe" --check-playback
  if errorlevel 1 exit /b 1
  copy /Y "..\src-tauri\binaries\ytmd-backend-x86_64-pc-windows-msvc.exe" "..\src-tauri\resources\ytmd-backend.exe"
  python -c "import json,pathlib; p=pathlib.Path('../src-tauri/tauri.conf.json'); c=json.loads(p.read_text()); r=c['bundle']['resources']; r.append('resources/ytmd-backend.exe') if 'resources/ytmd-backend.exe' not in r else None; p.write_text(json.dumps(c,indent=2)+'\n')"
  echo Built src-tauri\binaries\ytmd-backend-x86_64-pc-windows-msvc.exe
) else (
  echo ERROR: sidecar was not created
  exit /b 1
)
