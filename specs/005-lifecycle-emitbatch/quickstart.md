# Quickstart: Validate LifecycleEmitter emitBatch

```bash
./mvnw -pl processor test    # emission suite
./mvnw test                  # full
```

Expected: emission tests pass unchanged; `emitSaveAll`/`emitInsertAll`/`emitUpdateAll` are gone,
replaced by one `emitBatch`.
