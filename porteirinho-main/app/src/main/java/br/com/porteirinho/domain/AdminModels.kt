package br.com.porteirinho.domain

data class AdminPoint(
    val id: String,
    val blockId: String,
    val name: String,
    val sequence: Int,
    val fixed: Boolean,
    val qrRawValue: String?,
    val qrVersion: Int?,
)

data class AdminBlock(
    val id: String,
    val name: String,
    val points: List<AdminPoint>,
)

data class AdminRound(
    val id: String,
    val name: String,
    val startMinuteOfDay: Int,
)

data class FixedPointDefinition(
    val id: String,
    val qrCredentialId: String,
    val name: String,
    val sequence: Int,
)

data class FixedBlockDefinition(
    val id: String,
    val name: String,
    val number: Int,
    val points: List<FixedPointDefinition>,
)

data class FixedRoundDefinition(
    val id: String,
    val name: String,
    val slot: Int,
    val startMinuteOfDay: Int,
)

object FixedPatrolStructure {
    const val PropertyId = "10000000-0000-4000-8000-000000000001"
    const val PropertyName = "Condomínio"
    const val RoundDurationMinutes = 60
    const val RoundToleranceMinutes = 15

    private val pointNames = listOf(
        "Térreo",
        "Garagem",
        "Play",
        "1º andar",
        "2º andar",
        "3º andar",
        "4º andar",
        "5º andar",
        "6º andar",
        "7º andar",
        "8º andar",
        "9º andar",
        "10º andar",
        "11º andar",
        "Cobertura",
    )

    val blocks: List<FixedBlockDefinition> = (1..2).map { blockNumber ->
        FixedBlockDefinition(
            id = blockId(blockNumber),
            name = "Bloco $blockNumber",
            number = blockNumber,
            points = pointNames.mapIndexed { index, name ->
                val slot = index + 1
                FixedPointDefinition(
                    id = checkpointId(blockNumber, slot),
                    qrCredentialId = initialQrCredentialId(blockNumber, slot),
                    name = name,
                    sequence = slot,
                )
            },
        )
    }

    val rounds: List<FixedRoundDefinition> = (1..4).map { slot ->
        FixedRoundDefinition(
            id = scheduleId(slot),
            name = "Ronda $slot",
            slot = slot,
            startMinuteOfDay = (slot - 1) * 360,
        )
    }

    val blockIds: Set<String> = blocks.mapTo(linkedSetOf()) { it.id }
    val fixedCheckpointIds: Set<String> = blocks.flatMap { it.points }.mapTo(linkedSetOf()) { it.id }
    val scheduleIds: List<String> = rounds.map { it.id }

    fun isFixedCheckpoint(id: String): Boolean = id in fixedCheckpointIds

    fun blockId(blockNumber: Int): String = when (blockNumber) {
        1 -> "10000000-0000-4000-8000-000000000101"
        2 -> "10000000-0000-4000-8000-000000000102"
        else -> error("Bloco fixo inválido: $blockNumber")
    }

    fun checkpointId(blockNumber: Int, slot: Int): String {
        require(slot in 1..15)
        val prefix = if (blockNumber == 1) "11000000-0000-4000-8101-" else "12000000-0000-4000-8102-"
        return prefix + slot.toString().padStart(12, '0')
    }

    fun initialQrCredentialId(blockNumber: Int, slot: Int): String {
        require(slot in 1..15)
        val prefix = if (blockNumber == 1) "21000000-0000-4000-8201-" else "22000000-0000-4000-8202-"
        return prefix + slot.toString().padStart(12, '0')
    }

    fun scheduleId(slot: Int): String {
        require(slot in 1..4)
        return "31000000-0000-4000-8300-" + slot.toString().padStart(12, '0')
    }

    fun qrRawValue(credentialId: String, checkpointId: String, version: Int): String =
        "porteirinho:v1:$credentialId:$checkpointId-v$version"

    fun endMinuteOfDay(startMinuteOfDay: Int): Int =
        (startMinuteOfDay + RoundDurationMinutes) % (24 * 60)

    fun formatMinuteOfDay(value: Int): String =
        "%02d:%02d".format(value / 60, value % 60)
}
