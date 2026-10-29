package app.descargador.musica

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast

/**
 * Sin interfaz: recibe lo que se comparte desde YouTube, TikTok, X, Spotify, el navegador…,
 * lo mete en la cola con el formato elegido en la app y se cierra.
 * Si lo compartido no lleva enlace (p. ej. "Artista - Canción"), abre la app con la búsqueda.
 */
class CompartirActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val texto = buildString {
            intent?.getStringExtra(Intent.EXTRA_TEXT)?.let { append(it).append(' ') }
            intent?.getStringExtra(Intent.EXTRA_SUBJECT)?.let { append(it) }
        }.trim()
        val urls = extraerUrls(texto)
        val ajustes = Ajustes(this)

        when {
            texto.isBlank() ->
                Toast.makeText(this, "No ha llegado nada que descargar", Toast.LENGTH_LONG).show()

            urls.isEmpty() || ajustes.carpetaUri == null -> {
                if (ajustes.carpetaUri == null) {
                    Toast.makeText(this, "Elige primero la carpeta donde guardar la música", Toast.LENGTH_LONG).show()
                }
                startActivity(
                    Intent(this, MainActivity::class.java)
                        .putExtra(MainActivity.EXTRA_TEXTO, if (urls.isEmpty()) texto else urls.joinToString("\n"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }

            else -> {
                urls.forEach { Motor.encolar(it, ajustes.formato, ajustes.opciones()) }
                DescargaService.arrancar(this)
                val que = if (urls.size == 1) urls[0] else "${urls.size} enlaces"
                Toast.makeText(this, "Descargando (${ajustes.formato.etiqueta}): $que", Toast.LENGTH_SHORT).show()
            }
        }
        finish()
    }
}
