# Unqualified Command Probe Tranche Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Validate a complete explicit risk manifest for the 18 recovered unqualified request commands and export eight new literal read-only probes.

**Architecture:** Add explicit-scope validation beside the existing added-since-baseline validator and share all row safety checks. Keep a separate 18-row manifest, make CLI baseline input optional, and export deterministic `capture_send.py` input without any runtime connection.

**Tech Stack:** Python 3.9 standard library, JSON, `unittest`, existing 9.2.4 command inventory.

## Global Constraints

- Cover exactly the 18 recovered request IDs.
- Classify all 18; export only eight new read-only requests.
- Keep cmd 6156 in the audit but do not export it twice.
- Preserve existing added-since-baseline validation and CLI behavior.
- Preserve explicit JSON null separately from a missing payload.
- Add no ADB, HTTP, WebSocket, subprocess, or send dependency.
- Do not start, restart, force-stop, or send a command through the client.
- Add no private-server handlers or response-shape claims.
- Use Python 3.9-compatible syntax.
- Run `git diff --check` before every commit.

---

### Task 1: Add explicit-scope validation and CLI mode

**Files:**
- Modify: `tools/client_protocol_probe_plan.py`
- Modify: `tools/test_client_protocol_probe_plan.py`

**Interfaces:**
- Produces: `validate_explicit_probe_plan(manifest, current_inventory) -> list[dict]`.
- Preserves: `validate_probe_plan(manifest, baseline_inventory, current_inventory)`.
- CLI: `--baseline-inventory` becomes optional.
- CLI with baseline uses delta validation; CLI without baseline uses explicit validation.

- [ ] **Step 1: Write failing explicit validation tests**

Import `validate_explicit_probe_plan` and add:

```python
def test_validate_explicit_plan_accepts_inventory_subset(self):
    rows = validate_explicit_probe_plan(
        manifest={
            "clientVersion": "9.2.4",
            "scope": "EXPLICIT",
            "commands": [
                row(
                    42,
                    "QUERY",
                    "READ_ONLY_STATIC",
                    auto_probe=True,
                    probe_payload=None,
                ),
                row(43, "WRITE", "MUTATING"),
            ],
        },
        current_inventory=inventory(
            "9.2.4",
            [(1, "OLD"), (42, "QUERY"), (43, "WRITE")],
        ),
    )

    self.assertEqual([42, 43], [item["id"] for item in rows])
    self.assertEqual(
        [{"cmd": 42, "payload": None}],
        build_auto_probe_batch(rows),
    )
```

Add a table-driven explicit rejection test for:

```text
scope missing
scope not EXPLICIT
clientVersion mismatch
unknown command ID
name mismatch
duplicate ID
unsorted IDs
invalid classification
READ_ONLY_STATIC without probePayload
probePayload on non-static row
autoProbe=true on non-static row
```

- [ ] **Step 2: Write the failing explicit CLI test**

Use temporary files and call `main` without `--baseline-inventory`:

```python
exit_code = main(
    [
        "--current-inventory", str(current_path),
        "--manifest", str(manifest_path),
        "--output", str(output_path),
    ]
)
```

Assert exit code zero, explicit JSON null preservation, and trailing newline.
Keep the existing baseline CLI test unchanged.

- [ ] **Step 3: Run explicit tests and verify RED**

Run:

```bash
python3 -m unittest \
  tools.test_client_protocol_probe_plan.ProbePlanTest.test_validate_explicit_plan_accepts_inventory_subset \
  tools.test_client_protocol_probe_plan.ProbePlanTest.test_cli_exports_explicit_validated_batch \
  -v
```

Expected: import failure for `validate_explicit_probe_plan` or required
baseline argument failure.

- [ ] **Step 4: Extract shared row validation**

Add:

```python
def _validate_command_rows(commands, current_by_id, expected_ids=None):
    ...
```

Move existing manifest row checks into it. It must:

1. Require a list of object rows.
2. Validate integer non-boolean IDs, uniqueness, and ascending order.
3. If `expected_ids` is supplied, require exact equality.
4. Otherwise require every row ID to exist in `current_by_id`.
5. Require exact names and all existing classification/evidence/payload
   invariants.
6. Return validated rows in manifest order.

Update `validate_probe_plan` to compute the delta and call:

```python
return _validate_command_rows(
    manifest.get("commands"),
    current_by_id,
    expected_ids=set(current_by_id) - set(baseline_by_id),
)
```

- [ ] **Step 5: Implement explicit validation**

Add:

```python
def validate_explicit_probe_plan(manifest, current_inventory):
    if not isinstance(manifest, dict):
        raise ProbePlanError("probe manifest must be an object")
    if manifest.get("scope") != "EXPLICIT":
        raise ProbePlanError("explicit probe manifest scope must be EXPLICIT")

    current_by_id = _inventory_by_id(current_inventory, "current")
    if manifest.get("clientVersion") != current_inventory.get("clientVersion"):
        raise ProbePlanError(
            "probe client version does not match current inventory"
        )
    return _validate_command_rows(
        manifest.get("commands"),
        current_by_id,
    )
```

- [ ] **Step 6: Make baseline CLI input optional**

Change:

```python
parser.add_argument("--baseline-inventory", type=Path)
```

Load manifest and current inventory once. Branch:

```python
if args.baseline_inventory is None:
    rows = validate_explicit_probe_plan(manifest, current)
else:
    rows = validate_probe_plan(
        manifest,
        load_json(args.baseline_inventory),
        current,
    )
```

- [ ] **Step 7: Run all probe-plan tests from both entrypoints**

Run:

```bash
python3 tools/test_client_protocol_probe_plan.py -v
python3 -m unittest tools/test_client_protocol_probe_plan.py -v
```

Expected: all old delta and new explicit tests pass.

- [ ] **Step 8: Commit Task 1**

Run:

```bash
git add tools/client_protocol_probe_plan.py tools/test_client_protocol_probe_plan.py
git diff --cached --check
git commit -m "feat: validate explicit command probe tranches"
```

---

### Task 2: Add the complete recovered-source tranche

**Files:**
- Create: `tools/protocol/client-9.2.4-unqualified-command-probes.json`
- Modify: `tools/test_client_protocol_probe_plan.py`

**Interfaces:**
- Consumes: `validate_explicit_probe_plan`.
- Produces: 18 sorted, classified command rows.
- Produces: eight new automatic probes accepted by `capture_send.py`.

- [ ] **Step 1: Write the failing real-manifest test**

Load the active inventory and new manifest, then assert:

```python
rows = validate_explicit_probe_plan(manifest, current)
self.assertEqual(18, len(rows))
self.assertEqual(
    {
        "MUTATING": 6,
        "READ_ONLY_CONTEXTUAL": 3,
        "READ_ONLY_STATIC": 9,
    },
    dict(sorted(Counter(row["classification"] for row in rows).items())),
)
self.assertEqual(
    [671, 2628, 3611, 3801, 3864, 6130, 6220, 6291],
    [item["cmd"] for item in build_auto_probe_batch(rows)],
)
```

Serialize the batch with the CLI format and assert SHA-256:

```python
self.assertEqual(
    "b6ee66e31ac96374bf744c54b1a73f2171240c8e6614f517498b69825dcb6ef5",
    hashlib.sha256(
        (json.dumps(batch, ensure_ascii=False, indent=2) + "\n")
        .encode("utf-8")
    ).hexdigest(),
)
```

- [ ] **Step 2: Run the real-manifest test and verify RED**

Run:

```bash
python3 -m unittest \
  tools.test_client_protocol_probe_plan.ProbePlanTest.test_real_unqualified_manifest_is_complete_and_exports_new_safe_reads \
  -v
```

Expected: `FileNotFoundError` for the new manifest.

- [ ] **Step 3: Create the exact 18-row manifest**

Use `scope="EXPLICIT"` and ascending IDs. Populate rows from this table:

| Cmd | Classification | Shape | Payload | Auto | Evidence | Reason |
|---:|---|---|---|---|---|---|
| 671 | `READ_ONLY_STATIC` | `[] or null` | `[]` | yes | `CardRecordData.cs:33-36`, historical send `[]` | Read card record |
| 885 | `MUTATING` | `UserOpenUI object` | absent | no | `LoginServerListUI.cs:786-794` | Writes UI-open telemetry |
| 2628 | `READ_ONLY_STATIC` | `empty string` | `""` | yes | `CardOperateHeroRetrieve.cs:156-162` | Read hero reset list |
| 3611 | `READ_ONLY_STATIC` | `null` | `null` | yes | `ArmyConsultOverviewUI.cs:189-202` | Read own suggestions |
| 3674 | `MUTATING` | `null` | absent | no | `ActivityXiaoManMainUI.cs:130-136` | Draw activity reward |
| 3801 | `READ_ONLY_STATIC` | `null` | `null` | yes | `PictorialOverview.cs:130-146` | Read gear record |
| 3864 | `READ_ONLY_STATIC` | `null` | `null` | yes | `FamilyApplyProcessUI.cs:103-124` | Read family applications |
| 3990 | `READ_ONLY_CONTEXTUAL` | `[inviteId]` | absent | no | `InviteSeasonGameMapData.cs:1744-1770` | Needs live invite ID |
| 4000 | `READ_ONLY_CONTEXTUAL` | `[inviteId, op, optionalId]` | absent | no | `InviteSeasonGameMapData.cs:1420-1430,1734-1767` | Needs live invite/event IDs |
| 4091 | `MUTATING` | `null` | absent | no | `FamilyPrayMainUI.cs:285-298` | Performs family prayer |
| 4203 | `MUTATING` | `message string` | absent | no | `SolartermBookMsgUI.cs:180-197` | Writes book message |
| 4204 | `MUTATING` | `bookId` | absent | no | `SolartermBookMsgUI.cs:160-170` | Deletes book message |
| 5140 | `READ_ONLY_CONTEXTUAL` | `[serverId, op, optionalData]` | absent | no | `InviteNewPaperData.cs:164-168`, `RankingData.cs:2239-2243` | Needs live server/op data |
| 6130 | `READ_ONLY_STATIC` | `[] or null` | `[]` | yes | `InviteSeasonBornMapData.cs:435-439`, `InviteSeasonMainView.cs:1033-1043` | Read region configuration |
| 6156 | `READ_ONLY_STATIC` | `null` | `null` | no | `InviteSeasonFamilyList.cs:412-425` | Already exported earlier |
| 6220 | `READ_ONLY_STATIC` | `null` | `null` | yes | `MutualAidNeedTab.cs:264-275` | Check army-aid state |
| 6260 | `MUTATING` | `null` | absent | no | `RoleForcesRealWarehouseUI.cs:207-265` | Collects warehouse resources |
| 6291 | `READ_ONLY_STATIC` | `null` | `null` | yes | `BczhTournamentBracketMainUI.cs:40-50` | Read tournament bracket |

Every row must include exact active-inventory `names`, non-empty
`requestShapes`, `evidence`, and `reason`. Only `READ_ONLY_STATIC` rows carry
`probePayload`.

- [ ] **Step 4: Run all tests and manifest audits**

Run:

```bash
python3 tools/test_client_protocol_probe_plan.py -v
python3 -m unittest tools/test_client_protocol_probe_plan.py -v
jq -e '.scope == "EXPLICIT" and (.commands | length) == 18' \
  tools/protocol/client-9.2.4-unqualified-command-probes.json
```

Expected: tests pass and jq prints `true`.

- [ ] **Step 5: Export and audit the real batch**

Run:

```bash
python3 tools/client_protocol_probe_plan.py \
  --current-inventory \
    src/main/resources/protocol/client-9.2.4-command-inventory.json \
  --manifest tools/protocol/client-9.2.4-unqualified-command-probes.json \
  --output build/protocol/client-9.2.4-unqualified-safe-probes.json
jq -e 'map(.cmd) == [671,2628,3611,3801,3864,6130,6220,6291]' \
  build/protocol/client-9.2.4-unqualified-safe-probes.json
shasum -a 256 \
  build/protocol/client-9.2.4-unqualified-safe-probes.json
```

Expected:

```text
validated=18 auto_probes=8 output=build/protocol/client-9.2.4-unqualified-safe-probes.json
true
b6ee66e31ac96374bf744c54b1a73f2171240c8e6614f517498b69825dcb6ef5
```

Do not invoke `capture_send.py` while `phoneConnected=false`.

- [ ] **Step 6: Commit Task 2**

Run:

```bash
git add \
  tools/protocol/client-9.2.4-unqualified-command-probes.json \
  tools/test_client_protocol_probe_plan.py
git diff --cached --check
git commit -m "chore: classify recovered unqualified command probes"
```

---

## Final Verification

- [ ] Run:

```bash
python3 tools/test_client_protocol_inventory.py -v
python3 -m unittest tools/test_client_protocol_inventory.py -v
python3 tools/test_client_protocol_probe_plan.py -v
python3 -m unittest tools/test_client_protocol_probe_plan.py -v
git diff --check
git status --short --untracked-files=all
curl --fail --silent --show-error http://127.0.0.1:59124/api/state
adb -s 127.0.0.1:5555 shell pidof com.netease.stzb.netease
```

Expected: all tests pass, Git is clean, autosave remains enabled, and no
command is sent while the game PID is absent.
