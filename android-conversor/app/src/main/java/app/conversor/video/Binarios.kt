package app.conversor.video

import android.content.Context
import android.system.Os
import org.apache.commons.compress.archivers.zip.ZipFile
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference

/**
 * ffmpeg y ffprobe vienen de youtubedl-android (compilados con los paquetes de Termux):
 *  - libffmpeg.so / libffprobe.so: los ejecutables, que Android deja en nativeLibraryDir.
 *  - libffmpeg.zip.so: las bibliotecas de ffmpeg (libavcodec…), comprimidas.
 *  - libpython.zip.so: de aquí solo hacen falta libc++_shared, libcrypto, libexpat y dos
 *    bibliotecas de compatibilidad de Termux que ffmpeg también necesita.
 * La primera vez se descomprime todo en la carpeta privada de la app (unos segundos).
 */
object Binarios {
    private const val VERSION_EXTRACCION = 1
    private val NECESARIAS_DE_PYTHON = Regex("""^lib(c\+\+_shared|crypto|ssl|expat|android-support|android-posix-semaphore)\.so.*""")

    lateinit var ffmpeg: File
        private set
    lateinit var ffprobe: File
        private set
    private var entorno: Map<String, String> = emptyMap()

    fun preparar(ctx: Context) {
        val nativos = File(ctx.applicationInfo.nativeLibraryDir)
        ffmpeg = File(nativos, "libffmpeg.so")
        ffprobe = File(nativos, "libffprobe.so")
        val zipFfmpeg = File(nativos, "libffmpeg.zip.so")
        val zipPython = File(nativos, "libpython.zip.so")
        for (f in listOf(ffmpeg, ffprobe, zipFfmpeg, zipPython)) {
            if (!f.exists()) throw IOException("Falta ${f.name} en la app. Instala el APK de la arquitectura de tu móvil.")
        }

        val base = File(ctx.noBackupFilesDir, "ffmpeg")
        val libFfmpeg = File(base, "ffmpeg")
        val libExtra = File(base, "extra")
        val firma = "$VERSION_EXTRACCION-${zipFfmpeg.length()}-${zipPython.length()}"
        val prefs = ctx.getSharedPreferences("binarios", Context.MODE_PRIVATE)
        if (prefs.getString("firma", null) != firma || !File(libFfmpeg, "usr/lib").isDirectory || !File(libExtra, "usr/lib").isDirectory) {
            base.deleteRecursively()
            try {
                extraer(zipFfmpeg, libFfmpeg) { true }
                extraer(zipPython, libExtra) { nombre ->
                    nombre.startsWith("usr/lib/") && nombre.count { it == '/' } == 2 &&
                        NECESARIAS_DE_PYTHON.matches(nombre.substringAfterLast('/'))
                }
            } catch (e: Exception) {
                base.deleteRecursively()
                throw IOException("No se pudo preparar ffmpeg: ${e.message}", e)
            }
            prefs.edit().putString("firma", firma).apply()
        }

        entorno = mapOf(
            "LD_LIBRARY_PATH" to "${libFfmpeg.absolutePath}/usr/lib:${libExtra.absolutePath}/usr/lib",
            "TMPDIR" to ctx.cacheDir.absolutePath,
            "HOME" to ctx.filesDir.absolutePath,
        )
    }

    private fun extraer(zip: File, destino: File, incluir: (String) -> Boolean) {
        destino.mkdirs()
        val raiz = destino.canonicalPath + File.separator
        ZipFile(zip).use { zf ->
            val entradas = zf.entries
            while (entradas.hasMoreElements()) {
                val e = entradas.nextElement()
                if (!incluir(e.name)) continue
                val f = File(destino, e.name)
                if (!f.canonicalPath.startsWith(raiz) && !e.isUnixSymlink) throw IOException("Ruta no válida en el zip: ${e.name}")
                when {
                    e.isDirectory -> f.mkdirs()
                    e.isUnixSymlink -> {
                        f.parentFile?.mkdirs()
                        val objetivo = zf.getInputStream(e).use { it.readBytes().toString(Charsets.UTF_8) }
                        f.delete()
                        Os.symlink(objetivo, f.absolutePath)
                    }
                    else -> {
                        f.parentFile?.mkdirs()
                        zf.getInputStream(e).use { ent -> FileOutputStream(f).use { ent.copyTo(it, 256 * 1024) } }
                    }
                }
            }
        }
    }

    fun iniciar(exe: File, args: List<String>): Process =
        ProcessBuilder(listOf(exe.absolutePath) + args).apply {
            environment().putAll(entorno)
            redirectErrorStream(false)
        }.start()

    /** Ejecuta y espera; devuelve (código, stdout, stderr). Mata el proceso si tarda más de [maxSeg]. */
    fun ejecutar(exe: File, args: List<String>, maxSeg: Long = 120): Triple<Int, String, String> {
        val p = iniciar(exe, args)
        val err = AtomicReference("")
        val hiloErr = Thread { err.set(runCatching { p.errorStream.bufferedReader().readText() }.getOrDefault("")) }
        hiloErr.start()
        val vigilante = Thread {
            try {
                Thread.sleep(maxSeg * 1000)
                p.destroy()
            } catch (_: InterruptedException) {
            }
        }
        vigilante.isDaemon = true
        vigilante.start()
        val out = runCatching { p.inputStream.bufferedReader().readText() }.getOrDefault("")
        val codigo = p.waitFor()
        vigilante.interrupt()
        hiloErr.join(5000)
        return Triple(codigo, out, err.get())
    }

    fun analizar(ruta: String): InfoMedia {
        val (codigo, out, err) = ejecutar(ffprobe, Analisis.argumentos(ruta))
        if (codigo != 0 || out.isBlank()) {
            throw IOException(errorFfmpeg(err.lines(), codigo).let {
                if ("dañado" in it || "Invalid" in err) "No se puede leer: el fichero está dañado o no es un vídeo." else it
            })
        }
        return Analisis.parsear(out)
    }

    fun version(): String {
        val (_, out, err) = ejecutar(ffmpeg, listOf("-hide_banner", "-version"), 30)
        return (out.lineSequence().firstOrNull { it.isNotBlank() } ?: err.lines().firstOrNull().orEmpty())
            .substringBefore(" Copyright").trim()
    }
}
