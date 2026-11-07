# Quickstart: Validate SqlBuilder pagedSeekStatement Aliasing

```bash
./mvnw -pl core,processor test    # SqlBuilderSeekClauseTest + CursorSeekEmissionTest
./mvnw test                        # full
```

Expected: tests pass with byte-identical generated SQL; `pagedSeekStatement` no longer calls
`String.replace`, and `FindEmitter` no longer assembles the cursor ORDER BY string.
