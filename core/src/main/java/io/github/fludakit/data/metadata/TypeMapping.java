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
 * entities ({@link AggregateTypeMapping}, {@link EntityTypeMapping}) and value objects
 * ({@link ValueObjectMapping} — two shapes, one per PLACEMENT: flattened into the owner's row, or
 * in a child table of its own). It also implements
 * {@link TableSchema} and {@link ColumnResolver} separately: physical columns describe one table,
 * while entity-rooted paths are resolved through the aggregate tree by {@link #resolve(String)}.</p>
 *
 * <p>The domain role of a mapping is expressed by WHICH implementation it is, not by a flag: an
 * aggregate root is an {@link AggregateTypeMapping}, a plain entity an {@link EntityTypeMapping}, and
 * a value object a {@link ValueObjectMapping}. The interface is deliberately NOT {@code sealed}:
 * the processor generates one {@code <Entity>Metadata implements TypeMapping<T>} descriptor per
 * mapped type, and a sealed permits list could not name those. The hierarchy still buys the real
 * benefit — distinct types instead of a flag — and {@link #ownsAggregateLifecycle()} answers the one
 * behavioural question callers actually ask.</p>
 */
public interface TypeMapping<T> extends TableSchema, ColumnResolver {

    /**
     * Whether this mapping owns the lifecycle of everything inside it.
     *
     * <p>True only for {@link AggregateTypeMapping}. This is the aggregate-root gate: it drives the
     * write-side cascade and, per {@code docs/AGGREGATE-SEMANTICS.md}, the read-side eager/lazy
     * decision too. A plain entity never cascades its own relations — only a root synchronises the
     * whole aggregate.</p>
     */
    default boolean ownsAggregateLifecycle() {
        return false;
    }

    /**
     * Whether this mapping is a value object (embedded or element-collection).
     *
     * <p>Value objects have no identity and never own relations. Table-backed
     * {@link #tableBacked()} is NOT the same question: an element-collection value object lives in
     * its own child table, so it is table-backed without being an entity.</p>
     */
    default boolean isValueObject() {
        return false;
    }

    /** Whether this mapping has its own table (entity or element-collection value object). */
    default boolean tableBacked() {
        return tableName() != null;
    }

    /** Fully qualified Java type name. */
    String typeName();

    /** Identifier property, or {@code null} for a value object. */
    Prop.Id id();

    /** Properties other than the identifier, in declaration order. */
    List<Prop> props();

    default String idColumn() {
        return id() == null ? null : id().column();
    }

    /**
     * Finds a property by name, including the identifier.
     *
     * @throws UnknownAttributePathException when no property carries that name
     */
    default Prop prop(String name) {
        Prop p = propOrNull(name);
        if (p == null) {
            throw new UnknownAttributePathException(name, typeName());
        }
        return p;
    }

    /**
     * The properties that have a column on this type's OWN row: everything but the two to-many
     * shapes. An {@link Prop.EntityCollection} is reached through a join and an
     * {@link Prop.ElementCollection} through its child table, so neither has a column here — which
     * is the same split {@link #isToManyPath} answers one property at a time.
     */
    default List<Prop> rowProps() {
        return props().stream()
                .filter(p -> !(p instanceof Prop.EntityCollection) && !(p instanceof Prop.ElementCollection))
                .toList();
    }

    /**
     * The {@code List<@ValueObject>} element collections, in declaration order: the to-many shape
     * that is not reached through a join but through its own child table.
     */
    default List<Prop.ElementCollection> elementCollections() {
        return props().stream()
                .filter(Prop.ElementCollection.class::isInstance)
                .map(Prop.ElementCollection.class::cast)
                .toList();
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
     * every relation-traversal leaf (those are resolved by {@link #resolve}).
     *
     * <p>Placements only. A generated descriptor overrides it with a literal map; nothing here
     * describes how to REACH a column, because a column that needs an edge is reached through
     * {@link #resolve} instead.</p>
     */
    @Override
    default Map<String, ColumnRef> columnRefs() {
        return ownColumns(false);
    }

    /**
     * The entity's own resolvable columns, keyed by logical path.
     *
     * <p>Alias of {@link #columnRefs()}, kept because generated code and the
     * processor both refer to the read view by this name; the distinction from
     * {@link #writeColumnRefs()} is the meaningful one.
     */
    default Map<String, ColumnRef> localColumnRefs() {
        return columnRefs();
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
     * Resolves an entity-rooted dot path through the aggregate tree to its placement. A to-one
     * {@link Prop.Reference} chain traverses transitively; a to-many head is depth-1 over the
     * child's own columns. A write-only back-reference, a cycle, or an unknown property throws.
     *
     * <p>This is the GENERIC walk, and it is the default rather than the only implementation:
     * a mapped type's navigation list is fixed at compile time, so the processor emits a
     * {@code switch} that returns each path's placement directly. This walk remains for the mappings
     * that have no generated descriptor — value objects and the processor's own compile-time models,
     * which cannot use a descriptor that does not exist yet.</p>
     */
    @Override
    default ColumnPlacement resolve(String path) {
        return resolve(this, path, new HashSet<>());
    }

    private static ColumnPlacement resolve(TypeMapping<?> mapping, String path, Set<String> inProgress) {
        ColumnRef local = mapping.columnRefs().get(path);
        if (local != null) {
            return new ColumnPlacement(local);
        }
        int dot = path.indexOf('.');
        if (dot < 0) {
            throw new UnknownAttributePathException(path, mapping.typeName());
        }
        String head = path.substring(0, dot);
        String tail = path.substring(dot + 1);
        Prop prop = mapping.propOrNull(head);
        if (prop instanceof Prop.Embedded embedded && embedded.target() != null) {
            // Embedded leaves flatten into this table; continue the walk in the embedded target.
            return resolve(embedded.target(), tail, inProgress);
        }
        JoinEdge edge = relationEdge(mapping, prop);
        TypeMapping<?> target = relationTarget(prop);
        if (edge == null || target == null || !inProgress.add(target.typeName())) {
            throw new UnknownAttributePathException(path, mapping.typeName());
        }
        ColumnPlacement tailPlacement = prop instanceof Prop.Reference
                ? resolve(target, tail, inProgress)
                : childLeaf(target, tail, path, mapping.typeName());
        List<JoinEdge> edges = new ArrayList<>();
        edges.add(edge);
        edges.addAll(tailPlacement.edges());
        return new ColumnPlacement(tailPlacement.leaf(), edges);
    }

    /** A to-many head resolves to one of the child's own columns; deeper paths are not traversable. */
    private static ColumnPlacement childLeaf(TypeMapping<?> target, String tail, String path,
                                             String ownerTypeName) {
        ColumnRef leaf = target.columnRefs().get(tail);
        if (leaf == null) {
            throw new UnknownAttributePathException(path, ownerTypeName);
        }
        return new ColumnPlacement(leaf);
    }

    /**
     * The join edge a traversable relation property contributes, or {@code null} when unbuildable
     * (or when the property is a write-only back-reference). Public so the processor's path resolver
     * reuses this one rule instead of duplicating it against its compile-time model.
     */
    public static JoinEdge relationEdge(TypeMapping<?> owner, Prop prop) {
        if (prop instanceof Prop.Reference reference && !reference.backReference()) {
            if (reference.targetTable() == null || reference.targetIdColumn() == null) {
                return null;
            }
            return new JoinEdge.ToOne(
                    new ColumnRef.ForeignKey(reference.fkColumn(), owner.tableName()),
                    new ColumnRef.Simple(reference.targetIdColumn(), reference.targetTable()));
        }
        if (prop instanceof Prop.EntityCollection entityCollection) {
            if (entityCollection.targetTable() == null || entityCollection.inverseFkColumn() == null
                    || owner.idColumn() == null) {
                return null;
            }
            return new JoinEdge.ToMany(
                    new ColumnRef.Simple(owner.idColumn(), owner.tableName()),
                    new ColumnRef.ForeignKey(entityCollection.inverseFkColumn(),
                            entityCollection.targetTable()));
        }
        if (prop instanceof Prop.ElementCollection elementCollection) {
            if (elementCollection.childTable() == null || elementCollection.parentFkColumn() == null
                    || owner.idColumn() == null) {
                return null;
            }
            return new JoinEdge.ToMany(
                    new ColumnRef.Simple(owner.idColumn(), owner.tableName()),
                    new ColumnRef.ForeignKey(elementCollection.parentFkColumn(),
                            elementCollection.childTable()));
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
                            Names.columnBase(embedded.name()) + "_" + leaf.column(), ownerTableName));
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
                            new ColumnRef.ForeignKey(columnPrefix + reference.fkColumn(), ownerTableName));
                }
            } else if (prop instanceof Prop.Embedded embedded && embedded.target() != null) {
                addLocalColumnRefs(refs, ownerTableName, null, embedded.target().props(),
                        pathPrefix + embedded.name() + ".", columnPrefix + Names.columnBase(embedded.name()) + "_",
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
         * an unresolved model during processor analysis — and it is {@code null} in a generated
         * descriptor, which publishes placements rather than the transitive mapping.
         */
        record Embedded(String name, ValueObjectMapping<?> target) implements Prop { }

        /**
         * To-one foreign-key reference. The target name remains available while a cyclic target
         * model is being resolved; {@code composition} distinguishes root-owned references from
         * external associations, and {@code backReference} marks the inverse FK of a parent
         * {@code EntityCollection} — a write-only column that is not a queryable path.
         *
         * <p>The target is typed as {@link TypeMapping} rather than {@link EntityTypeMapping} so an
         * aggregate root can be a traversal target: a root and a plain entity differ in relationship
         * contract, not in storage, and both are valid to-one destinations.</p>
         */
        record Reference(String name, String fkColumn, String targetTypeName,
                         TypeMapping<?> target, boolean composition,
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
         * until relation resolution completes. The target is a {@link TypeMapping} so an aggregate
         * root can be the collection's element type.
         */
        record EntityCollection(String name, String targetTypeName, TypeMapping<?> target,
                                String inverseName, String inverseFkColumn) implements Prop {

            /** Target table, or {@code null} until resolved. */
            public String targetTable() {
                return target == null ? null : target.tableName();
            }
        }

        /**
         * Value-object element collection stored in a child table.
         *
         * <p>The child table, the parent FK and the flattened element columns are all answered by the
         * TARGET, which is the value object placed on them: a value object in an element collection
         * really does have a table of its own, the column linking it back to the owner is part of that
         * placement, and the flattened columns are the target's own {@link ElementCollectionValueObjectMapping#columns()}.
         * The prop stores only the target, so the two views of the placement cannot drift apart.</p>
         */
        record ElementCollection(String name, ElementCollectionValueObjectMapping<?> target) implements Prop {

            /**
             * The flattened element columns in write order — derived from the target, never stored
             * separately, so it can never disagree with the placement that produced it.
             */
            public List<ColumnRef> elementColumns() {
                return target == null ? List.of() : target.columns();
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
