package app.descargador.musica

import android.content.Context
import android.util.Log
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Estado compartido entre la UI, la actividad de "Compartir" y el servicio de descargas.
 * La cola vive aquí; el servicio la va consumiendo de una en una.
 */
object Motor {
    private const val TAG = "Musica"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val listo = CompletableDeferred<Boolean>()
    private var siguienteId = 1

    private val _estado = MutableStateFlow<EstadoMotor>(EstadoMotor.Iniciando)
    val estado: StateFlow<EstadoMotor> = _estado.asStateFlow()

    private val _version = MutableStateFlow("")
    val version: StateFlow<String> = _version.asStateFlow()

    private val _tareas = MutableStateFlow<List<Tarea>>(emptyList())
    val tareas: StateFlow<List<Tarea>> = _tareas.asStateFlow()

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    @Volatile var cancelado = false
        private set
    @Volatile private var pidActual: String? = null

    /** Descomprime Python + yt-dlp la primera vez (tarda unos segundos) y actualiza yt-dlp una vez al día. */
    fun inicializar(ctx: Context) {
        val app = ctx.applicationContext
        scope.launch {
            try {
                YoutubeDL.getInstance().init(app)
                FFmpeg.getInstance().init(app)
                _version.value = YoutubeDL.getInstance().versionName(app).orEmpty()
                log("Motor listo · yt-dlp ${_version.value}")
                if (System.currentTimeMillis() - Ajustes(app).ultimaActualizacion > 24 * 3600 * 1000L) {
                    actualizarYtDlp(app)
                }
                _estado.value = EstadoMotor.Listo
                listo.complete(true)
            } catch (e: Exception) {
                Log.e(TAG, "Error iniciando yt-dlp", e)
                _estado.value = EstadoMotor.Fallo(e.message ?: e.toString())
                log("ERROR iniciando el motor: ${e.message}")
                listo.complete(false)
            }
        }
    }

    suspend fun esperarListo(): Boolean = listo.await()

    /** Bloqueante: llamar fuera del hilo principal y nunca con una descarga en marcha. */
    fun actualizarYtDlp(ctx: Context): String {
        if (_tareas.value.any { it.estado.activa }) return "Espera a que termine la descarga en curso para actualizar."
        return try {
            val st = YoutubeDL.getInstance().updateYoutubeDL(ctx, YoutubeDL.UpdateChannel.STABLE)
            Ajustes(ctx).ultimaActualizacion = System.currentTimeMillis()
            _version.value = YoutubeDL.getInstance().versionName(ctx).orEmpty()
            val msg = if (st == YoutubeDL.UpdateStatus.DONE) "yt-dlp actualizado a ${_version.value}"
            else "yt-dlp ya estaba al día (${_version.value})"
            log(msg)
            msg
        } catch (e: Exception) {
            val msg = "No se pudo actualizar yt-dlp: ${e.message}"
            log(msg)
            msg
        }
    }

    // ------------------------------------------------------------- cola
    fun encolar(url: String, formato: Formato, opciones: Opciones, titulo: String = ""): Tarea = synchronized(lock) {
        val t = Tarea(siguienteId++, url.trim(), formato, opciones, titulo = titulo)
        _tareas.update { it + t }
        t
    }

    fun siguientePendiente(): Tarea? = synchronized(lock) {
        _tareas.value.firstOrNull { it.estado == Estado.EN_COLA }
    }

    fun pendientes(): Int = _tareas.value.count { it.estado == Estado.EN_COLA }

    fun actualizar(id: Int, cambio: (Tarea) -> Tarea) {
        _tareas.update { lista -> lista.map { if (it.id == id) cambio(it) else it } }
    }

    fun tarea(id: Int): Tarea? = _tareas.value.firstOrNull { it.id == id }

    fun reintentar(id: Int) = actualizar(id) {
        if (it.estado == Estado.ERROR || it.estado == Estado.CANCELADA)
            it.copy(estado = Estado.EN_COLA, progreso = 0f, etaSeg = -1, error = "", aviso = "", paso = "",
                archivos = emptyList(), bytes = 0, item = 0, items = 0)
        else it
    }

    fun quitarTerminadas() = _tareas.update { lista -> lista.filterNot { it.estado.terminada } }

    fun cancelarPendientes() = _tareas.update { lista ->
        lista.map { if (it.estado == Estado.EN_COLA) it.copy(estado = Estado.CANCELADA) else it }
    }

    // ------------------------------------------------------------- proceso en curso
    fun empezarProceso(pid: String) {
        cancelado = false
        pidActual = pid
    }

    fun terminarProceso() {
        pidActual = null
    }

    fun cancelarActual() {
        val pid = pidActual ?: return
        cancelado = true
        runCatching { YoutubeDL.getInstance().destroyProcessById(pid) }
    }

    // ------------------------------------------------------------- log
    fun log(msg: String) {
        Log.i(TAG, msg)
        val linea = "[${hora()}] $msg"
        _log.update { (it + linea).takeLast(400) }
    }
}
