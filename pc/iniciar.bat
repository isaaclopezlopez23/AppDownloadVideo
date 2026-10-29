@echo off
rem ==========================================================================
rem  Descargador de videos - arranque en Windows
rem  1a vez: crea el entorno, instala dependencias y baja ffmpeg+ffprobe.
rem  Siempre: actualiza yt-dlp (las webs cambian a menudo) y abre la app.
rem  "iniciar.bat preparar" hace lo mismo pero sin abrir la app (lo usa compilar_exe.bat).
rem ==========================================================================
setlocal
cd /d "%~dp0"
title Descargador de videos

set "PY="
where py >nul 2>nul && set "PY=py -3"
if not defined PY (where python >nul 2>nul && set "PY=python")
if not defined PY (
    echo No encuentro Python 3.10 o superior.
    echo Instalalo desde https://www.python.org/downloads/ marcando "Add python.exe to PATH".
    if not defined CI pause
    exit /b 1
)

if not exist ".venv\Scripts\python.exe" (
    echo [1/3] Creando entorno virtual...
    %PY% -m venv .venv
    if errorlevel 1 (
        echo Error creando el entorno virtual.
        if not defined CI pause
        exit /b 1
    )
)

echo [2/3] Instalando / actualizando yt-dlp y dependencias...
".venv\Scripts\python.exe" -m pip install -q --disable-pip-version-check -U -r requirements.txt
if errorlevel 1 (
    echo AVISO: no se pudieron actualizar las dependencias. Sigo con lo que haya instalado.
)

if not exist "tools\ffmpeg\bin\ffprobe.exe" (
    echo [3/3] Descargando ffmpeg completo, solo la primera vez, unos 90 MB...
    powershell -NoProfile -ExecutionPolicy Bypass -Command ^
      "$ErrorActionPreference='Stop'; $ProgressPreference='SilentlyContinue';" ^
      "$u='https://github.com/yt-dlp/FFmpeg-Builds/releases/download/latest/ffmpeg-master-latest-win64-gpl.zip';" ^
      "$z=Join-Path $env:TEMP 'ffmpeg-ytdlp.zip'; $d=Join-Path $env:TEMP 'ffmpeg-ytdlp';" ^
      "Invoke-WebRequest $u -OutFile $z;" ^
      "if (Test-Path $d) { Remove-Item $d -Recurse -Force };" ^
      "Expand-Archive $z $d;" ^
      "$bin=(Get-ChildItem $d -Recurse -Filter ffprobe.exe | Select-Object -First 1).DirectoryName;" ^
      "New-Item -ItemType Directory -Force 'tools\ffmpeg\bin' | Out-Null;" ^
      "Copy-Item (Join-Path $bin 'ffmpeg.exe'),(Join-Path $bin 'ffprobe.exe') 'tools\ffmpeg\bin' -Force;" ^
      "Remove-Item $z,$d -Recurse -Force"
    if errorlevel 1 echo AVISO: no se pudo descargar ffmpeg; se usara el de imageio-ffmpeg, sin ffprobe.
)

if /i "%~1"=="preparar" exit /b 0

start "" ".venv\Scripts\pythonw.exe" descargador.py
exit /b 0
