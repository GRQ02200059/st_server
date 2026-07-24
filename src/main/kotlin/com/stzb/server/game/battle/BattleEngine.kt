package com.stzb.server.game.battle

object BattleEngine {
    fun resolve(request: BattleRequest): BattleResult =
        resolveInternal(request, skillRuntime = null, runtimeState = null, random = null)

    fun resolve(
        request: BattleRequest,
        config: BattleConfigRepository,
        random: BattleRandom = SeededBattleRandom(0),
    ): BattleResult {
        val interpreter = BattleSkillInterpreter(config)
        val runtime = BattleSkillRuntime(config)
        return resolveInternal(
            request = request.copy(
                attacker = interpreter.applyPreBattle(request.attacker),
                defender = interpreter.applyPreBattle(request.defender),
            ),
            skillRuntime = runtime,
            runtimeState = SkillRuntimeState(),
            random = random,
        )
    }

    private fun resolveInternal(
        request: BattleRequest,
        skillRuntime: BattleSkillRuntime?,
        runtimeState: SkillRuntimeState?,
        random: BattleRandom?,
    ): BattleResult {
        var attacker = request.attacker.heroes.associateBy { it.position }.toMutableMap()
        var defender = request.defender.heroes.associateBy { it.position }.toMutableMap()
        val statuses = mutableMapOf<BattleHeroRef, MutableList<ActiveBattleStatus>>()
        val events = mutableListOf<BattleEvent>(BattleEvent.BattleStart)
        var outcome = BattleOutcome.DRAW

        seedInitialActiveStatuses(attacker, defender, statuses)

        for (round in 1..request.maxRounds) {
            events.add(BattleEvent.RoundStart(round))
            applyOngoingStatuses(round, attacker, defender, statuses, events)
            outcome = currentOutcome(attacker, defender)
            if (outcome != BattleOutcome.DRAW) {
                events.add(BattleEvent.BattleEnd(outcome))
                return result(outcome, attacker, defender, events)
            }

            val turnOrder = buildTurnOrder(attacker, defender, statuses)
            for (actorRef in turnOrder) {
                val actor = currentHero(actorRef.side, actorRef.position, attacker, defender) ?: continue
                if (actor.troops <= 0) continue
                val actorStatuses = statuses[actorRef].orEmpty()

                events.add(BattleEvent.HeroActionStart(round, actorRef))
                if (!actor.shouldSkipAction(actorStatuses)) {
                    val effectiveActor = actor.withEffectiveStats(actorStatuses)
                    val skillCast = tryCastSkill(round, actorRef, effectiveActor, attacker, defender, statuses, skillRuntime, runtimeState, random, setOf(SkillKind.ACTIVE))
                    if (skillCast != null) {
                        applySkillCastResult(actorRef, skillCast, attacker, defender, statuses, events, round)
                    } else {
                        val targetRef = selectNormalAttackTarget(actorRef, effectiveActor, attacker, defender, statuses)
                        if (targetRef != null) {
                            val target = currentHero(targetRef.side, targetRef.position, attacker, defender) ?: continue
                            val targetStatuses = statuses[targetRef].orEmpty()
                            if (tryEvade(round, actorRef, targetRef, targetStatuses, statuses, events)) {
                                events.add(BattleEvent.HeroActionEnd(round, actorRef))
                                outcome = currentOutcome(attacker, defender)
                                if (outcome != BattleOutcome.DRAW) {
                                    events.add(BattleEvent.BattleEnd(outcome))
                                    return result(outcome, attacker, defender, events)
                                }
                                continue
                            }
                            val effectiveTarget = target.withEffectiveStats(targetStatuses)
                            val damage = normalAttackDamage(effectiveActor, effectiveTarget)
                            val newTarget = target.copy(troops = (target.troops - damage).coerceAtLeast(0))
                            if (targetRef.side == Side.ATTACKER) {
                                attacker[targetRef.position] = newTarget
                            } else {
                                defender[targetRef.position] = newTarget
                            }
                            events.add(
                                BattleEvent.NormalAttack(
                                    round = round,
                                    source = actorRef,
                                    target = targetRef,
                                    damage = damage,
                                    targetTroopsAfter = newTarget.troops,
                                ),
                            )
                            val pursuitActor = currentHero(actorRef.side, actorRef.position, attacker, defender) ?: actor
                            val pursuit = tryCastSkill(round, actorRef, pursuitActor.withEffectiveStats(statuses[actorRef].orEmpty()), attacker, defender, statuses, skillRuntime, runtimeState, random, setOf(SkillKind.PURSUIT))
                            if (pursuit != null) {
                                applySkillCastResult(actorRef, pursuit, attacker, defender, statuses, events, round)
                            }
                        }
                    }
                }
                events.add(BattleEvent.HeroActionEnd(round, actorRef))

                outcome = currentOutcome(attacker, defender)
                if (outcome != BattleOutcome.DRAW) {
                    events.add(BattleEvent.BattleEnd(outcome))
                    return result(outcome, attacker, defender, events)
                }
            }

            events.add(BattleEvent.RoundEnd(round))
            expireStatuses(statuses)
        }

        outcome = currentOutcome(attacker, defender)
        events.add(BattleEvent.BattleEnd(outcome))
        return result(outcome, attacker, defender, events)
    }

    private fun seedInitialActiveStatuses(
        attacker: Map<Int, BattleHero>,
        defender: Map<Int, BattleHero>,
        statuses: MutableMap<BattleHeroRef, MutableList<ActiveBattleStatus>>,
    ) {
        attacker.values.forEach { hero ->
            val ref = hero.ref(Side.ATTACKER)
            hero.activeStatuses.forEach { s ->
                statuses.getOrPut(ref) { mutableListOf() } += ActiveBattleStatus(
                    status = s,
                    remainingRounds = 99,
                    source = ref,
                )
            }
        }
        defender.values.forEach { hero ->
            val ref = hero.ref(Side.DEFENDER)
            hero.activeStatuses.forEach { s ->
                statuses.getOrPut(ref) { mutableListOf() } += ActiveBattleStatus(
                    status = s,
                    remainingRounds = 99,
                    source = ref,
                )
            }
        }
    }

    private fun applySkillCastResult(
        actorRef: BattleHeroRef,
        skillCast: SkillCastResult,
        attacker: MutableMap<Int, BattleHero>,
        defender: MutableMap<Int, BattleHero>,
        statuses: MutableMap<BattleHeroRef, MutableList<ActiveBattleStatus>>,
        events: MutableList<BattleEvent>,
        round: Int,
    ) {
        if (actorRef.side == Side.ATTACKER) {
            skillCast.updatedAllies?.heroes?.forEach { h -> attacker[h.position] = h }
            skillCast.updatedEnemies.heroes.forEach { h -> defender[h.position] = h }
        } else {
            skillCast.updatedAllies?.heroes?.forEach { h -> defender[h.position] = h }
            skillCast.updatedEnemies.heroes.forEach { h -> attacker[h.position] = h }
        }
        val actor = currentHero(actorRef.side, actorRef.position, attacker, defender)
        skillCast.events.forEach { event ->
            if (event is BattleEvent.StatusApplied && isControlStatus(event.status) && hasInsight(event.target, statuses)) {
                return@forEach
            }
            events += event
            if (event is BattleEvent.StatusApplied) {
                statuses.getOrPut(event.target) { mutableListOf() } += ActiveBattleStatus(
                    status = event.status,
                    remainingRounds = event.durationRounds + 1,
                    source = event.source,
                    power = if (event.power != 0) event.power else actor?.stats?.strategy?.coerceAtLeast(1) ?: 1,
                    statDelta = event.statDelta,
                    skillId = event.skillId,
                )
            }
        }
        if (skillCast.selfStatDelta != BattleStats.ZERO) {
            val self = currentHero(actorRef.side, actorRef.position, attacker, defender)
            if (self != null) {
                val stats: List<Pair<BattleStat, Int>> = listOf(
                    BattleStat.ATTACK to skillCast.selfStatDelta.attack,
                    BattleStat.DEFENSE to skillCast.selfStatDelta.defense,
                    BattleStat.STRATEGY to skillCast.selfStatDelta.strategy,
                    BattleStat.SPEED to skillCast.selfStatDelta.speed,
                )
                val primaryStat: Pair<BattleStat, Int>? = stats.firstOrNull { pair: Pair<BattleStat, Int> -> pair.second != 0 }
                if (primaryStat != null) {
                    val buffStatus = when (primaryStat.first) {
                        BattleStat.ATTACK -> BattleStatus.ATTACK_BUFF
                        BattleStat.DEFENSE -> BattleStatus.DEFENSE_BUFF
                        BattleStat.STRATEGY -> BattleStatus.STRATEGY_BUFF
                        BattleStat.SPEED -> BattleStatus.SPEED_BUFF
                        else -> null
                    }
                    if (buffStatus != null) {
                        statuses.getOrPut(actorRef) { mutableListOf() } += ActiveBattleStatus(
                            status = buffStatus,
                            remainingRounds = (skillCast.selfBuffDuration ?: 2) + 1,
                            source = actorRef,
                            statDelta = skillCast.selfStatDelta,
                            skillId = skillCast.skillId,
                        )
                        events += BattleEvent.StatChanged(
                            round = round,
                            source = actorRef,
                            target = actorRef,
                            stat = primaryStat.first,
                            delta = primaryStat.second,
                            durationRounds = skillCast.selfBuffDuration ?: 2,
                            skillId = skillCast.skillId,
                        )
                    }
                }
            }
        }
    }

    private fun tryEvade(
        round: Int,
        source: BattleHeroRef,
        target: BattleHeroRef,
        targetStatuses: List<ActiveBattleStatus>,
        statuses: MutableMap<BattleHeroRef, MutableList<ActiveBattleStatus>>,
        events: MutableList<BattleEvent>,
    ): Boolean {
        val evade = targetStatuses.firstOrNull { it.status == BattleStatus.EVADE } ?: return false
        events += BattleEvent.Evaded(round = round, source = source, target = target)
        val list = statuses[target] ?: return true
        list.remove(evade)
        if (list.isEmpty()) statuses.remove(target)
        return true
    }

    private fun hasInsight(ref: BattleHeroRef, statuses: Map<BattleHeroRef, List<ActiveBattleStatus>>): Boolean =
        statuses[ref].orEmpty().any { it.status == BattleStatus.INSIGHT }

    private fun isControlStatus(status: BattleStatus): Boolean =
        status in setOf(
            BattleStatus.CONFUSION, BattleStatus.HESITATION, BattleStatus.DISARM,
        )

    private fun tryCastSkill(
        round: Int,
        actorRef: BattleHeroRef,
        actor: BattleHero,
        attacker: Map<Int, BattleHero>,
        defender: Map<Int, BattleHero>,
        statuses: Map<BattleHeroRef, List<ActiveBattleStatus>>,
        skillRuntime: BattleSkillRuntime?,
        runtimeState: SkillRuntimeState?,
        random: BattleRandom?,
        allowedKinds: Set<SkillKind>,
    ): SkillCastResult? {
        if (skillRuntime == null || runtimeState == null || random == null) return null
        val enemies = if (actorRef.side == Side.ATTACKER) defender else attacker
        val allies = if (actorRef.side == Side.ATTACKER) attacker else defender
        val enemiesWithStats = enemies.values.map { it.withEffectiveStats(statuses[it.ref(actorRef.side.opposite())].orEmpty()) }
        val alliesWithStats = allies.values.map { it.withEffectiveStats(statuses[it.ref(actorRef.side)].orEmpty()) }
        return skillRuntime.tryAct(
            round = round,
            sourceRef = actorRef,
            source = actor,
            targets = BattleTeam(enemiesWithStats.sortedBy { it.position }),
            allies = BattleTeam(alliesWithStats.sortedBy { it.position }),
            random = random,
            state = runtimeState,
            allowedKinds = allowedKinds,
        )
    }

    private fun buildTurnOrder(
        attacker: Map<Int, BattleHero>,
        defender: Map<Int, BattleHero>,
        statuses: Map<BattleHeroRef, List<ActiveBattleStatus>>,
    ): List<BattleHeroRef> =
        (attacker.map { (_, hero) -> hero.ref(Side.ATTACKER) } +
            defender.map { (_, hero) -> hero.ref(Side.DEFENDER) })
            .filter { ref -> currentHero(ref.side, ref.position, attacker, defender)?.troops ?: 0 > 0 }
            .sortedWith(
                compareByDescending<BattleHeroRef> { ref ->
                    val hero = currentHero(ref.side, ref.position, attacker, defender)
                    hero?.withEffectiveStats(statuses[ref].orEmpty())?.stats?.speed ?: 0
                }.thenBy { it.side.ordinal }.thenBy { it.position },
            )

    private fun selectNormalAttackTarget(
        actorRef: BattleHeroRef,
        actor: BattleHero,
        attacker: Map<Int, BattleHero>,
        defender: Map<Int, BattleHero>,
        statuses: Map<BattleHeroRef, List<ActiveBattleStatus>>,
    ): BattleHeroRef? {
        val enemies = if (actorRef.side == Side.ATTACKER) defender else attacker
        return enemies.values
            .filter { it.troops > 0 }
            .filter { target -> isInRange(actor.position, target.position, actor.stats.hitRange) }
            .minByOrNull { it.position }
            ?.ref(actorRef.side.opposite())
    }

    private fun isInRange(sourcePos: Int, targetPos: Int, hitRange: Int): Boolean {
        val distance = targetPos - sourcePos + 1
        return distance in 1..hitRange
    }

    private fun normalAttackDamage(source: BattleHero, target: BattleHero): Int {
        val troopScale = source.troops.toDouble() / source.maxTroops.coerceAtLeast(1)
        val raw = (source.stats.attack - target.stats.defense / 2).coerceAtLeast(1)
        return (raw * troopScale).toInt().coerceAtLeast(1).coerceAtMost(target.troops)
    }

    private fun currentOutcome(
        attacker: Map<Int, BattleHero>,
        defender: Map<Int, BattleHero>,
    ): BattleOutcome {
        val attackerAlive = attacker.values.any { it.troops > 0 }
        val defenderAlive = defender.values.any { it.troops > 0 }
        return when {
            attackerAlive && !defenderAlive -> BattleOutcome.ATTACKER_WIN
            !attackerAlive && defenderAlive -> BattleOutcome.DEFENDER_WIN
            else -> BattleOutcome.DRAW
        }
    }

    private fun currentHero(
        side: Side,
        position: Int,
        attacker: Map<Int, BattleHero>,
        defender: Map<Int, BattleHero>,
    ): BattleHero? =
        if (side == Side.ATTACKER) attacker[position] else defender[position]

    private fun result(
        outcome: BattleOutcome,
        attacker: Map<Int, BattleHero>,
        defender: Map<Int, BattleHero>,
        events: List<BattleEvent>,
    ): BattleResult =
        BattleResult(
            outcome = outcome,
            attacker = BattleTeam(attacker.values.sortedBy { it.position }),
            defender = BattleTeam(defender.values.sortedBy { it.position }),
            events = events,
        )

    private fun BattleHero.ref(side: Side): BattleHeroRef =
        BattleHeroRef(side = side, position = position, heroId = id)

    private fun BattleHero.shouldSkipAction(runtimeStatuses: List<ActiveBattleStatus>): Boolean =
        runtimeStatuses.map { it.status }
            .any { it == BattleStatus.CONFUSION || it == BattleStatus.HESITATION || it == BattleStatus.DISARM }

    private fun BattleHero.withEffectiveStats(runtimeStatuses: List<ActiveBattleStatus>): BattleHero {
        val delta = runtimeStatuses.fold(BattleStats.ZERO) { acc, s -> acc + s.statDelta }
        if (delta == BattleStats.ZERO) return this
        return copy(
            stats = BattleStats(
                attack = (stats.attack + delta.attack).coerceAtLeast(1),
                defense = (stats.defense + delta.defense).coerceAtLeast(0),
                strategy = (stats.strategy + delta.strategy).coerceAtLeast(1),
                speed = (stats.speed + delta.speed).coerceAtLeast(1),
                siege = stats.siege + delta.siege,
                hitRange = stats.hitRange + delta.hitRange,
            ),
        )
    }

    private fun applyOngoingStatuses(
        round: Int,
        attacker: MutableMap<Int, BattleHero>,
        defender: MutableMap<Int, BattleHero>,
        statuses: Map<BattleHeroRef, List<ActiveBattleStatus>>,
        events: MutableList<BattleEvent>,
    ) {
        statuses.forEach { (targetRef, activeStatuses) ->
            val target = currentHero(targetRef.side, targetRef.position, attacker, defender) ?: return@forEach
            if (target.troops <= 0) return@forEach
            activeStatuses.filter { it.status.isDamageOverTime() }.forEach { active ->
                val damage = ongoingDamage(active, target)
                val newTarget = target.copy(troops = (target.troops - damage).coerceAtLeast(0))
                if (targetRef.side == Side.ATTACKER) {
                    attacker[targetRef.position] = newTarget
                } else {
                    defender[targetRef.position] = newTarget
                }
                events += BattleEvent.OngoingDamage(
                    round = round,
                    source = active.source,
                    target = targetRef,
                    status = active.status,
                    damage = damage,
                    targetTroopsAfter = newTarget.troops,
                    skillId = active.skillId,
                )
            }
        }
    }

    private fun expireStatuses(statuses: MutableMap<BattleHeroRef, MutableList<ActiveBattleStatus>>) {
        statuses.values.forEach { list ->
            list.replaceAll { it.copy(remainingRounds = it.remainingRounds - 1) }
            list.removeAll { it.remainingRounds <= 0 }
        }
        statuses.entries.removeAll { it.value.isEmpty() }
    }

    private fun ongoingDamage(status: ActiveBattleStatus, target: BattleHero): Int {
        val base = when (status.status) {
            BattleStatus.SHAKE -> status.power / 3
            BattleStatus.PANIC -> status.power / 2
            BattleStatus.BURN -> status.power / 2
            BattleStatus.HEX -> status.power / 2
            else -> 0
        }.coerceAtLeast(1)
        return base.coerceAtMost(target.troops)
    }

    private fun BattleStatus.isDamageOverTime(): Boolean =
        this == BattleStatus.SHAKE || this == BattleStatus.PANIC || this == BattleStatus.BURN || this == BattleStatus.HEX
}
