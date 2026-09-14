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
    val permanentFailures by viewModel.permanentFailures.collectAsStateWithLifecycle()
    val executionCount by viewModel.executionCount.collectAsStateWithLifecycle()
    val problemExecutionCount by viewModel.problemExecutionCount.collectAsStateWithLifecycle()
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
                    executionCount = executionCount,
                    problemCount = problemExecutionCount,
                    pendingSync = pendingSync,
                    permanentFailures = permanentFailures,
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
    executionCount: Int,
    problemCount: Int,
    pendingSync: Int,
    permanentFailures: Int,
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
        item {
            Text(
                "$blockCount blocos • $fixedPointCount pontos fixos • $roundCount rondas obrigatórias",
                modifier = Modifier.padding(horizontal = 24.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item { SectionHeading("Atalhos", "Gerencie os principais módulos", Modifier.padding(horizontal = 24.dp)) }
        item {
            Box(Modifier.padding(horizontal = 24.dp)) {
                AdminConfigurationCard("Central de alertas", "$unresolvedAlerts alertas não resolvidos", onAlerts)
            }
        }
        item {
            Box(Modifier.padding(horizontal = 24.dp)) {
                AdminConfigurationCard("Pontos de Ronda", "2 blocos, 30 pontos fixos, QR Codes e pontos extras", onPoints)
            }
        }
        item {
            Box(Modifier.padding(horizontal = 24.dp)) {
                AdminConfigurationCard("Rondas Fixas", "4 rondas obrigatórias • editar nome e horário de início", onRounds)
            }
        }
        item {
            Box(Modifier.padding(horizontal = 24.dp)) {
                AdminConfigurationCard("Porteiros e dispositivos", "Perfis, bloqueios e aparelhos autorizados", {})
            }
        }
        item {
            Box(Modifier.padding(horizontal = 24.dp)) {
                AdminConfigurationCard("Histórico e auditoria", "Linha do tempo e alterações administrativas", {})
            }
        }
        item {
            Text(
                "Nesta versão, somente Pontos de Ronda, QR Codes, os 2 blocos e as 4 Rondas Fixas receberam alterações. A área de porteiros permanece inalterada.",
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
