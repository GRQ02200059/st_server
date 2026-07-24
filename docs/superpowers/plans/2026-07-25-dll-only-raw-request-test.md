# DLL-Only Raw Request Test Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Produce a single private-development `Game.Core.dll` that enables the existing inner-package purchase path only while the client is manually in DevMode.

**Architecture:** Create an isolated Python patcher that maps CLR method RVAs through `assembly_fixed`, verifies the unmodified body in `assembly_extracted`, and replaces only `Tenth.GameConfig.get_IsInnerPackageDebugMode`. The existing client `client <cmd> <json-array>` GM command remains the raw request sender; no UI, session, NPK, APK, or server changes are required.

**Tech Stack:** Python 3 standard library, `dnfile`, Mono PE/CLI metadata, `unittest`.

## Global Constraints

- Modify only the output copy of `Game.Core.dll`; never change `assets/assembly_extracted/`.
- `get_IsInnerPackageDebugMode` must return the existing `get_DevMode` result, not a constant.
- Support tiny and fat IL method headers; the target getter is tiny.
- Verify source and fixed method bytes before patching.
- Deliver `assets/assembly_security_tests/Game.Core.dll` as the only client artifact.
- Do not rebuild `assembly.npk` or APK, sign an APK, add a UI, alter `get_DevMode`, alter uid/SID/session handling, or modify the Kotlin server.
- The raw GM sender accepts JSON arrays only and continues to use the active client connection.

---

### Task 1: Add a Test-First Method-Level Patch Contract

**Files:**
- Create: `tools/test_patch_security_tests.py`
- Test: `tools/test_patch_security_tests.py`

**Interfaces:**
- Consumes: `tools/patch_security_tests.py` module with `patch_inner_package_debug_mode(source, fixed, output) -> PatchResult`, `find_method(pe, type_name, method_name) -> MethodLocation`, and `read_method_body(data, offset) -> MethodBody`.
- Produces: A regression contract for the exact target token, tiny-header replacement, source immutability, and output-only changes.

- [ ] **Step 1: Write the failing test**

Create `tools/test_patch_security_tests.py`:

```python
from __future__ import annotations

import hashlib
import sys
import tempfile
import unittest
from pathlib import Path

import dnfile


ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "stzb_9.2.2_out_branch_9.1.1776213" / "assets"
TOOLS = Path(__file__).resolve().parent
sys.path.insert(0, str(TOOLS))

import patch_security_tests as patcher


class PatchInnerPackageDebugModeTest(unittest.TestCase):
    def test_replaces_only_inner_package_debug_getter_with_dev_mode_call(self) -> None:
        source = ASSETS / "assembly_extracted" / "Game.Core.dll"
        fixed = ASSETS / "assembly_fixed" / "Game.Core.dll"

        fixed_pe = dnfile.dnPE(str(fixed))
        target = patcher.find_method(
            fixed_pe,
            "Tenth.GameConfig",
            "get_IsInnerPackageDebugMode",
        )
        dev_mode = patcher.find_method(
            fixed_pe,
            "Tenth.GameConfig",
            "get_DevMode",
        )
        source_bytes = source.read_bytes()
        original_body = patcher.read_method_body(source_bytes, target.file_offset)
        expected_il = b"\x28" + dev_mode.token.to_bytes(4, "little") + b"\x2a"

        self.assertTrue(original_body.is_tiny)
        self.assertNotEqual(expected_il, original_body.il)

        with tempfile.TemporaryDirectory() as temporary_directory:
            output = Path(temporary_directory) / "Game.Core.dll"
            result = patcher.patch_inner_package_debug_mode(source, fixed, output)
            patched = output.read_bytes()
            patched_body = patcher.read_method_body(patched, target.file_offset)

        self.assertEqual(expected_il, patched_body.il)
        self.assertTrue(patched_body.is_tiny)
        self.assertEqual(6, patched_body.code_size)
        self.assertEqual(hashlib.sha256(source_bytes).hexdigest(), result.source_sha256)
        self.assertEqual(hashlib.sha256(patched).hexdigest(), result.patched_sha256)

        unchanged_prefix = source_bytes[:target.file_offset]
        unchanged_suffix = source_bytes[original_body.end_offset:]
        self.assertEqual(unchanged_prefix, patched[:target.file_offset])
        self.assertEqual(unchanged_suffix, patched[original_body.end_offset:])


if __name__ == "__main__":
    unittest.main()
```

- [ ] **Step 2: Run the test to verify it fails**

Run:

```bash
python3 tools/test_patch_security_tests.py
```

Expected: the test runner fails with `ModuleNotFoundError: No module named 'patch_security_tests'`. This proves the contract is not satisfied before the patcher exists.

- [ ] **Step 3: Commit the failing test**

```bash
git add tools/test_patch_security_tests.py
git commit -m "test: define DLL-only patch contract"
```

### Task 2: Implement the Isolated DLL Patcher

**Files:**
- Create: `tools/patch_security_tests.py`
- Modify: `tools/test_patch_security_tests.py`
- Test: `tools/test_patch_security_tests.py`

**Interfaces:**
- Consumes: The test contract from Task 1 and `assembly_extracted/Game.Core.dll` plus `assembly_fixed/Game.Core.dll`.
- Produces: `patch_inner_package_debug_mode(source: Path, fixed: Path, output: Path) -> PatchResult` and CLI `python3 tools/patch_security_tests.py --out-dir <directory>`.

- [ ] **Step 1: Write the minimal implementation**

Create `tools/patch_security_tests.py`:

```python
#!/usr/bin/env python3
"""Build the DLL-only private-server raw-request test artifact."""

from __future__ import annotations

import argparse
import hashlib
import struct
from dataclasses import dataclass
from pathlib import Path

import dnfile


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_ASSETS = ROOT / "stzb_9.2.2_out_branch_9.1.1776213" / "assets"
TARGET_TYPE = "Tenth.GameConfig"
TARGET_METHOD = "get_IsInnerPackageDebugMode"
DEV_MODE_METHOD = "get_DevMode"


@dataclass(frozen=True)
class MethodLocation:
    token: int
    rva: int
    file_offset: int


@dataclass(frozen=True)
class MethodBody:
    is_tiny: bool
    header_size: int
    code_size: int
    il_offset: int
    end_offset: int
    il: bytes


@dataclass(frozen=True)
class PatchResult:
    source_sha256: str
    patched_sha256: str
    target_token: int
    dev_mode_token: int


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def find_method(pe: dnfile.dnPE, type_name: str, method_name: str) -> MethodLocation:
    for type_def in pe.net.mdtables.TypeDef.rows:
        full_name = f"{type_def.TypeNamespace}.{type_def.TypeName}"
        if full_name != type_name:
            continue
        for method_index in type_def.MethodList:
            method = method_index.row
            if str(method.Name) == method_name:
                return MethodLocation(
                    token=0x06000000 | method_index.row_index,
                    rva=method.Rva,
                    file_offset=pe.get_offset_from_rva(method.Rva),
                )
    raise RuntimeError(f"method not found: {type_name}::{method_name}")


def read_method_body(data: bytes | bytearray, file_offset: int) -> MethodBody:
    first = data[file_offset]
    format_bits = first & 0x3
    if format_bits == 0x2:
        code_size = first >> 2
        il_offset = file_offset + 1
        return MethodBody(
            is_tiny=True,
            header_size=1,
            code_size=code_size,
            il_offset=il_offset,
            end_offset=il_offset + code_size,
            il=bytes(data[il_offset:il_offset + code_size]),
        )
    if format_bits == 0x3:
        header = struct.unpack_from("<H", data, file_offset)[0]
        header_size = (header >> 12) * 4
        code_size = struct.unpack_from("<I", data, file_offset + 4)[0]
        if header_size < 12:
            raise RuntimeError(f"invalid fat method header at 0x{file_offset:x}")
        il_offset = file_offset + header_size
        return MethodBody(
            is_tiny=False,
            header_size=header_size,
            code_size=code_size,
            il_offset=il_offset,
            end_offset=il_offset + code_size,
            il=bytes(data[il_offset:il_offset + code_size]),
        )
    raise RuntimeError(f"unsupported method header at 0x{file_offset:x}")


def replace_method_body(
    data: bytearray,
    fixed: bytes,
    location: MethodLocation,
    replacement_il: bytes,
) -> None:
    source_body = read_method_body(data, location.file_offset)
    fixed_body = read_method_body(fixed, location.file_offset)
    if source_body != fixed_body:
        raise RuntimeError("source and fixed target method bodies differ")
    if len(replacement_il) > source_body.code_size:
        raise RuntimeError("replacement IL does not fit original method body")

    if source_body.is_tiny:
        if len(replacement_il) > 63:
            raise RuntimeError("tiny method replacement is too large")
        data[location.file_offset] = (len(replacement_il) << 2) | 0x2
    else:
        struct.pack_into("<I", data, location.file_offset + 4, len(replacement_il))
    data[source_body.il_offset:source_body.il_offset + len(replacement_il)] = replacement_il


def patch_inner_package_debug_mode(source: Path, fixed: Path, output: Path) -> PatchResult:
    fixed_pe = dnfile.dnPE(str(fixed))
    target = find_method(fixed_pe, TARGET_TYPE, TARGET_METHOD)
    dev_mode = find_method(fixed_pe, TARGET_TYPE, DEV_MODE_METHOD)
    source_bytes = source.read_bytes()
    patched = bytearray(source_bytes)
    replacement_il = b"\x28" + dev_mode.token.to_bytes(4, "little") + b"\x2a"

    replace_method_body(patched, fixed.read_bytes(), target, replacement_il)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_bytes(patched)
    return PatchResult(
        source_sha256=sha256(source_bytes),
        patched_sha256=sha256(patched),
        target_token=target.token,
        dev_mode_token=dev_mode.token,
    )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--assets-dir", type=Path, default=DEFAULT_ASSETS)
    parser.add_argument("--out-dir", type=Path, default=DEFAULT_ASSETS / "assembly_security_tests")
    args = parser.parse_args()

    assets = args.assets_dir.resolve()
    result = patch_inner_package_debug_mode(
        assets / "assembly_extracted" / "Game.Core.dll",
        assets / "assembly_fixed" / "Game.Core.dll",
        args.out_dir.resolve() / "Game.Core.dll",
    )
    print(f"Game.Core.dll: {result.patched_sha256}")


if __name__ == "__main__":
    main()
```

- [ ] **Step 2: Run the regression test to verify it passes**

Run:

```bash
python3 tools/test_patch_security_tests.py
```

Expected: `Ran 1 test` followed by `OK`.

- [ ] **Step 3: Run syntax validation**

Run:

```bash
python3 -m py_compile tools/patch_security_tests.py tools/test_patch_security_tests.py
```

Expected: exit status `0` with no output.

- [ ] **Step 4: Commit the patcher and green test**

```bash
git add tools/patch_security_tests.py tools/test_patch_security_tests.py
git commit -m "feat: add DLL-only raw request test patcher"
```

### Task 3: Generate and Verify the DLL-Only Artifact

**Files:**
- Create: `../stzb_9.2.2_out_branch_9.1.1776213/assets/assembly_security_tests/Game.Core.dll`
- Test: `tools/test_patch_security_tests.py`

**Interfaces:**
- Consumes: `patch_inner_package_debug_mode()` from Task 2.
- Produces: One modified DLL with only `get_IsInnerPackageDebugMode` changed.

- [ ] **Step 1: Generate the delivery DLL**

Run:

```bash
python3 tools/patch_security_tests.py \
  --out-dir ../stzb_9.2.2_out_branch_9.1.1776213/assets/assembly_security_tests
```

Expected: one line in the form `Game.Core.dll: <64-character-sha256>`.

- [ ] **Step 2: Verify the delivery directory contains only the DLL**

Run:

```bash
find ../stzb_9.2.2_out_branch_9.1.1776213/assets/assembly_security_tests \
  -maxdepth 1 -type f -print
```

Expected: exactly:

```text
../stzb_9.2.2_out_branch_9.1.1776213/assets/assembly_security_tests/Game.Core.dll
```

- [ ] **Step 3: Re-run the regression test against a temporary output**

Run:

```bash
python3 tools/test_patch_security_tests.py
```

Expected: `Ran 1 test` followed by `OK`.

- [ ] **Step 4: Inspect the final IL header and code bytes**

Run:

```bash
python3 - <<'PY'
from pathlib import Path
import sys
sys.path.insert(0, "tools")
import dnfile
import patch_security_tests as patcher

assets = Path("../stzb_9.2.2_out_branch_9.1.1776213/assets")
fixed = assets / "assembly_fixed" / "Game.Core.dll"
output = assets / "assembly_security_tests" / "Game.Core.dll"
pe = dnfile.dnPE(str(fixed))
location = patcher.find_method(pe, "Tenth.GameConfig", "get_IsInnerPackageDebugMode")
dev_mode = patcher.find_method(pe, "Tenth.GameConfig", "get_DevMode")
body = patcher.read_method_body(output.read_bytes(), location.file_offset)
expected = b"\x28" + dev_mode.token.to_bytes(4, "little") + b"\x2a"
assert body.is_tiny
assert body.il == expected
print(f"token=0x{location.token:08x} il={body.il.hex()}")
PY
```

Expected:

```text
token=0x060000a2 il=285c0000062a
```

- [ ] **Step 5: Commit the final delivery preparation code only**

```bash
git status --short
git add tools/patch_security_tests.py tools/test_patch_security_tests.py
git commit -m "test: verify DLL-only delivery artifact"
```

Do not add the generated DLL to the server repository. It is a requested delivery artifact outside the repository.

## Plan Self-Review

- **Spec coverage:** Task 2 implements the manual-DevMode inner-package gate, Task 3 produces only the requested DLL, and the global constraints exclude NPK/APK, UI, session, and server changes.
- **Placeholder scan:** No unresolved implementation placeholders are present.
- **Type consistency:** The test imports the three public patcher interfaces with the same names and argument types created in Task 2.
