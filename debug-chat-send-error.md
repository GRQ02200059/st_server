# Debug Session: chat-send-error
- **Status**: [OPEN]
- **Issue**: Clicking send in chat produces a client error instead of posting the message.
- **Debug Server**: http://127.0.0.1:7778/event
- **Log File**: .dbg/trae-debug-log-chat-send-error.ndjson

## Reproduction Steps
1. Log in with the Android user 999 client.
2. Open a chat channel.
3. Enter a short text message and tap send.
4. Capture command 710, its response, command 2100, and client/server errors.

## Hypotheses & Verification
| ID | Hypothesis | Likelihood | Effort | Evidence |
|----|------------|------------|--------|----------|
| A | Command 710 returns a response shape rejected by the current client. | High | Low | Rejected |
| B | The real command 710 payload differs from the server parser assumptions. | High | Low | Rejected |
| C | Command 2100 uses an incompatible chat record shape or field type. | High | Medium | Confirmed |
| D | The client is connected to a stale or different server process. | Medium | Low | Rejected |
| E | Channel, power, or role validation returns a business error. | Medium | Low | Rejected |

## Log Evidence
- Line 1: The live user 999 client sent command 710 as
  `[1,0,"弄",[[]],0,0,"","",0,"",""]`; the server returned `[false,0]`,
  then pushed a 46-field command 2100 record.
- The client parser requires index 21 to be a string, index 23 to be an
  integer, index 24 to be a string, index 39 to be an integer, and index 40
  to be a string. The live record has the opposite types at all five indexes.
- A captured official command 2100 ordinary chat record has 48 fields and
  matches the parser types.

## Verification Conclusion
The command 710 request and acknowledgement are valid. The error occurs when
the client parses the malformed command 2100 notification. The server record
builder shifts several fields and omits the final two fields.

## Fix Status
- Updated the canonical chat record and command 2100 notification to the
  client's 48-field contract.
- Verified the regression test failed before the fix with
  `expected: <48> but was: <46>`.
- Verified 17 handler protocol tests and 2 world chat store tests pass after
  the fix.
- Built `build/install/stzb-server/lib/stzb-server-0.1.0.jar`.
- SHA-256:
  `1884e8a4d6ce7c2a7ae8f6ffedd7a66459198a3c01b3e3c184d89abebf813f63`.
- Pending: manually restart the game server and confirm the user 999 client
  can send chat without an error. Keep this session open until confirmed.
