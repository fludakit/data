# Tasks: LifecycleEmitter emitBatch Dedupe

## Phase 1: Setup

- [X] T001 Establish a green baseline by running `./mvnw -pl core,processor test`.

## Phase 2: User Story 1 — single emitBatch (P1)

- [X] T002 [US1] Add a private `enum BatchOp { SAVE, INSERT, UPDATE }` with a `methodName()` accessor in `processor/.../emitter/LifecycleEmitter.java`.
- [X] T003 [US1] Add `emitBatch(ctx, method, BatchOp)` that builds the shared loop, splicing `op.methodName()`; replace the bodies of `emitSaveAll`/`emitInsertAll`/`emitUpdateAll` with calls to it.
- [X] T004 [US1] Run `./mvnw -pl processor test` and confirm emission tests pass unchanged.

## Phase 3: Polish

- [X] T005 Run `./mvnw test` and confirm every module is green.

## Notes

- Behavior-preserving; generated code byte-identical.
