package io.github.fludakit.data.metadata;

import java.util.ArrayList;
import java.util.List;

/**
 * Acyclic, reflection-free description of an entity (or value object) and its physical columns.
 *
 * <p>Implemented both by the annotation processor's compile-time model and by the generated
 * {@code <Entity>Metadata} classes. Relationships are carried as type-name strings
 * ({@link Attribute#targetTypeName()}, {@link Attribute#inverseName()}) rather than object
 * references so the graph cannot form an initialization cycle. The only nested descriptor is
 * {@link Attribute#embedded()}, used to flatten {@code EMBEDDED} value-object leaves.</p>
 *
 * <p>{@link io.github.fludakit.data.sql.SqlBuilder} renders SQL from {@link #tableName()},
 * {@link #columns()} and {@link #id()} only.</p>
 */
public interface EntityDescriptor {

    enum Kind { ID, SCALAR, EMBEDDED, MANY_TO_ONE, ASSOCIATION, ONE_TO_MANY, ELEMENT_COLLECTION }

    /**
     * @param column         physical column (or fk column); {@code null} for ONE_TO_MANY / ELEMENT_COLLECTION
     * @param targetTypeName relationship target FQN; {@code null} for ID / SCALAR
     * @param inverseName    ONE_TO_MANY: the child property that points back; otherwise {@code null}
     * @param embedded       EMBEDDED: nested value-object descriptor for leaf flattening; otherwise {@code null}
     */
    record Attribute(String name, String typeName, Kind kind, String column,
                     String targetTypeName, String inverseName, EntityDescriptor embedded) { }

    /** A flat physical column plus the dot-separated property path that selects it. */
    record ColumnRef(String column, String path) { }

    String typeName();

    /** Physical table name, or {@code null} for a value object. */
    String tableName();

    /** The identifier attribute, or {@code null} for a value object. */
    Attribute id();

    /** All non-identifier attributes in declaration order. */
    List<Attribute> attributes();

    default boolean isValueObject() {
        return tableName() == null;
    }

    /**
     * Resolves the named attribute, looking the identifier up first. This deliberately diverges
     * from the old {@code EntityMetadata.property}, which threw when asked for the id name.
     */
    default Attribute attribute(String name) {
        if (id() != null && id().name().equals(name)) return id();
        for (Attribute a : attributes()) {
            if (a.name().equals(name)) return a;
        }
        throw new IllegalArgumentException("Unknown property '" + name + "' on " + typeName());
    }

    /** Flat physical columns (id, scalar, fk, flattened embedded leaves) with their dot-separated path. */
    default List<ColumnRef> columns() {
        List<ColumnRef> out = new ArrayList<>();
        if (id() != null) out.add(new ColumnRef(id().column(), id().name()));
        for (Attribute a : attributes()) {
            switch (a.kind()) {
                case SCALAR, MANY_TO_ONE, ASSOCIATION -> out.add(new ColumnRef(a.column(), a.name()));
                case EMBEDDED -> {
                    if (a.embedded() == null) {
                        throw new IllegalStateException("Entity " + typeName()
                                + " has an embedded property '" + a.name() + "' whose target is unresolved");
                    }
                    for (ColumnRef leaf : a.embedded().columns()) {
                        out.add(new ColumnRef(a.column() + "_" + leaf.column(), a.name() + "." + leaf.path()));
                    }
                }
                case ONE_TO_MANY, ELEMENT_COLLECTION -> { /* no column on this table */ }
                default -> { }
            }
        }
        return out;
    }
}
