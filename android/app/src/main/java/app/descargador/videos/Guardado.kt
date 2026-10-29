package app.descargador.videos

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import com.yausername.youtubedl_android.YoutubeDL
import java.io.File
import java.io.IOException

/**
 * yt-dlp necesita rutas de fichero reales, así que descarga en la caché de la app
 * y aquí se copia el resultado a la carpeta que eligió el usuario (SAF).
 */
object Guardado {
    fun nombreCarpeta(ctx: Context, uri: Uri?): String =
        uri?.let { DocumentFile.fromTreeUri(ctx, it)?.name } ?: "sin elegir"

    /** Devuelve el nombre final del fichero en la carpeta destino. */
    fun copiar(ctx: Context, carpeta: Uri, f: File, cancelado: () -> Boolean): String {
        val dir = DocumentFile.fromTreeUri(ctx, carpeta)
            ?: throw IOException("No se puede acceder a la carpeta de destino; vuelve a elegirla")
        if (!dir.canWrite()) throw IOException("Sin permiso de escritura en la carpeta; vuelve a elegirla")

        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase())
        // Con MIME conocido el sistema añade la extensión él solo; si no, se pasa el nombre completo.
        val doc = (if (mime != null) dir.createFile(mime, f.nameWithoutExtension)
        else dir.createFile("application/octet-stream", f.name))
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
        return doc.name ?: f.name
    }
}
