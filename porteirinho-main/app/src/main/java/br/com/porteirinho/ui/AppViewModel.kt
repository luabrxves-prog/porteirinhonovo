package br.com.porteirinho.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import br.com.porteirinho.BuildConfig
import br.com.porteirinho.data.PatrolRepository
import br.com.porteirinho.data.local.UserEntity
import br.com.porteirinho.data.local.UserRole
import br.com.porteirinho.domain.ActivePatrolSnapshot
import br.com.porteirinho.domain.AvailablePatrol
import br.com.porteirinho.domain.LoginResult
import br.com.porteirinho.domain.ScanResult
import br.com.porteirinho.sync.AdminRemoteClient
import br.com.porteirinho.sync.RemoteSyncClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface AppScreen {
    data object AreaChoice : AppScreen
    data class ProfileChoice(val role: String) : AppScreen
    data class PinLogin(val userId: String) : AppScreen
    data class ChangePin(val userId: String) : AppScreen
    data object AdminLogin : AppScreen
    data object GatekeeperHome : AppScreen
    data object Patrol : AppScreen
    data object Scanner : AppScreen
    data object AdminDashboard : AppScreen
    data object AdminAlerts : AppScreen
    data object AdminPoints : AppScreen
    data class AdminQr(val checkpointId: String) : AppScreen
    data object AdminSchedules : AppScreen
    data object AdminGatekeepers : AppScreen
    data object AdminReports : AppScreen
}

data class AppUiState(
    val screen: AppScreen = AppScreen.AreaChoice,
    val authenticatedUser: UserEntity? = null,
    val shiftActive: Boolean = false,
    val availablePatrols: List<AvailablePatrol> = emptyList(),
    val activePatrol: ActivePatrolSnapshot? = null,
    val observationCheckpointId: String? = null,
    val observationCheckpointName: String? = null,
    val adminQrPayload: AdminRemoteClient.QrPayload? = null,
    val generatedGatekeeperPin: String? = null,
    val reportFilePath: String? = null,
    val busy: Boolean = false,
    val message: String? = null,
)

class AppViewModel(
    private val repository: PatrolRepository,
    private val remoteSyncClient: RemoteSyncClient,
    private val adminRemoteClient: AdminRemoteClient,
) : ViewModel() {
    private val _uiState = MutableStateFlow(AppUiState())
    val uiState: StateFlow<AppUiState> = _uiState.asStateFlow()

    val users = repository.activeUsers.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val checkpoints = repository.checkpoints.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val schedules = repository.schedules.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val alerts = repository.recentAlerts.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val pendingSync = repository.pendingSyncCount.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
    val permanentFailures = repository.permanentSyncFailureCount.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
    val executionCount = repository.executionCount.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
    val problemExecutionCount = repository.problemExecutionCount.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
    val unresolvedAlerts = repository.unresolvedAlertCount.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    init {
        viewModelScope.launch {
            if (BuildConfig.SUPABASE_URL.isBlank()) repository.seedDemoIfEmpty()
            else remoteSyncClient.pullSnapshot()
        }
    }

    fun chooseArea(role: String) {
        val next = if (role == UserRole.ADMIN && BuildConfig.SUPABASE_URL.isNotBlank()) {
            if (adminRemoteClient.hasSession()) AppScreen.AdminDashboard else AppScreen.AdminLogin
        } else {
            AppScreen.ProfileChoice(role)
        }
        _uiState.value = _uiState.value.copy(screen = next, message = null)
    }

    fun loginAdmin(email: String, password: String) = launchBusy {
        adminRemoteClient.login(email, password).getOrThrow()
        remoteSyncClient.pullSnapshot()
        _uiState.value = _uiState.value.copy(screen = AppScreen.AdminDashboard, authenticatedUser = null)
    }

    fun logoutAdmin() {
        adminRemoteClient.logout()
        _uiState.value = AppUiState(screen = AppScreen.AreaChoice)
    }

    fun chooseProfile(userId: String) { _uiState.value = _uiState.value.copy(screen = AppScreen.PinLogin(userId), message = null) }

    fun login(userId: String, pin: String) = launchBusy {
        when (val result = repository.authenticate(userId, pin)) {
            is LoginResult.Error -> showMessage(result.message)
            is LoginResult.Success -> {
                val user = repository.user(result.userId)
                if (result.role == UserRole.GATEKEEPER && BuildConfig.SUPABASE_URL.isNotBlank()) {
                    val onlineMustChange = remoteSyncClient.loginGuardOnline(result.userId, pin)
                        .getOrElse { error ->
                            if (result.mustChangePin) throw IllegalStateException(
                                "Conecte este aparelho à internet para concluir o primeiro acesso. ${error.message.orEmpty()}".trim(),
                            )
                            false
                        }
                    if (result.mustChangePin || onlineMustChange) {
                        _uiState.value = _uiState.value.copy(screen = AppScreen.ChangePin(result.userId), authenticatedUser = user)
                    } else {
                        enterGatekeeper(result.userId, user)
                    }
                } else if (result.mustChangePin) {
                    _uiState.value = _uiState.value.copy(screen = AppScreen.ChangePin(result.userId), authenticatedUser = user)
                } else if (result.role == UserRole.ADMIN) {
                    _uiState.value = _uiState.value.copy(screen = AppScreen.AdminDashboard, authenticatedUser = user)
                } else {
                    enterGatekeeper(result.userId, user)
                }
            }
        }
    }

    fun changePin(userId: String, pin: String) = launchBusy {
        val user = repository.user(userId) ?: error("Perfil não encontrado.")
        if (BuildConfig.SUPABASE_URL.isNotBlank() && user.role == UserRole.GATEKEEPER) {
            require(pin.length == 6 && pin.all(Char::isDigit)) { "O novo PIN deve conter exatamente 6 dígitos." }
            remoteSyncClient.changeGuardPinOnline(userId, pin).getOrThrow()
        }
        repository.changePin(userId, pin).getOrThrow()
        if (user.role == UserRole.ADMIN) {
            _uiState.value = _uiState.value.copy(screen = AppScreen.AdminDashboard, authenticatedUser = user)
        } else {
            remoteSyncClient.pullSnapshot()
            enterGatekeeper(userId, repository.user(userId))
        }
        showMessage("Senha definida com sucesso.")
    }

    private suspend fun enterGatekeeper(userId: String, user: UserEntity?) {
        val active = repository.resumePatrol(userId)
        val shiftActive = repository.hasActiveShift(userId)
        _uiState.value = _uiState.value.copy(
            screen = if (active == null) AppScreen.GatekeeperHome else AppScreen.Patrol,
            authenticatedUser = user,
            shiftActive = shiftActive,
            activePatrol = active,
            availablePatrols = if (shiftActive) repository.availablePatrols(userId) else emptyList(),
        )
    }

    fun startShift() = launchBusy {
        val user = requireUser()
        repository.startShift(user.id).onSuccess {
            _uiState.value = _uiState.value.copy(shiftActive = true, availablePatrols = repository.availablePatrols(user.id))
            adminRemoteClient.requestSyncNow()
            showMessage("Turno iniciado.")
        }.onFailure { showMessage(it.message ?: "Não foi possível iniciar o turno.") }
    }

    fun finishShift() = launchBusy {
        val user = requireUser()
        repository.finishShift(user.id).onSuccess {
            _uiState.value = _uiState.value.copy(shiftActive = false, availablePatrols = emptyList())
            adminRemoteClient.requestSyncNow()
            showMessage("Turno encerrado.")
        }.onFailure { showMessage(it.message ?: "Não foi possível encerrar o turno.") }
    }

    fun startPatrol(scheduleId: String) = launchBusy {
        val user = requireUser()
        when (val reservation = remoteSyncClient.reservePatrol(scheduleId, user.id, 0L)) {
            RemoteSyncClient.ReservationResult.Reserved,
            RemoteSyncClient.ReservationResult.LocalDemo -> {
                repository.startPatrol(user.id, scheduleId)
                    .onSuccess { patrol ->
                        _uiState.value = _uiState.value.copy(screen = AppScreen.Patrol, activePatrol = patrol)
                        adminRemoteClient.requestSyncNow()
                    }
                    .onFailure { showMessage(it.message ?: "Não foi possível iniciar a ronda.") }
            }
            is RemoteSyncClient.ReservationResult.Conflict -> showMessage(reservation.message)
            is RemoteSyncClient.ReservationResult.Unavailable -> showMessage(reservation.message)
        }
    }

    fun openScanner() { _uiState.value = _uiState.value.copy(screen = AppScreen.Scanner, message = null) }
    fun cancelScanner() { _uiState.value = _uiState.value.copy(screen = AppScreen.Patrol) }

    fun processScan(rawValue: String) = launchBusy {
        val patrol = _uiState.value.activePatrol ?: return@launchBusy
        when (val result = repository.registerScan(patrol.executionId, rawValue)) {
            is ScanResult.Accepted -> {
                _uiState.value = _uiState.value.copy(observationCheckpointId = result.checkpointId, observationCheckpointName = result.checkpointName)
                adminRemoteClient.requestSyncNow()
                showMessage(if (result.suspicious) "Ponto confirmado. A leitura gerou um alerta para auditoria." else "${result.checkpointName} confirmado.")
            }
            is ScanResult.AlreadyVisited -> showMessage("${result.checkpointName} já foi confirmado.")
            is ScanResult.Rejected -> showMessage(result.message)
        }
        val user = requireUser()
        _uiState.value = _uiState.value.copy(screen = AppScreen.Patrol, activePatrol = repository.resumePatrol(user.id))
    }

    fun saveObservation(text: String) = launchBusy {
        val patrol = _uiState.value.activePatrol ?: return@launchBusy
        val checkpointId = _uiState.value.observationCheckpointId ?: return@launchBusy
        repository.addOccurrence(patrol.executionId, checkpointId, text).onSuccess {
            _uiState.value = _uiState.value.copy(observationCheckpointId = null, observationCheckpointName = null)
            adminRemoteClient.requestSyncNow()
            showMessage("Observação registrada e enviada para a central de alertas.")
        }.onFailure { showMessage(it.message ?: "Não foi possível registrar a observação.") }
    }

    fun dismissObservation() { _uiState.value = _uiState.value.copy(observationCheckpointId = null, observationCheckpointName = null) }

    fun finishPatrol() = launchBusy {
        val patrol = _uiState.value.activePatrol ?: return@launchBusy
        repository.finishPatrol(patrol.executionId).onSuccess { status ->
            val user = requireUser()
            _uiState.value = _uiState.value.copy(screen = AppScreen.GatekeeperHome, activePatrol = null, observationCheckpointId = null, observationCheckpointName = null, availablePatrols = repository.availablePatrols(user.id))
            adminRemoteClient.requestSyncNow()
            showMessage("Ronda finalizada: $status.")
        }.onFailure { showMessage(it.message ?: "Não foi possível finalizar a ronda.") }
    }

    fun openAdminAlerts() { _uiState.value = _uiState.value.copy(screen = AppScreen.AdminAlerts) }
    fun openAdminPoints() { _uiState.value = _uiState.value.copy(screen = AppScreen.AdminPoints) }
    fun openAdminSchedules() { _uiState.value = _uiState.value.copy(screen = AppScreen.AdminSchedules) }
    fun openAdminGatekeepers() { _uiState.value = _uiState.value.copy(screen = AppScreen.AdminGatekeepers, generatedGatekeeperPin = null) }
    fun openAdminReports() { _uiState.value = _uiState.value.copy(screen = AppScreen.AdminReports, reportFilePath = null) }

    fun addAdminPoint(name: String) = launchBusy {
        if (BuildConfig.SUPABASE_URL.isBlank()) {
            repository.addExtraCheckpoint("place-demo", name).getOrThrow()
        } else {
            adminRemoteClient.createPoint(name).getOrThrow()
            remoteSyncClient.pullSnapshot().getOrThrow()
        }
        showMessage("Ponto adicionado às quatro rondas.")
    }

    fun openAdminQr(checkpointId: String) = launchBusy {
        _uiState.value = _uiState.value.copy(screen = AppScreen.AdminQr(checkpointId), adminQrPayload = null)
        if (BuildConfig.SUPABASE_URL.isBlank()) {
            showMessage("No modo de demonstração, substitua o QR para gerar um código visualizável.")
        } else {
            adminRemoteClient.getQr(checkpointId)
                .onSuccess { _uiState.value = _uiState.value.copy(adminQrPayload = it) }
                .onFailure { showMessage(it.message ?: "Não foi possível carregar o QR Code.") }
        }
    }

    fun replaceAdminQr(checkpointId: String) = launchBusy {
        if (BuildConfig.SUPABASE_URL.isBlank()) {
            val raw = repository.replaceQr(checkpointId).getOrThrow()
            _uiState.value = _uiState.value.copy(adminQrPayload = AdminRemoteClient.QrPayload(checkpointId, "demo", 1, raw))
        } else {
            val qr = adminRemoteClient.replaceQr(checkpointId).getOrThrow()
            _uiState.value = _uiState.value.copy(adminQrPayload = qr)
            remoteSyncClient.pullSnapshot()
        }
        showMessage("QR Code substituído. O código anterior não funciona mais.")
    }

    fun updateAdminSchedule(scheduleId: String, name: String, startMinuteOfDay: Int) = launchBusy {
        if (BuildConfig.SUPABASE_URL.isBlank()) {
            repository.updateFixedSchedule(scheduleId, name, startMinuteOfDay).getOrThrow()
        } else {
            adminRemoteClient.updateSchedule(scheduleId, name, startMinuteOfDay).getOrThrow()
            remoteSyncClient.pullSnapshot().getOrThrow()
        }
        showMessage("Ronda atualizada.")
    }

    fun createAdminGatekeeper(displayName: String) = launchBusy {
        if (BuildConfig.SUPABASE_URL.isBlank()) {
            repository.createGatekeeper(displayName).onSuccess { (_, pin) -> _uiState.value = _uiState.value.copy(generatedGatekeeperPin = pin) }.getOrThrow()
        } else {
            val created = adminRemoteClient.createGatekeeper(displayName).getOrThrow()
            _uiState.value = _uiState.value.copy(generatedGatekeeperPin = created.temporaryPin)
            remoteSyncClient.pullSnapshot().getOrThrow()
        }
        showMessage("Porteiro cadastrado. Entregue o PIN temporário para o primeiro acesso.")
    }

    fun clearGeneratedGatekeeperPin() { _uiState.value = _uiState.value.copy(generatedGatekeeperPin = null) }

    fun downloadAdminReport(days: Int) = launchBusy {
        adminRemoteClient.downloadReport(days)
            .onSuccess { file ->
                _uiState.value = _uiState.value.copy(reportFilePath = file.absolutePath)
                showMessage("Relatório de $days dias gerado.")
            }
            .onFailure { showMessage(it.message ?: "Não foi possível gerar o relatório.") }
    }

    fun clearReportFile() { _uiState.value = _uiState.value.copy(reportFilePath = null) }

    fun resolveAlert(id: String) = launchBusy {
        if (BuildConfig.SUPABASE_URL.isBlank()) repository.resolveAlert(id)
        else {
            adminRemoteClient.resolveAlert(id).getOrThrow()
            repository.resolveAlert(id)
        }
        showMessage("Alerta marcado como resolvido.")
    }

    fun back() {
        val next = when (_uiState.value.screen) {
            AppScreen.AreaChoice -> AppScreen.AreaChoice
            AppScreen.AdminLogin -> AppScreen.AreaChoice
            is AppScreen.ProfileChoice -> AppScreen.AreaChoice
            is AppScreen.PinLogin -> AppScreen.ProfileChoice(users.value.firstOrNull { it.id == (_uiState.value.screen as AppScreen.PinLogin).userId }?.role ?: UserRole.GATEKEEPER)
            is AppScreen.ChangePin -> AppScreen.AreaChoice
            AppScreen.GatekeeperHome -> AppScreen.AreaChoice
            AppScreen.Patrol -> AppScreen.GatekeeperHome
            AppScreen.Scanner -> AppScreen.Patrol
            AppScreen.AdminDashboard -> AppScreen.AreaChoice
            AppScreen.AdminAlerts,
            AppScreen.AdminPoints,
            AppScreen.AdminSchedules,
            AppScreen.AdminGatekeepers,
            AppScreen.AdminReports -> AppScreen.AdminDashboard
            is AppScreen.AdminQr -> AppScreen.AdminPoints
        }
        _uiState.value = _uiState.value.copy(
            screen = next,
            authenticatedUser = if (next == AppScreen.AreaChoice) null else _uiState.value.authenticatedUser,
            adminQrPayload = if (next is AppScreen.AdminQr) _uiState.value.adminQrPayload else null,
            message = null,
        )
    }

    fun consumeMessage() { _uiState.value = _uiState.value.copy(message = null) }

    private fun launchBusy(block: suspend () -> Unit) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(busy = true, message = null)
            try { block() } catch (error: Throwable) { showMessage(error.message ?: "Ocorreu um erro inesperado.") }
            finally { _uiState.value = _uiState.value.copy(busy = false) }
        }
    }

    private fun requireUser(): UserEntity = checkNotNull(_uiState.value.authenticatedUser) { "Sessão expirada." }
    private fun showMessage(message: String) { _uiState.value = _uiState.value.copy(message = message) }

    class Factory(
        private val repository: PatrolRepository,
        private val remoteSyncClient: RemoteSyncClient,
        private val adminRemoteClient: AdminRemoteClient,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = AppViewModel(repository, remoteSyncClient, adminRemoteClient) as T
    }
}
