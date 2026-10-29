package app.descargador.videos

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast

/**
 * Sin interfaz: recibe lo que se comparte desde el navegador (o YouTube, Instagram, X…),
 * lo mete en la cola y se cierra. La descarga sigue en segundo plano con notificación.
 */
class CompartirActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val texto = buildString {
            intent?.getStringExtra(Intent.EXTRA_TEXT)?.let { append(it).append(' ') }
            intent?.getStringExtra(Intent.EXTRA_SUBJECT)?.let { append(it) }
        }
        val urls = extraerUrls(texto)
        val ajustes = Ajustes(this)

        when {
            urls.isEmpty() ->
                Toast.makeText(this, "No he encontrado ningún enlace en lo compartido", Toast.LENGTH_LONG).show()

            ajustes.carpetaUri == null -> {
                Toast.makeText(this, "Elige primero la carpeta donde guardar", Toast.LENGTH_LONG).show()
                startActivity(
                    Intent(this, MainActivity::class.java)
                        .putExtra(MainActivity.EXTRA_URL, urls.joinToString("\n"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }

            else -> {
                urls.forEach { Motor.encolar(it, ajustes.calidad, ajustes.playlist) }
                DescargaService.arrancar(this)
                val que = if (urls.size == 1) urls[0] else "${urls.size} enlaces"
                Toast.makeText(this, "Descargando (${ajustes.calidad.etiqueta}): $que", Toast.LENGTH_SHORT).show()
            }
        }
        finish()
    }
}
