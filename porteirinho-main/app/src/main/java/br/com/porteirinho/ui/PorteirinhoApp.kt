package br.com.porteirinho.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import br.com.porteirinho.data.local.AlertEntity
import br.com.porteirinho.data.local.UserEntity
import br.com.porteirinho.data.local.UserRole
import br.com.porteirinho.domain.ActivePatrolSnapshot
import br.com.porteirinho.domain.AvailablePatrol
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

@Composable
fun PorteirinhoApp(viewModel: AppViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val users by viewModel.users.collectAsStateWithLifecycle()
    val checkpoints by viewModel.checkpoints.collectAsStateWithLifecycle()
    val schedules by viewModel.schedules.collectAsStateWithLifecycle()
    val alerts by viewModel.alerts.collectAsStateWithLifecycle()
    val pendingSync by viewModel.pendingSync.collectAsStateWithLifecycle()
    val permanentFailures by viewModel.permanentFailures.collectAsStateWithLifecycle()
    val executions by viewModel.executionCount.collectAsStateWithLifecycle()
    val problems by viewModel.problemExecutionCount.collectAsStateWithLifecycle()
    val unresolved by viewModel.unresolvedAlerts.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    state.message?.let { message ->
        LaunchedEffect(message) {
            snackbar.showSnackbar(message)
            viewModel.consumeMessage()
        }
    }
    BackHandler(enabled = state.screen != AppScreen.AreaChoice, onBack = viewModel::back)

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (val screen = state.screen) {
                AppScreen.AreaChoice -> AreaChoiceScreen(viewModel::chooseArea)
                AppScreen.AdminLogin -> AdminLoginScreen(state.busy, viewModel::loginAdmin, viewModel::back)
                is AppScreen.ProfileChoice -> ProfileChoiceScreen(screen.role, users.filter { it.role == screen.role }, viewModel::chooseProfile, viewModel::back)
                is AppScreen.PinLogin -> PinLoginScreen(users.firstOrNull { it.id == screen.userId }, state.busy, { viewModel.login(screen.userId, it) }, viewModel::back)
                is AppScreen.ChangePin -> ChangePinScreen(state.busy, { viewModel.changePin(screen.userId, it) }, viewModel::back)
                AppScreen.GatekeeperHome -> GatekeeperHomeScreen(state.authenticatedUser, state.shiftActive, state.availablePatrols, state.busy, pendingSync, viewModel::startShift, viewModel::finishShift, viewModel::startPatrol, viewModel::back)
                AppScreen.Patrol -> PatrolScreen(
                    patrol = state.activePatrol,
                    busy = state.busy,
                    observationCheckpointName = state.observationCheckpointName,
                    onScan = viewModel::openScanner,
                    onFinish = viewModel::finishPatrol,
                    onSaveObservation = viewModel::saveObservation,
                    onDismissObservation = viewModel::dismissObservation,
                )
                AppScreen.Scanner -> QrScannerView(viewModel::processScan, viewModel::cancelScanner)
                AppScreen.AdminDashboard -> AdminDashboardScreen(
                    executionCount = executions,
                    problemCount = problems,
                    unresolvedAlerts = unresolved,
                    pendingSync = pendingSync,
                    permanentFailures = permanentFailures,
                    onAlerts = viewModel::openAdminAlerts,
                    onPoints = viewModel::openAdminPoints,
                    onSchedules = viewModel::openAdminSchedules,
                    onGatekeepers = viewModel::openAdminGatekeepers,
                    onReports = viewModel::openAdminReports,
                    onExit = viewModel::logoutAdmin,
                )
                AppScreen.AdminAlerts -> AlertsScreen(alerts, viewModel::resolveAlert, viewModel::back)
                AppScreen.AdminPoints -> AdminPointsScreen(checkpoints, state.busy, viewModel::addAdminPoint, viewModel::openAdminQr, viewModel::back)
                is AppScreen.AdminQr -> AdminQrScreen(
                    checkpoint = checkpoints.firstOrNull { it.id == screen.checkpointId },
                    qr = state.adminQrPayload,
                    busy = state.busy,
                    onReplace = { viewModel.replaceAdminQr(screen.checkpointId) },
                    onBack = viewModel::back,
                )
                AppScreen.AdminSchedules -> AdminSchedulesScreen(schedules, state.busy, viewModel::updateAdminSchedule, viewModel::back)
                AppScreen.AdminGatekeepers -> AdminGatekeepersScreen(users, state.generatedGatekeeperPin, state.busy, viewModel::createAdminGatekeeper, viewModel::clearGeneratedGatekeeperPin, viewModel::back)
                AppScreen.AdminReports -> AdminReportsScreen(state.reportFilePath, state.busy, viewModel::downloadAdminReport, viewModel::back)
            }
            if (state.busy) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .15f)), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
        }
    }
}

@Composable
private fun AreaChoiceScreen(onChoose: (String) -> Unit) {
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Porteirinho", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        Text("Rondas comprovadas, mesmo sem internet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        AccessCard("PORTARIA", "Iniciar turno e executar as rondas") { onChoose(UserRole.GATEKEEPER) }
        AccessCard("ADMINISTRAÇÃO", "Acompanhar operação, alertas e cadastros") { onChoose(UserRole.ADMIN) }
        Spacer(Modifier.weight(1f))
        Text("As leituras ficam salvas no aparelho e sincronizam automaticamente quando a internet voltar.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun AccessCard(title: String, subtitle: String, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(20.dp)) {
            Text(title, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun AdminLoginScreen(busy: Boolean, onLogin: (String, String) -> Unit, onBack: () -> Unit) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    Screen("Administração", onBack) {
        item {
            Text("Entre com seu acesso administrativo.", style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = email,
                onValueChange = { email = it.trim().take(160) },
                label = { Text("E-mail") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = password,
                onValueChange = { password = it.take(128) },
                label = { Text("Senha") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { onLogin(email, password) },
                enabled = email.contains('@') && password.isNotBlank() && !busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Entrar") }
        }
    }
}

@Composable
private fun ProfileChoiceScreen(role: String, users: List<UserEntity>, onProfile: (String) -> Unit, onBack: () -> Unit) {
    Screen(title = if (role == UserRole.ADMIN) "Administração" else "Quem está na portaria?", onBack = onBack) {
        if (users.isEmpty()) item { Text("Nenhum perfil ativo.") }
        items(users, key = { it.id }) { user ->
            Card(Modifier.fillMaxWidth().clickable { onProfile(user.id) }) {
                Column(Modifier.padding(18.dp)) {
                    Text(user.displayName, fontWeight = FontWeight.SemiBold)
                    Text(if (user.mustChangePin) "Primeiro acesso pendente" else if (user.role == UserRole.ADMIN) "Administrador" else "Porteiro", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun PinLoginScreen(user: UserEntity?, busy: Boolean, onLogin: (String) -> Unit, onBack: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    Screen("Acesso seguro", onBack) {
        item {
            user?.let { Text(it.displayName, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
            Spacer(Modifier.height(20.dp))
            OutlinedTextField(pin, { pin = it.filter(Char::isDigit).take(6) }, label = { Text("PIN") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
            Button({ onLogin(pin) }, enabled = pin.length == 6 && !busy, modifier = Modifier.fillMaxWidth()) { Text("Entrar") }
        }
    }
}

@Composable
private fun ChangePinScreen(busy: Boolean, onSave: (String) -> Unit, onBack: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    Screen("Crie seu PIN", onBack) {
        item {
            Text("Este é seu primeiro acesso. Defina um PIN pessoal de 6 números para os próximos acessos.")
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(pin, { pin = it.filter(Char::isDigit).take(6) }, label = { Text("Novo PIN") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(confirm, { confirm = it.filter(Char::isDigit).take(6) }, label = { Text("Confirmar PIN") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
            Button({ onSave(pin) }, enabled = pin.length == 6 && pin == confirm && !busy, modifier = Modifier.fillMaxWidth()) { Text("Salvar PIN") }
        }
    }
}

@Composable
private fun GatekeeperHomeScreen(user: UserEntity?, shiftActive: Boolean, patrols: List<AvailablePatrol>, busy: Boolean, pendingSync: Int, onStartShift: () -> Unit, onFinishShift: () -> Unit, onStartPatrol: (String) -> Unit, onExit: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Text("Olá, ${user?.displayName?.substringBefore(' ') ?: "Porteiro"}", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(if (pendingSync == 0) "Tudo sincronizado" else "$pendingSync eventos aguardando internet", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!shiftActive) {
            item { Button(onStartShift, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Iniciar turno") } }
        } else {
            item { Text("Rondas", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
            items(patrols, key = { it.schedule.id }) { patrol -> PatrolCard(patrol, onStartPatrol) }
            item { OutlinedButton(onFinishShift, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Encerrar turno") } }
        }
        item { TextButton(onExit) { Text("Sair") } }
    }
}

@Composable
private fun PatrolCard(patrol: AvailablePatrol, onStart: (String) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp)) {
            Text(patrol.schedule.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("${patrol.windowLabel} • ${patrol.pointCount} pontos")
            Text(patrol.durationLabel, style = MaterialTheme.typography.bodySmall)
            Text(patrol.toleranceLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            Button({ onStart(patrol.schedule.id) }, enabled = patrol.availableNow, modifier = Modifier.fillMaxWidth()) { Text(if (patrol.availableNow) "Iniciar ronda" else "Fora do horário") }
        }
    }
}

@Composable
private fun PatrolScreen(patrol: ActivePatrolSnapshot?, busy: Boolean, observationCheckpointName: String?, onScan: () -> Unit, onFinish: () -> Unit, onSaveObservation: (String) -> Unit, onDismissObservation: () -> Unit) {
    if (patrol == null) return
    var elapsed by remember { mutableLongStateOf(0L) }
    var observation by remember(observationCheckpointName) { mutableStateOf("") }
    LaunchedEffect(patrol.executionId) {
        while (true) { elapsed = System.currentTimeMillis() - patrol.startedAtEpochMillis; delay(1000) }
    }
    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text(patrol.scheduleName, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("Tempo: %02d:%02d".format(elapsed / 60_000, (elapsed / 1_000) % 60))
            LinearProgressIndicator(progress = { patrol.progress }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
            Text("${patrol.completedPoints} de ${patrol.totalPoints} pontos concluídos")
        }
        items(patrol.checkpointNames, key = { it.first }) { (id, name) ->
            val done = id in patrol.visitedPointIds
            Surface(shape = RoundedCornerShape(12.dp), color = if (done) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                Text((if (done) "✓ " else "○ ") + name, Modifier.padding(16.dp), fontWeight = if (done) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
        if (observationCheckpointName != null) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Adicionar observação em $observationCheckpointName?", fontWeight = FontWeight.Bold)
                        Text("Opcional. Use quando encontrar algo que o administrador deva verificar.", style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(observation, { observation = it }, label = { Text("Observação") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onDismissObservation) { Text("Agora não") }
                            Button({ onSaveObservation(observation) }, enabled = observation.isNotBlank()) { Text("Registrar") }
                        }
                    }
                }
            }
        }
        item { Button(onScan, enabled = !busy && !patrol.canFinish, modifier = Modifier.fillMaxWidth()) { Text("Escanear QR Code") } }
        item {
            Button(onFinish, enabled = !busy && patrol.canFinish, modifier = Modifier.fillMaxWidth()) { Text("Finalizar ronda") }
            if (!patrol.canFinish) Text("A ronda só poderá ser finalizada depois que todos os pontos forem concluídos.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
        }
    }
}

@Composable
private fun AdminDashboardScreen(
    executionCount: Int,
    problemCount: Int,
    unresolvedAlerts: Int,
    pendingSync: Int,
    permanentFailures: Int,
    onAlerts: () -> Unit,
    onPoints: () -> Unit,
    onSchedules: () -> Unit,
    onGatekeepers: () -> Unit,
    onReports: () -> Unit,
    onExit: () -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Administração", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Metric("$executionCount", "rondas", Modifier.weight(1f))
                Metric("$problemCount", "com alerta", Modifier.weight(1f))
                Metric("$pendingSync", "pendentes", Modifier.weight(1f))
            }
        }
        item { AdminCard("Central de alertas", "$unresolvedAlerts não resolvidos", onAlerts) }
        item { AdminCard("Pontos de ronda", "Pontos fixos, extras e QR Codes", onPoints) }
        item { AdminCard("4 rondas fixas", "Alterar nome e horário", onSchedules) }
        item { AdminCard("Porteiros", "Cadastrar e acompanhar primeiro acesso", onGatekeepers) }
        item { AdminCard("Relatórios", "Excel de 30, 90 ou 120 dias", onReports) }
        if (permanentFailures > 0) item { Text("$permanentFailures falhas de sincronização precisam de atenção.", color = MaterialTheme.colorScheme.error) }
        item { TextButton(onExit) { Text("Sair da administração") } }
    }
}

@Composable
private fun Metric(value: String, label: String, modifier: Modifier = Modifier) {
    Card(modifier) { Column(Modifier.padding(14.dp)) { Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold); Text(label, style = MaterialTheme.typography.bodySmall) } }
}

@Composable
private fun AdminCard(title: String, subtitle: String, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) { Column(Modifier.padding(18.dp)) { Text(title, fontWeight = FontWeight.Bold); Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
}

@Composable
private fun AlertsScreen(alerts: List<AlertEntity>, onResolve: (String) -> Unit, onBack: () -> Unit) {
    Screen("Central de alertas", onBack) {
        if (alerts.isEmpty()) item { Text("Nenhum alerta registrado.") }
        items(alerts, key = { it.id }) { alert ->
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = if (alert.resolved) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.errorContainer)) {
                Column(Modifier.padding(16.dp)) {
                    Text(alert.type.replace('_', ' '), fontWeight = FontWeight.Bold)
                    Text(alert.description)
                    Text(DateFormat.getDateTimeInstance().format(Date(alert.createdAtEpochMillis)), style = MaterialTheme.typography.bodySmall)
                    if (!alert.resolved) TextButton({ onResolve(alert.id) }) { Text("Marcar como resolvido") }
                }
            }
        }
    }
}

@Composable
private fun Screen(title: String, onBack: () -> Unit, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Row(verticalAlignment = Alignment.CenterVertically) { TextButton(onBack) { Text("Voltar") }; Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) } }
        content()
    }
}
