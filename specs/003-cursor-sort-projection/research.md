# Research: FindEmitter Cursor Sort/Projection (#26)

## Scope

Internal, behavior-preserving refactor of the cursor-paging to-many sort/projection. "Research" here
resolves how the hand-built SQL maps onto a single `CursorSortProjection` plan rendered by the core.

## Current hand-built SQL (FindEmitter)

1. **Base SELECT shell** — root columns (scalars/id/version/association-FK/embedded) plus `MIN`/`MAX`
   aggregate expressions per to-many sort term, then `FROM` + `LEFT JOIN` + `GROUP BY`.
2. **ORDER BY / SEEK terms** — aliases for `ORDER BY`, full aggregate/correlated expressions for the
   keyset `WHERE`.
3. **Aggregate expressions** — re-enumerated for the derived-table wrap of an aggregate seek.

## Decision 1: CursorSortProjection shape

- **Decision**: A `CursorSortProjection` record holds the to-many sort terms (property path +
  ascending), the join (child table + FK column), the projection columns (root + aggregates), and the
  group-by columns.
- **Rationale**: One place describes the to-many sort/projection; `QueryCompiler` renders it.
- **Alternatives**: Keep the hand-built SQL in `FindEmitter` — rejected (violates single-renderer).

## Decision 2: renderer responsibility

- **Decision**: `QueryCompiler` renders `SELECT`/`GROUP BY`/`LEFT JOIN`/aggregate/`ORDER BY`/`SEEK`
  from the plan; `TypeDefinition.columns()` supplies the root projection; `aggregateSortTerm`
  supplies the correlated sort term.
- **Rationale**: These are the existing single implementations the issue points to.

## Decision 3: FindEmitter delegates

- **Decision**: `FindEmitter` builds a `CursorSortProjection` from the `SortSpec`s + entity metadata
  and passes it to `SqlBuilder` / `QueryDefinition.Read`.
- **Rationale**: `FindEmitter` stops assembling SQL text entirely.

## Decision 4: order stability

- **Decision**: Preserve exact column/term/aggregate order; generated SQL byte-identical.
- **Rationale**: `ToManySortTest` and `CursorSeekEmissionTest` assert exact SQL.

## Residual unknowns

The exact `CursorSortProjection` field set (and whether it nests in `QueryDefinition.Read`) is
finalized during implementation, once `QueryCompiler.aggregateSortTerm` and `TypeDefinition.columns()`
are inspected against the three `FindEmitter` sites.
