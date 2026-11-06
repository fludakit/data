# Implementation Plan: TypeDefinition Cleanup (polymorphic id + PropVisitor)

**Branch**: `001-typedefinition-cleanup` | **Date**: 2026-11-06 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/001-typedefinition-cleanup/spec.md`

## Summary

Refactor `core/.../metadata/TypeDefinition.java` (issues #24 + #25) so that (a) an
entity/aggregate's identifier is exposed through a single polymorphic `TypeDefinition.id()`,
and (b) property traversal is consolidated behind one `PropVisitor`, eliminating all
`instanceof Entity/Aggregate` dispatch and the duplicated `Prop` walks. Behavior is preserved:
generated repository code stays byte-identical and all existing tests pass unchanged.

## Technical Context

**Language/Version**: Java 21 (records, sealed interfaces, switch pattern matching). Build on
JDK 21+; JDK 25 to publish releases.

**Primary Dependencies**: Java Annotation Processing API (`javax.annotation.processing`),
JavaPoet (emitter code generation), JUnit 5 (tests). ANTLR is in `qbn` and untouched.

**Storage**: N/A (compile-time metadata/codegen library; no runtime persistence in scope).

**Testing**: JUnit 5 unit tests (`core/.../metadata/TypeDefinitionTest`, `JoinEdgeTest`,
`MutationPlanTest`); processor emission tests via `ProcessorHarness`; integration tests over
embedded H2.

**Target Platform**: JVM. The processor runs at compile time; generated code runs in Jakarta
EE/CDI apps.

**Project Type**: library (compile-time annotation processor / code generator).

**Performance Goals**: No regression — byte-identical generated output; annotation-processing
build time not materially slower.

**Constraints**: Behavior-preserving; no public API change; no `instanceof Entity/Aggregate`
inside `TypeDefinition`; a single `Prop` traversal.

**Scale/Scope**: One core file (`TypeDefinition.java`) plus id-extraction call sites across five
emitters and `RepositoryVisitor`.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

- **I. Compile-Time Repository Generation** — PASS. Refactor is compile-time metadata only; no
  runtime reflection/codegen introduced.
- **II. DDD Entity Model** — PASS. `Entity`/`Aggregate`/`ValueObject` model and its contracts are
  unchanged; only dispatch is consolidated.
- **III. Jakarta Data Subset Conformance** — PASS (not directly affected; no scope change).
- **IV. Test-First (NON-NEGOTIABLE)** — PASS. Work is driven by the existing
  unit/emission/integration suites plus direct `TypeDefinition` unit tests (per issue #18 precedent).
- **V. Resolve-Plan-Emit Pipeline & Single Source of SQL** — PASS. This feature *implements*
  Principle V's "polymorphic dispatch, no instanceof, polymorphic id(), PropVisitor" clause.

**Verdict**: No violations; no complexity justification required.

## Project Structure

### Documentation (this feature)

```text
specs/001-typedefinition-cleanup/
├── plan.md              # This file
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
├── contracts/           # Phase 1 output (API surface invariant)
└── tasks.md             # Phase 2 output (/speckit-tasks, not created here)
```

### Source Code (repository root)

```text
core/src/main/java/io/github/fludakit/data/metadata/
├── TypeDefinition.java      # primary target: add id(), PropVisitor, collapse walks
├── ColumnRef.java           # unchanged (semantics preserved)
├── ColumnPlacement.java     # unchanged
└── JoinEdge.java            # unchanged
core/src/test/java/io/github/fludakit/data/metadata/
└── TypeDefinitionTest.java  # direct unit tests (extend as needed)
processor/src/main/java/io/github/fludakit/data/processor/
├── RepositoryVisitor.java   # replace idPropertyName with def.id()
└── emitter/
    ├── FindEmitter.java     # replace inline instanceof id extraction with def.id()
    ├── WriteEmitter.java    # delete idProp helper
    ├── HydrationEmitter.java# delete idProp helper
    ├── LifecycleEmitter.java# delete idProp helper
    ├── MetamodelEmitter.java# delete idProp helper
    └── RowMapperEmitter.java# delete idProp helper
```

**Structure Decision**: No new modules or directories. The change is confined to the existing
`core` metadata model and the `processor` emitters that consume it.

## Complexity Tracking

> None — Constitution Check has no violations.
