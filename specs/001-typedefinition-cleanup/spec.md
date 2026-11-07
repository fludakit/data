# Feature Specification: TypeDefinition Cleanup (polymorphic id + Prop.Visitor)

**Feature Branch**: `001-typedefinition-cleanup`

**Created**: 2026-11-06

**Status**: Draft

**Input**: User description: "Refactor `TypeDefinition` to a single polymorphic identifier accessor and a single `Prop.Visitor` traversal, removing `instanceof` dispatch and duplicated helper code (GitHub issues #24 and #25)."

## User Scenarios & Testing *(mandatory)*

The "users" here are the maintainers of the processor and core modules. Each story is an
independently testable slice of the refactor and preserves generated output exactly.

### User Story 1 - Single identifier accessor (Priority: P1)

As a maintainer of the processor emitters, I want to retrieve an entity's identifier
property through a single `TypeDefinition.id()` method, so I no longer copy the same
`idProp` helper into every emitter and no longer branch on `instanceof Entity/Aggregate`.

**Why this priority**: It removes seven duplicated implementations that drift independently,
and it is the natural prerequisite for the broader `Prop.Visitor` cleanup.

**Independent Test**: Build the project and run the core unit, processor emission, and
integration test suites; confirm every former `idProp` call site now calls
`TypeDefinition.id()` and the generated output is unchanged.

**Acceptance Scenarios**:

1. **Given** a `TypeDefinition` for an entity with an `@Identity` field, **When** an emitter
   calls `def.id()`, **Then** it receives that `Prop.Id` without any
   `instanceof Entity/Aggregate` dispatch.
2. **Given** a `TypeDefinition` for a value object with no identifier, **When** `def.id()`
   is called, **Then** it returns null (the documented "no id" sentinel).
3. **Given** the codebase, **When** the seven duplicated `idProp`/`idPropertyName` helpers
   are removed, **Then** every former call site resolves the id through the single
   `TypeDefinition.id()` implementation.
4. **Given** a full build, **When** emission tests run, **Then** the generated repository
   code is byte-identical to before the change.

---

### User Story 2 - Single property traversal (Priority: P2)

As a maintainer, I want to traverse `TypeDefinition` properties through one `Prop.Visitor`,
so column walking and path resolution no longer duplicate `instanceof` dispatch or
near-identical recursive walks.

**Why this priority**: It consolidates the largest source of `instanceof` duplication in
`TypeDefinition` and removes the second `leafColumn` switch; it builds on P1 but is
independently valuable.

**Independent Test**: Run the core unit tests (`TypeDefinitionTest`, `JoinEdgeTest`,
`MutationPlanTest`) plus the emission tests; confirm `ColumnRef`/`ColumnPlacement` semantics
are unchanged.

**Acceptance Scenarios**:

1. **Given** any `TypeDefinition`, **When** a `Prop.Visitor<R>` visits it, **Then** each
   property (Scalar, Id, Version, Association, Embedded, EntityCollection, ElementCollection)
   is dispatched polymorphically with no `instanceof` chain.
2. **Given** the column walkers (`addColumnsRecursive`/`addColumnRefsRecursive`), **When**
   they run, **Then** both use the single visitor traversal.
3. **Given** `PathResolver`, **When** it resolves an attribute path, **Then** it delegates
   property dispatch to the visitor and keeps only join/cycle decisions.
4. **Given** `hasId`, `idColumn`, and `ownsAggregateLifecycle`, **When** queried, **Then**
   each is a polymorphic method on the sealed base, not an `instanceof` branch.

---

### Edge Cases

- A `@ValueObject` (no identity) must resolve `id()` to null without a special-case branch
  outside `TypeDefinition`.
- A `Prop.Id` carrying generation metadata must survive the visitor round-trip unchanged.
- Nested/embedded value objects and element collections must be visited in the same order as
  before (order stability is part of behavior preservation).
- `PathResolver` must still reject non-local attribute paths with the existing exceptions.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: `TypeDefinition` MUST expose a polymorphic `id()` (or equivalent) returning the
  identifier property, implemented once on the sealed base and returning null for value objects.
- **FR-002**: The seven duplicated `idProp`/`idPropertyName` helpers in the emitters and
  `RepositoryVisitor` MUST be deleted; all call sites MUST use `TypeDefinition.id()`.
- **FR-003**: No `instanceof Entity/Aggregate` identifier extraction MAY remain outside
  `TypeDefinition`.
- **FR-004**: A single `Prop.Visitor<R>` (or `Prop.visit(visitor)`) MUST cover every `Prop` kind.
- **FR-005**: `TypeDefinition`'s column walkers and `PathResolver` MUST use the single visitor
  traversal; `PathResolver` MUST retain only join/cycle decisions.
- **FR-006**: `hasId`/`idColumn`/`ownsAggregateLifecycle` MUST be polymorphic methods, not
  `instanceof` branches.
- **FR-007**: `ColumnRef` and `ColumnPlacement` semantics MUST remain unchanged.
- **FR-008**: Generated repository output MUST be byte-identical; all existing unit, emission,
  and integration tests MUST pass unmodified.

### Key Entities *(include if feature involves data)*

- **TypeDefinition** — the sealed model of an entity or value object and its properties; the
  target of the polymorphic `id()` and the visitor.
- **Prop** — the sealed property hierarchy (Scalar, Id, Version, Association, Embedded,
  EntityCollection, ElementCollection); the target of `Prop.Visitor`.
- **Prop.Visitor** — the single traversal abstraction to be introduced.
- **ColumnRef / ColumnPlacement** — the column-projection results whose semantics must be preserved.
- **PathResolver** — attribute-path resolution to be reduced to join/cycle decisions.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Zero `instanceof Entity`/`instanceof Aggregate` dispatch sites remain inside
  `TypeDefinition`.
- **SC-002**: The seven duplicated identifier helpers collapse to one implementation.
- **SC-003**: 100% of existing core unit, processor emission, and integration tests pass
  without modification.
- **SC-004**: Generated repository code is byte-identical (verified by the emission test suite)
  before and after the change.

## Assumptions

- This is a behavior-preserving internal refactor; no public API or generated-code change is intended.
- The sealed `Entity`/`Aggregate` hierarchy shape is retained; only the dispatch mechanism changes.
- The existing test suites are the authoritative definition of "behavior preserved."
- GitHub issues #24 (polymorphic id) and #25 (Prop.Visitor) are delivered together; #24 is the P1
  slice and #25 the P2 slice.

## Scope Extensions (added during implementation)

The implementation extended the scope above with follow-ups not covered by the
original requirements/tasks:

- **`TypeDefinition.Visitor`** — a visitor over the `TypeDefinition` kinds
  (entity, aggregate, value object), used by `TypeDefinitionScanner` and
  `MutationPlanAnalyzer`.
- **`IdentityAware`** — a sealed interface for the shared Entity/Aggregate shape
  (`version`, `scalars`, `embedded`, `associations`, `entityCollections`,
  `elementCollections`).
- **`asEntity()`** — a polymorphic "entity view" accessor (`Entity` → itself,
  `Aggregate` → a collection-free view, `ValueObject` → `null`).
- **`TypeDefinitionScanner`** — the scan phase (formerly `TypeMappingVisitor`) renamed.
- **`hasId()`-based classification** — `TypeGraph`/`RepositoryProcessor` classify
  managed entities / value objects via `hasId()` instead of `instanceof`.

These map to GitHub issue #30 (partial — Entity/Aggregate dedup).
