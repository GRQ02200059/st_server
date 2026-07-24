# Hero Card Capacity Design

## Goal

Remove the client's default hero-card bag limit of approximately 200 for the
local development server.

## Client Contract

The client reads `Tb_user_stuff.hero_card_max` from field index `63` and uses
the larger of that value and its configured default capacity. The field is an
`int`.

## Server Change

`UserInitTableBuilder.tbUserStuff` will include field `63` in the `99991`
login snapshot, with `PlayerResources.UNLIMITED_AMOUNT` (`2_000_000_000`).

The value is below the signed 32-bit maximum and far above the available hero
catalog, so it effectively removes the limit without relying on overflow.

## Scope

Only the login snapshot changes. Card-pack selection, recruiting, hero
creation, persistence, and `90005` incremental updates remain unchanged.

The client must fully reconnect so it discards the old table snapshot and
loads the new `Tb_user_stuff` row.

## Verification

Add a snapshot test that asserts `Tb_user_stuff[63]` equals
`PlayerResources.UNLIMITED_AMOUNT`. Run the focused test and the full Gradle
test suite before rebuilding the server distribution.
