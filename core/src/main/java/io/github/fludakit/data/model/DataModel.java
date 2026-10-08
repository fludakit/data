package io.github.fludakit.data.model;

import io.github.fludakit.data.metadata.ColumnRef;
import io.github.fludakit.data.metadata.EntitySchema;

import java.util.ArrayList;
import java.util.List;

/**
 * The compile-time, reflection-free data model of one mapped type, distilled from the processor's
 * former {@code DescriptorModel} (zero {@code javax.lang.model} imports — pure data). A sealed
 * sub-type per USE CASE: {@link EntityDataModel} (table-backed entity / aggregate root),
 * {@link EmbeddedValueObjectModel} (value object flattened into its owner's table) and
 * {@link ExternalValueObjectModel} (value object in a secondary element-collection child table).
 *
 * <p>A value object CLASS has no single canonical model: the same class embedded on one owner and
 * collected in a child table on another is TWO model instances (one per use kind) — the use site
 * picks the sub-type. The processor builds these behind its memoized caches and the generated
 * runtime descriptors implement the sibling {@link EntitySchema} SQL view, so {@code SqlBuilder}
 * renders from either side of code generation unchanged.</p>
 */
public sealed interface DataModel extends EntitySchema
        permits EntityDataModel, EmbeddedValueObjectModel, ExternalValueObjectModel {

    /** DDD stereotype: aggregate root, plain entity, or value object. */
    enum Stereotype { AGGREGATE_ROOT, ENTITY, VALUE_OBJECT }

    Stereotype stereotype();

    /** Qualified class name as a String: entity classes may not exist yet at resolve time. */
    String typeName();

    /** The identifier property, or {@code null} for a value object. */
    Prop.Id id();

    /** Non-identifier properties in declaration order. */
    List<Prop> props();

    default boolean isValueObject() {
        return stereotype() == Stereotype.VALUE_OBJECT;
    }

    @Override
    default String idColumn() {
        return id() == null ? null : id().column();
    }

    /** Looks one property up by name — the identifier first, then the props. */
    default Prop prop(String name) {
        if (id() != null && id().name().equals(name)) {
            return id();
        }
        for (Prop p : props()) {
            if (p.name().equals(name)) {
                return p;
            }
        }
        throw new IllegalArgumentException("Unknown property '" + name + "' on " + typeName());
    }

    /**
     * True when the named root property is a to-many container (one-to-many / element collection).
     * Consumed by {@link io.github.fludakit.data.sql.SqlBuilder#joinClause}, which asks the schema it
     * is rendering for rather than testing {@code instanceof EntityDescriptor} — this is the one
     * production consumer of the method.
     */
    @Override
    default boolean isToManyHead(String propName) {
        for (Prop p : props()) {
            if (p.name().equals(propName)) {
                // Java 17 safety: an instanceof chain, never a pattern switch.
                return p instanceof Prop.EntityList || p instanceof Prop.ElementList;
            }
        }
        return false;
    }

    /**
     * The shared flattening: id, scalars, fk columns and embedded leaves (prefixed
     * {@code <embeddedColumn>_<leafColumn>}, dot paths {@code <embeddedName>.<leafPath>}) in
     * declaration order; {@link Prop.EntityList}/{@link Prop.ElementList} contribute nothing.
     * This ordering is what keeps every generated SQL constant byte-identical.
     */
    static List<ColumnRef> flattenColumns(String ownerTypeName, Prop.Id id, List<Prop> props) {
        List<ColumnRef> out = new ArrayList<>();
        if (id != null) {
            out.add(new ColumnRef(id.column(), id.name()));
        }
        for (Prop p : props) {
            if (p instanceof Prop.Scalar scalar) {
                out.add(new ColumnRef(scalar.column(), scalar.name()));
            } else if (p instanceof Prop.Reference reference) {
                out.add(new ColumnRef(reference.fkColumn(), reference.name()));
            } else if (p instanceof Prop.Embedded embedded) {
                if (embedded.target() == null) {
                    throw new IllegalStateException("Entity " + ownerTypeName
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
     * {@code instanceof} and a columnless container never carries a column. Relation members carry
     * their target's MODEL (sub-typed per use kind); table/FK placement is DERIVED from it, never
     * duplicated as stored strings — except {@code Reference.targetTypeName}, kept because the
     * processor's cycle placeholder must be name-matchable without a built target.
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
         * @param target the value object's embedded-kind model; {@code null} marks an unresolved target
         */
        record Embedded(String name, String column, EmbeddedValueObjectModel target) implements Prop { }

        /**
         * A foreign-key reference to another entity's table.
         *
         * @param targetTypeName the target's FQN — retained even when {@code target} is {@code null}
         *                       or a cycle placeholder, so inverse matching stays byte-identical
         * @param target         the target's entity-kind model; {@code null} when unresolved
         * @param composition    {@code true} for a root-owned many-to-one, {@code false} for an
         *                       external {@code @Association}
         */
        record Reference(String name, String fkColumn, String targetTypeName,
                         EntityDataModel target, boolean composition) implements Prop {

            /** Derived from the target model: the referenced table, {@code null} when unresolved. */
            public String targetTable() {
                return target == null ? null : target.tableName();
            }

            /** Derived from the target model: the referenced id column, {@code null} when unresolved. */
            public String targetIdColumn() {
                return target == null ? null : target.idColumn();
            }
        }

        /**
         * A one-to-many over the target's own table, joined by the inverse fk.
         *
         * @param inverseName     the child property that points back; {@code null} while unresolved
         * @param inverseFkColumn the child table's column for that inverse; {@code null} with it
         */
        record EntityList(String name, EntityDataModel target,
                          String inverseName, String inverseFkColumn) implements Prop {

            /** Derived from the target model. */
            public String targetTypeName() {
                return target == null ? null : target.typeName();
            }

            /** Derived from the target model. */
            public String targetTable() {
                return target == null ? null : target.tableName();
            }
        }

        /**
         * A {@code List<@ValueObject>} element collection persisted to a child table; the child
         * table and parent fk live on the {@link ExternalValueObjectModel} target.
         *
         * @param elementColumns the element value object's flattened physical columns, in write order
         */
        record ElementList(String name, ExternalValueObjectModel target,
                           List<ColumnRef> elementColumns) implements Prop {

            public ElementList {
                elementColumns = elementColumns == null
                        ? List.of() : List.copyOf(elementColumns);
            }

            /** Derived from the target model. */
            public String elementTypeName() {
                return target == null ? null : target.typeName();
            }

            /** Derived from the target model: the element-collection child table. */
            public String childTable() {
                return target == null ? null : target.tableName();
            }

            /** Derived from the target model: the fk column back to the parent. */
            public String parentFkColumn() {
                return target == null ? null : target.parentFkColumn();
            }
        }
    }
}
