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
| C | Client dynamic land tables or runtime map cache override the static map 5 level. | High | Medium | Table overrides rejected; cache export inconclusive |
| D | The client runtime loaded a different map container despite cfgDataIndex 5. | Medium | Medium | Inconclusive |
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

## Verification Conclusion
The static resources are byte-identical, the corrected Android route reaches
the inspected Mac 59979 process, and no dynamic database-table override exists
for wid 15061504. The live server response is the map-5 result `611,612`.
Direct observation of the client-rendered level remains the final verification
because the generic map-cache exporter cannot currently read the runtime cache.
