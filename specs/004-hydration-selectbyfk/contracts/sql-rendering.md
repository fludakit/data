# Contract: Hydration SELECT Rendering (must remain stable)

This is a behavior-preserving refactor. The contract below MUST NOT change.

## Generated SQL

- The three hydration SELECTs MUST be byte-identical, including the `column AS alias` projection
  (property-name alias for id/scalar, FK-column alias for associations) and the `:parentId`/`:childId`
  parameters.

## Renderer (SqlBuilder / QueryCompiler / QueryDefinition)

- `selectByFk` (and the by-id variant) are additive and render through the same quoting conventions.
- `TypeDefinition.columns()` is the single column-set source.

## Emitter (HydrationEmitter)

- `HydrationEmitter` MUST NOT build raw `SELECT` strings.
