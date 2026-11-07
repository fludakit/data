# Implementation Plan: SqlBuilder pagedSeekStatement Aliasing

**Branch**: `006-pagedseek-aliasing` | **Date**: 2026-11-07 | **Spec**: [spec.md](./spec.md)

## Summary

Replace the derived-table re-aliasing in `SqlBuilder.pagedSeekStatement` (repeated `String.replace` on
rendered SQL) with node-level aliasing: pass the ORDER BY as a parallel `orderByTerms` list, render the
ORDER BY in core, and map each term exactly (aggregate -> `inner_.agg_N`, root-qualified -> `inner_`).
`FindEmitter` passes structured terms instead of hand-assembling `orderByClause`.

## Technical Context

**Language/Version**: Java 21. **Primary Dependencies**: JUnit 5, AssertJ. **Storage**: N/A.
**Testing**: `SqlBuilderSeekClauseTest` (core), `CursorSeekEmissionTest` (processor). **Target Platform**: JVM.
**Project Type**: library. **Performance Goals**: none. **Constraints**: byte-identical output.
**Scale/Scope**: `SqlBuilder.java` (core), `FindEmitter.java` (processor), `SqlBuilderSeekClauseTest.java`.

## Constitution Check

*GATE: Must pass before Phase 0 research.*

- **V (Single Source of SQL)** — IMPROVED: cursor ORDER BY rendering moves from `FindEmitter` into core.
- **IV (Test-First)** — characterization tests already pin the byte-identical output; they stay green.
- No other principle affected. PASS.

## Project Structure

```text
core/.../sql/SqlBuilder.java                      # pagedSeekStatement + renderOrderBy + repoint
core/.../sql/SqlBuilderSeekClauseTest.java        # signature updates + content-vs-position regression
processor/.../emitter/FindEmitter.java            # pass orderByTerms, drop orderByClause
```

## Complexity Tracking

> None — mechanical re-pointing made exact; no new plan types.
