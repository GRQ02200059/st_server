# Debug Session: map-defender-mismatch
- **Status**: [OPEN]
- **Issue**: Client map defender display does not match the land level despite direct server replies reporting the canonical defender army.
- **Debug Server**: Pending startup
- **Log File**: .dbg/trae-debug-log-map-defender-mismatch.ndjson

## Reproduction Steps
1. Enter the world map on the Android client.
2. Select a land whose displayed defender level is incorrect.
3. Open the defender detail and record the visible land level and defender army.

## Hypotheses & Verification
| ID | Hypothesis | Likelihood | Effort | Evidence |
|----|------------|------------|--------|----------|
| A | The client selects a different `wid` than the previously checked sample. | High | Low | Rejected |
| B | The client uses stale `Tb_user_npc_army` data and does not send `4329`. | High | Low | Confirmed |
| C | The live service response differs from the tested response. | Medium | Low | Confirmed |
| D | The client resource-map level differs from the server's resource-map level. | Medium | Medium | Confirmed |

## Log Evidence
- `2026-08-01`: Client-side reproduction was reported, but
  `.dbg/trae-debug-log-map-defender-mismatch.ndjson` was not created.
- The active process on `59979` is `./gradlew run` output from
  `build/classes`, and its environment does not contain
  `DEBUG_SERVER_URL` or `DEBUG_SESSION_ID`.
- The instrumentation therefore has no endpoint and intentionally sends no
  debug event.
- After relaunching with the debug environment, the client sent `4331` for
  `15061504`, `15071503`, `15081505`, `15031503`, and `15031501`, but never
  sent `4329`. Their live server replies were respectively `305`, `203`,
  `509`, `611,612`, and `305`.
- The login snapshot contained an empty `Tb_user_npc_army` table. Client
  `DbNotify.Initialize` removes cached rows, but `ArmyGuardHandler` keeps an
  already-rendered guard model when it receives a row removal.
- Client `MapResCommon.GetResourceLevel` reads `MapCfgData.X.MapResourcesInMap`.
  `CfgData.Initialize(5)` selects map 5. Direct resource-map comparison:
  `15061504=6`, `15071503=6`, `15081505=2`, `15031503=7`, and
  `15031501=7` in map 5; the corresponding map-984 levels are `3,2,5,6,3`.

## Verification Conclusion
The server was incorrectly using resource map 984 while the client UI reads
the map selected by `cfgDataIndex=5`. The minimal fix is to make
`RESOURCE_MAP_CFG_ID` equal `CFG_DB_ID`. Instrumentation remains active for
post-fix comparison.

## Post-Fix Evidence
- `post-fix` login event reports `cfgDataIndex=5` and `resourceMapId=5`.
- `post-fix` defender-detail event for `15061504` returns `611,612`, the
  canonical two-team defender sequence for its client-visible level 6.
- `LandDefenderFactoryTest` passed after the map selection change.
- User verification contradicts the inferred client-visible level: the live
  map displays `15061504` as level 3. The resource-map source must therefore
  be resolved independently from `cfgDataIndex`; the post-fix conclusion is
  invalid.
- The client-side `MultiCfgTable` and static map files are independently
  versioned from the live map state. The direct UI observation is decisive:
  restore resource map 984, where `15061504` is level 3 and maps to defender
  army 305.
