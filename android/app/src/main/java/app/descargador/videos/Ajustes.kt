package app.descargador.videos

import android.content.Context
import android.net.Uri

class Ajustes(ctx: Context) {
    private val p = ctx.applicationContext.getSharedPreferences("ajustes", Context.MODE_PRIVATE)

    /** Carpeta elegida con el selector del sistema (SAF). Vale para memoria interna y tarjeta SD. */
    var carpetaUri: Uri?
        get() = p.getString("carpeta", null)?.let { Uri.parse(it) }
        set(v) = p.edit().putString("carpeta", v?.toString()).apply()

    var calidad: Calidad
        get() = runCatching { Calidad.valueOf(p.getString("calidad", Calidad.MEJOR.name)!!) }
            .getOrDefault(Calidad.MEJOR)
        set(v) = p.edit().putString("calidad", v.name).apply()

    var playlist: Boolean
        get() = p.getBoolean("playlist", false)
        set(v) = p.edit().putBoolean("playlist", v).apply()

    var ultimaActualizacion: Long
        get() = p.getLong("ultima_actualizacion", 0L)
        set(v) = p.edit().putLong("ultima_actualizacion", v).apply()
}
