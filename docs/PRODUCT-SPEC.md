# FluDa Data — Product Scope (Agent Spec)

Source decisions: [#29](https://github.com/fludakit/data/issues/29) (Jakarta Data v1 subset), [#30](https://github.com/fludakit/data/issues/30) (DDD annotation contracts).

This document is the agent-facing product boundary for FluDa Data. Prefer it over inferred scope from code when deciding what to implement, reject, or defer.

## Purpose

FluDa Data is a compile-time repository generator for Jakarta EE / CDI over JDBC. It implements a **declared subset** of Jakarta Data 1.0.1 and a **provider-owned DDD entity model**. Spec-conformant behavior, non-portable extensions, and deferred features must stay visibly distinct — never silently emulate JDQL/JPQL or invent unspecified cascade rules.

## Goals

1. Generate typed CDI repository beans at compile time (static SQL, row mappers, static metamodel) with no runtime entity reflection.
2. Ship a coherent Jakarta Data v1 subset that application code can rely on.
3. Enforce DDD ownership contracts (`@AggregateRoot` / `@Entity` / `@ValueObject` / `@Association` / `@Identity`) at compile time.
4. Fail early with located diagnostics; never abort a whole repository for one unresolvable method via an escaped `IllegalStateException`.

## Non-goals (v1)

- JDQL and JPQL (including any `"id(this)"`-style placeholder presented as JDQL).
- Many-to-many join tables (model a join entity with two to-one references instead).
- Runtime bytecode generation or runtime model discovery.

---

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

Document these as extensions (never reinterpret as JDQL/JPQL):

- `@Query` **native SQL** with `:name` parameters (omitted `@Param` → Java parameter name under `-parameters`)
- Positional `?1` in native SQL
- Any other vendor-specific `@Query` behavior

### Parameter names (`-parameters`)

`@By` / `@Param` both require a `value()` — there is no optional/default attribute. When the annotation is omitted and `-parameters` is enabled, FluDa uses the Java parameter name (`String title` ≡ `@By("title") String title`; same for `@Param` / `:title`). Compound attributes use `_` in the parameter name (`address_city` → `address.city`). An explicit `@By("...")` / `@Param("...")` always wins. FluDa enables `-parameters` in its integration-test compiler config and in the processor test harness.

### Deferred

- **JDQL** — reject; remove `"id(this)"` string substitution rather than shipping it as JDQL
- **JPQL** — deferred; native SQL is the only accepted `@Query` language

### Must reject at compile time (spec 4.6)

- More than one `Limit` or `PageRequest` parameter
- `Limit` and `PageRequest` on the same method
- `Limit` or `PageRequest` combined with the `First` keyword

### Fail-early method resolution

An unresolvable repository method MUST use `diagnostics.error(...)` on the method element and skip that method. It MUST NOT throw `IllegalStateException` and abort the whole repository.

---

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
- Product concern for find/save mapping: **assigned** vs **DB-generated** identity — express that on `@Identity` (or equivalent declared metadata). Do not invent a parallel processor-only strategy from Java type guessing.
- Do not keep unused generation enums on the metadata model.

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

---

## Agent checklist

When changing FluDa Data:

1. Classify the change as **spec-conformant**, **provider extension**, or **deferred/out of scope**.
2. If adding repository method support, match the v1 matrix above; reject illegal `Limit`/`PageRequest`/`First` combos.
3. If touching persistence of associations or collections, apply the lifecycle ownership table — do not cascade outside it.
4. Prefer located `diagnostics.error` / warnings over thrown exceptions on user model mistakes.
5. Keep README’s supported-vs-deferred matrix aligned with this document when scope changes.

## Related docs

- [Constitution](https://github.com/fludakit/data/blob/main/.specify/memory/constitution.md) — engineering principles (compile-time generation, TDD, single SQL source).
- [README](https://github.com/fludakit/data/blob/main/README.md) — usage entry point.
- Issues [#29](https://github.com/fludakit/data/issues/29), [#30](https://github.com/fludakit/data/issues/30).
