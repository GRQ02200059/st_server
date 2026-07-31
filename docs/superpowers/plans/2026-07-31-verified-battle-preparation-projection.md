# Verified Battle Preparation Projection Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the generated pre-battle replay use the verified official preparation envelopes and equipment-feature actions without inventing unknown protocol semantics.

**Architecture:** Keep battle settlement events independent from client replay actions. Extend the existing typed preparation facts produced by `BattleFormationCalculator`, then route round-zero events through a dedicated preparation projection path in `ClientBattleTextReplayAdapter`. Lock the boundary with a deterministic differential test built from the official `paper/11` fixture.

**Tech Stack:** Kotlin 1.9.23, JVM 17, Gradle 8.7, `kotlin.test`, Jackson Kotlin, official JSON reports under `assent/cfg/paper`.

## Global Constraints

- Implement only actions whose semantics and parameter shapes are supported by official reports, client configuration, or decompiled client behavior.
- Do not hard-code the representative battle's heroes, action stream, or calculated values in production code.
- Unknown preparation effects must be diagnosed or rejected in strict mode; they must not be converted to guessed client actions.
- Round one and later must retain the existing battle replay behavior.
- Preserve all unrelated dirty-worktree changes.
- Every production change must follow a red-green TDD cycle.

---

### Task 1: Official preparation effect envelopes

**Files:**
- Modify: `src/main/kotlin/com/stzb/server/game/battle/ClientBattleTextReplayProtocol.kt`
- Modify: `src/main/kotlin/com/stzb/server/game/battle/ClientBattleTextReplayAdapter.kt`
- Test: `src/test/kotlin/com/stzb/server/game/battle/ClientBattleTextReplayAdapterTest.kt`

**Interfaces:**
- Consumes: existing `BattlePreparationSource`, `BattlePreparationEffect`, `BattlePreparationModifier`, and `BattlePreparationAction`.
- Produces: `PREPARATION_EFFECT_BEGIN`, `PREPARATION_EFFECT_END`, and `PREPARATION_EFFECT_BOUNDARY` protocol constants and enveloped source output.

- [ ] **Step 1: Write the failing envelope test**

Add a test that builds one team with a verified `0k` source and one flat attribute effect:

```kotlin
@Test
fun `preparation source effects use official inner envelopes`() {
    val attacker = BattleTeam(
        heroes = listOf(hero(1, 0)),
        preparationSources = listOf(
            BattlePreparationSource(BattlePreparationStage.SYSTEM, 295094),
        ),
        preparationEffects = listOf(
            BattlePreparationEffect(
                stage = BattlePreparationStage.SYSTEM,
                sourceId = 295094,
                targetPosition = 0,
                stat = BattleStat.SPEED,
                strength = 10,
                delta = 10,
                valueAfter = 120,
                percent = false,
            ),
        ),
    )
    val result = result(attacker = attacker)
    val actions = ClientBattleTextReplayAdapter.adapt(result)

    val sourceIndex = actions.indexOf(
        ClientReportAction(ClientBattleTextReplayProtocol.SYSTEM_EFFECT_SOURCE, listOf(0, 295094)),
    )
    assertEquals(
        listOf(
            ClientReportAction(ClientBattleTextReplayProtocol.SYSTEM_EFFECT_SOURCE, listOf(0, 295094)),
            ClientReportAction(ClientBattleTextReplayProtocol.PREPARATION_EFFECT_BEGIN),
            ClientReportAction(
                ClientBattleTextReplayProtocol.FLAT_SPEED,
                listOf(0, 295094, 1, 10, 120),
            ),
            ClientReportAction(ClientBattleTextReplayProtocol.PREPARATION_EFFECT_END),
            ClientReportAction(ClientBattleTextReplayProtocol.PREPARATION_EFFECT_BOUNDARY),
        ),
        actions.subList(sourceIndex, sourceIndex + 5),
    )
}
```

- [ ] **Step 2: Run the focused test and verify RED**

Run:

```bash
./gradlew test --tests '*ClientBattleTextReplayAdapterTest.preparation source effects use official inner envelopes'
```

Expected: compilation or assertion failure because the three protocol constants and envelope actions do not exist.

- [ ] **Step 3: Add the verified protocol constants**

In `ClientBattleTextReplayProtocol` add:

```kotlin
const val PREPARATION_EFFECT_BOUNDARY = 217 // 61
const val PREPARATION_EFFECT_BEGIN = 222    // 66
const val PREPARATION_EFFECT_END = 223      // 67
```

Remove or rename the unused `NORMAL_ATTACK_BEGIN` and `NORMAL_ATTACK_END` aliases so each constant has one preparation meaning.

- [ ] **Step 4: Envelop each preparation source payload**

In `preparationEffects`, build each source segment as:

```kotlin
add(sourceAction)
add(ClientReportAction(ClientBattleTextReplayProtocol.PREPARATION_EFFECT_BEGIN))
addAll(effectActions)
addAll(modifierActions)
addAll(specialActions)
add(ClientReportAction(ClientBattleTextReplayProtocol.PREPARATION_EFFECT_END))
add(ClientReportAction(ClientBattleTextReplayProtocol.PREPARATION_EFFECT_BOUNDARY))
```

Do not add nested envelopes around each individual action. The official unit being represented is one typed preparation source segment.

- [ ] **Step 5: Run adapter tests and verify GREEN**

Run:

```bash
./gradlew test --tests '*ClientBattleTextReplayAdapterTest'
```

Expected: all adapter tests pass after updating existing exact-list expectations for the verified envelopes.

- [ ] **Step 6: Commit the envelope change**

```bash
git add src/main/kotlin/com/stzb/server/game/battle/ClientBattleTextReplayProtocol.kt \
  src/main/kotlin/com/stzb/server/game/battle/ClientBattleTextReplayAdapter.kt \
  src/test/kotlin/com/stzb/server/game/battle/ClientBattleTextReplayAdapterTest.kt
git commit -m "feat(battle-report): add preparation effect envelopes"
```

### Task 2: Equipment feature facts and `8x`

**Files:**
- Modify: `src/main/kotlin/com/stzb/server/game/battle/BattleFormationCalculator.kt`
- Test: `src/test/kotlin/com/stzb/server/game/battle/BattleEquipmentApplierTest.kt`
- Test: `src/test/kotlin/com/stzb/server/game/battle/ClientBattleTextReplayAdapterTest.kt`

**Interfaces:**
- Consumes: `BattleHeroSpec.equipmentIds`, `equipmentFeatureSkillIds`, and `equipmentFeatureSkillLevels`.
- Produces: a typed `BattlePreparationAction` whose encoded shape is `8x position,featureSkillId,position,associatedSkillId,level`.

- [ ] **Step 1: Write the failing equipment-feature fact test**

Add:

```kotlin
@Test
fun `equipment feature skills become sourced derived preparation actions`() {
    val team = calculator.calculate(
        listOf(
            BattleHeroSpec(
                heroId = 100683,
                position = 0,
                troops = 9_700,
                equipmentIds = listOf(1102),
                equipmentSkillIds = listOf(400114),
                equipmentSkillLevels = listOf(6),
                equipmentFeatureSkillIds = listOf(450037),
                equipmentFeatureSkillLevels = listOf(8),
            ),
        ),
    )

    assertEquals(
        BattlePreparationAction(
            stage = BattlePreparationStage.EQUIPMENT,
            sourceId = 450037,
            sourcePosition = 0,
            targetPosition = 0,
            actionId = "8x".toInt(36),
            amountExact = 8.0,
            actionParameter = 400114,
            containerSourceId = 1102,
        ),
        team.preparationActions.single { it.sourceId == 450037 },
    )
}
```

- [ ] **Step 2: Run the focused test and verify RED**

Run:

```bash
./gradlew test --tests '*BattleEquipmentApplierTest.equipment feature skills become sourced derived preparation actions'
```

Expected: failure because `equipmentFeatureSkillIds` are currently ignored.

- [ ] **Step 3: Materialize verified equipment-feature actions**

Add a focused calculator function:

```kotlin
private fun equipmentFeaturePreparationActions(
    specs: List<BattleHeroSpec>,
): List<BattlePreparationAction> =
    specs.flatMap { spec ->
        val equipmentId = spec.equipmentIds.singleOrNull() ?: return@flatMap emptyList()
        val associatedSkillId = spec.equipmentSkillIds.firstOrNull() ?: return@flatMap emptyList()
        spec.equipmentFeatureSkillIds.mapIndexedNotNull { index, featureSkillId ->
            val level = spec.equipmentFeatureSkillLevels.getOrNull(index)
                ?.takeIf { it > 0 }
                ?: return@mapIndexedNotNull null
            BattlePreparationAction(
                stage = BattlePreparationStage.EQUIPMENT,
                sourceId = featureSkillId,
                sourcePosition = spec.position,
                targetPosition = spec.position,
                actionId = "8x".toInt(36),
                amountExact = level.toDouble(),
                actionParameter = associatedSkillId,
                containerSourceId = equipmentId,
            )
        }
    }
```

Append it to `BattleTeam.preparationActions`. Use `singleOrNull()` deliberately: multiple equipment containers require an explicit slot association and must not be guessed.

- [ ] **Step 4: Write and run the failing encoded-shape test**

Add:

```kotlin
@Test
fun `equipment feature action encodes the official 8x shape`() {
    val team = BattleTeam(
        heroes = listOf(hero(1, 0)),
        preparationActions = listOf(
            BattlePreparationAction(
                stage = BattlePreparationStage.EQUIPMENT,
                sourceId = 450037,
                sourcePosition = 0,
                targetPosition = 0,
                actionId = "8x".toInt(36),
                amountExact = 8.0,
                actionParameter = 400114,
                containerSourceId = 1102,
            ),
        ),
    )

    assertTrue(
        ClientBattleTextReplayAdapter.adapt(result(attacker = team)).contains(
            ClientReportAction("8x".toInt(36), listOf(1, 450037, 1, 400114, 8)),
        ),
    )
}
```

Run:

```bash
./gradlew test --tests '*ClientBattleTextReplayAdapterTest.equipment feature action encodes the official 8x shape'
```

Expected before encoder adjustment: failure if the generic action parameter order differs.

- [ ] **Step 5: Make the generic typed action encode `8x` correctly**

Retain the existing `BattlePreparationAction` field order:

```kotlin
sourcePosition, sourceId, targetPosition, actionParameter, amountExact
```

Do not introduce an `8x` raw-string special case.

- [ ] **Step 6: Run equipment and adapter tests**

```bash
./gradlew test \
  --tests '*BattleEquipmentApplierTest' \
  --tests '*ClientBattleTextReplayAdapterTest'
```

Expected: all selected tests pass.

- [ ] **Step 7: Commit the equipment-feature change**

```bash
git add src/main/kotlin/com/stzb/server/game/battle/BattleFormationCalculator.kt \
  src/test/kotlin/com/stzb/server/game/battle/BattleEquipmentApplierTest.kt \
  src/test/kotlin/com/stzb/server/game/battle/ClientBattleTextReplayAdapterTest.kt
git commit -m "feat(battle-report): project verified equipment features"
```

### Task 3: Separate round-zero command projection from battle projection

**Files:**
- Create: `src/main/kotlin/com/stzb/server/game/battle/ClientBattlePreparationEventProjector.kt`
- Modify: `src/main/kotlin/com/stzb/server/game/battle/ClientBattleTextReplayAdapter.kt`
- Test: `src/test/kotlin/com/stzb/server/game/battle/ClientBattlePreparationEventProjectorTest.kt`
- Test: `src/test/kotlin/com/stzb/server/game/battle/ClientBattleTextReplayAdapterTest.kt`

**Interfaces:**
- Consumes: one round-zero `BattleEvent`, strict/safe projection policy, and existing stat/status protocol helpers.
- Produces: `PreparationProjection(actions: List<ClientReportAction>, diagnostic: String?)`.

- [ ] **Step 1: Write the failing command-projection test**

Create:

```kotlin
class ClientBattlePreparationEventProjectorTest {
    private val source = BattleHeroRef(Side.ATTACKER, 0, BattleHeroId(1))
    private val target = BattleHeroRef(Side.DEFENDER, 0, BattleHeroId(2))

    @Test
    fun `round zero damage is diagnosed instead of using battle skill wrappers`() {
        val diagnostics = mutableListOf<String>()
        val projection = ClientBattlePreparationEventProjector.project(
            BattleEvent.SkillDamage(
                round = 0,
                skillId = 200648,
                effectId = 301,
                source = source,
                target = target,
                damage = 358,
                targetTroopsAfter = 9_242,
            ),
            diagnostics::add,
        )

        assertEquals(emptyList(), projection)
        assertTrue(diagnostics.single().contains("round-zero SkillDamage"))
    }
}
```

- [ ] **Step 2: Run the projector test and verify RED**

```bash
./gradlew test --tests '*ClientBattlePreparationEventProjectorTest'
```

Expected: compilation failure because the projector does not exist.

- [ ] **Step 3: Implement the preparation projector**

Create an internal object with this API:

```kotlin
internal object ClientBattlePreparationEventProjector {
    fun project(
        event: BattleEvent,
        diagnostic: (String) -> Unit,
    ): List<ClientReportAction> =
        when (event) {
            is BattleEvent.SkillTriggered -> listOf(preparationSkillTriggered(event))
            is BattleEvent.StatChanged -> listOf(preparationStatChanged(event))
            is BattleEvent.ModifierApplied -> listOf(preparationModifier(event))
            is BattleEvent.StatusApplied -> projectVerifiedPreparationStatus(event, diagnostic)
            is BattleEvent.SkillDamage,
            is BattleEvent.Recovery,
            is BattleEvent.OngoingDamage,
            is BattleEvent.NormalAttack,
            -> {
                diagnostic("Unsupported round-zero ${event::class.simpleName} projection")
                emptyList()
            }
            else -> emptyList()
        }
}
```

Move or expose the existing typed helpers rather than duplicating action-number logic. The projector must never call `skillSegment()`.

- [ ] **Step 4: Write the failing adapter regression**

Build a result containing round-zero command `SkillTriggered` followed by `SkillDamage`, then assert:

```kotlin
val preparation = ClientBattleTextReplayAdapter.adapt(result)
    .takeWhile { it.id != ClientBattleTextReplayProtocol.ROUND }

assertTrue(preparation.none {
    it.id in setOf(
        ClientBattleTextReplayProtocol.SKILL_BEGIN,
        ClientBattleTextReplayProtocol.SKILL_END,
        ClientBattleTextReplayProtocol.SKILL_CAST,
        ClientBattleTextReplayProtocol.SKILL_DAMAGE,
    )
})
assertTrue(preparation.any {
    it.id == ClientBattleTextReplayProtocol.SKILL_TRIGGERED_COMMAND
})
```

- [ ] **Step 5: Route preparation events through the projector**

In `adapt()`:

```kotlin
fun appendPreparationEvent(event: BattleEvent) {
    actions += ClientBattlePreparationEventProjector.project(event, diagnostic)
}
```

Use it for `preparationEvents`; retain the current `appendEvent` only for `battleEvents`.

- [ ] **Step 6: Verify preparation and battle behavior**

```bash
./gradlew test \
  --tests '*ClientBattlePreparationEventProjectorTest' \
  --tests '*ClientBattleTextReplayAdapterTest' \
  --tests '*BattleIntegrationTest'
```

Expected: command preparation contains no battle wrappers, while round-one damage/recovery tests remain green.

- [ ] **Step 7: Commit the projection split**

```bash
git add src/main/kotlin/com/stzb/server/game/battle/ClientBattlePreparationEventProjector.kt \
  src/main/kotlin/com/stzb/server/game/battle/ClientBattleTextReplayAdapter.kt \
  src/test/kotlin/com/stzb/server/game/battle/ClientBattlePreparationEventProjectorTest.kt \
  src/test/kotlin/com/stzb/server/game/battle/ClientBattleTextReplayAdapterTest.kt
git commit -m "refactor(battle-report): separate preparation event projection"
```

### Task 4: Restrict generic `ja` and `8c` preparation projections

**Files:**
- Modify: `src/main/kotlin/com/stzb/server/game/battle/ClientBattlePreparationEventProjector.kt`
- Modify: `src/main/kotlin/com/stzb/server/game/battle/ClientBattleTextReplayProtocol.kt`
- Test: `src/test/kotlin/com/stzb/server/game/battle/ClientBattlePreparationEventProjectorTest.kt`

**Interfaces:**
- Consumes: verified effect/action allow-lists.
- Produces: preparation actions only when the effect has an explicit verified projection.

- [ ] **Step 1: Write failing tests for unknown generic projections**

Add two tests:

```kotlin
@Test
fun `unknown preparation modifier is diagnosed instead of becoming ja`() {
    val diagnostics = mutableListOf<String>()
    val actions = ClientBattlePreparationEventProjector.project(
        BattleEvent.ModifierApplied(0, source, target, 200001, 999, 10, 2),
        diagnostics::add,
    )
    assertEquals(emptyList(), actions)
    assertTrue(diagnostics.single().contains("effect=999"))
}

@Test
fun `unverified derived preparation skill is diagnosed instead of becoming 8c`() {
    val diagnostics = mutableListOf<String>()
    val actions = ClientBattlePreparationEventProjector.project(
        BattleEvent.SkillTriggered(
            round = 0,
            source = source,
            rootSkillId = 200001,
            skillId = 219999,
            trigger = BattleTrigger.BATTLE_COMMAND,
        ),
        diagnostics::add,
    )
    assertEquals(emptyList(), actions)
    assertTrue(diagnostics.single().contains("derived skill=219999"))
}
```

- [ ] **Step 2: Run and verify RED**

```bash
./gradlew test --tests '*ClientBattlePreparationEventProjectorTest'
```

Expected: current generic `ja/8c` behavior makes both tests fail.

- [ ] **Step 3: Add explicit verified predicates**

In the protocol object add:

```kotlin
fun supportsPreparationModifier(effectId: Int): Boolean =
    effectId in setOf(521, 522, 523, 524, 531, 532, 533, 534)

fun supportsDerivedPreparationSkill(skillId: Int): Boolean =
    skillId in 210_000..213_999 || skillId in 450_000..459_999
```

The ranges correspond to verified derived battle-skill and equipment-feature namespaces. Do not expand them when an unknown ID merely has the same parameter width.

- [ ] **Step 4: Gate `ja` and `8c`**

When predicates reject an event:

```kotlin
diagnostic(
    "Unsupported preparation modifier projection: " +
        "skill=${event.skillId} effect=${event.effectId}",
)
return emptyList()
```

For a rejected derived skill, include root ID and derived ID in the diagnostic.

- [ ] **Step 5: Run projector and adapter tests**

```bash
./gradlew test \
  --tests '*ClientBattlePreparationEventProjectorTest' \
  --tests '*ClientBattleTextReplayAdapterTest'
```

Expected: all selected tests pass.

- [ ] **Step 6: Commit the generic-projection restriction**

```bash
git add src/main/kotlin/com/stzb/server/game/battle/ClientBattlePreparationEventProjector.kt \
  src/main/kotlin/com/stzb/server/game/battle/ClientBattleTextReplayProtocol.kt \
  src/test/kotlin/com/stzb/server/game/battle/ClientBattlePreparationEventProjectorTest.kt
git commit -m "fix(battle-report): restrict generic preparation actions"
```

### Task 5: Deterministic official-paper differential gate

**Files:**
- Create: `src/test/kotlin/com/stzb/server/game/battle/OfficialPreparationReportDiffTest.kt`
- Create: `src/test/kotlin/com/stzb/server/game/battle/OfficialReportFixture.kt`
- Test fixture: `assent/cfg/paper/11/cap_20260312014510506_0000000b_zlib.json`

**Interfaces:**
- Consumes: official report JSON and production `ClientReportTextEncoder`.
- Produces: parsed `OfficialAction(id, raw, params)` lists, reconstructed `BattleRequest`, and focused structural assertions.

- [ ] **Step 1: Extract a reusable official fixture parser**

Create:

```kotlin
internal data class OfficialAction(
    val id: Int,
    val raw: String,
    val params: List<String>,
)

internal object OfficialReportFixture {
    fun read(path: Path): List<OfficialAction> {
        val report = jacksonObjectMapper().readTree(path.toFile())[1]["report"].asText()
        return report.split('#')
            .filter(String::isNotBlank)
            .map { raw ->
                OfficialAction(
                    id = raw.take(2).toInt(36),
                    raw = raw,
                    params = raw.drop(2).takeIf(String::isNotEmpty)
                        ?.split(',')
                        ?: emptyList(),
                )
            }
    }

    fun preparation(actions: List<OfficialAction>): List<OfficialAction> =
        actions.takeWhile { it.id != ClientBattleTextReplayProtocol.ROUND }
}
```

- [ ] **Step 2: Write the failing differential test**

The test must reconstruct heroes from:

- `0e`: hero ID by client position;
- `5p`: level, troops, skills, skill levels, troop features, equipment skills and levels;
- `ba`: base equipment ID by position;
- `8x`: equipment feature skill and level by position.

Then assert:

```kotlin
@Test
fun `generated preparation matches verified official structural invariants`() {
    val official = OfficialReportFixture.read(SAMPLE)
    val request = OfficialReportFixture.reconstructBattleRequest(official)
    val result = BattleEngine.resolve(request, config, FixedBattleRandom(0))
    val generated = ClientReportTextEncoder.encode(result)
        .let(OfficialReportFixture::parseText)
        .let(OfficialReportFixture::preparation)

    val generatedIds = generated.map(OfficialAction::id)
    assertTrue(generatedIds.none {
        it in setOf(
            ClientBattleTextReplayProtocol.SKILL_BEGIN,
            ClientBattleTextReplayProtocol.SKILL_END,
            ClientBattleTextReplayProtocol.SKILL_CAST,
            ClientBattleTextReplayProtocol.SKILL_DAMAGE,
        )
    })
    assertTrue(
        setOf(
            ClientBattleTextReplayProtocol.PREPARATION_EFFECT_BOUNDARY,
            ClientBattleTextReplayProtocol.PREPARATION_EFFECT_BEGIN,
            ClientBattleTextReplayProtocol.PREPARATION_EFFECT_END,
        ).all(generatedIds::contains),
    )
    assertTrue(generated.any {
        it.id == "8x".toInt(36) && it.params.any { value -> value.startsWith("45") }
    })
    assertEquals(emptyList(), commonWidthMismatches(official, generated))
}
```

- [ ] **Step 3: Run the differential test and verify RED**

```bash
./gradlew test --tests '*OfficialPreparationReportDiffTest'
```

Expected: failure on at least one structural invariant before Tasks 1–4 are applied; if the test is introduced after those tasks, temporarily revert the relevant production hunk, verify RED, then restore it.

- [ ] **Step 4: Complete the reconstruction helper**

Implement side/position mapping exactly:

```kotlin
private fun formationPosition(clientPosition: Int): Pair<Side, Int> =
    if (clientPosition in 1..3) {
        Side.ATTACKER to (clientPosition - 1)
    } else {
        Side.DEFENDER to (6 - clientPosition)
    }
```

Use `BattleTeamBuilder` and production repositories. Do not duplicate formation calculations in the fixture.

- [ ] **Step 5: Verify deterministic output**

Run twice:

```bash
./gradlew test --tests '*OfficialPreparationReportDiffTest'
./gradlew test --tests '*OfficialPreparationReportDiffTest'
```

Expected: both executions pass with identical assertions.

- [ ] **Step 6: Commit the differential gate**

```bash
git add src/test/kotlin/com/stzb/server/game/battle/OfficialPreparationReportDiffTest.kt \
  src/test/kotlin/com/stzb/server/game/battle/OfficialReportFixture.kt
git commit -m "test(battle-report): add official preparation diff gate"
```

### Task 6: Final verification and documentation refresh

**Files:**
- Modify: `docs/正式战报战前准备缺口-2026-07-30.md`
- Modify: `docs/正版战报战前动作差距审计-2026-07-30.md`

**Interfaces:**
- Consumes: verified test output and the official differential gate.
- Produces: an accurate current-state handoff without claiming unknown actions are supported.

- [ ] **Step 1: Run focused verification**

```bash
./gradlew test \
  --tests '*BattleEquipmentApplierTest' \
  --tests '*ClientBattlePreparationEventProjectorTest' \
  --tests '*ClientBattleTextReplayAdapterTest' \
  --tests '*OfficialPreparationReportDiffTest'
```

Expected: zero failures.

- [ ] **Step 2: Run the complete test suite**

```bash
./gradlew test
```

Expected: the newly added report tests pass. If `CompleteSkillCoverageTest` still fails only on the pre-existing 18 unknown condition codes, report that exact independent failure rather than marking the suite green.

- [ ] **Step 3: Refresh the two audit documents**

Record:

- implemented `61/66/67`;
- removed round-zero use of `5x/5y/8d/1o`;
- connected verified `8x/45xxxx`;
- restricted `ja/8c`;
- remaining unknown or display-only action families;
- the fresh real/generated preparation counts from the deterministic differential test.

- [ ] **Step 4: Check formatting and unintended changes**

```bash
git diff --check
git status --short
git diff --stat
```

Expected: no whitespace errors; only scoped files plus pre-existing user changes appear.

- [ ] **Step 5: Commit documentation**

```bash
git add docs/正式战报战前准备缺口-2026-07-30.md \
  docs/正版战报战前动作差距审计-2026-07-30.md
git commit -m "docs: update verified battle report gaps"
```
