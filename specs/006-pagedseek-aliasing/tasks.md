# Tasks: SqlBuilder pagedSeekStatement Aliasing

**Input**: Design documents from `/specs/006-pagedseek-aliasing/`

**Prerequisites**: plan.md, spec.md

**Tests**: `SqlBuilderSeekClauseTest` (core) and `CursorSeekEmissionTest` (processor) are the
byte-identical gate; one new core test locks content-vs-position aliasing.

## Phase 1: Setup

- [X] T001 Record the broken baseline: `SqlBuilder.java` does not compile (`aggIndex` out of scope in the WIP).

## Phase 2: Core — node-level aliasing (US1)

- [ ] T002 [US1] Change `pagedSeekStatement` in `core/.../sql/SqlBuilder.java` to take `List<String> orderByTerms`
      instead of the pre-rendered `orderBy` string; render ORDER BY via a new `renderOrderBy(terms, ascending)` helper.
- [ ] T003 [US1] Replace the derived-table re-aliasing with exact-token mapping: a shared loop aliases
      `terms` and `orderByTerms` in lockstep, mapping aggregates to `inner_.agg_N` (content match on the
      parallel seek term) and root-qualified terms via a prefix-only `repoint(...)` helper (no `String.replace`).

## Phase 3: Processor — delegate ORDER BY (US1)

- [ ] T004 [US1] In `processor/.../emitter/FindEmitter.java`, pass `orderByTerms` to `pagedSeekStatement` and
      delete the local `orderByClause` assembly and the empty-seek special case (core now renders both).

## Phase 4: Tests + validation

- [ ] T005 [US1] Update `SqlBuilderSeekClauseTest` to the new signature (parallel `orderByTerms`) and add a
      mixed-sort test asserting the aggregate aliases by content (not position) and no raw `MIN(`/`MAX(` leaks.
- [ ] T006 Run `./mvnw -pl core,processor test` and confirm byte-identical output.

## Dependencies & Execution Order

T002 -> T003 (core) -> T004 (processor) -> T005 -> T006.

## Notes

- Behavior-preserving; generated SQL byte-identical (see `specs/003-cursor-sort-projection/contracts/sql-rendering.md`).
- `terms`, `ascending`, `orderByTerms` are parallel lists — the emitter's existing contract.
