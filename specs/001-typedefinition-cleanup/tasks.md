# Tasks: TypeDefinition Cleanup (polymorphic id + Prop.Visitor)

**Input**: Design documents from `/specs/001-typedefinition-cleanup/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/

**Tests**: Included for the two *new* public surfaces (`TypeDefinition.id()`, `Prop.Visitor`) per the
Test-First constitution principle. The pre-existing unit/emission/integration suites are the
authoritative "behavior preserved" gate for the rest.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (US1, US2)

## Phase 1: Setup (Baseline + Recon)

**Purpose**: Confirm a green baseline and enumerate the exact change sites before touching code.

- [X] T001 Establish a green baseline by running `./mvnw clean install` at the repo root and recording that all module suites (core, qbn, processor, integration-tests) pass.
- [X] T002 [P] Enumerate the seven id-extraction sites with `grep -rn "idProp\|idPropertyName" processor/src/main/java core/src/main/java` and record the `file:line` list (WriteEmitter, HydrationEmitter, LifecycleEmitter, MetamodelEmitter, RowMapperEmitter, FindEmitter inline, RepositoryVisitor).

## Phase 2: Foundational (Blocking Prerequisite — polymorphic `id()`)

**Purpose**: The single identifier accessor that both user stories build on. This is the prerequisite for US1's migration and US2's pushed-down methods.

**⚠️ CRITICAL**: No emitter migration (US1) or traversal collapse (US2) can start until `TypeDefinition.id()` exists.

- [X] T003 Write a direct unit test for `TypeDefinition.id()` in `core/src/test/java/io/github/fludakit/data/metadata/TypeDefinitionTest.java` asserting that an `Entity`/`Aggregate` returns its `Prop.Id` and a `ValueObject` returns `null`. Run it and confirm it FAILS (the method does not exist yet).
- [X] T004 Add abstract `Prop.Id id()` to the sealed `TypeDefinition` in `core/src/main/java/io/github/fludakit/data/metadata/TypeDefinition.java`; override it in `ValueObject` to return `null`; let `Entity`/`Aggregate` satisfy it via their existing record accessors. Confirm T003 now passes.

**Checkpoint**: `TypeDefinition.id()` exists and is covered by a unit test. US1 and US2 can now proceed in parallel.

---

## Phase 3: User Story 1 — Single identifier accessor (Priority: P1) 🎯 MVP

**Goal**: Every emitter and `RepositoryVisitor` resolves the entity id through `def.id()` instead of a local `idProp`/`idPropertyName` helper or an `instanceof Entity/Aggregate` branch.

**Independent Test**: `./mvnw -pl core,processor test` — all emission tests pass unchanged with no `idProp`/`idPropertyName` helpers remaining.

### Implementation for User Story 1

- [X] T005 [P] [US1] In `processor/src/main/java/io/github/fludakit/data/processor/emitter/WriteEmitter.java`, replace every `idProp(...)` call with `def.id()` and delete the private `idProp(TypeDefinition)` helper (~line 1052).
- [X] T006 [P] [US1] In `processor/src/main/java/io/github/fludakit/data/processor/emitter/HydrationEmitter.java`, replace every `idProp(...)` call with `def.id()` and delete the private helper (~line 344).
- [X] T007 [P] [US1] In `processor/src/main/java/io/github/fludakit/data/processor/emitter/LifecycleEmitter.java`, replace every `idProp(...)` call with `def.id()` and delete the private helper (~line 206).
- [X] T008 [P] [US1] In `processor/src/main/java/io/github/fludakit/data/processor/emitter/MetamodelEmitter.java`, replace every `idProp(...)` call with `def.id()` and delete the private helper (~line 266).
- [X] T009 [P] [US1] In `processor/src/main/java/io/github/fludakit/data/processor/emitter/RowMapperEmitter.java`, replace every `idProp(...)` call with `def.id()` and delete the private helper (~line 438).
- [X] T010 [P] [US1] In `processor/src/main/java/io/github/fludakit/data/processor/emitter/FindEmitter.java`, replace the inline `instanceof Entity/Aggregate` id-name extraction (~lines 687-691) with `def.id()`.
- [X] T011 [P] [US1] In `processor/src/main/java/io/github/fludakit/data/processor/RepositoryVisitor.java`, replace the `idPropertyName(TypeDefinition)` helper (~line 939) and its call sites with a null-safe `def.id().name()`.
- [X] T012 [US1] Run `./mvnw -pl core,processor test` and confirm the emission tests (`WriteEmissionTest`, `GeneratedArtifactsTest`, `ToManySortTest`, `CursorSeekEmissionTest`) pass unchanged.

**Checkpoint**: User Story 1 is complete — a single `TypeDefinition.id()` accessor, no duplicated helpers, behavior preserved.

---

## Phase 4: User Story 2 — Single property traversal (Priority: P2)

**Goal**: Property traversal is consolidated behind one `Prop.Visitor<R>`; the two column walkers and `PathResolver` stop duplicating `instanceof` dispatch, and `hasId`/`idColumn`/`ownsAggregateLifecycle` become polymorphic.

**Independent Test**: `./mvnw -pl core test` plus `./mvnw -pl processor test` — `ColumnRef`/`ColumnPlacement` semantics and generated output are unchanged.

### Implementation for User Story 2

- [X] T013 [US2] Write a direct unit test in `core/src/test/java/io/github/fludakit/data/metadata/TypeDefinitionTest.java` asserting the visitor dispatches over all 7 `Prop` kinds (Scalar, Id, Version, Association, Embedded, EntityCollection, ElementCollection) in declaration order. Run it and confirm it FAILS (no visitor yet).
- [X] T014 [US2] Introduce `Prop.Visitor<R>` (one method per `Prop` kind) and an `accept`/`visit` dispatch on `Prop` in `core/src/main/java/io/github/fludakit/data/metadata/TypeDefinition.java`. Confirm T013 passes.
- [X] T015 [US2] Push down `hasId()`, `idColumn()`, and `ownsAggregateLifecycle()` to `Entity`/`Aggregate`/`ValueObject`, deleting the three `instanceof`-based default methods in `core/src/main/java/io/github/fludakit/data/metadata/TypeDefinition.java`.
- [X] T016 [US2] Replace `addColumnsRecursive`/`addColumnRefsRecursive` with a single visitor-based traversal; make `columns()` and `columnRefs()` delegate to it in `core/src/main/java/io/github/fludakit/data/metadata/TypeDefinition.java`.
- [X] T017 [US2] Refactor the nested `PathResolver` to delegate property dispatch to the visitor, keeping only join/cycle decisions, and remove the duplicate `leafColumn` switch in `core/src/main/java/io/github/fludakit/data/metadata/TypeDefinition.java`.
- [X] T018 [US2] Run `./mvnw -pl core test` and `./mvnw -pl processor test` and confirm `ColumnRef`/`ColumnPlacement` semantics and generated output are unchanged.

**Checkpoint**: User Story 2 is complete — one `Prop.Visitor` traversal, no `instanceof` dispatch in `TypeDefinition`.

---

## Phase 5: Polish & Cross-Cutting Concerns

**Purpose**: Full-project validation and cleanup.

- [X] T019 Run `./mvnw clean install` at the repo root and confirm every module (core, qbn, processor, integration-tests) is green and generated output is byte-identical.
- [X] T020 [P] Run the `quickstart.md` validation: `grep -rn "instanceof Entity\|instanceof Aggregate\|instanceof TypeDefinition.Entity\|instanceof TypeDefinition.Aggregate" core/src/main/java/io/github/fludakit/data/metadata/TypeDefinition.java` returns no matches, and no `idProp`/`idPropertyName` remains under `processor/`.
- [X] T021 [P] Remove any now-unused imports/helpers left behind in the emitters and `RepositoryVisitor`.

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies — start immediately.
- **Foundational (Phase 2)**: Depends on Setup; BLOCKS both user stories.
- **User Story 1 (Phase 3)** and **User Story 2 (Phase 4)**: Depend on Foundational; can proceed in parallel.
- **Polish (Phase 5)**: Depends on both stories.

### User Story Dependencies

- **US1 (P1)**: After Foundational (Phase 2); no dependency on US2.
- **US2 (P2)**: After Foundational (Phase 2) — reuses `TypeDefinition.id()`; independent of US1's emitter migration.

### Within Each User Story

- Foundational test (T003/T013) MUST be written and FAIL before implementation.
- US1: the seven migration tasks (T005-T011) are independent and parallel; T012 is the checkpoint.
- US2: tasks are sequential within the story (all edit `TypeDefinition.java`): T013 → T014 → T015 → T016 → T017 → T018.

### Parallel Opportunities

- T002 (recon) can run alongside T001.
- US1's T005-T011 are all `[P]` (seven different files).
- US1 (emitters) and US2 (`TypeDefinition.java`) can run in parallel after Foundational, since they touch disjoint files.
- T020 and T021 (Polish) are `[P]`.

---

## Parallel Example: User Story 1

```bash
# After Foundational (id() exists), launch all seven emitter migrations together:
Task: "T005 WriteEmitter.java — replace idProp with def.id(), delete helper"
Task: "T006 HydrationEmitter.java — replace idProp with def.id(), delete helper"
Task: "T007 LifecycleEmitter.java — replace idProp with def.id(), delete helper"
Task: "T008 MetamodelEmitter.java — replace idProp with def.id(), delete helper"
Task: "T009 RowMapperEmitter.java — replace idProp with def.id(), delete helper"
Task: "T010 FindEmitter.java — replace inline instanceof extraction with def.id()"
Task: "T011 RepositoryVisitor.java — replace idPropertyName with def.id().name()"
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete Phase 1 (Setup) + Phase 2 (Foundational).
2. Complete Phase 3 (US1) — the seven emitter migrations + checkpoint.
3. **STOP and VALIDATE**: run `./mvnw -pl core,processor test`; confirm no helpers remain and tests pass.
4. This is a shippable increment on its own (issue #24).

### Incremental Delivery

1. Setup + Foundational → `TypeDefinition.id()` available.
2. US1 → single id accessor (issue #24) → validate.
3. US2 → single `Prop.Visitor` traversal (issue #25) → validate.
4. Polish → full `./mvnw clean install` + grep check.

### Parallel Team Strategy

With two developers after Foundational: Developer A takes US1 (emitter migrations), Developer B
takes US2 (`TypeDefinition.java` traversal). They touch disjoint files and integrate at the
Polish phase.

---

## Notes

- [P] tasks are in different files with no interdependency.
- This is a behavior-preserving refactor: generated output must be byte-identical and no public API may change (see `contracts/typedefinition-api.md`).
- Commit after each task or logical group; stop at any checkpoint to validate independently.
