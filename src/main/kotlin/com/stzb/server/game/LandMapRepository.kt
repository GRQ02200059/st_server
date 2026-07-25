package com.stzb.server.game

import java.util.zip.InflaterInputStream

data class LandTileConfig(
    val wid: Int,
    val resourceType: Int,
    val level: Int,
)

/**
 * Resource-land configuration for the map selected by login cfgDataIndex.
 *
 * This follows the client MapResCommon.GetResourceLevel lookup:
 *   index = (x - 1) * mapSize + y - 1
 * The wid is only decoded into coordinates; no digit of it represents level.
 */
class LandMapRepository private constructor(
    private val mapSize: Int,
    private val resourcesInMap: ByteArray,
) {
    fun tile(wid: Int): LandTileConfig? {
        val x = wid / WID_COORDINATE_BASE
        val y = wid % WID_COORDINATE_BASE
        if (x !in 1..mapSize || y !in 1..mapSize) return null

        val encoded = resourcesInMap[(x - 1) * mapSize + y - 1].toInt() and 0xff
        val resourceType = decodeResourceType(encoded) ?: return null
        return LandTileConfig(
            wid = wid,
            resourceType = resourceType,
            level = resourceType / 10,
        )
    }

    companion object {
        private const val WID_COORDINATE_BASE = 10_000
        private const val DEFAULT_MAP_SIZE = 1_001
        private const val DEFAULT_RESOURCE = "/map/2001/resources_in_map.mbd"

        fun loadDefault(): LandMapRepository {
            val compressed = requireNotNull(LandMapRepository::class.java.getResourceAsStream(DEFAULT_RESOURCE)) {
                "missing map resource $DEFAULT_RESOURCE"
            }
            val decoded = compressed.use { InflaterInputStream(it).readBytes() }
            require(decoded.size == DEFAULT_MAP_SIZE * DEFAULT_MAP_SIZE) {
                "invalid resources_in_map size: ${decoded.size}"
            }
            return LandMapRepository(DEFAULT_MAP_SIZE, decoded)
        }

        private fun decodeResourceType(encoded: Int): Int? {
            if (encoded >= 100) {
                val decoded = (encoded - 100) / 16 * 10 + (encoded - 100) % 16 / 4 + 11
                return if (decoded / 10 == 2) decoded + 1 else decoded
            }
            return encoded.takeIf { it / 10 in 1..9 }
        }
    }
}
