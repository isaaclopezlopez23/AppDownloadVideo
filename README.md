# Descargador de vídeos y música (PC + Android)

Tres apps con el mismo motor, **yt-dlp**: soporta más de 1.700 webs (YouTube, Vimeo, X, Instagram, TikTok, SoundCloud, webs de noticias, cursos…).
Si yt-dlp no reconoce una web, entra un **plan B**: busca en el HTML enlaces directos `.m3u8` / `.mp4` / `.webm` / `.mpd` (en `<video>` o en el JSON del reproductor) y los descarga con el *Referer* correcto.

**Lo que no hace:** contenido con DRM (Netflix, Prime, Disney+, Movistar+, Filmin…). Se detecta y se avisa.
Ojo también con los términos de uso de cada web (YouTube, por ejemplo, no permite descargar fuera de su app).

```
pc/              Vídeo · app de escritorio (Python + Tkinter). Windows, y también Linux/macOS.
android/         Vídeo · app Android (Kotlin + Jetpack Compose + youtubedl-android).
android-musica/  Música · app Android solo de audio (ver más abajo).
.github/         Workflow que compila el .exe y los APK en GitHub, sin instalar nada.
```

---

## Música (Android)

App aparte, **"Música"** (en Compartir aparece como **"Descargar música"**), que saca solo el audio, bien etiquetado:

- **De cualquier web que soporte yt-dlp:** YouTube y YouTube Music, TikTok, X/Twitter, Instagram, SoundCloud, Bandcamp, Facebook, Twitch… Y si no la reconoce, busca enlaces de audio directos en la página.
- **Spotify, Apple Music, Deezer, Tidal, Shazam, Amazon Music:** su música va con DRM, así que la app lee el título y el artista del enlace y **busca esa canción en YouTube**. Solo funciona con canciones sueltas, no con álbumes ni listas, y en el log se ve qué ha buscado.
- **Búsqueda por texto:** escribe "artista canción" y elige entre los resultados de YouTube. Si compartes un texto sin enlace, la app se abre y lo busca.
- **Formatos:**
  - *MP3 320* y *MP3 192*: se oyen en cualquier sitio (coche, altavoces…).
  - *M4A*: AAC sin recodificar cuando la web lo da; YouTube sí lo da.
  - *Original*: el audio tal cual llega, sin pérdida extra (en YouTube, Opus).
  - Ojo: YouTube sirve unos 130-160 kbps, así que un MP3 320 no suena mejor que el M4A, solo ocupa más.
- **Etiquetas:**
  - artista y título (saca el artista de títulos tipo "Artista - Canción" y quita "(Official Video)", "[Lyrics]", etc.);
  - **carátula cuadrada** recortada de la miniatura;
  - en listas, álbum = nombre de la lista y número de pista.
- **Listas completas:** marca "Lista completa" y comparte una lista o un álbum. Se guarda en una subcarpeta con las pistas numeradas (`01 - Artista - Canción.mp3`…).
- **Quitar intros (YouTube):** con SponsorBlock recorta las partes sin música de los videoclips (intros, diálogos). Si su servidor no responde, la canción se guarda entera y la app lo avisa.
- Al terminar, **Escuchar** y **Compartir** desde la propia app. La notificación muestra el progreso, con "canción 3 de 12" en las listas.

Se compila igual que la de vídeo: el workflow genera el artefacto **DescargadorMusica-Android**. Instala `app-arm64-v8a-release.apk` y elige como carpeta de destino *Music* (o la que quieras) la primera vez.

---

## PC (Windows)

**Requisito:** Python 3.10 o superior ([python.org](https://www.python.org/downloads/), marca *"Add python.exe to PATH"*).

1. Doble clic en **`pc\iniciar.bat`**.
   - La primera vez crea un entorno virtual, instala yt-dlp + Deno y baja ffmpeg completo, con ffprobe (unos 90 MB). Tarda 1-2 minutos.
   - Cada vez que lo abres actualiza yt-dlp. Esto importa, porque las webs cambian a menudo y es lo que más rompe las descargas.
2. Pega una o varias URLs (una por línea) y pulsa **Descargar**. También puedes activar **"Vigilar portapapeles"**: cada enlace que copies en el navegador entra solo en la cola.

**Funciones**
- Cola secuencial con progreso, velocidad y ETA. Doble clic en una descarga terminada la muestra en el Explorador.
- Calidad: Mejor / 1080p / 720p / 480p / Solo audio (MP3). A igual resolución prefiere MP4/H.264, que se abre en cualquier reproductor.
- **Cookies de navegador** para webs con login (cursos, contenido de suscriptor). En Windows usa **Firefox**: Chrome y Edge cifran sus cookies y yt-dlp no suele poder leerlas.
- Cancelar, detener todo, reintentar errores. Los parciales quedan en `%APPDATA%\DescargadorVideos\temp`, así que al reintentar **se reanuda** donde se quedó.
- Log por descarga con hora, `[i/N]` y ETA de la cola, más un **resumen final** con OK, errores (y el motivo) y canceladas. También se guarda en `%APPDATA%\DescargadorVideos\logs\descargas_AAAA-MM-DD.log`.

**.exe independiente (opcional):** ejecuta `pc\compilar_exe.bat`. Genera `pc\dist\DescargadorVideos\` con Python, yt-dlp, ffmpeg y Deno dentro. Para actualizar yt-dlp en el .exe, vuelve a compilar (o usa `iniciar.bat`, que se actualiza solo).

> ¿Por qué Deno? Desde noviembre de 2025, yt-dlp necesita un runtime de JavaScript para sacar todas las calidades de YouTube. `iniciar.bat` lo instala con pip, sin tocar el sistema.

---

## Android (7.0 o superior)

### Compilar el APK — opción A: GitHub (sin instalar nada)
1. Sube la carpeta `descargador-videos` a un repositorio de GitHub (puede ser privado).
2. Pestaña **Actions** → **Compilar apps** → **Run workflow**.
3. En unos 10 minutos, descarga el artefacto **DescargadorVideos-Android**. Instala `app-arm64-v8a-release.apk`: es el de casi todos los móviles actuales.

### Compilar el APK — opción B: Android Studio
1. **File → Open** → carpeta `android`. Deja que sincronice Gradle; si te ofrece actualizar el plugin de Android, puedes aceptar.
2. **Build → Generate App Bundles or APKs → Generate APKs**.
3. El APK sale en `android/app/build/outputs/apk/`.

**Instalar:** pasa el APK al móvil, ábrelo y permite *"Instalar apps desconocidas"* para esa app (gestor de archivos, Drive…). No está en Play Store porque Google no admite apps que descarguen de YouTube.

### Uso
- **Lo cómodo:** en el navegador (o en YouTube, Instagram, X…) pulsa **Compartir → "Descargar vídeo"**. Se añade a la cola y descarga en segundo plano con notificación de progreso y botón *Cancelar*.
- **O bien:** abre la app, pega el enlace y pulsa *Descargar*.
- La primera vez elige la **carpeta de destino** (vale cualquiera, también de la tarjeta SD). Calidad y "listas completas" se recuerdan y son las que usa el menú Compartir.
- La primera vez que se abre, la app tarda unos segundos en preparar el motor (descomprime Python + yt-dlp). yt-dlp se actualiza solo una vez al día; también hay un botón *Actualizar yt-dlp*.
- *Ver log* muestra el detalle de cada descarga y el resumen final.

**Limitaciones en Android:** no usa cookies del navegador (las webs con login no funcionarán). Mientras descarga necesita el doble de espacio del vídeo, porque baja a la caché de la app y luego lo copia a tu carpeta.

### Firma propia (opcional)
Los APK de GitHub van firmados con una clave *debug* distinta en cada compilación, así que para actualizar tendrías que desinstalar antes. Para evitarlo:
1. Crea una clave una vez: `keytool -genkeypair -v -keystore release.jks -alias descargador -keyalg RSA -keysize 2048 -validity 10000`.
2. En GitHub → Settings → Secrets and variables → Actions, crea:
   - `ANDROID_KEYSTORE_BASE64`: el contenido de `base64 -w0 release.jks` (en Windows: `certutil -encode`).
   - `KEYSTORE_PASSWORD`, `KEY_ALIAS` (`descargador`) y `KEY_PASSWORD`.

---

## Solución de problemas
| Síntoma | Qué hacer |
|---|---|
| Fallos 403 o "unable to extract" en una web que antes iba | Actualiza yt-dlp (`iniciar.bat` lo hace solo; en Android, botón *Actualizar yt-dlp*). |
| "La web pide iniciar sesión" | En PC: elige *Firefox* en "Cookies de" (con la sesión iniciada en Firefox). |
| "No se ha encontrado ningún vídeo" | La web carga el vídeo por JavaScript tras interactuar, o lleva DRM. |
| YouTube da pocas calidades | Falta Deno (PC). Arranca con `iniciar.bat` o instala con `winget install DenoLand.Deno`. |
