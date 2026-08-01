# Debug Session: map-runtime-alignment
- **Status**: [OPEN]
- **Issue**: Client land display and server defender selection remain misaligned after both were configured to use map 5.
- **Debug Server**: http://127.0.0.1:7777/event
- **Log File**: .dbg/trae-debug-log-map-runtime-alignment.ndjson

## Reproduction Steps
1. Start the current server build on port 59979.
2. Enter the world map in the Android user 999 client.
3. Open a land whose displayed level and defender strength do not match.
4. Capture the exact wid plus commands 21, 4329, 4331, 5025, and 5026.

## Hypotheses & Verification
| ID | Hypothesis | Likelihood | Effort | Evidence |
|----|------------|------------|--------|----------|
| A | The process listening on 59979 has not loaded the aligned build. | High | Low | Rejected |
| B | Android port 59979 routes to a different or absent Mac service. | High | Low | Confirmed pre-fix; route corrected |
| C | Client dynamic land tables or runtime map cache override the static map 5 level. | High | Medium | Rejected |
| D | Client and server decode the same map 5 resource byte with different encoding rules. | High | Medium | Confirmed |
| E | The client-selected wid differs from the previously verified 15061504 sample. | Medium | Low | Rejected for captured 4331 |

## Log Evidence
- Line 1: Mac port 59979 is served by PID 39813. The process started after the
  aligned `LandMapRepository.class` was written and returns level 6 with armies
  611 and 612 for wid 15061504.
- Line 2: `adb reverse` maps Android port 59979 to Mac port 59980. Mac port
  59979 is listening, while Mac port 59980 is not listening.
- Line 3: The Android game process is not running and PipeBridge reports
  `phoneConnected=false`, so the effective client runtime level cannot yet be
  inspected.
- Line 4: After correcting the route, Android user 999 is connected to Mac
  59979 and PipeBridge is connected on 59123.
- Line 5: The client runtime has zero rows in `Tb_developed_land`,
  `Tb_land_reclamation`, and `Tb_store_house`, so those tables do not override
  wid 15061504.
- Line 6: The live user 999 client requested 4331 for wid 15061504 and received
  `[15061504,"611,612"]`.
- Line 7: `exportMapCache` failed with `TargetInvocationException`, so
  `MapWidData.NewResLv` is not yet directly observable through that exporter.
- Line 8: The user confirmed wid 15061504 renders as level 3 while the live
  server still selected level 6 armies 611 and 612.
- Line 9: The fixed single-coordinate probe read the client runtime directly:
  `cfgIndex=5`, `mapSize=3001`, `mapResourceLength=9006001`,
  `rawResourceCode=65`, `isNewResource=false`, `resourceLevel=33`, and
  `realResourceLevel=33`. Dynamic level sources remain zero or absent.
- Line 10: The staged distribution resolves wid 15061504 to level 3, army
  305, and matching 4329/4331 payloads. Its SHA-256 is
  `055272b5214a4dec617a5aea28d7414e54ce07254bbd4fab85ce76c97ef4648f`.
- Line 11: After the user restarted the server, live PID 89307 returned
  `[15061504,"305"]` for both 4329 and 4331 while the client probe still
  returned resource type 33.

## Verification Conclusion
The map file and cfg index are aligned. The mismatch is the resource encoding:
map 5 stores the legacy code `65`, which the client maps to resource type `33`
because `ClientConfigCfg.IsNewResource=false`. The server incorrectly treated
the same byte as the new-format resource type `65`, producing level 6 defenders.

The staged server fix applies the client's legacy resource mapping to map 5
while preserving new-format decoding for maps 984, 2001, and 2002. Focused
tests and the built distribution resolve wid 15061504 to level 3 and army 305.
After restart, the live client/server protocol result is aligned:

- pre-fix: client `33` versus server `611,612`
- post-fix: client `33` and server `305`

The debug session remains open until the user confirms the in-client result.
