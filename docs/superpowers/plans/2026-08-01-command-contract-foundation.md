# Command Contract Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** Build the 9.2.2 bidirectional command inventory and server contract registry that makes every known client command explicitly classified, removes the generic successful `[]` fallback, and produces a deterministic coverage report.

**Architecture:** A standard-library Python scanner parses the decompiled `NetCommandDef.cs`, request calls, receive observers, and existing capture index into a versioned JSON resource. Kotlin loads that resource into a `CommandContractRegistry`, applies explicit overrides for current server behavior and captured response shapes, and uses the resulting status to gate all fallback responses. The Netty handler retains current explicit branches but returns no fabricated success response for unimplemented or rejected commands.

**Tech Stack:** Kotlin 1.9.23, JDK 17, Gradle, Jackson 2.17.0, Netty 4.1.109, kotlin.test, Python 3 standard library.

## Global Constraints

- Only modify the server repository, its server resources, and server-owned protocol tooling; do not modify, inject into, or replace client DLLs, assets, memory, or runtime configuration.
- Treat `/Users/bytedance/stzb/stzb_9.2.2_out_branch_9.1.1776213/assets/decompiled` as read-only 9.2.2 evidence.
- The generated inventory must include all `2591` unique command ids declared by `Game.Network/Tenth.Network/NetCommandDef.cs`, plus ids found only at numeric client send/receive call sites.
- Every inventory command must have exactly one server status: `EXACT`, `OBSERVED_SHAPE`, `PROVISIONAL`, `UNIMPLEMENTED`, or `REJECTED`.
- `EXACT` requires request or push ownership, response sequence, state projection, source evidence, and a server test. No existing command is promoted to `EXACT` during this foundation task unless those requirements are already encoded and tested.
- Preserve current explicit handler behavior. Existing handler-owned commands become `PROVISIONAL` until their domain contract is audited.
- Keep recorded response-shape fallbacks only for ids explicitly registered as `OBSERVED_SHAPE`; remove `cmdId in 1..99999 -> []`.
- Unknown, unimplemented, malformed, and rejected commands must never receive a fabricated successful `[]`.
- Keep `5026`, `5028`, `90005`, and `2100` as independently registered server packets rather than treating them as missing client requests.
- Do not stage unrelated dirty files. Each commit stages only files named in its task.
- Run Kotlin commands with `-Dkotlin.compiler.execution.strategy=in-process`.

---

## File Structure

| File | Responsibility |
|---|---|
| `tools/client_protocol_inventory.py` | Read-only 9.2.2 source/capture scanner and deterministic JSON inventory writer. |
| `tools/test_client_protocol_inventory.py` | Unit tests for constant parsing, request/receive discovery, capture merge, and stable JSON ordering. |
| `src/main/resources/protocol/client-9.2.2-command-inventory.json` | Checked-in generated inventory consumed by the server runtime. |
| `src/main/kotlin/com/stzb/server/protocol/CommandContract.kt` | Contract types, inventory loader, validation rules, and registry lookup. |
| `src/main/kotlin/com/stzb/server/protocol/CommandContractCatalog.kt` | Explicit status/domain/owner overrides for existing handler commands, captured shapes, and local rejections. |
| `src/main/kotlin/com/stzb/server/protocol/NetworkResponsePolicy.kt` | Shape-only response bodies for explicit `OBSERVED_SHAPE` contracts; no broad range fallback. |
| `src/main/kotlin/com/stzb/server/protocol/CommandCoverageReport.kt` | Deterministic Markdown report renderer and CLI entry point. |
| `src/main/kotlin/com/stzb/server/handler/GameServerHandler.kt` | Registry-controlled unresolved-command response path and recorded acknowledgements. |
| `build.gradle.kts` | `protocolCoverageReport` JavaExec task that writes the generated coverage report. |
| `src/test/kotlin/com/stzb/server/protocol/CommandContractRegistryTest.kt` | Registry validation, inventory coverage, status precedence, and response eligibility tests. |
| `src/test/kotlin/com/stzb/server/protocol/CommandCoverageReportTest.kt` | Report ordering and missing-status regression tests. |
| `src/test/kotlin/com/stzb/server/protocol/NetworkResponsePolicyTest.kt` | Explicit shape response tests and no-generic-fallback regression test. |
| `src/test/kotlin/com/stzb/server/protocol/CapturedShapeTest.kt` | Existing recorded-shape assertions through the explicit policy API. |
| `src/test/kotlin/com/stzb/server/handler/GameServerHandlerProtocolTest.kt` | EmbeddedChannel proof that unknown commands receive no fabricated response. |

## Shared Interfaces

```kotlin
package com.stzb.server.protocol

enum class CommandDirection {
    CLIENT_REQUEST,
    SERVER_PUSH,
    DUPLEX,
}

enum class CommandStatus {
    EXACT,
    OBSERVED_SHAPE,
    PROVISIONAL,
    UNIMPLEMENTED,
    REJECTED,
}

enum class CommandDomain {
    TRANSPORT,
    LOGIN,
    WORLD,
    CITY,
    ARMY,
    HERO,
    BATTLE,
    SOCIAL,
    ACTIVITY,
    EXTERNAL,
    UNKNOWN,
}

data class ContractEvidence(
    val kind: String,
    val reference: String,
)

data class ResponseStep(
    val cmdId: Int,
    val description: String,
)

data class ClientCommandInventoryEntry(
    val id: Int,
    val names: List<String> = emptyList(),
    val requestSources: List<String> = emptyList(),
    val receiveSources: List<String> = emptyList(),
    val unresolvedRequestSources: List<String> = emptyList(),
    val captureSendCount: Int = 0,
    val captureReceiveCount: Int = 0,
)

data class ClientCommandInventory(
    val clientVersion: String,
    val commands: List<ClientCommandInventoryEntry>,
    val unresolvedRequestSources: List<String> = emptyList(),
)

data class CommandContract(
    val id: Int,
    val names: List<String>,
    val direction: CommandDirection,
    val domain: CommandDomain,
    val status: CommandStatus,
    val owner: String? = null,
    val requestShape: String? = null,
    val responseSequence: List<ResponseStep> = emptyList(),
    val stateProjection: List<String> = emptyList(),
    val evidence: List<ContractEvidence> = emptyList(),
)

class CommandContractRegistry(
    inventory: ClientCommandInventory,
    overrides: Collection<CommandContract>,
) {
    fun contract(cmdId: Int): CommandContract?
    fun all(): List<CommandContract>
    fun isShapeResponseAllowed(cmdId: Int): Boolean
}
```

### Task 1: Generate a Versioned Bidirectional Client Inventory

**Files:**
- Create: `tools/client_protocol_inventory.py`
- Create: `tools/test_client_protocol_inventory.py`
- Create: `src/main/resources/protocol/client-9.2.2-command-inventory.json`

**Interfaces:**
- Consumes: a 9.2.2 decompiled root, `NetCommandDef.cs`, all `*.cs` files beneath that root, and a capture `index.json`.
- Produces: stable `ClientCommandInventory` JSON with sorted unique command ids, constant names, request sources, receive sources, unresolved request sources, and capture counts.

- [x] **Step 1: Add the failing scanner tests**

Create `tools/test_client_protocol_inventory.py`:

```python
import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from client_protocol_inventory import (
    build_inventory,
    parse_command_constants,
    scan_client_sources,
)


class ClientProtocolInventoryTests(unittest.TestCase):
    def test_constants_merge_duplicate_numeric_ids_without_losing_names(self):
        constants = parse_command_constants(
            """
            public const int FIRST = 7;
            public const int SECOND = 7;
            public const int THIRD = 8;
            """
        )

        self.assertEqual({7: ["FIRST", "SECOND"], 8: ["THIRD"]}, constants)

    def test_scan_classifies_literal_send_and_observer_sites(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            source = root / "Game.UI" / "Example.cs"
            source.parent.mkdir()
            source.write_text(
                """
                public class Example {
                    void Go() {
                        NetManager.GetInstance().Send<object[]>(42, new object[0]);
                        NetObserver.GetInstance().AddObserver(43, OnPacket, 0);
                        NetManager.GetInstance().Send<object[]>(dynamicCmd, new object[0]);
                    }
                }
                """,
                encoding="utf-8",
            )

            found = scan_client_sources(root, {42: ["REQUEST"], 43: ["PUSH"]})

        self.assertEqual(["Game.UI/Example.cs:4"], found[42]["requestSources"])
        self.assertEqual(["Game.UI/Example.cs:5"], found[43]["receiveSources"])
        self.assertEqual(["Game.UI/Example.cs:6"], found["unresolvedRequestSources"])

    def test_inventory_contains_constants_sources_and_capture_counts_in_id_order(self):
        discovered = {
            42: {"requestSources": ["A.cs:1"], "receiveSources": []},
            43: {"requestSources": [], "receiveSources": ["B.cs:2"]},
            "unresolvedRequestSources": ["C.cs:3"],
        }
        inventory = build_inventory(
            constants={42: ["REQUEST"], 43: ["PUSH"]},
            discovered=discovered,
            capture_index={"42": {"send": 2, "recv": 1}},
        )

        self.assertEqual("9.2.2", inventory["clientVersion"])
        self.assertEqual([42, 43], [row["id"] for row in inventory["commands"]])
        self.assertEqual(2, inventory["commands"][0]["captureSendCount"])
        self.assertEqual(1, inventory["commands"][0]["captureReceiveCount"])
        self.assertEqual(["C.cs:3"], inventory["unresolvedRequestSources"])
        self.assertEqual(
            json.dumps(inventory, ensure_ascii=False, sort_keys=True),
            json.dumps(inventory, ensure_ascii=False, sort_keys=True),
        )


if __name__ == "__main__":
    unittest.main()
```

- [x] **Step 2: Run scanner tests to prove the import is missing**

Run:

```bash
python3 -m unittest tools/test_client_protocol_inventory.py
```

Expected: FAIL with `ModuleNotFoundError: No module named 'client_protocol_inventory'`.

- [x] **Step 3: Implement the deterministic scanner**

Create `tools/client_protocol_inventory.py`:

```python
#!/usr/bin/env python3
import argparse
import json
import re
from collections import defaultdict
from pathlib import Path

CLIENT_VERSION = "9.2.2"
CONST_RE = re.compile(r"public\s+const\s+int\s+([A-Z0-9_]+)\s*=\s*(\d+)\s*;")
SEND_RE = re.compile(
    r"\.Send(?:<[^>]+>)?\s*\(\s*(?:NetCommandDef\.)?([A-Za-z_][A-Za-z0-9_]*|\d+)\b"
)
RAW_SEND_RE = re.compile(
    r"SendRawObject\s*\(\s*(?:NetCommandDef\.)?([A-Za-z_][A-Za-z0-9_]*|\d+)\b"
)
OBSERVER_RE = re.compile(
    r"\.AddObserver\s*\(\s*(?:NetCommandDef\.)?([A-Za-z_][A-Za-z0-9_]*|\d+)\b"
)


def parse_command_constants(text):
    names = defaultdict(list)
    for name, number in CONST_RE.findall(text):
        names[int(number)].append(name)
    return {command_id: sorted(values) for command_id, values in sorted(names.items())}


def _line_number(text, offset):
    return text.count("\n", 0, offset) + 1


def _source_ref(root, path, text, offset):
    return f"{path.relative_to(root).as_posix()}:{_line_number(text, offset)}"


def _resolve(token, names_by_id):
    if token.isdigit():
        return int(token)
    for command_id, names in names_by_id.items():
        if token in names:
            return command_id
    return None


def scan_client_sources(root, names_by_id):
    root = Path(root)
    requests = defaultdict(set)
    receives = defaultdict(set)
    unresolved_requests = set()
    for path in sorted(root.rglob("*.cs")):
        text = path.read_text(encoding="utf-8", errors="replace")
        for pattern in (SEND_RE, RAW_SEND_RE):
            for match in pattern.finditer(text):
                command_id = _resolve(match.group(1), names_by_id)
                ref = _source_ref(root, path, text, match.start())
                if command_id is None:
                    unresolved_requests.add(ref)
                else:
                    requests[command_id].add(ref)
        for match in OBSERVER_RE.finditer(text):
            command_id = _resolve(match.group(1), names_by_id)
            if command_id is not None:
                receives[command_id].add(_source_ref(root, path, text, match.start()))
    found = {
        command_id: {
            "requestSources": sorted(requests[command_id]),
            "receiveSources": sorted(receives[command_id]),
        }
        for command_id in sorted(set(requests) | set(receives))
    }
    found["unresolvedRequestSources"] = sorted(unresolved_requests)
    return found


def build_inventory(constants, discovered, capture_index):
    all_ids = set(constants)
    all_ids.update(command_id for command_id in discovered if isinstance(command_id, int))
    all_ids.update(int(command_id) for command_id in capture_index)
    commands = []
    for command_id in sorted(all_ids):
        sources = discovered.get(command_id, {})
        captures = capture_index.get(str(command_id), {})
        commands.append(
            {
                "id": command_id,
                "names": constants.get(command_id, []),
                "requestSources": sources.get("requestSources", []),
                "receiveSources": sources.get("receiveSources", []),
                "captureSendCount": int(captures.get("send", 0)),
                "captureReceiveCount": int(captures.get("recv", 0)),
            }
        )
    return {
        "clientVersion": CLIENT_VERSION,
        "commands": commands,
        "unresolvedRequestSources": discovered.get("unresolvedRequestSources", []),
    }


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--client-root", type=Path, required=True)
    parser.add_argument("--capture-index", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    constants_file = args.client_root / "Game.Network" / "Tenth.Network" / "NetCommandDef.cs"
    constants = parse_command_constants(constants_file.read_text(encoding="utf-8"))
    captures = json.loads(args.capture_index.read_text(encoding="utf-8"))
    inventory = build_inventory(constants, scan_client_sources(args.client_root, constants), captures)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(
        json.dumps(inventory, ensure_ascii=False, sort_keys=True, indent=2) + "\n",
        encoding="utf-8",
    )
    print(f"commands={len(inventory['commands'])} output={args.output}")


if __name__ == "__main__":
    main()
```

- [x] **Step 4: Run scanner unit tests**

Run:

```bash
python3 -m unittest tools/test_client_protocol_inventory.py
```

Expected: PASS with `Ran 3 tests`.

- [x] **Step 5: Generate and inspect the checked-in 9.2.2 inventory**

Run:

```bash
python3 tools/client_protocol_inventory.py \
  --client-root /Users/bytedance/stzb/stzb_9.2.2_out_branch_9.1.1776213/assets/decompiled \
  --capture-index /Users/bytedance/stzb/tools/monitor-agent/samples/protocol/index.json \
  --output src/main/resources/protocol/client-9.2.2-command-inventory.json

python3 - <<'PY'
import json
from pathlib import Path

inventory = json.loads(Path("src/main/resources/protocol/client-9.2.2-command-inventory.json").read_text())
ids = [row["id"] for row in inventory["commands"]]
assert inventory["clientVersion"] == "9.2.2"
assert ids == sorted(ids)
assert len(ids) == len(set(ids))
assert len(ids) >= 2591
assert 5025 in ids and 5026 in ids and 90005 in ids
print(f"commands={len(ids)} unresolved_request_sites={len(inventory['unresolvedRequestSources'])}")
PY
```

Expected: one scanner summary followed by `commands=<number at least 2591>`.

- [x] **Step 6: Commit the inventory foundation**

```bash
git add \
  tools/client_protocol_inventory.py \
  tools/test_client_protocol_inventory.py \
  src/main/resources/protocol/client-9.2.2-command-inventory.json
git diff --cached --check
git commit -m "feat: inventory client protocol commands"
```

### Task 2: Add the Kotlin Contract Model and Validated Registry

**Files:**
- Create: `src/main/kotlin/com/stzb/server/protocol/CommandContract.kt`
- Create: `src/main/kotlin/com/stzb/server/protocol/CommandContractCatalog.kt`
- Create: `src/test/kotlin/com/stzb/server/protocol/CommandContractRegistryTest.kt`

**Interfaces:**
- Consumes: `protocol/client-9.2.2-command-inventory.json`, `Cmd` constants, and the explicit response-shape id set from `NetworkResponsePolicy`.
- Produces: `CommandContractCatalog.registry`, `CommandContractRegistry.contract(cmdId)`, `CommandContractRegistry.all()`, and `CommandContractRegistry.isShapeResponseAllowed(cmdId)`.

- [x] **Step 1: Write failing registry tests**

Create `src/test/kotlin/com/stzb/server/protocol/CommandContractRegistryTest.kt`:

```kotlin
package com.stzb.server.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CommandContractRegistryTest {
    @Test
    fun `production registry contains every generated 9 2 2 inventory command`() {
        val registry = CommandContractCatalog.registry
        val all = registry.all()

        assertTrue(all.size >= 2_591)
        assertEquals(all.map(CommandContract::id).sorted(), all.map(CommandContract::id))
        assertNotNull(registry.contract(Cmd.GET_WORLD_SCENCE_INFO))
        assertNotNull(registry.contract(Cmd.SEND_WORLD_SCENCE_FULL_INFO))
        assertNotNull(registry.contract(Cmd.SYS_NOTIFY_DB_UPDATE))
    }

    @Test
    fun `existing handler and emitted commands stay provisional until audited`() {
        val registry = CommandContractCatalog.registry

        assertEquals(CommandStatus.PROVISIONAL, registry.contract(Cmd.CARD_RECRUIT)?.status)
        assertEquals(CommandStatus.PROVISIONAL, registry.contract(Cmd.GET_WORLD_SCENCE_INFO)?.status)
        assertEquals(CommandStatus.PROVISIONAL, registry.contract(Cmd.CHAT)?.status)
        assertEquals(CommandStatus.PROVISIONAL, registry.contract(Cmd.SYS_NOTIFY_DB_UPDATE)?.status)
        assertEquals(CommandStatus.PROVISIONAL, registry.contract(Cmd.SEND_WORLD_SCENCE_FULL_INFO)?.status)
        assertEquals(CommandStatus.PROVISIONAL, registry.contract(Cmd.NOTIFY_CHAT_MSG)?.status)
    }

    @Test
    fun `recorded shape command is eligible but unknown command is not`() {
        val registry = CommandContractCatalog.registry

        assertEquals(CommandStatus.OBSERVED_SHAPE, registry.contract(959)?.status)
        assertTrue(registry.isShapeResponseAllowed(959))
        assertTrue(!registry.isShapeResponseAllowed(45_678))
    }

    @Test
    fun `exact contracts require ownership shape projection and evidence`() {
        val inventory = ClientCommandInventory(
            clientVersion = "test",
            commands = listOf(ClientCommandInventoryEntry(id = 1)),
        )

        assertFailsWith<IllegalArgumentException> {
            CommandContractRegistry(
                inventory = inventory,
                overrides = listOf(
                    CommandContract(
                        id = 1,
                        names = listOf("ONE"),
                        direction = CommandDirection.CLIENT_REQUEST,
                        domain = CommandDomain.WORLD,
                        status = CommandStatus.EXACT,
                    ),
                ),
            )
        }
    }
}
```

- [x] **Step 2: Run registry tests and confirm the model is missing**

Run:

```bash
./gradlew -Dkotlin.compiler.execution.strategy=in-process test \
  --tests com.stzb.server.protocol.CommandContractRegistryTest
```

Expected: FAIL because `CommandContractCatalog`, `CommandContractRegistry`, and inventory model types do not exist.

- [x] **Step 3: Implement inventory loading and registry validation**

Create `src/main/kotlin/com/stzb/server/protocol/CommandContract.kt` with the shared interfaces above and these required behaviors:

```kotlin
private val mapper = jacksonObjectMapper()

private fun inferredDirection(entry: ClientCommandInventoryEntry): CommandDirection =
    when {
        entry.requestSources.isNotEmpty() && entry.receiveSources.isNotEmpty() -> CommandDirection.DUPLEX
        entry.requestSources.isNotEmpty() || entry.captureSendCount > 0 -> CommandDirection.CLIENT_REQUEST
        else -> CommandDirection.SERVER_PUSH
    }

class CommandContractRegistry(
    inventory: ClientCommandInventory,
    overrides: Collection<CommandContract>,
) {
    private val byId: Map<Int, CommandContract>

    init {
        require(inventory.clientVersion == "9.2.2")
        require(inventory.commands.map { it.id }.distinct().size == inventory.commands.size)
        require(overrides.map { it.id }.distinct().size == overrides.size)
        require(overrides.all { override -> inventory.commands.any { it.id == override.id } })

        val overrideById = overrides.associateBy(CommandContract::id)
        byId = inventory.commands.associate { entry ->
            val baseline = CommandContract(
                id = entry.id,
                names = entry.names,
                direction = inferredDirection(entry),
                domain = CommandDomain.UNKNOWN,
                status = CommandStatus.UNIMPLEMENTED,
            )
            entry.id to (overrideById[entry.id]?.copy(
                names = overrideById.getValue(entry.id).names.ifEmpty { entry.names },
            ) ?: baseline)
        }
        byId.values.forEach(::validate)
    }

    fun contract(cmdId: Int): CommandContract? = byId[cmdId]

    fun all(): List<CommandContract> = byId.values.sortedBy(CommandContract::id)

    fun isShapeResponseAllowed(cmdId: Int): Boolean =
        contract(cmdId)?.status == CommandStatus.OBSERVED_SHAPE

    private fun validate(contract: CommandContract) {
        if (contract.status != CommandStatus.EXACT) return
        require(!contract.owner.isNullOrBlank()) { "exact ${contract.id} has no owner" }
        require(!contract.requestShape.isNullOrBlank() || contract.direction == CommandDirection.SERVER_PUSH) {
            "exact ${contract.id} has no request shape"
        }
        require(contract.responseSequence.isNotEmpty()) { "exact ${contract.id} has no response sequence" }
        require(contract.stateProjection.isNotEmpty()) { "exact ${contract.id} has no projection" }
        require(contract.evidence.any { it.kind == "SOURCE" }) { "exact ${contract.id} has no source evidence" }
        require(contract.evidence.any { it.kind == "SERVER_TEST" }) { "exact ${contract.id} has no server test evidence" }
    }

    companion object {
        fun loadFromClasspath(): ClientCommandInventory =
            requireNotNull(CommandContractRegistry::class.java.classLoader.getResourceAsStream(
                "protocol/client-9.2.2-command-inventory.json",
            )).use { stream ->
                mapper.readValue(stream, ClientCommandInventory::class.java)
            }
    }
}
```

Implement `CommandContractCatalog.kt` with:

```kotlin
object CommandContractCatalog {
    val registry: CommandContractRegistry by lazy {
        CommandContractRegistry(
            inventory = CommandContractRegistry.loadFromClasspath(),
            overrides = mergedOverrides(),
        )
    }

    private fun mergedOverrides(): List<CommandContract> =
        (observedShapeContracts() + rejectedContracts() + provisionalHandlerContracts() + provisionalPushContracts())
            .associateBy(CommandContract::id)
            .values
            .sortedBy(CommandContract::id)

    private fun provisionalHandlerContracts(): List<CommandContract> =
        listOf(
            Cmd.SYS_HEART_BEAT, Cmd.SYS_ACKNOWLEDGE, Cmd.SYS_CHECK_SID,
            Cmd.SYS_PLATFORM_LOGIN_CHECK, Cmd.GET_ALL_SERVER_INFO_NEW,
            Cmd.GET_CLASSIC_AND_YOUTH_SERVER_LIST, Cmd.SYS_PRE_SERVER_TOKEN_CHECK,
            Cmd.SYS_LOGIN, Cmd.RANDOM_ROLE_NAME, Cmd.CREATE_ROLE, Cmd.GET_SERVER_TIME,
            Cmd.SYNC_SERVER_TIME, Cmd.BATTLE_REPORT_PROFILE, Cmd.BATTLE_REPORT_DETAIL,
            Cmd.BATTLE_REPORT_SHORT_DETAIL, Cmd.UNION_CREATE, Cmd.UNION_INFO,
            Cmd.UNION_MEMBER, Cmd.GET_HOMEPAGE_INFO, Cmd.CHAT, Cmd.CHAT_HISTORY,
            Cmd.ARMY_BATTLE, Cmd.BUILD_BUILDING, Cmd.UPGRADE_BUILDING, Cmd.LAND_INFO,
            Cmd.GET_USER_NPC_ARMY, Cmd.GET_LAND_NPC_ARMY, Cmd.GET_LAND_DEFEND_ARMY,
            Cmd.ADD_HERO_TO_ARMY, Cmd.REMOVE_HERO_FROM_ARMY, Cmd.SWITCH_HERO_IN_ARMY,
            Cmd.CONSCRIPT, Cmd.CONSCRIPT_IMMEDIATELY, Cmd.LEARN_HERO_SKILL,
            Cmd.REPLACE_HERO_SKILL, Cmd.FORGET_HERO_SKILL, Cmd.REMOVE_USER_SKILL,
            Cmd.CARD_RECRUIT, Cmd.CARD_QUICK_RECRUIT, Cmd.CARD_SET_ALL_NOT_NEW,
            Cmd.HERO_SELECT_FACADE, Cmd.HERO_USE_CARD_BORDER, Cmd.ROTATE_CARD_BORDER_ADD,
            Cmd.ROTATE_CARD_BORDER_REMOVE, Cmd.HERO_ACTIVE_CARD_BORDER,
            Cmd.BATCH_ACTIVE_ARMY_FACADE_CARD, Cmd.UNLOCK_TROOP_FACADE_CARD,
            Cmd.USE_TROOP_FACADE_CARD, Cmd.HERO_ACTIVE_FACADE, Cmd.HERO_ADVANCE,
            Cmd.GEAR_EQUIP, Cmd.GEAR_FORGET, Cmd.CARD_ADD_POINT, Cmd.CARD_WASH_POINT,
            Cmd.CARD_PROTECT, Cmd.CARD_SAVE_POINT_PLAN, Cmd.CARD_CHANGE_POINT_PLAN,
            Cmd.CARD_EXTRACT_SWITCH, Cmd.CARD_SELECT_HERO, Cmd.GET_WORLD_SCENCE_INFO,
            Cmd.SYS_PING, Cmd.QUERY_ARMY_RELATED_FORT, Cmd.SET_CLIENT_RED_DOT_DATA,
            Cmd.SET_FRONT_UNLOCK_ANIM, Cmd.USER_CHANGE_NAME, Cmd.HERO_TEAM_LIBRARY,
            Cmd.NORMAL_TEAM_COMPOSITION, Cmd.WORLD_BOSS_SAVE_TEAM,
            Cmd.EXERCISE_DAILY_SAVE_TEAM,
        ).distinct().map { id ->
            CommandContract(
                id = id,
                names = emptyList(),
                direction = CommandDirection.DUPLEX,
                domain = CommandDomain.UNKNOWN,
                status = CommandStatus.PROVISIONAL,
                owner = "GameServerHandler",
            )
        }

    private fun provisionalPushContracts(): List<CommandContract> =
        listOf(
            Cmd.SYS_NOTIFY_SID,
            Cmd.SYS_NOTIFY_DB_UPDATE,
            Cmd.SEND_WORLD_SCENCE_FULL_INFO,
            Cmd.NOTIFY_CHAT_MSG,
        ).map { id ->
            CommandContract(
                id = id,
                names = emptyList(),
                direction = CommandDirection.SERVER_PUSH,
                domain = CommandDomain.UNKNOWN,
                status = CommandStatus.PROVISIONAL,
                owner = "GameServerHandler",
            )
        }

    private fun observedShapeContracts(): List<CommandContract> =
        NetworkResponsePolicy.observedShapeCommandIds().map { id ->
            CommandContract(
                id = id,
                names = emptyList(),
                direction = CommandDirection.CLIENT_REQUEST,
                domain = CommandDomain.UNKNOWN,
                status = CommandStatus.OBSERVED_SHAPE,
                owner = "NetworkResponsePolicy",
            )
        }

    private fun rejectedContracts(): List<CommandContract> =
        listOf(98_765).map { id ->
            CommandContract(
                id = id,
                names = emptyList(),
                direction = CommandDirection.CLIENT_REQUEST,
                domain = CommandDomain.EXTERNAL,
                status = CommandStatus.REJECTED,
                owner = "LocalPrivilegePolicy",
            )
        }
}
```

When provisional and observed lists overlap, build the catalog map with
`PROVISIONAL` taking precedence; `observedShapeCommandIds` remains available
for direct acknowledgements that are part of a provisional handler flow.

- [x] **Step 4: Run registry tests**

Run:

```bash
./gradlew -Dkotlin.compiler.execution.strategy=in-process test \
  --tests com.stzb.server.protocol.CommandContractRegistryTest
```

Expected: PASS with four tests.

- [x] **Step 5: Commit the registry**

```bash
git add \
  src/main/kotlin/com/stzb/server/protocol/CommandContract.kt \
  src/main/kotlin/com/stzb/server/protocol/CommandContractCatalog.kt \
  src/test/kotlin/com/stzb/server/protocol/CommandContractRegistryTest.kt
git diff --cached --check
git commit -m "feat: register client command contracts"
```

### Task 3: Gate Shape Responses Through Explicit Contracts

**Files:**
- Modify: `src/main/kotlin/com/stzb/server/protocol/NetworkResponsePolicy.kt:7-156`
- Modify: `src/main/kotlin/com/stzb/server/handler/GameServerHandler.kt:1252-1267,1422-1427`
- Modify: `src/test/kotlin/com/stzb/server/protocol/NetworkResponsePolicyTest.kt:12-208`
- Modify: `src/test/kotlin/com/stzb/server/protocol/CapturedShapeTest.kt:32-126`
- Modify: `src/test/kotlin/com/stzb/server/handler/GameServerHandlerProtocolTest.kt:54-558`

**Interfaces:**
- Consumes: `CommandContractCatalog.registry` and the existing explicit recorded shape data.
- Produces: `NetworkResponsePolicy.observedShapeCommandIds()`, `NetworkResponsePolicy.observedShapeBody(cmdId, requestBody)`, and registry-controlled unresolved-command behavior.

- [x] **Step 1: Replace the generic fallback regression tests**

In `NetworkResponsePolicyTest.kt`, replace the first two tests with:

```kotlin
@Test
fun `explicit recorded array command returns its observed shape`() {
    assertEquals("[]", NetworkResponsePolicy.observedShapeBody(959))
}

@Test
fun `unregistered command has no shape response`() {
    assertNull(NetworkResponsePolicy.observedShapeBody(45_678))
    assertTrue(!CommandContractCatalog.registry.isShapeResponseAllowed(45_678))
}

@Test
fun `privileged test command is rejected rather than treated as a no op`() {
    assertEquals(CommandStatus.REJECTED, CommandContractCatalog.registry.contract(98_765)?.status)
    assertNull(NetworkResponsePolicy.observedShapeBody(98_765))
}
```

Change every remaining test call from `fallbackBody(...)` to
`observedShapeBody(...)`, except tests that assert handler-owned commands
return `null`. Those tests must assert that the registry status is
`PROVISIONAL`.

Append this EmbeddedChannel test to `GameServerHandlerProtocolTest.kt`:

```kotlin
@Test
fun `unknown command is logged without fabricated success response`() {
    val channel = newChannel()

    channel.writeInbound(upPacket(45_678, "[]"))

    assertNull(channel.readOutbound<Any>())
    channel.finishAndReleaseAll()
}
```

- [x] **Step 2: Run the focused tests and confirm the expected failure**

Run:

```bash
./gradlew -Dkotlin.compiler.execution.strategy=in-process test \
  --tests com.stzb.server.protocol.NetworkResponsePolicyTest \
  --tests com.stzb.server.protocol.CapturedShapeTest \
  --tests com.stzb.server.handler.GameServerHandlerProtocolTest
```

Expected: FAIL because `observedShapeBody` and
`observedShapeCommandIds` do not exist, and the old broad fallback still
returns `[]` for `45678`.

- [x] **Step 3: Convert the response policy to an explicit shape catalog**

In `NetworkResponsePolicy.kt`:

```kotlin
fun observedShapeCommandIds(): Set<Int> =
    booleanCommands +
        jsonNullCommands +
        scalarNumberCommands.keys +
        stringCommands +
        dictionaryCommands +
        fixedTupleCommands.keys +
        pagedListCommands +
        objectResultCommands +
        noOpArrayCommands +
        setOf(
            Cmd.UNION_INFO, Cmd.UNION_CREATE, Cmd.GET_HOMEPAGE_INFO,
            212, 502, 5013, 4979, 3877, 4968, 5091, 6092,
        )

fun observedShapeBody(cmdId: Int, requestBody: String? = null): String? =
    when {
        cmdId == Cmd.UNION_INFO -> GenericGameResponses.unionInfoUnavailable()
        cmdId == Cmd.UNION_CREATE -> "0"
        cmdId == 212 -> GenericGameResponses.userLookup()
        cmdId == 502 -> "[1,\"\"]"
        cmdId == 3686 -> ProfileResponses.homepageInfo()
        cmdId == 5013 -> roleLookup(requestBody)
        cmdId == 4979 -> nameLookup(requestBody)
        cmdId in booleanCommands -> "true"
        cmdId in jsonNullCommands -> "null"
        cmdId in scalarNumberCommands -> scalarNumberCommands.getValue(cmdId)
        cmdId in stringCommands -> "\"\""
        cmdId in fixedTupleCommands -> fixedTupleCommands.getValue(cmdId)
        cmdId == 3877 -> "[${GameServerConfig.SERVER_ID}]"
        cmdId == 4968 -> "[false,[]]"
        cmdId == 5091 -> "200"
        cmdId == 6092 -> "[[],0]"
        cmdId in dictionaryCommands -> GenericGameResponses.emptyObject()
        cmdId in pagedListCommands -> GenericGameResponses.emptyPagedList()
        cmdId in objectResultCommands -> GenericGameResponses.emptyObjectResult()
        cmdId in noOpArrayCommands -> GenericGameResponses.emptyArray()
        else -> null
    }
```

Delete `isBusinessCommand`. No method in this file may branch on the range
`1..99999`.

Replace `logUnhandledOrFallback` in `GameServerHandler.kt` with:

```kotlin
private fun logUnhandledOrFallback(ctx: ChannelHandlerContext, msg: UpPacket) {
    val contract = CommandContractCatalog.registry.contract(msg.cmdId)
    val response = contract
        ?.takeIf { it.status == CommandStatus.OBSERVED_SHAPE }
        ?.let { NetworkResponsePolicy.observedShapeBody(msg.cmdId, msg.bodyText) }
    if (response == null) {
        log.warn(
            "unhandled command cmd=${msg.cmdId} status=${contract?.status ?: "UNKNOWN"} " +
                "idx=${msg.cmdIndex} uid=${msg.userId} checkOk=${msg.checkOk}",
        )
        return
    }
    log.warn("shape-only command cmd=${msg.cmdId} status=${contract.status}")
    ctx.writeAndFlush(DownPacket.json(msg.cmdId, response, dataType = DownType.PLAIN))
}
```

Replace `sendRecordedAcknowledgement` with:

```kotlin
private fun sendRecordedAcknowledgement(ctx: ChannelHandlerContext, msg: UpPacket) {
    val response = requireNotNull(
        NetworkResponsePolicy.observedShapeBody(msg.cmdId, msg.bodyText),
    ) { "missing recorded acknowledgement shape for ${msg.cmdId}" }
    ctx.writeAndFlush(DownPacket.json(msg.cmdId, response, dataType = DownType.PLAIN))
    log.info(">> cmd=${msg.cmdId} protocol acknowledgement (${responseShape(response)})")
}
```

Add imports for `CommandContractCatalog` and `CommandStatus`.

- [x] **Step 4: Run focused fallback and handler tests**

Run:

```bash
./gradlew -Dkotlin.compiler.execution.strategy=in-process test \
  --tests com.stzb.server.protocol.CommandContractRegistryTest \
  --tests com.stzb.server.protocol.NetworkResponsePolicyTest \
  --tests com.stzb.server.protocol.CapturedShapeTest \
  --tests com.stzb.server.handler.GameServerHandlerProtocolTest
```

Expected: PASS. The unknown command test sees no outbound `DownPacket`; all
captured shape tests retain their prior top-level type and tuple sizes.

- [x] **Step 5: Commit explicit response gating**

```bash
git add \
  src/main/kotlin/com/stzb/server/protocol/NetworkResponsePolicy.kt \
  src/main/kotlin/com/stzb/server/handler/GameServerHandler.kt \
  src/test/kotlin/com/stzb/server/protocol/NetworkResponsePolicyTest.kt \
  src/test/kotlin/com/stzb/server/protocol/CapturedShapeTest.kt \
  src/test/kotlin/com/stzb/server/handler/GameServerHandlerProtocolTest.kt
git diff --cached --check
git commit -m "fix: reject unregistered protocol requests"
```

### Task 4: Publish a Deterministic Contract Coverage Report

**Files:**
- Create: `src/main/kotlin/com/stzb/server/protocol/CommandCoverageReport.kt`
- Modify: `build.gradle.kts:1-31`
- Create: `src/test/kotlin/com/stzb/server/protocol/CommandCoverageReportTest.kt`

**Interfaces:**
- Consumes: `CommandContractCatalog.registry`.
- Produces: a Markdown report at `build/reports/protocol/command-coverage.md` and
  `CommandCoverageReport.render(registry): String`.

- [x] **Step 1: Write failing report tests**

Create `src/test/kotlin/com/stzb/server/protocol/CommandCoverageReportTest.kt`:

```kotlin
package com.stzb.server.protocol

import kotlin.test.Test
import kotlin.test.assertTrue

class CommandCoverageReportTest {
    @Test
    fun `report lists all commands by id and exposes unfinished statuses`() {
        val report = CommandCoverageReport.render(CommandContractCatalog.registry)

        assertTrue(report.startsWith("# 9.2.2 Command Coverage"))
        assertTrue(report.contains("| 5025 |"))
        assertTrue(report.contains("| 5026 |"))
        assertTrue(report.contains("| 90005 |"))
        assertTrue(report.contains("UNIMPLEMENTED"))
        assertTrue(report.contains("PROVISIONAL"))
        assertTrue(report.indexOf("| 2 |") < report.indexOf("| 5025 |"))
    }
}
```

- [x] **Step 2: Run the report test and confirm red**

Run:

```bash
./gradlew -Dkotlin.compiler.execution.strategy=in-process test \
  --tests com.stzb.server.protocol.CommandCoverageReportTest
```

Expected: FAIL because `CommandCoverageReport` does not exist.

- [x] **Step 3: Implement the report renderer and Gradle task**

Create `src/main/kotlin/com/stzb/server/protocol/CommandCoverageReport.kt`:

```kotlin
package com.stzb.server.protocol

import java.nio.file.Files
import java.nio.file.Path

object CommandCoverageReport {
    fun render(registry: CommandContractRegistry): String = buildString {
        appendLine("# 9.2.2 Command Coverage")
        appendLine()
        appendLine("| cmd | names | direction | domain | status | request sources | receive sources | captures send/recv |")
        appendLine("|---:|---|---|---|---|---:|---:|---:|")
        registry.all().forEach { contract ->
            val inventory = CommandContractRegistry.loadFromClasspath().commands
                .single { it.id == contract.id }
            appendLine(
                "| ${contract.id} | ${contract.names.joinToString(",")} | ${contract.direction} | " +
                    "${contract.domain} | ${contract.status} | ${inventory.requestSources.size} | " +
                    "${inventory.receiveSources.size} | ${inventory.captureSendCount}/${inventory.captureReceiveCount} |",
            )
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val output = Path.of(args.singleOrNull() ?: "build/reports/protocol/command-coverage.md")
        Files.createDirectories(output.parent)
        Files.writeString(output, render(CommandContractCatalog.registry))
        println("wrote $output")
    }
}
```

Optimize the implementation by indexing the inventory once before the loop:

```kotlin
val inventoryById = CommandContractRegistry.loadFromClasspath()
    .commands
    .associateBy(ClientCommandInventoryEntry::id)
```

Then append this to `build.gradle.kts`:

```kotlin
tasks.register<JavaExec>("protocolCoverageReport") {
    group = "verification"
    description = "Writes the 9.2.2 command contract coverage report."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.stzb.server.protocol.CommandCoverageReport")
    args(layout.buildDirectory.file("reports/protocol/command-coverage.md").get().asFile.absolutePath)
}
```

- [x] **Step 4: Run report test and generate the artifact**

Run:

```bash
./gradlew -Dkotlin.compiler.execution.strategy=in-process test \
  --tests com.stzb.server.protocol.CommandCoverageReportTest
./gradlew -Dkotlin.compiler.execution.strategy=in-process protocolCoverageReport
sed -n '1,20p' build/reports/protocol/command-coverage.md
```

Expected: test passes; the report begins with the Markdown table and contains
the statuses `UNIMPLEMENTED`, `PROVISIONAL`, and `OBSERVED_SHAPE`.

- [x] **Step 5: Commit reporting support**

```bash
git add \
  build.gradle.kts \
  src/main/kotlin/com/stzb/server/protocol/CommandCoverageReport.kt \
  src/test/kotlin/com/stzb/server/protocol/CommandCoverageReportTest.kt
git diff --cached --check
git commit -m "feat: report protocol contract coverage"
```

### Task 5: Verify the Foundation and Record the First Domain Backlog

**Files:**
- Modify: `src/test/kotlin/com/stzb/server/protocol/CommandContractRegistryTest.kt`

**Interfaces:**
- Consumes: generated inventory, contract registry, coverage report, and all focused test suites.
- Produces: a verified protocol-foundation baseline and the generated report
  used to prepare the next `Transport and login` specification.

- [x] **Step 1: Write the foundation completion test**

Append to `CommandContractRegistryTest.kt`:

```kotlin
@Test
fun `every inventory entry has one explicit status and observed shapes have bodies`() {
    val registry = CommandContractCatalog.registry

    registry.all().forEach { contract ->
        assertTrue(contract.status in CommandStatus.entries, "missing status for ${contract.id}")
        if (contract.status == CommandStatus.OBSERVED_SHAPE) {
            assertNotNull(NetworkResponsePolicy.observedShapeBody(contract.id), "missing shape body for ${contract.id}")
        }
    }
}
```

- [x] **Step 2: Run the completion test to verify the inventory and policy agree**

Run:

```bash
./gradlew -Dkotlin.compiler.execution.strategy=in-process test \
  --tests com.stzb.server.protocol.CommandContractRegistryTest
```

Expected: PASS. Every generated command has one effective status and each
`OBSERVED_SHAPE` status has an explicit response body.

- [x] **Step 3: Run all protocol-foundation checks**

Run:

```bash
python3 -m unittest tools/test_client_protocol_inventory.py
./gradlew -Dkotlin.compiler.execution.strategy=in-process test \
  --tests com.stzb.server.protocol.CommandContractRegistryTest \
  --tests com.stzb.server.protocol.NetworkResponsePolicyTest \
  --tests com.stzb.server.protocol.CapturedShapeTest \
  --tests com.stzb.server.protocol.CommandCoverageReportTest \
  --tests com.stzb.server.handler.GameServerHandlerProtocolTest
./gradlew -Dkotlin.compiler.execution.strategy=in-process protocolCoverageReport
./gradlew -Dkotlin.compiler.execution.strategy=in-process installDist
```

Expected: every command succeeds; the report exists at
`build/reports/protocol/command-coverage.md`; `installDist` produces
`build/install/stzb-server/lib/stzb-server-0.1.0.jar`.

- [x] **Step 4: Commit the verified foundation**

```bash
git add \
  src/test/kotlin/com/stzb/server/protocol/CommandContractRegistryTest.kt
git diff --cached --check
git commit -m "test: verify protocol contract foundation"
```
