# Unqualified Client Network Call Inventory Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Record line-start game `Send(...)` and `AddObserver(...)` calls in the active 9.2.4 command inventory without importing non-game or dynamic-wrapper noise.

**Architecture:** Add path-gated unqualified-call patterns alongside the existing receiver-qualified scanner. Resolve only numeric IDs and known command constants, regenerate only the 9.2.4 resource, and verify the exact source-reference and direction delta in both Python and Kotlin.

**Tech Stack:** Python 3.9 `re`/`unittest`, Kotlin 1.9.23, Jackson, Gradle 8.7, JDK 17.

## Global Constraints

- Scan unqualified calls only under top-level `Game` or `Game.*` decompiled directories.
- Keep existing receiver-qualified, raw-send, and observer behavior intact.
- Record only unqualified first arguments that resolve to a numeric ID or known command constant.
- Do not add dynamic unqualified wrappers to `unresolvedRequestSources`.
- Preserve `src/main/resources/protocol/client-9.2.2-command-inventory.json` byte-for-byte.
- Regenerate 9.2.4 without a capture index.
- Keep all 9.2.4 capture counts at zero.
- Do not start, restart, force-stop, or send a command through the Android client.
- Add no handlers, fallback responses, or response-shape claims.
- Run `git diff --check` before every commit.

---

### Task 1: Detect path-gated unqualified game calls

**Files:**
- Modify: `tools/client_protocol_inventory.py`
- Modify: `tools/test_client_protocol_inventory.py`

**Interfaces:**
- Produces: `UNQUALIFIED_SEND_RE`.
- Produces: `UNQUALIFIED_OBSERVER_RE`.
- Produces: `_is_game_source(root: Path, path: Path) -> bool`.
- Extends: `scan_client_sources(root, names_by_id)`.

- [ ] **Step 1: Write the failing scanner test**

Add:

```python
def test_scan_includes_game_unqualified_calls_and_excludes_noise(self):
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        game_source = root / "Game.UI" / "Panel.cs"
        game_source.parent.mkdir()
        game_source.write_text(
            """class Panel {
    void Go() {
        Send(51, null);
        Send<List<List<int>>>(52, null);
        AddObserver(53, OnPacket);
        Send(NetCommandDef.SYMBOLIC_REQUEST, null);
        AddObserver(NetCommandDef.SYMBOLIC_PUSH, OnPacket);
        Send(dynamicCmd, null);
    }
    protected void Send(int cmd, object data) {}
}
""",
            encoding="utf-8",
        )
        plain_game_source = root / "Game" / "Plain.cs"
        plain_game_source.parent.mkdir()
        plain_game_source.write_text(
            "class Plain {\\n    void Go() {\\n"
            "        Send(56, null);\\n    }\\n}\\n",
            encoding="utf-8",
        )
        noise_source = root / "Safaia" / "Noise.cs"
        noise_source.parent.mkdir()
        noise_source.write_text(
            "class Noise {\\n    void Go() {\\n"
            "        Send(51, null);\\n"
            "        AddObserver(53, OnPacket);\\n    }\\n}\\n",
            encoding="utf-8",
        )

        found = scan_client_sources(
            root,
            {
                51: ["NUMERIC_REQUEST"],
                52: ["NESTED_REQUEST"],
                53: ["NUMERIC_PUSH"],
                54: ["SYMBOLIC_REQUEST"],
                55: ["SYMBOLIC_PUSH"],
                56: ["PLAIN_GAME_REQUEST"],
            },
        )

    self.assertEqual(
        ["Game.UI/Panel.cs:3"],
        found[51]["requestSources"],
    )
    self.assertEqual(
        ["Game.UI/Panel.cs:4"],
        found[52]["requestSources"],
    )
    self.assertEqual(
        ["Game.UI/Panel.cs:5"],
        found[53]["receiveSources"],
    )
    self.assertEqual(
        ["Game.UI/Panel.cs:6"],
        found[54]["requestSources"],
    )
    self.assertEqual(
        ["Game.UI/Panel.cs:7"],
        found[55]["receiveSources"],
    )
    self.assertEqual(
        ["Game/Plain.cs:3"],
        found[56]["requestSources"],
    )
    self.assertEqual([], found["unresolvedRequestSources"])
```

- [ ] **Step 2: Run the test and verify RED**

Run:

```bash
python3 -m unittest \
  tools.test_client_protocol_inventory.ClientProtocolInventoryTests.test_scan_includes_game_unqualified_calls_and_excludes_noise \
  -v
```

Expected: missing command entries or source references because line-start
unqualified calls are not scanned.

- [ ] **Step 3: Implement path-gated patterns**

Add:

```python
UNQUALIFIED_SEND_RE = re.compile(
    r"^[ \t]*Send(?:<[^\r\n]+?>)?[ \t]*\([ \t]*"
    r"(?:NetCommandDef\.)?([A-Za-z_][A-Za-z0-9_]*|\d+)\b",
    re.MULTILINE,
)
UNQUALIFIED_OBSERVER_RE = re.compile(
    r"^[ \t]*AddObserver[ \t]*\([ \t]*"
    r"(?:NetCommandDef\.)?([A-Za-z_][A-Za-z0-9_]*|\d+)\b",
    re.MULTILINE,
)


def _is_game_source(root, path):
    top = path.relative_to(root).parts[0]
    return top == "Game" or top.startswith("Game.")
```

In `scan_client_sources`, retain the existing loops. For game sources only,
scan the new patterns after the receiver-qualified patterns:

```python
if _is_game_source(root, path):
    for pattern, target in (
        (UNQUALIFIED_SEND_RE, requests),
        (UNQUALIFIED_OBSERVER_RE, receives),
    ):
        for match in pattern.finditer(text):
            command_id = _resolve_command(match.group(1), names_by_id)
            if command_id is None:
                continue
            target[command_id].add(
                _source_ref(root, path, text, match.start())
            )
```

- [ ] **Step 4: Run all inventory tests from both entrypoints**

Run:

```bash
python3 tools/test_client_protocol_inventory.py -v
python3 -m unittest tools/test_client_protocol_inventory.py -v
```

Expected: all scanner tests pass from both entrypoints.

- [ ] **Step 5: Commit Task 1**

Run:

```bash
git add tools/client_protocol_inventory.py tools/test_client_protocol_inventory.py
git diff --cached --check
git commit -m "feat: scan unqualified client network calls"
```

---

### Task 2: Regenerate and activate corrected 9.2.4 evidence

**Files:**
- Modify: `src/main/resources/protocol/client-9.2.4-command-inventory.json`
- Modify: `src/test/kotlin/com/stzb/server/protocol/CommandContractRegistryTest.kt`

**Interfaces:**
- Consumes: Task 1 scanner and the normalized 9.2.4 decompiled root.
- Produces: corrected request/receive sources and inventory-derived directions.
- Preserves: 2,655 command IDs and all contract status counts.

- [ ] **Step 1: Write the failing active-direction test**

Add:

```kotlin
@Test
fun `active inventory includes unqualified request and observer evidence`() {
    val registry = CommandContractCatalog.registry
    val collectedFamily = assertNotNull(registry.contract(6_156))
    val regionBet = assertNotNull(registry.contract(6_128))
    val collectedFamilyInventory =
        assertNotNull(registry.inventoryEntry(6_156))
    val regionBetInventory =
        assertNotNull(registry.inventoryEntry(6_128))

    assertEquals(
        CommandDirection.CLIENT_REQUEST,
        collectedFamily.direction,
    )
    assertEquals(CommandDirection.DUPLEX, regionBet.direction)
    assertEquals(
        listOf(
            "Game.UI.GamePlay.Seasons/Tenth.UI.Invite2026/" +
                "InviteSeasonFamilyList.cs:425",
        ),
        collectedFamilyInventory.requestSources,
    )
    assertTrue(
        "Game.UI.GamePlay.Systems/Tenth.UI/" +
            "InviteSeasonTeamBornMiniMap3DMainUI.cs:559" in
            regionBetInventory.receiveSources,
    )
}
```

- [ ] **Step 2: Run the focused test and verify RED**

Run:

```bash
./gradlew test \
  --tests 'com.stzb.server.protocol.CommandContractRegistryTest' \
  --no-daemon --rerun-tasks \
  -Pkotlin.compiler.execution.strategy=in-process
```

Expected: cmd 6156 is still inferred as `SERVER_PUSH`, cmd 6128 is still
`CLIENT_REQUEST`, or the expected source reference is absent.

- [ ] **Step 3: Regenerate the active inventory**

Run:

```bash
python3 tools/client_protocol_inventory.py \
  --client-version 9.2.4 \
  --client-root /private/tmp/stzb-protocol-9.2.4.QJxxO8/decompiled \
  --output src/main/resources/protocol/client-9.2.4-command-inventory.json
```

Expected:

```text
commands=2655 unresolved_request_sites=96 output=src/main/resources/protocol/client-9.2.4-command-inventory.json
```

- [ ] **Step 4: Audit the exact evidence delta**

Run a structured comparison between the committed resource and the working
resource:

```bash
git show HEAD:src/main/resources/protocol/client-9.2.4-command-inventory.json \
  > /private/tmp/client-9.2.4-before-unqualified-scan.json
python3 - <<'PY'
import hashlib
import json
from collections import Counter
from pathlib import Path

before_path = Path("/private/tmp/client-9.2.4-before-unqualified-scan.json")
after_path = Path(
    "src/main/resources/protocol/client-9.2.4-command-inventory.json"
)
legacy_path = Path(
    "src/main/resources/protocol/client-9.2.2-command-inventory.json"
)
before = json.loads(before_path.read_text(encoding="utf-8"))
after = json.loads(after_path.read_text(encoding="utf-8"))
before_by_id = {row["id"]: row for row in before["commands"]}
after_by_id = {row["id"]: row for row in after["commands"]}

request_added = []
receive_added = []
for command_id, row in after_by_id.items():
    previous = before_by_id[command_id]
    request_added.extend(
        (command_id, ref)
        for ref in set(row["requestSources"])
        - set(previous["requestSources"])
    )
    receive_added.extend(
        (command_id, ref)
        for ref in set(row["receiveSources"])
        - set(previous["receiveSources"])
    )

def direction(row):
    if row["requestSources"] and row["receiveSources"]:
        return "DUPLEX"
    if row["requestSources"] or row["captureSendCount"] > 0:
        return "CLIENT_REQUEST"
    return "SERVER_PUSH"

assert after["clientVersion"] == "9.2.4"
assert len(after["commands"]) == 2655
assert len(after["unresolvedRequestSources"]) == 96
assert len(request_added) == 27
assert len({item[0] for item in request_added}) == 18
assert len(receive_added) == 56
assert len({item[0] for item in receive_added}) == 49
assert all(row["captureSendCount"] == 0 for row in after["commands"])
assert all(row["captureReceiveCount"] == 0 for row in after["commands"])
assert Counter(map(direction, after["commands"])) == {
    "CLIENT_REQUEST": 1751,
    "DUPLEX": 345,
    "SERVER_PUSH": 559,
}
assert after_by_id[6156]["requestSources"] == [
    "Game.UI.GamePlay.Seasons/Tenth.UI.Invite2026/"
    "InviteSeasonFamilyList.cs:425"
]
assert (
    "Game.UI.GamePlay.Systems/Tenth.UI/"
    "InviteSeasonTeamBornMiniMap3DMainUI.cs:559"
    in after_by_id[6128]["receiveSources"]
)
assert hashlib.sha256(after_path.read_bytes()).hexdigest() == (
    "797fc9c4cf0dcf13afcaeccfb8618bdc5f859e1beccf427bf09c1687a09c3c26"
)
assert hashlib.sha256(legacy_path.read_bytes()).hexdigest() == (
    "b18cd8aa81a0023d847eb18cb086e47b54e7ad32c064b077e654aed6df15af59"
)
print("unqualified network evidence audit: PASS")
PY
```

Expected:

```text
unqualified network evidence audit: PASS
```

- [ ] **Step 5: Run focused and full protocol gates**

Run:

```bash
python3 tools/test_client_protocol_inventory.py -v
python3 -m unittest tools/test_client_protocol_inventory.py -v
python3 tools/test_client_protocol_probe_plan.py -v
python3 -m unittest tools/test_client_protocol_probe_plan.py -v
./gradlew test \
  --tests 'com.stzb.server.handler.GameServerHandlerProtocolTest' \
  --tests 'com.stzb.server.protocol.CapturedShapeTest' \
  --tests 'com.stzb.server.protocol.NetworkResponsePolicyTest' \
  --tests 'com.stzb.server.protocol.CommandContractRegistryTest' \
  --tests 'com.stzb.server.protocol.CommandCoverageReportTest' \
  --no-daemon --rerun-tasks \
  -Pkotlin.compiler.execution.strategy=in-process
```

Expected: all Python tests and all focused Kotlin protocol tests pass.

- [ ] **Step 6: Regenerate and audit coverage**

Run:

```bash
./gradlew protocolCoverageReport --no-daemon
python3 - <<'PY'
from collections import Counter
from pathlib import Path

lines = Path("build/reports/protocol/command-coverage.md").read_text(
    encoding="utf-8",
).splitlines()
rows = [line for line in lines if line.startswith("| ")][1:]
statuses = Counter(row.split("|")[5].strip() for row in rows)
assert lines[0] == "# 9.2.4 Command Coverage"
assert len(rows) == 2655
assert dict(statuses) == {
    "OBSERVED_SHAPE": 41,
    "PROVISIONAL": 183,
    "REJECTED": 22,
    "UNIMPLEMENTED": 2409,
}
print(dict(statuses))
PY
```

Expected: the exact four status counts remain unchanged.

- [ ] **Step 7: Commit Task 2**

Run:

```bash
git add \
  src/main/resources/protocol/client-9.2.4-command-inventory.json \
  src/test/kotlin/com/stzb/server/protocol/CommandContractRegistryTest.kt
git diff --cached --check
git commit -m "chore: add unqualified 9.2.4 network evidence"
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

Expected: all tests pass, Git is clean, passive autosave remains enabled, and
no command is sent while the game PID is absent.
