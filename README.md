# FluDa Data

[![Build](https://github.com/fludakit/data/actions/workflows/build.yml/badge.svg)](https://github.com/fludakit/data/actions/workflows/build.yml)

A fluent data-access toolkit for Jakarta EE / CDI applications.

## Modules

- `fluda-data-core`: the core API and supporting types.

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
| `WidgetMetadata` | the reflection-free `EntityDescriptor<Widget>`: table, columns, navigated refs, `value()` |
| `WidgetRowMapper` | the reflection-free row reader (when the entity passes the gate) |
| `_Widget` (+ `_<Widget><Field>` navs) | the Jakarta Data static metamodel, navigation only |
| `WidgetsModel` | the resolved structure of each query-by-names method (see below) |

### Query metadata

Every query-by-names method (`findBy…`, `countBy…`, `deleteBy…`, paged variants) and every
`@Find`/`@By` structured finder contributes a nested class inside `<Repository>Model`:

```java
public final class WidgetsModel {
    public static final class FindByStatusQueryModel implements QueryModel<Widget> {
        public static final FindByStatusQueryModel INSTANCE = new FindByStatusQueryModel();
        // operation(), resultKind(), predicates(), sorts(), paging(), limit(), seekTerms(), paramBindings()
    }
}
```

Each accessor returns the resolved structure the SQL literal in `WidgetsImpl` was rendered from:
predicates and sorts paired with their physical `ColumnRef`s (and the `LEFT JOIN` edges they
contribute), the paging mode, the keyset seek terms and the parameter bindings. It is metadata, not a
runtime hook — nothing in the engine reads it — and overloads get a declaration-order numeric suffix
(`FindByStatusQueryModel2`). `SqlBuilder.build(schema, model)` is the single renderer that turns a
model back into SQL.

## Breaking changes in 1.0.0-SNAPSHOT (navigable metamodel iteration)

- `ColumnAttribute.column()` and the relation attributes' plumbing (`ForeignKeyAttribute.target()/
  targetDescriptor()`, `OneToManyAttribute.inverseName()/childFkColumn()`,
  `ElementCollectionAttribute.childTable()/parentFkColumn()/elementDescriptor()`,
  `EmbeddedAttribute.embedded()`) removed — attributes navigate only; resolution moved to the
  generated `EntityDescriptor.navigationRefs()`.
- `ForeignKeyAttribute`/`EmbeddedAttribute`/`OneToManyAttribute`/`ElementCollectionAttribute` renamed
  to the ported Jakarta Data 1.1 vocabulary: `EntityAttribute`, `EmbeddableAttribute`, `ListAttribute`
  (covers one-to-many and element collections) under the new `NavigableAttribute` root; `*Record`
  implementations shrink to `(name)`.
- `EntityDescriptor.columnRef` failures for unknown/navigable-but-unknown-leaf paths now report a
  single `Unknown property '<path>' on <Entity>` message.
- `EntityDescriptor.columns()` no longer has a derived default implementation. Implementers must
  supply the explicit column view (the generated `<Entity>Metadata` does; hand-written test
  descriptors must too).
- `EntityDescriptor.attributes()` no longer flattens embedded leaves. `attribute("address.city")`
  does not resolve — dot paths resolve through `EntityDescriptor.columnRef(String)`, which also
  reports the joins a path contributes.
- Method-name finders whose predicate path traverses one relation (`findByCustomerName`) now
  generate join-aware SQL. `findByCustomerId`-style names traverse the relation's id instead of
  comparing the owner's FK column directly (Jakarta Data 1.0.1 traversal semantics); orphan FK
  rows are consequently excluded from such results. There is deliberately no FK short-circuit:
  comparing the owner's FK column directly (`"customer_id" = :id`) would newly include orphan-FK
  rows and diverge from traversal semantics — the join cost is the spec's cost.
- Path-depth asymmetry (known, documented): compile-side method-name resolution accepts at most
  ONE relation hop (multi-hop paths emit a NOTE and keep the legacy unqualified column). The
  runtime `columnRef` used by `Order.by`/`Sort` is now bounded the same way: it resolves the
  flattened `columns()` paths plus the generated `navigationRefs()` map — exactly the one-hop
  relation leaves whose values the `value()` switch answers for CURSOR keys (both surfaces come
  from one shared generation-time enumeration), so a sortable path always has a working cursor
  key and anything deeper fails fast with `Unknown property '<path>' on <Entity>`.
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
- `SqlBuilder` no longer imports `io.github.fludakit.data.metamodel`; the to-many-head decision moved
  to `EntitySchema.isToManyHead(String)`, which `DataModel` and generated `EntityDescriptor`s each
  answer for themselves.
- `EntitySchema` gained `isToManyHead(String)` (default `false`) and `QueryModel` is now the rendering
  input consumed by `SqlBuilder.build(EntitySchema, QueryModel)`.

## License

Apache License, Version 2.0. See [LICENSE](LICENSE).
