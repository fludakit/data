# Data Model: TypeDefinition Cleanup

This is a refactor of the existing metadata model; the model's shape is preserved. This
document records the model as it stands and what changes, so the plan and tasks have a single
reference.

## Current model (unchanged shape)

- **TypeDefinition** (sealed interface, `permits Entity, Aggregate, ValueObject`)
  - `typeName()`, `tableName()`, `properties()`
  - operations: `findProperty`, `hasId`, `idColumn`, `ownsAggregateLifecycle`, `columns`,
    `columnRefs`, `resolve`, `isToManyPath`
- **Prop** (sealed interface, `permits Scalar, Embedded, Association, EntityCollection,
  ElementCollection, Id, Version`)
  - `name()`, `javaType()`
  - records: `Scalar(name, javaType, columnName, nullable)`,
    `Embedded(name, javaType, definition, prefix)`,
    `Association(name, javaType, definition, fkColumn, backReference, composition, cascade)`,
    `EntityCollection(name, javaType, definition, fkColumn, mappedBy)`,
    `ElementCollection(name, javaType, definition, childTable, fkColumn)`,
    `Id(name, javaType, columnName, generated)`, `Version(name, javaType, columnName)`
- **ColumnRef**: `Simple(columnName, tableName)` | `ForeignKey(columnName, tableName)`
- **ColumnPlacement(leaf, edges)**, **JoinEdge**: `ToOne(ownerFk, childId)` | `ToMany(ownerId, childFk)`

## Changes (no shape change)

| Element | Change |
|---|---|
| `TypeDefinition.id()` | New: abstract `Prop.Id id()`; `ValueObject` returns `null`. |
| `TypeDefinition.hasId()` | From `instanceof Entity/Aggregate` → polymorphic (`Entity`/`Aggregate` true, `ValueObject` false). |
| `TypeDefinition.idColumn()` | From `instanceof` → polymorphic. |
| `TypeDefinition.ownsAggregateLifecycle()` | From `instanceof Aggregate` → polymorphic. |
| `PropVisitor<R>` | New abstraction: one visit method per `Prop` kind. |
| `TypeDefinition.columns()`/`columnRefs()` | Implemented via the single `PropVisitor` traversal. |
| `TypeDefinition.PathResolver` | Delegates property dispatch to the visitor; retains join/cycle decisions only. |

## Invariants (must hold)

- `properties()` declaration order is the traversal order.
- `columns()`/`columnRefs()`/`resolve()` output is identical to before.
- `ValueObject.id() == null`; `hasId()` is `false` for `ValueObject`.
