# FluDa Data

[![Build](https://github.com/fludakit/data/actions/workflows/build.yml/badge.svg)](https://github.com/fludakit/data/actions/workflows/build.yml)

A fluent data-access toolkit for Jakarta EE / CDI applications.

## Modules

- `fluda-data-core`: the core API and supporting types.

## Jakarta Data and SQL scope

Repository methods and derived queries are analyzed at compile time and emitted as generated
implementations that execute through `JdbcClient`. Generated SQL uses a dialect seam for identifier
quoting and pagination syntax; the shipped dialects are H2 (the default) and PostgreSQL, selected
with the `-Afluda.dialect` annotation-processing option.
Repository type resolution, entity/value-object model construction, and method declaration
validation are handled by focused processor components before SQL and source generation.

Jakarta Data Query Language (JDQL) parsing and translation are not part of this implementation.
Methods annotated with `@Query` use native SQL supplied by the application, which is not rewritten
by the dialect layer and may therefore be database-specific.

Free-style `@Repository` interfaces can declare explicitly annotated `@Find` and `@Save` methods
without extending a Jakarta Data repository interface; unannotated abstract methods are rejected.
Java default methods retain their interface implementation and are not overridden by generated code.
`@Find` supports entity-array results; parameter-based `@Delete` methods derive delete predicates
from parameter names or `@By`. Native `@Query` parameters bind by position or by `@Param`/source
parameter name without rewriting the supplied SQL text.
Entity lifecycle methods support a single entity, `List<E>`, or `E[]`, including `void` results.

## Building

```bash
./mvnw clean install
```

## Documentation

See the [reference documentation site](https://fludakit.github.io/) for getting started and full API reference.

## Contributing

Contributions are welcome — issues, pull requests, and feature suggestions are all encouraged. See [CONTRIBUTING.md](CONTRIBUTING.md) for how to build the project and submit changes.

## Generated artifacts

For each `@Repository` interface `Widgets extends CrudRepository<Widget, Long>` the annotation
processor emits, in the repository's package:

| Artifact | Holds |
|---|---|
| `WidgetsImpl` | the CDI bean; one `static final String SQL_*` per rendered statement |
| `WidgetMetadata` | the reflection-free `EntityMetadata<Widget>`: table, typed path mappings, `navigate(path, root)` |
| `WidgetRowMapper` | the reflection-free row reader (when the entity passes the gate) |
| `_Widget` (+ `_<Widget><Field>` navs) | the Jakarta Data static metamodel, navigation only |
| `WidgetsQueries` | the logical structure of each query-by-names method (when the repository declares at least one query-by-names or `@Find` finder; see below) |

### Query metadata

Every query-by-names method (`findBy…`, `countBy…`, `deleteBy…`, paged variants) and every
`@Find`/`@By` structured finder contributes a nested query definition inside `<Repository>Queries`:

```java
public final class WidgetsQueries {
    public static final class FindByStatusQuery implements QueryDefinition {
        public static final FindByStatusQuery INSTANCE = new FindByStatusQuery();
        // operation(), resultKind(), predicates(), sorts(), paging(), limit(), seekTerms(), paramBindings()
    }
}
```

Each accessor returns the logical query structure the SQL literal in `WidgetsImpl` was rendered
from: predicate and sort paths, paging mode, keyset seek terms and parameter bindings. The processor
`TypeMapping` implements `AttributePathResolver<String>` to map logical paths to physical
`ColumnRef`s, and `SqlBuilder` combines that resolver and the table schema with the query plan during
rendering. The generated query metadata is not a runtime hook — nothing
in the engine reads it — and overloads get a declaration-order numeric suffix
(`FindByStatusQuery2`). `new SqlBuilder(schema, resolver, definition).dialect(...).select()`
(or `.count()`/`.delete()`) is the single renderer that turns a
query definition back into SQL (full-materialisation projections are an emission-time argument, not
definition state — a published definition re-renders the root projection).

## Breaking changes in 1.0.0-SNAPSHOT (navigable metamodel and query pipeline iterations)

- Relation attributes' plumbing (`ForeignKeyAttribute.target()/
  targetDescriptor()`, `OneToManyAttribute.inverseName()/childFkColumn()`,
  `ElementCollectionAttribute.childTable()/parentFkColumn()/elementDescriptor()`,
  `EmbeddedAttribute.embedded()`) removed — attributes navigate only; path resolution lives in
  the generated `AttributePathResolver` index.
- The misleading `ColumnAttribute`, `SortableColumnAttribute`, and `TextColumnAttribute` names (which
  carried logical attribute names, not physical column details) are
  replaced by `ScalarAttribute`, `SortableScalarAttribute`, and `TextScalarAttribute`. These
  user-facing attributes contain logical names and paths, never physical column names. All
  `PathAttribute`s expose a local `name()` and root-relative `path()`; sort values use the latter.
- `ForeignKeyAttribute`/`EmbeddedAttribute`/`OneToManyAttribute`/`ElementCollectionAttribute` renamed
  to the ported Jakarta Data 1.1 vocabulary: `EntityAttribute`, `EmbeddableAttribute`, `ListAttribute`
  (covers one-to-many and element collections) under the new `NavigableAttribute` root; `*Record`
  implementations shrink to `(name)`.
- `EntityMetadata.columnRef` failures for unknown/navigable-but-unknown-leaf paths now report a
  single `Unknown property '<path>' on <Entity>` message.
- `AttributePathResolver<A>.columnRefs()` owns the ordered path-to-placement index. Generated
  runtime metadata uses `PathAttribute<T>` keys; processor mappings use logical string paths.
  `ColumnRef` contains only physical column/table/join placement and never an attribute name.
  `TableSchema.columns()` remains scoped to physical columns in one table.
- `EntityMetadata.attributes()` no longer flattens embedded leaves. `attribute("address.city")`
  does not resolve — dot paths resolve through `EntityMetadata.columnRef(String)`, which also
  reports the joins a path contributes.
- Method-name finders whose predicate path traverses one relation (`findByCustomerName`) now
  generate join-aware SQL. `findByCustomerId`-style names traverse the relation's id instead of
  comparing the owner's FK column directly (Jakarta Data 1.0.1 traversal semantics); orphan FK
  rows are consequently excluded from such results. There is deliberately no FK short-circuit:
  comparing the owner's FK column directly (`"customer_id" = :id`) would newly include orphan-FK
  rows and diverge from traversal semantics — the join cost is the spec's cost.
- Path-depth asymmetry (known, documented): compile-side method-name resolution now traverses
  to-one `Reference` chains transitively (arbitrary depth, cycle-safe) — `findByCustomerCountryCode`
  chains two LEFT JOINs — while to-many heads stay depth-1 existential leaves. The runtime
  `columnRef` used by `Order.by`/`Sort` remains one-hop bounded: it resolves the flattened
  `columns()` paths plus the generated `columnRefs()` map — exactly the one-hop relation leaves
  whose values the `navigate()` switch answers for CURSOR keys (both surfaces come from one shared
  generation-time enumeration), so a sortable path always has a working cursor key and anything
  deeper fails fast with `Unknown property '<path>' on <Entity>`.
- Cursors for relation-sorted paged reads now end with the root id key.
- `deleteBy…` names whose predicate path traverses one relation now render correlated `EXISTS`
  semi-joins — `deleteByCustomerName` emits `DELETE FROM "order" WHERE EXISTS (SELECT 1 FROM
  "customer" "customer" WHERE "customer"."id" = "order"."customer_id" AND "customer"."name" = :name)`
  — and delete exactly the roots the matching `findBy…` read returns. Before, they were NOTE-degraded
  to a join-free predicate on the lower-snake column, which could match the wrong rows. Relation-free
  delete rendering is unchanged; multi-hop paths stay NOTE-rejected (legacy fallback).
- `SqlBuilder.existsClause(rootTable, rootColumn, childTable, alias, aliasColumn, predicate, leafRef)`
  is replaced by `SqlBuilder.existsConjunct(schema, predicate, leafRef)`: the correlation edge is read
  from the leaf ref's last `JoinRef` instead of six loose strings.
- `TableSchema` describes one physical table only. Entity-rooted resolution and to-many
  classification belong to `AttributePathResolver`; `TypeMapping` and `EntityMetadata<T>` provide
  the resolver with their respective compile-time and runtime key types. Structured query rendering
  consumes both views through `SqlBuilder.build(TableSchema, AttributePathResolver, QueryDefinition)`.

## License

Apache License, Version 2.0. See [LICENSE](LICENSE).
