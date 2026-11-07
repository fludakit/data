# Contract: SQL Rendering (must remain stable)

This is a behavior-preserving refactor. The contract below MUST NOT change.

## Generated SQL

- Every child-table mutation emitted by `WriteEmitter` MUST produce the exact same SQL string as
  before (byte-identical), including dialect quoting and `:param` names and their order.
- `WriteEmissionTest` and the integration tests are the authoritative verification.

## Renderer API (SqlBuilder / QueryDefinition)

- Existing methods (`insert`, `update`, `upsert`, `deleteById`, `deleteByFk`, `selectForeignKey`)
  MUST keep their signatures and output unchanged.
- Any new method (e.g. `deleteChildByIdAndFk`, `selectByFk`, a child-table `insert` overload) is
  additive only and MUST render through the same quoting/binding conventions.

## Binding

- Child-table bindings MUST continue to use the existing `.param(...)` helpers; parameter names and
  order are part of the byte-identical contract.
