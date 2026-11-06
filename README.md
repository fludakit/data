# FluDa Data

[![Build](https://github.com/fludakit/data/actions/workflows/build.yml/badge.svg)](https://github.com/fludakit/data/actions/workflows/build.yml)

A fluent data-access toolkit for Jakarta EE / CDI applications, providing compile-time repository generation over JDBC. It implements a subset of Jakarta Data 1.0 (`jakarta.data:jakarta.data-api:1.0.1`) and embraces Domain-Driven Design modeling concepts through its own entity model annotations.

## Modules

- `fluda-data-core`: the core API, the DDD entity-model annotations, the query/metadata plan types, and SQL rendering.
- `fluda-data-qbn`: optional query-by-method-name parser for derived finders.
- `fluda-data-processor`: the annotation processor that resolves repositories and generates their implementations.
- `fluda-data-integration-tests`: end-to-end tests over a real database (H2).

## Jakarta Data scope (v1)

The target is Jakarta Data 1.0.1. Section numbers refer to the 1.0 FINAL specification document.

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

Methods that combine special parameters illegally per spec 4.6 — more than one `Limit` or `PageRequest`, `Limit` together with `PageRequest`, or `Limit`/`PageRequest` with the `First` keyword — are rejected at compile time.

### Associations

Associations are supported as to-one references, element collections, and aggregate-owned entity collections. Many-to-many relations stored in a join table are **not** supported and are not planned; model the join table as an entity with two to-one references instead.

## DDD entity model

FluDa Data provides its own DDD modeling annotations (the Jakarta Data spec allows a provider-defined entity programming model):

- `@Entity` — an object defined by its identity rather than its attributes.
- `@AggregateRoot` — the single entry point for modifying the objects it owns.
- `@ValueObject` — an immutable object with no identity of its own, embedded into its parent table.
- `@Identity` — marks the field holding an entity's identifier.
- `@Association` — a reference to an entity outside the aggregate, persisted as a foreign key only and never cascaded.

Repositories are marked with Jakarta Data's `@Repository` and implemented by the processor.

## Usage

Define a repository interface and let the processor generate the implementation:

```java
import io.github.fludakit.data.annotation.Entity;
import io.github.fludakit.data.annotation.Identity;
import jakarta.data.repository.CrudRepository;
import jakarta.data.repository.Repository;

@Entity
public class Customer {
    @Identity Long id;
    String name;
    String email;
    // getters and setters
}

@Repository
public interface CustomerRepository extends CrudRepository<Customer, Long> {
    List<Customer> findByName(String name);
    long countByEmail(String email);
}
```

The processor generates a CDI bean `CustomerRepository_` with static SQL constants, a code-generated row mapper, and a Jakarta Data static metamodel. Add `fluda-data-qbn` to the annotation processor path to enable query-by-method-name finders like `findByName` and `countByEmail`.

For annotations, repository patterns, query-by-method-name conformance, relation traversal, paging, and aggregate composition, see the [Data documentation](https://fludakit.github.io/documentation/data/getting-started/).

## Architecture

The processor follows a resolve-then-emit pipeline:

1. **Resolve** — `fluda-data-processor` reads the repository interface and the entity model into immutable plan value types in `fluda-data-core` (`TypeDefinition`, `QueryDefinition`, `MutationPlan`).
2. **Plan** — methods are reified into structured plans rather than re-derived SQL per emitter.
3. **Emit** — emitters render the generated CDI repository bean from those plans.

SQL is rendered once in `fluda-data-core` (`SqlBuilder`/`QueryCompiler`) and dialects are pluggable.

## Roadmap

The processor code is being refactored toward the pipeline above, tracked in GitHub issues:

- Split `RepositoryVisitor` into entity/type resolution, a single interface-hierarchy walker, a built-in operation registry, and a method plan builder.
- Make `TypeDefinition` polymorphic (no `instanceof` dispatch): a polymorphic `id()` and a `PropVisitor`.
- Route all SQL — finder, write, and hydration — through `SqlBuilder`/`QueryCompiler`, with a single `SqlFragment` renderer.
- Enforce the DDD annotation contracts at compile time (`@ValueObject` immutability, `@Association` rules) and add `@Identity` generation metadata.
- Pluggable dialect discovery (`ServiceLoader`) and polymorphic emitter dispatch.

## Building

```bash
./mvnw clean install
```

## Documentation

See the [reference documentation site](https://fludakit.github.io/documentation/data/getting-started/) for getting started, repository patterns, query-by-method-name, and full API reference.

## Contributing

Contributions are welcome — issues, pull requests, and feature suggestions are all encouraged. See [CONTRIBUTING.md](CONTRIBUTING.md) for how to build the project and submit changes.
