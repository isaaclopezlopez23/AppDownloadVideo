package app.descargador.musica

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File

/**
 * Servicio en primer plano que va sacando tareas de la cola de [Motor] y las descarga de una en una.
 * Log por iteración con hora, ETA de la cola y resumen final (OK / errores / canceladas).
 */
class DescargaService : Service() {

    companion object {
        private const val ACCION_PROCESAR = "procesar"
        private const val ACCION_CANCELAR = "cancelar"
        private const val CANAL = "descargas_musica"
        private const val NOTIF_PROGRESO = 1
        private const val NOTIF_FIN = 2
        private val RE_DESTINO = Regex("""^\[download] Destination: (.+)$""")
        private val RE_ITEM = Regex("""^\[download] Downloading item (\d+) of (\d+)""")
        private val RE_SPONSOR = Regex("""^\[SponsorBlock] Found (\d+) segments""")

        /** Arranca (o despierta) el servicio para que procese lo que haya en cola. */
        fun arrancar(ctx: Context) {
            ContextCompat.startForegroundService(
                ctx, Intent(ctx, DescargaService::class.java).setAction(ACCION_PROCESAR)
            )
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lockServicio = Any()
    private var trabajando = false
    private var ultimoStartId = 0
    private var wakeLock: PowerManager.WakeLock? = null

    // Estado de la tarea en curso (hilo de trabajo + hilo lector de la salida de yt-dlp)
    @Volatile private var tituloFijo = false
    @Volatile private var ultimaNotif = 0L
    @Volatile private var ultimoProgresoUi = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        NotificationManagerCompat.from(this).createNotificationChannel(
            NotificationChannelCompat.Builder(CANAL, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName("Descargas de música")
                .build()
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACCION_CANCELAR) {
            Motor.cancelarActual()
            synchronized(lockServicio) { if (!trabajando) stopSelf(startId) }
            return START_NOT_STICKY
        }
        ServiceCompat.startForeground(
            this, NOTIF_PROGRESO, notifProgreso("Preparando…", "", -1),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
        synchronized(lockServicio) {
            ultimoStartId = startId
            if (!trabajando) {
                trabajando = true
                scope.launch { bucle() }
            }
        }
        return START_NOT_STICKY
    }

    // Android 15: límite de tiempo para servicios dataSync. Hay que parar ya.
    override fun onTimeout(startId: Int, fgsType: Int) {
        Motor.log("Android ha agotado el tiempo del servicio; se cancela la descarga en curso.")
        Motor.cancelarPendientes()
        Motor.cancelarActual()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        Motor.cancelarActual()
        scope.cancel()
        liberarWakeLock()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ bucle
    private suspend fun bucle() {
        tomarWakeLock()
        val lote = mutableListOf<Int>()
        val duraciones = mutableListOf<Long>()
        val inicioLote = System.currentTimeMillis()

        if (!Motor.esperarListo()) {
            Motor.log("El motor no arrancó; no se puede descargar. Reinstala o reinicia la app.")
            Motor.cancelarPendientes()
            synchronized(lockServicio) { trabajando = false }
        } else {
            while (true) {
                val t = synchronized(lockServicio) {
                    val sig = Motor.siguientePendiente()
                    if (sig == null) trabajando = false
                    sig
                } ?: break
                lote += t.id
                // pendientes() todavía incluye a 't' (sigue EN_COLA hasta que procesar() la marque)
                val total = lote.size + Motor.pendientes() - 1
                procesar(t, lote.size, total, duraciones)
            }
        }

        if (lote.isNotEmpty()) resumen(lote, inicioLote)
        synchronized(lockServicio) {
            // Si mientras tanto llegó otra descarga, ya hay otro bucle en marcha: no parar nada.
            if (!trabajando) {
                liberarWakeLock()
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf(ultimoStartId)
            }
        }
    }

    private fun etaCola(duraciones: List<Long>, restantes: Int): String = when {
        restantes <= 0 -> "00:00"
        duraciones.isEmpty() -> "calculando…"
        else -> "~" + fmtTiempo(duraciones.average().toLong() * restantes / 1000)
    }

    // ------------------------------------------------------------------ una tarea
    private fun procesar(t: Tarea, idx: Int, total: Int, duraciones: MutableList<Long>) {
        val inicio = System.currentTimeMillis()
        tituloFijo = false
        Motor.actualizar(t.id) {
            it.copy(estado = Estado.DESCARGANDO, inicio = inicio, fin = 0, progreso = 0f, etaSeg = -1, paso = "",
                error = "", aviso = "", archivos = emptyList(), bytes = 0, item = 0, items = 0)
        }
        Motor.log("[$idx/$total] Inicio (${t.formato.etiqueta}): ${t.url} · ETA cola ${etaCola(duraciones, total - idx + 1)}")
        notificar(NOTIF_PROGRESO, notifProgreso(t.titulo.ifBlank { t.url }, "Empezando…", -1))

        val carpeta = Ajustes(this).carpetaUri
        val tmp = File(cacheDir, "descargas/${t.id}_$inicio").apply { mkdirs() }
        val pid = "tarea-${t.id}-$inicio"
        Motor.empezarProceso(pid)
        try {
            if (carpeta == null) throw YoutubeDLException("Elige una carpeta de destino en la app")

            // Spotify, Apple Music, Deezer…: se busca la canción en YouTube por título y artista
            var url = t.url
            var opciones = t.opciones
            Tiendas.nombre(url)?.let { tienda ->
                Motor.log("    $tienda no deja descargar (DRM); busco la canción en YouTube…")
                val q = try {
                    Tiendas.consulta(url)
                } catch (e: Exception) {
                    throw YoutubeDLException(e.message ?: "No se pudo leer la canción de $tienda")
                }
                Motor.log("    Búsqueda: «$q»")
                Motor.actualizar(t.id) { it.copy(titulo = q) }
                url = "ytsearch1:$q"
                opciones = opciones.copy(lista = false)
            }

            var aviso = ""
            try {
                ejecutar(t, Argumentos.construir(t.formato, opciones.caratula, opciones.lista, opciones.sinRelleno, tmp), url, pid)
            } catch (e: YoutubeDLException) {
                if (Motor.cancelado) throw YoutubeDL.CanceledException()
                val hay = Guardado.audios(tmp).isNotEmpty()
                when {
                    // SponsorBlock caído o carátula que no entra: el audio está completo
                    hay && Argumentos.soloAvisos(e.message) -> aviso = avisoDe(e.message)
                    // En listas, si fallan algunas canciones se guardan las demás
                    hay && opciones.lista -> {
                        val fallos = e.message.orEmpty().lines().count { it.startsWith("ERROR:") }
                        aviso = "$fallos canción(es) de la lista no se pudieron bajar: ${errorLegible(e.message)}"
                    }
                    // (nunca en tiendas: su HTML trae avances de 30 s que no queremos)
                    Tiendas.nombre(t.url) == null && mereceSniffer(e.message) -> {
                        Motor.log("[$idx/$total] yt-dlp no reconoce la web; buscando audio en el HTML…")
                        if (!sniffer(t, tmp, pid)) throw e
                    }
                    else -> throw e
                }
            }
            if (Motor.cancelado) throw YoutubeDL.CanceledException()
            if (aviso.isNotEmpty()) Motor.log("    Aviso: $aviso")

            val ficheros = Guardado.audios(tmp)
            if (ficheros.isEmpty()) throw YoutubeDLException("No se ha descargado ningún audio")

            Motor.actualizar(t.id) { it.copy(estado = Estado.GUARDANDO, progreso = 100f, etaSeg = -1, paso = "") }
            notificar(NOTIF_PROGRESO, notifProgreso(Motor.tarea(t.id)?.titulo ?: t.url, "Guardando en la carpeta…", -1))
            val guardados = Guardado.guardar(this, carpeta, tmp) { Motor.cancelado }
            val bytes = ficheros.sumOf { it.length() }
            val fin = System.currentTimeMillis()
            duraciones += fin - inicio
            Motor.actualizar(t.id) {
                it.copy(estado = Estado.OK, archivos = guardados, bytes = bytes, fin = fin, aviso = aviso, paso = "",
                    titulo = if (guardados.size == 1) guardados[0].nombre.substringBeforeLast('.') else it.titulo)
            }
            val extra = if (guardados.size > 1) " (+${guardados.size - 1} más)" else ""
            Motor.log("[$idx/$total] OK en ${fmtTiempo((fin - inicio) / 1000)} · ${fmtBytes(bytes)} · " +
                "${guardados.first().nombre}$extra · ETA cola ${etaCola(duraciones, Motor.pendientes())}")
        } catch (e: YoutubeDL.CanceledException) {
            Motor.actualizar(t.id) { it.copy(estado = Estado.CANCELADA, fin = System.currentTimeMillis(), paso = "") }
            Motor.log("[$idx/$total] Cancelada: ${t.url}")
        } catch (e: InterruptedException) {
            Motor.actualizar(t.id) { it.copy(estado = Estado.CANCELADA, fin = System.currentTimeMillis(), paso = "") }
            Motor.log("[$idx/$total] Interrumpida: ${t.url}")
        } catch (e: Exception) {
            val fin = System.currentTimeMillis()
            duraciones += fin - inicio
            val msg = errorLegible(e.message)
            Motor.actualizar(t.id) { it.copy(estado = Estado.ERROR, error = msg, fin = fin, paso = "") }
            Motor.log("[$idx/$total] ERROR en ${fmtTiempo((fin - inicio) / 1000)}: $msg · ${t.url}")
        } finally {
            Motor.terminarProceso()
            tmp.deleteRecursively()
        }
    }

    private fun avisoDe(err: String?): String {
        val low = err.orEmpty().lowercase()
        return when {
            "sponsorblock" in low -> "No se pudo consultar SponsorBlock: canción completa, sin recortar"
            else -> "No se pudo poner la carátula"
        }
    }

    private fun ejecutar(t: Tarea, args: List<String>, url: String, pid: String) {
        val req = YoutubeDLRequest(url).addCommands(args)
        YoutubeDL.getInstance().execute(req, pid) { progreso, eta, linea -> alProgresar(t, progreso, eta, linea) }
    }

    private fun alProgresar(t: Tarea, progreso: Float, eta: Long, linea: String) {
        val l = linea.trim()
        RE_ITEM.find(l)?.let { m ->
            val i = m.groupValues[1].toInt()
            val n = m.groupValues[2].toInt()
            Motor.actualizar(t.id) { it.copy(item = i, items = n, estado = Estado.DESCARGANDO, progreso = 0f, paso = "") }
            Motor.log("    Canción $i de $n")
        }
        RE_DESTINO.find(l)?.let { m ->
            val nombre = File(m.groupValues[1]).nameWithoutExtension
            Motor.actualizar(t.id) {
                it.copy(estado = Estado.DESCARGANDO, paso = "", titulo = if (tituloFijo) it.titulo else nombre)
            }
        }
        RE_SPONSOR.find(l)?.let { Motor.log("    SponsorBlock: ${it.groupValues[1]} parte(s) sin música para quitar") }
        val paso = when {
            l.startsWith("[ExtractAudio]") ->
                if (t.formato == Formato.ORIGINAL) "Extrayendo el audio…" else "Convirtiendo a ${t.formato.etiqueta}…"
            l.startsWith("[ModifyChapters] Removing") -> "Quitando partes sin música…"
            l.startsWith("[Metadata]") -> "Escribiendo etiquetas…"
            l.startsWith("[EmbedThumbnail]") -> "Añadiendo carátula…"
            else -> null
        }
        if (paso != null) Motor.actualizar(t.id) { it.copy(estado = Estado.PROCESANDO, etaSeg = -1, paso = paso) }

        val ahora = System.currentTimeMillis()
        if (paso == null && progreso >= 0 && ahora - ultimoProgresoUi > 300) {
            ultimoProgresoUi = ahora
            Motor.actualizar(t.id) {
                if (it.estado == Estado.DESCARGANDO) it.copy(progreso = progreso, etaSeg = eta) else it
            }
        }
        if (ahora - ultimaNotif > 800) {
            ultimaNotif = ahora
            val tt = Motor.tarea(t.id) ?: return
            val lista = if (tt.items > 1) "${tt.item}/${tt.items} · " else ""
            val texto = when (tt.estado) {
                Estado.PROCESANDO -> lista + tt.paso
                else -> lista + "${"%.1f".format(tt.progreso)} %" + if (tt.etaSeg >= 0) " · ETA ${fmtTiempo(tt.etaSeg)}" else ""
            }
            notificar(NOTIF_PROGRESO, notifProgreso(tt.titulo.ifBlank { tt.url }, texto,
                if (tt.estado == Estado.DESCARGANDO) tt.progreso.toInt() else -1))
        }
    }

    // ------------------------------------------------------------------ plan B: HTML
    private fun mereceSniffer(msg: String?): Boolean {
        val low = msg.orEmpty().lowercase()
        return "drm" !in low && listOf("unsupported url", "no video formats found", "unable to extract", "no media found")
            .any { it in low }
    }

    private fun sniffer(t: Tarea, tmp: File, pid: String): Boolean {
        val pagina = Web.descargar(t.url) ?: return false
        val candidatos = Web.candidatos(pagina.urlFinal, pagina.html)
        if (candidatos.isEmpty()) {
            Motor.log("    No hay enlaces de audio ni vídeo en el HTML (puede que se carguen por JavaScript o lleven DRM).")
            return false
        }
        val titulo = Web.titulo(pagina.html) ?: "audio"
        tituloFijo = true
        Motor.actualizar(t.id) { it.copy(titulo = titulo) }
        Motor.log("    ${candidatos.size} candidato(s); probando el mejor…")
        for ((i, url) in candidatos.take(5).withIndex()) {
            if (Motor.cancelado) throw YoutubeDL.CanceledException()
            Motor.log("    Candidato ${i + 1}: ${url.take(150)}")
            try {
                val args = Argumentos.construir(t.formato, t.opciones.caratula, false, false, tmp,
                    plantilla = "${Web.nombreSeguro(titulo)}.%(ext)s", referer = t.url)
                ejecutar(t, args, url, pid)
                return true
            } catch (e: YoutubeDLException) {
                if (Guardado.audios(tmp).isNotEmpty() && Argumentos.soloAvisos(e.message)) return true
                Motor.log("    Candidato ${i + 1} falló: ${errorLegible(e.message)}")
            }
        }
        return false
    }

    // ------------------------------------------------------------------ resumen
    private fun resumen(ids: List<Int>, inicioLote: Long) {
        val tareas = ids.mapNotNull { Motor.tarea(it) }
        val ok = tareas.filter { it.estado == Estado.OK }
        val err = tareas.filter { it.estado == Estado.ERROR }
        val can = tareas.count { it.estado == Estado.CANCELADA }
        val canciones = ok.sumOf { it.archivos.size }
        val bytes = ok.sumOf { it.bytes }
        val dur = fmtTiempo((System.currentTimeMillis() - inicioLote) / 1000)
        Motor.log("========================================")
        Motor.log("RESUMEN · Total: ${tareas.size} · OK: ${ok.size} ($canciones canciones) · Errores: ${err.size} · " +
            "Canceladas: $can · Tiempo: $dur · Descargado: ${fmtBytes(bytes).ifBlank { "0 B" }}")
        err.forEach { Motor.log("  ✗ ${it.titulo.ifBlank { it.url }} → ${it.error}") }
        ok.filter { it.aviso.isNotEmpty() }.forEach { Motor.log("  ! ${it.titulo} → ${it.aviso}") }
        Motor.log("========================================")

        val n = NotificationCompat.Builder(this, CANAL)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(if (canciones == 1) ok.first().titulo else "Música descargada")
            .setContentText("$canciones canción(es) · Errores ${err.size} · Canceladas $can · $dur")
            .setContentIntent(abrirApp())
            .setAutoCancel(true)
            .build()
        notificar(NOTIF_FIN, n)
    }

    // ------------------------------------------------------------------ notificaciones
    private fun abrirApp(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun notifProgreso(titulo: String, texto: String, progreso: Int): Notification {
        val cancelar = PendingIntent.getService(
            this, 1, Intent(this, DescargaService::class.java).setAction(ACCION_CANCELAR),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CANAL)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(titulo)
            .setContentText(texto)
            .setProgress(100, progreso.coerceIn(0, 100), progreso < 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(abrirApp())
            .addAction(0, "Cancelar", cancelar)
            .build()
    }

    private fun puedeNotificar() = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun notificar(id: Int, n: Notification) {
        if (puedeNotificar()) NotificationManagerCompat.from(this).notify(id, n)
    }

    // ------------------------------------------------------------------ wake lock
    private fun tomarWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Musica:descargas").apply {
            setReferenceCounted(false)
            acquire(4 * 60 * 60 * 1000L)
        }
    }

    private fun liberarWakeLock() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
    }
}
