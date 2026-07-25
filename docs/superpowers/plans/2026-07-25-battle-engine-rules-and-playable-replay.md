# Battle Engine Rules and Playable Replay Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Correct the high-confidence battle rules proven by client configuration and real reports, then encode the resulting events as playable client animation segments.

**Architecture:** `BattleEngine` remains authoritative for turn order, targeting, combat events, and troop state. `ClientBattleTextReplayAdapter` remains a pure adapter from `BattleResult.events` to client records; it gains the begin/end records required by the client's `BattleAnimationData` state machine.

**Tech Stack:** Kotlin 1.9.23, JVM 17, Gradle 8.7, `kotlin.test`, client decompiled C#, and reference reports from `assent/cfg/paper.zip`.

## Global Constraints

- Correct only attack distance, nearest-target selection, active-skill/normal-attack sequencing, and `CONFUSION`/`HESITATION`/`DISARM` semantics.
- Do not alter normal-attack damage, skill-damage, recovery, growth, or outcome formulas.
- Do not add rampage, taunt, siege-control, equipment animation, or season-specific behavior.
- Preserve `BattleResult.events` order in client replay output.
- Preserve pre-existing uncommitted changes in `ClientBattleReportStore.kt` and `ClientBattleReportStoreTest.kt`.
- Use TDD: every production behavior change must be preceded by a test that fails for the expected reason.

---

## File Structure

- Modify `src/main/kotlin/com/stzb/server/game/battle/BattleEngine.kt`
  - Correct formation distance, target selection, action phases, and control gates.
- Modify `src/test/kotlin/com/stzb/server/game/battle/BattleEngineTest.kt`
  - Lock down formation distance and nearest-target behavior.
- Modify `src/test/kotlin/com/stzb/server/game/battle/BattleEngineSkillTest.kt`
  - Lock down active skill followed by normal attack, including preparation.
- Modify `src/test/kotlin/com/stzb/server/game/battle/BattleEnginePlayableTest.kt`
  - Lock down independent control-state semantics.
- Modify `src/main/kotlin/com/stzb/server/game/battle/ClientBattleTextReplayProtocol.kt`
  - Declare client animation segment IDs.
- Modify `src/main/kotlin/com/stzb/server/game/battle/ClientBattleTextReplayAdapter.kt`
  - Wrap engine events in normal-attack and skill segments.
- Modify `src/test/kotlin/com/stzb/server/game/battle/ClientBattleTextReplayAdapterTest.kt`
  - Verify both sides produce closed playable segments.
- Create `src/test/kotlin/com/stzb/server/game/battle/ClientBattleAnimationQueueContractTest.kt`
  - Mirror the client animation queue state machine.
- Modify `src/test/kotlin/com/stzb/server/game/battle/ClientBattleTextReplayProtocolTest.kt`
  - Cross-check segment IDs against `paper.zip`.

---

### Task 1: Correct formation distance and nearest-target selection

**Files:**
- Modify: `src/main/kotlin/com/stzb/server/game/battle/BattleEngine.kt`
- Modify: `src/test/kotlin/com/stzb/server/game/battle/BattleEngineTest.kt`

**Interfaces:**
- Produces: private `formationDistance(sourcePos: Int, targetPos: Int): Int`.
- Consumes: formation positions `0 = base`, `1 = middle`, `2 = front`.

- [ ] **Step 1: Replace the old range test with explicit formation cases**

Add tests covering front-to-front, base-to-base, and target priority:

```kotlin
@Test
fun `front heroes are distance one apart`() {
    val result = resolveOneRound(
        attacker = listOf(hero(pos = 2, hitRange = 1, speed = 100, attack = 100, defense = 0, troops = 100)),
        defender = listOf(hero(pos = 2, speed = 10, attack = 10, defense = 0, troops = 100)),
    )

    assertTrue(result.events.any { it is BattleEvent.NormalAttack && it.source.side == Side.ATTACKER })
}

@Test
fun `base heroes need range five to hit each other`() {
    val shortRange = resolveOneRound(
        attacker = listOf(hero(pos = 0, hitRange = 4, speed = 100, attack = 100, defense = 0, troops = 100)),
        defender = listOf(hero(pos = 0, speed = 10, attack = 10, defense = 0, troops = 100)),
    )
    val fullRange = resolveOneRound(
        attacker = listOf(hero(pos = 0, hitRange = 5, speed = 100, attack = 100, defense = 0, troops = 100)),
        defender = listOf(hero(pos = 0, speed = 10, attack = 10, defense = 0, troops = 100)),
    )

    assertTrue(shortRange.events.none { it is BattleEvent.NormalAttack && it.source.side == Side.ATTACKER })
    assertTrue(fullRange.events.any { it is BattleEvent.NormalAttack && it.source.side == Side.ATTACKER })
}

@Test
fun `normal attack selects nearest enemy front first`() {
    val result = resolveOneRound(
        attacker = listOf(hero(pos = 2, hitRange = 5, speed = 100, attack = 10, defense = 0, troops = 100)),
        defender = listOf(
            hero(pos = 0, speed = 10, attack = 1, defense = 0, troops = 100),
            hero(pos = 2, speed = 9, attack = 1, defense = 0, troops = 100),
        ),
    )

    val firstAttack = result.events.filterIsInstance<BattleEvent.NormalAttack>()
        .first { it.source.side == Side.ATTACKER }
    assertEquals(2, firstAttack.target.position)
}
```

Add the local helper:

```kotlin
private fun resolveOneRound(
    attacker: List<BattleHero>,
    defender: List<BattleHero>,
): BattleResult = BattleEngine.resolve(
    BattleRequest(BattleTeam(attacker), BattleTeam(defender), maxRounds = 1),
)
```

- [ ] **Step 2: Run the tests and verify RED**

Run:

```bash
./gradlew test \
  --tests com.stzb.server.game.battle.BattleEngineTest \
  --no-daemon -Dkotlin.compiler.execution.strategy=in-process
```

Expected: front-to-front and target-priority assertions fail under the old `targetPos - sourcePos + 1` formula.

- [ ] **Step 3: Implement formation distance and nearest selection**

Change target selection to:

```kotlin
return enemies.values
    .filter { it.troops > 0 }
    .map { target -> target to formationDistance(actor.position, target.position) }
    .filter { (_, distance) -> distance <= actor.stats.hitRange }
    .minWithOrNull(compareBy<Pair<BattleHero, Int>> { it.second }.thenByDescending { it.first.position })
    ?.first
    ?.ref(actorRef.side.opposite())
```

Replace `isInRange` with:

```kotlin
private fun formationDistance(sourcePos: Int, targetPos: Int): Int =
    5 - sourcePos - targetPos
```

- [ ] **Step 4: Run `BattleEngineTest` and verify GREEN**

Run the Task 1 test command again.

Expected: all `BattleEngineTest` tests pass.

- [ ] **Step 5: Commit**

```bash
git add \
  src/main/kotlin/com/stzb/server/game/battle/BattleEngine.kt \
  src/test/kotlin/com/stzb/server/game/battle/BattleEngineTest.kt
git commit -m "fix: correct battle formation attack range"
```

---

### Task 2: Make active skills precede rather than replace normal attacks

**Files:**
- Modify: `src/main/kotlin/com/stzb/server/game/battle/BattleEngine.kt`
- Modify: `src/test/kotlin/com/stzb/server/game/battle/BattleEngineSkillTest.kt`

**Interfaces:**
- Consumes: `BattleSkillRuntime.tryAct` results.
- Produces: one actor turn containing optional active skill, optional normal attack, then optional pursuit skill.

- [ ] **Step 1: Strengthen the active-skill test**

Replace the existing assertion with:

```kotlin
val skillIndex = result.events.indexOfFirst {
    it is BattleEvent.SkillDamage && it.source.heroId == BattleHeroId(100479)
}
val attackIndex = result.events.indexOfFirst {
    it is BattleEvent.NormalAttack && it.source.heroId == BattleHeroId(100479)
}

assertTrue(skillIndex >= 0)
assertTrue(attackIndex > skillIndex)
```

Add a preparation test using skill `200031`:

```kotlin
@Test
fun `preparing an active skill does not consume the normal attack`() {
    val attacker = BattleTeam(
        listOf(hero(100017, 2, 100, 50, 120, 100, skillIds = listOf(200031))),
    )
    val defender = BattleTeam(
        listOf(hero(1, 2, 10, 20, 10, 10)),
    )

    val result = BattleEngine.resolve(
        BattleRequest(attacker, defender, maxRounds = 1),
        repo,
        FixedBattleRandom(0),
    )

    assertTrue(result.events.none { it is BattleEvent.SkillDamage })
    assertTrue(result.events.any {
        it is BattleEvent.NormalAttack && it.source.heroId == BattleHeroId(100017)
    })
}
```

- [ ] **Step 2: Run the two tests and verify RED**

Run:

```bash
./gradlew test \
  --tests com.stzb.server.game.battle.BattleEngineSkillTest \
  --no-daemon -Dkotlin.compiler.execution.strategy=in-process
```

Expected: active-skill actors have no normal attack under the current mutually exclusive branch.

- [ ] **Step 3: Split each actor turn into phases**

Inside the non-confused actor block:

```kotlin
val effectiveActor = actor.withEffectiveStats(actorStatuses)
if (!actorStatuses.has(BattleStatus.HESITATION)) {
    val active = tryCastSkill(
        round, actorRef, effectiveActor, attacker, defender, statuses,
        skillRuntime, runtimeState, random, setOf(SkillKind.ACTIVE),
    )
    if (active != null) {
        applySkillCastResult(actorRef, active, attacker, defender, statuses, events, round)
    }
}

if (!actorStatuses.has(BattleStatus.DISARM)) {
    performNormalAttackAndPursuit(
        round, actorRef, attacker, defender, statuses,
        skillRuntime, runtimeState, random, events,
    )
}
```

Extract the current target, evade, normal-damage, and pursuit block into:

```kotlin
private fun performNormalAttackAndPursuit(
    round: Int,
    actorRef: BattleHeroRef,
    attacker: MutableMap<Int, BattleHero>,
    defender: MutableMap<Int, BattleHero>,
    statuses: MutableMap<BattleHeroRef, MutableList<ActiveBattleStatus>>,
    skillRuntime: BattleSkillRuntime?,
    runtimeState: SkillRuntimeState?,
    random: BattleRandom?,
    events: MutableList<BattleEvent>,
)
```

Add:

```kotlin
private fun List<ActiveBattleStatus>.has(status: BattleStatus): Boolean =
    any { it.status == status }
```

- [ ] **Step 4: Run engine skill tests and verify GREEN**

Run the Task 2 command again.

Expected: active and preparing skills are followed by normal attacks when in range.

- [ ] **Step 5: Commit**

```bash
git add \
  src/main/kotlin/com/stzb/server/game/battle/BattleEngine.kt \
  src/test/kotlin/com/stzb/server/game/battle/BattleEngineSkillTest.kt
git commit -m "fix: preserve normal attacks after active skills"
```

---

### Task 3: Separate confusion, hesitation, and disarm semantics

**Files:**
- Modify: `src/main/kotlin/com/stzb/server/game/battle/BattleEngine.kt`
- Modify: `src/test/kotlin/com/stzb/server/game/battle/BattleEnginePlayableTest.kt`

**Interfaces:**
- Consumes: runtime `ActiveBattleStatus` values.
- Produces: independent active-skill and normal-attack gates.

- [ ] **Step 1: Replace the combined control test with three explicit tests**

Add assertions:

```kotlin
@Test
fun `hesitation blocks active skill but allows normal attack`() {
    val result = controlledResult(BattleStatus.HESITATION, skillIds = listOf(200070))

    assertTrue(result.events.none {
        it is BattleEvent.SkillDamage && it.source.side == Side.ATTACKER
    })
    assertTrue(result.events.any {
        it is BattleEvent.NormalAttack && it.source.side == Side.ATTACKER
    })
}

@Test
fun `disarm blocks normal attack but allows active skill`() {
    val result = controlledResult(BattleStatus.DISARM, skillIds = listOf(200070))

    assertTrue(result.events.any {
        it is BattleEvent.SkillDamage && it.source.side == Side.ATTACKER
    })
    assertTrue(result.events.none {
        it is BattleEvent.NormalAttack && it.source.side == Side.ATTACKER
    })
}

@Test
fun `confusion blocks both active skill and normal attack`() {
    val result = controlledResult(BattleStatus.CONFUSION, skillIds = listOf(200070))

    assertTrue(result.events.none {
        (it is BattleEvent.SkillDamage || it is BattleEvent.NormalAttack) &&
            when (it) {
                is BattleEvent.SkillDamage -> it.source.side == Side.ATTACKER
                is BattleEvent.NormalAttack -> it.source.side == Side.ATTACKER
                else -> false
            }
    })
}
```

Add:

```kotlin
private fun controlledResult(
    status: BattleStatus,
    skillIds: List<Int>,
): BattleResult = BattleEngine.resolve(
    BattleRequest(
        attacker = BattleTeam(
            listOf(hero(100036, 2, attack = 500, skillIds = skillIds, statuses = setOf(status))),
        ),
        defender = BattleTeam(listOf(hero(1, 2))),
        maxRounds = 1,
    ),
    repo,
    FixedBattleRandom(0),
)
```

- [ ] **Step 2: Run the control tests and verify RED**

Run:

```bash
./gradlew test \
  --tests com.stzb.server.game.battle.BattleEnginePlayableTest \
  --no-daemon -Dkotlin.compiler.execution.strategy=in-process
```

Expected: hesitation currently blocks normal attacks and disarm currently blocks active skills.

- [ ] **Step 3: Replace the combined skip predicate**

Remove:

```kotlin
private fun BattleHero.shouldSkipAction(...)
```

Gate the whole actor turn only with:

```kotlin
if (!actorStatuses.has(BattleStatus.CONFUSION)) {
    // active phase and normal phase from Task 2
}
```

Keep the phase-specific `HESITATION` and `DISARM` checks from Task 2.

- [ ] **Step 4: Run all battle-engine tests**

Run:

```bash
./gradlew test \
  --tests 'com.stzb.server.game.battle.BattleEngine*' \
  --no-daemon -Dkotlin.compiler.execution.strategy=in-process
```

Expected: all engine tests pass.

- [ ] **Step 5: Commit**

```bash
git add \
  src/main/kotlin/com/stzb/server/game/battle/BattleEngine.kt \
  src/test/kotlin/com/stzb/server/game/battle/BattleEnginePlayableTest.kt
git commit -m "fix: separate battle control status semantics"
```

---

### Task 4: Define client animation segment protocol

**Files:**
- Modify: `src/main/kotlin/com/stzb/server/game/battle/ClientBattleTextReplayProtocol.kt`
- Modify: `src/test/kotlin/com/stzb/server/game/battle/ClientBattleTextReplayProtocolTest.kt`

**Interfaces:**
- Produces:
  - `NORMAL_ATTACK_BEGIN = 222`
  - `NORMAL_ATTACK_END = 223`
  - `SKILL_BEGIN = 213`
  - `SKILL_END = 214`

- [ ] **Step 1: Extend the real-report protocol test**

Add:

```kotlin
assertTrue(
    setOf(
        ClientBattleTextReplayProtocol.NORMAL_ATTACK_BEGIN,
        ClientBattleTextReplayProtocol.NORMAL_ATTACK_END,
        ClientBattleTextReplayProtocol.SKILL_BEGIN,
        ClientBattleTextReplayProtocol.SKILL_END,
    ).all(ids::contains),
)
```

- [ ] **Step 2: Run and verify RED**

Run:

```bash
./gradlew test \
  --tests com.stzb.server.game.battle.ClientBattleTextReplayProtocolTest \
  --no-daemon -Dkotlin.compiler.execution.strategy=in-process
```

Expected: compilation fails because the four constants are absent.

- [ ] **Step 3: Add the four constants**

Add the exact values listed in this task's interface to `ClientBattleTextReplayProtocol`.

- [ ] **Step 4: Run and verify GREEN**

Run the Task 4 command again.

Expected: the real `paper.zip` report contains every segment marker.

- [ ] **Step 5: Commit**

```bash
git add \
  src/main/kotlin/com/stzb/server/game/battle/ClientBattleTextReplayProtocol.kt \
  src/test/kotlin/com/stzb/server/game/battle/ClientBattleTextReplayProtocolTest.kt
git commit -m "feat: define client battle animation segments"
```

---

### Task 5: Encode playable normal-attack and skill segments

**Files:**
- Modify: `src/main/kotlin/com/stzb/server/game/battle/ClientBattleTextReplayAdapter.kt`
- Modify: `src/test/kotlin/com/stzb/server/game/battle/ClientBattleTextReplayAdapterTest.kt`

**Interfaces:**
- Produces:
  - `normalAttack(event): List<ClientReportAction>`
  - `skillSegment(source, skillId, effects): List<ClientReportAction>`

- [ ] **Step 1: Add exact sequence tests for both sides**

For attacker and defender normal attacks, assert:

```kotlin
listOf(
    ClientReportAction(ClientBattleTextReplayProtocol.NORMAL_ATTACK_BEGIN),
    ClientReportAction(ClientBattleTextReplayProtocol.NORMAL_DAMAGE, listOf(4, 1, 0, 120, 880)),
    ClientReportAction(ClientBattleTextReplayProtocol.NORMAL_ATTACK_END),
    ClientReportAction(ClientBattleTextReplayProtocol.NORMAL_ATTACK_BEGIN),
    ClientReportAction(ClientBattleTextReplayProtocol.NORMAL_DAMAGE, listOf(1, 4, 0, 90, 910)),
    ClientReportAction(ClientBattleTextReplayProtocol.NORMAL_ATTACK_END),
)
```

For each attributed skill effect, assert the sequence begins with:

```kotlin
ClientReportAction(ClientBattleTextReplayProtocol.SKILL_BEGIN)
ClientReportAction(ClientBattleTextReplayProtocol.SKILL_CAST, listOf(sourcePosition, sourcePosition, skillId))
```

and ends with:

```kotlin
ClientReportAction(ClientBattleTextReplayProtocol.SKILL_END)
```

- [ ] **Step 2: Run adapter tests and verify RED**

Run:

```bash
./gradlew test \
  --tests com.stzb.server.game.battle.ClientBattleTextReplayAdapterTest \
  --no-daemon -Dkotlin.compiler.execution.strategy=in-process
```

Expected: current output contains effect actions without segment markers.

- [ ] **Step 3: Implement normal and skill helpers**

Normal attack:

```kotlin
private fun normalAttack(event: BattleEvent.NormalAttack): List<ClientReportAction> =
    listOf(
        ClientReportAction(ClientBattleTextReplayProtocol.NORMAL_ATTACK_BEGIN),
        ClientReportAction(
            ClientBattleTextReplayProtocol.NORMAL_DAMAGE,
            listOf(
                ClientBattleTextReplayProtocol.position(event.target),
                ClientBattleTextReplayProtocol.position(event.source),
                0,
                event.damage,
                event.targetTroopsAfter,
            ),
        ),
        ClientReportAction(ClientBattleTextReplayProtocol.NORMAL_ATTACK_END),
    )
```

Skill segment:

```kotlin
private fun skillSegment(
    source: BattleHeroRef,
    skillId: Int,
    effects: List<ClientReportAction>,
): List<ClientReportAction> =
    if (skillId <= 0 || effects.isEmpty()) emptyList() else buildList {
        add(ClientReportAction(ClientBattleTextReplayProtocol.SKILL_BEGIN))
        addAll(skillCast(source, skillId))
        addAll(effects)
        add(ClientReportAction(ClientBattleTextReplayProtocol.SKILL_END))
    }
```

Route `SkillDamage`, attributed `Recovery`, `StatusApplied`, `OngoingDamage`, and supported `StatChanged` through `skillSegment`. Keep unattributed effects omitted.

- [ ] **Step 4: Run adapter tests and verify GREEN**

Run the Task 5 command again.

Expected: all adapter tests pass with paired segments for both sides.

- [ ] **Step 5: Commit**

```bash
git add \
  src/main/kotlin/com/stzb/server/game/battle/ClientBattleTextReplayAdapter.kt \
  src/test/kotlin/com/stzb/server/game/battle/ClientBattleTextReplayAdapterTest.kt
git commit -m "feat: encode playable client combat actions"
```

---

### Task 6: Mirror the client animation queue contract

**Files:**
- Create: `src/test/kotlin/com/stzb/server/game/battle/ClientBattleAnimationQueueContractTest.kt`

**Interfaces:**
- Consumes: `ClientBattleTextReplayAdapter.adapt`.
- Produces: a test-only segment parser matching `BattleAnimationData.SetRoundData`.

- [ ] **Step 1: Create the queue contract test**

Implement a parser with these rules:

```kotlin
when (action.id) {
    NORMAL_ATTACK_BEGIN -> open(begin = 2)
    SKILL_BEGIN -> open(begin = 5)
    NORMAL_ATTACK_END -> close(expectedBegin = 2)
    SKILL_END -> close(expectedBegin = 5)
    else -> if (segmentIsOpen) append(action)
}
```

It must fail on nested, empty, mismatched, orphaned, or unclosed segments.

Run it against `ClientBattleReportStore.createDefault(...).getOrCreateDefault().result` and assert:

```kotlin
assertTrue(segments.any { it.begin == 2 })
assertTrue(segments.any { it.begin == 5 })
assertTrue(payloads.any { it.id == ClientBattleTextReplayProtocol.NORMAL_DAMAGE })
assertTrue(payloads.any { it.referencesAttackerPosition() })
assertTrue(payloads.any { it.referencesDefenderPosition() })
```

- [ ] **Step 2: Prove RED capability**

Temporarily omit one `NORMAL_ATTACK_END` in the test input and run:

```bash
./gradlew test \
  --tests com.stzb.server.game.battle.ClientBattleAnimationQueueContractTest \
  --no-daemon -Dkotlin.compiler.execution.strategy=in-process
```

Expected: failure with `unclosed animation segment`. Restore the valid test input immediately.

- [ ] **Step 3: Run the valid contract and verify GREEN**

Run the same command.

Expected: PASS with normal and skill segments from both sides.

- [ ] **Step 4: Run all battle tests**

Run:

```bash
./gradlew test \
  --tests 'com.stzb.server.game.battle.*' \
  --no-daemon -Dkotlin.compiler.execution.strategy=in-process
```

Expected: all battle tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/test/kotlin/com/stzb/server/game/battle/ClientBattleAnimationQueueContractTest.kt
git commit -m "test: lock down client battle animation queue"
```

---

### Task 7: Full verification and client handoff

**Files:**
- Verify only.

**Interfaces:**
- Produces: a tested distribution in `build/install/stzb-server`.

- [ ] **Step 1: Run the complete verification gate**

```bash
./gradlew test installDist \
  --no-daemon -Dkotlin.compiler.execution.strategy=in-process
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 2: Check XML results and diff hygiene**

```bash
find build/test-results/test -name 'TEST-*.xml' -exec grep -h '<testsuite ' {} \;
git diff --check
git status --short
```

Expected: zero test failures/errors and no whitespace errors.

- [ ] **Step 3: Restart the server**

Stop the existing listener through the project's normal runtime procedure, then run:

```bash
./gradlew run \
  --no-daemon -Dkotlin.compiler.execution.strategy=in-process
```

- [ ] **Step 4: Generate a fresh report and verify in the client**

Confirm:

- attacker and defender both act;
- active skill does not consume the actor's normal attack;
- normal attacks select reachable nearest targets;
- rounds advance;
- skill damage/recovery/status and final troops are visible;
- opening the report does not throw.

- [ ] **Step 5: Record deferred work**

Do not alter formulas in this implementation. Record damage and targeting fidelity fitting as the next independent task using `paper.zip` samples.
