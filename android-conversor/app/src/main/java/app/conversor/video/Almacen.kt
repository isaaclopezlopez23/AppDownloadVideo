package app.conversor.video

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.Settings
import androidx.core.content.ContextCompat
import java.io.File
import java.io.IOException
import java.util.Locale

/**
 * Acceso a los ficheros con rutas reales: ffmpeg es un proceso aparte y no entiende de content://,
 * así que la app pide "acceso a todos los archivos" (o el permiso clásico en Android 10 o anterior).
 */
object Almacen {
    val EXT_VIDEO = setOf(
        "mkv", "mp4", "m4v", "mov", "avi", "wmv", "asf", "flv", "f4v", "webm", "ts", "m2ts", "mts",
        "mpg", "mpeg", "vob", "3gp", "ogv", "divx", "rm", "rmvb", "mxf",
    )

    data class Raiz(val nombre: String, val dir: File, val extraible: Boolean)

    fun esVideo(f: File) = f.extension.lowercase(Locale.ROOT) in EXT_VIDEO

    fun tienePermiso(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else listOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE).all {
            ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED
        }

    /** Pantalla de Ajustes donde se concede "Acceso a todos los archivos" (Android 11+). */
    fun intentsPermiso(ctx: Context): List<Intent> = listOf(
        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${ctx.packageName}")),
        Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
    )

    fun raices(ctx: Context): List<Raiz> {
        val lista = mutableListOf<Raiz>()
        val sm = ctx.getSystemService(StorageManager::class.java)
        if (Build.VERSION.SDK_INT >= 30) {
            for (v in sm.storageVolumes) {
                val d = v.directory ?: continue
                if (v.state != Environment.MEDIA_MOUNTED && v.state != Environment.MEDIA_MOUNTED_READ_ONLY) continue
                lista += Raiz(if (v.isPrimary) "Memoria interna" else v.getDescription(ctx), d, v.isRemovable)
            }
        } else {
            lista += Raiz("Memoria interna", Environment.getExternalStorageDirectory(), false)
            ctx.getExternalFilesDirs(null).drop(1).filterNotNull().forEach { f ->
                val raiz = File(f.absolutePath.substringBefore("/Android/data/"))
                val nombre = sm.getStorageVolume(f)?.getDescription(ctx) ?: "Tarjeta / USB"
                lista += Raiz(nombre, raiz, true)
            }
        }
        if (lista.isEmpty()) lista += Raiz("Memoria interna", Environment.getExternalStorageDirectory(), false)
        return lista.distinctBy { it.dir.absolutePath }
    }

    /** Carpetas y vídeos de [dir], carpetas primero; sin ficheros ocultos. */
    fun listar(dir: File): List<File> =
        (dir.listFiles() ?: emptyArray())
            .filter { !it.name.startsWith(".") && (it.isDirectory || (it.isFile && esVideo(it))) }
            .sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase(Locale.ROOT) }))

    /** Vídeos de [dir] y sus subcarpetas (para "convertir la carpeta entera"). */
    fun videosRecursivo(dir: File, max: Int = 2000): List<File> =
        dir.walkTopDown()
            .onEnter { !it.name.startsWith(".") && it.name != "Convertidos" }
            .filter { it.isFile && esVideo(it) }
            .take(max)
            .sortedBy { it.path.lowercase(Locale.ROOT) }
            .toList()

    /**
     * Carpeta "Convertidos" junto al original. Si ahí no se puede escribir (USB en Android 10, carpeta de
     * solo lectura…), se usa Películas/Convertidos en la memoria interna.
     */
    fun carpetaSalida(origen: File): File {
        val padre = origen.absoluteFile.parentFile ?: File("/")
        val junto = if (padre.name == "Convertidos") padre else File(padre, "Convertidos")
        if (escribible(junto)) return junto
        val respaldo = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "Convertidos")
        if (escribible(respaldo)) return respaldo
        throw IOException("No se puede escribir ni junto al original ni en Películas/Convertidos. Revisa el permiso de archivos.")
    }

    private fun escribible(d: File): Boolean = try {
        d.mkdirs()
        val t = File(d, ".prueba_${System.nanoTime()}")
        val ok = t.createNewFile()
        t.delete()
        ok
    } catch (_: Exception) {
        false
    }

    fun nombreLibre(dir: File, base: String, ext: String): File {
        var f = File(dir, "$base.$ext")
        var n = 2
        while (f.exists()) f = File(dir, "$base (${n++}).$ext")
        return f
    }

    // ------------------------------------------------------------- "Abrir con" / "Compartir" desde otras apps

    /** Intenta averiguar la ruta real de un content:// de un gestor de archivos, la galería o Descargas. */
    fun rutaDeUri(ctx: Context, uri: Uri): String? {
        val candidatos = mutableListOf<String>()
        if (uri.scheme == "file") uri.path?.let { candidatos += it }
        if (uri.scheme == "content") {
            runCatching {
                if (DocumentsContract.isDocumentUri(ctx, uri)) {
                    val id = DocumentsContract.getDocumentId(uri)
                    when (uri.authority) {
                        "com.android.externalstorage.documents" -> {
                            val tipo = id.substringBefore(':')
                            val rel = id.substringAfter(':', "")
                            candidatos += if (tipo.equals("primary", ignoreCase = true))
                                File(Environment.getExternalStorageDirectory(), rel).path
                            else "/storage/$tipo/$rel"
                        }
                        "com.android.providers.downloads.documents" -> when {
                            id.startsWith("raw:") -> candidatos += id.removePrefix("raw:")
                            id.startsWith("msf:") ->
                                columnaData(ctx, MediaStore.Files.getContentUri("external"), id.removePrefix("msf:"))?.let { candidatos += it }
                            else -> id.toLongOrNull()?.let { n ->
                                columnaData(ctx, ContentUris.withAppendedId(Uri.parse("content://downloads/public_downloads"), n), null)
                                    ?.let { candidatos += it }
                            }
                        }
                        "com.android.providers.media.documents" -> {
                            val base = when (id.substringBefore(':')) {
                                "video" -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                                "audio" -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                                else -> MediaStore.Files.getContentUri("external")
                            }
                            columnaData(ctx, base, id.substringAfter(':'))?.let { candidatos += it }
                        }
                    }
                }
            }
            columnaData(ctx, uri, null)?.let { candidatos += it }
            // FileProvider de gestores de archivos: …/root/storage/emulated/0/Movies/x.mkv, …/external_files/Movies/x.mkv
            val ruta = Uri.decode(uri.encodedPath.orEmpty())
            ruta.indexOf("/storage/").takeIf { it >= 0 }?.let { candidatos += ruta.substring(it) }
            ruta.indexOf("/sdcard/").takeIf { it >= 0 }?.let {
                candidatos += Environment.getExternalStorageDirectory().path + ruta.substring(it + "/sdcard".length)
            }
            uri.pathSegments.drop(1).joinToString("/").takeIf { it.isNotBlank() }?.let {
                candidatos += File(Environment.getExternalStorageDirectory(), it).path
            }
        }
        return candidatos.firstOrNull { File(it).let { f -> f.isFile && f.canRead() } }
    }

    private fun columnaData(ctx: Context, uri: Uri, id: String?): String? = runCatching {
        val sel = if (id != null) "${MediaStore.MediaColumns._ID}=?" else null
        val args = if (id != null) arrayOf(id) else null
        @Suppress("DEPRECATION")
        ctx.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), sel, args, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
        }
    }.getOrNull()
}
