# FluDa Data — Technical Specification

This document describes the architecture of the FluDa Data annotation processor and the refactoring roadmap that moves the implementation toward it.

## Architecture

The processor follows a resolve-then-emit pipeline:

1. **Resolve** — `fluda-data-processor` reads the repository interface and the entity model into immutable plan value types in `fluda-data-core` (`TypeDefinition`, `QueryDefinition`, `MutationPlan`).
2. **Plan** — methods are reified into structured plans rather than re-derived SQL per emitter.
3. **Emit** — emitters render the generated CDI repository bean from those plans.

SQL is rendered once in `fluda-data-core` (`SqlBuilder`/`QueryCompiler`) and dialects are pluggable.

### Module responsibilities

- `fluda-data-core` — core API, DDD entity-model annotations, query/metadata plan types, and SQL rendering.
- `fluda-data-qbn` — optional query-by-method-name parser for derived finders.
- `fluda-data-processor` — the annotation processor that resolves repositories and generates their implementations.
- `fluda-data-integration-tests` — end-to-end tests over a real database (H2).

## Roadmap

The processor code is being refactored toward the pipeline above, tracked in GitHub issues:

- Split `RepositoryVisitor` into entity/type resolution, a single interface-hierarchy walker, a built-in operation registry, and a method plan builder.
- Make `TypeDefinition` polymorphic (no `instanceof` dispatch): a polymorphic `id()` and a `PropVisitor`.
- Route all SQL — finder, write, and hydration — through `SqlBuilder`/`QueryCompiler`, with a single `SqlFragment` renderer.
- Enforce the DDD annotation contracts at compile time (`@ValueObject` immutability, `@Association` rules) and add `@Identity` generation metadata.
- Pluggable dialect discovery (`ServiceLoader`) and polymorphic emitter dispatch.
