# Implementation Plan: QueryCompiler Single Renderer

**Branch**: `007-querycompiler-single-renderer` | **Date**: 2026-11-07 | **Spec**: [spec.md](./spec.md)

## Summary

Three dedupes in the SQL layer: (1) drop `SqlFragment.Conjunct` and reuse `QueryCompiler.Conjunct`;
(2) render `aggregateSortTerm`'s deeper joins via `SqlFragment.joins`; (3) route the classic
`selectBy`/`countBy`/`deleteBy` through `SqlFragment` (adding a table-alias form and re-pointing
`appendWhere` at the accumulator).

## Technical Context

**Language/Version**: Java 21. **Primary Dependencies**: JUnit 5. **Storage**: N/A.
**Testing**: `SqlBuilderBuildTest` (core) + full suite. **Target Platform**: JVM. **Project Type**: library.
**Performance Goals**: none. **Constraints**: byte-identical output.
**Scale/Scope**: `SqlFragment.java`, `QueryCompiler.java`.

## Constitution Check

*GATE: Must pass before Phase 0 research.*

- **V (Single Source of SQL)** — IMPROVED: one accumulator + one join renderer. PASS.
- **IV (Test-First)** — characterization tests pin byte-identical output. PASS.

## Project Structure

```text
core/.../sql/SqlFragment.java     # drop duplicate Conjunct; add table(.., alias); appendFrom helper
core/.../sql/QueryCompiler.java   # aggregateSortTerm via SqlFragment.joins; classic helpers via SqlFragment
```

## Complexity Tracking

> None — mechanical dedupe; no new plan types.
