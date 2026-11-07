# Feature Specification: WriteEmitter Child-Table SQL Routing

**Feature Branch**: `002-writeemitter-sqlbuilder`

**Created**: 2026-11-07

**Status**: Draft

**Input**: User description: "Route `WriteEmitter`'s child-table INSERT/DELETE statements through
`SqlBuilder` (or `QueryDefinition`), removing the raw SQL string concatenation (GitHub issue #27)."

## User Scenarios & Testing *(mandatory)*

The "users" here are the maintainers of the processor. Each story is an independently testable slice
of the refactor and preserves generated output exactly.

### User Story 1 - Child-table DELETE via SqlBuilder (Priority: P1)

As a maintainer, I want `WriteEmitter`'s child-table `DELETE` statements (element-collection and
entity-collection cascade deletes) to be rendered by `SqlBuilder.deleteByFk(...)` (or
`QueryDefinition.DeleteByFk`), so dialect quoting and bind-parameter naming are handled once in the
core renderer rather than duplicated as raw strings.

**Why this priority**: DELETE is the most frequent child-table mutation (cascade delete, diff-sync
orphan delete, element-collection replace), and it is the clearest violation of `WriteEmitter`'s
"builds no SQL text itself" contract.

**Independent Test**: `WriteEmissionTest` passes unchanged with no `DELETE FROM` string literals
remaining in `WriteEmitter`.

**Acceptance Scenarios**:

1. **Given** a cascade delete of an entity collection, **When** `WriteEmitter` emits the child
   delete, **Then** the SQL is produced by `SqlBuilder.deleteByFk` (not string concatenation).
2. **Given** a diff-sync orphan delete, **When** `WriteEmitter` emits the delete, **Then** the SQL
   matches the renderer's quoting and parameter conventions.
3. **Given** a full build, **When** `WriteEmissionTest` runs, **Then** it passes unchanged.

---

### User Story 2 - Child-table INSERT via SqlBuilder (Priority: P2)

As a maintainer, I want `WriteEmitter`'s child-table `INSERT` (element-collection replace) to be
rendered by `SqlBuilder.insert(...)` (or `QueryDefinition.Insert`), so the child-row insert follows
the same renderer path as the root insert.

**Why this priority**: The element-collection replace builds an `INSERT INTO` by joining column
names and `:params` manually, duplicating `SqlBuilder.insert` and bypassing dialect quoting.

**Independent Test**: `WriteEmissionTest` (and the integration element-collection round-trip tests)
passes unchanged with no `INSERT INTO` string literal in `WriteEmitter`.

**Acceptance Scenarios**:

1. **Given** an element-collection replace, **When** `WriteEmitter` emits the child insert, **Then**
   the SQL is produced by `SqlBuilder.insert` (not string concatenation).
2. **Given** a full build, **When** emission and integration tests run, **Then** they pass unchanged.

---

### Edge Cases

- Element-collection child tables whose column set is dynamic (flattened value-object leaves) must
  still bind the same `:param` names in the same order.
- Child-table DELETE with a composite foreign key (multiple columns) must match the renderer's quoting.
- The `EC_IDS` select (existing child IDs for diff-sync) must remain consistent with the delete's
  id/FK columns.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: `WriteEmitter` MUST NOT build raw SQL text (no `DELETE FROM` / `INSERT INTO` string literals).
- **FR-002**: Child-table DELETE MUST be routed through `SqlBuilder.deleteByFk` / `QueryDefinition.DeleteByFk` (single-column) or `SqlBuilder.deleteChildByIdAndFk` / `QueryDefinition.DeleteChildByIdAndFk` (two-column diff-sync); the diff-sync existing-IDs SELECT MUST route through `SqlBuilder.selectChildIds` / `QueryDefinition.SelectChildIds`.
- **FR-003**: Child-table INSERT MUST be routed through `SqlBuilder.insertColumns` / `QueryDefinition.InsertColumns`.
- **FR-004**: Child bindings MUST keep using the existing `.param(...)` helpers.
- **FR-005**: Quoting and parameter naming MUST match the core renderer's conventions.
- **FR-006**: Generated SQL MUST be byte-identical; `WriteEmissionTest` and integration tests pass unchanged.

### Key Entities *(include if feature involves data)*

- **WriteEmitter** — the emitter whose child-table mutations are being routed through the renderer.
- **SqlBuilder / QueryDefinition** — the single SQL-rendering source (`insert`, `deleteByFk`, `Insert`, `DeleteByFk`).
- **MutationPlan.CascadeSpec** — the cascade spec describing child-table mutations (entity collections, element collections).

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Zero raw `DELETE FROM`/`INSERT INTO` string literals in `WriteEmitter`.
- **SC-002**: 100% of `WriteEmissionTest` passes unchanged.
- **SC-003**: Generated SQL is byte-identical before/after (verified by the emission test suite).

## Assumptions

- This is a behavior-preserving internal refactor; no public API or generated-code change.
- `SqlBuilder`/`QueryDefinition` may need a small child-table overload (per the issue), but the core
  renderer remains the single source of SQL.
- The existing test suites are the authoritative definition of "behavior preserved."
