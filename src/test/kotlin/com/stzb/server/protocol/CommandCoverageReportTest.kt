package com.stzb.server.protocol

import kotlin.test.Test
import kotlin.test.assertTrue

class CommandCoverageReportTest {
    @Test
    fun `report lists all commands by id and exposes unfinished statuses`() {
        val report = CommandCoverageReport.render(CommandContractCatalog.registry)

        assertTrue(report.startsWith("# 9.2.2 Command Coverage"))
        assertTrue(report.contains("| 5025 |"))
        assertTrue(report.contains("| 5026 |"))
        assertTrue(report.contains("| 90005 |"))
        assertTrue(report.contains("UNIMPLEMENTED"))
        assertTrue(report.contains("PROVISIONAL"))
        assertTrue(report.indexOf("| 2 |") < report.indexOf("| 5025 |"))
    }
}
