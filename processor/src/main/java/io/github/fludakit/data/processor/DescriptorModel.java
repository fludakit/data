package io.github.fludakit.data.processor;

import io.github.fludakit.data.metadata.ColumnRef;
import io.github.fludakit.data.metadata.EntitySchema;

import java.util.ArrayList;
import java.util.List;

/**
 * Compile-time entity model used by {@link RepositoryProcessor} to drive code generation.
 *
 * <p>This mirrors the pre-refactor {@code EntityDescriptor} shape (kind enum, flattened-embedded
 * recursion, FQN strings for relationship targets) because the processor runs before entity
 * classes exist and can neither build {@code Class} literals nor load generated metamodel
 * instances. The end-user-facing runtime API — the subtype-based
 * {@code io.github.fludakit.data.metadata.EntityDescriptor<T>} — is only ever emitted into
 * generated sources; both sides share SQL rendering through the core {@link EntitySchema} view.</p>
 */
interface DescriptorModel extends EntitySchema {

    enum Kind { ID, SCALAR, EMBEDDED, MANY_TO_ONE, ASSOCIATION, ONE_TO_MANY, ELEMENT_COLLECTION }

    /**
     * @param column          physical column (or fk column); {@code null} for ONE_TO_MANY / ELEMENT_COLLECTION
     * @param targetTypeName  relationship target FQN; {@code null} for ID / SCALAR
     * @param inverseName     ONE_TO_MANY: the child property that points back; otherwise {@code null}
     * @param embedded        EMBEDDED: nested value-object model for leaf flattening; otherwise {@code null}
     * @param childTable      ELEMENT_COLLECTION: the child table the elements persist to; otherwise {@code null}
     * @param parentFkColumn  ELEMENT_COLLECTION: the child table's column referencing the parent; otherwise {@code null}
     */
    record Attribute(String name, String typeName, Kind kind, String column,
                     String targetTypeName, String inverseName, DescriptorModel embedded,
                     String childTable, String parentFkColumn) {

        /** Relation-free/legacy construction: element-collection physical placement defaults to unset. */
        Attribute(String name, String typeName, Kind kind, String column,
                  String targetTypeName, String inverseName, DescriptorModel embedded) {
            this(name, typeName, kind, column, targetTypeName, inverseName, embedded, null, null);
        }
    }

    /** Fully-qualified entity or value-object type name. */
    String typeName();

    /** Physical table name, or {@code null} for a value object. */
    @Override
    String tableName();

    /** The identifier attribute, or {@code null} for a value object. */
    Attribute id();

    /** All non-identifier attributes in declaration order. */
    List<Attribute> attributes();

    default boolean isValueObject() {
        return tableName() == null;
    }

    @Override
    default String idColumn() {
        return id() == null ? null : id().column();
    }

    default Attribute attribute(String name) {
        if (id() != null && id().name().equals(name)) return id();
        for (Attribute a : attributes()) {
            if (a.name().equals(name)) return a;
        }
        throw new IllegalArgumentException("Unknown property '" + name + "' on " + typeName());
    }

    /** Flat physical columns (id, scalar, fk, flattened embedded leaves) with their dot paths. */
    @Override
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
