# Data Model: WriteEmitter Child-Table SQL Routing

This is a refactor of SQL *rendering*; the domain model is unchanged. This records the renderer
surface involved and what changes.

## Current renderer surface

- **SqlBuilder** — renders SQL strings for a `TypeDefinition` (or explicit tables/columns):
  - `insert(boolean includeId)` — root insert.
  - `update()` — root update.
  - `upsert()` — root upsert.
  - `deleteById()` — delete by identifier.
  - `deleteByFk(table, fkColumn)` — child-table delete by foreign key.
  - `selectForeignKey(fkColumn)` — load a composing FK.
- **QueryDefinition** — immutable plan types (`Insert`, `DeleteByFk`, `DeleteWhere`, `Read`, …).

## Changes (no shape change to the model)

| Site | Raw SQL today | After |
|---|---|---|
| `buildCascadeDeleteCodeBlock` | `DELETE … WHERE fk = :parentId` | `SqlBuilder.deleteByFk(childTable, fkColumn)` |
| `buildDiffSyncCodeBlock` (delete) | `DELETE … WHERE id = :childId AND fk = :parentId` | `SqlBuilder.deleteChildByIdAndFk(...)` (new) |
| `buildDiffSyncCodeBlock` (select) | `SELECT id … WHERE fk = :parentId` | `SqlBuilder.selectByFk(...)` (new) |
| `buildElementCollectionReplaceCodeBlock` (delete) | `DELETE … WHERE fk = :parentId` | `SqlBuilder.deleteByFk(childTable, fkColumn)` |
| `buildElementCollectionReplaceCodeBlock` (insert) | `INSERT … (<cols>) VALUES (<:params>)` | `SqlBuilder.insert(<cols>, <params>)` overload (new) |

## Invariants

- Generated SQL byte-identical before/after.
- Child bindings stay on the existing `.param(...)` helpers.
- Quoting and parameter naming match the core renderer.
