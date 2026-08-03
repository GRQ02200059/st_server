package com.stzb.server.protocol

import kotlin.test.Test
import kotlin.test.assertTrue

class CommandCoverageReportTest {
    @Test
    fun `report uses the active inventory version and exposes unfinished statuses`() {
        val report = CommandCoverageReport.render(CommandContractCatalog.registry)

        assertTrue(report.startsWith("# 9.2.4 Command Coverage"))
        assertTrue(report.contains("| 4389 | IO_MOD_CLAIM_NEWBIE_REWARD |"))
        assertTrue(report.contains("| 8041 | INNER_CITY_BUILD_SPEEDUP |"))
        assertTrue(report.contains("UNIMPLEMENTED"))
        assertTrue(report.contains("PROVISIONAL"))
        assertTrue(report.indexOf("| 2 |") < report.indexOf("| 5025 |"))
    }

    @Test
    fun `report heading follows an explicitly loaded legacy inventory`() {
        val registry = CommandContractRegistry(
            inventory = CommandContractRegistry.loadFromClasspath("9.2.2"),
            overrides = emptyList(),
        )

        assertTrue(
            CommandCoverageReport.render(registry)
                .startsWith("# 9.2.2 Command Coverage"),
        )
    }
}
