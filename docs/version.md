# Optimistic Locking with @Version

!!! warning "Not implemented yet"
    Optimistic locking is on the roadmap but is **not supported** in the current version. The `@Version` attribute is not recognized, and concurrent updates are last-write-wins — a stale update is not detected or rejected. The Jakarta Data TCK's `@Version` suites are excluded from the conformance run for this reason.

The intended design is a version column on the entity that the generated `UPDATE` both increments and guards with a `WHERE … AND version = :expected` predicate, failing the update when the row changed underneath you. Until this lands, do not rely on a version field for concurrency control. Track progress in the [issue tracker](https://github.com/fludakit/data/issues).
