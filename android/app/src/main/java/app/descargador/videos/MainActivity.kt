@file:OptIn(ExperimentalMaterial3Api::class)

package app.descargador.videos

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.compose.ui.text.input.KeyboardType
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
        const val EXTRA_URL = "url"
        /** URL que llega desde "Compartir" cuando aún no hay carpeta elegida. */
        val urlEntrante = MutableStateFlow<String?>(null)
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
        i?.getStringExtra(EXTRA_URL)?.let { urlEntrante.value = it }
    }
}

@Composable
private fun Tema(contenido: @Composable () -> Unit) {
    val oscuro = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val esquema = when {
        Build.VERSION.SDK_INT >= 31 -> if (oscuro) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        oscuro -> darkColorScheme()
        else -> lightColorScheme()
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
    val entrante by MainActivity.urlEntrante.collectAsState()

    var url by rememberSaveable { mutableStateOf("") }
    var calidad by remember { mutableStateOf(ajustes.calidad) }
    var playlist by remember { mutableStateOf(ajustes.playlist) }
    var carpeta by remember { mutableStateOf(ajustes.carpetaUri) }
    var verLog by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(entrante) {
        entrante?.let { url = it; MainActivity.urlEntrante.value = null }
    }

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

    fun descargar() {
        val urls = extraerUrls(url)
        when {
            urls.isEmpty() -> Toast.makeText(ctx, "Pega un enlace que empiece por http", Toast.LENGTH_SHORT).show()
            carpeta == null -> {
                Toast.makeText(ctx, "Elige primero la carpeta donde guardar", Toast.LENGTH_SHORT).show()
                elegirCarpeta.launch(null)
            }
            else -> {
                urls.forEach { Motor.encolar(it, calidad, playlist) }
                DescargaService.arrancar(ctx)
                url = ""
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text("Descargador de vídeos", style = MaterialTheme.typography.titleLarge)
        val (textoMotor, colorMotor) = when (val e = estadoMotor) {
            EstadoMotor.Iniciando -> "Preparando el motor (la primera vez tarda un poco)…" to MaterialTheme.colorScheme.onSurfaceVariant
            EstadoMotor.Listo -> "yt-dlp $version" to MaterialTheme.colorScheme.onSurfaceVariant
            is EstadoMotor.Fallo -> "Error del motor: ${e.mensaje}" to MaterialTheme.colorScheme.error
        }
        Text(textoMotor, style = MaterialTheme.typography.bodySmall, color = colorMotor)
        Spacer(Modifier.height(10.dp))

        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text("Enlace del vídeo o de la página") },
            modifier = Modifier.fillMaxWidth(),
            maxLines = 3,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { descargar() }),
        )
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { portapapeles.getText()?.text?.let { url = it } }) { Text("Pegar") }
            Button(onClick = { descargar() }, modifier = Modifier.weight(1f)) { Text("Descargar") }
        }

        Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp)) {
            Calidad.entries.forEach { c ->
                FilterChip(
                    selected = c == calidad,
                    onClick = { calidad = c; ajustes.calidad = c },
                    label = { Text(c.etiqueta) },
                    modifier = Modifier.padding(end = 6.dp),
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = playlist, onCheckedChange = { playlist = it; ajustes.playlist = it })
            Text("Listas completas", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.width(12.dp))
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
                "Pega un enlace arriba, o desde el navegador pulsa Compartir → \"Descargar vídeo\".",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 16.dp),
            )
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                items(tareas.asReversed(), key = { it.id }) { t -> FilaTarea(t) }
            }
        }
    }
}

@Composable
private fun FilaTarea(t: Tarea) {
    val ctx = LocalContext.current
    val verde = if (isSystemInDarkTheme()) Color(0xFF7FD69B) else Color(0xFF1B7F3B)
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text(
                t.titulo.ifBlank { t.url },
                style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            val (texto, color) = when (t.estado) {
                Estado.EN_COLA -> "En cola · ${t.calidad.etiqueta}" to MaterialTheme.colorScheme.onSurfaceVariant
                Estado.DESCARGANDO -> buildString {
                    append("Descargando · ").append("%.1f".format(t.progreso)).append(" %")
                    if (t.etaSeg >= 0) append(" · ETA ").append(fmtTiempo(t.etaSeg))
                } to MaterialTheme.colorScheme.primary
                Estado.PROCESANDO -> "Procesando…" to MaterialTheme.colorScheme.primary
                Estado.GUARDANDO -> "Guardando en la carpeta…" to MaterialTheme.colorScheme.primary
                Estado.OK -> "OK · ${fmtBytes(t.bytes)} · ${t.archivos.lastOrNull().orEmpty()}" to verde
                Estado.ERROR -> "Error: ${t.error}" to MaterialTheme.colorScheme.error
                Estado.CANCELADA -> "Cancelada" to MaterialTheme.colorScheme.onSurfaceVariant
            }
            Text(texto, style = MaterialTheme.typography.bodySmall, color = color, maxLines = 3, overflow = TextOverflow.Ellipsis)
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
                else -> Unit
            }
        }
    }
}
