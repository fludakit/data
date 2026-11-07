# Feature Specification: QueryCompiler Single Renderer

**Feature Branch**: `007-querycompiler-single-renderer`

**Created**: 2026-11-07

**Status**: Draft

**Input**: User description: "Collapse `QueryCompiler`'s two parallel assembly paths (`SqlFragment` vs
`StringBuilder`), the duplicate `Conjunct` records, and `aggregateSortTerm` re-implementing join
rendering to one renderer through `SqlFragment` (GitHub #28, item 3)."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - One statement accumulator (Priority: P1)

As a maintainer, I want every statement `QueryCompiler` emits assembled as a `SqlFragment`, so there is
one ordered accumulator and one join renderer, and no duplicated `Conjunct` shape.

**Why this priority**: It removes the last dual assembly path, keeping the single-renderer contract of
Principle V (single source of SQL).

**Independent Test**: `./mvnw -pl core,processor test` passes with byte-identical SQL; the classic
`selectBy`/`countBy`/`deleteBy` helpers render through `SqlFragment`.

**Acceptance Scenarios**:

1. **Given** a classic `selectBy`/`countBy`/`deleteBy`, **When** it is rendered, **Then** it is assembled
   as a `SqlFragment` and the SQL is byte-identical.
2. **Given** an existential to-many sort, **When** its aggregate sort term is rendered, **Then** the
   deeper join chain comes from `SqlFragment.joins`, not a hand-built loop.
3. **Given** the codebase, **When** the `Conjunct` shape is searched, **Then** exactly one definition exists.

### Edge Cases

- A classic `selectBy` with `DISTINCT` and a table alias must render `FROM "t" AS "alias"` unchanged.
- A grouped OR user expression followed by a cursor clause must still render `WHERE (a OR b) AND cursor`.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: Exactly ONE `Conjunct` record MUST exist (no duplicate in `SqlFragment`).
- **FR-002**: `aggregateSortTerm` MUST render deeper joins via `SqlFragment.joins`.
- **FR-003**: `selectBy`/`countBy`/`deleteBy` MUST assemble through `SqlFragment` (single accumulator).
- **FR-004**: Generated SQL MUST be byte-identical; the full test suite passes.

### Key Entities *(include if feature involves data)*

- **SqlFragment** — the one ordered statement accumulator (gains a table-alias form).
- **QueryCompiler.Conjunct** — the single conjunct shape reused by `SqlFragment`.
- **QueryCompiler.selectBy/countBy/deleteBy** — classic helpers now rendered via `SqlFragment`.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: One `Conjunct` definition.
- **SC-002**: `aggregateSortTerm` contains no hand-built `LEFT JOIN` loop.
- **SC-003**: `selectBy`/`countBy`/`deleteBy` build a `SqlFragment` and produce byte-identical SQL.

## Assumptions

- Behavior-preserving internal refactor; the classic raw-predicate API stays (only its assembly changes).
- No public API change beyond the internal assembly mechanism.
