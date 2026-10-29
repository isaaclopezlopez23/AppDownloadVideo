@echo off
rem ==========================================================================
rem  Genera dist\DescargadorVideos\DescargadorVideos.exe con todo dentro
rem  (Python, yt-dlp, ffmpeg+ffprobe y Deno). Copia la carpeta entera donde quieras.
rem  Para actualizar yt-dlp en el .exe: vuelve a ejecutar este script.
rem ==========================================================================
setlocal
cd /d "%~dp0"

call "%~dp0iniciar.bat" preparar
if errorlevel 1 exit /b 1

echo Instalando PyInstaller...
".venv\Scripts\python.exe" -m pip install -q --disable-pip-version-check -U pyinstaller
if errorlevel 1 exit /b 1

for /f "delims=" %%i in ('call ".venv\Scripts\python.exe" -c "import deno; print(deno.find_deno_bin())"') do set "DENO=%%i"
if not exist "%DENO%" (
    echo No encuentro deno.exe en el entorno.
    exit /b 1
)
if not exist "tools\ffmpeg\bin\ffprobe.exe" (
    echo Falta tools\ffmpeg\bin\ffprobe.exe. Ejecuta antes iniciar.bat con conexion a internet.
    exit /b 1
)

echo Compilando...
".venv\Scripts\pyinstaller.exe" --noconfirm --clean --windowed --name DescargadorVideos ^
    --add-binary "%DENO%;deno" ^
    --add-binary "tools\ffmpeg\bin\ffmpeg.exe;tools\ffmpeg\bin" ^
    --add-binary "tools\ffmpeg\bin\ffprobe.exe;tools\ffmpeg\bin" ^
    descargador.py
if errorlevel 1 exit /b 1

echo.
echo Listo: dist\DescargadorVideos\DescargadorVideos.exe
if not defined CI pause
