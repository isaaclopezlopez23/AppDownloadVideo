"""
Motor de descargas basado en yt-dlp.

- Cola secuencial en un hilo propio (la GUI nunca se bloquea).
- Log por iteración con hora, ETA de la cola y resumen final de OK / errores.
- Fallback "sniffer": si yt-dlp no reconoce la web, busca en el HTML enlaces
  directos a .m3u8 / .mp4 / .webm / .mpd y los descarga con el Referer correcto.
- Contenido con DRM: se detecta y se informa; no se intenta saltar.
"""
import datetime as dt
import html
import logging
import os
import queue
import re
import shutil
import subprocess
import sys
import threading
import time
from dataclasses import dataclass, field
from pathlib import Path
from urllib.parse import urljoin

import yt_dlp
from yt_dlp.utils import DownloadCancelled, DownloadError, sanitize_filename

APP_NOMBRE = "DescargadorVideos"

CALIDADES = {
    "Mejor disponible": "bv*+ba/b",
    "1080p": "bv*[height<=1080]+ba/b[height<=1080]/b",
    "720p": "bv*[height<=720]+ba/b[height<=720]/b",
    "480p": "bv*[height<=480]+ba/b[height<=480]/b",
    "Solo audio (MP3)": "ba/b",
}
AUDIO_MP3 = "Solo audio (MP3)"

# Navegadores de los que yt-dlp sabe leer cookies (para webs con login).
NAVEGADORES = ["Ninguno", "firefox", "chrome", "edge", "brave", "opera", "vivaldi", "chromium"]

EXT_VIDEO = r"(?:m3u8|mp4|webm|mpd|m4v|mov)"
RE_URL_ABS = re.compile(rf"""(?:https?:)?//[^\s"'<>()\\]+?\.{EXT_VIDEO}(?:\?[^\s"'<>\\]*)?""", re.I)
# Cualquier cadena entre comillas que acabe en extensión de vídeo (atributos src=, JSON de reproductores, etc.)
RE_URL_COMILLAS = re.compile(rf"""["']([^"'\s<>]+?\.{EXT_VIDEO}(?:\?[^"'\s<>]*)?)["']""", re.I)
RE_TITLE = re.compile(r"<title[^>]*>(.*?)</title>", re.I | re.S)
RE_OG_TITLE = re.compile(r"""<meta[^>]+property=["']og:title["'][^>]+content=["']([^"']+)""", re.I)
RE_ANSI = re.compile(r"\x1b\[[0-9;]*m")


# ---------------------------------------------------------------------------
# Rutas: configuración, logs y herramientas externas (ffmpeg, deno)
# ---------------------------------------------------------------------------
def dir_datos() -> Path:
    if sys.platform == "win32":
        base = Path(os.environ.get("APPDATA", Path.home()))
    elif sys.platform == "darwin":
        base = Path.home() / "Library" / "Application Support"
    else:
        base = Path(os.environ.get("XDG_CONFIG_HOME", Path.home() / ".config"))
    d = base / APP_NOMBRE
    d.mkdir(parents=True, exist_ok=True)
    return d


def carpeta_por_defecto() -> Path:
    return Path.home() / "Downloads" / "Videos"


def dir_app() -> Path:
    """Carpeta del programa (junto al .py, o la interna del .exe)."""
    if getattr(sys, "frozen", False):
        return Path(getattr(sys, "_MEIPASS", Path(sys.executable).parent))
    return Path(__file__).resolve().parent


def buscar_ffmpeg() -> str | None:
    """Devuelve la CARPETA con ffmpeg+ffprobe si existe, o la ruta al binario de ffmpeg.

    Orden: tools/ffmpeg/bin (lo descarga iniciar.bat / va dentro del .exe) → ffmpeg del PATH
    → imageio-ffmpeg (solo trae ffmpeg, sin ffprobe: funciona, pero con algún aviso).
    """
    exe = "ffmpeg.exe" if sys.platform == "win32" else "ffmpeg"
    for d in (dir_app() / "tools" / "ffmpeg" / "bin", dir_app() / "ffmpeg"):
        if (d / exe).exists():
            return str(d)
    en_path = shutil.which("ffmpeg")
    if en_path and shutil.which("ffprobe"):
        return str(Path(en_path).parent)
    try:
        import imageio_ffmpeg

        ruta = imageio_ffmpeg.get_ffmpeg_exe()
        if ruta and os.path.exists(ruta):
            return ruta
    except Exception:
        pass
    return en_path


def tiene_ffprobe(ffmpeg: str | None) -> bool:
    if not ffmpeg:
        return False
    d = Path(ffmpeg) if os.path.isdir(ffmpeg) else Path(ffmpeg).parent
    return (d / ("ffprobe.exe" if sys.platform == "win32" else "ffprobe")).exists()


def buscar_deno() -> str | None:
    """Deno es el runtime JS que yt-dlp necesita para YouTube (desde 2025.11)."""
    if getattr(sys, "frozen", False):  # .exe de PyInstaller con deno embebido
        cand = Path(getattr(sys, "_MEIPASS", "")) / "deno" / ("deno.exe" if sys.platform == "win32" else "deno")
        if cand.exists():
            return str(cand)
    try:
        import deno  # paquete pip "deno"

        exe = deno.find_deno_bin()
        if exe and os.path.exists(exe):
            return str(exe)
    except Exception:
        pass
    return shutil.which("deno")


# ---------------------------------------------------------------------------
# Utilidades de formato
# ---------------------------------------------------------------------------
def fmt_tiempo(seg) -> str:
    if seg is None:
        return ""
    seg = int(max(0, seg))
    h, r = divmod(seg, 3600)
    m, s = divmod(r, 60)
    return f"{h}:{m:02d}:{s:02d}" if h else f"{m:02d}:{s:02d}"


def fmt_bytes(n) -> str:
    if not n:
        return ""
    n = float(n)
    for u in ("B", "KB", "MB", "GB"):
        if n < 1024 or u == "GB":
            return f"{n:.1f} {u}" if u != "B" else f"{int(n)} B"
        n /= 1024
    return ""


def fmt_velocidad(bps) -> str:
    return f"{fmt_bytes(bps)}/s" if bps else ""


def error_legible(msg: str) -> str:
    """Traduce los errores típicos de yt-dlp a algo que se entienda."""
    m = RE_ANSI.sub("", str(msg)).replace("ERROR: ", "").strip()
    low = m.lower()
    if "drm" in low:
        return "Contenido protegido con DRM: no se puede descargar."
    if "sign in to confirm" in low or "login required" in low or "use --cookies" in low or "cookies" in low and "account" in low:
        return "La web pide iniciar sesión: elige tu navegador en 'Cookies' y reintenta."
    if "could not copy" in low and "cookie" in low or "failed to decrypt" in low:
        return "No se pudieron leer las cookies del navegador (ciérralo o usa Firefox)."
    if "unsupported url" in low:
        return "No se ha encontrado ningún vídeo en esa página."
    if "http error 403" in low:
        return "403 Prohibido: actualiza yt-dlp o usa las cookies del navegador."
    if "http error 404" in low:
        return "404: el vídeo ya no existe en esa dirección."
    if "private video" in low:
        return "Vídeo privado."
    if "ffmpeg" in low and "not found" in low:
        return "Falta ffmpeg para unir audio y vídeo."
    return m.splitlines()[-1][:300] if m else "Error desconocido"


# ---------------------------------------------------------------------------
# Modelo
# ---------------------------------------------------------------------------
@dataclass
class Tarea:
    id: int
    url: str
    calidad: str
    carpeta: str
    playlist: bool = False
    cookies: str = "Ninguno"
    estado: str = "En cola"  # En cola | Descargando | Procesando | OK | Error | Cancelada
    titulo: str = ""
    progreso: float = 0.0
    velocidad: str = ""
    eta: str = ""
    tamano: int = 0
    archivos: list = field(default_factory=list)
    error: str = ""
    inicio: float = 0.0
    fin: float = 0.0
    titulo_fijo: bool = False  # True cuando el título sale del HTML (sniffer) y no debe pisarse

    def snapshot(self) -> dict:
        return {
            "id": self.id, "url": self.url, "estado": self.estado, "titulo": self.titulo,
            "progreso": self.progreso, "velocidad": self.velocidad, "eta": self.eta,
            "tamano": self.tamano, "archivos": list(self.archivos), "error": self.error,
            "calidad": self.calidad,
        }


class _YtdlpLogger:
    """Redirige avisos y errores de yt-dlp a nuestro log (el ruido de debug se descarta)."""

    def __init__(self, motor: "Motor"):
        self.motor = motor

    def debug(self, msg):
        pass

    def info(self, msg):
        pass

    def warning(self, msg):
        msg = RE_ANSI.sub("", msg)
        if "Falling back on generic information extractor" in msg:
            return
        self.motor.log("WARNING", f"yt-dlp: {msg}")

    def error(self, msg):
        # Los errores llegan también como excepción; aquí solo dejamos rastro en el fichero.
        self.motor._flog.error(RE_ANSI.sub("", msg))


# ---------------------------------------------------------------------------
# Motor
# ---------------------------------------------------------------------------
class Motor:
    def __init__(self, emitir):
        """emitir(evento: tuple) se llama desde el hilo de trabajo; debe ser thread-safe (p.ej. queue.put)."""
        self._emitir = emitir
        self._cola: "queue.Queue[Tarea]" = queue.Queue()
        self._tareas: dict[int, Tarea] = {}
        self._lock = threading.Lock()
        self._siguiente_id = 1
        self._cancelar = threading.Event()
        self._actual: Tarea | None = None
        self._lote: list[Tarea] = []
        self._lote_inicio = 0.0
        self._ultimo_emit = 0.0
        self._pp_avisados: set = set()

        self.ffmpeg = buscar_ffmpeg()
        self.deno = buscar_deno()
        self.dir_temp = dir_datos() / "temp"
        self.dir_temp.mkdir(exist_ok=True)
        limite = time.time() - 7 * 86400  # parciales abandonados hace más de una semana
        for f in self.dir_temp.iterdir():
            try:
                if f.is_file() and f.stat().st_mtime < limite:
                    f.unlink()
            except OSError:
                pass

        log_dir = dir_datos() / "logs"
        log_dir.mkdir(exist_ok=True)
        self.fichero_log = log_dir / f"descargas_{dt.date.today():%Y-%m-%d}.log"
        self._flog = logging.getLogger(APP_NOMBRE)
        self._flog.setLevel(logging.INFO)
        if not self._flog.handlers:
            h = logging.FileHandler(self.fichero_log, encoding="utf-8")
            h.setFormatter(logging.Formatter("%(asctime)s [%(levelname)s] %(message)s", "%Y-%m-%d %H:%M:%S"))
            self._flog.addHandler(h)

        self._hilo = threading.Thread(target=self._bucle, name="motor-descargas", daemon=True)
        self._hilo.start()

    # ---------------- API pública (llamable desde la GUI) ----------------
    def info_entorno(self) -> dict:
        return {
            "yt_dlp": yt_dlp.version.__version__,
            "ffmpeg": self.ffmpeg,
            "ffprobe": tiene_ffprobe(self.ffmpeg),
            "deno": self.deno,
            "log": str(self.fichero_log),
        }

    def encolar(self, url: str, calidad: str, carpeta: str, playlist=False, cookies="Ninguno") -> Tarea:
        with self._lock:
            t = Tarea(self._siguiente_id, url.strip(), calidad, carpeta, playlist, cookies)
            self._siguiente_id += 1
            self._tareas[t.id] = t
        self._emitir(("tarea", t.snapshot()))
        self._cola.put(t)
        return t

    def reintentar(self, tarea_id: int):
        t = self._tareas.get(tarea_id)
        if not t or t.estado not in ("Error", "Cancelada"):
            return
        t.estado, t.progreso, t.error, t.velocidad, t.eta = "En cola", 0.0, "", "", ""
        t.archivos, t.tamano = [], 0
        self._emitir(("tarea", t.snapshot()))
        self._cola.put(t)

    def quitar(self, tarea_id: int):
        """Quita una tarea pendiente o terminada (la activa hay que cancelarla antes)."""
        t = self._tareas.get(tarea_id)
        if not t or t is self._actual:
            return False
        if t.estado == "En cola":
            t.estado = "Cancelada"  # el bucle la saltará
        self._tareas.pop(tarea_id, None)
        return True

    def cancelar_actual(self):
        if self._actual:
            self._cancelar.set()

    def cancelar_todo(self):
        for t in list(self._tareas.values()):
            if t.estado == "En cola":
                t.estado = "Cancelada"
                self._emitir(("tarea", t.snapshot()))
        self.cancelar_actual()

    def actualizar_ytdlp(self) -> tuple[bool, str]:
        """Solo tiene sentido ejecutando desde código fuente (no en el .exe)."""
        if getattr(sys, "frozen", False):
            return False, "En la versión .exe hay que recompilar (compilar_exe.bat) o usar iniciar.bat."
        cmd = [sys.executable, "-m", "pip", "install", "-U", "yt-dlp[default]", "yt-dlp-ejs"]
        kw = {"creationflags": 0x08000000} if sys.platform == "win32" else {}  # CREATE_NO_WINDOW
        r = subprocess.run(cmd, capture_output=True, text=True, **kw)
        ok = r.returncode == 0
        salida = (r.stdout + r.stderr).strip().splitlines()
        return ok, (salida[-1] if salida else "") + ("\nReinicia la aplicación para usar la nueva versión." if ok else "")

    # ---------------- Log ----------------
    def log(self, nivel: str, texto: str):
        getattr(self._flog, nivel.lower(), self._flog.info)(texto)
        self._emitir(("log", nivel, f"[{dt.datetime.now():%H:%M:%S}] {texto}"))

    # ---------------- Bucle de trabajo ----------------
    def _bucle(self):
        while True:
            t = self._cola.get()
            if t.estado == "Cancelada" or t.id not in self._tareas:
                self._comprobar_fin_lote()
                continue
            if not self._lote:
                self._lote_inicio = time.time()
            self._lote.append(t)
            try:
                self._procesar(t)
            except Exception as e:  # nunca dejar morir el hilo
                t.estado, t.error = "Error", f"Error interno: {e}"
                self._flog.exception("Error interno procesando %s", t.url)
            finally:
                self._actual = None
                self._emitir(("tarea", t.snapshot()))
            self._comprobar_fin_lote()

    def _pendientes(self) -> int:
        return sum(1 for x in self._tareas.values() if x.estado == "En cola")

    def _eta_cola(self, restantes: int) -> str:
        hechas = [x for x in self._lote if x.fin]
        if not hechas or restantes <= 0:
            return "calculando…" if restantes > 0 else "00:00"
        media = sum(x.fin - x.inicio for x in hechas) / len(hechas)
        return "~" + fmt_tiempo(media * restantes)

    def _procesar(self, t: Tarea):
        self._actual = t
        self._cancelar.clear()
        self._pp_avisados = set()
        t.titulo_fijo = False
        t.estado, t.inicio, t.fin = "Descargando", time.time(), 0.0
        t.archivos, t.tamano, t.error = [], 0, ""
        Path(t.carpeta).mkdir(parents=True, exist_ok=True)

        idx = len(self._lote)
        total = idx + self._pendientes()
        self.log("INFO", f"[{idx}/{total}] Inicio ({t.calidad}): {t.url}  · ETA cola {self._eta_cola(total - idx + 1)}")
        self._emitir(("tarea", t.snapshot()))

        try:
            self._descargar_ytdlp(t, t.url)
        except DownloadCancelled:
            t.estado = "Cancelada"
        except DownloadError as e:
            msg = str(e)
            if self._merece_sniffer(msg) and not self._cancelar.is_set():
                self.log("INFO", f"[{idx}/{total}] yt-dlp no reconoce la web; buscando vídeos en el HTML…")
                try:
                    if not self._sniffer(t):
                        t.estado, t.error = "Error", error_legible(msg)
                except DownloadCancelled:
                    t.estado = "Cancelada"
            else:
                t.estado, t.error = "Error", error_legible(msg)

        t.fin = time.time()
        if t.estado not in ("Error", "Cancelada"):
            t.estado, t.progreso, t.velocidad, t.eta = "OK", 100.0, "", ""
        dur = fmt_tiempo(t.fin - t.inicio)
        restantes = self._pendientes()
        eta = self._eta_cola(restantes)
        nombre = Path(t.archivos[-1]).name if t.archivos else t.titulo or t.url
        if t.estado == "OK":
            extra = f" (+{len(t.archivos) - 1} más)" if len(t.archivos) > 1 else ""
            self.log("INFO", f"[{idx}/{total}] OK en {dur} · {fmt_bytes(t.tamano)} · {nombre}{extra} · ETA cola {eta}")
        elif t.estado == "Cancelada":
            self.log("WARNING", f"[{idx}/{total}] Cancelada tras {dur}: {t.url}")
        else:
            self.log("ERROR", f"[{idx}/{total}] ERROR en {dur}: {t.error} · {t.url}")

    @staticmethod
    def _merece_sniffer(msg: str) -> bool:
        low = msg.lower()
        return "drm" not in low and any(
            k in low for k in ("unsupported url", "no video formats found", "unable to extract", "no media found")
        )

    # ---------------- yt-dlp ----------------
    def _opciones(self, t: Tarea, outtmpl: str | None = None, referer: str | None = None) -> dict:
        audio = t.calidad == AUDIO_MP3
        o = {
            "format": CALIDADES.get(t.calidad, CALIDADES["Mejor disponible"]),
            # Los parciales van a la carpeta de datos de la app: no ensucian el destino y se reanudan al reintentar
            "paths": {"home": t.carpeta, "temp": str(self.dir_temp)},
            "outtmpl": {"default": outtmpl or "%(title).150B [%(id)s].%(ext)s"},
            "windowsfilenames": True,
            "noplaylist": not t.playlist,
            "retries": 10,
            "fragment_retries": 10,
            "concurrent_fragment_downloads": 4,
            "continuedl": True,
            "quiet": True,
            "noprogress": True,
            "logger": _YtdlpLogger(self),
            "progress_hooks": [lambda d: self._hook_descarga(t, d)],
            "postprocessor_hooks": [lambda d: self._hook_post(t, d)],
            "post_hooks": [lambda f: self._hook_final(t, f)],
        }
        if self.ffmpeg:
            o["ffmpeg_location"] = self.ffmpeg
        if self.deno:
            o["js_runtimes"] = {"deno": {"path": self.deno}}
        if audio:
            o["postprocessors"] = [
                {"key": "FFmpegExtractAudio", "preferredcodec": "mp3", "preferredquality": "192"},
                {"key": "FFmpegMetadata"},
            ]
        else:
            o["merge_output_format"] = "mp4"
            o["format_sort"] = ["res", "ext:mp4:m4a"]  # a igual resolución, preferir MP4/H.264 (se abre en todo)
        if t.cookies and t.cookies != "Ninguno":
            o["cookiesfrombrowser"] = (t.cookies,)
        if referer:
            o["http_headers"] = {"Referer": referer}
        return o

    def _descargar_ytdlp(self, t: Tarea, url: str, outtmpl=None, referer=None):
        with yt_dlp.YoutubeDL(self._opciones(t, outtmpl, referer)) as ydl:
            ydl.download([url])
        if not t.archivos:
            raise DownloadError("No media found")

    def _hook_descarga(self, t: Tarea, d: dict):
        if self._cancelar.is_set():
            raise DownloadCancelled("Cancelada por el usuario")
        if d.get("status") != "downloading":
            return
        info = d.get("info_dict") or {}
        if not t.titulo_fijo:
            t.titulo = info.get("title") or t.titulo
        solo_audio = info.get("vcodec") == "none" and info.get("acodec") not in (None, "none")
        t.estado = "Descargando audio" if solo_audio and t.calidad != AUDIO_MP3 else "Descargando"
        total = d.get("total_bytes") or d.get("total_bytes_estimate")
        hecho = d.get("downloaded_bytes") or 0
        if total:
            t.progreso = min(100.0, hecho * 100.0 / total)
        elif d.get("fragment_count"):
            t.progreso = min(100.0, (d.get("fragment_index") or 0) * 100.0 / d["fragment_count"])
        t.velocidad = fmt_velocidad(d.get("speed"))
        t.eta = fmt_tiempo(d.get("eta"))
        ahora = time.time()
        if ahora - self._ultimo_emit > 0.25:  # no saturar la GUI
            self._ultimo_emit = ahora
            self._emitir(("tarea", t.snapshot()))

    def _hook_post(self, t: Tarea, d: dict):
        if self._cancelar.is_set():
            raise DownloadCancelled("Cancelada por el usuario")
        if d.get("status") == "started":
            nombre = d.get("postprocessor", "")
            clave = (nombre, (d.get("info_dict") or {}).get("id"))
            if clave in self._pp_avisados:  # yt-dlp a veces avisa dos veces del mismo paso
                return
            self._pp_avisados.add(clave)
            texto = {"Merger": "Uniendo audio y vídeo…", "ExtractAudio": "Convirtiendo a MP3…"}.get(nombre)
            t.estado, t.velocidad, t.eta = "Procesando", "", ""
            self._emitir(("tarea", t.snapshot()))
            if texto:
                self.log("INFO", f"    {texto}")

    def _hook_final(self, t: Tarea, filepath: str):
        t.archivos.append(filepath)
        try:
            t.tamano += os.path.getsize(filepath)
        except OSError:
            pass

    # ---------------- Fallback: buscar vídeos en el HTML ----------------
    def _sniffer(self, t: Tarea) -> bool:
        with yt_dlp.YoutubeDL({"quiet": True, "logger": _YtdlpLogger(self),
                               **({"cookiesfrombrowser": (t.cookies,)} if t.cookies != "Ninguno" else {})}) as ydl:
            resp = ydl.urlopen(t.url)
            raw = resp.read(8 * 1024 * 1024)
        texto = raw.decode("utf-8", errors="ignore")
        candidatos = self._extraer_candidatos(t.url, texto)
        if not candidatos:
            self.log("WARNING", "    No hay enlaces de vídeo en el HTML (puede que se carguen por JavaScript o tengan DRM).")
            return False

        m = RE_OG_TITLE.search(texto) or RE_TITLE.search(texto)
        titulo = sanitize_filename(html.unescape(m.group(1)).strip(), restricted=False)[:150] if m else "video"
        t.titulo, t.titulo_fijo = titulo or "video", True
        plantilla = t.titulo.replace("%", "%%") + ".%(ext)s"

        self.log("INFO", f"    {len(candidatos)} candidato(s) encontrados; probando el mejor…")
        for i, url in enumerate(candidatos[:5], 1):
            if self._cancelar.is_set():
                raise DownloadCancelled("Cancelada por el usuario")
            self.log("INFO", f"    Candidato {i}: {url[:150]}")
            try:
                self._descargar_ytdlp(t, url, outtmpl=plantilla, referer=t.url)
                return True
            except DownloadCancelled:
                raise
            except DownloadError as e:
                self.log("WARNING", f"    Candidato {i} falló: {error_legible(str(e))}")
        return False

    @staticmethod
    def _extraer_candidatos(base: str, texto: str) -> list[str]:
        t = texto.replace("\\/", "/").replace("\\u002F", "/").replace("\\u002f", "/")
        t = html.unescape(t)
        encontrados = [m.group(0) for m in RE_URL_ABS.finditer(t)]
        encontrados += [m.group(1) for m in RE_URL_COMILLAS.finditer(t)]
        vistos, salida = set(), []
        for u in encontrados:
            u = u.strip()
            if u.startswith("//"):
                u = "https:" + u
            u = urljoin(base, u)
            if not u.startswith("http") or u in vistos:
                continue
            vistos.add(u)
            salida.append(u)

        def rango(u: str) -> tuple:
            low = u.lower().split("?")[0]
            if low.endswith(".m3u8"):
                return (0 if "master" in low or "playlist" in low else 1,)
            if low.endswith(".mpd"):
                return (2,)
            if low.endswith((".mp4", ".m4v", ".mov")):
                return (3,)
            return (4,)

        return sorted(salida, key=rango)

    # ---------------- Resumen ----------------
    def _comprobar_fin_lote(self):
        if self._cola.empty() and self._lote and not any(x.estado == "En cola" for x in self._tareas.values()):
            self._resumen()

    def _resumen(self):
        lote, self._lote = self._lote, []
        ok = [x for x in lote if x.estado == "OK"]
        err = [x for x in lote if x.estado == "Error"]
        can = [x for x in lote if x.estado == "Cancelada"]
        dur = fmt_tiempo(time.time() - self._lote_inicio)
        total_bytes = sum(x.tamano for x in ok)
        self.log("INFO", "=" * 60)
        self.log("INFO", f"RESUMEN · Total: {len(lote)} · OK: {len(ok)} · Errores: {len(err)} · "
                         f"Canceladas: {len(can)} · Tiempo: {dur} · Descargado: {fmt_bytes(total_bytes) or '0 B'}")
        for x in err:
            self.log("ERROR", f"  ✗ {x.titulo or x.url} → {x.error}")
        self.log("INFO", "=" * 60)
        self._emitir(("resumen", {"total": len(lote), "ok": len(ok), "errores": len(err),
                                  "canceladas": len(can), "tiempo": dur}))
