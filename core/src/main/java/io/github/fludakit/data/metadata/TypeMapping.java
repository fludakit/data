package io.github.fludakit.data.metadata;

import io.github.fludakit.data.Names;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Immutable mapping metadata for an entity or value-object use, shared by the processor (build
 * time) and {@code SqlBuilder} (runtime).
 *
 * <p>The mapping has no compiler API or reflection dependencies. Its variants describe table-backed
 * entities, embedded value objects, and value-object element collections. It also implements
 * {@link TableSchema} and {@link AttributePathResolver} separately: physical columns describe one
 * table, while entity-rooted paths are walked through the aggregate tree by {@link #visit}.</p>
 */
public interface TypeMapping<T> extends TableSchema, AttributePathResolver {

    /** Domain role of the mapped type: entity, value object, or aggregate root. */
    enum Kind { ENTITY, VALUE_OBJECT, AGGREGATE_ROOT }

    /** Domain role of the mapped type. */
    Kind kind();

    /** Fully qualified Java type name. */
    String typeName();

    /** Identifier property, or {@code null} for a value object. */
    Prop.Id id();

    /** Properties other than the identifier, in declaration order. */
    List<Prop> props();

    default String idColumn() {
        return id() == null ? null : id().column();
    }

    /** Finds a property by name, including the identifier. */
    default Prop prop(String name) {
        Prop p = propOrNull(name);
        if (p == null) {
            throw new IllegalArgumentException("Unknown property '" + name + "' on " + typeName());
        }
        return p;
    }

    /** Returns whether the named property is a to-many relation or element collection. */
    default boolean isToManyPath(String path) {
        int dot = path.indexOf('.');
        String pathHead = dot < 0 ? path : path.substring(0, dot);
        for (Prop p : props()) {
            if (p.name().equals(pathHead)) {
                return p instanceof Prop.EntityCollection || p instanceof Prop.ElementCollection;
            }
        }
        return false;
    }

    /**
     * The entity's own table columns by logical path: the identifier, scalars, forward to-one
     * foreign keys, and flattened embedded leaves. Write-only back-references are omitted, as are
     * every relation-traversal leaf (those are resolved by {@link #visit}).
     */
    @Override
    default Map<String, ColumnRef> columnRefs() {
        return ownColumns(false);
    }

    /**
     * The write-path view of the entity's own columns: everything {@link #columnRefs()} exposes,
     * plus write-only back-reference foreign keys as plain columns. INSERT/UPDATE binds these even
     * though they are not queryable paths.
     */
    default Map<String, ColumnRef> writeColumnRefs() {
        return ownColumns(true);
    }

    private Map<String, ColumnRef> ownColumns(boolean includeBackReferences) {
        Map<String, ColumnRef> refs = new LinkedHashMap<>();
        addLocalColumnRefs(refs, tableName(), id(), props(), "", "", includeBackReferences);
        return Collections.unmodifiableMap(refs);
    }

    /**
     * Walks an entity-rooted dot path through the aggregate tree, reporting each relation edge and
     * the terminal column to {@code visitor}. A to-one {@link Prop.Reference} chain traverses
     * transitively; a to-many head is depth-1 over the child's own columns. A write-only
     * back-reference, a cycle, or an unknown property throws without reporting a leaf.
     */
    @Override
    default void visit(String path, AttributePathVisitor visitor) {
        visit(path, visitor, new HashSet<>());
    }

    private void visit(String path, AttributePathVisitor visitor, Set<String> inProgress) {
        ColumnRef local = columnRefs().get(path);
        if (local != null) {
            visitor.leaf(local);
            return;
        }
        int dot = path.indexOf('.');
        if (dot < 0) {
            throw new IllegalArgumentException("Unknown property '" + path + "' on " + typeName());
        }
        String head = path.substring(0, dot);
        String tail = path.substring(dot + 1);
        Prop prop = propOrNull(head);
        if (prop instanceof Prop.Embedded embedded && embedded.target() != null) {
            // Embedded leaves flatten into this table; continue the walk in the embedded target.
            TypeMapping<?> embeddedTarget = embedded.target();
            embeddedTarget.visit(tail, visitor, inProgress);
            return;
        }
        ColumnRef edge = relationEdge(prop);
        TypeMapping<?> target = relationTarget(prop);
        if (edge == null || target == null || !inProgress.add(target.typeName())) {
            throw new IllegalArgumentException("Unknown property '" + path + "' on " + typeName());
        }
        visitor.edge(edge);
        if (prop instanceof Prop.Reference) {
            target.visit(tail, visitor, inProgress);
        } else {
            ColumnRef leaf = target.columnRefs().get(tail);
            if (leaf == null) {
                throw new IllegalArgumentException("Unknown property '" + path + "' on " + typeName());
            }
            visitor.leaf(leaf);
        }
    }

    /** The join edge a traversable relation property contributes, or {@code null} when unbuildable. */
    private ColumnRef relationEdge(Prop prop) {
        if (prop instanceof Prop.Reference reference && !reference.backReference()) {
            if (reference.targetTable() == null || reference.targetIdColumn() == null) {
                return null;
            }
            return new ColumnRef.Reference(reference.fkColumn(), tableName(),
                    reference.targetTable(), reference.targetIdColumn());
        }
        if (prop instanceof Prop.EntityCollection entityCollection) {
            if (entityCollection.targetTable() == null || entityCollection.inverseFkColumn() == null
                    || idColumn() == null) {
                return null;
            }
            return new ColumnRef.Collection(entityCollection.inverseFkColumn(),
                    entityCollection.targetTable(), tableName(), idColumn());
        }
        if (prop instanceof Prop.ElementCollection elementCollection) {
            if (elementCollection.childTable() == null || elementCollection.parentFkColumn() == null
                    || idColumn() == null) {
                return null;
            }
            return new ColumnRef.Collection(elementCollection.parentFkColumn(),
                    elementCollection.childTable(), tableName(), idColumn());
        }
        return null;
    }

    /** The compile-time model a relation property targets, or {@code null} for non-relations. */
    private static TypeMapping<?> relationTarget(Prop prop) {
        if (prop instanceof Prop.Reference reference) return reference.target();
        if (prop instanceof Prop.EntityCollection entityCollection) return entityCollection.target();
        if (prop instanceof Prop.ElementCollection elementCollection) return elementCollection.target();
        return null;
    }

    private Prop propOrNull(String name) {
        if (id() != null && id().name().equals(name)) {
            return id();
        }
        for (Prop p : props()) {
            if (p.name().equals(name)) {
                return p;
            }
        }
        return null;
    }

    /**
     * Flattens table columns in declaration order. Embedded leaves receive the embedded column
     * prefix; to-many properties do not add owner-table columns. Back-reference foreign keys stay,
     * because they are physical columns even though they are not queryable paths.
     */
    static List<ColumnRef> flattenColumns(String ownerTypeName, String ownerTableName,
                                          Prop.Id id, List<Prop> props) {
        List<ColumnRef> out = new ArrayList<>();
        if (id != null) {
            out.add(new ColumnRef.Simple(id.column(), ownerTableName));
        }
        for (Prop p : props) {
            if (p instanceof Prop.Scalar scalar) {
                out.add(new ColumnRef.Simple(scalar.column(), ownerTableName));
            } else if (p instanceof Prop.Reference reference) {
                out.add(new ColumnRef.Simple(reference.fkColumn(), ownerTableName));
            } else if (p instanceof Prop.Embedded embedded) {
                if (embedded.target() == null) {
                    throw new IllegalStateException("Entity " + ownerTypeName
                            + " has an embedded property '" + embedded.name() + "' whose target is unresolved");
                }
                for (ColumnRef leaf : embedded.target().columns()) {
                    out.add(new ColumnRef.Simple(
                            Names.columnName(embedded.name()) + "_" + leaf.column(), ownerTableName));
                }
            }
            // Prop.Id handled above; EntityCollection / ElementCollection carry no column on this table.
        }
        return out;
    }

    private static void addLocalColumnRefs(Map<String, ColumnRef> refs, String ownerTableName,
                                           Prop.Id id, List<Prop> props,
                                           String pathPrefix, String columnPrefix,
                                           boolean includeBackReferences) {
        if (id != null) {
            refs.put(pathPrefix + id.name(),
                    new ColumnRef.Simple(columnPrefix + id.column(), ownerTableName));
        }
        for (Prop prop : props) {
            if (prop instanceof Prop.Scalar scalar) {
                refs.put(pathPrefix + scalar.name(),
                        new ColumnRef.Simple(columnPrefix + scalar.column(), ownerTableName));
            } else if (prop instanceof Prop.Reference reference) {
                if (reference.backReference()) {
                    if (includeBackReferences) {
                        refs.put(pathPrefix + reference.name(),
                                new ColumnRef.Simple(columnPrefix + reference.fkColumn(), ownerTableName));
                    }
                } else {
                    refs.put(pathPrefix + reference.name(),
                            new ColumnRef.Reference(columnPrefix + reference.fkColumn(), ownerTableName,
                                    reference.targetTable(), reference.targetIdColumn()));
                }
            } else if (prop instanceof Prop.Embedded embedded && embedded.target() != null) {
                addLocalColumnRefs(refs, ownerTableName, null, embedded.target().props(),
                        pathPrefix + embedded.name() + ".", columnPrefix + Names.columnName(embedded.name()) + "_",
                        includeBackReferences);
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
        record Embedded(String name, EmbeddedValueObjectMapping<?> target) implements Prop { }

        /**
         * To-one foreign-key reference. The target name remains available while a cyclic target
         * model is being resolved; {@code composition} distinguishes root-owned references from
         * external associations, and {@code backReference} marks the inverse FK of a parent
         * {@code EntityCollection} — a write-only column that is not a queryable path.
         */
        record Reference(String name, String fkColumn, String targetTypeName,
                         EntityTypeMapping<?> target, boolean composition,
                         boolean backReference) implements Prop {

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
        record EntityCollection(String name, EntityTypeMapping<?> target,
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
        record ElementCollection(String name, ElementCollectionValueObjectMapping<?> target,
                                 List<ColumnRef> elementColumns) implements Prop {

            public ElementCollection {
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
