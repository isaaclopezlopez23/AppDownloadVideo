package app.descargador.musica

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val RE_URL = Regex("""https?://[^\s"'<>]+""", RegexOption.IGNORE_CASE)

/** Saca las URLs de un texto compartido ("Escucha esto en Spotify: https://…" → la URL). */
fun extraerUrls(texto: String): List<String> =
    RE_URL.findAll(texto)
        .map { it.value.trimEnd('.', ',', ';', ')', ']', '"', '\'') }
        .distinct()
        .toList()

fun hora(): String = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())

fun fmtTiempo(seg: Long): String {
    if (seg < 0) return ""
    val h = seg / 3600
    val m = (seg % 3600) / 60
    val s = seg % 60
    return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s)
    else String.format(Locale.ROOT, "%02d:%02d", m, s)
}

fun fmtBytes(b: Long): String {
    if (b <= 0) return ""
    val unidades = arrayOf("B", "KB", "MB", "GB")
    var v = b.toDouble()
    var i = 0
    while (v >= 1024 && i < unidades.lastIndex) {
        v /= 1024
        i++
    }
    return if (i == 0) "$b B" else String.format(Locale.ROOT, "%.1f %s", v, unidades[i])
}

/** Traduce los errores típicos de yt-dlp a algo entendible. */
fun errorLegible(msg: String?): String {
    val m = msg.orEmpty()
    val low = m.lowercase(Locale.ROOT)
    return when {
        "drm" in low -> "Contenido protegido con DRM: no se puede descargar."
        "sign in to confirm your age" in low -> "YouTube pide confirmar la edad (necesita iniciar sesión)."
        "sign in to confirm" in low || "login required" in low || "use --cookies" in low ->
            "La web pide iniciar sesión; esta app no usa cuentas."
        "unsupported url" in low -> "No se ha encontrado audio ni vídeo en esa página."
        "http error 403" in low -> "403 Prohibido: pulsa 'Actualizar yt-dlp' y reintenta."
        "http error 404" in low -> "404: ya no existe en esa dirección."
        "private video" in low -> "Vídeo privado."
        "video unavailable" in low -> "Vídeo no disponible."
        "unable to resolve host" in low || "network is unreachable" in low ||
            "getaddrinfo failed" in low || "name or service not known" in low -> "Sin conexión a internet."
        else -> m.lines().lastOrNull { it.startsWith("ERROR:") }?.removePrefix("ERROR:")?.trim()?.take(300)
            ?: m.lines().lastOrNull { it.isNotBlank() }?.trim()?.take(300)
            ?: "Error desconocido"
    }
}
