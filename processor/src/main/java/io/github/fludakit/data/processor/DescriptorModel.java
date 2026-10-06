package io.github.fludakit.data.processor;

import io.github.fludakit.data.metadata.ColumnRef;
import io.github.fludakit.data.metadata.EntitySchema;

import java.util.ArrayList;
import java.util.List;

/**
 * Compile-time entity model used by {@link RepositoryProcessor} to drive code generation.
 *
 * <p>The {@code DescriptorModel Kind} enum and its 9-component {@code Attribute} record that this
 * replaces let every property carry every field the pipeline might want — a columnless container
 * still had a {@code column}, a reference still had an {@code embedded} tree. This model is
 * stereotype-tagged at the top level and splits properties into a {@link Prop} sealed hierarchy so
 * each per-field shape carries exactly what it needs. The processor runs before entity classes
 * exist and can neither build {@code Class} literals nor load generated metamodel instances, so the
 * model keeps FQN strings for relationship targets. The end-user-facing runtime API — the subtype-
 * based {@code io.github.fludakit.data.metadata.EntityDescriptor<T>} — is only ever emitted into
 * generated sources; both sides share SQL rendering through the core {@link EntitySchema} view.</p>
 */
record DescriptorModel(Stereotype stereotype, String typeName, String tableName,
                       Prop.Id id, List<Prop> props) implements EntitySchema {

    DescriptorModel {
        // Defensive copy: the immutability invariant of a descriptor must not rely on
        // call-site discipline.
        props = List.copyOf(props);
    }

    enum Stereotype { AGGREGATE_ROOT, ENTITY, VALUE_OBJECT }

    /** DDD stereotype: aggregate root, plain entity, or value object. */
    boolean isValueObject() {
        return stereotype == Stereotype.VALUE_OBJECT;
    }

    /** The identifier property, or {@code null} for a value object. */
    @Override
    public String idColumn() {
        return id == null ? null : id.column();
    }

    /** Looks one property up by name — the identifier first, then the props. */
    Prop prop(String name) {
        if (id != null && id.name().equals(name)) {
            return id;
        }
        for (Prop p : props) {
            if (p.name().equals(name)) {
                return p;
            }
        }
        throw new IllegalArgumentException("Unknown property '" + name + "' on " + typeName);
    }

    /**
     * Flat physical columns (id, scalar, fk, flattened embedded leaves) with their dot paths, in
     * declaration order. {@link Prop.EntityList} and {@link Prop.ElementList} contribute nothing:
     * the one-to-many lives in the child's own table and the element collection in a child table.
     * This ordering is what keeps every generated SQL constant byte-identical.
     */
    @Override
    public List<ColumnRef> columns() {
        List<ColumnRef> out = new ArrayList<>();
        if (id != null) {
            out.add(new ColumnRef(id.column(), id.name()));
        }
        for (Prop p : props) {
            // Java 17 safety: an instanceof chain, never a pattern switch.
            if (p instanceof Prop.Scalar scalar) {
                out.add(new ColumnRef(scalar.column(), scalar.name()));
            } else if (p instanceof Prop.Reference reference) {
                out.add(new ColumnRef(reference.fkColumn(), reference.name()));
            } else if (p instanceof Prop.Embedded embedded) {
                if (embedded.target() == null) {
                    throw new IllegalStateException("Entity " + typeName
                            + " has an embedded property '" + embedded.name() + "' whose target is unresolved");
                }
                for (ColumnRef leaf : embedded.target().columns()) {
                    out.add(new ColumnRef(embedded.column() + "_" + leaf.column(),
                            embedded.name() + "." + leaf.path()));
                }
            }
            // Prop.Id handled above; EntityList / ElementList carry no column on this table.
        }
        return out;
    }

    /**
     * One mapped property's compile-time shape. Sealed so the codegen pipeline branches on
     * {@code instanceof} and a columnless container never carries a column.
     */
    sealed interface Prop {

        /** The declared property name, as written on the entity. */
        String name();

        /** @param typeFqn the declared Java type of the identifier field. */
        record Id(String name, String column, String typeFqn) implements Prop { }

        /** @param typeFqn the declared Java type of the column-bearing field. */
        record Scalar(String name, String column, String typeFqn) implements Prop { }

        /**
         * A direct value-object field whose leaves flatten into this table under {@code column} as
         * the leaf prefix.
         *
         * @param target the value object's own model; {@code null} marks an unresolved (cycle) target
         */
        record Embedded(String name, String column, DescriptorModel target) implements Prop { }

        /**
         * A foreign-key reference. {@code targetTable}/{@code targetIdColumn} resolve the join edge.
         *
         * @param composition {@code true} for a root-owned many-to-one, {@code false} for an external
         *                    {@code @Association}. Both skip hydration today (Task 9 changes that).
         */
        record Reference(String name, String fkColumn, String targetTypeName,
                         String targetTable, String targetIdColumn, boolean composition) implements Prop { }

        /**
         * A one-to-many over the target's own table, joined by the inverse fk.
         *
         * @param inverseName    the child property that points back; {@code null} while unresolved
         * @param inverseFkColumn the child table's column for that inverse; {@code null} with it
         */
        record EntityList(String name, String targetTypeName, String targetTable,
                          String inverseName, String inverseFkColumn) implements Prop { }

        /**
         * A {@code List<@ValueObject>} element collection persisted to a child table.
         *
         * @param elementColumns the element value object's flattened physical columns, in write order
         */
        record ElementList(String name, String elementTypeName, String childTable,
                           String parentFkColumn, List<ColumnRef> elementColumns) implements Prop { }
    }
}
