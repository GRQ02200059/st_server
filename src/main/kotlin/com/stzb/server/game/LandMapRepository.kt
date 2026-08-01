package com.stzb.server.game

import com.stzb.server.protocol.GameServerConfig
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
    val mapSize: Int,
    private val resourcesInMap: ByteArray,
    private val resourceEncoding: ResourceEncoding,
) {
    fun tile(wid: Int): LandTileConfig? {
        val x = wid / WID_COORDINATE_BASE
        val y = wid % WID_COORDINATE_BASE
        if (x !in 1..mapSize || y !in 1..mapSize) return null

        val encoded = resourcesInMap[(x - 1) * mapSize + y - 1].toInt() and 0xff
        val resourceType = decodeResourceType(encoded, resourceEncoding) ?: return null
        return LandTileConfig(
            wid = wid,
            resourceType = resourceType,
            level = resourceType / 10,
        )
    }

    companion object {
        private const val WID_COORDINATE_BASE = 10_000
        private const val LEGACY_RESOURCE_CODES =
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWX"
        private val LEGACY_RESOURCE_TYPES = intArrayOf(
            91, 92, 93, 94,
            81, 82, 83, 84,
            71, 72, 73, 74,
            61, 62, 63, 64,
            51, 52, 53, 54,
            41, 42, 43, 44,
            31, 32, 33, 34,
            22, 23, 24, 25,
            11, 12, 18,
            56, 57, 58,
            66, 67, 68,
            76, 77, 78,
            86, 87, 88,
            96, 97, 98,
        )
        private val RESOURCE_ENCODINGS = mapOf(
            5 to ResourceEncoding.LEGACY,
            984 to ResourceEncoding.NEW,
            2001 to ResourceEncoding.NEW,
            2002 to ResourceEncoding.NEW,
        )

        /**
         * Loads the resource-land map for the given login cfgDataIndex.
         *
         * The map size is derived from the decompressed resources_in_map length
         * (mapSize = sqrt(len)) so different seasons with different dimensions
         * (2001=1001, 2002=1201, 5=3001, 984=4001, ...) all load without a
         * hard-coded size. The .mbd on disk is zlib compressed just like the
         * client cache, so it is inflated here.
         */
        fun load(cfgIndex: Int): LandMapRepository {
            val resource = "/map/$cfgIndex/resources_in_map.mbd"
            val resourceEncoding = requireNotNull(RESOURCE_ENCODINGS[cfgIndex]) {
                "missing resource encoding for cfgIndex=$cfgIndex"
            }
            val compressed = requireNotNull(LandMapRepository::class.java.getResourceAsStream(resource)) {
                "missing map resource $resource"
            }
            val decoded = compressed.use { InflaterInputStream(it).readBytes() }
            val mapSize = Math.sqrt(decoded.size.toDouble()).toInt()
            require(mapSize * mapSize == decoded.size) {
                "resources_in_map for cfgIndex=$cfgIndex is not square: ${decoded.size}"
            }
            return LandMapRepository(mapSize, decoded, resourceEncoding)
        }

        /** Loads the resource map advertised to the active client. */
        fun loadDefault(): LandMapRepository = load(GameServerConfig.CFG_DB_ID)

        private fun decodeResourceType(
            encoded: Int,
            resourceEncoding: ResourceEncoding,
        ): Int? {
            if (resourceEncoding == ResourceEncoding.LEGACY) {
                val index = LEGACY_RESOURCE_CODES.indexOf(encoded.toChar())
                return LEGACY_RESOURCE_TYPES.getOrNull(index)
            }

            val resourceType = if (encoded >= 100) {
                val decoded = (encoded - 100) / 16 * 10 + (encoded - 100) % 16 / 4 + 11
                if (decoded / 10 == 2) decoded + 1 else decoded
            } else {
                encoded
            }
            return resourceType.takeIf { it / 10 in 1..9 }
        }
    }

    private enum class ResourceEncoding {
        LEGACY,
        NEW,
    }
}
