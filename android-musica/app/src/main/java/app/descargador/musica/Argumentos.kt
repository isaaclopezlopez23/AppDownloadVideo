package app.descargador.musica

import java.io.File

/**
 * Argumentos de yt-dlp para sacar el audio bien etiquetado.
 * Probados en local con yt-dlp real (ver README): MP3/M4A/Original, carátula cuadrada,
 * artista/título separados, títulos limpios y listas numeradas en subcarpeta.
 */
object Argumentos {
    /** Recorta la miniatura (16:9 en YouTube) a un cuadrado centrado, como una portada de disco. */
    const val PPA_CUADRADA =
        "ThumbnailsConvertor+FFmpeg_o:-c:v mjpeg -vf crop=\"'if(gt(ih,iw),iw,ih)':'if(gt(iw,ih),ih,iw)'\""

    /** Si la web no da artista, lo saca de títulos tipo "Artista - Canción" (también – y —). */
    const val PARSE_ARTISTA =
        """%(artist|)s|%(title)s:^\|(?P<meta_artist>.+?)\s+[-–—]\s+(?P<meta_title>.+)$"""

    /** Quita "(Official Video)", "[Lyrics]", "(Audio)", "[HD]"… del título. */
    const val LIMPIAR_TITULO =
        """(?i)\s*[\(\[][^\)\]]*\b(?:official|oficial|video|vídeo|videoclip|audio|lyrics?|letra|visuali[sz]er|hd|hq|4k|mv)\b[^\)\]]*[\)\]]"""

    /** Los títulos de X/Twitter acaban en un enlace t.co. */
    const val QUITAR_URLS = """\s*https?://\S+"""

    /** En listas: el nombre de la lista como álbum (si no hay álbum) y la posición como nº de pista. */
    const val PARSE_ALBUM = """%(album|)s|%(playlist_title|)s:^\|(?P<meta_album>.+)$"""
    const val PARSE_PISTA = """%(playlist_index|)s:^(?P<meta_track>\d+)$"""

    const val NOMBRE =
        """%(meta_artist,artist,creator,uploader,channel|Desconocido).80B - %(meta_title,track,title).120B.%(ext)s"""
    const val NOMBRE_LISTA = """%(playlist_title,playlist|Lista).80B/%(playlist_index|0)02d - """ + NOMBRE

    fun construir(
        formato: Formato,
        caratula: Boolean,
        lista: Boolean,
        sinRelleno: Boolean,
        tmp: File,
        plantilla: String? = null,
        referer: String? = null,
    ): List<String> {
        val a = mutableListOf("-f", formato.selector, "-S", "res:480", "-x", "--audio-format", formato.codec)
        formato.calidad?.let { a += listOf("--audio-quality", it) }
        a += "--embed-metadata"
        if (caratula) a += listOf("--embed-thumbnail", "--convert-thumbnails", "jpg", "--ppa", PPA_CUADRADA)
        a += if (lista) "--yes-playlist" else "--no-playlist"
        if (sinRelleno) a += listOf("--sponsorblock-remove", "music_offtopic")
        a += listOf(
            "--parse-metadata", PARSE_ARTISTA,
            "--replace-in-metadata", "title,meta_title", LIMPIAR_TITULO, "",
            "--replace-in-metadata", "title,meta_title", QUITAR_URLS, "",
        )
        if (lista) a += listOf("--parse-metadata", PARSE_ALBUM, "--parse-metadata", PARSE_PISTA)
        a += listOf("-o", File(tmp, plantilla ?: if (lista) NOMBRE_LISTA else NOMBRE).absolutePath)
        a += listOf(
            "--windows-filenames", "--no-mtime", "--retries", "10", "--fragment-retries", "10",
            "--concurrent-fragments", "4",
        )
        referer?.let { a += listOf("--add-headers", "Referer:$it") }
        return a
    }

    /**
     * yt-dlp devuelve error si no puede consultar SponsorBlock o incrustar la carátula,
     * pero el audio queda completo y etiquetado: eso es un aviso, no un fallo.
     */
    fun soloAvisos(err: String?): Boolean {
        val errores = err.orEmpty().lines().filter { it.startsWith("ERROR:") }
        return errores.isNotEmpty() && errores.all { l ->
            val low = l.lowercase()
            "sponsorblock" in low || "thumbnail" in low || "mutagen" in low
        }
    }
}
