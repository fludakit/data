# Quickstart: Validate WriteEmitter Child-Table SQL Routing

## Prerequisites

- JDK 21+, Maven 3.9+ or `./mvnw`.

## Build & test

```bash
# Processor emission tests (asserts generated SQL is byte-identical)
./mvnw -pl processor test

# Full build incl. integration tests over H2
./mvnw test
```

## Expected outcomes

- `WriteEmissionTest` passes unchanged.
- No `DELETE FROM` / `INSERT INTO` string literals remain in `WriteEmitter`:
  ```bash
  grep -n "DELETE FROM\|INSERT INTO" processor/src/main/java/io/github/fludakit/data/processor/emitter/WriteEmitter.java
  # Expected: no matches
  ```
- Integration tests (cascade, element-collection round-trip) pass unchanged.

## Definition of done

- `WriteEmitter` builds no raw SQL text.
- All child-table mutations render via `SqlBuilder` / `QueryDefinition`.
- Generated SQL byte-identical; all suites green.
