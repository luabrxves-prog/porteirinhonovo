package br.com.porteirinho.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
    val alerts by viewModel.alerts.collectAsStateWithLifecycle()
    val pendingSync by viewModel.pendingSync.collectAsStateWithLifecycle()
    val permanentFailures by viewModel.permanentFailures.collectAsStateWithLifecycle()
    val executions by viewModel.executionCount.collectAsStateWithLifecycle()
    val problems by viewModel.problemExecutionCount.collectAsStateWithLifecycle()
    val unresolved by viewModel.unresolvedAlerts.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    state.message?.let { message ->
        LaunchedEffect(message) {
            snackbarHostState.showSnackbar(message)
            viewModel.consumeMessage()
        }
    }
    BackHandler(enabled = state.screen != AppScreen.AreaChoice, onBack = viewModel::back)

    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (val screen = state.screen) {
                AppScreen.AreaChoice -> AreaChoiceScreen(viewModel::chooseArea)
                is AppScreen.ProfileChoice -> ProfileChoiceScreen(
                    role = screen.role,
                    users = users.filter { it.role == screen.role },
                    onProfile = viewModel::chooseProfile,
                    onBack = viewModel::back,
                )
                is AppScreen.PinLogin -> PinLoginScreen(
                    user = users.firstOrNull { it.id == screen.userId },
                    busy = state.busy,
                    onLogin = { viewModel.login(screen.userId, it) },
                    onBack = viewModel::back,
                )
                AppScreen.GatekeeperHome -> GatekeeperHomeScreen(
                    user = state.authenticatedUser,
                    shiftActive = state.shiftActive,
                    patrols = state.availablePatrols,
                    busy = state.busy,
                    pendingSync = pendingSync,
                    onStartShift = viewModel::startShift,
                    onFinishShift = viewModel::finishShift,
                    onStartPatrol = viewModel::startPatrol,
                    onExit = viewModel::back,
                )
                AppScreen.Patrol -> PatrolScreen(
                    patrol = state.activePatrol,
                    busy = state.busy,
                    onScan = viewModel::openScanner,
                    onFinish = viewModel::finishPatrol,
                )
                AppScreen.Scanner -> QrScannerView(viewModel::processScan, viewModel::cancelScanner)
                AppScreen.AdminDashboard -> AdminDashboardScreen(
                    executionCount = executions,
                    problemCount = problems,
                    unresolvedAlerts = unresolved,
                    pendingSync = pendingSync,
                    permanentFailures = permanentFailures,
                    onAlerts = viewModel::openAdminAlerts,
                    onExit = viewModel::back,
                )
                AppScreen.AdminAlerts -> AlertsScreen(alerts, viewModel::resolveAlert, viewModel::back)
            }

            if (state.busy) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
        }
    }
}

@Composable
private fun AreaChoiceScreen(onChoose: (String) -> Unit) {
    Column(
        Modifier.fillMaxSize().navigationBarsPadding(),
    ) {
        Surface(
            color = MaterialTheme.colorScheme.primary,
            shape = RoundedCornerShape(0.dp, 0.dp, 36.dp, 36.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.statusBarsPadding().padding(horizontal = 24.dp, vertical = 34.dp)) {
                Surface(color = Color.White, shape = RoundedCornerShape(18.dp), modifier = Modifier.size(64.dp)) {
                    Box(contentAlignment = Alignment.Center) { Text("P", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold) }
                }
                Spacer(Modifier.height(24.dp))
                Text("Porteirinho", color = Color.White, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
                Text("Rondas comprovadas, mesmo sem internet.", color = Color.White.copy(alpha = 0.88f), style = MaterialTheme.typography.titleMedium)
            }
        }
        Column(Modifier.padding(24.dp)) {
            SectionHeading("Escolha seu acesso", "Cada área mostra apenas o necessário para o perfil.")
            Spacer(Modifier.height(24.dp))
            AreaCard("PORTARIA", "Iniciar turno e executar rondas") { onChoose(UserRole.GATEKEEPER) }
            Spacer(Modifier.height(16.dp))
            AreaCard("ADMINISTRAÇÃO", "Acompanhar operação, alertas e cadastros") { onChoose(UserRole.ADMIN) }
            Spacer(Modifier.height(28.dp))
            Text("Dados operacionais permanecem salvos neste aparelho até a confirmação do servidor.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun AreaCard(title: String, subtitle: String, onClick: () -> Unit) {
    HardShadowCard(Modifier.fillMaxWidth().clickable(onClick = onClick), containerColor = MaterialTheme.colorScheme.surfaceVariant) {
        Row(Modifier.padding(22.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(44.dp)) {
                Box(contentAlignment = Alignment.Center) { Text(if (title == "PORTARIA") "P" else "A", color = Color.White, fontWeight = FontWeight.Bold) }
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(4.dp))
                Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("›", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun ProfileChoiceScreen(role: String, users: List<UserEntity>, onProfile: (String) -> Unit, onBack: () -> Unit) {
    ScreenColumn(title = if (role == UserRole.ADMIN) "Administração" else "Quem está na portaria?", onBack = onBack) {
        if (users.isEmpty()) Text("Nenhum perfil ativo disponível.")
        users.forEach { user ->
            HardShadowCard(Modifier.fillMaxWidth().clickable { onProfile(user.id) }.padding(vertical = 6.dp), containerColor = MaterialTheme.colorScheme.surfaceVariant) {
                Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Initials(user.displayName)
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text(user.displayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(if (user.role == UserRole.ADMIN) "Administrador" else "Porteiro", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun PinLoginScreen(user: UserEntity?, busy: Boolean, onLogin: (String) -> Unit, onBack: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    ScreenColumn(title = "Acesso seguro", onBack = onBack) {
        Spacer(Modifier.height(28.dp))
        user?.let {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Initials(it.displayName, 76.dp) }
            Text(it.displayName, Modifier.fillMaxWidth().padding(top = 12.dp), textAlign = TextAlign.Center, style = MaterialTheme.typography.headlineSmall)
        }
        Spacer(Modifier.height(28.dp))
        OutlinedTextField(
            value = pin,
            onValueChange = { value -> pin = value.filter(Char::isDigit).take(8) },
            label = { Text("PIN pessoal") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))
        BrandButton("Entrar", { onLogin(pin) }, enabled = pin.length >= 4 && !busy, modifier = Modifier.fillMaxWidth())
        Text("Ambiente de demonstração: PIN 1234", Modifier.padding(top = 14.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun GatekeeperHomeScreen(
    user: UserEntity?,
    shiftActive: Boolean,
    patrols: List<AvailablePatrol>,
    busy: Boolean,
    pendingSync: Int,
    onStartShift: () -> Unit,
    onFinishShift: () -> Unit,
    onStartPatrol: (String) -> Unit,
    onExit: () -> Unit,
) {
    val firstName = user?.displayName?.substringBefore(' ') ?: "Porteiro"
    val initials = user?.displayName?.split(' ')?.take(2)?.mapNotNull { it.firstOrNull()?.uppercase() }?.joinToString("") ?: "P"
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            SummaryHero(
                eyebrow = "Resumo do turno",
                title = "Olá, $firstName",
                description = "Acompanhe sua operação na portaria",
                badge = if (shiftActive) "Turno em andamento" else "Aguardando início do turno",
                initials = initials,
                modifier = Modifier.statusBarsPadding(),
            )
        }
        item {
            Column(Modifier.padding(horizontal = 24.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MetricTile(patrols.size.toString(), "rondas disponíveis", "R", Modifier.weight(1f))
                    MetricTile(pendingSync.toString(), "eventos pendentes", "S", Modifier.weight(1f), MaterialTheme.colorScheme.tertiary)
                    MetricTile(if (shiftActive) "ON" else "OFF", "status do turno", "T", Modifier.weight(1f), Color(0xFF058E3F))
                }
            }
        }
        if (!shiftActive) {
            item {
                Column(Modifier.padding(horizontal = 24.dp)) {
                    BrandButton("Iniciar turno", onStartShift, enabled = !busy, modifier = Modifier.fillMaxWidth())
                }
            }
        } else {
            item { SectionHeading("Rondas disponíveis", "Programações liberadas para este horário", Modifier.padding(horizontal = 24.dp)) }
            if (patrols.isEmpty()) item { Text("Nenhuma ronda disponível para este perfil e horário.", Modifier.padding(horizontal = 24.dp)) }
            items(patrols, key = { it.schedule.id }) { patrol ->
                Box(Modifier.padding(horizontal = 24.dp)) { PatrolCard(patrol, onStartPatrol) }
            }
            item {
                OutlinedButton(onClick = onFinishShift, modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)) { Text("Encerrar turno") }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                SyncBadge(pendingSync)
                TextButton(onClick = onExit) { Text("Sair") }
            }
        }
        item { Spacer(Modifier.navigationBarsPadding().height(8.dp)) }
    }
}

@Composable
private fun PatrolCard(patrol: AvailablePatrol, onStart: (String) -> Unit) {
    HardShadowCard(modifier = Modifier.fillMaxWidth(), containerColor = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.padding(18.dp)) {
            Text(patrol.schedule.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("${patrol.windowLabel}  •  ${patrol.pointCount} pontos", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(14.dp))
            BrandButton(if (patrol.availableNow) "Iniciar ronda" else "Fora do horário", { onStart(patrol.schedule.id) }, enabled = patrol.availableNow, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun PatrolScreen(patrol: ActivePatrolSnapshot?, busy: Boolean, onScan: () -> Unit, onFinish: () -> Unit) {
    if (patrol == null) return
    var elapsed by remember { mutableLongStateOf(0L) }
    LaunchedEffect(patrol.executionId) {
        while (true) {
            elapsed = System.currentTimeMillis() - patrol.startedAtEpochMillis
            delay(1_000)
        }
    }
    val minutes = elapsed / 60_000
    val seconds = (elapsed / 1_000) % 60
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            SummaryHero(
                eyebrow = "Ronda em andamento",
                title = patrol.scheduleName,
                description = "Confirme cada ponto pelo QR Code",
                badge = "Tempo  •  %02d:%02d".format(minutes, seconds),
                initials = "R",
                modifier = Modifier.statusBarsPadding(),
            )
        }
        item {
            HardShadowCard(Modifier.fillMaxWidth().padding(horizontal = 24.dp), containerColor = MaterialTheme.colorScheme.surfaceVariant) {
                Column(Modifier.padding(20.dp)) {
                    Text("${patrol.completedPoints} de ${patrol.totalPoints} pontos concluídos", fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(10.dp))
                    LinearProgressIndicator(progress = { patrol.progress }, modifier = Modifier.fillMaxWidth().height(10.dp).clip(CircleShape))
                    Text("${(patrol.progress * 100).toInt()}%", Modifier.fillMaxWidth().padding(top = 8.dp), textAlign = TextAlign.End)
                }
            }
        }
        item { SectionHeading("Pontos da ronda", "Siga a sequência e confirme presencialmente", Modifier.padding(horizontal = 24.dp)) }
        items(patrol.checkpointNames, key = { it.first }) { (id, name) ->
            val done = id in patrol.visitedPointIds
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp).background(if (done) Color(0xFFE4F2E8) else MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp)).padding(16.dp)) {
                Text(if (done) "✓" else "○", color = if (done) Color(0xFF16875A) else MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(12.dp))
                Text(name, fontWeight = if (done) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
        item {
            Column(Modifier.padding(horizontal = 24.dp)) {
                BrandButton("Escanear QR Code", onScan, enabled = !busy && patrol.completedPoints < patrol.totalPoints, modifier = Modifier.fillMaxWidth())
            }
        }
        item {
            OutlinedButton(onClick = onFinish, enabled = !busy, modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
                Text(if (patrol.completedPoints < patrol.totalPoints) "Finalizar como incompleta" else "Finalizar ronda")
            }
        }
        item { Spacer(Modifier.navigationBarsPadding()) }
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
    onExit: () -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            SummaryHero(
                eyebrow = "Resumo",
                title = "Administração",
                description = "Acompanhe a operação e as pendências",
                badge = if (permanentFailures > 0) "$permanentFailures falhas exigem atenção" else "Operação monitorada",
                initials = "AD",
                modifier = Modifier.statusBarsPadding(),
            )
        }
        item {
            Column(Modifier.padding(horizontal = 24.dp)) {
                SectionHeading("Visão geral", "Indicadores salvos neste dispositivo")
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MetricTile(executionCount.toString(), "rondas", "R", Modifier.weight(1f))
                    MetricTile(problemCount.toString(), "com alerta", "!", Modifier.weight(1f), MaterialTheme.colorScheme.error)
                    MetricTile(pendingSync.toString(), "para sincronizar", "S", Modifier.weight(1f), MaterialTheme.colorScheme.tertiary)
                }
            }
        }
        item { SectionHeading("Atalhos", "Gerencie os principais módulos", Modifier.padding(horizontal = 24.dp)) }
        item { Box(Modifier.padding(horizontal = 24.dp)) { AdminMenuCard("Central de alertas", "$unresolvedAlerts alertas não resolvidos", onAlerts) } }
        item { Box(Modifier.padding(horizontal = 24.dp)) { AdminMenuCard("Locais e pontos", "Condomínio → bloco → andar → local → QR", {}) } }
        item { Box(Modifier.padding(horizontal = 24.dp)) { AdminMenuCard("Programações", "Horários, tolerância, pontos e responsáveis", {}) } }
        item { Box(Modifier.padding(horizontal = 24.dp)) { AdminMenuCard("Porteiros e dispositivos", "Perfis, bloqueios e aparelhos autorizados", {}) } }
        item { Box(Modifier.padding(horizontal = 24.dp)) { AdminMenuCard("Histórico e auditoria", "Linha do tempo e alterações administrativas", {}) } }
        item {
            Text(
                "Os cadastros completos serão habilitados quando o projeto Supabase for conectado. O painel já acompanha a operação offline deste aparelho.",
                modifier = Modifier.padding(horizontal = 24.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onExit) { Text("Sair da administração") }
            }
        }
        item { Spacer(Modifier.navigationBarsPadding().height(8.dp)) }
    }
}

@Composable
private fun AlertsScreen(alerts: List<AlertEntity>, onResolve: (String) -> Unit, onBack: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { HeaderRow("Central de alertas", onBack) }
        if (alerts.isEmpty()) item { Text("Nenhum alerta registrado.") }
        items(alerts, key = { it.id }) { alert ->
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = if (alert.resolved) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.errorContainer)) {
                Column(Modifier.padding(16.dp)) {
                    Text(alert.type.replace('_', ' '), fontWeight = FontWeight.Bold)
                    Text(alert.description)
                    Text(DateFormat.getDateTimeInstance().format(Date(alert.createdAtEpochMillis)), style = MaterialTheme.typography.bodySmall)
                    if (!alert.resolved) TextButton(onClick = { onResolve(alert.id) }) { Text("Marcar como resolvido") }
                }
            }
        }
    }
}

@Composable
private fun AdminMenuCard(title: String, subtitle: String, onClick: () -> Unit) {
    HardShadowCard(Modifier.fillMaxWidth().clickable(onClick = onClick), containerColor = MaterialTheme.colorScheme.surfaceVariant) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("›", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun SyncBadge(count: Int) {
    Surface(color = if (count == 0) Color(0xFFE0F4EA) else Color(0xFFFFF1CC), shape = CircleShape) {
        Text(if (count == 0) "Tudo sincronizado" else "$count eventos aguardando internet", Modifier.padding(horizontal = 14.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun Initials(name: String, size: androidx.compose.ui.unit.Dp = 52.dp) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(size)) {
        Box(contentAlignment = Alignment.Center) {
            Text(name.split(' ').take(2).mapNotNull { it.firstOrNull()?.uppercase() }.joinToString(""), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}

@Composable
private fun ScreenColumn(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(20.dp)) {
        HeaderRow(title, onBack)
        Spacer(Modifier.height(16.dp))
        content()
    }
}

@Composable
private fun HeaderRow(title: String, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onBack) { Text("Voltar") }
        Spacer(Modifier.width(8.dp))
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
    }
}
