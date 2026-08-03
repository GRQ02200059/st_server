package com.stzb.server.game

data class InventoryFeatureTier(
    val advance: Int,
    val levelType: Int,
    val level: Int,
)

data class InventoryGearDefinition(
    val uid: Int,
    val gearId: Int,
    val featureId: Int,
    val featureTier: InventoryFeatureTier,
    val phase: Int,
    val isSeason: Int,
    val skill: String,
    val featureSkillIds: List<Int> = emptyList(),
    val featureSkillLevels: List<Int> = emptyList(),
)

data class InventoryItemDefinition(
    val id: Int,
    val itemId: Int,
    val repoType: Int,
)

/**
 * Battle-facing view of a granted gear: the equipment id (its base skills are
 * resolved by the formation calculator) plus the hongji feature skill loadout.
 */
data class BattleGearLoadout(
    val equipmentIds: List<Int>,
    val equipmentFeatureSkillIds: List<Int>,
    val equipmentFeatureSkillLevels: List<Int>,
)

/**
 * Produces the deterministic starter inventory from exactly the configuration
 * tables bundled with the server. No client-side injection is required.
 */
object InventoryCatalog {
    private val inventory: GeneratedInventory by lazy(::load)

    fun normalWeapons(): List<InventoryGearDefinition> = inventory.baseWeapons

    fun hongjiCopies(): List<InventoryGearDefinition> = inventory.hongjiCopies

    fun items(): List<InventoryItemDefinition> = inventory.items

    fun isGrantedGearUid(gearUid: Int): Boolean =
        gearUid > 0 && gearUid in inventory.grantedGearUids

    /**
     * Resolves a granted gear uid into the battle loadout fields consumed by
     * [BattleHeroSpec]. Base equipment skills are auto-filled by the formation
     * calculator from [BattleGearLoadout.equipmentIds]; the hongji feature
     * skills must be supplied explicitly, mirroring the NPC defender path.
     * Returns null for any uid the server did not grant.
     */
    fun battleLoadoutForGearUid(gearUid: Int): BattleGearLoadout? {
        val gear = inventory.grantedGearByUid[gearUid] ?: return null
        return BattleGearLoadout(
            equipmentIds = listOf(gear.gearId),
            equipmentFeatureSkillIds = gear.featureSkillIds,
            equipmentFeatureSkillLevels = gear.featureSkillLevels,
        )
    }

    private fun load(): GeneratedInventory {
        val gears = parseGears(readResource(GEAR_RESOURCE))
            .filter(StoredGear::isEligible)
            .sortedBy(StoredGear::id)
        require(gears.isNotEmpty()) { "没有可用的武器配置" }

        val hongjiByType = parseFeatures(readResource(FEATURE_RESOURCE))
            .asSequence()
            .filter { feature -> feature.tier.advance == HONGJI_ADVANCE && feature.isRenderable }
            .groupBy(StoredFeature::gearType)
            .mapValues { (_, features) -> features.maxWithOrNull(FEATURE_COMPARATOR)!! }
        val globalHongji = hongjiByType.values.maxWithOrNull(FEATURE_COMPARATOR)
            ?: error("没有可用的鸿级词条配置")

        val baseWeapons = gears.map { gear ->
            val feature = hongjiByType[gear.type] ?: globalHongji
            InventoryGearDefinition(
                uid = BASE_GEAR_UID + gear.id,
                gearId = gear.id,
                featureId = feature.id,
                featureTier = feature.tier,
                phase = gear.phase,
                isSeason = gear.isSeason,
                skill = gear.skill,
                featureSkillIds = feature.skillIds,
                featureSkillLevels = feature.skillLevels,
            )
        }

        val hongjiBody = gears.firstOrNull { it.type == globalHongji.gearType } ?: gears.first()
        val hongjiCopies = (1..HONGJI_COPY_COUNT).map { copyIndex ->
            InventoryGearDefinition(
                uid = HONGJI_GEAR_UID + copyIndex,
                gearId = hongjiBody.id,
                featureId = globalHongji.id,
                featureTier = globalHongji.tier,
                phase = hongjiBody.phase,
                isSeason = hongjiBody.isSeason,
                skill = hongjiBody.skill,
                featureSkillIds = globalHongji.skillIds,
                featureSkillLevels = globalHongji.skillLevels,
            )
        }

        val items = parseItems(readResource(ITEM_RESOURCE))
            .sortedBy(StoredItem::id)
            .mapIndexed { index, item ->
                InventoryItemDefinition(
                    id = ITEM_UID + index + 1,
                    itemId = item.id,
                    repoType = item.repoType,
                )
            }

        return GeneratedInventory(baseWeapons, hongjiCopies, items)
    }

    private fun parseGears(bytes: ByteArray): List<StoredGear> {
        val table = MemoryPackTable.open(bytes, GEAR_RESOURCE)
        return buildList(table.keys.size) {
            table.keys.forEach {
                require(table.reader.byte().toInt() and 0xff == GEAR_OBJECT_FIELDS) {
                    "无效的 $GEAR_RESOURCE 行"
                }
                val id = table.reader.int()
                val type = table.reader.int()
                table.reader.int() // produce_type
                table.reader.int() // prefect_gear_id
                val phase = table.reader.int()
                table.reader.int() // cost_time
                table.reader.int() // gear_kind
                table.reader.int() // icon_gear_id
                val isSeason = table.reader.byte().toInt()
                val isDefective = table.reader.byte().toInt()
                val tag = table.reader.byte().toInt()
                val strings = List(GEAR_STRING_FIELDS) {
                    table.string(table.reader.int()).orEmpty()
                }
                add(
                    StoredGear(
                        id = id,
                        type = type,
                        phase = phase.coerceAtLeast(1),
                        isSeason = isSeason,
                        isDefective = isDefective,
                        tag = tag,
                        skill = strings[SKILL_STRING_INDEX].ifBlank { strings[POLICY_STRING_INDEX] },
                    ),
                )
            }
        }
    }

    private fun parseFeatures(bytes: ByteArray): List<StoredFeature> {
        val table = MemoryPackTable.open(bytes, FEATURE_RESOURCE)
        return buildList(table.keys.size) {
            table.keys.forEach {
                require(table.reader.byte().toInt() and 0xff == FEATURE_OBJECT_FIELDS) {
                    "无效的 $FEATURE_RESOURCE 行"
                }
                val id = table.reader.int()
                val gearType = table.reader.int()
                val level = table.reader.int()
                val levelType = table.reader.int()
                val advance = table.reader.int()
                table.reader.int() // visible
                table.reader.int() // feature_type
                table.reader.int() // seven_feature_id
                val skill = table.string(table.reader.int()).orEmpty()
                table.reader.int() // desc
                val policy = table.string(table.reader.int()).orEmpty()
                val skillSlots = parseIdLevels(skill)
                add(
                    StoredFeature(
                        id = id,
                        gearType = gearType,
                        tier = InventoryFeatureTier(advance, levelType, level),
                        isRenderable = skill.isNotEmpty() || policy.isNotEmpty(),
                        skillIds = skillSlots.map(Pair<Int, Int>::first),
                        skillLevels = skillSlots.map(Pair<Int, Int>::second),
                    ),
                )
            }
        }
    }

    private fun parseItems(bytes: ByteArray): List<StoredItem> {
        val table = MemoryPackTable.open(bytes, ITEM_RESOURCE)
        return buildList(table.keys.size) {
            table.keys.forEach {
                require(table.reader.byte().toInt() and 0xff == ITEM_OBJECT_FIELDS) {
                    "无效的 $ITEM_RESOURCE 行"
                }
                val id = table.reader.int()
                val repoType = table.reader.int()
                repeat(11) { table.reader.int() }
                repeat(ITEM_STRING_FIELDS) { table.reader.int() }
                add(StoredItem(id, repoType))
            }
        }
    }

    private fun readResource(name: String): ByteArray =
        InventoryCatalog::class.java.getResourceAsStream("/client-config/$name")
            ?.use { it.readBytes() }
            ?: error("缺少库存配置: /client-config/$name")

    private data class GeneratedInventory(
        val baseWeapons: List<InventoryGearDefinition>,
        val hongjiCopies: List<InventoryGearDefinition>,
        val items: List<InventoryItemDefinition>,
    ) {
        val grantedGearUids: Set<Int> =
            (baseWeapons + hongjiCopies).map(InventoryGearDefinition::uid).toSet()

        val grantedGearByUid: Map<Int, InventoryGearDefinition> =
            (baseWeapons + hongjiCopies).associateBy(InventoryGearDefinition::uid)
    }

    private fun parseIdLevels(value: String): List<Pair<Int, Int>> =
        value.split(';')
            .mapNotNull { item ->
                val parts = item.split(',')
                val id = parts.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
                val level = parts.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null
                (id to level).takeIf { id > 0 && level > 0 }
            }

    private data class StoredGear(
        val id: Int,
        val type: Int,
        val phase: Int,
        val isSeason: Int,
        val isDefective: Int,
        val tag: Int,
        val skill: String,
    ) {
        fun isEligible(): Boolean =
            id > 0 && type > 0 && isDefective == 0 && tag == 0
    }

    private data class StoredFeature(
        val id: Int,
        val gearType: Int,
        val tier: InventoryFeatureTier,
        val isRenderable: Boolean,
        val skillIds: List<Int> = emptyList(),
        val skillLevels: List<Int> = emptyList(),
    )

    private data class StoredItem(
        val id: Int,
        val repoType: Int,
    )

    private val FEATURE_COMPARATOR =
        compareBy<StoredFeature>({ it.tier.level }, { -it.id })

    private const val GEAR_RESOURCE = "tb_cfg_gear.bin"
    private const val FEATURE_RESOURCE = "tb_cfg_gear_feature.bin"
    private const val ITEM_RESOURCE = "tb_cfg_item.bin"

    private const val GEAR_OBJECT_FIELDS = 22
    private const val GEAR_STRING_FIELDS = 11
    private const val SKILL_STRING_INDEX = 2
    private const val POLICY_STRING_INDEX = 10

    private const val FEATURE_OBJECT_FIELDS = 11
    private const val ITEM_OBJECT_FIELDS = 22
    private const val ITEM_STRING_FIELDS = 9

    private const val HONGJI_ADVANCE = 1
    private const val BASE_GEAR_UID = 800_000_000
    private const val HONGJI_GEAR_UID = 840_100_000
    private const val ITEM_UID = 1_900_000_000
    private const val HONGJI_COPY_COUNT = 50
}
