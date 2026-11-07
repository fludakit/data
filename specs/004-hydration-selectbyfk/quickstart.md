# Quickstart: Validate HydrationEmitter SELECT Routing

## Prerequisites

- JDK 21+, Maven 3.9+ or `./mvnw`.

## Build & test

```bash
./mvnw -pl processor test    # emission suite
./mvnw test                  # full, incl. hydration integration tests (H2)
```

## Expected outcomes

- No hand-built `SELECT` string in `HydrationEmitter`:
  ```bash
  grep -nE '"SELECT ' processor/src/main/java/io/github/fludakit/data/processor/emitter/HydrationEmitter.java
  # Expected: no matches
  ```
- Hydration integration tests (entity-collection, element-collection, composing association) pass unchanged.

## Definition of done

- `HydrationEmitter` builds no raw SQL.
- The three hydration SELECTs render via `SqlBuilder`.
- Generated SQL byte-identical; all suites green.
