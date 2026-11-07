# Feature Specification: SqlBuilder pagedSeekStatement Aliasing

**Feature Branch**: `006-pagedseek-aliasing`

**Created**: 2026-11-07

**Status**: Draft

**Input**: User description: "Stop `SqlBuilder.pagedSeekStatement` re-aliasing already-rendered SQL
with repeated `String.replace`, which can corrupt unrelated occurrences; alias at node level or map
exact tokens (GitHub #28, item 5)."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Alias at node level (Priority: P1)

As a maintainer, I want the derived-table wrap of a to-many cursor seek to re-point sort terms at
the inner alias by mapping each exact term (aggregate -> `inner_.agg_N`, root-qualified -> `inner_`)
rather than by global `String.replace`, so unrelated occurrences cannot be corrupted and the ORDER BY
is rendered by the one SQL source.

**Why this priority**: It removes fragile string surgery on already-rendered SQL — the last place the
core renderer post-processes its own output text.

**Independent Test**: `SqlBuilderSeekClauseTest` and `CursorSeekEmissionTest` pass unchanged
(byte-identical generated SQL), and no `String.replace` remains in `pagedSeekStatement`.

**Acceptance Scenarios**:

1. **Given** a cursor-paged read sorted by a to-many relation, **When** the seek statement is
   assembled, **Then** each sort term is re-aliased by exact token, not `String.replace`, and the
   generated SQL is byte-identical to the characterization pins.
2. **Given** the same read, **When** the ORDER BY is produced, **Then** it is rendered in core from
   structured terms (the single SQL source), not hand-assembled in `FindEmitter`.

### Edge Cases

- A bare (unqualified) sort term must be left unchanged — it is unambiguous in the outer derived table.
- A root-qualified sort term must be re-pointed only at its leading qualifier, never at an embedded occurrence.
- A simple local key that sorts BEFORE a to-many aggregate must still alias the aggregate to `agg_0`
  (content match, not positional assumption).

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: `pagedSeekStatement` MUST NOT re-alias rendered SQL via `String.replace`.
- **FR-002**: The derived-table wrap MUST map an aggregate term to `inner_.agg_N` and a root-qualified
  term to `inner_.<col>` by exact token.
- **FR-003**: The cursor ORDER BY MUST be rendered in core from structured terms, not hand-assembled in `FindEmitter`.
- **FR-004**: Generated SQL MUST be byte-identical; `SqlBuilderSeekClauseTest` and
  `CursorSeekEmissionTest` pass unchanged.

### Key Entities *(include if feature involves data)*

- **SqlBuilder.pagedSeekStatement** — the method whose re-aliasing is made exact and node-level.
- **FindEmitter** — the emitter that stops hand-assembling the cursor ORDER BY.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Zero `String.replace` calls in `pagedSeekStatement` and its helpers.
- **SC-002**: `FindEmitter` no longer renders the cursor ORDER BY string.
- **SC-003**: Generated SQL byte-identical (characterization tests pass).

## Assumptions

- Behavior-preserving internal refactor; the signature change from a pre-rendered `orderBy` string to a
  `orderByTerms` list is internal to the core/processor boundary.
- `terms` and `orderByTerms` are parallel (same length, same order); this is the emitter's contract.
