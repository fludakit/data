# Research: HydrationEmitter SELECT Routing (#28 item 2)

## Scope

Internal, behavior-preserving refactor of the three hand-built hydration SELECTs in
`HydrationEmitter`.

## Raw SELECT sites

1. `emitEntityCollectionLoader` — `SELECT <cols> FROM <child> WHERE <fk> = :parentId`, where `<cols>`
   is a hand-built `column AS alias` list (id, scalars, association FKs).
2. `emitElementCollectionLoader` — `SELECT * FROM <child> WHERE <fk> = :parentId`.
3. `emitCompositionAssociationLoader` — `SELECT <cols> FROM <child> WHERE <id> = :childId`.

## Decision 1: selectByFk renderer

- **Decision**: Add `SqlBuilder.selectByFk(table, fkColumn)` (a `QueryDefinition.SelectByFk`) that
  renders `SELECT <projection> FROM <table> WHERE <fk> = :parentId`.
- **Rationale**: One renderer for the two FK-based loaders; matches `deleteByFk`/`selectChildIds`.

## Decision 2: the `column AS alias` projection

- **Decision**: The entity-collection projection aliases each column to its property name (id/scalar)
  or its FK column name (associations); this is the row-mapper contract, so the renderer MUST keep it.
  The projection is derived from `TypeDefinition.columns()` (column set) plus `properties()` (aliases).
- **Rationale**: `columns()` gives physical columns but not property names; the alias is the row
  mapper's read key. Byte-identical requires preserving it.
- **Alternatives**: drop the aliases — rejected (changes generated SQL / breaks the row mapper).

## Decision 3: composing-association by-id SELECT

- **Decision**: Reuse the existing by-identifier shape (`selectById`) rather than `selectByFk`.
- **Rationale**: The composing loader keys by the child identifier, not the parent FK.

## Decision 4: order stability

- **Decision**: Preserve column and `:param` order; byte-identical SQL.

## Residual unknowns

The exact `SelectByFk` record shape (whether it carries the aliased projection or derives it from a
passed `TypeDefinition`) is finalized during implementation against `TypeDefinition.columns()`.
