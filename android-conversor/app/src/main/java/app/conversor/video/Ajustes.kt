package app.conversor.video

import android.content.Context

class Ajustes(ctx: Context) {
    private val p = ctx.applicationContext.getSharedPreferences("ajustes", Context.MODE_PRIVATE)

    var modo: Modo
        get() = runCatching { Modo.valueOf(p.getString("modo", null)!!) }.getOrDefault(Modo.SONIDO)
        set(v) = p.edit().putString("modo", v.name).apply()

    var estereo: Boolean
        get() = p.getBoolean("estereo", true)
        set(v) = p.edit().putBoolean("estereo", v).apply()

    var resolucion: Resolucion
        get() = runCatching { Resolucion.valueOf(p.getString("resolucion", null)!!) }.getOrDefault(Resolucion.ORIGINAL)
        set(v) = p.edit().putString("resolucion", v.name).apply()

    var hardware: Boolean
        get() = p.getBoolean("hardware", true)
        set(v) = p.edit().putBoolean("hardware", v).apply()

    var forzar: Boolean
        get() = p.getBoolean("forzar", false)
        set(v) = p.edit().putBoolean("forzar", v).apply()

    /** El codificador por hardware ya falló en este móvil: no volver a intentarlo. */
    var hardwareFalla: Boolean
        get() = p.getBoolean("hardware_falla", false)
        set(v) = p.edit().putBoolean("hardware_falla", v).apply()

    /** Última carpeta abierta en el explorador. */
    var ultimaCarpeta: String?
        get() = p.getString("ultima_carpeta", null)
        set(v) = p.edit().putString("ultima_carpeta", v).apply()

    fun opciones() = Opciones(
        modo = modo,
        estereo = estereo,
        resolucion = resolucion,
        hardware = hardware,
        forzar = forzar,
    )
}
