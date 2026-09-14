package br.com.porteirinho.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun PorteirinhoRoot(viewModel: AppViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    if (state.screen != AppScreen.AdminDashboard) {
        PorteirinhoApp(viewModel)
        return
    }

    val blocks by viewModel.adminBlocks.collectAsStateWithLifecycle()
    val rounds by viewModel.adminRounds.collectAsStateWithLifecycle()
    val pendingSync by viewModel.pendingSync.collectAsStateWithLifecycle()
    val unresolvedAlerts by viewModel.unresolvedAlerts.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    state.message?.let { message ->
        LaunchedEffect(message) {
            snackbarHostState.showSnackbar(message)
            viewModel.consumeMessage()
        }
    }

    BackHandler(onBack = viewModel::back)

    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (state.adminSection) {
                AdminSection.HOME -> AdminPatrolConfigurationHome(
                    blockCount = blocks.size,
                    fixedPointCount = blocks.sumOf { block -> block.points.count { it.fixed } },
                    roundCount = rounds.size,
                    pendingSync = pendingSync,
                    unresolvedAlerts = unresolvedAlerts,
                    onPoints = viewModel::openAdminPoints,
                    onRounds = viewModel::openAdminRounds,
                    onAlerts = viewModel::openAdminAlerts,
                    onExit = viewModel::back,
                )
                AdminSection.POINTS -> AdminPointsScreen(
                    blocks = blocks,
                    busy = state.busy,
                    onAddExtra = viewModel::addExtraPoint,
                    onReplaceQr = viewModel::replaceQr,
                    onBack = viewModel::back,
                )
                AdminSection.ROUNDS -> AdminRoundsScreen(
                    rounds = rounds,
                    busy = state.busy,
                    onSave = viewModel::updateFixedRound,
                    onBack = viewModel::back,
                )
            }

            if (state.busy) {
                Box(
                    Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
        }
    }
}

@Composable
private fun AdminPatrolConfigurationHome(
    blockCount: Int,
    fixedPointCount: Int,
    roundCount: Int,
    pendingSync: Int,
    unresolvedAlerts: Int,
    onPoints: () -> Unit,
    onRounds: () -> Unit,
    onAlerts: () -> Unit,
    onExit: () -> Unit,
) {
    LazyColumn(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            SummaryHero(
                eyebrow = "Configuração de rondas",
                title = "Administração",
                description = "Estrutura fixa do condomínio e horários obrigatórios",
                badge = if (pendingSync == 0) "Configuração sincronizada" else "$pendingSync alterações aguardando sincronização",
                initials = "AD",
                modifier = Modifier.statusBarsPadding(),
            )
        }
        item {
            Column(Modifier.padding(horizontal = 24.dp)) {
                SectionHeading("Estrutura do condomínio", "Somente os módulos desta versão estão habilitados")
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MetricTile(blockCount.toString(), "blocos", "B", Modifier.weight(1f))
                    MetricTile(fixedPointCount.toString(), "pontos fixos", "QR", Modifier.weight(1f))
                    MetricTile(roundCount.toString(), "rondas fixas", "R", Modifier.weight(1f))
                }
            }
        }
        item {
            Box(Modifier.padding(horizontal = 24.dp)) {
                AdminConfigurationCard(
                    title = "Pontos de Ronda",
                    subtitle = "2 blocos, 30 pontos fixos, QR Codes e pontos extras",
                    onClick = onPoints,
                )
            }
        }
        item {
            Box(Modifier.padding(horizontal = 24.dp)) {
                AdminConfigurationCard(
                    title = "Rondas Fixas",
                    subtitle = "4 rondas obrigatórias • editar somente nome e início",
                    onClick = onRounds,
                )
            }
        }
        item {
            Box(Modifier.padding(horizontal = 24.dp)) {
                AdminConfigurationCard(
                    title = "Central de alertas",
                    subtitle = "$unresolvedAlerts alertas não resolvidos",
                    onClick = onAlerts,
                )
            }
        }
        item {
            Text(
                "Cadastro de porteiros, PINs, associações e respectivas FKs permanecem fora do escopo desta versão.",
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
        item { Spacer(Modifier.height(12.dp)) }
    }
}

@Composable
private fun AdminConfigurationCard(title: String, subtitle: String, onClick: () -> Unit) {
    HardShadowCard(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("›", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
        }
    }
}
