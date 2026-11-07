# Contract: Cursor Sort/Projection Rendering (must remain stable)

This is a behavior-preserving refactor. The contract below MUST NOT change.

## Generated SQL

- The cursor-paging to-many sort/projection emitted by `FindEmitter` MUST produce the exact same SQL
  (byte-identical), including the aggregate `MIN`/`MAX` subqueries, `LEFT JOIN`, `GROUP BY`, `ORDER BY`
  aliases, and keyset seek terms, in the same order.
- `ToManySortTest`, `CursorSeekEmissionTest`, and the emission suite are the authoritative verification.

## Renderer (QueryCompiler / SqlBuilder / QueryDefinition)

- `aggregateSortTerm` and `columns()` MUST be the single implementations (no duplicate enumeration).
- The new `CursorSortProjection` plan type is additive and rendered through the same quoting/binding
  conventions.

## Emitter (FindEmitter)

- `FindEmitter` MUST NOT assemble SQL text for cursor paging; it passes sort terms to the core.
