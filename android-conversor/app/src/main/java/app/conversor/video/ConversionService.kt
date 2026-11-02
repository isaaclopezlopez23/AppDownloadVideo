package app.conversor.video

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaScannerConnection
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import kotlin.concurrent.thread

/** Servicio en primer plano: convierte la cola de una en una aunque se apague la pantalla. */
class ConversionService : Service() {
    companion object {
        const val ACCION_CANCELAR = "app.conversor.video.CANCELAR"
        const val ACCION_CANCELAR_TODO = "app.conversor.video.CANCELAR_TODO"
        private const val CANAL = "conversion"
        private const val CANAL_FIN = "fin"
        private const val NOTIF = 1
        private const val NOTIF_FIN = 2
        private const val TAG = "Conversor"

        fun iniciar(ctx: Context) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, ConversionService::class.java))
        }
    }

    private class Resumen {
        var convertidos = 0
        var yaCompatibles = 0
        var errores = 0
        var cancelados = 0
        var bytesEntrada = 0L
        var bytesSalida = 0L
        val inicio = SystemClock.elapsedRealtime()
        val total get() = convertidos + yaCompatibles + errores + cancelados
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lockBucle = Any()
    private var trabajando = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var ultimaNotif = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CANAL, "Conversión en curso", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CANAL_FIN, "Conversión terminada", NotificationManager.IMPORTANCE_DEFAULT))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACCION_CANCELAR -> {
                Motor.log("Cancelado por el usuario")
                Motor.cancelarActual()
                return START_NOT_STICKY
            }
            ACCION_CANCELAR_TODO -> {
                Motor.log("Cancelada toda la cola")
                Motor.cancelarPendientes()
                Motor.cancelarActual()
                return START_NOT_STICKY
            }
        }
        enPrimerPlano(notificacion("Preparando…", "", -1f))
        synchronized(lockBucle) {
            if (!trabajando) {
                trabajando = true
                scope.launch { bucle() }
            }
        }
        return START_NOT_STICKY
    }

    @SuppressLint("WakelockTimeout")
    private suspend fun bucle() {
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Conversor:conversion")
            .apply { acquire(12 * 3600 * 1000L) }
        val resumen = Resumen()
        try {
            if (!Motor.esperarListo()) {
                val motivo = (Motor.estado.value as? EstadoMotor.Fallo)?.mensaje ?: "ffmpeg no está disponible"
                while (true) {
                    val t = Motor.siguientePendiente() ?: break
                    Motor.actualizar(t.id) { it.copy(estado = Estado.ERROR, mensaje = motivo) }
                }
                return
            }
            while (true) {
                val t = Motor.siguientePendiente()
                if (t == null) {
                    // Si entró algo en la cola justo ahora, se sigue; si no, se termina.
                    val seguir = synchronized(lockBucle) {
                        if (Motor.pendientes() > 0) true else {
                            trabajando = false
                            false
                        }
                    }
                    if (seguir) continue else break
                }
                try {
                    procesar(t, resumen)
                } catch (e: Throwable) {
                    Log.e(TAG, "Error inesperado con ${t.ruta}", e)
                    Motor.terminarProceso()
                    Motor.actualizar(t.id) { it.copy(estado = Estado.ERROR, mensaje = "Error inesperado: ${e.message ?: e}", fin = System.currentTimeMillis()) }
                    Motor.log("✘ ${t.nombre}: error inesperado: $e")
                    resumen.errores++
                }
            }
        } finally {
            synchronized(lockBucle) { trabajando = false }
            runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
            if (resumen.total > 0) terminar(resumen)
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun procesar(t: Tarea, resumen: Resumen) {
        val f = File(t.ruta)
        Motor.reiniciarCancelacion()
        val numero = Motor.tareas.value.count { it.estado.terminada && it.inicio > 0 } + 1
        val total = numero + Motor.pendientes() - 1
        Motor.actualizar(t.id) { it.copy(estado = Estado.ANALIZANDO, inicio = System.currentTimeMillis(), paso = "Analizando…") }
        Motor.log("▶ ($numero/$total) ${f.name} · ${fmtBytes(f.length())} · ${t.opciones.modo.titulo}")
        notificar(f.name, "Analizando…", -1f, forzar = true)

        fun fallo(msg: String) {
            Motor.actualizar(t.id) { it.copy(estado = Estado.ERROR, mensaje = msg, paso = "", fin = System.currentTimeMillis()) }
            Motor.log("✘ ${f.name}: $msg")
            resumen.errores++
        }

        if (!f.isFile) return fallo("El fichero ya no existe.")
        if (!f.canRead()) return fallo("Sin permiso para leer el fichero. Revisa el acceso a archivos.")

        val info = try {
            Binarios.analizar(f.path)
        } catch (e: Exception) {
            return fallo(e.message ?: "No se pudo analizar el fichero.")
        }
        if (Motor.cancelado) return cancelada(t, null, resumen)

        val pistas = info.pistas.filter { it.esVideo || it.esAudio || it.esSubtitulo }.map {
            when {
                it.esVideo -> "Vídeo: "
                it.esAudio -> "Audio: "
                else -> "Subtítulos: "
            } + it.descripcion()
        }
        Motor.actualizar(t.id) { it.copy(pistas = pistas) }
        Motor.log("   Duración ${fmtTiempo(info.duracionSeg.toLong())} · " + pistas.joinToString(" · "))

        val ajustes = Ajustes(this)
        val hardware = t.opciones.hardware && !ajustes.hardwareFalla
        val dir = try {
            Almacen.carpetaSalida(f)
        } catch (e: Exception) {
            return fallo(e.message ?: "No hay dónde guardar el resultado.")
        }
        val ext = Planificador.extensionSalida(info, f.extension, t.opciones.modo)
        val destino = Almacen.nombreLibre(dir, f.nameWithoutExtension, ext)
        val temporal = File(dir, ".${destino.name}.part")

        var plan = try {
            Planificador.planificar(info, f.extension, t.opciones, f.path, temporal.path, hardware)
        } catch (e: PlanImposible) {
            return fallo(e.message ?: "No se puede convertir.")
        }
        plan.nadaQueHacer?.let { motivo ->
            Motor.actualizar(t.id) { it.copy(estado = Estado.YA_COMPATIBLE, mensaje = motivo, paso = "", fin = System.currentTimeMillis()) }
            Motor.log("• ${f.name}: $motivo")
            resumen.yaCompatibles++
            return
        }

        val necesario = (f.length() * if (plan.recodificaVideo) 0.5 else 1.05).toLong()
        val libre = dir.usableSpace
        if (libre in 1 until necesario) {
            return fallo("No hay espacio suficiente en ${dir.path}: hacen falta unos ${fmtBytes(necesario)} y quedan ${fmtBytes(libre)}.")
        }

        plan.decisiones.forEach { Motor.log("   $it") }
        Motor.log("   Destino: ${destino.path}")

        var (codigo, errores) = convertir(t, plan, info, f.name)
        if (codigo != 0 && plan.usaHardware && !Motor.cancelado) {
            Motor.log("   El codificador por hardware del móvil ha fallado (${errorFfmpeg(errores, codigo)}). Repito por software; tardará más.")
            ajustes.hardwareFalla = true
            temporal.delete()
            plan = Planificador.planificar(info, f.extension, t.opciones, f.path, temporal.path, hardware = false)
            plan.decisiones.filter { it.startsWith("Vídeo") }.forEach { Motor.log("   $it") }
            val r = convertir(t, plan, info, f.name)
            codigo = r.first
            errores = r.second
        }

        when {
            Motor.cancelado -> cancelada(t, temporal, resumen)
            codigo == 0 && temporal.isFile && temporal.length() > 0 -> {
                if (!temporal.renameTo(destino)) {
                    temporal.delete()
                    return fallo("No se pudo renombrar el fichero temporal a ${destino.name}.")
                }
                MediaScannerConnection.scanFile(applicationContext, arrayOf(destino.path), null, null)
                val ahora = System.currentTimeMillis()
                Motor.actualizar(t.id) {
                    it.copy(
                        estado = Estado.HECHO, progreso = 1f, etaSeg = -1, paso = "", salida = destino.path,
                        tamanoSalida = destino.length(), fin = ahora, decisiones = plan.decisiones,
                    )
                }
                val tarda = (ahora - (Motor.tarea(t.id)?.inicio ?: ahora)) / 1000
                Motor.log("✔ ${f.name} → ${destino.name} · ${fmtBytes(destino.length())} · en ${fmtTiempo(tarda)}")
                resumen.convertidos++
                resumen.bytesEntrada += f.length()
                resumen.bytesSalida += destino.length()
            }
            else -> {
                temporal.delete()
                errores.takeLast(5).forEach { Motor.log("   ffmpeg: $it") }
                fallo(errorFfmpeg(errores, codigo))
            }
        }
    }

    private fun cancelada(t: Tarea, temporal: File?, resumen: Resumen) {
        temporal?.delete()
        Motor.actualizar(t.id) { it.copy(estado = Estado.CANCELADA, paso = "", etaSeg = -1, fin = System.currentTimeMillis()) }
        Motor.log("■ ${t.nombre}: cancelado")
        resumen.cancelados++
    }

    /** Lanza ffmpeg y va leyendo el progreso. Devuelve el código de salida y las últimas líneas de error. */
    private fun convertir(t: Tarea, plan: Plan, info: InfoMedia, nombre: String): Pair<Int, List<String>> {
        val paso = when {
            plan.recodificaVideo && plan.usaHardware -> "Recodificando vídeo (hardware)…"
            plan.recodificaVideo -> "Recodificando vídeo…"
            plan.extension == "mp3" -> "Extrayendo audio…"
            else -> "Convirtiendo audio…"
        }
        Motor.actualizar(t.id) { it.copy(estado = Estado.CONVIRTIENDO, decisiones = plan.decisiones, progreso = 0f, etaSeg = -1, paso = paso) }
        Log.i(TAG, "ffmpeg " + plan.args.joinToString(" "))

        val p = Binarios.iniciar(Binarios.ffmpeg, plan.args)
        Motor.empezarProceso(p)
        val errores = ArrayDeque<String>()
        val hiloErr = thread(name = "ffmpeg-stderr") {
            runCatching {
                p.errorStream.bufferedReader().forEachLine { l ->
                    if (l.isNotBlank()) synchronized(errores) {
                        errores.addLast(l)
                        if (errores.size > 40) errores.removeFirst()
                    }
                }
            }
        }

        val lector = LectorProgreso(info.duracionSeg)
        val inicio = SystemClock.elapsedRealtime()
        var ultimoDecimo = 0
        runCatching {
            p.inputStream.bufferedReader().forEachLine { l ->
                val av = lector.linea(l) ?: return@forEachLine
                val transcurrido = (SystemClock.elapsedRealtime() - inicio) / 1000.0
                val eta = when {
                    av.fin -> 0L
                    av.fraccion > 0.01f -> (transcurrido / av.fraccion * (1 - av.fraccion)).toLong()
                    av.velocidad > 0 && info.duracionSeg > 0 -> ((info.duracionSeg - av.segundos) / av.velocidad).toLong()
                    else -> -1L
                }
                val terminando = av.fraccion >= 0.999f
                Motor.actualizar(t.id) {
                    it.copy(
                        progreso = av.fraccion, velocidad = av.velocidad, etaSeg = eta, tamanoSalida = av.bytes,
                        paso = if (terminando) "Terminando de escribir el fichero…" else paso,
                    )
                }
                val pct = (av.fraccion * 100).toInt()
                val texto = if (av.fraccion >= 0) "$pct % · quedan ${fmtTiempo(eta)}" else "${fmtTiempo(av.segundos.toLong())} convertidos"
                notificar(nombre, texto, av.fraccion)
                val decimo = pct / 10
                if (av.fraccion >= 0 && decimo > ultimoDecimo && decimo < 10) {
                    ultimoDecimo = decimo
                    Motor.log("   $pct % · ${"%.1f".format(av.velocidad)}x · ${fmtBytes(av.bytes)} · quedan ${fmtTiempo(eta)}")
                }
            }
        }
        val codigo = p.waitFor()
        hiloErr.join(3000)
        Motor.terminarProceso()
        return codigo to synchronized(errores) { errores.toList() }
    }

    // ------------------------------------------------------------- notificaciones
    private fun enPrimerPlano(n: Notification) {
        val tipo = when {
            Build.VERSION.SDK_INT >= 35 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
            Build.VERSION.SDK_INT >= 29 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            else -> 0
        }
        ServiceCompat.startForeground(this, NOTIF, n, tipo)
    }

    private fun abrirApp(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_PESTANA, 1)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun accion(accion: String, codigo: Int): PendingIntent = PendingIntent.getService(
        this, codigo, Intent(this, ConversionService::class.java).setAction(accion),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun notificacion(titulo: String, texto: String, fraccion: Float): Notification {
        val pendientes = Motor.pendientes()
        val b = NotificationCompat.Builder(this, CANAL)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(titulo)
            .setContentText(if (pendientes > 0) "$texto · $pendientes en cola" else texto)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(abrirApp())
            .addAction(0, "Cancelar", accion(ACCION_CANCELAR, 1))
        if (pendientes > 0) b.addAction(0, "Cancelar todo", accion(ACCION_CANCELAR_TODO, 2))
        if (fraccion < 0) b.setProgress(0, 0, true) else b.setProgress(1000, (fraccion * 1000).toInt(), false)
        return b.build()
    }

    private fun notificar(titulo: String, texto: String, fraccion: Float, forzar: Boolean = false) {
        val ahora = SystemClock.elapsedRealtime()
        if (!forzar && ahora - ultimaNotif < 1000) return
        ultimaNotif = ahora
        runCatching { getSystemService(NotificationManager::class.java).notify(NOTIF, notificacion(titulo, texto, fraccion)) }
    }

    private fun terminar(r: Resumen) {
        val partes = buildList {
            if (r.convertidos > 0) add("${r.convertidos} convertido${if (r.convertidos > 1) "s" else ""}")
            if (r.yaCompatibles > 0) add("${r.yaCompatibles} no hacía${if (r.yaCompatibles > 1) "n" else ""} falta")
            if (r.errores > 0) add("${r.errores} con error")
            if (r.cancelados > 0) add("${r.cancelados} cancelado${if (r.cancelados > 1) "s" else ""}")
        }
        val tiempo = fmtTiempo((SystemClock.elapsedRealtime() - r.inicio) / 1000)
        val texto = partes.joinToString(", ")
        Motor.log("══ Resumen: $texto · ${fmtBytes(r.bytesEntrada)} → ${fmtBytes(r.bytesSalida)} · $tiempo en total")
        val n = NotificationCompat.Builder(this, CANAL_FIN)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(if (r.errores > 0) "Conversión terminada con errores" else "Conversión terminada")
            .setContentText("$texto · $tiempo")
            .setAutoCancel(true)
            .setContentIntent(abrirApp())
            .build()
        runCatching { getSystemService(NotificationManager::class.java).notify(NOTIF_FIN, n) }
    }

    /** Android 15+: los servicios de procesado de medios tienen un máximo de 6 horas al día. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        Motor.log("Android ha parado la conversión por llevar demasiadas horas seguidas. Vuelve a lanzarla para continuar.")
        Motor.cancelarPendientes()
        Motor.cancelarActual()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
