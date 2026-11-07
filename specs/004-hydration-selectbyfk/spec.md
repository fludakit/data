# Feature Specification: HydrationEmitter SELECT Routing via SqlBuilder

**Feature Branch**: `004-hydration-selectbyfk`

**Created**: 2026-11-07

**Status**: Draft

**Input**: User description: "Route `HydrationEmitter`'s hand-built `SELECT ... WHERE fk = :parentId`
strings through `SqlBuilder` (GitHub #28, item 2)."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Hydration SELECTs via SqlBuilder (Priority: P1)

As a maintainer, I want `HydrationEmitter`'s three hand-built hydration `SELECT` strings to be
rendered by `SqlBuilder` (a `selectByFk` reusing `TypeDefinition.columns()`, plus a by-id variant for
the composing association), so dialect quoting and the row-mapper projection stay single-source.

**Why this priority**: It removes the last hand-built SQL in the hydration path and enforces the
"single source of SQL" contract (Principle V) the other emitters now follow.

**Independent Test**: `./mvnw -pl processor test` (and integration hydration tests) pass unchanged,
with no `SELECT ... WHERE fk = :parentId` string literals remaining in `HydrationEmitter`.

**Acceptance Scenarios**:

1. **Given** an entity-collection hydration, **When** `HydrationEmitter` emits the child SELECT,
   **Then** it delegates to `SqlBuilder` (not string concatenation) and the SQL is unchanged.
2. **Given** an element-collection hydration (`SELECT *`), **When** it emits the SELECT, **Then** it
   uses the renderer and produces the same SQL.
3. **Given** a composing association hydration (by child id), **When** it emits the SELECT, **Then**
   it uses the renderer and produces the same SQL.
4. **Given** a full build, **When** the hydration integration tests run, **Then** they pass unchanged.

### Edge Cases

- The element-collection `SELECT *` form must remain byte-identical.
- The composing-association SELECT is keyed by the child identifier, not the parent FK.
- Column ordering for the entity-collection loader must match `TypeDefinition.columns()`.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: `HydrationEmitter` MUST NOT build raw `SELECT ... WHERE fk = :parentId` strings.
- **FR-002**: The entity-collection and element-collection hydration SELECTs MUST route through
  `SqlBuilder.selectByFk` (reusing `TypeDefinition.columns()`).
- **FR-003**: The composing-association hydration SELECT (by child id) MUST route through the renderer.
- **FR-004**: Generated SQL MUST be byte-identical; emission and hydration integration tests pass unchanged.

### Key Entities *(include if feature involves data)*

- **HydrationEmitter** — the emitter whose hydration SELECTs are routed through the renderer.
- **SqlBuilder.selectByFk** — the new renderer reusing `TypeDefinition.columns()`.
- **TypeDefinition.columns()** — the single projection source.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Zero hand-built hydration `SELECT` strings in `HydrationEmitter`.
- **SC-002**: 100% of emission + hydration integration tests pass unchanged.
- **SC-003**: Generated SQL byte-identical.

## Assumptions

- Behavior-preserving internal refactor.
- `SqlBuilder.selectByFk` is added to core (reusing `TypeDefinition.columns()`); the composing-association
  by-id SELECT reuses the existing `deleteById`-style by-identifier shape.
- Existing tests are the authoritative definition of "behavior preserved."
