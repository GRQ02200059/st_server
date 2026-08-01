package com.stzb.server.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CommandContractRegistryTest {
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
}
