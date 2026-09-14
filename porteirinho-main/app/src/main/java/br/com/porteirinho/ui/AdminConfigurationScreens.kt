package br.com.porteirinho.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import br.com.porteirinho.domain.AdminBlock
import br.com.porteirinho.domain.AdminPoint
import br.com.porteirinho.domain.AdminRound
import br.com.porteirinho.domain.FixedPatrolStructure
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

@Composable
fun AdminPointsScreen(
    blocks: List<AdminBlock>,
    busy: Boolean,
    onAddExtra: (String, String) -> Unit,
    onReplaceQr: (String) -> Unit,
    onBack: () -> Unit,
) {
    var qrPoint by remember { mutableStateOf<AdminPoint?>(null) }
    var addToBlock by remember { mutableStateOf<AdminBlock?>(null) }
    var extraName by remember { mutableStateOf("") }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { HeaderRow("Pontos de Ronda", onBack) }
        item {
            Text(
                "2 blocos • 30 pontos fixos. Pontos fixos não podem ser apagados; o QR pode ser substituído sem alterar o ponto.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        blocks.forEach { block ->
            item(key = "title-${block.id}") {
                Column {
                    Text(block.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("${block.points.count { it.fixed }} pontos fixos", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(block.points, key = { it.id }) { point ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    shape = RoundedCornerShape(18.dp),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(point.name, fontWeight = FontWeight.SemiBold)
                                Text(
                                    if (point.fixed) "Ponto fixo • QR v${point.qrVersion ?: 1}" else "Ponto extra • QR v${point.qrVersion ?: 1}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(if (point.fixed) "FIXO" else "EXTRA", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedButton(
                                onClick = { qrPoint = point },
                                enabled = point.qrRawValue != null,
                                modifier = Modifier.weight(1f),
                            ) { Text("Ver QR Code") }
                            OutlinedButton(
                                onClick = { onReplaceQr(point.id) },
                                enabled = !busy,
                                modifier = Modifier.weight(1f),
                            ) { Text("Substituir QR") }
                        }
                    }
                }
            }
            item(key = "extra-${block.id}") {
                Button(
                    onClick = {
                        addToBlock = block
                        extraName = ""
                    },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Adicionar ponto extra em ${block.name}") }
            }
        }
        item { Spacer(Modifier.height(12.dp)) }
    }

    qrPoint?.let { point ->
        val value = point.qrRawValue
        AlertDialog(
            onDismissRequest = { qrPoint = null },
            title = { Text(point.name) },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (value != null) {
                        val bitmap = remember(value) { createQrBitmap(value) }
                        Image(bitmap.asImageBitmap(), contentDescription = "QR Code de ${point.name}", modifier = Modifier.size(260.dp))
                        Spacer(Modifier.height(12.dp))
                        Text("QR ativo • versão ${point.qrVersion ?: 1}", textAlign = TextAlign.Center)
                    } else {
                        Text("QR Code ainda não disponível.")
                    }
                }
            },
            confirmButton = { TextButton(onClick = { qrPoint = null }) { Text("Fechar") } },
        )
    }

    addToBlock?.let { block ->
        AlertDialog(
            onDismissRequest = { addToBlock = null },
            title = { Text("Adicionar ponto extra") },
            text = {
                Column {
                    Text(block.name)
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = extraName,
                        onValueChange = { extraName = it },
                        label = { Text("Nome do ponto") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onAddExtra(block.id, extraName)
                        addToBlock = null
                    },
                    enabled = extraName.isNotBlank() && !busy,
                ) { Text("Adicionar") }
            },
            dismissButton = { TextButton(onClick = { addToBlock = null }) { Text("Cancelar") } },
        )
    }
}

@Composable
fun AdminRoundsScreen(
    rounds: List<AdminRound>,
    busy: Boolean,
    onSave: (String, String, Int) -> Unit,
    onBack: () -> Unit,
) {
    var editing by remember { mutableStateOf<AdminRound?>(null) }
    var editName by remember { mutableStateOf("") }
    var editTime by remember { mutableStateOf("") }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { HeaderRow("Rondas Fixas", onBack) }
        item {
            Text(
                "As 4 rondas são obrigatórias. O administrador pode alterar somente nome e horário de início; duração e regras internas permanecem controladas pelo sistema.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        items(rounds, key = { it.id }) { round ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = RoundedCornerShape(18.dp),
            ) {
                Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(round.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(
                            "Início ${FixedPatrolStructure.formatMinuteOfDay(round.startMinuteOfDay)} • duração definida pelo sistema",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    OutlinedButton(
                        onClick = {
                            editing = round
                            editName = round.name
                            editTime = FixedPatrolStructure.formatMinuteOfDay(round.startMinuteOfDay)
                        },
                        enabled = !busy,
                    ) { Text("Editar") }
                }
            }
        }
        if (rounds.size < 4) {
            item {
                Text(
                    "Carregando a estrutura obrigatória de rondas...",
                    modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(14.dp)).padding(14.dp),
                )
            }
        }
    }

    editing?.let { round ->
        val parsedTime = parseTime(editTime)
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("Editar ronda") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = editName,
                        onValueChange = { editName = it },
                        label = { Text("Nome da ronda") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = editTime,
                        onValueChange = { editTime = it.take(5) },
                        label = { Text("Horário de início (HH:mm)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("Não é possível excluir esta ronda nem criar uma quinta ronda fixa.", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onSave(round.id, editName, parsedTime!!)
                        editing = null
                    },
                    enabled = editName.isNotBlank() && parsedTime != null && !busy,
                ) { Text("Salvar") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancelar") } },
        )
    }
}

private fun parseTime(value: String): Int? {
    val parts = value.split(':')
    if (parts.size != 2) return null
    val hour = parts[0].toIntOrNull() ?: return null
    val minute = parts[1].toIntOrNull() ?: return null
    if (hour !in 0..23 || minute !in 0..59) return null
    return hour * 60 + minute
}

private fun createQrBitmap(value: String, size: Int = 768): Bitmap {
    val matrix = QRCodeWriter().encode(value, BarcodeFormat.QR_CODE, size, size)
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    for (y in 0 until size) {
        for (x in 0 until size) {
            bitmap.setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
        }
    }
    return bitmap
}
