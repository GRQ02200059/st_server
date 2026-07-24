# AI Handoff Context

## Project

- Path: `/Users/bytedance/stzb/server`
- Target: restore a Kotlin + Netty server for the mobile game client `stzb` version `9.2.2`.
- Client connects to local server on port `59979`, usually via:

```bash
adb reverse tcp:59979 tcp:59979
```

Run server:

```bash
cd /Users/bytedance/stzb/server
./gradlew run --no-daemon -Dkotlin.compiler.execution.strategy=in-process
```

Build install distribution:

```bash
cd /Users/bytedance/stzb/server
./gradlew installDist --no-daemon -Dkotlin.compiler.execution.strategy=in-process
```

Kotlin daemon on macOS often reports permission errors like:

```text
FileSystemException: ... kotlin-daemon-client-tsmarker... Operation not permitted
Daemon compilation failed
```

This usually falls back successfully if using:

```bash
--no-daemon -Dkotlin.compiler.execution.strategy=in-process
```

## Architecture

Important files:

- `src/main/kotlin/com/stzb/server/handler/GameServerHandler.kt`
- `src/main/kotlin/com/stzb/server/protocol/Cmd.kt`
- `src/main/kotlin/com/stzb/server/protocol/NetworkResponsePolicy.kt`
- `src/main/kotlin/com/stzb/server/game/GameResponses.kt`
- `src/main/kotlin/com/stzb/server/game/UserInitTableBuilder.kt`
- `src/main/kotlin/com/stzb/server/game/PlayerState.kt`

Main state model:

- `PlayerState`
- `PlayerStateRepository`
- `PlayerResources`
- `PlayerHero`

`PlayerState.primaryArmyId()` uses:

```kotlin
cityWid * 10 + 1
```

The main city is usually:

```kotlin
GameServerConfig.CITY_WID = 100001
```

## Important Army Position Rule

The client army position semantics are:

- `pos=1` -> `base_heroid_u`
- `pos=2` -> `middle_heroid_u`
- `pos=3` -> `front_heroid_u`

The server `PlayerState.team` is stored as:

```text
[base, middle, front]
```

When writing `Tb_army`, map it as:

- index `5` `front_heroid_u = team[2]`
- index `6` `middle_heroid_u = team[1]`
- index `7` `base_heroid_u = team[0]`

This was a real bug before: the server used to write `pos=1` as front, causing client-side invalid army state and `KeyNotFoundException: key '0'`.

## Login Snapshot

Login snapshot is built in:

```text
src/main/kotlin/com/stzb/server/game/UserInitTableBuilder.kt
```

Critical tables that must exist:

- `Tb_user`
- `Tb_user_res`
- `Tb_user_build`
- `Tb_build_effect_city`
- `Tb_army`
- `Tb_hero`
- `Tb_user_card_extract`
- `Tb_activity`
- core empty tables for mail, reports, heroes, gear, etc.

Known crash reasons:

- Missing `Tb_hero`: recruited heroes cannot be inserted by `90005`.
- Missing `Tb_build_effect_city`: team cost calculation crashes.
- Missing `Tb_build_effect_city[26] army_cost_max`: team UI can crash.

## Implemented Protocols

### Base / Login

- `98888` notify SID handshake
- `99992` platform login check
- `99994` pre-server token check
- `99991` login success with `UserInitTable`
- `90008` SID check
- `90006` ping
- `25` server time in seconds
- `694` server time in milliseconds
- `5025 -> 5026` world scene full info

### Card Recruit

Commands:

- `301 CARD_RECRUIT`
- `304 CARD_QUICK_RECRUIT`
- `302 CARD_SET_ALL_NOT_NEW`

Important behavior:

- Recruit result card list uses `heroUid = 0`.
- Real hero insertion happens via `90005 Tb_hero`.
- This avoids client result animation reading a not-yet-inserted hero instance.

Relevant files:

- `GameResponses.cardRecruit`
- `GameResponses.quickCardRecruit`
- `RecruitResultParser`

### Building

Commands:

- `13 BUILD_BUILDING`
- `14 UPGRADE_BUILDING`

Behavior:

- Updates `PlayerState` build levels.
- Spends wood, stone, iron, food.
- Pushes `90005 Tb_user_build`.
- Pushes `90005 Tb_build_effect_city`.
- Pushes `90005 Tb_user_res`.

### Army / Team

Commands:

- `30 ADD_HERO_TO_ARMY`
- `32 SWITCH_HERO_IN_ARMY`
- `9026 NORMAL_TEAM_COMPOSITION`
- `9029 HERO_TEAM_LIBRARY`
- `8005 WORLD_BOSS_SAVE_TEAM`
- `8011 EXERCISE_DAILY_SAVE_TEAM`

`cmd=30` request:

```json
[cityWid, heroUid, armyId, pos]
```

Server behavior:

- `state.assignTeamHero(heroUid, pos)`
- returns `[armyId]`
- pushes `90005 Tb_army`
- pushes `90005 Tb_hero`

`cmd=32` request:

```json
[cityWid, armyId1, pos1, armyId2, pos2]
```

Client response handler iterates response as `List<object>` and casts each item to `int`.

Server behavior:

- `state.switchTeamHeroes(pos1, pos2)`
- returns distinct army ids as `List<Int>`
- pushes `90005 Tb_army`
- pushes affected `90005 Tb_hero`

### Conscript

Commands:

- `37 CONSCRIPT`
- `38 CONSCRIPT_IMMEDIATELY`

Request formats:

`37`:

```json
[conscriptType, [[heroUid, count], ...]]
```

`38`:

```json
[[[heroUid, count], ...], conscriptType]
```

Client response expectation:

```csharp
Convert.ToInt32(packet)
```

Server returns one integer army id.

Current simplified behavior:

- Each soldier costs `1 food + 1 money`.
- Troop cap is `1000`.
- Conscript completes immediately.
- Pushes `90005 Tb_hero`.
- Pushes `90005 Tb_user_res`.

Files:

- `ConscriptRequestParser.kt`
- `PlayerConscriptService.kt`

### Land Info

Command:

- `21 LAND_INFO`

Reason:

Returning `[]` can make land detail UI use default `LandId=0`, then later some UI accesses a dictionary key `0` and crashes.

Server now returns a 54-slot safe array from:

```kotlin
GameResponses.landInfo(wid)
```

### World Scene

Command:

- `5025 GET_WORLD_SCENCE_INFO`
- server responds with `5026 SEND_WORLD_SCENCE_FULL_INFO`

`GameResponses.worldSceneFullInfo` must return 30 slots. Important slots:

- `[1]` map users
- `[10]` short messages
- `[14]` world chunks
- `[18]` server order id, must be positive
- `[29]` real march

## Battle / Report

Battle exists but user currently asked to avoid battle-specific work unless necessary.

Files include:

- `BattleEngine`
- `BattleConfigRepository`
- `BattleTeamBuilder`
- `PlayerBattleService`
- `ClientBattleReportStore`
- `BattleReportCodec`

Battle report detail must use:

```text
zzz + GZIP + Base64
```

Battle profile must include:

- `battle_id`
- `wid`
- `result`
- `time`
- attacker/defender troops
- `hero_info`

## Network Fallback

Fallback logic is in:

```text
src/main/kotlin/com/stzb/server/protocol/NetworkResponsePolicy.kt
```

General rule:

- If a command repeats or affects visible state, do not leave it on `[]` fallback.
- Implement it precisely and push the matching `90005` table changes.

Commands already removed from generic fallback / precisely handled:

- `21`
- `30`
- `32`
- `694`
- `3400`
- `4159`
- `7046`

## Recent Debugging Notes

### Repeated `cmd=32`

Observed log:

```text
cmd=32 body: [100001,1000011,1,1000011,3]
```

Meaning:

- switch hero in army `1000011`
- swap `pos=1` and `pos=3`

Fixes done:

- Implemented `cmd=32`.
- Corrected army position mapping to client semantics:
  - `pos=1 base`
  - `pos=2 middle`
  - `pos=3 front`

### `KeyNotFoundException: key '0'`

Client crash:

```text
System.Collections.Generic.KeyNotFoundException:
The given key '0' was not present in the dictionary.
Dictionary<int, List<ValueTuple<int,bool>>>.get_Item(Int32 key)
```

Likely causes already addressed:

- `cmd=21` land info no longer returns `[]`.
- `Tb_army` no longer writes team positions reversed.

If it still occurs:

1. Ask for server logs immediately before crash.
2. Look for repeated:

```text
未精确实现 cmd=...
```

3. Implement that command precisely instead of fallback.
4. Focus first on army, land, map, UI-state, and `90005` table updates.

## Test / Build Status

These were passing at handoff time:

```bash
cd /Users/bytedance/stzb/server
./gradlew test --no-daemon -Dkotlin.compiler.execution.strategy=in-process
./gradlew installDist --no-daemon -Dkotlin.compiler.execution.strategy=in-process
```

## Operational Notes

After code changes:

1. Rebuild or run via Gradle.
2. Restart the server.
3. Re-login client if the fix affects login snapshot tables like `Tb_army`.

Recommended run:

```bash
cd /Users/bytedance/stzb/server
./gradlew run --no-daemon -Dkotlin.compiler.execution.strategy=in-process
```

Check port:

```bash
lsof -iTCP:59979 -sTCP:LISTEN
```

