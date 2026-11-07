# Implementation Plan: LifecycleEmitter emitBatch Dedupe

**Branch**: `005-lifecycle-emitbatch` | **Date**: 2026-11-07 | **Spec**: [spec.md](./spec.md)

## Summary

Replace `emitSaveAll`/`emitInsertAll`/`emitUpdateAll` with one `emitBatch(ctx, method, BatchOp)`
that selects the delegated method name from a `BatchOp` enum. Behavior preserved.

## Technical Context

**Language/Version**: Java 21. **Primary Dependencies**: JavaPoet, JUnit 5. **Storage**: N/A.
**Testing**: emission suite. **Target Platform**: JVM. **Project Type**: library.
**Performance Goals**: none. **Constraints**: byte-identical output.
**Scale/Scope**: `processor/.../emitter/LifecycleEmitter.java` only.

## Constitution Check

*GATE: Must pass before Phase 0 research.*

- **I–V, Module separation** — PASS. No violations; this is a mechanical dedupe.

## Project Structure

```text
processor/.../emitter/LifecycleEmitter.java   # primary target
```

## Complexity Tracking

> None.
