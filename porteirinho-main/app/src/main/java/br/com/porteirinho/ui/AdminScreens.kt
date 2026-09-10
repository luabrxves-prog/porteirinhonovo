package br.com.porteirinho.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import br.com.porteirinho.data.local.CheckpointEntity
import br.com.porteirinho.data.local.PatrolScheduleEntity
import br.com.porteirinho.data.local.UserEntity
import br.com.porteirinho.data.local.UserRole
import br.com.porteirinho.sync.AdminRemoteClient
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import java.io.File

@Composable
fun AdminPointsScreen(
    checkpoints: List<CheckpointEntity>,
    busy: Boolean,
    onAdd: (String) -> Unit,
    onQr: (String) -> Unit,
    onBack: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    AdminListScreen("Pontos de ronda", onBack) {
        item {
            Text("Os 15 pontos padrão já ficam cadastrados. Você pode adicionar pontos extras quando necessário.")
        }
        item {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(100) },
                label = { Text("Novo ponto extra") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { onAdd(name); name = "" },
                enabled = name.trim().length >= 2 && !busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Adicionar ponto") }
        }
        items(checkpoints, key = { it.id }) { point ->
            Card(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(point.name, fontWeight = FontWeight.SemiBold)
                        Text(if (point.fixed) "Ponto fixo" else "Ponto extra", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { onQr(point.id) }) { Text("Ver QR") }
                }
            }
        }
    }
}

@Composable
fun AdminQrScreen(
    checkpoint: CheckpointEntity?,
    qr: AdminRemoteClient.QrPayload?,
    busy: Boolean,
    onReplace: () -> Unit,
    onBack: () -> Unit,
) {
    var confirmReplace by remember { mutableStateOf(false) }
    if (confirmReplace) {
        AlertDialog(
            onDismissRequest = { confirmReplace = false },
            title = { Text("Substituir QR Code?") },
            text = { Text("Tem certeza que deseja substituir este QR Code? O QR atual irá parar de funcionar.") },
            confirmButton = {
                Button(onClick = { confirmReplace = false; onReplace() }) { Text("Substituir") }
            },
            dismissButton = { TextButton(onClick = { confirmReplace = false }) { Text("Cancelar") } },
        )
    }

    AdminListScreen(checkpoint?.name ?: "QR Code", onBack) {
        item {
            if (qr == null) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        if (busy) CircularProgressIndicator() else Text("QR Code indisponível no momento.")
                    }
                }
            } else {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        QrImage(qr.rawPayload)
                        Spacer(Modifier.height(12.dp))
                        Text("QR versão ${qr.version}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Use este código no ponto ${checkpoint?.name.orEmpty()}.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        item {
            OutlinedButton(
                onClick = { confirmReplace = true },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Substituir QR Code") }
        }
    }
}

@Composable
fun AdminSchedulesScreen(
    schedules: List<PatrolScheduleEntity>,
    busy: Boolean,
    onSave: (String, String, Int) -> Unit,
    onBack: () -> Unit,
) {
    AdminListScreen("4 rondas", onBack) {
        item { Text("Existem sempre quatro rondas. O administrador pode alterar somente o nome e o horário.") }
        items(schedules.filter { it.fixedSlot in 1..4 }.sortedBy { it.fixedSlot }, key = { it.id }) { schedule ->
            ScheduleEditor(schedule, busy, onSave)
        }
    }
}

@Composable
private fun ScheduleEditor(
    schedule: PatrolScheduleEntity,
    busy: Boolean,
    onSave: (String, String, Int) -> Unit,
) {
    var name by remember(schedule.id, schedule.name) { mutableStateOf(schedule.name) }
    var time by remember(schedule.id, schedule.startMinuteOfDay) {
        mutableStateOf("%02d:%02d".format(schedule.startMinuteOfDay / 60, schedule.startMinuteOfDay % 60))
    }
    val minute = parseMinuteOfDay(time)

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Ronda ${schedule.fixedSlot}", fontWeight = FontWeight.Bold)
            OutlinedTextField(name, { name = it.take(80) }, label = { Text("Nome") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                time,
                { value -> time = value.filter { it.isDigit() || it == ':' }.take(5) },
                label = { Text("Horário (HH:mm)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "Tempo previsto: ${schedule.targetDurationMinutes} min • tolerância -${schedule.startToleranceMinutes}/+${schedule.endToleranceMinutes} min",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = { minute?.let { onSave(schedule.id, name, it) } },
                enabled = !busy && name.isNotBlank() && minute != null,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Salvar") }
        }
    }
}

@Composable
fun AdminGatekeepersScreen(
    users: List<UserEntity>,
    generatedPin: String?,
    busy: Boolean,
    onCreate: (String) -> Unit,
    onDismissPin: () -> Unit,
    onBack: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    AdminListScreen("Porteiros", onBack) {
        if (generatedPin != null) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("PIN temporário", fontWeight = FontWeight.Bold)
                        Text(generatedPin, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                        Text("Entregue este PIN ao porteiro. No primeiro acesso ele deverá criar a própria senha.")
                        TextButton(onClick = onDismissPin) { Text("Entendi") }
                    }
                }
            }
        }
        item {
            OutlinedTextField(name, { name = it.take(100) }, label = { Text("Nome do porteiro") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { onCreate(name); name = "" },
                enabled = name.trim().length >= 2 && !busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Cadastrar porteiro") }
        }
        items(users.filter { it.role == UserRole.GATEKEEPER }, key = { it.id }) { user ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(user.displayName, fontWeight = FontWeight.SemiBold)
                    Text(if (user.mustChangePin) "Aguardando primeiro acesso" else "Ativo", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
fun AdminReportsScreen(
    reportFilePath: String?,
    busy: Boolean,
    onGenerate: (Int) -> Unit,
    onBack: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    AdminListScreen("Relatórios", onBack) {
        item { Text("Escolha o período. O Excel usa os dados sincronizados do servidor central.") }
        item { ReportButton(30, busy, onGenerate) }
        item { ReportButton(90, busy, onGenerate) }
        item { ReportButton(120, busy, onGenerate) }
        if (reportFilePath != null) {
            item {
                Button(
                    onClick = { shareReport(context, reportFilePath) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Compartilhar Excel") }
            }
        }
    }
}

@Composable
private fun ReportButton(days: Int, busy: Boolean, onGenerate: (Int) -> Unit) {
    OutlinedButton(
        onClick = { onGenerate(days) },
        enabled = !busy,
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Gerar Excel — $days dias") }
}

@Composable
private fun QrImage(payload: String) {
    val bitmap = remember(payload) { generateQrBitmap(payload, 720) }
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface) {
        Image(bitmap.asImageBitmap(), contentDescription = "QR Code", modifier = Modifier.size(260.dp).padding(12.dp))
    }
}

private fun generateQrBitmap(value: String, size: Int): Bitmap {
    val matrix = MultiFormatWriter().encode(value, BarcodeFormat.QR_CODE, size, size)
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    for (x in 0 until size) {
        for (y in 0 until size) {
            bitmap.setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
        }
    }
    return bitmap
}

private fun parseMinuteOfDay(value: String): Int? {
    val parts = value.split(':')
    if (parts.size != 2) return null
    val hour = parts[0].toIntOrNull() ?: return null
    val minute = parts[1].toIntOrNull() ?: return null
    if (hour !in 0..23 || minute !in 0..59) return null
    return hour * 60 + minute
}

private fun shareReport(context: Context, filePath: String) {
    val file = File(filePath)
    if (!file.exists()) return
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Compartilhar relatório"))
}

@Composable
private fun AdminListScreen(
    title: String,
    onBack: () -> Unit,
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit,
) {
    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("Voltar") }
                Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            }
        }
        content()
    }
}
