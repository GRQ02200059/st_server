# 满级玩家状态与多队伍 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 新旧账号均成为正常势力满级测试账号，并支持五支互不覆盖、可独立出征的队伍。

**Architecture:** `PlayerState` 以 armyId 为键保存队伍和行军；协议构建器枚举所有队伍。业务处理器只把请求解析为 armyId，再调用状态模型。

**Tech Stack:** Kotlin/JVM、Jackson、Netty、Kotlin Test、Gradle 8.7。

## Global Constraints

- 保留已有账号武将和第一队阵容。
- 旧 `team` 自动迁移到第一队。
- 三战法槽全部开放，已配置战法等级为 10。
- 不实现动画，不改变战报外层协议。
- 所有生产代码前必须有失败测试。
- 不提交或回退工作区现有改动。

---

### Task 1: 满级账号迁移

**Files:**
- Modify: `src/main/kotlin/com/stzb/server/game/PlayerState.kt`
- Test: `src/test/kotlin/com/stzb/server/game/PlayerStateRepositoryTest.kt`
- Test: `src/test/kotlin/com/stzb/server/game/PlayerStatePersistenceTest.kt`

- [ ] 写失败测试：新武将为 50 级，旧武将和建筑加载后迁移到 50/20。
- [ ] 运行测试确认失败。
- [ ] 实现常量和幂等迁移。
- [ ] 运行测试确认通过。

### Task 2: 五队状态模型

**Files:**
- Modify: `src/main/kotlin/com/stzb/server/game/PlayerState.kt`
- Test: `src/test/kotlin/com/stzb/server/game/PlayerStateRepositoryTest.kt`

- [ ] 写失败测试：第二队上阵不覆盖第一队，跨队移动清除旧位置，五个 armyId 稳定。
- [ ] 运行测试确认失败。
- [ ] 将单个 team 替换为 armies 映射，并保留第一队兼容入口。
- [ ] 运行测试确认通过。

### Task 3: 五队协议与满战法槽

**Files:**
- Modify: `src/main/kotlin/com/stzb/server/game/UserInitTableBuilder.kt`
- Modify: `src/main/kotlin/com/stzb/server/game/GameResponses.kt`
- Test: `src/test/kotlin/com/stzb/server/game/UserInitTableBuilderTest.kt`
- Test: `src/test/kotlin/com/stzb/server/game/GameResponsesTest.kt`

- [ ] 写失败测试：登录含五行军队、满级建筑、武将 50 级和三个 10 级战法槽。
- [ ] 运行测试确认失败。
- [ ] 枚举军队、建筑并编码技能字段。
- [ ] 运行测试确认通过。

### Task 4: 多队业务命令

**Files:**
- Modify: `src/main/kotlin/com/stzb/server/handler/GameServerHandler.kt`
- Modify: `src/main/kotlin/com/stzb/server/game/PlayerConscriptService.kt`
- Test: `src/test/kotlin/com/stzb/server/game/PlayerConscriptServiceTest.kt`

- [ ] 写失败测试：第二队上阵/下阵/换位和征兵使用请求 armyId。
- [ ] 运行测试确认失败。
- [ ] 将所有队伍命令透传 armyId，原子通知所有受影响队伍。
- [ ] 运行测试确认通过。

### Task 5: 多队出征和土地等级守军

**Files:**
- Modify: `src/main/kotlin/com/stzb/server/game/PlayerBattleService.kt`
- Modify: `src/main/kotlin/com/stzb/server/game/PlayerState.kt`
- Create: `src/main/kotlin/com/stzb/server/game/LandDefenderFactory.kt`
- Test: `src/test/kotlin/com/stzb/server/game/PlayerBattleServiceTest.kt`
- Test: `src/test/kotlin/com/stzb/server/game/LandDefenderFactoryTest.kt`

- [ ] 写失败测试：第二队出征保持 armyId，不同土地等级守军兵力和等级不同。
- [ ] 运行测试确认失败。
- [ ] 行军按 armyId 保存，守军工厂按土地等级构建。
- [ ] 运行测试确认通过。

### Task 6: 正常势力身份和全量回归

**Files:**
- Modify: `src/main/kotlin/com/stzb/server/game/UserInitTableBuilder.kt`
- Modify: `src/main/kotlin/com/stzb/server/game/GameResponses.kt`
- Test: `src/test/kotlin/com/stzb/server/game/UserInitTableBuilderTest.kt`

- [ ] 写失败测试：主城和玩家身份字段为正常势力且无流浪军标记。
- [ ] 运行测试确认失败。
- [ ] 修正势力字段并运行相关测试。
- [ ] 运行 `./gradlew test installDist --no-daemon -Dkotlin.compiler.execution.strategy=in-process`。
- [ ] 运行 `git diff --check`。

