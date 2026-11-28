# Data

Part of the [FluDa](https://fludakit.github.io/) toolkit — [module source](https://github.com/fludakit/data).

FluDa Data is an implementation of [Jakarta Data](https://jakarta.ee/specifications/data/) for Jakarta EE and CDI applications. You declare a `@Repository` interface, and an annotation processor generates the implementation at **compile time** — no runtime proxies, no classpath scanning, no reflection to read your entities. The generated code renders SQL and executes it through the FluDa [JDBC Client](https://fludakit.github.io/jdbc-client/).

The project is organized into three published modules:

| Module      | Artifact                 | Description                                                                                                                                       |
|-------------|--------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------|
| `core`      | `fluda-data-core`        | The runtime API: entity-model annotations (`@Entity`, `@Identity`, `@AggregateRoot`, `@ValueObject`, `@Association`), the generated-metadata contracts (`EntityMetadata`, `AttributePathResolver`), the SQL renderer (`SqlBuilder`) and its dialects, and paging/cursor support. |
| `qbn`       | `fluda-data-qbn`         | The optional **query-by-method-name** parser. Add it to the annotation processor path for repositories that derive queries from method names (`findBy…`, `countBy…`, `deleteBy…`). |
| `processor` | `fluda-data-processor`   | The annotation processor. It reads each `@Repository` interface and emits the CDI implementation, entity metadata, reflection-free row mappers and the static metamodel. |

## Key concepts

- **`@Repository`** — the Jakarta Data stereotype. A repository interface extends `DataRepository`, `BasicRepository` or `CrudRepository` with a concrete entity type, and the processor generates a matching `…Impl` CDI bean.
- **Compile-time generation** — for every repository the processor emits the implementation plus a reflection-free `EntityMetadata`, a row mapper and the `_Entity` static metamodel. Nothing is built or proxied at runtime.
- **JdbcClient execution** — generated SQL is rendered by a dialect seam (identifier quoting and pagination) and run through `JdbcClient`. The shipped dialects are **H2** (the default) and **PostgreSQL**, selected with the `-Afluda.dialect` annotation-processing option.
- **Lifecycle annotations** — `@Insert`, `@Update`, `@Save` and `@Delete` map a method signature straight to a DML statement. `save`/`saveAll` are a true **upsert**, as Jakarta Data defines them.
- **Finders** — three ways to express a query: built-in `CrudRepository` methods, the Jakarta Data annotations (`@Find` with `@By`/`@OrderBy`, or `@Query` for native SQL), and query-by-method-name (`findByStatus`, …) via the optional `qbn` module.

!!! note "Jakarta Data Query Language (JDQL) is not implemented"
    Methods annotated with `@Query` take **native SQL** supplied by your application. That SQL is not parsed or rewritten by the dialect layer, so it may be database-specific. JDQL string translation is out of scope.

## Reading order

- Start with [getting started](getting-started.md) to add the processor, map an entity, declare a repository and use the lifecycle annotations, built-in methods and query-by-method-name.
- Explore [Applying DDD](ddd.md) for aggregate roots and value objects, [Optimistic Locking](version.md) for the versioning roadmap, and [Domain Events](domain-events.md).


---

[FluDa Toolkit — browse all modules](https://fludakit.github.io/){ .md-button .md-button--primary }
