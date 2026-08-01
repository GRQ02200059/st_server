package com.stzb.server.protocol

import java.nio.file.Files
import java.nio.file.Path

object CommandCoverageReport {
    fun render(registry: CommandContractRegistry): String {
        val inventoryById = registry.all()
            .associate { contract ->
                contract.id to requireNotNull(registry.inventoryEntry(contract.id))
            }

        return buildString {
            appendLine("# 9.2.2 Command Coverage")
            appendLine()
            appendLine(
                "| cmd | names | direction | domain | status | request sources | receive sources | captures send/recv |",
            )
            appendLine("|---:|---|---|---|---|---:|---:|---:|")
            registry.all().forEach { contract ->
                val inventory = inventoryById.getValue(contract.id)
                appendLine(
                    "| ${contract.id} | ${contract.names.joinToString(",")} | " +
                        "${contract.direction} | ${contract.domain} | ${contract.status} | " +
                        "${inventory.requestSources.size} | ${inventory.receiveSources.size} | " +
                        "${inventory.captureSendCount}/${inventory.captureReceiveCount} |",
                )
            }
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val output = Path.of(
            args.singleOrNull()
                ?: "build/reports/protocol/command-coverage.md",
        )
        Files.createDirectories(requireNotNull(output.parent))
        Files.writeString(output, render(CommandContractCatalog.registry))
        println("wrote $output")
    }
}
