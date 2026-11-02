@file:OptIn(ExperimentalMaterial3Api::class)

package app.conversor.video

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.io.File
import java.util.Locale

@Composable
fun ColaUi(tareas: List<Tarea>) {
    if (tareas.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
            Text(
                "La cola está vacía.\nElige vídeos en la pestaña Archivos.",
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    val hayEnMarcha = tareas.any { !it.estado.terminada }
    val hayTerminadas = tareas.any { it.estado.terminada }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (hayEnMarcha) OutlinedButton(onClick = {
                Motor.log("Cancelada toda la cola")
                Motor.cancelarPendientes()
                Motor.cancelarActual()
            }) { Text("Cancelar todo") }
            if (hayTerminadas) OutlinedButton(onClick = { Motor.quitarTerminadas() }) { Text("Limpiar terminadas") }
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(tareas, key = { it.id }) { t -> TarjetaTarea(t) }
        }
    }
}

@Composable
private fun TarjetaTarea(t: Tarea) {
    val ctx = LocalContext.current
    var abierta by rememberSaveable(t.id) { mutableStateOf(false) }
    Card(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clickable { abierta = !abierta },
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    t.nombre,
                    Modifier.weight(1f),
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                AssistChip(onClick = { abierta = !abierta }, label = { Text(t.estado.texto) })
            }
            Text(
                t.opciones.modo.titulo + if (t.opciones.modo == Modo.MP4 && t.opciones.resolucion != Resolucion.ORIGINAL) " · ${t.opciones.resolucion.titulo}" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))

            when (t.estado) {
                Estado.ANALIZANDO -> {
                    Text(t.paso, style = MaterialTheme.typography.bodySmall)
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 4.dp))
                }
                Estado.CONVIRTIENDO -> {
                    Text(t.paso, style = MaterialTheme.typography.bodySmall)
                    if (t.progreso >= 0f) {
                        LinearProgressIndicator(progress = { t.progreso }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 4.dp))
                    }
                    Text(
                        listOfNotNull(
                            if (t.progreso >= 0f) "${(t.progreso * 100).toInt()} %" else null,
                            if (t.velocidad > 0) String.format(Locale("es", "ES"), "%.1fx", t.velocidad) else null,
                            if (t.etaSeg >= 0) "quedan ${fmtTiempo(t.etaSeg)}" else null,
                            if (t.tamanoSalida > 0) fmtBytes(t.tamanoSalida) else null,
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Estado.HECHO -> {
                    Text("→ ${File(t.salida).name} · ${fmtBytes(t.tamanoSalida)} · en ${fmtTiempo(t.duracionSeg)}")
                    Text(
                        "Guardado en ${File(t.salida).parent}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Estado.YA_COMPATIBLE -> Text(t.mensaje)
                Estado.ERROR -> Text(t.mensaje, color = MaterialTheme.colorScheme.error)
                Estado.EN_COLA, Estado.CANCELADA -> {}
            }

            if (abierta && (t.pistas.isNotEmpty() || t.decisiones.isNotEmpty())) {
                Spacer(Modifier.height(8.dp))
                if (t.pistas.isNotEmpty()) {
                    Text("Pistas del original", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
                    t.pistas.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                }
                if (t.decisiones.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text("Qué se hace", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
                    t.decisiones.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                }
                Text(t.ruta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                when (t.estado) {
                    Estado.ANALIZANDO, Estado.CONVIRTIENDO -> TextButton(onClick = { Motor.cancelarActual() }) { Text("Cancelar") }
                    Estado.EN_COLA -> TextButton(onClick = { Motor.quitar(t.id) }) { Text("Quitar") }
                    Estado.HECHO -> {
                        TextButton(onClick = { abrirFichero(ctx, t.salida) }) { Text("Reproducir") }
                        TextButton(onClick = { abrirFichero(ctx, t.salida, compartir = true) }) { Text("Compartir") }
                    }
                    Estado.ERROR, Estado.CANCELADA -> {
                        TextButton(onClick = {
                            Motor.reintentar(t.id)
                            ConversionService.iniciar(ctx)
                        }) { Text("Reintentar") }
                        TextButton(onClick = { Motor.quitar(t.id) }) { Text("Quitar") }
                    }
                    Estado.YA_COMPATIBLE -> TextButton(onClick = {
                        Motor.quitar(t.id)
                        Motor.encolar(listOf(t.ruta), t.opciones.copy(forzar = true))
                        ConversionService.iniciar(ctx)
                    }) { Text("Convertir igualmente") }
                }
                if (!abierta && t.pistas.isNotEmpty()) TextButton(onClick = { abierta = true }) { Text("Detalles") }
            }
        }
    }
}
