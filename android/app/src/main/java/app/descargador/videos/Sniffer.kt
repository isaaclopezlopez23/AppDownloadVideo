package app.descargador.videos

import android.text.Html
import java.net.HttpURLConnection
import java.net.URL

/**
 * Plan B cuando yt-dlp no reconoce una web: descarga el HTML y busca enlaces directos
 * a .m3u8 / .mp4 / .webm / .mpd (en <video>, en el JSON del reproductor, etc.).
 * No hace nada con contenido cifrado (DRM).
 */
object Sniffer {
    private const val EXT = "(?:m3u8|mp4|webm|mpd|m4v|mov)"
    private val RE_ABS = Regex("""(?:https?:)?//[^\s"'<>()\\]+?\.$EXT(?:\?[^\s"'<>\\]*)?""", RegexOption.IGNORE_CASE)
    private val RE_COMILLAS = Regex("""["']([^"'\s<>]+?\.$EXT(?:\?[^"'\s<>]*)?)["']""", RegexOption.IGNORE_CASE)
    private val RE_TITLE = Regex("""<title[^>]*>(.*?)</title>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val RE_OG = Regex("""<meta[^>]+property=["']og:title["'][^>]+content=["']([^"']+)""", RegexOption.IGNORE_CASE)
    private const val UA =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36"

    data class Pagina(val urlFinal: String, val html: String)

    fun descargarHtml(url: String): Pagina? = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.instanceFollowRedirects = true
        c.connectTimeout = 15_000
        c.readTimeout = 20_000
        c.setRequestProperty("User-Agent", UA)
        c.setRequestProperty("Accept", "text/html,application/xhtml+xml,*/*")
        try {
            val bytes = c.inputStream.use { it.readNBytesCompat(8 * 1024 * 1024) }
            Pagina(c.url.toString(), String(bytes, Charsets.UTF_8))
        } finally {
            c.disconnect()
        }
    } catch (e: Exception) {
        null
    }

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

    private fun rango(u: String): Int {
        val low = u.lowercase().substringBefore('?')
        return when {
            low.endsWith(".m3u8") -> if ("master" in low || "playlist" in low) 0 else 1
            low.endsWith(".mpd") -> 2
            low.endsWith(".mp4") || low.endsWith(".m4v") || low.endsWith(".mov") -> 3
            else -> 4
        }
    }

    fun titulo(html: String): String? {
        val crudo = RE_OG.find(html)?.groupValues?.get(1) ?: RE_TITLE.find(html)?.groupValues?.get(1) ?: return null
        @Suppress("DEPRECATION")
        val limpio = Html.fromHtml(crudo).toString().trim()
        return limpio.ifBlank { null }
    }

    /** Título → nombre de fichero seguro, escapando % para la plantilla de yt-dlp. */
    fun nombreSeguro(titulo: String): String =
        titulo.replace(Regex("""[\\/:*?"<>|\r\n\t]"""), "_").trim().take(120).ifBlank { "video" }.replace("%", "%%")

    private fun java.io.InputStream.readNBytesCompat(max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        var total = 0
        while (total < max) {
            val n = read(buf, 0, minOf(buf.size, max - total))
            if (n < 0) break
            out.write(buf, 0, n)
            total += n
        }
        return out.toByteArray()
    }
}
