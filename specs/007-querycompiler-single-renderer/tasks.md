# Tasks: QueryCompiler Single Renderer

**Input**: Design documents from `/specs/007-querycompiler-single-renderer/`

**Prerequisites**: plan.md, spec.md

**Tests**: `SqlBuilderBuildTest` + full `./mvnw -pl core,processor test` (byte-identical gate).

## Phase 1: Setup

- [ ] T001 Establish a green baseline by running `./mvnw -pl core,processor test`.

## Phase 2: Single Conjunct + single join renderer (US1)

- [ ] T002 [US1] Remove the private `SqlFragment.Conjunct` record and reference `QueryCompiler.Conjunct`.
- [ ] T003 [US1] Replace `aggregateSortTerm`'s hand-built deeper-join loop with `SqlFragment.joins(dialect, edges.subList(1, edges.size()))`.

## Phase 3: Classic helpers via SqlFragment (US1)

- [ ] T004 [US1] Add a table-alias form (`table(String, String)`) to `SqlFragment`, rendering `FROM "t" AS "alias"`.
- [ ] T005 [US1] Re-point `appendWhere` at `SqlFragment` (add conjuncts, grouping the OR user expression as one conjunct when a cursor clause follows) and route `selectBy`/`countBy`/`deleteBy` through `SqlFragment`.
- [ ] T006 [US1] Extract `classicOrderByTerms` so `selectBy` feeds rendered ORDER BY terms to the accumulator.

## Phase 4: Validation

- [ ] T007 Run `./mvnw -pl core,processor test` and confirm byte-identical SQL.

## Notes

- Behavior-preserving; the classic raw-predicate API stays, only its assembly changes.
