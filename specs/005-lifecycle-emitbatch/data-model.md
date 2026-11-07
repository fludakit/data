# Data Model: LifecycleEmitter emitBatch

No domain-model change. Adds a private `enum BatchOp { SAVE, INSERT, UPDATE }` with a
`methodName()` accessor (`save`/`insert`/`update`) inside `LifecycleEmitter`.
