package com.stzb.server.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CommandContractRegistryTest {
    @Test
    fun `union group commands expose readable ids`() {
        assertEquals(142, Cmd.UNION_GET_GROUP_LIST)
        assertEquals(143, Cmd.UNION_GET_ALL_MEMBER_LIST_FOR_CHAT)
    }

    @Test
    fun `rank list exposes a readable handler owned contract`() {
        assertEquals(700, Cmd.RANK_LIST)
        val contract = CommandContractCatalog.registry.contract(Cmd.RANK_LIST)
        assertEquals(CommandStatus.PROVISIONAL, contract?.status)
        assertEquals("GameServerHandler", contract?.owner)
    }

    @Test
    fun `user head icon lookup exposes a readable handler owned contract`() {
        assertEquals(514, Cmd.USER_GET_USERS_HEADICON)
        val contract = CommandContractCatalog.registry.contract(Cmd.USER_GET_USERS_HEADICON)
        assertEquals(CommandStatus.PROVISIONAL, contract?.status)
        assertEquals("GameServerHandler", contract?.owner)
    }

    @Test
    fun `mail info exposes a readable handler owned contract`() {
        assertEquals(204, Cmd.MAIL_INFO)
        val contract = CommandContractCatalog.registry.contract(Cmd.MAIL_INFO)
        assertEquals(CommandStatus.PROVISIONAL, contract?.status)
        assertEquals("GameServerHandler", contract?.owner)
    }

    @Test
    fun `production registry contains every generated 9 2 2 inventory command`() {
        val registry = CommandContractCatalog.registry
        val all = registry.all()

        assertTrue(all.size >= 2_591)
        assertEquals(all.map(CommandContract::id).sorted(), all.map(CommandContract::id))
        assertNotNull(registry.contract(Cmd.GET_WORLD_SCENCE_INFO))
        assertNotNull(registry.contract(Cmd.SEND_WORLD_SCENCE_FULL_INFO))
        assertNotNull(registry.contract(Cmd.SYS_NOTIFY_DB_UPDATE))
        assertNotNull(registry.contract(2_100))
    }

    @Test
    fun `existing handler and emitted commands stay provisional until audited`() {
        val registry = CommandContractCatalog.registry

        assertEquals(CommandStatus.PROVISIONAL, registry.contract(Cmd.CARD_RECRUIT)?.status)
        assertEquals(CommandStatus.PROVISIONAL, registry.contract(Cmd.GET_WORLD_SCENCE_INFO)?.status)
        assertEquals(CommandStatus.PROVISIONAL, registry.contract(710)?.status)
        assertEquals(CommandStatus.PROVISIONAL, registry.contract(Cmd.SYS_NOTIFY_DB_UPDATE)?.status)
        assertEquals(CommandStatus.PROVISIONAL, registry.contract(Cmd.SEND_WORLD_SCENCE_FULL_INFO)?.status)
        assertEquals(CommandStatus.PROVISIONAL, registry.contract(2_100)?.status)
    }

    @Test
    fun `union group contracts reflect policy and explicit handler ownership`() {
        val groupList = CommandContractCatalog.registry.contract(Cmd.UNION_GET_GROUP_LIST)
        assertEquals(CommandStatus.OBSERVED_SHAPE, groupList?.status)
        assertEquals("NetworkResponsePolicy", groupList?.owner)

        val chatMembers = CommandContractCatalog.registry.contract(Cmd.UNION_GET_ALL_MEMBER_LIST_FOR_CHAT)
        assertEquals(CommandStatus.PROVISIONAL, chatMembers?.status)
        assertEquals("GameServerHandler", chatMembers?.owner)
    }

    @Test
    fun `recorded shape command is eligible but unknown command is not`() {
        val registry = CommandContractCatalog.registry

        assertEquals(CommandStatus.OBSERVED_SHAPE, registry.contract(959)?.status)
        assertTrue(registry.isShapeResponseAllowed(959))
        assertTrue(!registry.isShapeResponseAllowed(45_678))
    }

    @Test
    fun `exact contracts require ownership shape projection and evidence`() {
        val inventory = ClientCommandInventory(
            clientVersion = "9.2.2",
            commands = listOf(ClientCommandInventoryEntry(id = 1)),
        )

        assertFailsWith<IllegalArgumentException> {
            CommandContractRegistry(
                inventory = inventory,
                overrides = listOf(
                    CommandContract(
                        id = 1,
                        names = listOf("ONE"),
                        direction = CommandDirection.CLIENT_REQUEST,
                        domain = CommandDomain.WORLD,
                        status = CommandStatus.EXACT,
                    ),
                ),
            )
        }
    }

    @Test
    fun `every inventory entry has one effective contract and observed shapes have bodies`() {
        val registry = CommandContractCatalog.registry
        val inventoryIds = CommandContractRegistry.loadFromClasspath()
            .commands
            .map(ClientCommandInventoryEntry::id)
            .toSet()
        val contracts = registry.all()

        assertEquals(inventoryIds, contracts.map(CommandContract::id).toSet())
        contracts
            .filter { it.status == CommandStatus.OBSERVED_SHAPE }
            .forEach { contract ->
                assertNotNull(
                    NetworkResponsePolicy.observedShapeBody(contract.id),
                    "missing observed response shape for ${contract.id}",
                )
            }
    }
}
