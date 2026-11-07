# Implementation Plan: FindEmitter Cursor Sort/Projection

**Branch**: `003-cursor-sort-projection` | **Date**: 2026-11-07 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/003-cursor-sort-projection/spec.md`

## Summary

Model the cursor-paging to-many sort/projection as a `CursorSortProjection` core plan type, rendered
by `QueryCompiler` and consumed by `SqlBuilder` / `QueryDefinition.Read`. `FindEmitter` builds the
plan and stops hand-assembling `SELECT`/`GROUP BY`/`LEFT JOIN`/`MIN`/`MAX`/`ORDER BY` strings.

## Technical Context

**Language/Version**: Java 21.

**Primary Dependencies**: JavaPoet (code generation), JUnit 5 (tests).

**Storage**: N/A (compile-time code generator).

**Testing**: JUnit 5 (`ToManySortTest`, `CursorSeekEmissionTest`, emission suite) + integration over H2.

**Target Platform**: JVM (annotation processor runs at compile time).

**Project Type**: library (compile-time annotation processor / code generator).

**Performance Goals**: No regression — byte-identical generated SQL.

**Constraints**: Behavior-preserving; no SQL text assembly in `FindEmitter`'s cursor-paging path.

**Scale/Scope**: `processor/.../emitter/FindEmitter.java` (primary) + `core/.../sql/{QueryCompiler,SqlBuilder}.java`,
`core/.../query/QueryDefinition.java`, and `core/.../metadata/TypeDefinition.java` (projection).

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

- **I. Compile-Time Repository Generation** — PASS.
- **II. DDD Entity Model** — PASS. Model unchanged.
- **III. Jakarta Data Subset Conformance** — PASS. No scope change.
- **IV. Test-First (NON-NEGOTIABLE)** — PASS. Existing suites define "behavior preserved."
- **V. Resolve-Plan-Emit Pipeline & Single Source of SQL** — PASS. This feature *implements* the
  "single source of SQL" for `FindEmitter`'s cursor-paging path.

**Verdict**: No violations.

## Project Structure

### Documentation (this feature)

```text
specs/003-cursor-sort-projection/
├── plan.md              # This file
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
├── contracts/           # Phase 1 output (SQL-rendering contract)
└── tasks.md             # Phase 2 output (/speckit-tasks)
```

### Source Code (repository root)

```text
processor/src/main/java/io/github/fludakit/data/processor/emitter/
└── FindEmitter.java             # primary target: delegate to CursorSortProjection
core/src/main/java/io/github/fludakit/data/query/
└── QueryDefinition.java         # gains CursorSortProjection (or Read gains a field)
core/src/main/java/io/github/fludakit/data/sql/
├── QueryCompiler.java           # renders SELECT/GROUP BY/LEFT JOIN/aggregate/ORDER BY from the plan
└── SqlBuilder.java              # exposes the plan-rendering terminal
core/src/main/java/io/github/fludakit/data/metadata/
└── TypeDefinition.java          # columns() — the single projection source
```

**Structure Decision**: No new modules. Confined to `FindEmitter` and the core renderer.

## Complexity Tracking

> None — Constitution Check has no violations.
