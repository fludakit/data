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

    /**
     * True when {@code head} — the FIRST dot segment of a navigated path — names a to-many container
     * (one-to-many or element collection) whose leaf must NOT splice a fanning {@code LEFT JOIN}.
     * The compile-time {@link io.github.fludakit.data.model.DataModel} answers from its {@code Prop}
     * shapes; a generated {@code EntityDescriptor} answers from its navigable attribute kinds. The
     * default is {@code false}: a schema with no container notion (a hand-rolled descriptor) joins
     * as before.
     */
    default boolean isToManyHead(String head) {
        return false;
    }
}
