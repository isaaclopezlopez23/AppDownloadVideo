package app.conversor.video

// Sin dependencias de Android: se prueba en el PC con el ffprobe de escritorio.

/** Una pista (stream) del fichero tal como la describe ffprobe. */
data class Pista(
    val indice: Int,
    val tipo: String,          // video, audio, subtitle, attachment, data
    val codec: String,         // h264, hevc, ac3, dts, aac, subrip…
    val perfil: String = "",
    val ancho: Int = 0,
    val alto: Int = 0,
    val pixFmt: String = "",
    val canales: Int = 0,
    val frecuencia: Int = 0,
    val idioma: String = "",
    val titulo: String = "",
    val porDefecto: Boolean = false,
    val esPortada: Boolean = false,
    val rotacion: Int = 0,
) {
    val esVideo get() = tipo == "video" && !esPortada
    val esAudio get() = tipo == "audio"
    val esSubtitulo get() = tipo == "subtitle"
    val esAdjunto get() = tipo == "attachment"

    /** "AC3 5.1 (spa)", "H264 1920×1080", "SUBRIP (eng, Forzados)". */
    fun descripcion(): String {
        val extra = listOf(idioma, titulo).filter { it.isNotBlank() && it != "und" }.joinToString(", ")
        val base = when {
            esVideo -> "${nombreCodec(codec)} ${ancho}×$alto" + if (pixFmt.contains("10")) " 10 bits" else ""
            esAudio -> "${nombreCodec(codec)} ${nombreCanales(canales)}"
            else -> nombreCodec(codec)
        }
        return if (extra.isEmpty()) base else "$base ($extra)"
    }
}

data class InfoMedia(
    val formato: String,       // "matroska,webm", "mov,mp4,m4a,3gp,3g2,mj2", "avi"…
    val duracionSeg: Double,
    val tamano: Long,
    val pistas: List<Pista>,
) {
    val videos get() = pistas.filter { it.esVideo }
    val audios get() = pistas.filter { it.esAudio }
    val subtitulos get() = pistas.filter { it.esSubtitulo }
    val esMp4 get() = formato.split(',').any { it == "mp4" || it == "mov" }
    val esMatroska get() = formato.startsWith("matroska")
}

object Analisis {
    /** Argumentos de ffprobe (sin el ejecutable) cuya salida entiende [parsear]. */
    fun argumentos(ruta: String) = listOf(
        "-v", "error", "-hide_banner",
        "-probesize", "50M", "-analyzeduration", "50M",
        "-show_format", "-show_streams",
        "-of", "flat",
        ruta,
    )

    /**
     * Interpreta la salida "-of flat" de ffprobe:
     *   streams.stream.1.codec_name="ac3"
     *   streams.stream.1.channels=6
     *   format.duration="7203.120000"
     */
    fun parsear(salida: String): InfoMedia {
        val claves = HashMap<String, String>()
        for (linea in salida.lineSequence()) {
            val i = linea.indexOf('=')
            if (i <= 0) continue
            claves[linea.substring(0, i).trim()] = desescapar(linea.substring(i + 1).trim())
        }

        val indices = claves.keys.mapNotNull { k ->
            if (k.startsWith("streams.stream.")) k.removePrefix("streams.stream.").substringBefore('.').toIntOrNull()
            else null
        }.toSortedSet()

        val pistas = indices.map { n ->
            fun v(campo: String) = claves["streams.stream.$n.$campo"].orEmpty().let { if (it == "N/A") "" else it }
            fun e(campo: String) = v(campo).toIntOrNull() ?: 0
            // La rotación sale como side_data de tipo "Display Matrix"; puede no estar en la posición 0.
            val rotacion = claves.entries.firstOrNull {
                it.key.startsWith("streams.stream.$n.side_data_list.") && it.key.endsWith(".rotation")
            }?.value?.toDoubleOrNull()?.toInt() ?: (v("tags.rotate").toIntOrNull() ?: 0)
            Pista(
                indice = v("index").toIntOrNull() ?: n,
                tipo = v("codec_type"),
                codec = v("codec_name").ifBlank { "desconocido" },
                perfil = v("profile").takeUnless { it == "unknown" }.orEmpty(),
                ancho = e("width"),
                alto = e("height"),
                pixFmt = v("pix_fmt"),
                canales = e("channels"),
                frecuencia = e("sample_rate"),
                idioma = v("tags.language"),
                titulo = v("tags.title"),
                porDefecto = v("disposition.default") == "1",
                esPortada = v("disposition.attached_pic") == "1",
                rotacion = rotacion,
            )
        }.sortedBy { it.indice }

        val duracionFormato = claves["format.duration"]?.toDoubleOrNull() ?: 0.0
        val duracionPistas = indices.maxOfOrNull { n -> claves["streams.stream.$n.duration"]?.toDoubleOrNull() ?: 0.0 } ?: 0.0
        return InfoMedia(
            formato = claves["format.format_name"].orEmpty(),
            duracionSeg = if (duracionFormato > 0) duracionFormato else duracionPistas,
            tamano = claves["format.size"]?.toLongOrNull() ?: 0L,
            pistas = pistas,
        )
    }

    /** flat entrecomilla los textos y escapa \ " ` $ con una barra invertida. */
    private fun desescapar(v: String): String {
        val s = if (v.length >= 2 && v.startsWith('"') && v.endsWith('"')) v.substring(1, v.length - 1) else v
        if ('\\' !in s) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                val sig = s[i + 1]
                sb.append(if (sig == 'n') '\n' else sig)
                i += 2
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }
}

fun nombreCodec(c: String): String = when (c) {
    "dts", "dca" -> "DTS"
    "truehd" -> "Dolby TrueHD"
    "eac3" -> "Dolby Digital Plus (E-AC3)"
    "ac3" -> "Dolby Digital (AC3)"
    "aac" -> "AAC"
    "mp3" -> "MP3"
    "hevc" -> "H.265"
    "h264" -> "H.264"
    "subrip" -> "SRT"
    "hdmv_pgs_subtitle" -> "PGS"
    "dvd_subtitle" -> "VobSub"
    "mov_text" -> "Texto MP4"
    else -> c.uppercase()
}

fun nombreCanales(n: Int): String = when (n) {
    0 -> ""
    1 -> "mono"
    2 -> "estéreo"
    6 -> "5.1"
    7 -> "6.1"
    8 -> "7.1"
    else -> "$n canales"
}
