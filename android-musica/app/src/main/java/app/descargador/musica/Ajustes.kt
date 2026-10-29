package app.descargador.musica

import android.content.Context
import android.net.Uri

class Ajustes(ctx: Context) {
    private val p = ctx.applicationContext.getSharedPreferences("ajustes", Context.MODE_PRIVATE)

    /** Carpeta elegida con el selector del sistema (SAF). Vale para memoria interna y tarjeta SD. */
    var carpetaUri: Uri?
        get() = p.getString("carpeta", null)?.let { Uri.parse(it) }
        set(v) = p.edit().putString("carpeta", v?.toString()).apply()

    var formato: Formato
        get() = runCatching { Formato.valueOf(p.getString("formato", Formato.MP3_320.name)!!) }
            .getOrDefault(Formato.MP3_320)
        set(v) = p.edit().putString("formato", v.name).apply()

    var caratula: Boolean
        get() = p.getBoolean("caratula", true)
        set(v) = p.edit().putBoolean("caratula", v).apply()

    var lista: Boolean
        get() = p.getBoolean("lista", false)
        set(v) = p.edit().putBoolean("lista", v).apply()

    /** Quitar intros, diálogos y partes sin música de los videoclips de YouTube (SponsorBlock). */
    var sinRelleno: Boolean
        get() = p.getBoolean("sin_relleno", true)
        set(v) = p.edit().putBoolean("sin_relleno", v).apply()

    var ultimaActualizacion: Long
        get() = p.getLong("ultima_actualizacion", 0L)
        set(v) = p.edit().putLong("ultima_actualizacion", v).apply()

    fun opciones() = Opciones(caratula = caratula, lista = lista, sinRelleno = sinRelleno)
}
