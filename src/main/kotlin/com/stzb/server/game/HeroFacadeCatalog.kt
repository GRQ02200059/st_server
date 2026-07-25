package com.stzb.server.game

import java.nio.file.Files
import java.nio.file.Path

data class HeroFacadeDefinition(
    val facadeHeroId: Int,
    val baseHeroIds: Set<Int>,
    val bookType: Int,
)

/**
 * Client hero rows with base_hero_id are facade configurations. Ownership is
 * represented by Tb_user_facade_card; achievement facades use the same table.
 */
object HeroFacadeCatalog {
    private val facades: LinkedHashMap<Int, HeroFacadeDefinition> by lazy { load() }

    fun all(): List<HeroFacadeDefinition> = facades.values.toList()

    fun canUse(facadeHeroId: Int, baseHeroId: Int): Boolean =
        facadeHeroId == 0 || baseHeroId in facades[facadeHeroId]?.baseHeroIds.orEmpty()

    private fun load(): LinkedHashMap<Int, HeroFacadeDefinition> {
        HeroFacadeCatalog::class.java.classLoader
            ?.getResourceAsStream("hero_table.csv")
            ?.use { input -> return input.bufferedReader().useLines(::parse) }

        val path = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .map { it.resolve("hero_table.csv") }
            .firstOrNull(Files::exists)
            ?: error("无法定位 hero_table.csv")
        return Files.newBufferedReader(path).useLines(::parse)
    }

    private fun parse(lines: Sequence<String>): LinkedHashMap<Int, HeroFacadeDefinition> =
        lines.drop(1)
            .mapNotNull(::parseRow)
            .associateByTo(LinkedHashMap(), HeroFacadeDefinition::facadeHeroId)

    private fun parseRow(line: String): HeroFacadeDefinition? {
        val columns = line.split(',')
        val facadeId = columns.getOrNull(HERO_ID)?.toIntOrNull() ?: return null
        val baseId = columns.getOrNull(BASE_HERO_ID)?.toIntOrNull() ?: return null
        if (facadeId <= 0 || baseId <= 0 || facadeId == baseId) return null
        val extendedId = columns.getOrNull(FACADE_EX_HERO_ID)?.toIntOrNull()
        val bindings = buildSet {
            add(baseId)
            if (extendedId != null && extendedId > 0) add(extendedId)
        }
        return HeroFacadeDefinition(
            facadeHeroId = facadeId,
            baseHeroIds = bindings,
            bookType = columns.getOrNull(BOOK_TYPE)?.toIntOrNull() ?: 0,
        )
    }

    private const val HERO_ID = 1
    private const val BASE_HERO_ID = 16
    private const val BOOK_TYPE = 20
    private const val FACADE_EX_HERO_ID = 55
}
