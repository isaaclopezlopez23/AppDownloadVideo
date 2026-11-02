package app.conversor.video

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class Estado(val texto: String) {
    EN_COLA("En cola"),
    ANALIZANDO("Analizando"),
    CONVIRTIENDO("Convirtiendo"),
    HECHO("Convertido"),
    YA_COMPATIBLE("No hacía falta"),
    ERROR("Error"),
    CANCELADA("Cancelada");

    val activa get() = this == ANALIZANDO || this == CONVIRTIENDO
    val terminada get() = this == HECHO || this == YA_COMPATIBLE || this == ERROR || this == CANCELADA
}

data class Tarea(
    val id: Int,
    val ruta: String,
    val opciones: Opciones,
    val estado: Estado = Estado.EN_COLA,
    /** 0..1, o -1 si no se conoce la duración. */
    val progreso: Float = 0f,
    val velocidad: Double = 0.0,
    val etaSeg: Long = -1,
    val paso: String = "",
    val pistas: List<String> = emptyList(),
    val decisiones: List<String> = emptyList(),
    val salida: String = "",
    val tamanoSalida: Long = 0,
    val mensaje: String = "",
    val inicio: Long = 0,
    val fin: Long = 0,
) {
    val nombre: String get() = File(ruta).name
    val duracionSeg: Long get() = if (inicio > 0 && fin >= inicio) (fin - inicio) / 1000 else -1
}

sealed interface EstadoMotor {
    data object Iniciando : EstadoMotor
    data class Listo(val version: String) : EstadoMotor
    data class Fallo(val mensaje: String) : EstadoMotor
}

/** Estado compartido entre la pantalla y el servicio. La cola vive aquí y el servicio la consume de una en una. */
object Motor {
    private const val TAG = "Conversor"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val listo = CompletableDeferred<Boolean>()
    private var siguienteId = 1

    private val _estado = MutableStateFlow<EstadoMotor>(EstadoMotor.Iniciando)
    val estado: StateFlow<EstadoMotor> = _estado.asStateFlow()

    private val _tareas = MutableStateFlow<List<Tarea>>(emptyList())
    val tareas: StateFlow<List<Tarea>> = _tareas.asStateFlow()

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    @Volatile var cancelado = false
        private set
    @Volatile private var proceso: Process? = null

    fun inicializar(ctx: Context) {
        val app = ctx.applicationContext
        scope.launch {
            try {
                val t0 = System.currentTimeMillis()
                Binarios.preparar(app)
                val v = Binarios.version()
                _estado.value = EstadoMotor.Listo(v)
                log("Motor listo en ${System.currentTimeMillis() - t0} ms · $v")
                listo.complete(true)
            } catch (e: Throwable) {
                Log.e(TAG, "Error preparando ffmpeg", e)
                _estado.value = EstadoMotor.Fallo(e.message ?: e.toString())
                log("ERROR preparando ffmpeg: ${e.message}")
                listo.complete(false)
            }
        }
    }

    suspend fun esperarListo(): Boolean = listo.await()

    // ------------------------------------------------------------- cola
    fun encolar(rutas: List<String>, opciones: Opciones): Int = synchronized(lock) {
        val ocupadas = _tareas.value.filter { !it.estado.terminada }.map { it.ruta }.toSet()
        val nuevas = rutas.distinct().filter { it !in ocupadas }.map { Tarea(siguienteId++, it, opciones) }
        _tareas.update { it + nuevas }
        nuevas.forEach { log("En cola: ${it.nombre} · ${opciones.modo.titulo}") }
        nuevas.size
    }

    fun siguientePendiente(): Tarea? = synchronized(lock) { _tareas.value.firstOrNull { it.estado == Estado.EN_COLA } }

    fun pendientes(): Int = _tareas.value.count { it.estado == Estado.EN_COLA }

    fun tarea(id: Int): Tarea? = _tareas.value.firstOrNull { it.id == id }

    fun actualizar(id: Int, cambio: (Tarea) -> Tarea) {
        _tareas.update { lista -> lista.map { if (it.id == id) cambio(it) else it } }
    }

    fun reintentar(id: Int) = actualizar(id) {
        if (it.estado == Estado.ERROR || it.estado == Estado.CANCELADA)
            Tarea(it.id, it.ruta, it.opciones)
        else it
    }

    fun quitar(id: Int) = _tareas.update { lista -> lista.filterNot { it.id == id && !it.estado.activa } }

    fun quitarTerminadas() = _tareas.update { lista -> lista.filterNot { it.estado.terminada } }

    fun cancelarPendientes() = _tareas.update { lista ->
        lista.map { if (it.estado == Estado.EN_COLA) it.copy(estado = Estado.CANCELADA) else it }
    }

    // ------------------------------------------------------------- proceso en curso
    /** Registra el proceso en marcha; si ya se pidió cancelar (p. ej. durante el análisis), lo para en el acto. */
    fun empezarProceso(p: Process) {
        proceso = p
        if (cancelado) cancelarActual()
    }

    fun terminarProceso() {
        proceso = null
    }

    fun reiniciarCancelacion() {
        cancelado = false
    }

    /** Cancela la conversión en marcha (o el análisis previo). */
    fun cancelarActual() {
        cancelado = true
        proceso?.let { p ->
            p.destroy()
            scope.launch {
                Thread.sleep(3000)
                if (runCatching { p.exitValue() }.isFailure) p.destroyForcibly()
            }
        }
    }

    // ------------------------------------------------------------- log
    private val formatoHora = SimpleDateFormat("HH:mm:ss", Locale.ROOT)

    fun log(msg: String) {
        Log.i(TAG, msg)
        val linea = "[${synchronized(formatoHora) { formatoHora.format(Date()) }}] $msg"
        _log.update { (it + linea).takeLast(600) }
    }

    fun limpiarLog() = _log.update { emptyList() }
}
