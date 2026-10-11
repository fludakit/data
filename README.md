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

## Query-by-method-name conformance

`And`/`Or` are honoured: the connector between conditions is now part of the query model
(`Connector` on `Predicate`). Previously `findByNameOrEmail` silently rendered `AND`. Jakarta Data
evaluates `And` before `Or`, which is also SQL precedence, so the declared order renders as written —
`findByAAndBOrC` emits `a AND b OR c`, i.e. `(a AND b) OR c`. When an `OR`-bearing expression is
followed by a keyset cursor conjunct, the user expression is parenthesised so the cursor's `AND` does
not bind to the last `OR` term. Predicates also render in declaration order whenever any connector is
`OR` (the all-`AND` path keeps its existing ordering).

`Not` composes with every operator the spec negates: `NotLike`, `NotIn`, `NotBetween`, `NotContains`,
`NotStartsWith`, `NotEndsWith`, `NotNull`, and bare `Not` (equality). Previously everything except
`Like`/`Null` collapsed to `<>`, so `findByNameNotIn` silently rendered `name <> :name`. A `Not`
applied to an operator the spec does not negate (the relational ones) is now a parse failure rather
than a silent degrade. The negated string operators wrap their argument in `%` exactly as their
positive counterparts do.

The subject clause between the action and `By` now accepts only the keywords the spec reserves —
`First<n>` and `Distinct`. It used to be an unvalidated sink that absorbed arbitrary text, so
`findTop5ByName` or `findAllByStatus` parsed and silently behaved as if the extra word were absent.
Non-spec subject text is now a compile error. Built-in method names are matched before the query-by-
method-name rules, because Jakarta Data's own `CrudRepository.findAll` carries `@Find`.

The reserved `Distinct` subject keyword is honoured: a `Distinct` find renders `SELECT DISTINCT` over
the root projection, and a `Distinct` count renders `COUNT(DISTINCT <id>)` (the row identity column —
`COUNT(DISTINCT *)` is not valid SQL). The published query metadata reports it through
`QueryDefinition.distinct()`, which is a default method so existing implementors are unaffected.

A property written in a method name must resolve on the entity. It used to fall back silently to the
snake-cased column, so a typo or an unparseable keyword suffix produced SQL against a column that does
not exist — `findByNameIsNull` (not a Jakarta Data keyword) lexed as property `nameIs` and generated
`WHERE "name_is" IS NULL`. Such a property is now a compile error. Properties taken from `@By` or from
parameter names stay lenient, as do the documented multi-hop relation NOTE paths.

## Jakarta Data TCK

`tck-runner` runs the **official Jakarta Data TCK** against this implementation. It is opt-in so the
default build stays green and never downloads the TCK:

```
./mvnw -Drundatatck install      # builds tck-runner and runs the TCK suites
./mvnw -Drundatatck -pl tck-runner test
```

It follows Hibernate ORM's `hibernate-tck-data-runner`: the TCK's **repository interface sources** are
unpacked and compiled as test sources so this annotation processor can generate the implementations the
TCK injects (`<dependenciesToScan>` is deliberately *not* used — the TCK jar's own classes were compiled
without the processor, so they hold no implementation to inject). Everything else — the test classes,
the framework annotations and extensions — comes from the TCK jar.

**Arquillian is not part of the harness.** The runner uses this project's own test stack: standalone SE
mode (`jakarta.tck.skip.deployment=true`), Weld via `weld-junit5`, JUnit 5 and H2. Arquillian and
ShrinkWrap Resolver are excluded from the TCK dependency entirely. `shrinkwrap-api` remains as a single
test-scope dependency for one unavoidable reason: the TCK's test classes declare
`public static WebArchive createDeployment()`, and reflecting over a class to find its test methods
resolves that method's return type, so the type must exist even though nothing ever calls it.
`StandaloneExtension` is shadowed with a marker `Extension`, since upstream it is only an Arquillian
bridge that no-ops when deployment is skipped.

The first suite is `standalone/nosql/example/NoSQLEntityTests`. The TCK's domain classes carry
`jakarta.persistence`/`jakarta.nosql` annotations that this processor does not read, so `tck-runner`
re-declares them with FluDa's annotations at the same fully-qualified names and omits the originals
from the extraction whitelist — the TCK's own tests and repositories then compile against them
unchanged.

Out of scope and therefore excluded rather than silently skipped: JDQL `@Query` translation, `Slice`,
`@Version` optimistic locking, and transactions. `TckChallengeExclusions` (a JUnit `ExecutionCondition`,
registered through `META-INF/services` + `junit.jupiter.extensions.autodetection.enabled`) disables the
JDQL-dependent suites and reports them as *disabled with a reason* — at TCK 1.0.1 those are
`standalone/entity/EntityTests` and `standalone/persistence/PersistenceEntityTests`, whose repositories
are the JDQL ones. Widen by adding a suite's files to the unpack whitelist and a `*TckTests` wrapper.

## Repository method classification

A repository method falls into one of two categories, and the split is exactly whether the method
**name** carries the query.

**Annotation-driven — the name is never analyzed.**

- `@Insert`/`@Update`/`@Save`/`@Delete` decide on the method's *signature* alone, so a lifecycle method
  may be named freely (`@Insert void store(Note)`) and no name-based rule may claim it.
- The spec's built-in repository methods are matched by name, because the base interfaces carry the
  annotations in the API itself. These are checked before the query annotations: `CrudRepository.findAll`
  is itself annotated with `@Find`.
- `@Find`/`@Query` on the application's own methods. The criteria come from `@By`, or from the parameter
  names when `@By` is absent, always with the implied equality operator. The method name contributes
  nothing — `@Find List<C> findByEmailLike(@By("email") String p)` binds `WHERE "email" = :p`, and a
  relation traversal must be declared (`@By("customer.name")`) rather than spelled in the name.

**Name-driven — query-by-method-name.** An unannotated method whose name is the query, analyzed in full
for predicates, connectors, operators, `OrderBy`, `First<n>` and `Distinct`. This is the opposite of the
annotation-driven category, so an annotated method is never routed here.

`save`/`saveAll` are now a true **upsert**, as Jakarta Data defines them. Any entity with a non-null id
used to be routed straight to an UPDATE, so saving an entity carrying an application-assigned
identifier into an empty table was a silent no-op. The update now runs first and, when it matches no
row, the entity is inserted *with* its id. Found by the Jakarta Data TCK and pinned by
`SaveUpsertTest`.

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
- `AttributePathResolver.columnRefs()` owns the ordered path-to-placement index, keyed by the
  logical path string. `ColumnRef` contains only physical column/table/join placement and never an
  attribute name. `TableSchema.columns()` remains scoped to physical columns in one table.
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
  consumes both views through the `SqlBuilder(TableSchema, AttributePathResolver, QueryDefinition)`
  constructor — `.select()`, `.count()` and `.delete()` render the whole statement.
- **Cursor SQL is compile-time.** `io.github.fludakit.data.page.CursorSupport` is gone. The keyset
  seek predicate is rendered by `SqlBuilder.seekClause(terms, ascending, forward)` and baked into one
  `SQL_<X>_SEEK_NEXT` / `SQL_<X>_SEEK_PREV` constant per paged read, so no cursor SQL is assembled
  at runtime and the terms are quoted by the dialect instead of `Names.quote`. The only cursor helper
  generated code still calls is `io.github.fludakit.data.sql.CursorKeys.forEntity(properties,
  ascending, metadata, entity)`, which builds the key from a materialized row; cursor values bind as
  explicit `.param("cursor0", cursor.get(0))` calls.
- Composite cursor keys now compare their PRECEDING keys with NULL-safe `=`, not with the direction
  operator. Previously `(k0 > :c0) OR (k0 > :c0 AND k1 > :c1)` collapsed to `k0 > :c0`, making every
  row sharing the leading key unreachable from page two onward. A cursor must now carry one value per
  sort key, including the appended root-id tiebreak.
- `fluda-data-qbn` is an **optional** annotation processor dependency. A repository using
  `findBy…`/`countBy…`/`deleteBy…` names must add it to the annotation processor path
  (`annotationProcessorPaths`); without it the processor reports a compile error naming the
  dependency instead of generating a predicate-less query.

## Repository method validation

Every abstract repository method is validated before an implementation is generated:

| Method | Rule |
|---|---|
| Lifecycle-annotated (`@Insert`/`@Update`/`@Save`) | must accept a single `E`, `List<E>`, or `E[]` |
| `@Delete` | entity/sequence parameter, or a parameter-derived delete predicate (named freely) |
| Built-in (`save`, `findById`, `findAll`, …) | the parameter shape must be one the processor implements |
| `@Find` / `@Query` | accepted as declared; a query-by-method-shaped name is still parsed |
| Anything else | must resolve as a query-by-method name, or carry `@Query` |

Return types are honoured where the processor has a shape: `List`/`Collection`/`Iterable` materialize
with `.list()`, `Set` into a `LinkedHashSet`, `Stream` with `.stream()`, `Optional`/single entity with
`.optional()`/`.single()`, and `Page`/`CursoredPage` through the paged emitters. Declaring
`java.util.Set` used to emit `.list()` and fail to compile; returning `Slice` is rejected with a
compile error rather than silently generating a mismatched terminal.

## License

Apache License, Version 2.0. See [LICENSE](LICENSE).
