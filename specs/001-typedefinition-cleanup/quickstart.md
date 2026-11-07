# Quickstart: Validate TypeDefinition Cleanup

Run these to prove the refactor is behavior-preserving.

## Prerequisites

- JDK 21+ (JDK 25 to publish), Maven 3.9+ or `./mvnw`.

## Build & test

```bash
# Full build + all tests (unit, emission, integration over H2)
./mvnw clean install

# Fast targeted unit tests for the changed core metadata
./mvnw -pl core test

# Processor emission tests (asserts generated output is byte-identical)
./mvnw -pl processor test

# End-to-end integration tests over a real database
./mvnw -pl integration-tests test
```

## Expected outcomes

- `core` unit tests (`TypeDefinitionTest`, `JoinEdgeTest`, `MutationPlanTest`) pass unchanged.
- `processor` emission tests (`WriteEmissionTest`, `GeneratedArtifactsTest`, `ToManySortTest`,
  `CursorSeekEmissionTest`, etc.) pass without modifying expected generated output.
- `integration-tests` pass unchanged (estore, profile, projection, cascade, team, task, underscore).

## Manual check (no `instanceof Entity/Aggregate` left)

```bash
grep -rn "instanceof Entity\|instanceof Aggregate\|instanceof TypeDefinition.Entity\|instanceof TypeDefinition.Aggregate" \
  core/src/main/java/io/github/fludakit/data/metadata/TypeDefinition.java
# Expected: no matches
```

## Definition of done

- `TypeDefinition.id()` is the single identifier accessor; no `idProp`/`idPropertyName` helpers remain.
- A single `Prop.Visitor` covers all `Prop` kinds and backs `columns()`, `columnRefs()`, and `PathResolver`.
- Generated output byte-identical; all suites green.
