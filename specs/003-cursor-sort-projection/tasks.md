# Tasks: FindEmitter Cursor Sort/Projection

**Input**: Design documents from `/specs/003-cursor-sort-projection/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/

**Tests**: Existing suites (`ToManySortTest`, `CursorSeekEmissionTest`, the emission suite, and the
integration tests) are the authoritative "behavior preserved" gate; no new test files are required.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (US1)

## Phase 1: Setup (Baseline + Recon)

**Purpose**: Confirm a green baseline and pin down the exact `CursorSortProjection` field set.

- [X] T001 Establish a green baseline by running `./mvnw -pl core,processor test` and recording that the emission suite passes.
- [X] T002 [P] Inspect the three cursor-paging SQL sites in `processor/src/main/java/io/github/fludakit/data/processor/emitter/FindEmitter.java` (base SELECT shell, ORDER BY/SEEK terms, aggregate expressions), plus `QueryCompiler.aggregateSortTerm` in `core/src/main/java/io/github/fludakit/data/sql/QueryCompiler.java` and `TypeDefinition.columns()` in `core/src/main/java/io/github/fludakit/data/metadata/TypeDefinition.java`; record the exact `CursorSortProjection` field set (sort terms, join, projection columns, group-by columns).

## Phase 2: Foundational (CursorSortProjection plan type + renderer)

**Purpose**: The core capability `FindEmitter` will delegate to. BLOCKS the FindEmitter story.

- [X] T003 Add a `CursorSortProjection` record to `core/src/main/java/io/github/fludakit/data/query/QueryDefinition.java` (to-many sort terms with ascending flags, child-table join info, projection columns, group-by columns).
- [X] T004 Add a renderer in `core/src/main/java/io/github/fludakit/data/sql/QueryCompiler.java` that renders the `SELECT`/`GROUP BY`/`LEFT JOIN`/`MIN`/`MAX` aggregates/`ORDER BY`/keyset `SEEK` from the plan, reusing `TypeDefinition.columns()` and `aggregateSortTerm`.
- [X] T005 Add a `SqlBuilder` facade method in `core/src/main/java/io/github/fludakit/data/sql/SqlBuilder.java` exposing the `CursorSortProjection` rendering.

## Phase 3: User Story 1 — FindEmitter delegates (Priority: P1)

**Goal**: `FindEmitter` builds a `CursorSortProjection` and stops assembling cursor-paging SQL text.

**Independent Test**: `./mvnw -pl processor test` — `ToManySortTest` and `CursorSeekEmissionTest` pass unchanged.

- [X] T006 [US1] In `processor/.../emitter/FindEmitter.java`, replace the hand-built base SELECT shell (root columns + aggregates + `FROM` + `LEFT JOIN` + `GROUP BY`) with delegation to the `CursorSortProjection` renderer.
- [X] T007 [US1] In `processor/.../emitter/FindEmitter.java`, derive the `ORDER BY` aliases and keyset `SEEK` terms from the `CursorSortProjection` (aliases for ORDER BY, full aggregate/correlated expressions for WHERE), including the id-uniqueness sort key.
- [X] T008 [US1] In `processor/.../emitter/FindEmitter.java`, derive the aggregate expressions for the derived-table wrap from the `CursorSortProjection` instead of re-enumerating them.
- [X] T009 [US1] Run `./mvnw -pl processor test` and confirm `ToManySortTest` and `CursorSeekEmissionTest` pass unchanged.

## Phase 4: Polish & Cross-Cutting Concerns

**Purpose**: Full-project validation and cleanup.

- [X] T010 Run `./mvnw test` and confirm every module is green with byte-identical generated SQL.
- [X] T011 [P] Run the `quickstart.md` check: `grep -nE "SELECT |GROUP BY|LEFT JOIN|MIN\(|MAX\(|ORDER BY" processor/src/main/java/io/github/fludakit/data/processor/emitter/FindEmitter.java` returns no matches.
- [X] T012 [P] Remove any now-unused helpers/imports left behind in `FindEmitter.java`.

---

## Dependencies & Execution Order

- **Setup (Phase 1)** → **Foundational (Phase 2)** → **US1 (Phase 3)** → **Polish (Phase 4)**.
- T003–T005 are sequential (they build the plan type → renderer → facade).
- T006–T008 all edit `FindEmitter.java`, so they run sequentially; T009 is the US1 gate.

## Parallel Opportunities

- T002 (recon) can overlap T001.
- T011 and T012 (Polish) are `[P]` (read-only/cleanup).

## Implementation Strategy

1. Setup + recon (T001–T002) — pin the plan field set.
2. Foundational (T003–T005) — the `CursorSortProjection` type + renderer + facade.
3. US1 (T006–T008) — `FindEmitter` delegates; validate (T009).
4. Polish (T010–T012) — full build + grep + cleanup.

## Notes

- This is a behavior-preserving refactor: generated SQL must be byte-identical (see
  `contracts/sql-rendering.md`).
- `aggregateSortTerm` and `columns()` must remain the single implementations.
- Commit after each task or logical group.
