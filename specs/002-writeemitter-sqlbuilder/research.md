# Research: WriteEmitter Child-Table SQL Routing (#27)

## Scope

Internal, behavior-preserving refactor. "Research" here means resolving how each raw SQL site maps
onto the existing core renderer, against the current `WriteEmitter`/`SqlBuilder`/`QueryDefinition`
shape.

## Raw SQL sites (evidence)

1. `buildCascadeDeleteCodeBlock` — `DELETE FROM <childTable> WHERE <fkColumn> = :parentId`.
2. `buildDiffSyncCodeBlock` — `DELETE FROM <childTable> WHERE <idColumn> = :childId AND <fkColumn> = :parentId`
   plus `SELECT <idColumn> FROM <childTable> WHERE <fkColumn> = :parentId`.
3. `buildElementCollectionReplaceCodeBlock` — `DELETE FROM <childTable> WHERE <fkColumn> = :parentId`
   plus `INSERT INTO <childTable> (<cols>) VALUES (<:params>)`.

## Decision 1: simple child-table DELETE

- **Decision**: Use the existing `SqlBuilder.deleteByFk(table, fkColumn)` (already used by
  `emitCascadePreDelete`) for the `buildCascadeDeleteCodeBlock` and element-collection-replace
  deletes.
- **Rationale**: Identical shape (`WHERE fk = :parentId`); no new renderer needed.
- **Alternatives**: A new `QueryDefinition.DeleteByFk` — unnecessary when the SQL is already rendered.

## Decision 2: diff-sync orphan DELETE (id + fk)

- **Decision**: Add a `SqlBuilder.deleteChildByIdAndFk(...)` (or a `QueryDefinition.DeleteByFk`
  carrying both predicates) for the two-column delete.
- **Rationale**: The two-column shape (`id = :childId AND fk = :parentId`) is not covered by the
  existing single-column `deleteByFk`, so a small renderer addition is warranted.
- **Alternatives**: Reuse `deleteByFk` with an extra predicate — rejected: bypasses the renderer's
  quoting/binding conventions.

## Decision 3: element-collection INSERT

- **Decision**: Add a child-table `SqlBuilder.insert(...)` overload (or `QueryDefinition.Insert`)
  that takes the dynamic column/param list already computed by `collectLeaves`.
- **Rationale**: The column set is dynamic (flattened value-object leaves), so the renderer must
  accept an explicit column/param list rather than a `TypeDefinition`.
- **Alternatives**: Keep the manual `INSERT INTO ... VALUES` — rejected: it is the raw SQL to remove.

## Decision 4: select existing child IDs

- **Decision**: Route the `SELECT <idColumn> ... WHERE fk = :parentId` through `SqlBuilder`
  (a `selectByFk`-style renderer) or `QueryDefinition.Read`.
- **Rationale**: Same rendering path as the delete it feeds; keeps id/FK columns consistent.

## Decision 5: order stability

- **Decision**: Preserve the exact column and `:param` order produced today; generated SQL must be
  byte-identical.
- **Rationale**: `WriteEmissionTest` asserts exact SQL; any reordering is out of scope.

## Residual unknowns

None. No `[NEEDS CLARIFICATION]` remain.
