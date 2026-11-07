# Implementation Plan: WriteEmitter Child-Table SQL Routing

**Branch**: `002-writeemitter-sqlbuilder` | **Date**: 2026-11-07 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/002-writeemitter-sqlbuilder/spec.md`

## Summary

Route `WriteEmitter`'s raw child-table `DELETE`/`INSERT`/`SELECT` string concatenation through the
core SQL renderer (`SqlBuilder` / `QueryDefinition`). Behavior is preserved: generated SQL is
byte-identical and `WriteEmissionTest` (plus integration tests) passes unchanged.

## Technical Context

**Language/Version**: Java 21.

**Primary Dependencies**: JavaPoet (code generation), JUnit 5 (tests).

**Storage**: N/A (compile-time code generator).

**Testing**: JUnit 5 (`WriteEmissionTest` and the emission suite); integration tests over H2.

**Target Platform**: JVM (annotation processor runs at compile time).

**Project Type**: library (compile-time annotation processor / code generator).

**Performance Goals**: No regression — byte-identical generated SQL.

**Constraints**: Behavior-preserving; no raw `DELETE FROM`/`INSERT INTO` string literals in
`WriteEmitter`; quoting/parameter naming must match the core renderer.

**Scale/Scope**: `processor/.../emitter/WriteEmitter.java` (primary) plus a possible small
child-table overload in `core/.../sql/SqlBuilder.java` / `QueryDefinition.java`.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

- **I. Compile-Time Repository Generation** — PASS. Compile-time codegen only.
- **II. DDD Entity Model** — PASS. Model unchanged.
- **III. Jakarta Data Subset Conformance** — PASS. No scope change.
- **IV. Test-First (NON-NEGOTIABLE)** — PASS. Existing emission/integration suites define "behavior preserved."
- **V. Resolve-Plan-Emit Pipeline & Single Source of SQL** — PASS. This feature *implements* the
  "single source of SQL" for `WriteEmitter`'s child-table mutations.

**Verdict**: No violations.

## Project Structure

### Documentation (this feature)

```text
specs/002-writeemitter-sqlbuilder/
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
└── WriteEmitter.java        # primary target: replace raw SQL strings
core/src/main/java/io/github/fludakit/data/sql/
├── SqlBuilder.java          # renderer (may gain a child-table overload)
└── QueryDefinition.java     # plan types (Insert / DeleteByFk)
processor/src/test/java/io/github/fludakit/data/processor/
└── WriteEmissionTest.java   # authoritative behavior gate (unchanged)
```

**Structure Decision**: No new modules. Confined to `WriteEmitter` and, if needed, a small
`SqlBuilder`/`QueryDefinition` addition.

## Complexity Tracking

> None — Constitution Check has no violations.
