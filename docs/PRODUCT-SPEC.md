# FluDa Data — Product Specification (v1)

This document defines the v1 product surface of FluDa Data: which parts of Jakarta Data are implemented, which are provided as non-portable extensions, and which are deferred, together with the DDD entity modeling program the processor understands.

## Target

FluDa Data implements a subset of Jakarta Data 1.0 (`jakarta.data:jakarta.data-api:1.0.1`). Section numbers below refer to the Jakarta Data 1.0 FINAL specification document.

## Jakarta Data scope

| Area | v1 status |
| --- | --- |
| Built-in repositories — `CrudRepository`/`BasicRepository` CRUD (`findById`, `findAll`, `save`, `insert`, `update`, `deleteById` plus the `All` variants) | Supported |
| Query by Method Name (`findByX`/`deleteByX`) | Supported — required in 1.0 but deprecated; `@Find`/`@Query` are preferred |
| Parameter-based automatic query methods (`@Find`/`@Delete`) with `@By` and static `@OrderBy` | Supported |
| Pagination — offset `Page` and keyset `CursoredPage` via `PageRequest` (spec 4.8) | Supported |
| `Limit` as a standalone parameter (spec 4.6) | Supported |
| Dynamic `Sort`/`Order` parameters (spec 4.6/4.7) | Supported |
| Static metamodel (`_Entity`) for type-safe attribute access (spec 3.3) | Supported |
| `@Query` native SQL with `:name` or positional `?1` parameters, plus vendor extensions | Supported as **non-portable provider extensions**, not spec conformance |
| JDQL and JPQL | **Deferred in v1** — rejected with a compile-time diagnostic; `@Query` text is bound as native SQL, never parsed as JDQL/JPQL |

### Compile-time rejections (spec 4.6)

Methods that combine special parameters illegally per spec 4.6 are rejected at compile time:

- more than one `Limit` or `PageRequest` parameter,
- a `Limit` parameter together with a `PageRequest` parameter,
- a `Limit` or `PageRequest` parameter combined with the `First` keyword.

### Associations

Associations are supported as to-one references, element collections, and aggregate-owned entity collections. Many-to-many relations stored in a join table are **not** supported and are not planned; model the join table as an entity with two to-one references instead.

## DDD entity model

The Jakarta Data spec allows a provider-defined entity programming model. FluDa Data provides its own DDD modeling annotations:

- `@Entity` — an object defined by its identity rather than its attributes.
- `@AggregateRoot` — the single entry point for modifying the objects it owns.
- `@ValueObject` — an immutable object with no identity of its own, embedded into its parent table.
- `@Identity` — marks the field holding an entity's identifier.
- `@Association` — a reference to an entity outside the aggregate, persisted as a foreign key only and never cascaded.

Repositories are marked with Jakarta Data's `@Repository` and implemented by the processor.
