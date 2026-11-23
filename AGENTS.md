# FluDa Data — Agent Guide

FluDa Data is a compile-time repository generator for Jakarta EE / CDI over JDBC: typed CDI repository beans, static SQL, row mappers, and static metamodel generated at compile time, with no runtime entity reflection. It implements a **declared subset** of Jakarta Data 1.0.1 and a **provider-owned DDD entity model**.

This file is the product boundary. Prefer it over scope inferred from code when deciding what to implement, reject, or defer. Source decisions: [#29](https://github.com/fludakit/data/issues/29) (Jakarta Data v1 subset), [#30](https://github.com/fludakit/data/issues/30) (DDD annotation contracts).

## When changing FluDa Data

1. Classify the change as **spec-conformant**, **provider extension**, or **deferred/out of scope**, and keep the three visibly distinct in code and docs.
2. Match new repository method support to the v1 matrix below; reject illegal `Limit`/`PageRequest`/`First` combos at compile time.
3. Apply the lifecycle ownership table to association/collection persistence; cascade only within it.
4. Report user model mistakes as located `diagnostics.error(...)` / warnings on the offending element, then skip that method — an escaped `IllegalStateException` aborts the whole repository.
5. Keep README's supported-vs-deferred matrix aligned with this file when scope changes.

## Jakarta Data v1 subset (#29)

Target API: `jakarta.data:jakarta.data-api:1.0.1` (1.0 FINAL section numbers).

### Spec-conformant (in scope)

| Area | Scope |
|---|---|
| CRUD | `CrudRepository` / `BasicRepository`: `findById`, `findAll`, `save`, `insert`, `update`, `deleteById`, plus `*All` variants |
| Query by method name | `findByX` / `deleteByX` — required in 1.0 but deprecated; document as such; prefer `@Find` / `@Query` going forward |
| Parameter queries | `@Find` / `@Delete` with `@By` (or omitted `@By` → parameter name under `-parameters`), static `@OrderBy` |
| Pagination | Offset `Page` and cursor `CursoredPage` via `PageRequest` (spec 4.8) |
| Limit / sort | Standalone `Limit` (4.6); dynamic `Sort` / `Order` (4.6 / 4.7) |
| Metamodel | Static `_Entity` metamodel and row-mapper generation (3.3) |

### Non-portable provider extensions (in scope, not conformance)

Document these as extensions; native SQL is the only accepted `@Query` language:

- `@Query` **native SQL** with `:name` parameters (omitted `@Param` → Java parameter name under `-parameters`)
- Positional `?1` in native SQL
- Any other vendor-specific `@Query` behavior

### Parameter names (`-parameters`)

`@By` / `@Param` both require a `value()` — there is no optional/default attribute. When the annotation is omitted and `-parameters` is enabled, FluDa uses the Java parameter name (`String title` ≡ `@By("title") String title`; same for `@Param` / `:title`). Compound attributes use `_` in the parameter name (`address_city` → `address.city`). An explicit `@By("...")` / `@Param("...")` always wins. FluDa enables `-parameters` in its integration-test compiler config and in the processor test harness.

### Out of scope (v1)

- **JDQL** — reject; remove `"id(this)"` string substitution rather than shipping it as JDQL.
- **JPQL** — deferred.
- Many-to-many join tables — model a join entity with two to-one references instead.
- Runtime bytecode generation or runtime model discovery.

### Must reject at compile time (spec 4.6)

- More than one `Limit` or `PageRequest` parameter
- `Limit` and `PageRequest` on the same method
- `Limit` or `PageRequest` combined with the `First` keyword

## DDD entity model (#30)

Annotations live in `io.github.fludakit.data.annotation`. Javadoc contracts are product rules — enforce them at compile time, or explicitly downgrade with a documented reason.

### Lifecycle ownership

| Kind | Owns lifecycle of | Does not manage |
|---|---|---|
| **`@AggregateRoot`** | Whole aggregate: to-one entities inside the aggregate, to-many entity collections, element collections, embedded VOs; domain events | To-one references that are lightweight associations (FK update only) |
| **`@Entity`** | Own row; embedded VOs; element collections | To-many entity collections; to-one target entities (with or without `@Association`) — FK update only; domain events |
| **`@Association`** | Nothing — foreign key only, never cascaded, lazy | Value-object targets; any cascade settings |

Rules of thumb:

- Prefer an **id presentation** for to-one associations outside managed lifecycle.
- Explicit `@Association` on a to-one entity field skips lifecycle management (FK only).
- Navigation beyond the ownership boundary is allowed in **queries** (`@By` / `@OrderBy` / `Sort` multi-hop paths); that does not expand write/cascade ownership.

### Structural model

- `@AggregateRoot` and `@Entity` serve different ownership purposes but share structural shape: model an aggregate root as an entity (meta-annotate or delegate) so property assembly is not duplicated.
- Scanner may still distinguish entity vs aggregate for registration; property traversal should be one visitor/graph walk into `TypeGraph`. Back-reference validity belongs in query resolution, not scan.

### `@Identity`

- Marks the identifier field.
- Product concern for find/save mapping: **assigned** vs **DB-generated** identity — express that on `@Identity` (or equivalent declared metadata), never as a processor-only strategy guessed from Java types.
- Keep the metadata model free of unused generation enums.

### `@ValueObject`

- Immutable, embedded into the owner table (or element-collection child table).
- Enforce: warn/error on non-final fields or public setters.
- A Java `record` used as embedded / element-collection element may be treated as a value object without `@ValueObject` (see related #4).

### `@Association` enforcement

- Reject `@Association` whose target is a `@ValueObject`.
- Reject `@Association` combined with cascade settings.

### `@Version`

- Planned separately (#1): implicit field name `version`, or `@Version`; `long` or timestamp-compatible types.

### Markers not required

- No mandatory `@Embedded` / `@ElementCollection` annotations — classification comes from type shape and ownership.

## Related docs

- [README](README.md) — usage entry point.
- Issues [#29](https://github.com/fludakit/data/issues/29), [#30](https://github.com/fludakit/data/issues/30).