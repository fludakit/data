<!--
  Sync Impact Report (temporary scratch material — remove before committing)
  ==========================================================================
  Version change: (none) -> 1.0.0  (initial constitution authored from the
                  placeholder template; no prior project values existed)
  Bump rationale: first substantive content — all [PLACEHOLDER] tokens replaced.
  Modified principles (placeholders -> concrete):
    - [PRINCIPLE_1_NAME] -> I. Compile-Time Repository Generation
    - [PRINCIPLE_2_NAME] -> II. Domain-Driven Design Entity Model
    - [PRINCIPLE_3_NAME] -> III. Jakarta Data Subset Conformance & Explicit Scope
    - [PRINCIPLE_4_NAME] -> IV. Test-First (NON-NEGOTIABLE)
    - [PRINCIPLE_5_NAME] -> V. Resolve-Plan-Emit Pipeline & Single Source of SQL
  Added sections:
    - Technical Constraints (was [SECTION_2_NAME])
    - Development Workflow (was [SECTION_3_NAME])
  Removed sections: none
  Deferred TODOs: none
-->

# FluDa Data Constitution

## Core Principles

### I. Compile-Time Repository Generation

FluDa Data generates repository implementations at compile time via the
`fluda-data-processor` annotation processor. It MUST NOT generate code or inspect
entities at runtime through reflection or bytecode manipulation. Every
`@Repository` interface is reified into typed, immutable plan value types
(`TypeDefinition`, `QueryDefinition`, `MutationPlan`) before emission, and the
generated CDI bean ships static SQL constants and a code-generated row mapper.

Rationale: Compile-time generation produces startup-free, type-safe, static code
that fails fast at build time rather than silently misbehaving at runtime.

### II. Domain-Driven Design Entity Model

The entity programming model is defined by the project's own DDD annotations,
not by arbitrary conventions. `@AggregateRoot` is the aggregate boundary: it
owns the full lifecycle of its to-one and to-many children (cascade save,
update, and delete) and is the only type that manages domain events. `@Entity`
owns only its own row plus its embedded value objects and element collections —
it MUST NOT manage a to-many entity collection's or a to-one target's lifecycle;
those are foreign-key-only. `@ValueObject` is immutable and embedded into its
parent table; `@Identity` marks the identifier field; `@Association` is a
foreign-key-only reference to an entity outside the aggregate and MUST never
cascade. These contracts (immutability, association rules, lifecycle ownership)
are enforced at compile time, not left to runtime.

Rationale: The aggregate is the consistency and lifecycle boundary; an entity or
association that cascades outside it would corrupt the ownership invariant.

### III. Jakarta Data Subset Conformance & Explicit Scope

FluDa Data implements the documented v1 subset of Jakarta Data 1.0. Behavior that
is spec conformance, a non-portable provider extension, or deferred MUST be
explicitly distinguished and MUST NOT be silently emulated: `@Query` text binds as
native SQL (a non-portable extension, not spec conformance), JDQL/JPQL are deferred
and rejected with a compile-time diagnostic, and illegal special-parameter
combinations (spec 4.6) are rejected at compile time. Many-to-many relations stored
in a join table are out of scope and MUST be modeled as an entity with two to-one
references.

Rationale: Conformance boundaries must be visible at compile time so users are
never surprised by a provider-specific interpretation masquerading as spec behavior.

### IV. Test-First (NON-NEGOTIABLE)

Test-Driven Development is mandatory. Tests are written first, run to failure, then
implementation follows the Red-Green-Refactor cycle. Unit tests cover the processor
and the core plan/SQL rendering; `fluda-data-integration-tests` exercises repository
contracts, associations, cascades, and mutation plans end-to-end against a real
database (H2).

Rationale: The processor's correctness is a property of the code it generates; only
a real-database integration suite can verify the full resolve-plan-emit pipeline.

### V. Resolve-Plan-Emit Pipeline & Single Source of SQL

The processor MUST follow a resolve-then-emit pipeline: Resolve (read the repository
interface and entity model into plans) → Plan (reify methods into structured plans)
→ Emit (render the generated CDI bean from those plans). SQL MUST be rendered exactly
once in `fluda-data-core` (`SqlBuilder`/`QueryCompiler`), dialects MUST be pluggable,
and emitters MUST NOT re-derive SQL per emitter. Dispatch MUST be polymorphic (no
`instanceof` dispatch): `TypeDefinition` exposes a polymorphic `id()` and `asEntity()`,
a sealed `IdentityAware` shape, and `Prop.Visitor` / `TypeDefinition.Visitor` visitors.

Rationale: A single SQL source and polymorphic dispatch keep generated output
consistent across emitters and dialects, preventing drift and duplicated logic.

## Technical Constraints

- **Language & runtime:** Java 21 or later; JDK 25 is required to publish a release
  to Maven Central.
- **Build:** Maven 3.9+ via the included `./mvnw` wrapper.
- **Modules:** `core` (`fluda-data-core`), `qbn` (`fluda-data-qbn`), `processor`
  (`fluda-data-processor`), and `integration-tests` (`fluda-data-integration-tests`),
  plus `tck-runner`.
- **License:** Apache License, Version 2.0.
- **Code style:** governed by the committed `.editorconfig` (IntelliJ IDEA) — 4-space
  indentation, 120-column maximum line width, CRLF line endings, no wildcard imports,
  and import layout of project classes → `java.**`/`javax.**`/`jakarta.**` → static
  imports. Reformat before committing to keep formatting out of functional diffs.
- **No runtime reflection or bytecode generation:** all generation happens at compile
  time (see Principle I).
- **Module separation:** `core` (`fluda-data-core`) is the developer-facing API and the
  single SQL renderer; it MUST NOT depend on `javax.lang.model`. Annotation processing
  (the `javax.lang.model` API) belongs in `processor` (`fluda-data-processor`), whose
  `RepositoryProcessor` is the single orchestration entry point.

## Development Workflow

- Follow Conventional Commits: `feat:`, `fix:`, `docs:`, `chore:`, `ci:`, `refactor:`.
- Keep each pull request to a single logical change and reference any related issue.
- Add or update tests and documentation alongside every change.
- Build with `./mvnw clean install`; run unit tests with `./mvnw test`.
- Before adding join-table (many-to-many) support, open an issue to discuss it.

## Governance

This constitution supersedes all other ad-hoc development practices for the FluDa
Data project. All pull requests and reviews MUST verify compliance with these
principles; any complexity that deviates from a principle MUST be explicitly
justified in the pull request. Amendments require a documented proposal, review, and
approval via pull request, with a migration plan where a principle is removed or
redefined. The constitution version follows Semantic Versioning: MAJOR for
backward-incompatible principle removals or redefinitions, MINOR for a new principle
or materially expanded guidance, and PATCH for clarifications and wording fixes.

**Version**: 1.2.0 | **Ratified**: 2026-11-06 | **Last Amended**: 2026-11-07
