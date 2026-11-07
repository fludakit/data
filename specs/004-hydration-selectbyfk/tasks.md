# Tasks: HydrationEmitter SELECT Routing via SqlBuilder

**Input**: Design documents from `/specs/004-hydration-selectbyfk/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/

**Tests**: Existing emission + hydration integration suites are the authoritative "behavior preserved"
gate; no new test files are required.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (US1)

## Phase 1: Setup (Baseline + Recon)

**Purpose**: Confirm a green baseline and pin the `SelectByFk` shape.

- [X] T001 Establish a green baseline by running `./mvnw -pl core,processor test` and recording that the emission suite passes.
- [X] T002 [P] Inspect the three hand-built SELECTs in `processor/.../emitter/HydrationEmitter.java` and `TypeDefinition.columns()`/`properties()` in `core/.../metadata/TypeDefinition.java`; record the exact `SelectByFk` record shape (table, fk column, projection source for the `column AS alias` list).

## Phase 2: Foundational (SelectByFk renderer)

**Purpose**: The core capability `HydrationEmitter` delegates to. BLOCKS the story.

- [X] T003 Add a `QueryDefinition.SelectByFk` plan type to `core/.../query/QueryDefinition.java` (child table, fk column, and the projection source).
- [X] T004 Add a `QueryCompiler.selectByFk` renderer in `core/.../sql/QueryCompiler.java` that produces the `column AS alias` projection (property-name alias for id/scalar, FK-column alias for associations) and the `SELECT *` form.
- [X] T005 Add a `SqlBuilder.selectByFk(...)` facade in `core/.../sql/SqlBuilder.java`.

## Phase 3: User Story 1 — HydrationEmitter delegates (Priority: P1)

**Goal**: `HydrationEmitter` routes all three hydration SELECTs through the renderer.

**Independent Test**: `./mvnw -pl processor test` — emission + hydration tests pass unchanged.

- [X] T006 [US1] In `processor/.../emitter/HydrationEmitter.java`, route `emitEntityCollectionLoader`'s SELECT through `SqlBuilder.selectByFk`.
- [X] T007 [US1] In the same file, route `emitElementCollectionLoader`'s `SELECT *` through `SqlBuilder.selectByFk`.
- [X] T008 [US1] In the same file, route `emitCompositionAssociationLoader`'s by-id SELECT through the by-identifier renderer.
- [X] T009 [US1] Run `./mvnw -pl processor test` and confirm emission + hydration tests pass unchanged.

## Phase 4: Polish & Cross-Cutting Concerns

**Purpose**: Full-project validation and cleanup.

- [X] T010 Run `./mvnw test` and confirm every module is green with byte-identical generated SQL.
- [X] T011 [P] Run the `quickstart.md` check: `grep -nE '"SELECT ' processor/src/main/java/io/github/fludakit/data/processor/emitter/HydrationEmitter.java` returns no matches.
- [X] T012 [P] Remove any now-unused helpers/imports left behind in `HydrationEmitter.java`.

---

## Dependencies & Execution Order

- **Setup (Phase 1)** → **Foundational (Phase 2)** → **US1 (Phase 3)** → **Polish (Phase 4)**.
- T003–T005 are sequential (plan type → renderer → facade).
- T006–T008 all edit `HydrationEmitter.java`, so they run sequentially; T009 is the US1 gate.

## Parallel Opportunities

- T002 (recon) can overlap T001.
- T011 and T012 (Polish) are `[P]`.

## Implementation Strategy

1. Setup + recon (T001–T002) — pin the projection shape.
2. Foundational (T003–T005) — `SelectByFk` type + renderer + facade.
3. US1 (T006–T008) — `HydrationEmitter` delegates; validate (T009).
4. Polish (T010–T012) — full build + grep + cleanup.

## Notes

- Behavior-preserving refactor: generated SQL must be byte-identical (see `contracts/sql-rendering.md`).
- The `column AS alias` projection (property-name alias for id/scalar, FK-column alias for associations)
  is the row-mapper contract and must be preserved.
- Commit after each task or logical group.
