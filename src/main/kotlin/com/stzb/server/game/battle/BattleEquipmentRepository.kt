package com.stzb.server.game.battle

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.nio.file.Path
import kotlin.io.path.exists

data class EquipmentConfig(
    val id: Int,
    val name: String,
    val quality: String,
    val type: String,
    val skillName: String,
    val skillDescription: String,
    val featureGroup: Int,
)

data class EquipmentFeatureConfig(
    val groupId: Int,
    val name: String,
    val description: String,
)

class BattleEquipmentRepository private constructor(
    private val equipment: Map<Int, EquipmentConfig>,
    private val features: Map<Int, List<EquipmentFeatureConfig>>,
) {
    fun equipment(id: Int): EquipmentConfig? = equipment[id]

    fun features(groupId: Int): List<EquipmentFeatureConfig> = features[groupId].orEmpty()

    fun allEquipmentIds(): Set<Int> = equipment.keys

    companion object {
        fun loadDefault(): BattleEquipmentRepository =
            load(resolveProjectRoot())

        fun load(projectRoot: Path): BattleEquipmentRepository {
            val cfgRoot = resolveConfigRoot(projectRoot)
            val equipment = JsonRows.read(cfgRoot.resolve("gear_id.json")).associate { row ->
                val id = row.int("id")
                id to EquipmentConfig(
                    id = id,
                    name = row.string("name"),
                    quality = row.string("quality"),
                    type = row.string("type"),
                    skillName = row.string("skillName"),
                    skillDescription = row.string("skillDesc"),
                    featureGroup = row.int("featureGroup"),
                )
            }
            val features = JsonRows.read(cfgRoot.resolve("gear_feature_extra.json"))
                .flatMap { row ->
                    val groupId = row.int("groupId")
                    val infos = row["featureInfo"] as? List<*> ?: emptyList<Any?>()
                    infos.mapNotNull { item ->
                        val info = item as? Map<*, *> ?: return@mapNotNull null
                        EquipmentFeatureConfig(
                            groupId = groupId,
                            name = info["effectName"]?.toString().orEmpty(),
                            description = info["effectDesc"]?.toString().orEmpty(),
                        )
                    }
                }
                .groupBy { it.groupId }
            return BattleEquipmentRepository(equipment = equipment, features = features)
        }

        private fun resolveProjectRoot(): Path {
            val cwd = Path.of("").toAbsolutePath().normalize()
            return generateSequence(cwd) { it.parent }
                .firstOrNull { root ->
                    CONFIG_PATHS.any { path -> root.resolve(path).exists() }
                }
                ?: error("无法定位项目根目录: $cwd")
        }

        private fun resolveConfigRoot(projectRoot: Path): Path =
            CONFIG_PATHS
                .map(projectRoot::resolve)
                .firstOrNull { it.exists() }
                ?: projectRoot.resolve(CONFIG_PATHS.first())

        private val CONFIG_PATHS = listOf(
            Path.of("assent/cfg"),
            Path.of("server/assent/cfg"),
        )
    }
}

private object JsonRows {
    private val mapper = jacksonObjectMapper()
    private val rowsType = object : TypeReference<List<Map<String, Any?>>>() {}

    fun read(path: Path): List<Map<String, Any?>> {
        if (!path.exists()) return emptyList()
        return mapper.readValue(path.toFile(), rowsType)
    }
}

private fun Map<String, Any?>.string(name: String): String =
    this[name]?.toString().orEmpty()

private fun Map<String, Any?>.int(name: String): Int =
    when (val value = this[name]) {
        is Number -> value.toInt()
        is String -> value.toDoubleOrNull()?.toInt() ?: 0
        else -> 0
    }
