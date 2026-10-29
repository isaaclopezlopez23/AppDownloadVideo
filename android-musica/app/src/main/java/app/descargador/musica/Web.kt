package app.descargador.musica

import android.text.Html
import java.net.HttpURLConnection
import java.net.URL

/** Utilidades sobre el HTML de una página: descargarlo, leer metadatos y buscar enlaces de audio/vídeo. */
object Web {
    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36"

    data class Pagina(val urlFinal: String, val html: String)

    fun descargar(url: String): Pagina? = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.instanceFollowRedirects = true
        c.connectTimeout = 15_000
        c.readTimeout = 20_000
        c.setRequestProperty("User-Agent", UA)
        c.setRequestProperty("Accept", "text/html,application/xhtml+xml,*/*")
        // En inglés: los títulos de las tiendas tienen un formato fijo ("X by Y on Apple Music") fácil de limpiar
        c.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
        try {
            val bytes = c.inputStream.use { leer(it, 8 * 1024 * 1024) }
            Pagina(c.url.toString(), String(bytes, Charsets.UTF_8))
        } finally {
            c.disconnect()
        }
    } catch (e: Exception) {
        null
    }

    fun meta(html: String, nombre: String): String? {
        val n = Regex.escape(nombre)
        val r1 = Regex("""<meta[^>]+(?:property|name)=["']$n["'][^>]*content=["']([^"']*)""", RegexOption.IGNORE_CASE)
        val r2 = Regex("""<meta[^>]+content=["']([^"']*)["'][^>]*(?:property|name)=["']$n["']""", RegexOption.IGNORE_CASE)
        val v = (r1.find(html) ?: r2.find(html))?.groupValues?.get(1) ?: return null
        return texto(v)
    }

    fun titulo(html: String): String? =
        meta(html, "og:title")
            ?: Regex("""<title[^>]*>(.*?)</title>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                .find(html)?.groupValues?.get(1)?.let { texto(it) }

    @Suppress("DEPRECATION")
    private fun texto(s: String): String? = Html.fromHtml(s).toString()
        .replace("‎", "").replace("‏", "")
        .replace(Regex("""\s+"""), " ").trim().ifBlank { null }

    // ---------------------------------------------------------------- plan B: enlaces directos
    private const val EXT = "(?:mp3|m4a|aac|ogg|oga|opus|wav|flac|m3u8|mp4|webm|mpd|m4v|mov)"
    private val RE_ABS = Regex("""(?:https?:)?//[^\s"'<>()\\]+?\.$EXT(?:\?[^\s"'<>\\]*)?""", RegexOption.IGNORE_CASE)
    private val RE_COMILLAS = Regex("""["']([^"'\s<>]+?\.$EXT(?:\?[^"'\s<>]*)?)["']""", RegexOption.IGNORE_CASE)

    fun candidatos(base: String, html: String): List<String> {
        val t = html.replace("\\/", "/").replace("\\u002F", "/").replace("\\u002f", "/")
            .replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")
        val encontrados = RE_ABS.findAll(t).map { it.value } + RE_COMILLAS.findAll(t).map { it.groupValues[1] }
        val salida = LinkedHashSet<String>()
        for (crudo in encontrados) {
            var u = crudo.trim()
            if (u.startsWith("//")) u = "https:$u"
            val abs = runCatching { URL(URL(base), u).toString() }.getOrNull() ?: continue
            if (abs.startsWith("http")) salida += abs
        }
        return salida.sortedBy { rango(it) }
    }

    /** Primero audio directo, luego streams y por último vídeo. */
    private fun rango(u: String): Int {
        val low = u.lowercase().substringBefore('?')
        return when {
            listOf(".mp3", ".m4a", ".aac", ".ogg", ".oga", ".opus", ".flac", ".wav").any { low.endsWith(it) } -> 0
            low.endsWith(".m3u8") -> if ("master" in low || "playlist" in low) 1 else 2
            low.endsWith(".mpd") -> 3
            else -> 4
        }
    }

    /** Título → nombre de fichero seguro, escapando % para la plantilla de yt-dlp. */
    fun nombreSeguro(titulo: String): String =
        titulo.replace(Regex("""[\\/:*?"<>|\r\n\t]"""), "_").trim().take(120).ifBlank { "audio" }.replace("%", "%%")

    private fun leer(input: java.io.InputStream, max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        var total = 0
        while (total < max) {
            val n = input.read(buf, 0, minOf(buf.size, max - total))
            if (n < 0) break
            out.write(buf, 0, n)
            total += n
        }
        return out.toByteArray()
    }
}

/**
 * Spotify, Apple Music, Deezer, Tidal, Shazam… tienen la música cifrada (DRM) o solo dan avances de 30 s.
 * Lo que sí se puede es leer el título y el artista de la página y buscar esa canción en YouTube.
 */
object Tiendas {
    private val HOSTS = mapOf(
        "open.spotify.com" to "Spotify", "spotify.link" to "Spotify", "spoti.fi" to "Spotify",
        "music.apple.com" to "Apple Music", "itunes.apple.com" to "Apple Music",
        "deezer.com" to "Deezer", "www.deezer.com" to "Deezer", "deezer.page.link" to "Deezer",
        "link.deezer.com" to "Deezer", "dzr.page.link" to "Deezer",
        "tidal.com" to "Tidal", "listen.tidal.com" to "Tidal",
        "shazam.com" to "Shazam", "www.shazam.com" to "Shazam",
        "music.amazon.com" to "Amazon Music", "music.amazon.es" to "Amazon Music",
    )

    fun nombre(url: String): String? =
        runCatching { URL(url).host.lowercase() }.getOrNull()?.let { HOSTS[it] }

    /** Devuelve la búsqueda para YouTube ("Artista Canción") o lanza una excepción con el motivo. */
    fun consulta(url: String): String {
        val tienda = nombre(url) ?: throw IllegalArgumentException("No es una tienda de música")
        val pag = Web.descargar(url) ?: throw IllegalStateException("No se pudo abrir el enlace de $tienda")
        val ruta = runCatching { URL(pag.urlFinal).path.lowercase() + "?" + URL(pag.urlFinal).query.orEmpty() }
            .getOrDefault("")
        val esCancion = when (tienda) {
            "Spotify" -> "/track/" in ruta
            "Apple Music" -> "/song/" in ruta || "i=" in ruta
            "Deezer", "Tidal" -> "/track/" in ruta
            "Shazam" -> "/song/" in ruta || "/track/" in ruta
            else -> true
        }
        if (!esCancion) throw IllegalStateException("De $tienda solo se pueden bajar canciones sueltas, no álbumes ni listas")

        val og = Web.titulo(pag.html) ?: throw IllegalStateException("No se pudo leer el título en $tienda")
        val artista = when (tienda) {
            // og:description de Spotify: "Artista · Álbum · Song · 2020"
            "Spotify" -> Web.meta(pag.html, "music:musician_description")
                ?: Web.meta(pag.html, "og:description")?.split(" · ")?.firstOrNull()
            else -> null
        }
        val limpio = limpiarTitulo(og, tienda)
        val consulta = listOfNotNull(artista?.takeIf { it.isNotBlank() && !limpio.contains(it, true) }, limpio)
            .joinToString(" ").trim()
        if (consulta.isBlank()) throw IllegalStateException("No se pudo leer el título en $tienda")
        return consulta
    }

    /**
     * "Stand by Me by Ben E. King on Apple Music" → "Stand by Me Ben E. King"
     * "Stand by Me - song and lyrics by Ben E. King | Spotify" → "Stand by Me Ben E. King"
     * "Stand by Me - Ben E. King: Song Lyrics, Music Videos & Concerts" (Shazam) → "Stand by Me - Ben E. King"
     */
    fun limpiarTitulo(og: String, tienda: String): String {
        var s = og
        s = s.replace(Regex("""(?i):\s*song lyrics.*$"""), "")
        s = s.replace(Regex("""(?i)\s+-\s+song (?:and lyrics )?by\s+"""), " ")
        s = s.replace(Regex("""(?i)\s+(?:on\s+|[|–—-]\s+)(?:apple music|spotify|deezer|tidal|shazam|amazon music)\b.*$"""), "")
        if (tienda == "Apple Music" || tienda == "Tidal" || tienda == "Amazon Music") {
            // el último " by " separa canción y artista ("Stand by Me by Ben E. King")
            Regex("""(?i)^(.+)\s+by\s+(.+)$""").find(s)?.let { s = "${it.groupValues[1]} ${it.groupValues[2]}" }
        }
        return s.replace(Regex("""[–—]"""), " ").replace(Regex("""\s+"""), " ").trim()
    }
}
