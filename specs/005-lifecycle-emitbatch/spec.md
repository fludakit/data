# Feature Specification: LifecycleEmitter emitBatch Dedupe

**Feature Branch**: `005-lifecycle-emitbatch`

**Created**: 2026-11-07

**Status**: Draft

**Input**: User description: "Dedupe `emitSaveAll`/`emitInsertAll`/`emitUpdateAll` into one
`emitBatch(ctx, method, BatchOp)` (GitHub #28, item 4)."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Single emitBatch (Priority: P1)

As a maintainer, I want one `emitBatch(ctx, method, BatchOp)` instead of three identical
`emitSaveAll`/`emitInsertAll`/`emitUpdateAll` loops, so the batch loop lives once.

**Why this priority**: It removes a three-way copy-paste whose only difference is the delegated method name.

**Independent Test**: `./mvnw -pl processor test` passes unchanged.

**Acceptance Scenarios**:

1. **Given** a batch save/insert/update method, **When** the emitter emits it, **Then** it uses the
   single `emitBatch` with the right `BatchOp`, and the generated code is unchanged.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: A single `emitBatch(ctx, method, BatchOp)` MUST replace the three identical methods.
- **FR-002**: `BatchOp` (SAVE/INSERT/UPDATE) MUST select the delegated method name.
- **FR-003**: Generated code MUST be byte-identical; emission tests pass unchanged.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: The three methods collapse to one `emitBatch`.
- **SC-002**: 100% of emission tests pass unchanged.
- **SC-003**: Generated code byte-identical.

## Assumptions

- Behavior-preserving; `BatchOp` is a private enum mapping to the delegated method name.
