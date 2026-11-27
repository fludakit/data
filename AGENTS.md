# AGENTS.md

Guide for AI coding agents working on **FluDa Data** (`io.github.fludakit.data`): a compile-time
repository generator for Jakarta EE / CDI over JDBC. An annotation processor turns `@Repository`
interfaces and `@Entity` / `@AggregateRoot` / `@ValueObject` types into CDI beans with static SQL,
row mappers and a Jakarta Data static metamodel. There is no runtime reflection and no runtime
model discovery.

The project is **not released yet**. There is no backward compatibility to preserve.

## Build and test

- JDK 21+, Maven via the wrapper (`./mvnw`, or `mvnw.cmd` on Windows).
- Full build: `./mvnw clean install`
- After any processor or SQL change: `./mvnw -pl core,processor,integration-tests -am verify`
- One test: `./mvnw -pl processor -am test -Dtest=<TestClass> -Dsurefire.failIfNoSpecifiedTests=false`
- `tck-runner/` is git-ignored and outside the reactor; it runs the Jakarta Data TCK on demand.

## Modules

| Module | Role |
|---|---|
| `core` | Runtime and shared model: annotations, `metadata` (`TypeDefinition`, `JoinEdge`, `ColumnRef`), `query` (`QueryDefinition`, `Predicate`, `SortSpec`), `sql` (`SqlBuilder`, `QueryCompiler`, `SqlFragment`, dialects), `metamodel`, `page`. No compiler APIs. |
| `qbn` | ANTLR grammar (`Qbn.g4`) parsing query-by-method-name (`findByX`, `countByX`, ...) into `QueryDefinition`. Optional on the processor path. |
| `processor` | The annotation processor. Generates code with JavaPoet. |
| `integration-tests` | End-to-end tests over H2 in sample domains (`estore`, `profile`, `team`, `cascade`, ...). |

Dependencies point one way: `core` <- `qbn` <- `processor`.

## Processor pipeline

`RepositoryProcessor` creates every stage once in `init()`; `process()` only runs the phases:

1. `TypeDefinitionScanner` scans mapped types into the compilation-wide `TypeGraph`.
2. `RowMapperWriter` and `MetamodelWriter` write row mappers and `_Entity` metamodels from the graph.
3. `RepositoryScanner` resolves each repository into a `RepositoryContext` in the `RepositoryGraph`.
4. `RepositoryMemberEmitter` runs the method emitters (`FindEmitter`, `LifecycleEmitter`,
   `WriteEmitter`, `HydrationEmitter`), which register SQL constants and `MethodSpec`s on the context.
5. `RepositoryWriter` writes the `Repository_` class.

Both graphs outlive a processing round. Writers keep an "already written" set, and repository
contexts only move forward (`PLANNED` -> `EMITTED` -> `WRITTEN` / `FAILED`), so a later round never
regenerates a file.

Write cascades are planned by `CascadePlan.of(entity, WriteKind)`, which applies the aggregate
ownership rules, and rendered by `WriteEmitter`.

## Rules for changes

- **Generated output is the contract.** A refactor must keep generated SQL and Java byte-identical.
  The order of `ctx.registerConstant(...)` calls and of emitted statements is part of that output:
  constants are insertion-ordered and de-duplicated with `_2`, `_3` suffixes.
- **All SQL goes through `core/sql`** (`SqlBuilder` -> `QueryCompiler` / `SqlFragment` -> dialect).
  Emitters must not build SQL strings themselves.
- **Fail early, per method.** Report user-model mistakes with `diagnostics.error(message, element)`
  on the offending method or type, and skip only that member. Never let an exception abort a whole
  repository.
- **Exhaustive switches over sealed types.** Dispatch on `TypeDefinition.Prop` and `TypeDefinition`
  variants with pattern-matching `switch` listing every case, no `default`, plus `case null` where the
  value can be null (`properties()` can contain a null id). A new kind must fail to compile until
  every site handles it.
- **Keep helpers private.** A helper used by one class is a `private static final` nested class of
  that class. Only code other classes call is top level, and then package-private where possible.
- **Delete unused code.** Remove dead or `@Deprecated` members outright; do not keep them for
  compatibility.
- **Base decisions on the current code and tests.** The scope below is the intended v1 boundary;
  where it and the code disagree, check with the maintainer instead of guessing.

## Tests

- Processor tests compile sources in a temp dir through `ProcessorHarness` (`runTask`,
  `compileWithDiagnostics`, `sqlFlat`) and assert on generated sources and diagnostics.
- Pin SQL with `sqlFlat(...)` and assert one generated method body with `bodyOf(...)`.
- Rejected declarations belong in `ValidationDiagnosticsTest` and must assert the error and its location.
- Add or update tests with every behavioural change.

## Style

- Follow `.editorconfig`: 4-space indent, 120-column lines, **CRLF line endings**, no final newline.
  Some tools rewrite files to LF; restore CRLF before committing.
- No wildcard imports. Import order: project classes, then `java.**` / `javax.**` / `jakarta.**`,
  then static imports.
- Conventional Commits (`feat:`, `fix:`, `refactor:`, `docs:`, `chore:`, `test:`), one logical change
  per commit.

## Product scope (v1)

Implements a declared subset of **Jakarta Data 1.0.1** plus a provider-owned DDD entity model.
Keep spec-conformant behaviour, provider extensions and deferred features visibly distinct; never
emulate JDQL/JPQL or invent cascade rules. Classify every change as one of the three.

### Spec-conformant

| Area | Scope |
|---|---|
| CRUD | `CrudRepository` / `BasicRepository`: `findById`, `findAll`, `save`, `insert`, `update`, `deleteById`, plus `*All` variants |
| Query by method name | `findByX` / `countByX` / `existsByX` / `deleteByX` (deprecated in 1.0; prefer `@Find` / `@Query`) |
| Parameter queries | `@Find` / `@Delete` with `@By`, static `@OrderBy` |
| Pagination | Offset `Page` and cursor `CursoredPage` via `PageRequest` |
| Limit / sort | `Limit`; dynamic `Sort` / `Order` |
| Metamodel | Static `_Entity` metamodel and row mappers |

### Provider extensions

- `@Query` is **native SQL** with `:name` or positional `?1` parameters. It is never JDQL or JPQL.

### Parameter names

`@By` / `@Param` always need a `value()`. When the annotation is omitted, the Java parameter name is
used (needs `-parameters`): `String title` equals `@By("title")`. `_` maps to a path:
`address_city` is `address.city`. An explicit annotation always wins.

### Reject at compile time

- More than one `Limit` or `PageRequest` parameter.
- `Limit` and `PageRequest` on the same method.
- `Limit` or `PageRequest` together with the `First` keyword.
- `@Association` whose target is a `@ValueObject`, or combined with cascade settings.

### Out of scope

- JDQL and JPQL.
- Many-to-many join tables: model the join table as an entity with two to-one references.
- Runtime bytecode generation or runtime model discovery.

## DDD entity model

Annotations live in `io.github.fludakit.data.annotation`.

| Kind | Owns the lifecycle of | Does not manage |
|---|---|---|
| `@AggregateRoot` | The whole aggregate: composed to-one entities, to-many entity collections, element collections, embedded value objects | To-one references to other aggregates (foreign key only) |
| `@Entity` | Its own row, embedded value objects, element collections | To-many entity collections; to-one targets (foreign key only) |
| `@Association` | Nothing: foreign key only, never cascaded | Value-object targets; cascade settings |

- Prefer an id reference for to-one links outside the managed lifecycle. An explicit `@Association`
  on a to-one entity field means foreign-key update only.
- Queries (`@By`, `@OrderBy`, `Sort`) may navigate across ownership boundaries; that never widens
  what a write cascades to. `CascadePlan.of(...)` is the single place these rules are applied.
- `@ValueObject` types are immutable and embedded in the owner table, or in a child table for
  element collections. A Java `record` used that way counts as a value object without the annotation.
- `@Identity` marks the identifier. A null id on save or insert means the database generates it.
- No `@Embedded` / `@ElementCollection` markers: classification comes from type shape and ownership.
- `@Version` (optimistic locking) is planned, not implemented.