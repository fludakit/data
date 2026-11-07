# Research: LifecycleEmitter emitBatch (#28 item 4)

## Decision: BatchOp enum

- **Decision**: A private `enum BatchOp { SAVE, INSERT, UPDATE }` whose `methodName()` returns
  `"save"`/`"insert"`/`"update"`. `emitBatch(ctx, method, op)` builds the loop once, splicing
  `op.methodName()`.
- **Rationale**: The three methods are identical except the delegated method name; an enum makes the
  dispatch exhaustive.
- **Alternatives**: pass a `String` — rejected (untyped, error-prone).

## Residual unknowns

None.
