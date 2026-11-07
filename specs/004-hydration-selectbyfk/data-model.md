# Data Model: HydrationEmitter SELECT Routing

This is a refactor of SQL *rendering*; the domain model is unchanged.

## Current hand-built SELECTs (HydrationEmitter)

| Loader | SQL | Projection |
|---|---|---|
| `emitEntityCollectionLoader` | `SELECT <cols> FROM <child> WHERE <fk> = :parentId` | `column AS alias` (id/scalars/assoc-FKs) |
| `emitElementCollectionLoader` | `SELECT * FROM <child> WHERE <fk> = :parentId` | `*` |
| `emitCompositionAssociationLoader` | `SELECT <cols> FROM <child> WHERE <id> = :childId` | `column AS alias` |

## Changes

- Add `QueryDefinition.SelectByFk(table, fkColumn)` (and a projection source) rendered by
  `QueryCompiler.selectByFk`, exposed via `SqlBuilder.selectByFk(...)`.
- The entity-collection loader and element-collection loader route through `selectByFk`.
- The composing-association loader routes through the by-identifier renderer (`selectById`).

## Invariants

- The `column AS alias` projection (property-name alias for id/scalar, FK-column alias for
  associations) is preserved byte-for-byte.
- Generated SQL byte-identical; hydration integration tests pass unchanged.
