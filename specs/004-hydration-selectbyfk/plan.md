# Implementation Plan: HydrationEmitter SELECT Routing via SqlBuilder

**Branch**: `004-hydration-selectbyfk` | **Date**: 2026-11-07 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/004-hydration-selectbyfk/spec.md`

## Summary

Route `HydrationEmitter`'s three hand-built hydration `SELECT` strings through `SqlBuilder`: a
`selectByFk` for the entity-collection and element-collection loaders, and a by-identifier renderer
for the composing-association loader. Behavior is preserved (byte-identical SQL).

## Technical Context

**Language/Version**: Java 21.
**Primary Dependencies**: JavaPoet, JUnit 5.
**Storage**: N/A.
**Testing**: JUnit 5 emission suite + integration hydration tests (H2).
**Target Platform**: JVM (annotation processor).
**Project Type**: library.
**Performance Goals**: no regression — byte-identical SQL.
**Constraints**: behavior-preserving; no hand-built `SELECT` in `HydrationEmitter`.
**Scale/Scope**: `processor/.../emitter/HydrationEmitter.java` + `core/.../sql/{SqlBuilder,QueryCompiler}.java`
(+ `QueryDefinition`).

## Constitution Check

*GATE: Must pass before Phase 0 research.*

- **I. Compile-Time Generation** — PASS.
- **II. DDD Entity Model** — PASS.
- **III. Jakarta Data Subset Conformance** — PASS.
- **IV. Test-First** — PASS.
- **V. Resolve-Plan-Emit & Single Source of SQL** — PASS (this feature implements the single renderer
  for the hydration path).
- **Module separation** — PASS (renderer stays in `core`; `HydrationEmitter` stays in `processor`).

**Verdict**: No violations.

## Project Structure

### Documentation (this feature)

```text
specs/004-hydration-selectbyfk/
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/
└── tasks.md
```

### Source Code (repository root)

```text
processor/.../emitter/HydrationEmitter.java   # primary target
core/.../sql/{SqlBuilder,QueryCompiler}.java  # selectByFk renderer
core/.../query/QueryDefinition.java           # plan type
core/.../metadata/TypeDefinition.java         # columns() projection source
```

## Complexity Tracking

> None.
