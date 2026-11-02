package app.conversor.video

import java.util.Locale

// Sin dependencias de Android: se prueba en el PC con el ffmpeg de escritorio.

data class Avance(
    val segundos: Double,
    /** 0..1, o -1 si no se conoce la duración. */
    val fraccion: Float,
    /** Veces el tiempo real (2.5 = convierte 2,5 s de vídeo por segundo). */
    val velocidad: Double,
    val bytes: Long,
    val fin: Boolean,
)

/**
 * Lee la salida de "-progress pipe:1": bloques clave=valor que terminan en "progress=continue|end".
 * Devuelve un [Avance] al cerrar cada bloque.
 */
class LectorProgreso(private val duracionSeg: Double) {
    private var segundos = 0.0
    private var velocidad = 0.0
    private var bytes = 0L

    fun linea(l: String): Avance? {
        val i = l.indexOf('=')
        if (i <= 0) return null
        val clave = l.substring(0, i).trim()
        val valor = l.substring(i + 1).trim()
        when (clave) {
            // out_time_ms también va en microsegundos (error histórico de ffmpeg).
            "out_time_us", "out_time_ms" -> valor.toLongOrNull()?.takeIf { it >= 0 }?.let { segundos = it / 1_000_000.0 }
            "speed" -> valor.removeSuffix("x").trim().toDoubleOrNull()?.let { velocidad = it }
            "total_size" -> valor.toLongOrNull()?.let { bytes = it }
            "progress" -> {
                val fin = valor == "end"
                val fr = when {
                    duracionSeg <= 0 -> -1f
                    fin -> 1f
                    else -> (segundos / duracionSeg).coerceIn(0.0, 1.0).toFloat()
                }
                return Avance(segundos, fr, velocidad, bytes, fin)
            }
        }
        return null
    }
}

/** Traduce los errores típicos de ffmpeg a algo entendible. */
fun errorFfmpeg(lineas: List<String>, codigo: Int): String {
    val texto = lineas.joinToString("\n")
    val low = texto.lowercase(Locale.ROOT)
    return when {
        "no space left" in low -> "No queda espacio en el almacenamiento."
        "permission denied" in low || "operation not permitted" in low ->
            "Sin permiso para leer o escribir el fichero. Revisa el permiso de acceso a archivos."
        "invalid data found" in low || "moov atom not found" in low ->
            "El fichero está dañado o incompleto (¿descarga a medias?)."
        "no such file" in low -> "El fichero ya no existe en esa carpeta."
        "unknown encoder" in low || "encoder not found" in low -> "Este móvil no tiene el codificador necesario."
        "could not find tag for codec" in low || "not currently supported in container" in low ->
            "El contenedor de salida no admite alguna pista. Prueba el modo \"MP4 compatible\"."
        else -> lineas.lastOrNull { it.isNotBlank() }?.trim()?.take(300) ?: "ffmpeg terminó con código $codigo"
    }
}

fun fmtTiempo(seg: Long): String {
    if (seg < 0) return "--:--"
    val h = seg / 3600
    val m = (seg % 3600) / 60
    val s = seg % 60
    return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s)
    else String.format(Locale.ROOT, "%02d:%02d", m, s)
}

fun fmtBytes(b: Long): String {
    if (b <= 0) return "0 B"
    val unidades = arrayOf("B", "KB", "MB", "GB", "TB")
    var v = b.toDouble()
    var i = 0
    while (v >= 1024 && i < unidades.lastIndex) {
        v /= 1024
        i++
    }
    return if (i == 0) "$b B" else String.format(Locale("es", "ES"), "%.1f %s", v, unidades[i])
}
