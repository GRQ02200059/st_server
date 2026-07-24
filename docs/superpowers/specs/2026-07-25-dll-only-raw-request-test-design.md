# DLL-Only Raw Request Test Design

## Purpose

Provide a private-server test DLL that enables the client's existing inner-package
purchase path without enabling DevMode or GM UI by default. Reuse the existing GM
console `client` command for manual raw request submission.

## Scope

- Modify only `Game.Core.dll`.
- Replace `Tenth.GameConfig.get_IsInnerPackageDebugMode` with a call to the
  existing `get_DevMode` getter.
- Deliver only the modified `Game.Core.dll` under
  `assets/assembly_security_tests/`.

The package does not rebuild `assembly.npk` or the APK, sign an APK, add a UI,
modify `get_DevMode`, modify session identity fields, or change server code.

## Runtime Behavior

1. The operator manually enables the client's existing DevMode path and opens
   the existing GM command panel.
2. The client considers itself in inner-package debug mode only while that
   existing DevMode flag is true.
3. The normal purchase branch may therefore issue its existing `98765` request.
4. The existing `client <cmd> <json-array>` GM command parses the JSON array and
   calls `NetManager.Send` with the current connection's session context.
5. Replaying a request means deliberately submitting the same `client` command
   again. The server remains responsible for deduplication and authorization.

`client` accepts JSON arrays. This matches the current request shapes for
`98765`, `91001`, building, conscription, and battle commands. Non-array JSON
values are deliberately out of scope.

## Patch Design

The patcher reads method metadata and RVA from `assembly_fixed/Game.Core.dll`,
then applies a six-byte IL body to the matching location in
`assembly_extracted/Game.Core.dll`:

```text
call Tenth.GameConfig::get_DevMode
ret
```

Before writing output, the patcher verifies that the original and fixed method
layouts and IL bytes are identical. It changes no other method body.

## Verification

- A test first proves the original getter is not the target two-instruction body.
- The patcher produces a DLL in a dedicated output directory.
- The test confirms the method body has the expected `call` token and `ret`,
  and that all bytes outside that method body match the original DLL.
- A SHA-256 digest of the output is recorded by the test run, but the delivery
  directory contains only the modified DLL.

## Constraints and Risks

The raw command only establishes that a request can be generated through the
normal client network path. A response or game-state change is not proof that a
server-side vulnerability exists. Commands without a server handler can only be
observed as reachable or rejected.
