# Contract: TypeDefinition / Prop API surface (must remain stable)

This feature is a behavior-preserving refactor. The public API surface below is the contract
that MUST NOT change; downstream consumers (the processor emitters and integration tests) rely
on it.

## TypeDefinition (sealed)

- `String typeName()`
- `String tableName()` (null for `ValueObject`)
- `List<Prop> properties()` (declaration order)
- `default Prop findProperty(String name)`
- `boolean hasId()`
- `String idColumn()`
- `boolean ownsAggregateLifecycle()`
- `List<ColumnRef> columns()`
- `Map<String, ColumnRef> columnRefs()`
- `ColumnPlacement resolve(String path)`
- `boolean isToManyPath(String path)`

Added (net-new, backward-compatible): `Prop.Id id()` — `Entity`/`Aggregate` return their id;
`ValueObject` returns null.

## Prop (sealed) — kind records and accessors

Unchanged. Each record's accessors (e.g., `Prop.Id.columnName()`,
`Prop.Association.fkColumn()`, `Prop.ElementCollection.childTable()`) must keep identical
signatures and semantics.

## ColumnRef / ColumnPlacement / JoinEdge

Unchanged. `ColumnRef.Simple`, `ColumnRef.ForeignKey`, `JoinEdge.ToOne`, `JoinEdge.ToMany`
must produce identical results.

## Behavioral contract

- `columns()` and `columnRefs()` enumerate the same columns in the same order as before.
- `resolve(path)` returns the same `ColumnPlacement` (leaf + edges) for every valid path, and
  throws the same exceptions (`UnknownAttributePathException`, etc.) for invalid paths.
