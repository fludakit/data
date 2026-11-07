# Research: SqlBuilder pagedSeekStatement Aliasing (#28 item 5)

## Decision: node-level aliasing with parallel `orderByTerms`

- **Decision**: `pagedSeekStatement` takes `orderByTerms` (parallel to `terms`) and renders the ORDER BY
  in core; the derived-table wrap maps each term exactly — aggregate -> `inner_.agg_N` (detected by content
  match on the parallel seek term), root-qualified -> prefix swap via `repoint`.
- **Rationale**: `String.replace` is a global find-replace that can hit unrelated occurrences; a prefix
  swap on one term plus a content match for aggregates is exact and order-independent.
- **Alternatives**: keep the pre-rendered string and split/re-parse it — rejected (still string surgery);
  assume "first N terms are aggregates" — rejected (breaks when a local sort precedes a to-many sort).

## Residual unknowns

None — the parallel-term contract is already what `FindEmitter` builds.
