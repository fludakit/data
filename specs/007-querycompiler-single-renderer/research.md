# Research: QueryCompiler Single Renderer (#28 item 3)

## Decision: route classic helpers through SqlFragment

- **Decision**: `selectBy`/`countBy`/`deleteBy` build a `SqlFragment` (adding a table-alias form) and
  render via `SqlFragment.render`; `appendWhere` adds conjuncts to the accumulator, wrapping the OR
  user expression as one conjunct when a cursor clause follows; `aggregateSortTerm` calls `SqlFragment.joins`.
- **Rationale**: one accumulator + one join renderer; the `Conjunct` shape lives once.
- **Alternatives**: keep the `StringBuilder` path — rejected (it is the duplication the finding flags).

## Residual unknowns

None — `SqlBuilderBuildTest` pins the byte-identical classic output.
