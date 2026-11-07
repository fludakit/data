# Data Model: FindEmitter Cursor Sort/Projection

This is a refactor of SQL *rendering*; the domain model is unchanged. This records the plan type and
what changes.

## CursorSortProjection (new)

A plan type describing a cursor-paged to-many sort + projection:

- **sort terms** — the to-many sort specs (property path + ascending direction).
- **join** — the child table + foreign-key column for the `LEFT JOIN` hydration.
- **projection columns** — the root columns plus the `MIN`/`MAX` aggregate expressions.
- **group-by columns** — the root columns the aggregate groups on.

## Renderer surface (unchanged, but unified)

- `QueryCompiler` renders `SELECT`/`GROUP BY`/`LEFT JOIN`/aggregate/`ORDER BY`/`SEEK` from the plan.
- `TypeDefinition.columns()` enumerates the root projection.
- `QueryCompiler.aggregateSortTerm` renders the correlated sort term (no-hydration case).

## Changes

| Site | Hand-built SQL today | After |
|---|---|---|
| `FindEmitter` base SELECT shell | root columns + aggregates + `FROM` + `LEFT JOIN` + `GROUP BY` | rendered from `CursorSortProjection` |
| `FindEmitter` ORDER BY/SEEK terms | aliases + full aggregate/correlated expressions | derived from the plan |
| `FindEmitter` aggregate expressions | re-enumerated for the derived-table wrap | derived from the plan |

## Invariants

- Generated SQL byte-identical before/after.
- `aggregateSortTerm` and `columns()` are the single implementations.
