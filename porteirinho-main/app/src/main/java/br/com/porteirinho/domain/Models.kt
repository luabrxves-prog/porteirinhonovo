package br.com.porteirinho.domain

import br.com.porteirinho.data.local.PatrolScheduleEntity

data class AvailablePatrol(
    val schedule: PatrolScheduleEntity,
    val pointCount: Int,
    val windowLabel: String,
    val availableNow: Boolean,
)

data class ActivePatrolSnapshot(
    val executionId: String,
    val scheduleName: String,
    val startedAtEpochMillis: Long,
    val totalPoints: Int,
    val visitedPointIds: Set<String>,
    val checkpointNames: List<Pair<String, String>>,
    val suspicious: Boolean,
) {
    val completedPoints: Int get() = visitedPointIds.size
    val progress: Float get() = if (totalPoints == 0) 0f else completedPoints.toFloat() / totalPoints
}

sealed interface LoginResult {
    data class Success(val userId: String, val role: String) : LoginResult
    data class Error(val message: String) : LoginResult
}

sealed interface ScanResult {
    data class Accepted(val checkpointName: String, val suspicious: Boolean) : ScanResult
    data class AlreadyVisited(val checkpointName: String) : ScanResult
    data class Rejected(val message: String) : ScanResult
}
