package io.github.fludakit.data.metadata;

import java.util.List;

/**
 * The engine-facing SQL rendering view of a mapped table: name, identifier column, and the flat
 * physical column list. Implemented both by the annotation processor's compile-time model and by
 * the generated {@code EntityDescriptor<T>} classes, so {@code SqlBuilder} renders SQL from one
 * view regardless of which side of code generation it runs on.
 */
public interface EntitySchema {

    /** Physical table name, or {@code null} for a value object. */
    String tableName();

    /** Physical identifier column, or {@code null} for a value object. */
    String idColumn();

    /** Flat physical columns (id, scalar, fk, flattened embedded leaves) with their dot paths. */
    List<ColumnRef> columns();
}
