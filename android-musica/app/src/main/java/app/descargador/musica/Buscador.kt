package app.descargador.musica

import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONObject

/** Búsqueda por texto en YouTube con yt-dlp ("ytsearchN:…"), sin descargar nada. */
object Buscador {
    /** Bloqueante: llamar fuera del hilo principal. */
    fun buscar(consulta: String, cuantos: Int = 15): List<Resultado> {
        val req = YoutubeDLRequest("ytsearch$cuantos:${consulta.trim()}")
            .addOption("--flat-playlist")
            .addOption("-J")
            .addOption("--no-warnings")
        val salida = YoutubeDL.getInstance().execute(req, null, null).out
        val json = salida.substring(salida.indexOf('{').coerceAtLeast(0))
        val entradas = JSONObject(json).optJSONArray("entries") ?: return emptyList()
        return (0 until entradas.length()).mapNotNull { i ->
            val e = entradas.optJSONObject(i) ?: return@mapNotNull null
            val id = e.optString("id").takeIf { it.isNotBlank() && it != "null" } ?: return@mapNotNull null
            val url = e.optString("url").takeIf { it.startsWith("http") } ?: "https://www.youtube.com/watch?v=$id"
            Resultado(
                url = url,
                titulo = e.optString("title").takeUnless { it.isBlank() || it == "null" } ?: id,
                canal = sequenceOf("channel", "uploader").map { e.optString(it) }
                    .firstOrNull { it.isNotBlank() && it != "null" }.orEmpty(),
                duracionSeg = e.optDouble("duration", -1.0).let { if (it.isNaN()) -1L else it.toLong() },
            )
        }.distinctBy { it.url }
    }
}
