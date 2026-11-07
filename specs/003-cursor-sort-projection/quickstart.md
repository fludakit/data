# Quickstart: Validate FindEmitter Cursor Sort/Projection

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

- `ToManySortTest` and `CursorSeekEmissionTest` pass unchanged.
- No cursor-paging SQL assembly remains in `FindEmitter`:
  ```bash
  grep -nE "SELECT |GROUP BY|LEFT JOIN|MIN\(|MAX\(|ORDER BY" \
    processor/src/main/java/io/github/fludakit/data/processor/emitter/FindEmitter.java
  # Expected: no matches
  ```

## Definition of done

- `FindEmitter` builds no SQL text for cursor paging.
- `aggregateSortTerm` and `columns()` are the single implementations.
- Generated SQL byte-identical; all suites green.
