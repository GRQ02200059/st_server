# Army Command Capacity Design

## Goal

Set the local development server's army command capacity to 10.0.

## Client Contract

The client reads `Tb_build_effect_city.army_cost_max` at field index `26` and
divides the integer value by 10. Therefore, a displayed command capacity of
10.0 requires the protocol value `100`.

## Server Change

Use `100` for `army_cost_max` in both server response paths:

- `UserInitTableBuilder` login snapshot (`99991`) and role-creation
  success response, which reuses that snapshot.
- `GameResponses.userBuildUpsertNotify` building-upgrade delta (`90005`).

Keeping both paths aligned prevents a newly created role or an upgraded city
from observing the old 3.0 capacity.

## Scope

Do not change army count, hero costs, hero assignment, card packs, or battle
logic.

## Verification

Add assertions that both response builders emit `100` at field index `26`.
Run focused tests, then the complete Gradle check and distribution build.
