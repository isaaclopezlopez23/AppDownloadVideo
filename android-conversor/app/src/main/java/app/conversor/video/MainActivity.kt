@file:OptIn(ExperimentalMaterial3Api::class)

package app.conversor.video

import android.Manifest
import android.content.ActivityNotFoundException
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
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    companion object {
        const val EXTRA_PESTANA = "pestana"
        /** Vídeos que llegan desde "Abrir con" o "Compartir" de otra app. */
        val entrantes = MutableStateFlow<List<Uri>>(emptyList())
        val pestanaPedida = MutableStateFlow<Int?>(null)
        val permiso = MutableStateFlow(false)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        permiso.value = Almacen.tienePermiso(this)
        leerIntent(intent)
        setContent { Tema { Pantalla() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        leerIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        // Al volver de Ajustes tras conceder (o no) el acceso a archivos.
        permiso.value = Almacen.tienePermiso(this)
    }

    private fun leerIntent(i: Intent?) {
        if (i == null) return
        val uris = when (i.action) {
            Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(i, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE ->
                IntentCompat.getParcelableArrayListExtra(i, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            Intent.ACTION_VIEW -> listOfNotNull(i.data)
            else -> emptyList()
        }
        if (uris.isNotEmpty()) entrantes.value = entrantes.value + uris
        if (i.hasExtra(EXTRA_PESTANA)) pestanaPedida.value = i.getIntExtra(EXTRA_PESTANA, 0)
    }
}

private val VERDE = Color(0xFF00796B)

@Composable
private fun Tema(contenido: @Composable () -> Unit) {
    val oscuro = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val esquema = when {
        Build.VERSION.SDK_INT >= 31 -> if (oscuro) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        oscuro -> darkColorScheme(primary = Color(0xFF4DB6AC))
        else -> lightColorScheme(primary = VERDE)
    }
    MaterialTheme(colorScheme = esquema) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { contenido() }
    }
}

@Composable
private fun Pantalla() {
    val ctx = LocalContext.current
    val permiso by MainActivity.permiso.collectAsState()
    if (!permiso) {
        PantallaPermiso()
        return
    }

    val ajustes = remember { Ajustes(ctx) }
    val tareas by Motor.tareas.collectAsState()
    val estadoMotor by Motor.estado.collectAsState()
    val entrantes by MainActivity.entrantes.collectAsState()
    val pedida by MainActivity.pestanaPedida.collectAsState()
    var pestana by rememberSaveable { mutableIntStateOf(0) }
    var paraConvertir by remember { mutableStateOf<List<String>?>(null) }
    val seleccion = remember { mutableStateListOf<String>() }

    // Notificación de progreso (Android 13+): se pide una vez.
    val lanzarNotif = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) lanzarNotif.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    LaunchedEffect(pedida) {
        pedida?.let {
            pestana = it
            MainActivity.pestanaPedida.value = null
        }
    }

    // Vídeos abiertos desde otra app: se localiza su ruta real y se ofrecen las opciones.
    LaunchedEffect(entrantes) {
        if (entrantes.isEmpty()) return@LaunchedEffect
        val uris = entrantes
        MainActivity.entrantes.value = emptyList()
        val rutas = withContext(Dispatchers.IO) { uris.mapNotNull { Almacen.rutaDeUri(ctx, it) }.distinct() }
        val perdidos = uris.size - rutas.size
        if (perdidos > 0) {
            Toast.makeText(
                ctx,
                "No he podido localizar ${if (perdidos == 1) "un fichero" else "$perdidos ficheros"}. Ábrelo desde la pestaña Archivos.",
                Toast.LENGTH_LONG,
            ).show()
        }
        if (rutas.isNotEmpty()) paraConvertir = rutas
    }

    val activas = tareas.count { !it.estado.terminada }
    Scaffold(
        topBar = {
            TopAppBar(title = {
                Text(
                    when (pestana) {
                        0 -> "Conversor de vídeo"
                        1 -> "Cola"
                        else -> "Registro"
                    },
                )
            })
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = pestana == 0, onClick = { pestana = 0 },
                    icon = { Text("📁", fontSize = 20.sp) }, label = { Text("Archivos") },
                )
                NavigationBarItem(
                    selected = pestana == 1, onClick = { pestana = 1 },
                    icon = {
                        BadgedBox(badge = { if (activas > 0) Badge { Text("$activas") } }) { Text("⏳", fontSize = 20.sp) }
                    },
                    label = { Text("Cola") },
                )
                NavigationBarItem(
                    selected = pestana == 2, onClick = { pestana = 2 },
                    icon = { Text("📝", fontSize = 20.sp) }, label = { Text("Registro") },
                )
            }
        },
    ) { relleno ->
        Column(Modifier.padding(relleno).fillMaxSize()) {
            (estadoMotor as? EstadoMotor.Fallo)?.let { f ->
                Card(
                    Modifier.fillMaxWidth().padding(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                ) {
                    Text("No se pudo preparar ffmpeg: ${f.mensaje}", Modifier.padding(12.dp))
                }
            }
            when (pestana) {
                0 -> Explorador(ajustes, seleccion, onConvertir = { paraConvertir = it })
                1 -> ColaUi(tareas)
                else -> Registro(estadoMotor)
            }
        }
    }

    paraConvertir?.let { rutas ->
        OpcionesSheet(
            rutas = rutas,
            ajustes = ajustes,
            onCerrar = { paraConvertir = null },
            onConvertir = { opciones ->
                val n = Motor.encolar(rutas, opciones)
                paraConvertir = null
                seleccion.removeAll(rutas.toSet())
                if (n == 0) {
                    Toast.makeText(ctx, "Esos vídeos ya están en la cola", Toast.LENGTH_SHORT).show()
                } else {
                    ConversionService.iniciar(ctx)
                    pestana = 1
                }
            },
        )
    }
}

@Composable
private fun PantallaPermiso() {
    val ctx = LocalContext.current
    val lanzarClasico = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        MainActivity.permiso.value = Almacen.tienePermiso(ctx)
    }
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("🎬", fontSize = 56.sp)
        Spacer(Modifier.height(16.dp))
        Text("Conversor de vídeo", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Text(
            "Para leer tus vídeos y guardar la versión convertida al lado del original, la app necesita " +
                "acceso a los archivos del móvil (también a los de un pendrive o tarjeta SD).",
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Nada sale del móvil: la conversión se hace aquí mismo, sin internet.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = {
            if (Build.VERSION.SDK_INT >= 30) {
                val abierto = Almacen.intentsPermiso(ctx).any { intent ->
                    try {
                        ctx.startActivity(intent)
                        true
                    } catch (_: ActivityNotFoundException) {
                        false
                    }
                }
                if (!abierto) Toast.makeText(ctx, "Abre Ajustes → Apps → Conversor → Permisos → Archivos", Toast.LENGTH_LONG).show()
            } else {
                lanzarClasico.launch(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE))
            }
        }) { Text("Dar acceso a los archivos") }
        if (Build.VERSION.SDK_INT >= 30) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Se abrirá Ajustes: activa \"Permitir acceso para gestionar todos los archivos\" y vuelve atrás.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Registro(estadoMotor: EstadoMotor) {
    val ctx = LocalContext.current
    val log by Motor.log.collectAsState()
    val portapapeles = LocalClipboardManager.current
    val estadoLista = rememberLazyListState()
    LaunchedEffect(log.size) { if (log.isNotEmpty()) estadoLista.scrollToItem(log.lastIndex) }

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Text(
            when (estadoMotor) {
                EstadoMotor.Iniciando -> "Preparando ffmpeg…"
                is EstadoMotor.Listo -> estadoMotor.version
                is EstadoMotor.Fallo -> "ffmpeg no disponible"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 6.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                portapapeles.setText(AnnotatedString(log.joinToString("\n")))
                Toast.makeText(ctx, "Registro copiado", Toast.LENGTH_SHORT).show()
            }) { Text("Copiar") }
            OutlinedButton(onClick = { Motor.limpiarLog() }) { Text("Borrar") }
        }
        LazyColumn(state = estadoLista, modifier = Modifier.fillMaxSize().padding(top = 8.dp)) {
            items(log) { linea ->
                Text(linea, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp)
            }
        }
    }
}

/** Abre un fichero convertido con el reproductor del móvil. */
fun abrirFichero(ctx: Context, ruta: String, compartir: Boolean = false) {
    val f = java.io.File(ruta)
    val uri = try {
        androidx.core.content.FileProvider.getUriForFile(ctx, "${ctx.packageName}.archivos", f)
    } catch (e: Exception) {
        Toast.makeText(ctx, "No se puede abrir: ${e.message}", Toast.LENGTH_LONG).show()
        return
    }
    val mime = when (f.extension.lowercase()) {
        "mp4" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "mp3" -> "audio/mpeg"
        else -> "video/*"
    }
    val intent = if (compartir) {
        Intent.createChooser(
            Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            "Compartir",
        )
    } else {
        Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    try {
        ctx.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(ctx, "No hay ninguna app para abrir este fichero", Toast.LENGTH_SHORT).show()
    }
}
