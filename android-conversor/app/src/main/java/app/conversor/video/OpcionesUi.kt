@file:OptIn(ExperimentalMaterial3Api::class)

package app.conversor.video

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.io.File

@Composable
fun OpcionesSheet(rutas: List<String>, ajustes: Ajustes, onCerrar: () -> Unit, onConvertir: (Opciones) -> Unit) {
    val estado = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var modo by remember { mutableStateOf(ajustes.modo) }
    var estereo by remember { mutableStateOf(ajustes.estereo) }
    var resolucion by remember { mutableStateOf(ajustes.resolucion) }
    var hardware by remember { mutableStateOf(ajustes.hardware) }
    var hardwareFalla by remember { mutableStateOf(ajustes.hardwareFalla) }
    var forzar by remember { mutableStateOf(ajustes.forzar) }

    ModalBottomSheet(onDismissRequest = onCerrar, sheetState = estado) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
        ) {
            Text(
                if (rutas.size == 1) File(rutas[0]).name else "${rutas.size} vídeos",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(12.dp))

            Modo.entries.forEach { m ->
                val elegido = m == modo
                Card(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { modo = m },
                    colors = if (elegido) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                    else CardDefaults.cardColors(),
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
                        RadioButton(selected = elegido, onClick = { modo = m })
                        Column(Modifier.padding(top = 10.dp)) {
                            Text(m.titulo, fontWeight = FontWeight.SemiBold)
                            Text(m.explicacion, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            if (modo != Modo.MP3) {
                FilaInterruptor(
                    "Pasar el 5.1 a estéreo",
                    "Recomendado: los altavoces del proyector o la tele son estéreo y así las voces se oyen bien. " +
                        "Desactívalo si lo vas a escuchar en un equipo 5.1.",
                    estereo,
                ) { estereo = it }
            }

            if (modo == Modo.MP4) {
                Text("Resolución máxima", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Resolucion.entries.forEach { r ->
                        FilterChip(selected = r == resolucion, onClick = { resolucion = r }, label = { Text(r.titulo) })
                    }
                }
                Text(
                    "Nunca se amplía: un vídeo más pequeño que lo elegido se queda como está.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                if (hardwareFalla) {
                    Text(
                        "El codificador por hardware falló en este móvil, así que se usa el de software (más lento, pero seguro).",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(onClick = {
                        ajustes.hardwareFalla = false
                        hardwareFalla = false
                    }) { Text("Volver a probar el de hardware") }
                } else {
                    FilaInterruptor(
                        "Usar el codificador del móvil",
                        "Mucho más rápido. Si en tu móvil no funciona, se repite solo por software.",
                        hardware,
                    ) { hardware = it }
                }
            }

            FilaInterruptor(
                "Convertir aunque ya sea compatible",
                "Normalmente se salta lo que ya va a sonar y verse bien.",
                forzar,
            ) { forzar = it }

            Spacer(Modifier.height(8.dp))
            Text(
                "El resultado se guarda en una carpeta \"Convertidos\" junto a cada vídeo. El original no se toca.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    ajustes.modo = modo
                    ajustes.estereo = estereo
                    ajustes.resolucion = resolucion
                    ajustes.hardware = hardware
                    ajustes.forzar = forzar
                    onConvertir(ajustes.opciones())
                },
            ) { Text(if (rutas.size == 1) "Convertir" else "Convertir ${rutas.size} vídeos") }
        }
    }
}

@Composable
private fun FilaInterruptor(titulo: String, detalle: String, valor: Boolean, onCambio: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onCambio(!valor) }.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(titulo, fontWeight = FontWeight.SemiBold)
            Text(detalle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = valor, onCheckedChange = onCambio)
    }
}
