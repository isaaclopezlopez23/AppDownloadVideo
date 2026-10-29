package app.descargador.musica

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import com.yausername.youtubedl_android.YoutubeDL
import java.io.File
import java.io.IOException

/**
 * yt-dlp necesita rutas de fichero reales, así que descarga en la caché de la app
 * y aquí se copia el resultado a la carpeta que eligió el usuario (SAF), respetando
 * las subcarpetas (una por lista de reproducción).
 */
object Guardado {
    fun nombreCarpeta(ctx: Context, uri: Uri?): String =
        uri?.let { DocumentFile.fromTreeUri(ctx, it)?.name } ?: "sin elegir"

    /** Ficheros de audio terminados dentro de [raiz] (ignora parciales y miniaturas sueltas). */
    fun audios(raiz: File): List<File> =
        raiz.walkTopDown()
            .filter { it.isFile && it.extension.lowercase() in EXT_AUDIO && ".temp." !in it.name }
            .sortedBy { it.path }
            .toList()

    fun guardar(ctx: Context, carpeta: Uri, raiz: File, cancelado: () -> Boolean): List<Archivo> {
        val dir = DocumentFile.fromTreeUri(ctx, carpeta)
            ?: throw IOException("No se puede acceder a la carpeta de destino; vuelve a elegirla")
        if (!dir.canWrite()) throw IOException("Sin permiso de escritura en la carpeta; vuelve a elegirla")
        return audios(raiz).map { f ->
            val sub = f.parentFile?.relativeTo(raiz)?.invariantSeparatorsPath.orEmpty()
            var destino = dir
            for (nombre in sub.split('/').filter { it.isNotBlank() && it != "." }) {
                destino = destino.findFile(nombre)?.takeIf { it.isDirectory }
                    ?: destino.createDirectory(nombre)
                    ?: throw IOException("No se pudo crear la carpeta $nombre")
            }
            copiar(ctx, destino, f, cancelado)
        }
    }

    private fun copiar(ctx: Context, dir: DocumentFile, f: File, cancelado: () -> Boolean): Archivo {
        // Se pasa el nombre completo con el MIME de su propia extensión: así el sistema respeta la extensión.
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase())
        val doc = dir.createFile(mime ?: "application/octet-stream", f.name)
            ?: throw IOException("No se pudo crear ${f.name} en la carpeta de destino")
        try {
            val out = ctx.contentResolver.openOutputStream(doc.uri)
                ?: throw IOException("No se pudo escribir ${f.name}")
            out.use { salida ->
                f.inputStream().use { entrada ->
                    val buf = ByteArray(256 * 1024)
                    while (true) {
                        val n = entrada.read(buf)
                        if (n < 0) break
                        if (cancelado()) throw YoutubeDL.CanceledException()
                        salida.write(buf, 0, n)
                    }
                }
            }
        } catch (e: Exception) {
            doc.delete()
            throw e
        }
        return Archivo(doc.name ?: f.name, doc.uri.toString(), mime)
    }
}
