"""
Descargador de vídeos — interfaz de escritorio (Tkinter).

Uso: pega una o varias URLs (una por línea) o activa "Vigilar portapapeles"
y copia enlaces desde el navegador; se van añadiendo a la cola solos.
"""
import datetime as dt
import json
import os
import queue
import re
import subprocess
import sys
import threading
import tkinter as tk
from pathlib import Path
from tkinter import filedialog, messagebox, ttk
from tkinter.scrolledtext import ScrolledText

from motor import (CALIDADES, NAVEGADORES, Motor, carpeta_por_defecto, dir_datos,
                   fmt_bytes)

RE_URL = re.compile(r"^https?://\S+$", re.I)
CONFIG = dir_datos() / "config.json"

COLUMNAS = [
    ("estado", "Estado", 120),
    ("titulo", "Título / URL", 420),
    ("progreso", "Progreso", 80),
    ("tamano", "Tamaño", 90),
    ("velocidad", "Velocidad", 95),
    ("eta", "ETA", 70),
]


def abrir_en_sistema(ruta: str, seleccionar=False):
    try:
        if sys.platform == "win32":
            if seleccionar and os.path.isfile(ruta):
                subprocess.Popen(["explorer", "/select,", os.path.normpath(ruta)])
            else:
                os.startfile(ruta)  # noqa
        elif sys.platform == "darwin":
            subprocess.Popen(["open", "-R", ruta] if seleccionar else ["open", ruta])
        else:
            subprocess.Popen(["xdg-open", os.path.dirname(ruta) if seleccionar else ruta])
    except Exception as e:
        messagebox.showerror("Error", f"No se pudo abrir:\n{ruta}\n\n{e}")


class App(tk.Tk):
    def __init__(self):
        super().__init__()
        self.title("Descargador de vídeos")
        self.geometry("980x680")
        self.minsize(760, 520)

        estilo = ttk.Style(self)
        if "vista" in estilo.theme_names():
            estilo.theme_use("vista")
        elif "clam" in estilo.theme_names():
            estilo.theme_use("clam")

        self.cfg = self._cargar_config()
        self.eventos: queue.Queue = queue.Queue()
        self.motor = Motor(self.eventos.put)
        self.filas: dict[int, str] = {}          # id tarea -> id fila Treeview
        self.datos: dict[int, dict] = {}         # id tarea -> último snapshot
        self.quitadas: set[int] = set()
        self._ultimo_portapapeles = ""

        self._construir()
        self._log_inicio()
        self.after(100, self._procesar_eventos)
        self.after(1000, self._vigilar_portapapeles)
        self.protocol("WM_DELETE_WINDOW", self._cerrar)

    # ------------------------------------------------------------------ UI
    def _construir(self):
        pad = {"padx": 8, "pady": 4}
        top = ttk.Frame(self)
        top.pack(fill="x", **pad)

        ttk.Label(top, text="URL:").grid(row=0, column=0, sticky="w")
        self.var_url = tk.StringVar()
        e = ttk.Entry(top, textvariable=self.var_url)
        e.grid(row=0, column=1, sticky="ew", padx=4)
        e.bind("<Return>", lambda _e: self._anadir())
        e.focus_set()
        ttk.Button(top, text="Pegar", command=self._pegar).grid(row=0, column=2, padx=2)
        ttk.Button(top, text="Descargar", command=self._anadir).grid(row=0, column=3, padx=2)

        ttk.Label(top, text="Carpeta:").grid(row=1, column=0, sticky="w", pady=(6, 0))
        self.var_carpeta = tk.StringVar(value=self.cfg.get("carpeta", str(carpeta_por_defecto())))
        ttk.Entry(top, textvariable=self.var_carpeta).grid(row=1, column=1, sticky="ew", padx=4, pady=(6, 0))
        ttk.Button(top, text="Examinar…", command=self._elegir_carpeta).grid(row=1, column=2, padx=2, pady=(6, 0))
        ttk.Button(top, text="Abrir", command=lambda: self._abrir_carpeta()).grid(row=1, column=3, padx=2, pady=(6, 0))
        top.columnconfigure(1, weight=1)

        opts = ttk.Frame(self)
        opts.pack(fill="x", **pad)
        ttk.Label(opts, text="Calidad:").pack(side="left")
        self.var_calidad = tk.StringVar(value=self.cfg.get("calidad", "Mejor disponible"))
        ttk.Combobox(opts, textvariable=self.var_calidad, values=list(CALIDADES), state="readonly",
                     width=18).pack(side="left", padx=(4, 14))
        ttk.Label(opts, text="Cookies de:").pack(side="left")
        self.var_cookies = tk.StringVar(value=self.cfg.get("cookies", "Ninguno"))
        ttk.Combobox(opts, textvariable=self.var_cookies, values=NAVEGADORES, state="readonly",
                     width=10).pack(side="left", padx=(4, 14))
        self.var_playlist = tk.BooleanVar(value=self.cfg.get("playlist", False))
        ttk.Checkbutton(opts, text="Listas completas", variable=self.var_playlist).pack(side="left", padx=(0, 14))
        self.var_vigilar = tk.BooleanVar(value=False)
        ttk.Checkbutton(opts, text="Vigilar portapapeles", variable=self.var_vigilar,
                        command=self._toggle_vigilar).pack(side="left")

        # Tabla de descargas
        marco = ttk.Frame(self)
        marco.pack(fill="both", expand=True, **pad)
        self.tabla = ttk.Treeview(marco, columns=[c[0] for c in COLUMNAS], show="headings", selectmode="extended")
        for clave, titulo, ancho in COLUMNAS:
            self.tabla.heading(clave, text=titulo)
            self.tabla.column(clave, width=ancho, stretch=(clave == "titulo"),
                              anchor="w" if clave in ("titulo", "estado") else "e")
        sb = ttk.Scrollbar(marco, orient="vertical", command=self.tabla.yview)
        self.tabla.configure(yscrollcommand=sb.set)
        self.tabla.pack(side="left", fill="both", expand=True)
        sb.pack(side="right", fill="y")
        self.tabla.tag_configure("ok", foreground="#1b7f3b")
        self.tabla.tag_configure("error", foreground="#b3261e")
        self.tabla.tag_configure("cancelada", foreground="#777777")
        self.tabla.tag_configure("activa", foreground="#0b57d0")
        self.tabla.bind("<Double-1>", lambda _e: self._abrir_seleccion())
        self.tabla.bind("<Button-3>", self._menu_contextual)
        self.tabla.bind("<Delete>", lambda _e: self._quitar_seleccion())

        self.menu = tk.Menu(self, tearoff=0)
        self.menu.add_command(label="Mostrar en carpeta", command=self._abrir_seleccion)
        self.menu.add_command(label="Reintentar", command=self._reintentar_seleccion)
        self.menu.add_command(label="Copiar URL", command=self._copiar_url)
        self.menu.add_separator()
        self.menu.add_command(label="Quitar de la lista", command=self._quitar_seleccion)

        botones = ttk.Frame(self)
        botones.pack(fill="x", **pad)
        ttk.Button(botones, text="Cancelar actual", command=self.motor.cancelar_actual).pack(side="left")
        ttk.Button(botones, text="Detener todo", command=self.motor.cancelar_todo).pack(side="left", padx=4)
        ttk.Button(botones, text="Reintentar errores", command=self._reintentar_errores).pack(side="left", padx=4)
        ttk.Button(botones, text="Limpiar terminadas", command=self._limpiar).pack(side="left", padx=4)
        ttk.Button(botones, text="Actualizar yt-dlp", command=self._actualizar).pack(side="right")
        ttk.Button(botones, text="Ver log", command=lambda: abrir_en_sistema(str(self.motor.fichero_log))).pack(side="right", padx=4)

        self.log_txt = ScrolledText(self, height=10, state="disabled", wrap="word",
                                    font=("Consolas", 9) if sys.platform == "win32" else ("Monospace", 9))
        self.log_txt.pack(fill="both", expand=False, padx=8, pady=(0, 4))
        self.log_txt.tag_configure("WARNING", foreground="#a15c00")
        self.log_txt.tag_configure("ERROR", foreground="#b3261e")

        self.var_estado = tk.StringVar(value="Listo")
        ttk.Label(self, textvariable=self.var_estado, anchor="w", relief="sunken").pack(fill="x", side="bottom")

    def _log_inicio(self):
        env = self.motor.info_entorno()
        self._log("INFO", f"[{dt.datetime.now():%H:%M:%S}] yt-dlp {env['yt_dlp']} · "
                          f"ffmpeg: {'OK' if env['ffmpeg'] else 'NO'} · deno: {'OK' if env['deno'] else 'NO'}")
        self._log("INFO", f"Log en disco: {env['log']}")
        if not env["ffmpeg"]:
            self._log("WARNING", "Sin ffmpeg: no se podrá unir audio+vídeo en alta calidad ni sacar MP3.")
        elif not env["ffprobe"]:
            self._log("WARNING", "ffmpeg sin ffprobe: funciona, pero arranca con iniciar.bat para tener el ffmpeg completo.")
        if not env["deno"]:
            self._log("WARNING", "Sin Deno: YouTube puede dar menos calidades o fallar. "
                                 "Instálalo con:  winget install DenoLand.Deno")
        try:
            ver = dt.datetime.strptime(env["yt_dlp"][:10], "%Y.%m.%d")
            dias = (dt.datetime.now() - ver).days
            if dias > 45:
                self._log("WARNING", f"yt-dlp tiene {dias} días. Si empiezan a fallar descargas, actualízalo.")
        except ValueError:
            pass

    # ----------------------------------------------------------- acciones
    def _urls_de_texto(self, texto: str) -> list[str]:
        return [l.strip() for l in texto.split() if RE_URL.match(l.strip())]

    def _pegar(self):
        try:
            texto = self.clipboard_get()
        except tk.TclError:
            return
        urls = self._urls_de_texto(texto)
        if len(urls) > 1:
            for u in urls:
                self._encolar(u)
        elif urls:
            self.var_url.set(urls[0])

    def _anadir(self):
        urls = self._urls_de_texto(self.var_url.get())
        if not urls:
            messagebox.showwarning("URL no válida", "Pega una dirección que empiece por http:// o https://")
            return
        for u in urls:
            self._encolar(u)
        self.var_url.set("")

    def _encolar(self, url: str):
        carpeta = self.var_carpeta.get().strip() or str(carpeta_por_defecto())
        self.motor.encolar(url, self.var_calidad.get(), carpeta, self.var_playlist.get(), self.var_cookies.get())
        self._guardar_config()

    def _elegir_carpeta(self):
        d = filedialog.askdirectory(initialdir=self.var_carpeta.get() or str(Path.home()))
        if d:
            self.var_carpeta.set(d)
            self._guardar_config()

    def _abrir_carpeta(self):
        d = Path(self.var_carpeta.get())
        d.mkdir(parents=True, exist_ok=True)
        abrir_en_sistema(str(d))

    def _seleccion(self) -> list[int]:
        inv = {v: k for k, v in self.filas.items()}
        return [inv[i] for i in self.tabla.selection() if i in inv]

    def _abrir_seleccion(self):
        for tid in self._seleccion()[:1]:
            archivos = self.datos.get(tid, {}).get("archivos") or []
            if archivos and os.path.exists(archivos[-1]):
                abrir_en_sistema(archivos[-1], seleccionar=True)
            else:
                self._abrir_carpeta()

    def _reintentar_seleccion(self):
        for tid in self._seleccion():
            self.motor.reintentar(tid)

    def _reintentar_errores(self):
        for tid, d in list(self.datos.items()):
            if d["estado"] in ("Error", "Cancelada"):
                self.motor.reintentar(tid)

    def _copiar_url(self):
        urls = [self.datos[t]["url"] for t in self._seleccion() if t in self.datos]
        if urls:
            self.clipboard_clear()
            self.clipboard_append("\n".join(urls))
            self._ultimo_portapapeles = "\n".join(urls)  # que el vigilante no lo re-añada

    def _quitar_fila(self, tid: int):
        if self.motor.quitar(tid):
            self.quitadas.add(tid)
            self.tabla.delete(self.filas.pop(tid))
            self.datos.pop(tid, None)

    def _quitar_seleccion(self):
        for tid in self._seleccion():
            self._quitar_fila(tid)

    def _limpiar(self):
        for tid, d in list(self.datos.items()):
            if d["estado"] in ("OK", "Error", "Cancelada"):
                self._quitar_fila(tid)

    def _menu_contextual(self, ev):
        fila = self.tabla.identify_row(ev.y)
        if fila:
            if fila not in self.tabla.selection():
                self.tabla.selection_set(fila)
            self.menu.tk_popup(ev.x_root, ev.y_root)

    def _actualizar(self):
        self._log("INFO", f"[{dt.datetime.now():%H:%M:%S}] Actualizando yt-dlp…")

        def trabajo():
            ok, msg = self.motor.actualizar_ytdlp()
            self.eventos.put(("log", "INFO" if ok else "ERROR", f"[{dt.datetime.now():%H:%M:%S}] {msg}"))

        threading.Thread(target=trabajo, daemon=True).start()

    # -------------------------------------------------- portapapeles
    def _toggle_vigilar(self):
        if self.var_vigilar.get():
            try:
                self._ultimo_portapapeles = self.clipboard_get()
            except tk.TclError:
                self._ultimo_portapapeles = ""
            self.var_estado.set("Vigilando portapapeles: copia un enlace en el navegador y se añadirá solo")

    def _vigilar_portapapeles(self):
        if self.var_vigilar.get():
            try:
                texto = self.clipboard_get()
            except tk.TclError:
                texto = ""
            if texto and texto != self._ultimo_portapapeles:
                self._ultimo_portapapeles = texto
                for u in self._urls_de_texto(texto):
                    self._encolar(u)
        self.after(1000, self._vigilar_portapapeles)

    # -------------------------------------------------- eventos del motor
    def _procesar_eventos(self):
        try:
            while True:
                ev = self.eventos.get_nowait()
                tipo = ev[0]
                if tipo == "tarea":
                    self._pintar_tarea(ev[1])
                elif tipo == "log":
                    self._log(ev[1], ev[2])
                elif tipo == "resumen":
                    r = ev[1]
                    self.var_estado.set(f"Terminado · OK {r['ok']} · Errores {r['errores']} · "
                                        f"Canceladas {r['canceladas']} · {r['tiempo']}")
                    self.bell()
        except queue.Empty:
            pass
        self.after(100, self._procesar_eventos)

    def _pintar_tarea(self, d: dict):
        if d["id"] in self.quitadas:
            return  # el usuario la quitó de la lista
        self.datos[d["id"]] = d
        estado = d["estado"]
        nombre = d["titulo"] or d["url"]
        valores = (
            estado,
            f"{d['error']}  —  {nombre}" if estado == "Error" and d["error"] else nombre,
            f"{d['progreso']:.1f} %" if d["progreso"] else "",
            fmt_bytes(d["tamano"]),
            d["velocidad"],
            d["eta"],
        )
        tag = {"OK": "ok", "Error": "error", "Cancelada": "cancelada"}.get(estado,
                                                                           "" if estado == "En cola" else "activa")
        if d["id"] in self.filas:
            self.tabla.item(self.filas[d["id"]], values=valores, tags=(tag,))
        else:
            self.filas[d["id"]] = self.tabla.insert("", "end", values=valores, tags=(tag,))
        if estado.startswith("Descargando") or estado == "Procesando":
            self.var_estado.set(f"{estado}: {nombre}")

    def _log(self, nivel: str, texto: str):
        self.log_txt.configure(state="normal")
        self.log_txt.insert("end", texto + "\n", (nivel,))
        lineas = int(self.log_txt.index("end-1c").split(".")[0])
        if lineas > 2000:
            self.log_txt.delete("1.0", f"{lineas - 2000}.0")
        self.log_txt.see("end")
        self.log_txt.configure(state="disabled")

    # -------------------------------------------------- config
    def _cargar_config(self) -> dict:
        try:
            return json.loads(CONFIG.read_text(encoding="utf-8"))
        except Exception:
            return {}

    def _guardar_config(self):
        cfg = {
            "carpeta": self.var_carpeta.get(),
            "calidad": self.var_calidad.get(),
            "cookies": self.var_cookies.get(),
            "playlist": self.var_playlist.get(),
        }
        try:
            CONFIG.write_text(json.dumps(cfg, indent=2, ensure_ascii=False), encoding="utf-8")
        except OSError:
            pass

    def _cerrar(self):
        activas = [d for d in self.datos.values()
                   if d["estado"] not in ("OK", "Error", "Cancelada")]
        if activas and not messagebox.askyesno("Salir", f"Hay {len(activas)} descarga(s) en curso o en cola. ¿Salir igualmente?"):
            return
        self._guardar_config()
        self.destroy()


if __name__ == "__main__":
    App().mainloop()
