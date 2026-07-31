# World Chat Display Repair Design

## Goal

Make normal world-channel chat messages display immediately in the client and remain visible after the client refreshes its chat history during the same server process lifetime.

## Scope

- Server-side Kotlin implementation only.
- Support normal world chat (`cmd 710`, server channel `0`).
- Deliver new messages through `cmd 2100`.
- Serve recent world messages through `cmd 711`.
- Keep the most recent bounded number of world messages in memory.

## Non-goals

- No client modifications.
- No chat persistence across server restart.
- No support for non-world chat channels beyond the existing fallback behavior.
- No redesign of moderation, rate limiting, or social systems.

## Protocol Contract

### `cmd 710`

The server accepts the client request, assigns a monotonically increasing chat ID, and replies with `[false,0]`.

For a normal world message, it then broadcasts `cmd 2100` with `DownType.XOR`. The payload follows the official 46-element chat record layout. The required world-chat fields are:

| Index | Field | Value |
| --- | --- | --- |
| 0 | chat ID | server-generated ID |
| 1 | server channel | `0` |
| 2 | subtype | request subtype |
| 3 | user ID | authenticated player ID |
| 4 | user name | player role name |
| 5 | content | request content |
| 6 | timestamp | current epoch seconds |
| 10 | subtype params | request params |
| 11 | union position | `0` |
| 12 | channel ID indeed | request value or `0` |
| 13 | server ID | `GameServerConfig.SERVER_ID` |
| 19 | head icon | `0` |
| 20 | head frame | empty string |
| 21 | role ID | `role_<userId>` |
| 22-45 | optional metadata | type-correct defaults matching official message shape |

### `cmd 711`

The server returns the client history container in `ChatDefine.sChatHistoryChannelList` order. The world-channel slot contains records as `[chatId, chatValueWithoutId]`, because the client rebuilds a full record through `CombineIDandVO`.

The history response preserves the same message fields as `2100`; it does not deliver a second message format.

## Components

- `WorldChatStore`: a synchronized, bounded in-memory store for complete world-chat records.
- `GameServerHandler.sendChat`: validates the existing request shape, creates a canonical record, writes it to `WorldChatStore`, acknowledges `710`, and broadcasts its `2100` representation.
- `GameServerHandler.sendChatHistory`: maps the world-chat snapshot to the `711` response layout.

Keeping the canonical record in one store avoids drift between realtime delivery and history replay.

## Error Handling

- Malformed chat payload fields retain current defaults.
- Empty content is accepted only if sent by the client; the server does not invent message text.
- Unknown channels retain the current broadcast behavior but are not retained in world history.
- The world history is bounded; oldest messages are discarded first.

## Verification

1. A protocol test asserts normal world chat produces a 46-field XOR `2100` record matching the official field contract.
2. A protocol test asserts `711` returns the newly sent world message in the expected history slot and nested record form.
3. A two-session test asserts the sender and another online player receive the same canonical `2100` message.
4. A TCP smoke test exercises login, `710`, and `711` against an isolated server process.
