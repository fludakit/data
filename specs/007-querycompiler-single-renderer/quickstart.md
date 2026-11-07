# Quickstart: Validate QueryCompiler Single Renderer

```bash
./mvnw -pl core,processor test    # SqlBuilderBuildTest + full
./mvnw test                        # full
```

Expected: byte-identical SQL; one `Conjunct` definition; no hand-built join loop in `aggregateSortTerm`;
`selectBy`/`countBy`/`deleteBy` build a `SqlFragment`.
