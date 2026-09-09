package br.com.porteirinho.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import br.com.porteirinho.data.PatrolRepository
import br.com.porteirinho.data.local.UserEntity
import br.com.porteirinho.data.local.UserRole
import br.com.porteirinho.domain.ActivePatrolSnapshot
import br.com.porteirinho.domain.AvailablePatrol
import br.com.porteirinho.domain.LoginResult
import br.com.porteirinho.domain.ScanResult
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
    data object GatekeeperHome : AppScreen
    data object Patrol : AppScreen
    data object Scanner : AppScreen
    data object AdminDashboard : AppScreen
    data object AdminAlerts : AppScreen
}

data class AppUiState(
    val screen: AppScreen = AppScreen.AreaChoice,
    val authenticatedUser: UserEntity? = null,
    val shiftActive: Boolean = false,
    val availablePatrols: List<AvailablePatrol> = emptyList(),
    val activePatrol: ActivePatrolSnapshot? = null,
    val observationCheckpointId: String? = null,
    val observationCheckpointName: String? = null,
    val busy: Boolean = false,
    val message: String? = null,
)

class AppViewModel(
    private val repository: PatrolRepository,
    private val remoteSyncClient: RemoteSyncClient,
) : ViewModel() {
    private val _uiState = MutableStateFlow(AppUiState())
    val uiState: StateFlow<AppUiState> = _uiState.asStateFlow()

    val users = repository.activeUsers.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val alerts = repository.recentAlerts.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val pendingSync = repository.pendingSyncCount.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
    val permanentFailures = repository.permanentSyncFailureCount.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
    val executionCount = repository.executionCount.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
    val problemExecutionCount = repository.problemExecutionCount.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
    val unresolvedAlerts = repository.unresolvedAlertCount.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    init { viewModelScope.launch { repository.seedDemoIfEmpty() } }

    fun chooseArea(role: String) { _uiState.value = _uiState.value.copy(screen = AppScreen.ProfileChoice(role), message = null) }
    fun chooseProfile(userId: String) { _uiState.value = _uiState.value.copy(screen = AppScreen.PinLogin(userId), message = null) }

    fun login(userId: String, pin: String) = launchBusy {
        when (val result = repository.authenticate(userId, pin)) {
            is LoginResult.Error -> showMessage(result.message)
            is LoginResult.Success -> {
                val user = repository.user(result.userId)
                if (result.mustChangePin) {
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
        repository.changePin(userId, pin).onSuccess {
            val user = repository.user(userId)
            if (user?.role == UserRole.ADMIN) {
                _uiState.value = _uiState.value.copy(screen = AppScreen.AdminDashboard, authenticatedUser = user)
            } else {
                enterGatekeeper(userId, user)
            }
            showMessage("Senha definida com sucesso.")
        }.onFailure { showMessage(it.message ?: "Não foi possível definir a senha.") }
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
            showMessage("Turno iniciado e registrado no aparelho.")
        }.onFailure { showMessage(it.message ?: "Não foi possível iniciar o turno.") }
    }

    fun finishShift() = launchBusy {
        val user = requireUser()
        repository.finishShift(user.id).onSuccess {
            _uiState.value = _uiState.value.copy(shiftActive = false, availablePatrols = emptyList())
            showMessage("Turno encerrado.")
        }.onFailure { showMessage(it.message ?: "Não foi possível encerrar o turno.") }
    }

    fun startPatrol(scheduleId: String) = launchBusy {
        val user = requireUser()
        when (val reservation = remoteSyncClient.reservePatrol(scheduleId, user.id, 0L)) {
            RemoteSyncClient.ReservationResult.Reserved,
            RemoteSyncClient.ReservationResult.LocalDemo -> {
                repository.startPatrol(user.id, scheduleId)
                    .onSuccess { patrol -> _uiState.value = _uiState.value.copy(screen = AppScreen.Patrol, activePatrol = patrol) }
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
            showMessage("Observação registrada e enviada para a central de alertas.")
        }.onFailure { showMessage(it.message ?: "Não foi possível registrar a observação.") }
    }

    fun dismissObservation() { _uiState.value = _uiState.value.copy(observationCheckpointId = null, observationCheckpointName = null) }

    fun finishPatrol() = launchBusy {
        val patrol = _uiState.value.activePatrol ?: return@launchBusy
        repository.finishPatrol(patrol.executionId).onSuccess { status ->
            val user = requireUser()
            _uiState.value = _uiState.value.copy(screen = AppScreen.GatekeeperHome, activePatrol = null, observationCheckpointId = null, observationCheckpointName = null, availablePatrols = repository.availablePatrols(user.id))
            showMessage("Ronda finalizada: $status.")
        }.onFailure { showMessage(it.message ?: "Não foi possível finalizar a ronda.") }
    }

    fun openAdminAlerts() { _uiState.value = _uiState.value.copy(screen = AppScreen.AdminAlerts) }
    fun resolveAlert(id: String) = viewModelScope.launch { repository.resolveAlert(id) }

    fun back() {
        val next = when (_uiState.value.screen) {
            AppScreen.AreaChoice -> AppScreen.AreaChoice
            is AppScreen.ProfileChoice -> AppScreen.AreaChoice
            is AppScreen.PinLogin -> AppScreen.ProfileChoice(users.value.firstOrNull { it.id == (_uiState.value.screen as AppScreen.PinLogin).userId }?.role ?: UserRole.GATEKEEPER)
            is AppScreen.ChangePin -> AppScreen.AreaChoice
            AppScreen.GatekeeperHome -> AppScreen.AreaChoice
            AppScreen.Patrol -> AppScreen.GatekeeperHome
            AppScreen.Scanner -> AppScreen.Patrol
            AppScreen.AdminDashboard, AppScreen.AdminAlerts -> AppScreen.AreaChoice
        }
        _uiState.value = _uiState.value.copy(screen = next, authenticatedUser = if (next == AppScreen.AreaChoice) null else _uiState.value.authenticatedUser, message = null)
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
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = AppViewModel(repository, remoteSyncClient) as T
    }
}
