@file:OptIn(ExperimentalMaterial3Api::class)

package app.conversor.video

import android.os.Environment
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val FECHA = SimpleDateFormat("d MMM yyyy", Locale("es", "ES"))

@Composable
fun Explorador(ajustes: Ajustes, seleccion: SnapshotStateList<String>, onConvertir: (List<String>) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var carpeta by rememberSaveable { mutableStateOf(ajustes.ultimaCarpeta?.takeIf { File(it).isDirectory }) }
    var raices by remember { mutableStateOf(emptyList<Almacen.Raiz>()) }
    var contenido by remember { mutableStateOf<List<File>?>(null) }
    var convertidos by remember { mutableStateOf(emptySet<String>()) }
    var infoDe by remember { mutableStateOf<String?>(null) }
    var buscando by remember { mutableStateOf(false) }

    LaunchedEffect(carpeta) {
        contenido = null
        raices = withContext(Dispatchers.IO) { Almacen.raices(ctx) }
        val c = carpeta
        if (c == null) {
            contenido = emptyList()
        } else {
            val dir = File(c)
            val (lista, hechos) = withContext(Dispatchers.IO) {
                Almacen.listar(dir) to (File(dir, "Convertidos").list()?.map { it.substringBeforeLast('.') }?.toSet() ?: emptySet())
            }
            contenido = lista
            convertidos = hechos
            ajustes.ultimaCarpeta = c
        }
    }

    fun subir() {
        val c = carpeta ?: return
        val padre = File(c).parentFile
        carpeta = if (raices.any { it.dir.absolutePath == c } || padre == null || padre.listFiles() == null) null else padre.path
    }
    BackHandler(enabled = carpeta != null) { subir() }

    Column(Modifier.fillMaxSize()) {
        // Ruta actual
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (carpeta != null) TextButton(onClick = { subir() }) { Text("⬆ Subir") }
            Text(
                rutaLegible(carpeta, raices),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 12.dp),
            )
        }
        HorizontalDivider()

        val c = carpeta
        if (c == null) {
            Box(Modifier.weight(1f).fillMaxWidth()) { Raices(raices, onAbrir = { carpeta = it }) }
        } else {
            val lista = contenido
            val videos = lista.orEmpty().filter { it.isFile }
            if (videos.isNotEmpty() || lista.orEmpty().any { it.isDirectory }) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (videos.isNotEmpty()) {
                        val todos = videos.all { it.path in seleccion }
                        TextButton(onClick = {
                            if (todos) seleccion.removeAll(videos.map { it.path }.toSet())
                            else videos.forEach { if (it.path !in seleccion) seleccion.add(it.path) }
                        }) { Text(if (todos) "Quitar estos ${videos.size}" else "Marcar los ${videos.size}") }
                    }
                    if (lista.orEmpty().any { it.isDirectory }) {
                        TextButton(enabled = !buscando, onClick = {
                            buscando = true
                            scope.launch {
                                val todos = withContext(Dispatchers.IO) { Almacen.videosRecursivo(File(c)) }
                                todos.forEach { if (it.path !in seleccion) seleccion.add(it.path) }
                                buscando = false
                            }
                        }) { Text(if (buscando) "Buscando…" else "Marcar con subcarpetas") }
                    }
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    lista == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                    lista.isEmpty() -> Text(
                        "Aquí no hay vídeos ni carpetas.",
                        Modifier.align(Alignment.Center).padding(24.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    else -> LazyColumn(Modifier.fillMaxSize()) {
                        items(lista, key = { it.path }) { f ->
                            if (f.isDirectory) {
                                FilaCarpeta(f) { carpeta = f.path }
                            } else {
                                FilaVideo(
                                    f = f,
                                    marcado = f.path in seleccion,
                                    yaConvertido = f.nameWithoutExtension in convertidos,
                                    onMarcar = { if (f.path in seleccion) seleccion.remove(f.path) else seleccion.add(f.path) },
                                    onInfo = { infoDe = f.path },
                                )
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
        if (seleccion.isNotEmpty()) {
            Surface(tonalElevation = 3.dp) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${seleccion.size} ${if (seleccion.size == 1) "vídeo" else "vídeos"}",
                        Modifier.weight(1f),
                        fontWeight = FontWeight.SemiBold,
                    )
                    TextButton(onClick = { seleccion.clear() }) { Text("Quitar") }
                    Spacer(Modifier.width(4.dp))
                    Button(onClick = { onConvertir(seleccion.toList()) }) { Text("Convertir") }
                }
            }
        }
    }

    infoDe?.let { ruta ->
        DialogoInfo(ruta, onCerrar = { infoDe = null }, onConvertir = {
            infoDe = null
            onConvertir(listOf(ruta))
        })
    }
}

@Composable
private fun Raices(raices: List<Almacen.Raiz>, onAbrir: (String) -> Unit) {
    val principal = Environment.getExternalStorageDirectory()
    val accesos = listOf(
        "⬇️ Descargas" to File(principal, Environment.DIRECTORY_DOWNLOADS),
        "🎞️ Películas" to File(principal, Environment.DIRECTORY_MOVIES),
        "📷 Cámara" to File(principal, Environment.DIRECTORY_DCIM),
    ).filter { it.second.isDirectory }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp)) {
        Text("Almacenamiento", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(4.dp))
        raices.forEach { r ->
            Card(Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { onAbrir(r.dir.path) }) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (r.extraible) "🔌" else "📱", fontSize = 24.sp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(r.nombre, fontWeight = FontWeight.SemiBold)
                        Text(
                            "${fmtBytes(r.dir.usableSpace)} libres de ${fmtBytes(r.dir.totalSpace)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        if (accesos.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text("Accesos directos", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(4.dp))
            accesos.forEach { (nombre, dir) ->
                Card(Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { onAbrir(dir.path) }) {
                    Text(nombre, Modifier.padding(16.dp))
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(
            "¿Tienes los vídeos en un pendrive? Conéctalo al móvil con un adaptador OTG y aparecerá aquí.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(4.dp),
        )
    }
}

@Composable
private fun FilaCarpeta(f: File, onAbrir: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onAbrir).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("📁", fontSize = 20.sp)
        Spacer(Modifier.width(12.dp))
        Text(f.name, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text("›", fontSize = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun FilaVideo(f: File, marcado: Boolean, yaConvertido: Boolean, onMarcar: () -> Unit, onInfo: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onMarcar).padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = marcado, onCheckedChange = { onMarcar() })
        Column(Modifier.weight(1f)) {
            Text(f.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                buildString {
                    append(fmtBytes(f.length()))
                    append(" · ")
                    append(FECHA.format(Date(f.lastModified())))
                    if (yaConvertido) append(" · ✓ ya en Convertidos")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onInfo) { Text("ⓘ", fontSize = 18.sp) }
    }
}

/** Analiza un vídeo y dice qué pistas tiene y si va a sonar en un proyector o tele. */
@Composable
private fun DialogoInfo(ruta: String, onCerrar: () -> Unit, onConvertir: () -> Unit) {
    var info by remember { mutableStateOf<Result<InfoMedia>?>(null) }
    LaunchedEffect(ruta) {
        info = withContext(Dispatchers.IO) {
            if (!Motor.esperarListo()) Result.failure(IllegalStateException("ffmpeg no está disponible"))
            else runCatching { Binarios.analizar(ruta) }
        }
    }
    AlertDialog(
        onDismissRequest = onCerrar,
        title = { Text(File(ruta).name, maxLines = 3, overflow = TextOverflow.Ellipsis, fontSize = 18.sp) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                val r = info
                when {
                    r == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.padding(8.dp))
                        Text("Analizando…")
                    }
                    r.isFailure -> Text(r.exceptionOrNull()?.message ?: "No se pudo analizar", color = MaterialTheme.colorScheme.error)
                    else -> {
                        val i = r.getOrThrow()
                        Text("Duración ${fmtTiempo(i.duracionSeg.toLong())} · ${fmtBytes(File(ruta).length())}")
                        Spacer(Modifier.height(8.dp))
                        i.videos.forEach { Text("🎞️ ${it.descripcion()}") }
                        i.audios.forEach {
                            val ok = it.codec in Planificador.AUDIO_COMPATIBLE
                            Text("${if (ok) "🔊" else "🔇"} ${it.descripcion()}")
                        }
                        i.subtitulos.forEach { Text("💬 ${it.descripcion()}") }
                        Spacer(Modifier.height(12.dp))
                        val malos = i.audios.filter { it.codec !in Planificador.AUDIO_COMPATIBLE }
                        val videoRaro = i.videos.firstOrNull()?.let { it.codec != "h264" } ?: false
                        Text(
                            when {
                                i.audios.isEmpty() -> "No tiene audio."
                                malos.isNotEmpty() ->
                                    "El audio ${malos.joinToString { nombreCodec(it.codec) }} no suena en muchos proyectores y teles " +
                                        "(no pagan la licencia). Con \"Arreglar sonido\" se soluciona en un momento."
                                else -> "El audio es compatible: debería sonar en cualquier sitio."
                            } + if (videoRaro) "\n\nEl vídeo no es H.264: si en algún aparato no se ve, usa \"MP4 compatible\"." else "",
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onConvertir) { Text("Convertir") } },
        dismissButton = { TextButton(onClick = onCerrar) { Text("Cerrar") } },
    )
}

private fun rutaLegible(carpeta: String?, raices: List<Almacen.Raiz>): String {
    if (carpeta == null) return "Elige dónde están los vídeos"
    val raiz = raices.filter { carpeta.startsWith(it.dir.absolutePath) }.maxByOrNull { it.dir.absolutePath.length }
        ?: return carpeta
    val resto = carpeta.removePrefix(raiz.dir.absolutePath).trim('/')
    return if (resto.isEmpty()) raiz.nombre else "${raiz.nombre} › ${resto.replace("/", " › ")}"
}
