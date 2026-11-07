# Tasks: WriteEmitter Child-Table SQL Routing

**Input**: Design documents from `/specs/002-writeemitter-sqlbuilder/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/

**Tests**: Existing suites (`WriteEmissionTest`, integration tests) are the authoritative
"behavior preserved" gate; no new test files are required for this behavior-preserving refactor.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (US1, US2)

## Phase 1: Setup (Baseline + Recon)

**Purpose**: Confirm a green baseline and map the exact raw-SQL sites to renderer methods.

- [X] T001 Establish a green baseline by running `./mvnw -pl core,processor test` and recording that the emission suite passes.
- [X] T002 [P] Inspect `core/src/main/java/io/github/fludakit/data/sql/SqlBuilder.java` and `QueryDefinition.java` to confirm the existing `deleteByFk`/`insert` signatures, and locate the three raw-SQL sites in `processor/src/main/java/io/github/fludakit/data/processor/emitter/WriteEmitter.java` (`buildCascadeDeleteCodeBlock`, `buildDiffSyncCodeBlock`, `buildElementCollectionReplaceCodeBlock`).

## Phase 2: User Story 1 — Child-table DELETE via SqlBuilder (Priority: P1)

**Goal**: Every child-table `DELETE` in `WriteEmitter` renders via `SqlBuilder`; no raw `DELETE FROM`.

**Independent Test**: `./mvnw -pl processor test` — `WriteEmissionTest` DELETE assertions pass unchanged.

- [X] T003 [US1] In `processor/.../emitter/WriteEmitter.java`, replace the raw `DELETE FROM ... WHERE fk = :parentId` in `buildCascadeDeleteCodeBlock` with `SqlBuilder.deleteByFk(childEntity.tableName(), ec.fkColumn())`.
- [X] T004 [US1] In `processor/.../emitter/WriteEmitter.java`, replace the raw `DELETE FROM ... WHERE fk = :parentId` in `buildElementCollectionReplaceCodeBlock` with `SqlBuilder.deleteByFk(ec.childTable(), ec.fkColumn())`.
- [X] T005 [US1] Add `deleteChildByIdAndFk(table, idColumn, fkColumn)` to `core/.../sql/SqlBuilder.java` (or `QueryDefinition.DeleteByFk`) and route the two-column `DELETE ... WHERE id = :childId AND fk = :parentId` in `buildDiffSyncCodeBlock` through it.
- [X] T006 [US1] Add a `selectByFk(table, idColumn, fkColumn)` renderer to `core/.../sql/SqlBuilder.java` (or `QueryDefinition.Read`) and route the `SELECT id ... WHERE fk = :parentId` in `buildDiffSyncCodeBlock` through it.
- [X] T007 [US1] Run `./mvnw -pl processor test` and confirm `WriteEmissionTest` passes unchanged.

## Phase 3: User Story 2 — Child-table INSERT via SqlBuilder (Priority: P2)

**Goal**: The element-collection replace `INSERT` renders via `SqlBuilder`; no raw `INSERT INTO`.

**Independent Test**: `./mvnw -pl processor test` plus `./mvnw -pl integration-tests test` — element-collection round-trip passes unchanged.

- [X] T008 [US2] Add a child-table `insert(columns, params)` overload to `core/.../sql/SqlBuilder.java` (or `QueryDefinition.Insert`) and route the `INSERT INTO ... (<cols>) VALUES (<:params>)` in `buildElementCollectionReplaceCodeBlock` through it, keeping the exact column/param order.
- [X] T009 [US2] Run `./mvnw -pl processor test` and `./mvnw -pl integration-tests test` and confirm element-collection round-trip tests pass unchanged.

## Phase 4: Polish & Cross-Cutting Concerns

**Purpose**: Full-project validation and cleanup.

- [X] T010 Run `./mvnw test` and confirm every module is green with byte-identical generated SQL.
- [X] T011 [P] Run the `quickstart.md` check: `grep -n "DELETE FROM\|INSERT INTO" processor/src/main/java/io/github/fludakit/data/processor/emitter/WriteEmitter.java` returns no matches.
- [X] T012 [P] Remove any now-unused `dialect.quoteIdentifier` calls or imports left behind in `WriteEmitter.java`.

---

## Dependencies & Execution Order

- **Setup (Phase 1)** → **US1 (Phase 2)** → **US2 (Phase 3)** → **Polish (Phase 4)**.
- US1 and US2 both touch `WriteEmitter.java` and `SqlBuilder.java`, so within each phase tasks are sequential (same files); US2 depends on US1 only through shared files, not logic.
- T003/T004 are independent replacements in the same file (do sequentially); T005/T006 add renderer methods; T007 is the US1 gate.

## Parallel Opportunities

- T002 (recon) can overlap T001.
- T011 and T012 (Polish) are `[P]` (read-only/cleanup, no shared state).

## Implementation Strategy

1. Setup + recon (T001–T002).
2. US1 — route the three DELETEs + the SELECT (T003–T006), then validate (T007).
3. US2 — route the INSERT (T008), then validate (T009).
4. Polish — full build + grep + cleanup (T010–T012).

## Notes

- This is a behavior-preserving refactor: generated SQL must be byte-identical (see
  `contracts/sql-rendering.md`).
- Child bindings stay on the existing `.param(...)` helpers.
- Commit after each task or logical group.
