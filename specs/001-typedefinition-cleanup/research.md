# Research: TypeDefinition Cleanup (#24 + #25)

## Scope

This is an internal, behavior-preserving refactor. No external libraries or unfamiliar
technologies are introduced. "Research" here means resolving the design decisions for the
polymorphic `id()` accessor and the `Prop.Visitor` traversal against the current code.

## Decision 1: How to expose `id()` polymorphically

- **Decision**: Declare `Prop.Id id()` on the sealed `TypeDefinition` interface. `Entity` and
  `Aggregate` satisfy it via their existing record accessors; `ValueObject` overrides it to
  return `null`.
- **Rationale**: The record accessors already provide `id()` on `Entity`/`Aggregate`; only the
  base lacks the method and `ValueObject` lacks an id. No `instanceof` is needed; each permit
  answers directly.
- **Alternatives considered**:
  - `default Prop.Id id() { ... instanceof Entity e ? e.id() : ... instanceof Aggregate a ? a.id() : null; }` —
    rejected: reintroduces the `instanceof` chain this refactor removes.
  - A separate `idOrThrow()` that throws on value objects — deferred; call sites all tolerate
    null today, so `id()` returning null matches existing behavior.

## Decision 2: Prop traversal via `Prop.Visitor<R>`

- **Decision**: Add a `Prop.Visitor<R>` (or a `Prop.visit(Prop.Visitor<R>)`) with one method per
  `Prop` kind, and a single `accept` dispatch on `Prop`. The two column walkers
  (`addColumnsRecursive`/`addColumnRefsRecursive`) become one visitor; `PathResolver` delegates
  property dispatch to the visitor and keeps only join/cycle decisions.
- **Rationale**: Collapses the two near-duplicate recursive walks and the second `leafColumn`
  switch into a single, exhaustively-checked traversal. Java 21 sealed types make the visitor
  compile-checked (no default fall-through).
- **Alternatives considered**:
  - Switch-pattern-matching at each call site — rejected: would keep duplicate switch arms in
    `addColumnsRecursive`, `addColumnRefsRecursive`, and `PathResolver.leafColumn`.
  - Keep `PathResolver` switch and only dedupe the walkers — rejected: leaves `leafColumn`'s
    second dispatch in place, failing the "one traversal" acceptance criterion.

## Decision 3: Pushing `hasId`/`idColumn`/`ownsAggregateLifecycle` down

- **Decision**: Replace the three `instanceof`-based default methods with polymorphic methods:
  `Entity`/`Aggregate` share implementations (via their common shape), `ValueObject` returns
  `false`/`null`/`false`.
- **Rationale**: Matches the sealed-hierarchy intent (each variant answers its own shape) and
  removes the last `instanceof` dispatch in the interface body.
- **Alternatives considered**: A single shared default using the newly-added `id()` — partially
  works (`hasId()` = `id() != null`) but still needs `ownsAggregateLifecycle()` to distinguish
  `Aggregate`, so full push-down is cleaner.

## Decision 4: Order stability guarantee

- **Decision**: The visitor must visit `Prop` children in exactly the declaration order produced
  by `properties()`; `ColumnRef`/`ColumnPlacement` output must be unchanged.
- **Rationale**: The existing tests (notably `TypeDefinitionTest`, `JoinEdgeTest`,
  `MutationPlanTest`, `SqlBuilderBuildTest`) assert exact column ordering; byte-identical output
  is an explicit success criterion.
- **Alternatives considered**: Re-ordering id/version/scalar/embedded — rejected; out of scope
  and would change generated SQL.

## Residual unknowns

None. No `[NEEDS CLARIFICATION]` remain.
