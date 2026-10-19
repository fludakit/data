package io.github.fludakit.data.metadata;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sealed metadata interface for mapped types (entity, aggregate root, or value object).
 *
 * <p>Replaces the flat TypeDefinition record with a type-safe sealed hierarchy.
 * Each variant carries exactly the properties it needs: Entity and Aggregate have IDs and tables,
 * ValueObject does not. Properties are categorized into typed records for compile-time safety.</p>
 */
public sealed interface TypeDefinition extends TableSchema, ColumnResolver
        permits TypeDefinition.Entity,
                TypeDefinition.Aggregate,
                TypeDefinition.ValueObject {

    /** The fully-qualified Java type name. */
    String typeName();

    /** The physical table name, or {@code null} for value objects without their own table. */
    String tableName();

    /** All properties of this type, in declaration order. */
    List<Prop> properties();

    // ── Common operations ────────────────────────────────────────────────

    /**
     * Finds a property by name across all categories.
     */
    default Prop findProperty(String name) {
        for (Prop prop : properties()) {
            if (prop.name().equals(name)) {
                return prop;
            }
        }
        return null;
    }

    /**
     * Returns whether this type has an identifier column (Entity and Aggregate only).
     */
    default boolean hasId() {
        return this instanceof Entity || this instanceof Aggregate;
    }

    /**
     * Returns the ID column name, or {@code null} for value objects.
     */
    default String idColumn() {
        if (this instanceof Entity e) {
            return e.id().columnName();
        } else if (this instanceof Aggregate a) {
            return a.id().columnName();
        }
        return null;
    }

    /**
     * Returns whether this type owns aggregate lifecycle.
     */
    default boolean ownsAggregateLifecycle() {
        return this instanceof Aggregate;
    }

    // ── TableSchema implementation ───────────────────────────────────────

    /**
     * Physical columns owned by this type's table.
     */
    default List<ColumnRef> columns() {
        List<ColumnRef> refs = new ArrayList<>();
        addColumnsRecursive(refs, "", "");
        return refs;
    }

    private void addColumnsRecursive(List<ColumnRef> refs, String prefix, String table) {
        // Add scalar columns
        for (Prop prop : properties()) {
            if (prop instanceof Prop.Scalar s) {
                String columnName = prefix.isEmpty() ? s.columnName() : prefix + "_" + s.columnName();
                refs.add(new ColumnRef.Simple(columnName, table.isEmpty() ? tableName() : table));
            } else if (prop instanceof Prop.Id id) {
                String columnName = prefix.isEmpty() ? id.columnName() : prefix + "_" + id.columnName();
                refs.add(new ColumnRef.Simple(columnName, table.isEmpty() ? tableName() : table));
            } else if (prop instanceof Prop.Version v) {
                String columnName = prefix.isEmpty() ? v.columnName() : prefix + "_" + v.columnName();
                refs.add(new ColumnRef.Simple(columnName, table.isEmpty() ? tableName() : table));
            } else if (prop instanceof Prop.ToOne toOne && toOne.fkColumn() != null) {
                String columnName = prefix.isEmpty() ? toOne.fkColumn() : prefix + "_" + toOne.fkColumn();
                refs.add(new ColumnRef.Simple(columnName, table.isEmpty() ? tableName() : table));
            } else if (prop instanceof Prop.Embedded emb && emb.definition() != null) {
                String newPrefix = prefix.isEmpty() ? emb.name() : prefix + "_" + emb.name();
                TypeDefinition target = emb.definition();
                target.addColumnsRecursive(refs, newPrefix, table.isEmpty() ? tableName() : table);
            }
        }
    }

    // ── ColumnResolver implementation ────────────────────────────────────

    /**
     * Resolves entity-rooted attribute paths to their column placements.
     */
    default Map<String, ColumnRef> columnRefs() {
        Map<String, ColumnRef> refs = new LinkedHashMap<>();
        addColumnRefsRecursive(refs, "");
        return refs;
    }

    private void addColumnRefsRecursive(Map<String, ColumnRef> refs, String prefix) {
        // Add ID column first (only at root level)
        if (prefix.isEmpty() && hasId()) {
            Prop.Id id = null;
            if (this instanceof Entity e) {
                id = e.id();
            } else if (this instanceof Aggregate a) {
                id = a.id();
            }
            if (id != null) {
                refs.put(id.name(), new ColumnRef.Simple(id.columnName(), tableName()));
            }
        }

        // Add scalar columns with optional prefix
        for (Prop prop : properties()) {
            if (prop instanceof Prop.Scalar s) {
                String propName = prefix.isEmpty() ? s.name() : prefix + "." + s.name();
                String columnPrefix = prefix.replace('.', '_');
                String columnName = prefix.isEmpty() ? s.columnName() : columnPrefix + "_" + s.columnName();
                refs.put(propName, new ColumnRef.Simple(columnName, tableName()));
            } else if (prop instanceof Prop.ToOne toOne && toOne.fkColumn() != null) {
                String propName = prefix.isEmpty() ? toOne.name() : prefix + "." + toOne.name();
                String columnPrefix = prefix.replace('.', '_');
                String columnName = prefix.isEmpty() ? toOne.fkColumn() : columnPrefix + "_" + toOne.fkColumn();
                refs.put(propName, new ColumnRef.ForeignKey(columnName, tableName()));
            } else if (prop instanceof Prop.Embedded emb && emb.definition() != null) {
                String newPrefix = prefix.isEmpty() ? emb.name() : prefix + "." + emb.name();
                TypeDefinition target = emb.definition();
                target.addColumnRefsRecursive(refs, newPrefix);
            }
        }
    }

    /**
     * Resolves an entity-rooted dot path to its placement.
     */
    default ColumnPlacement resolve(String path) {
        String[] segments = path.split("\\.");
        List<JoinEdge> edges = new ArrayList<>();
        TypeDefinition current = this;
        StringBuilder columnPrefix = new StringBuilder();

        // Walk through all segments except the last to build edges and track embedded prefix
        for (int i = 0; i < segments.length - 1; i++) {
            String segment = segments[i];
            Prop prop = current.findProperty(segment);

            if (prop == null) {
                throw new UnknownAttributePathException(path,
                        "Cannot navigate through non-relation property: " + segment);
            }

            if (prop instanceof Prop.ToOne toOne) {
                // To-one: owner table has FK pointing to child table's ID
                ColumnRef.ForeignKey ownerFk = new ColumnRef.ForeignKey(
                        toOne.fkColumn(), current.tableName());
                ColumnRef.Simple childId = new ColumnRef.Simple(
                        toOne.target().id().columnName(), toOne.target().tableName());
                edges.add(new JoinEdge.ToOne(ownerFk, childId));
                current = toOne.target();
            } else if (prop instanceof Prop.ToMany toMany) {
                // To-many: child table has FK pointing back to owner table's ID
                ColumnRef.Simple ownerId = new ColumnRef.Simple(
                        current.idColumn(), current.tableName());
                ColumnRef.ForeignKey childFk = new ColumnRef.ForeignKey(
                        toMany.fkColumn(), toMany.target().tableName());
                edges.add(new JoinEdge.ToMany(ownerId, childFk));
                current = toMany.target();
            } else if (prop instanceof Prop.ElementCollection ec) {
                // Element collection: child table has FK pointing back to owner table's ID
                ColumnRef.Simple ownerId = new ColumnRef.Simple(
                        current.idColumn(), current.tableName());
                ColumnRef.ForeignKey childFk = new ColumnRef.ForeignKey(
                        ec.fkColumn(), ec.childTable());
                edges.add(new JoinEdge.ToMany(ownerId, childFk));
                // Element collections don't have a TypeDefinition target, so we can't navigate further
                throw new UnknownAttributePathException(path,
                        "Cannot navigate through element collection: " + segment);
            } else if (prop instanceof Prop.Embedded emb) {
                // Embedded relations don't create join edges - they're flattened into the same table
                if (columnPrefix.length() > 0) {
                    columnPrefix.append('_');
                }
                columnPrefix.append(segment);
                current = emb.definition();
            } else if (prop instanceof Prop.Composition comp) {
                // Composition is like ToMany but owned
                ColumnRef.Simple ownerId = new ColumnRef.Simple(
                        current.idColumn(), current.tableName());
                ColumnRef.ForeignKey childFk = new ColumnRef.ForeignKey(
                        comp.target().idColumn(), comp.target().tableName());
                edges.add(new JoinEdge.ToMany(ownerId, childFk));
                current = comp.target();
            } else {
                // Scalar, Id, Version cannot be navigated through
                throw new UnknownAttributePathException(path,
                        "Cannot navigate through scalar property: " + segment);
            }
        }

        // Resolve the final segment
        String lastSegment = segments[segments.length - 1];
        Prop lastProp = current.findProperty(lastSegment);

        if (lastProp == null) {
            throw new UnknownAttributePathException(path,
                    "Property not found: " + lastSegment);
        }

        // Build the column ref
        ColumnRef leaf;
        if (lastProp instanceof Prop.Scalar s) {
            String columnName = columnPrefix.length() > 0
                    ? columnPrefix.toString() + "_" + s.columnName()
                    : s.columnName();
            String tableName = current.tableName() != null ? current.tableName() : this.tableName();
            leaf = new ColumnRef.Simple(columnName, tableName);
        } else if (lastProp instanceof Prop.Id id) {
            String columnName = columnPrefix.length() > 0
                    ? columnPrefix.toString() + "_" + id.columnName()
                    : id.columnName();
            String tableName = current.tableName() != null ? current.tableName() : this.tableName();
            leaf = new ColumnRef.Simple(columnName, tableName);
        } else if (lastProp instanceof Prop.Version v) {
            String columnName = columnPrefix.length() > 0
                    ? columnPrefix.toString() + "_" + v.columnName()
                    : v.columnName();
            String tableName = current.tableName() != null ? current.tableName() : this.tableName();
            leaf = new ColumnRef.Simple(columnName, tableName);
        } else if (lastProp instanceof Prop.ToOne toOne) {
            String columnName = columnPrefix.length() > 0
                    ? columnPrefix.toString() + "_" + toOne.fkColumn()
                    : toOne.fkColumn();
            String tableName = current.tableName() != null ? current.tableName() : this.tableName();
            leaf = new ColumnRef.ForeignKey(columnName, tableName);
        } else {
            throw new UnknownAttributePathException(path,
                    "Terminal property is not a column: " + lastSegment);
        }

        return new ColumnPlacement(leaf, edges);
    }

    /**
     * Whether this entity-rooted path starts with a to-many relation or element collection.
     */
    default boolean isToManyPath(String path) {
        int dot = path.indexOf('.');
        String pathHead = dot < 0 ? path : path.substring(0, dot);
        Prop prop = findProperty(pathHead);
        return prop instanceof Prop.ToMany
                || prop instanceof Prop.ElementCollection;
    }

    // ── Nested Entity record ─────────────────────────────────────────────

    /**
     * An entity type with its own table, ID, and optional version column.
     */
    record Entity(
            String typeName,
            String tableName,
            Prop.Id id,
            Prop.Version version, // optional
            List<Prop.Scalar> scalars,
            List<Prop.Embedded> embedded,
            List<Prop.ToOne> toOneEntities,
            List<Prop.ToMany> toManyEntities,
            List<Prop.ElementCollection> elementCollections
    ) implements TypeDefinition {

        public Entity {
            scalars = List.copyOf(scalars);
            embedded = List.copyOf(embedded);
            toOneEntities = List.copyOf(toOneEntities);
            toManyEntities = List.copyOf(toManyEntities);
            elementCollections = List.copyOf(elementCollections);
        }

        @Override
        public List<Prop> properties() {
            List<Prop> all = new ArrayList<>();
            all.add(id);
            if (version != null) all.add(version);
            all.addAll(scalars);
            all.addAll(embedded);
            all.addAll(toOneEntities);
            all.addAll(toManyEntities);
            all.addAll(elementCollections);
            return all;
        }
    }

    // ── Nested Aggregate record ──────────────────────────────────────────

    /**
     * An aggregate root with its own table, ID, version, and owned child aggregates.
     */
    record Aggregate(
            String typeName,
            String tableName,
            Prop.Id id,
            Prop.Version version,
            List<Prop.Scalar> scalars,
            List<Prop.Embedded> embedded,
            List<Prop.ToOne> toOneEntities,
            List<Prop.ToMany> toManyEntities,
            List<Prop.ElementCollection> elementCollections,
            List<Prop.Composition> compositions // owned child aggregates
    ) implements TypeDefinition {

        public Aggregate {
            scalars = List.copyOf(scalars);
            embedded = List.copyOf(embedded);
            toOneEntities = List.copyOf(toOneEntities);
            toManyEntities = List.copyOf(toManyEntities);
            elementCollections = List.copyOf(elementCollections);
            compositions = List.copyOf(compositions);
        }

        @Override
        public List<Prop> properties() {
            List<Prop> all = new ArrayList<>();
            all.add(id);
            if (version != null) all.add(version);
            all.addAll(scalars);
            all.addAll(embedded);
            all.addAll(toOneEntities);
            all.addAll(toManyEntities);
            all.addAll(elementCollections);
            all.addAll(compositions);
            return all;
        }
    }

    // ── Nested ValueObject record ────────────────────────────────────────

    /**
     * A value object without its own table or ID, used for embedding.
     */
    record ValueObject(
            String typeName,
            List<Prop.Scalar> scalars,
            List<Prop.Embedded> embedded,
            List<Prop.ElementCollection> elementCollections
    ) implements TypeDefinition {

        public ValueObject {
            scalars = List.copyOf(scalars);
            embedded = List.copyOf(embedded);
            elementCollections = List.copyOf(elementCollections);
        }

        @Override
        public String tableName() {
            return null; // Value objects have no table of their own
        }

        @Override
        public List<Prop> properties() {
            List<Prop> all = new ArrayList<>();
            all.addAll(scalars);
            all.addAll(embedded);
            all.addAll(elementCollections);
            return all;
        }
    }

    // ── Nested Prop sealed interface ─────────────────────────────────────

    /**
     * Sealed interface for all property kinds.
     */
    sealed interface Prop permits Prop.Scalar, Prop.Embedded, Prop.ToOne,
                                      Prop.ToMany, Prop.ElementCollection,
                                      Prop.Composition, Prop.Id, Prop.Version {

        /** The Java property name. */
        String name();

        /** The fully-qualified Java type name. */
        String javaType();

        /** The kind of this property. */
        PropKind kind();

        enum PropKind {
            SCALAR, EMBEDDED, TO_ONE, TO_MANY, ELEMENT_COLLECTION, COMPOSITION, ID, VERSION
        }

        // ── Scalar property ──────────────────────────────────────────────

        /**
         * A simple scalar property mapped to a column.
         */
        record Scalar(String name, String javaType, String columnName, boolean nullable) implements Prop {
            @Override
            public PropKind kind() {
                return PropKind.SCALAR;
            }
        }

        // ── Embedded property ────────────────────────────────────────────

        /**
         * An embedded value object flattened into the parent table.
         */
        record Embedded(String name, String javaType, ValueObject definition, String prefix) implements Prop {
            @Override
            public PropKind kind() {
                return PropKind.EMBEDDED;
            }
        }

        // ── ToOne property ───────────────────────────────────────────────

        /**
         * A to-one foreign key reference (ManyToOne or OneToOne owning side).
         */
        record ToOne(String name, String javaType, Entity target, String fkColumn, boolean backReference) implements Prop {
            @Override
            public PropKind kind() {
                return PropKind.TO_ONE;
            }
        }

        // ── ToMany property ──────────────────────────────────────────────

        /**
         * A to-many entity collection stored in the target table (OneToMany or ManyToMany).
         */
        record ToMany(String name, String javaType, Entity target, String fkColumn, String mappedBy) implements Prop {
            @Override
            public PropKind kind() {
                return PropKind.TO_MANY;
            }
        }

        // ── ElementCollection property ───────────────────────────────────

        /**
         * A value object element collection stored in a child table.
         */
        record ElementCollection(String name, String elementType, String childTable, String fkColumn) implements Prop {
            @Override
            public String javaType() {
                return elementType;
            }

            @Override
            public PropKind kind() {
                return PropKind.ELEMENT_COLLECTION;
            }
        }

        // ── Composition property ─────────────────────────────────────────

        /**
         * An owned child aggregate (cascade lifecycle).
         */
        record Composition(String name, String javaType, Aggregate target, Cascade cascade) implements Prop {
            @Override
            public PropKind kind() {
                return PropKind.COMPOSITION;
            }

            public enum Cascade {
                ALL, PERSIST, MERGE, REMOVE
            }
        }

        // ── Id property ──────────────────────────────────────────────────

        /**
         * The primary key property.
         */
        record Id(String name, String javaType, String columnName, GeneratedType generated) implements Prop {
            @Override
            public PropKind kind() {
                return PropKind.ID;
            }

            public enum GeneratedType {
                NONE, AUTO, IDENTITY, SEQUENCE, UUID
            }
        }

        // ── Version property ─────────────────────────────────────────────

        /**
         * The optimistic locking version property.
         */
        record Version(String name, String javaType, String columnName) implements Prop {
            @Override
            public PropKind kind() {
                return PropKind.VERSION;
            }
        }
    }
}
