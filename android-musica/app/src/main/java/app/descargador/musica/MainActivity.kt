@file:OptIn(ExperimentalMaterial3Api::class)

package app.descargador.musica

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    companion object {
        const val EXTRA_TEXTO = "texto"
        /** Texto que llega desde "Compartir" (enlace sin carpeta elegida, o texto para buscar). */
        val textoEntrante = MutableStateFlow<String?>(null)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        leerIntent(intent)
        setContent { Tema { Pantalla() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        leerIntent(intent)
    }

    private fun leerIntent(i: Intent?) {
        i?.getStringExtra(EXTRA_TEXTO)?.let { textoEntrante.value = it }
    }
}

private val MORADO = Color(0xFF7B1FA2)

@Composable
private fun Tema(contenido: @Composable () -> Unit) {
    val oscuro = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val esquema = when {
        Build.VERSION.SDK_INT >= 31 -> if (oscuro) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        oscuro -> darkColorScheme(primary = Color(0xFFCE93D8))
        else -> lightColorScheme(primary = MORADO)
    }
    MaterialTheme(colorScheme = esquema) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { contenido() }
    }
}

@Composable
private fun Pantalla() {
    val ctx = LocalContext.current
    val ajustes = remember { Ajustes(ctx) }
    val portapapeles = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    val tareas by Motor.tareas.collectAsState()
    val log by Motor.log.collectAsState()
    val estadoMotor by Motor.estado.collectAsState()
    val version by Motor.version.collectAsState()
    val entrante by MainActivity.textoEntrante.collectAsState()

    var texto by rememberSaveable { mutableStateOf("") }
    var formato by remember { mutableStateOf(ajustes.formato) }
    var caratula by remember { mutableStateOf(ajustes.caratula) }
    var lista by remember { mutableStateOf(ajustes.lista) }
    var sinRelleno by remember { mutableStateOf(ajustes.sinRelleno) }
    var carpeta by remember { mutableStateOf(ajustes.carpetaUri) }
    var verLog by rememberSaveable { mutableStateOf(false) }

    var buscando by remember { mutableStateOf<String?>(null) }
    var resultados by remember { mutableStateOf<List<Resultado>?>(null) }
    val anadidos = remember { mutableStateListOf<String>() }

    val elegirCarpeta = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            ctx.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            ajustes.carpetaUri = uri
            carpeta = uri
        }
    }
    val pedirNotificaciones = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) pedirNotificaciones.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    fun opciones() = Opciones(caratula = caratula, lista = lista, sinRelleno = sinRelleno)

    fun buscar(q: String) {
        if (q.isBlank() || buscando != null) return
        scope.launch {
            buscando = q
            resultados = null
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    if (!Motor.esperarListo()) error("El motor no ha arrancado")
                    Buscador.buscar(q)
                }
            }
            buscando = null
            r.onSuccess {
                resultados = it
                if (it.isEmpty()) Toast.makeText(ctx, "Sin resultados para «$q»", Toast.LENGTH_SHORT).show()
            }.onFailure {
                Motor.log("Búsqueda fallida «$q»: ${it.message}")
                Toast.makeText(ctx, "No se pudo buscar: ${errorLegible(it.message)}", Toast.LENGTH_LONG).show()
            }
        }
    }

    fun encolar(url: String, titulo: String = "", opc: Opciones = opciones()): Boolean {
        if (carpeta == null) {
            Toast.makeText(ctx, "Elige primero la carpeta donde guardar la música", Toast.LENGTH_SHORT).show()
            elegirCarpeta.launch(null)
            return false
        }
        Motor.encolar(url, formato, opc, titulo)
        DescargaService.arrancar(ctx)
        return true
    }

    fun accion() {
        val urls = extraerUrls(texto)
        when {
            urls.isNotEmpty() -> if (urls.all { encolar(it) }) {
                texto = ""
                resultados = null
            }
            texto.isNotBlank() -> buscar(texto.trim())
            else -> Toast.makeText(ctx, "Pega un enlace o escribe una canción", Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(entrante) {
        val e = entrante ?: return@LaunchedEffect
        MainActivity.textoEntrante.value = null
        texto = e
        if (extraerUrls(e).isEmpty()) buscar(e.trim())
    }

    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text("Descargar música", style = MaterialTheme.typography.titleLarge)
        val (textoMotor, colorMotor) = when (val e = estadoMotor) {
            EstadoMotor.Iniciando -> "Preparando el motor (la primera vez tarda un poco)…" to MaterialTheme.colorScheme.onSurfaceVariant
            EstadoMotor.Listo -> "yt-dlp $version" to MaterialTheme.colorScheme.onSurfaceVariant
            is EstadoMotor.Fallo -> "Error del motor: ${e.mensaje}" to MaterialTheme.colorScheme.error
        }
        Text(textoMotor, style = MaterialTheme.typography.bodySmall, color = colorMotor)
        Spacer(Modifier.height(10.dp))

        val esEnlace = extraerUrls(texto).isNotEmpty()
        OutlinedTextField(
            value = texto,
            onValueChange = { texto = it },
            label = { Text("Enlace, o canción y artista para buscar") },
            modifier = Modifier.fillMaxWidth(),
            maxLines = 3,
            keyboardOptions = KeyboardOptions(imeAction = if (esEnlace) ImeAction.Go else ImeAction.Search),
            keyboardActions = KeyboardActions(onGo = { accion() }, onSearch = { accion() }),
        )
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { portapapeles.getText()?.text?.let { texto = it } }) { Text("Pegar") }
            Button(onClick = { accion() }, modifier = Modifier.weight(1f), enabled = buscando == null) {
                Text(if (esEnlace || texto.isBlank()) "Descargar" else "Buscar en YouTube")
            }
        }

        Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp)) {
            Formato.entries.forEach { f ->
                FilterChip(
                    selected = f == formato,
                    onClick = { formato = f; ajustes.formato = f },
                    label = { Text(f.etiqueta) },
                    modifier = Modifier.padding(end = 6.dp),
                )
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            Casilla("Carátula", caratula) { caratula = it; ajustes.caratula = it }
            Casilla("Lista completa", lista) { lista = it; ajustes.lista = it }
            Casilla("Quitar intros (YouTube)", sinRelleno) { sinRelleno = it; ajustes.sinRelleno = it }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Carpeta: ${Guardado.nombreCarpeta(ctx, carpeta)}",
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { elegirCarpeta.launch(carpeta) }) { Text(if (carpeta == null) "Elegir" else "Cambiar") }
        }

        Row(Modifier.horizontalScroll(rememberScrollState())) {
            TextButton(onClick = { Motor.cancelarActual() }) { Text("Cancelar") }
            TextButton(onClick = { Motor.cancelarPendientes(); Motor.cancelarActual() }) { Text("Detener todo") }
            TextButton(onClick = {
                tareas.filter { it.estado == Estado.ERROR || it.estado == Estado.CANCELADA }.forEach { Motor.reintentar(it.id) }
                DescargaService.arrancar(ctx)
            }) { Text("Reintentar errores") }
            TextButton(onClick = { Motor.quitarTerminadas() }) { Text("Limpiar") }
            TextButton(onClick = {
                scope.launch {
                    Toast.makeText(ctx, "Actualizando yt-dlp…", Toast.LENGTH_SHORT).show()
                    val msg = withContext(Dispatchers.IO) { Motor.actualizarYtDlp(ctx.applicationContext) }
                    Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
                }
            }) { Text("Actualizar yt-dlp") }
        }
        HorizontalDivider(Modifier.padding(vertical = 4.dp))

        val q = buscando
        val res = resultados
        when {
            q != null -> {
                Text("Buscando «$q» en YouTube…", style = MaterialTheme.typography.titleSmall)
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
            }

            res != null -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Resultados (${res.size})", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = { resultados = null }) { Text("Ver descargas") }
                }
                LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                    items(res, key = { it.url }) { r ->
                        FilaResultado(r, anadido = r.url in anadidos) {
                            if (encolar(r.url, r.titulo, opciones().copy(lista = false))) {
                                anadidos += r.url
                                Toast.makeText(ctx, "Descargando: ${r.titulo}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
            }

            else -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (verLog) "Log" else "Descargas (${tareas.size})",
                        style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { verLog = !verLog }) { Text(if (verLog) "Ver descargas" else "Ver log") }
                }
                if (verLog) {
                    val estadoLista = rememberLazyListState()
                    LaunchedEffect(log.size) { if (log.isNotEmpty()) estadoLista.scrollToItem(log.lastIndex) }
                    LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = estadoLista) {
                        items(log) { linea ->
                            val color = when {
                                "ERROR" in linea || "✗" in linea -> MaterialTheme.colorScheme.error
                                else -> MaterialTheme.colorScheme.onSurface
                            }
                            Text(linea, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = color)
                        }
                    }
                } else if (tareas.isEmpty()) {
                    Text(
                        "Pega un enlace de YouTube, TikTok, X, Instagram, SoundCloud, Spotify… o escribe el nombre " +
                            "de una canción.\n\nDesde cualquier app también puedes pulsar Compartir → \"Descargar música\".",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                } else {
                    LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                        items(tareas.asReversed(), key = { it.id }) { t -> FilaTarea(t, carpeta) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Casilla(texto: String, marcada: Boolean, cambio: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = marcada, onCheckedChange = cambio)
        Text(texto, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.width(8.dp))
    }
}

@Composable
private fun FilaResultado(r: Resultado, anadido: Boolean, descargar: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(r.titulo, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    listOf(r.canal, fmtTiempo(r.duracionSeg)).filter { it.isNotBlank() }.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            TextButton(onClick = descargar, enabled = !anadido) { Text(if (anadido) "En cola" else "Descargar") }
        }
    }
}

@Composable
private fun FilaTarea(t: Tarea, carpeta: Uri?) {
    val ctx = LocalContext.current
    val oscuro = isSystemInDarkTheme()
    val verde = if (oscuro) Color(0xFF7FD69B) else Color(0xFF1B7F3B)
    val ambar = if (oscuro) Color(0xFFFFCC80) else Color(0xFF9A5B00)
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text(
                t.titulo.ifBlank { t.url },
                style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            val lista = if (t.items > 1) "Canción ${t.item} de ${t.items} · " else ""
            val (texto, color) = when (t.estado) {
                Estado.EN_COLA -> "En cola · ${t.formato.etiqueta}" to MaterialTheme.colorScheme.onSurfaceVariant
                Estado.DESCARGANDO -> buildString {
                    append(lista).append("Descargando · ").append("%.1f".format(t.progreso)).append(" %")
                    if (t.etaSeg >= 0) append(" · ETA ").append(fmtTiempo(t.etaSeg))
                } to MaterialTheme.colorScheme.primary
                Estado.PROCESANDO -> lista + t.paso.ifBlank { "Procesando…" } to MaterialTheme.colorScheme.primary
                Estado.GUARDANDO -> "Guardando en la carpeta…" to MaterialTheme.colorScheme.primary
                Estado.OK -> (if (t.archivos.size > 1)
                    "OK · ${t.archivos.size} canciones · ${fmtBytes(t.bytes)} · en ${Guardado.nombreCarpeta(ctx, carpeta)}"
                else "OK · ${t.formato.etiqueta} · ${fmtBytes(t.bytes)}") to verde
                Estado.ERROR -> "Error: ${t.error}" to MaterialTheme.colorScheme.error
                Estado.CANCELADA -> "Cancelada" to MaterialTheme.colorScheme.onSurfaceVariant
            }
            Text(texto, style = MaterialTheme.typography.bodySmall, color = color, maxLines = 3, overflow = TextOverflow.Ellipsis)
            if (t.aviso.isNotBlank()) {
                Text(t.aviso, style = MaterialTheme.typography.bodySmall, color = ambar, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            when (t.estado) {
                Estado.DESCARGANDO -> LinearProgressIndicator(
                    progress = { (t.progreso / 100f).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                )
                Estado.PROCESANDO, Estado.GUARDANDO ->
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp))
                Estado.ERROR, Estado.CANCELADA -> TextButton(onClick = {
                    Motor.reintentar(t.id)
                    DescargaService.arrancar(ctx)
                }) { Text("Reintentar") }
                Estado.OK -> t.archivos.firstOrNull()?.let { a ->
                    Row {
                        TextButton(onClick = { abrir(ctx, a) }) { Text(if (t.archivos.size > 1) "Escuchar la 1ª" else "Escuchar") }
                        TextButton(onClick = { compartir(ctx, t.archivos) }) { Text("Compartir") }
                    }
                }
                else -> Unit
            }
        }
    }
}

private fun abrir(ctx: Context, a: Archivo) {
    val i = Intent(Intent.ACTION_VIEW)
        .setDataAndType(Uri.parse(a.uri), a.mime ?: "audio/*")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        ctx.startActivity(i)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(ctx, "No hay ninguna app para reproducir este archivo", Toast.LENGTH_SHORT).show()
    } catch (e: SecurityException) {
        Toast.makeText(ctx, "No se puede abrir: el archivo se ha movido o borrado", Toast.LENGTH_SHORT).show()
    }
}

private fun compartir(ctx: Context, archivos: List<Archivo>) {
    val uris = archivos.map { Uri.parse(it.uri) }
    val tipo = archivos.map { it.mime }.distinct().singleOrNull() ?: "audio/*"
    val i = if (uris.size == 1) {
        Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
    }
    i.type = tipo
    i.clipData = ClipData.newRawUri("", uris[0]).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        ctx.startActivity(Intent.createChooser(i, "Compartir"))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(ctx, "No hay ninguna app para compartir", Toast.LENGTH_SHORT).show()
    }
}
