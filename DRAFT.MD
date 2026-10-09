# Jakarta Data Processor: Design Notes

This document explains the current implementation for developers working on the Jakarta Data
processor. It describes the model boundaries, query metadata, generated artifacts, and deliberate
limitations; it is not a promise of support for every Jakarta Data feature or database.

## Scope

- Jakarta Data 1.0 repository methods are analyzed at compile time and implemented by generated
  classes that execute through `JdbcClient`.
- H2 is the only implemented and tested SQL dialect. Generated SQL has a small dialect boundary
  for database-specific syntax such as identifier quoting and pagination; the boundary is an
  extension point, not evidence that another database is supported.
- `@Query` text is application-supplied native SQL. The processor binds its parameters but does not
  parse or rewrite the SQL for a dialect.
- Jakarta Data Query Language (JDQL) parsing and translation are out of scope.

## Processing and execution flow

1. The annotation processor resolves repository types, inspects entity declarations, and validates
   repository methods.
2. It builds compile-time entity metadata and resolves structured query methods to physical
   columns, joins, predicates, ordering, paging, and parameter bindings.
3. The processor uses `SqlBuilder` and the selected `SqlDialect` to render generated SQL. SQL
   literals are written into the generated repository implementation.
4. At runtime, the generated implementation invokes `JdbcClient` with those SQL literals and
   bound values. There is no runtime ORM or general-purpose query planner in this path.

The `core`, `qbn`, `processor`, and `integration-tests` Maven modules separate shared metadata and
SQL support, query-by-name support, annotation processing, and integration coverage.

## Entity metadata

Keep the processor's compile-time analysis model separate from the runtime metamodel exposed to
applications. They describe related mappings but have different callers and responsibilities.

### Runtime `EntityMetadata<T>`

`EntityMetadata<T>` is generated metadata for an annotated entity, value object, or aggregate root.
It lives in `core` because generated repositories and runtime features use it. It exposes the Java
entity class, Jakarta Data attributes, identifier, table schema, and entity-rooted attribute resolver.
Its `navigate(path, root)` method reads a value by traversing the root object's property path without
reflection, which lets cursor support build keys from materialized rows.
The generated Jakarta Data static metamodel supplies type-safe attribute instances; entity metadata
provides runtime mapping and navigation data.

`attributes()` contains each root-level metamodel node once, including embedded and relation
containers. `AttributePathResolver<PathAttribute<T>>.columnRefs()` indexes SQL-resolvable leaves by
their `PathAttribute<T>` keys, including flattened embedded leaves and supported relation paths.
Generated metadata preserves declaration order in this index; the default `columns()` view filters
out joined or qualified mappings to expose the root table's physical columns without duplicating
mapping state. `ColumnRef` contains only `(column, ownerTableName, ownerAlias, joins)`: the logical
path remains in the resolver's key and is never duplicated in this table-placement value. Each
`JoinRef` contains `(ownerTableName, ownerAlias, ownerColumn, childTableName, childAlias, childColumn)`.

This is not a compile-time processor model and should not move into the processor module.

### Processor compile-time model

The sealed `TypeMapping` hierarchy now belongs to the processor module. It is internal input to
annotation analysis and SQL generation, not the application's runtime metamodel:

| Model | Storage shape | Identifier |
| --- | --- | --- |
| `EntityTypeMapping` | Entity or aggregate-root table | Has an identifier |
| `EmbeddedValueObjectMapping` | Value-object leaves flattened into the owning table | None |
| `ElementCollectionValueObjectMapping` | Value-object element-collection child table | None |

A value-object class can be used in more than one way, so it can have distinct compile-time model
instances for embedded storage and element-collection storage. The model represents the use site as
well as the Java type.

Each model exposes its type name, stereotype, identifier (when present), properties, table name (when
present), and flattened `ColumnRef` list. Properties use sealed `TypeMapping.Prop` shapes:

| Property shape | Meaning |
| --- | --- |
| `Id` / `Scalar` | Identifier or ordinary column-bearing property |
| `Embedded` | Embedded value object whose leaves flatten into the current table |
| `Reference` | To-one foreign-key reference to another entity model |
| `EntityList` | To-many entity relation stored in the target entity table |
| `ElementList` | Value-object collection stored in an external child table |

Flattening preserves declaration order: the identifier comes first, followed by scalar and
foreign-key columns and then embedded leaves. Embedded physical column names use the embedded
column as a prefix; their logical paths use dot notation. To-many containers do not contribute
columns to the owning table. Relation targets carry the model needed to derive table and key
placement rather than duplicating that information in unrelated strings.

The processor-local `RepositoryProcessor.EntityModel` record additionally carries compiler details
such as `TypeMirror`, JavaPoet types and row-mapping properties. Both compile-time representations
remain in the processor module.

### SQL rendering views

`TableSchema` is a narrow SQL-renderer projection for one table: table name, identifier column, and
ordered physical columns. It has no logical paths or relationship classification.
`AttributePathResolver<A>` owns the ordered path-to-`ColumnRef` index, path lookup, and
`isToManyPath(path)` classification for entity-rooted paths whose first segment is to-many. The
processor's `TypeMapping` uses string keys for compile-time SQL generation;
runtime `EntityMetadata<T>` uses `PathAttribute<T>` keys for generated repository helpers. Sharing
these small rendering views avoids duplicating SQL logic without merging compile-time and runtime
metadata.

### Attribute names and paths

Generated metamodel attributes expose logical Java property names and paths, not physical database
column names. Scalar attributes use `ScalarAttribute`, `SortableScalarAttribute`, or
`TextScalarAttribute`; the types describe navigation/sort capability rather than storage columns.
Physical columns and join placement remain in `ColumnRef`; the path-to-ref association belongs to
the relevant `AttributePathResolver`.

Every generated `PathAttribute` exposes `name()` for its local property name and `path()` for the
dot-separated path from the entity root. For example, the generated `zipcode` attribute under
`Customer.address` has `name() == "zipcode"` and `path() == "address.zipcode"`. A navigable
attribute's path identifies its current node; its children carry their own root-relative paths.
Sortable leaf attributes use `path()` when creating Jakarta Data `Sort` values. The processor still
builds its `TypeMapping` directly from compiler elements; these generated paths describe the
user-facing metamodel and do not drive compile-time entity analysis.

## Query metadata

`QueryDefinition` describes a structured repository method using logical attribute paths. It is a
structural plan, not SQL text, and has no entity type parameter or dependency on the processor's
`TypeMapping`.

| Accessor | Describes |
| --- | --- |
| `operation()` / `resultKind()` | The requested operation and declared result shape |
| `predicates()` | WHERE conjuncts and logical property paths |
| `sorts()` | Ordering terms and logical property paths |
| `paging()` / `limit()` | Paging mode and any static row cap |
| `seekTerms()` | Resolved key terms published for cursor paging |
| `paramBindings()` | Method argument slots mapped to named SQL parameters |

The processor's `TypeMapping` carries the mapping from each resolvable logical attribute path to a
`ColumnRef`, including its physical column and any relationship joins. `SqlBuilder.build(TableSchema,
AttributePathResolver, QueryDefinition)` combines those views with each logical predicate and sort. It chooses a normal
join, an `EXISTS` predicate, or aggregate sorting based on the mapped relationship shape; the query
plan does not duplicate physical mappings.

For a structured query, `SqlBuilder.build(TableSchema, AttributePathResolver, QueryDefinition)` combines the schema, resolver, and plan
to render SQL. The processor bakes that SQL into the generated implementation. When applicable,
The processor also generates `<Repository>Queries` with one nested `QueryDefinition` implementation per
query-by-name method and structured `@Find` finder. That published metadata represents the plan the
SQL was rendered from; it is not a runtime hook for rendering SQL. Cursor execution has additional
generated logic; `SqlBuilder.build` does not consume `seekTerms()`.

## Repository processing

Repository processing is divided among focused package-private components:

- `RepositoryTypeResolver` resolves repository entity and identifier types, including generic
  parent interfaces.
- `RepositoryTypeMappingBuilder` analyzes mapped types and builds entity/value-object metadata,
  including relation resolution and cycle handling.
- `RepositoryMethodValidator` checks operation annotations and repository method declarations.
- `RepositorySourceWriter` centralizes generated-file output and generated-name registration.
- `RepositoryProcessor` coordinates analysis and generation, including method-specific JavaPoet
  source construction.

An explicitly annotated free-style `@Repository` can declare supported `@Find` and `@Save` methods
without extending a Jakarta Data repository interface. Unannotated abstract methods in such a
repository are rejected. Query-by-method-name inference relies on the entity and identifier types
provided by a Jakarta Data repository base interface. Java default methods keep their interface
implementation and are not replaced by generated methods.

The current processor behavior also includes entity-array results for `@Find`, parameter-based
`@Delete` predicates, positional and named parameter binding for native `@Query`, and entity
lifecycle methods accepting a single entity, `List<E>`, or `E[]`. Lifecycle methods may return
`void` or the corresponding entity shape. Consult the implementation and tests for the exact
supported signatures; this list does not imply complete Jakarta Data feature coverage.

## SQL rendering and dialects

`SqlBuilder` renders structured generated SQL from a `TableSchema`, an `AttributePathResolver`, and,
for a whole query method, a `QueryDefinition`. `SqlDialect` owns syntax that varies between databases, including identifier
quoting and pagination. `H2Dialect` is the only shipped implementation and is the default for
existing convenience APIs.

The dialect applies to SQL generated from structured operations. Native SQL supplied to `@Query`
is passed through unchanged, so its syntax remains the application's responsibility.

## References

- [Jakarta Data 1.0](https://jakarta.ee/specifications/data/1.0/)
- [Jakarta Data Query by Name addendum](https://jakarta.ee/specifications/data/1.0/jakarta-data-addendum-1.0)
- [FluDa Jdbc Client](https://github.com/fludakit/jdbc-client) (local clone: `..\jdbc-client`)
- [Hibernate ORM metamodel generator](https://github.com/hibernate/hibernate-orm/tree/main/tooling/metamodel-generator)
- [Micronaut Data processor](https://github.com/micronaut-projects/micronaut-data/tree/master/data-processor)
- [Micronaut Data: how it works](https://micronaut-projects.github.io/micronaut-data/latest/guide/#howItWorks)
