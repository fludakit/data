package io.github.fludakit.data.processor.model;

import io.github.fludakit.data.metadata.ColumnRef;
import io.github.fludakit.data.metadata.JoinRef;
import io.github.fludakit.data.metadata.AttributePathResolver;
import io.github.fludakit.data.metadata.TableSchema;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable compile-time mapping metadata for an entity or value-object use.
 *
 * <p>The mapping has no compiler API or reflection dependencies. Its variants describe table-backed
 * entities, embedded value objects, and value-object element collections. It also implements
 * {@link TableSchema} and {@link AttributePathResolver} separately: physical columns describe one
 * table, while entity-rooted paths resolve through the attribute resolver.</p>
 */
public sealed interface TypeMapping extends TableSchema, AttributePathResolver<String>
        permits EntityTypeMapping, EmbeddedValueObjectMapping, ElementCollectionValueObjectMapping {

    /** Domain role of the mapped type. */
    enum Stereotype { AGGREGATE_ROOT, ENTITY, VALUE_OBJECT }

    Stereotype stereotype();

    /** Fully qualified Java type name. */
    String typeName();

    /** Identifier property, or {@code null} for a value object. */
    Prop.Id id();

    /** Properties other than the identifier, in declaration order. */
    List<Prop> props();

    default boolean isValueObject() {
        return stereotype() == Stereotype.VALUE_OBJECT;
    }

    default String idColumn() {
        return id() == null ? null : id().column();
    }

    /** Finds a property by name, including the identifier. */
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

    /** Returns whether the named property is a to-many relation or element collection. */
    default boolean isToManyPath(String attributePath) {
        int dot = attributePath.indexOf('.');
        String pathHead = dot < 0 ? attributePath : attributePath.substring(0, dot);
        for (Prop p : props()) {
            if (p.name().equals(pathHead)) {
                return p instanceof Prop.EntityList || p instanceof Prop.ElementList;
            }
        }
        return false;
    }

    /**
     * Column references for every resolvable attribute path, including leaves reached through one
     * relationship. This is the compile-time mapping source used by {@code SqlBuilder}; the query
     * plan itself carries logical paths only.
     */
    @Override
    default Map<String, ColumnRef> columnRefs() {
        Map<String, ColumnRef> refs = new LinkedHashMap<>(localAttributeRefs());
        for (Prop prop : props()) {
            Map<String, ColumnRef> targetAttributeRefs;
            JoinRef join;
            if (prop instanceof Prop.Reference reference) {
                if (reference.target() == null || reference.targetTable() == null
                        || reference.targetIdColumn() == null) {
                    continue;
                }
                targetAttributeRefs = reference.target().localAttributeRefs();
                join = new JoinRef(tableName(), null, reference.fkColumn(),
                        reference.targetTable(), reference.name(), reference.targetIdColumn());
            } else if (prop instanceof Prop.EntityList entityList) {
                if (entityList.target() == null || entityList.targetTable() == null
                        || entityList.inverseFkColumn() == null || idColumn() == null) {
                    continue;
                }
                targetAttributeRefs = entityList.target().localAttributeRefs();
                join = new JoinRef(tableName(), null, idColumn(),
                        entityList.targetTable(), entityList.name(), entityList.inverseFkColumn());
            } else if (prop instanceof Prop.ElementList elementList) {
                if (elementList.target() == null || elementList.childTable() == null
                        || elementList.parentFkColumn() == null || idColumn() == null) {
                    continue;
                }
                targetAttributeRefs = elementList.target().localAttributeRefs();
                join = new JoinRef(tableName(), null, idColumn(),
                        elementList.childTable(), elementList.name(), elementList.parentFkColumn());
            } else {
                continue;
            }
            for (Map.Entry<String, ColumnRef> entry : targetAttributeRefs.entrySet()) {
                if (!entry.getValue().joins().isEmpty()) continue;
                refs.put(prop.name() + "." + entry.getKey(), entry.getValue().through(join));
            }
        }
        return Collections.unmodifiableMap(refs);
    }

    /** Local table-column bindings, excluding every relation traversal. */
    default Map<String, ColumnRef> localAttributeRefs() {
        Map<String, ColumnRef> refs = new LinkedHashMap<>();
        addLocalAttributeRefs(refs, tableName(), id(), props(), "", "");
        return Collections.unmodifiableMap(refs);
    }

    default ColumnRef columnRef(String path) {
        ColumnRef ref = columnRefs().get(path);
        if (ref == null) {
            throw new IllegalArgumentException("Unknown property '" + path + "' on " + typeName());
        }
        return ref;
    }

    @Override
    default String attributePath(String path) {
        return path;
    }

    /**
     * Flattens table columns in declaration order. Embedded leaves receive the embedded column
     * prefix and a dot-separated logical path; to-many properties do not add owner-table columns.
     */
    static List<ColumnRef> flattenColumns(String ownerTypeName, String ownerTableName,
                                          Prop.Id id, List<Prop> props) {
        List<ColumnRef> out = new ArrayList<>();
        if (id != null) {
            out.add(new ColumnRef(id.column(), ownerTableName));
        }
        for (Prop p : props) {
            if (p instanceof Prop.Scalar scalar) {
                out.add(new ColumnRef(scalar.column(), ownerTableName));
            } else if (p instanceof Prop.Reference reference) {
                out.add(new ColumnRef(reference.fkColumn(), ownerTableName));
            } else if (p instanceof Prop.Embedded embedded) {
                if (embedded.target() == null) {
                    throw new IllegalStateException("Entity " + ownerTypeName
                            + " has an embedded property '" + embedded.name() + "' whose target is unresolved");
                }
                for (ColumnRef leaf : embedded.target().columns()) {
                    out.add(new ColumnRef(embedded.column() + "_" + leaf.column(), ownerTableName));
                }
            }
            // Prop.Id handled above; EntityList / ElementList carry no column on this table.
        }
        return out;
    }

    private static void addLocalAttributeRefs(Map<String, ColumnRef> refs, String ownerTableName,
                                              Prop.Id id, List<Prop> props,
                                              String pathPrefix, String columnPrefix) {
        if (id != null) {
            refs.put(pathPrefix + id.name(),
                    new ColumnRef(columnPrefix + id.column(), ownerTableName));
        }
        for (Prop prop : props) {
            if (prop instanceof Prop.Scalar scalar) {
                refs.put(pathPrefix + scalar.name(),
                        new ColumnRef(columnPrefix + scalar.column(), ownerTableName));
            } else if (prop instanceof Prop.Reference reference) {
                refs.put(pathPrefix + reference.name(),
                        new ColumnRef(columnPrefix + reference.fkColumn(), ownerTableName));
            } else if (prop instanceof Prop.Embedded embedded && embedded.target() != null) {
                addLocalAttributeRefs(refs, ownerTableName, null, embedded.target().props(),
                        pathPrefix + embedded.name() + ".", columnPrefix + embedded.column() + "_");
            }
        }
    }

    /** Shape of a mapped property. */
    sealed interface Prop {

        /** Declared Java property name. */
        String name();

        /** Identifier column and Java type. */
        record Id(String name, String column, String typeFqn) implements Prop { }

        /** Scalar column and Java type. */
        record Scalar(String name, String column, String typeFqn) implements Prop { }

        /**
         * Embedded value object whose leaves flatten into this table. A {@code null} target marks
         * an unresolved model during processor analysis.
         */
        record Embedded(String name, String column, EmbeddedValueObjectMapping target) implements Prop { }

        /**
         * To-one foreign-key reference. The target name remains available while a cyclic target
         * model is being resolved; {@code composition} distinguishes root-owned references from
         * external associations.
         */
        record Reference(String name, String fkColumn, String targetTypeName,
                         EntityTypeMapping target, boolean composition) implements Prop {

            /** Referenced table, or {@code null} until the target is resolved. */
            public String targetTable() {
                return target == null ? null : target.tableName();
            }

            /** Referenced identifier column, or {@code null} until the target is resolved. */
            public String targetIdColumn() {
                return target == null ? null : target.idColumn();
            }
        }

        /**
         * To-many entity relation stored in the target table. Inverse details may be {@code null}
         * until relation resolution completes.
         */
        record EntityList(String name, EntityTypeMapping target,
                           String inverseName, String inverseFkColumn) implements Prop {

            /** Target entity type, or {@code null} until resolved. */
            public String targetTypeName() {
                return target == null ? null : target.typeName();
            }

            /** Target table, or {@code null} until resolved. */
            public String targetTable() {
                return target == null ? null : target.tableName();
            }
        }

        /**
         * Value-object element collection stored in a child table. {@code elementColumns} contains
         * the flattened element columns in write order.
         */
        record ElementList(String name, ElementCollectionValueObjectMapping target,
                           List<ColumnRef> elementColumns) implements Prop {

            public ElementList {
                elementColumns = elementColumns == null
                        ? List.of() : List.copyOf(elementColumns);
            }

            /** Element type, or {@code null} until resolved. */
            public String elementTypeName() {
                return target == null ? null : target.typeName();
            }

            /** Child table, or {@code null} until resolved. */
            public String childTable() {
                return target == null ? null : target.tableName();
            }

            /** Parent foreign-key column, or {@code null} until resolved. */
            public String parentFkColumn() {
                return target == null ? null : target.parentFkColumn();
            }
        }
    }
}
