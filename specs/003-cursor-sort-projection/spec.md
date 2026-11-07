# Feature Specification: FindEmitter Cursor Sort/Projection

**Feature Branch**: `003-cursor-sort-projection`

**Created**: 2026-11-07

**Status**: Draft

**Input**: User description: "Move `FindEmitter`'s cursor to-many sort/projection SQL into the core
renderer, modeled as a `CursorSortProjection` plan type (GitHub issue #26)."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - CursorSortProjection plan type (Priority: P1)

As a maintainer, I want the cursor-paging to-many sort and projection modeled as a
`CursorSortProjection` core capability consumed by `SqlBuilder` / `QueryDefinition.Read`, so
`FindEmitter` passes the sort terms and stops hand-assembling `SELECT` / `GROUP BY` / `LEFT JOIN`
/ correlated `MIN`/`MAX` / `ORDER BY` strings.

**Why this priority**: It removes the last SQL-text assembly in `FindEmitter`'s cursor path and makes
`aggregateSortTerm` and `columns()` the single implementations — restoring the single-renderer contract.

**Independent Test**: `ToManySortTest` and the emission tests pass unchanged, and no cursor-paging
SQL string assembly remains in `FindEmitter`.

**Acceptance Scenarios**:

1. **Given** a cursor-paged query sorted by a to-many relation, **When** `FindEmitter` emits the SQL,
   **Then** it delegates to `CursorSortProjection` (via `SqlBuilder` / `QueryDefinition.Read`) rather
   than building the aggregate sort term inline.
2. **Given** the same query, **When** the projection (join + full materialization) is produced,
   **Then** it comes from the core (`TypeDefinition.columns()` / the renderer), not a hand-built column list.
3. **Given** a full build, **When** `ToManySortTest` and the emission tests run, **Then** they pass unchanged.

### Edge Cases

- A to-many sort over an empty collection (null aggregate) must render the same as before.
- Multiple to-many sorts must produce the same correlated aggregate subqueries and `ORDER BY` terms in the same order.
- The keyset seek predicate must still agree with the sort terms byte-for-byte.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: A `CursorSortProjection` plan type MUST model the cursor to-many sort + projection.
- **FR-002**: `FindEmitter` MUST NOT assemble cursor-paging SQL text; it MUST pass sort terms to the core.
- **FR-003**: `aggregateSortTerm` MUST be the single implementation of the to-many sort term.
- **FR-004**: `columns()` (or the renderer) MUST be the single implementation of the projection.
- **FR-005**: Generated SQL MUST be byte-identical; `ToManySortTest` and emission tests pass unchanged.

### Key Entities *(include if feature involves data)*

- **CursorSortProjection** — the new plan type (sort terms + projection) consumed by `SqlBuilder` / `QueryDefinition.Read`.
- **FindEmitter** — the emitter that stops assembling SQL and delegates.
- **QueryCompiler.aggregateSortTerm** / **TypeDefinition.columns()** — the single renderer implementations.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Zero SQL text assembly in `FindEmitter`'s cursor-paging path.
- **SC-002**: `aggregateSortTerm` and `columns()` are the only implementations.
- **SC-003**: Generated SQL byte-identical (`ToManySortTest` + emission tests pass).

## Assumptions

- Behavior-preserving internal refactor; no public API or generated-code change.
- `CursorSortProjection` is consumed by `SqlBuilder` / `QueryDefinition.Read` (per the issue).
- The existing test suites are the authoritative definition of "behavior preserved."
