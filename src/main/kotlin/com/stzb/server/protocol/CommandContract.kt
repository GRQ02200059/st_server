package com.stzb.server.protocol

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue

enum class CommandDirection {
    CLIENT_REQUEST,
    SERVER_PUSH,
    DUPLEX,
}

enum class CommandStatus {
    EXACT,
    OBSERVED_SHAPE,
    PROVISIONAL,
    UNIMPLEMENTED,
    REJECTED,
}

enum class CommandDomain {
    TRANSPORT,
    LOGIN,
    WORLD,
    CITY,
    ARMY,
    HERO,
    BATTLE,
    SOCIAL,
    ACTIVITY,
    EXTERNAL,
    UNKNOWN,
}

data class ContractEvidence(
    val kind: String,
    val reference: String,
)

data class ResponseStep(
    val cmdId: Int,
    val description: String,
)

data class ClientCommandInventoryEntry(
    val id: Int,
    val names: List<String> = emptyList(),
    val requestSources: List<String> = emptyList(),
    val receiveSources: List<String> = emptyList(),
    val captureSendCount: Int = 0,
    val captureReceiveCount: Int = 0,
)

data class ClientCommandInventory(
    val clientVersion: String,
    val commands: List<ClientCommandInventoryEntry>,
    val unresolvedRequestSources: List<String> = emptyList(),
)

data class CommandContract(
    val id: Int,
    val names: List<String>,
    val direction: CommandDirection,
    val domain: CommandDomain,
    val status: CommandStatus,
    val owner: String? = null,
    val requestShape: String? = null,
    val responseSequence: List<ResponseStep> = emptyList(),
    val stateProjection: List<String> = emptyList(),
    val evidence: List<ContractEvidence> = emptyList(),
)

class CommandContractRegistry(
    inventory: ClientCommandInventory,
    overrides: Collection<CommandContract>,
) {
    val clientVersion: String = inventory.clientVersion
    private val byId: Map<Int, CommandContract>
    private val inventoryById: Map<Int, ClientCommandInventoryEntry>

    init {
        require(clientVersion in SUPPORTED_CLIENT_VERSIONS) {
            "unsupported client inventory: $clientVersion"
        }
        require(inventory.commands.map(ClientCommandInventoryEntry::id).distinct().size == inventory.commands.size) {
            "duplicate command ids in client inventory"
        }
        require(overrides.map(CommandContract::id).distinct().size == overrides.size) {
            "duplicate command contract overrides"
        }

        inventoryById = inventory.commands.associateBy(ClientCommandInventoryEntry::id)
        require(overrides.all { it.id in inventoryById }) {
            "command override is absent from client inventory"
        }

        val overridesById = overrides.associateBy(CommandContract::id)
        byId = inventory.commands.associate { entry ->
            val baseline = CommandContract(
                id = entry.id,
                names = entry.names,
                direction = inferredDirection(entry),
                domain = CommandDomain.UNKNOWN,
                status = CommandStatus.UNIMPLEMENTED,
            )
            val override = overridesById[entry.id]
            entry.id to (
                override?.copy(names = override.names.ifEmpty { entry.names })
                    ?: baseline
                )
        }
        byId.values.forEach(::validate)
    }

    fun contract(cmdId: Int): CommandContract? = byId[cmdId]

    fun inventoryEntry(cmdId: Int): ClientCommandInventoryEntry? = inventoryById[cmdId]

    fun all(): List<CommandContract> = byId.values.sortedBy(CommandContract::id)

    fun isShapeResponseAllowed(cmdId: Int): Boolean =
        contract(cmdId)?.status == CommandStatus.OBSERVED_SHAPE

    private fun validate(contract: CommandContract) {
        if (contract.status != CommandStatus.EXACT) return

        require(!contract.owner.isNullOrBlank()) {
            "exact command ${contract.id} has no owner"
        }
        require(
            contract.direction == CommandDirection.SERVER_PUSH ||
                !contract.requestShape.isNullOrBlank(),
        ) {
            "exact command ${contract.id} has no request shape"
        }
        require(contract.responseSequence.isNotEmpty()) {
            "exact command ${contract.id} has no response sequence"
        }
        require(contract.stateProjection.isNotEmpty()) {
            "exact command ${contract.id} has no state projection"
        }
        require(contract.evidence.any { it.kind == "SOURCE" }) {
            "exact command ${contract.id} has no source evidence"
        }
        require(contract.evidence.any { it.kind == "SERVER_TEST" }) {
            "exact command ${contract.id} has no server test evidence"
        }
    }

    companion object {
        const val ACTIVE_CLIENT_VERSION = "9.2.4"
        private val SUPPORTED_CLIENT_VERSIONS = setOf("9.2.2", "9.2.4")
        private val mapper = jacksonObjectMapper()

        fun loadFromClasspath(
            clientVersion: String = ACTIVE_CLIENT_VERSION,
        ): ClientCommandInventory {
            require(clientVersion in SUPPORTED_CLIENT_VERSIONS) {
                "unsupported client inventory: $clientVersion"
            }
            val resource = "protocol/client-$clientVersion-command-inventory.json"
            val inventory = requireNotNull(
                CommandContractRegistry::class.java.classLoader
                    .getResourceAsStream(resource),
            ) {
                "missing protocol inventory resource: $resource"
            }.use { stream ->
                mapper.readValue<ClientCommandInventory>(stream)
            }
            require(inventory.clientVersion == clientVersion) {
                "inventory version mismatch: requested=$clientVersion " +
                    "actual=${inventory.clientVersion}"
            }
            return inventory
        }
    }
}

private fun inferredDirection(entry: ClientCommandInventoryEntry): CommandDirection =
    when {
        entry.requestSources.isNotEmpty() && entry.receiveSources.isNotEmpty() ->
            CommandDirection.DUPLEX

        entry.requestSources.isNotEmpty() || entry.captureSendCount > 0 ->
            CommandDirection.CLIENT_REQUEST

        else -> CommandDirection.SERVER_PUSH
    }
