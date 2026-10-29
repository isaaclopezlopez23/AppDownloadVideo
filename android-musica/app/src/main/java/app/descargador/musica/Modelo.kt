package app.descargador.musica

enum class Formato(val etiqueta: String, val selector: String, val codec: String, val calidad: String?) {
    MP3_320("MP3 320", "ba/b", "mp3", "320K"),
    MP3_192("MP3 192", "ba/b", "mp3", "192K"),
    M4A("M4A", "ba[ext=m4a]/ba/b", "m4a", null),       // AAC sin recodificar si la web lo da (YouTube sí)
    ORIGINAL("Original", "ba/b", "best", null),         // el códec tal cual viene (opus en YouTube)
}

val EXT_AUDIO = setOf("mp3", "m4a", "opus", "ogg", "oga", "aac", "flac", "wav", "mka", "alac")

enum class Estado {
    EN_COLA, DESCARGANDO, PROCESANDO, GUARDANDO, OK, ERROR, CANCELADA;

    val terminada get() = this == OK || this == ERROR || this == CANCELADA
    val activa get() = this == DESCARGANDO || this == PROCESANDO || this == GUARDANDO
}

data class Opciones(val caratula: Boolean, val lista: Boolean, val sinRelleno: Boolean)

/** Un fichero ya guardado en la carpeta del usuario. */
data class Archivo(val nombre: String, val uri: String, val mime: String?)

data class Tarea(
    val id: Int,
    val url: String,
    val formato: Formato,
    val opciones: Opciones,
    val estado: Estado = Estado.EN_COLA,
    val titulo: String = "",
    val paso: String = "",
    val progreso: Float = 0f,
    val etaSeg: Long = -1,
    val item: Int = 0,
    val items: Int = 0,
    val bytes: Long = 0,
    val archivos: List<Archivo> = emptyList(),
    val error: String = "",
    val aviso: String = "",
    val inicio: Long = 0,
    val fin: Long = 0,
)

/** Resultado de una búsqueda en YouTube. */
data class Resultado(val url: String, val titulo: String, val canal: String, val duracionSeg: Long)

sealed interface EstadoMotor {
    data object Iniciando : EstadoMotor
    data object Listo : EstadoMotor
    data class Fallo(val mensaje: String) : EstadoMotor
}
