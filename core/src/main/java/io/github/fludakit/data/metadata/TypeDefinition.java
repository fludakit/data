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
public sealed interface TypeDefinition extends TableSchema
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
            return e.id() != null ? e.id().columnName() : null;
        } else if (this instanceof Aggregate a) {
            return a.id() != null ? a.id().columnName() : null;
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
            } else if (prop instanceof Prop.Association assoc && assoc.fkColumn() != null) {
                String columnName = prefix.isEmpty() ? assoc.fkColumn() : prefix + "_" + assoc.fkColumn();
                refs.add(new ColumnRef.Simple(columnName, table.isEmpty() ? tableName() : table));
            } else if (prop instanceof Prop.EntityCollection ec) {
                // EntityCollections in VOs are stored in child tables - skip for column listing
                // They will be loaded separately via hydration
            } else if (prop instanceof Prop.ElementCollection ec) {
                // ElementCollections in VOs are stored in child tables - skip for column listing
                // They will be loaded separately via hydration
            } else if (prop instanceof Prop.Embedded emb && emb.definition() != null) {
                String newPrefix = prefix.isEmpty() ? emb.name() : prefix + "_" + emb.name();
                TypeDefinition target = emb.definition();
                target.addColumnsRecursive(refs, newPrefix, table.isEmpty() ? tableName() : table);
            }
        }
    }

    // ── Entity-rooted path resolution ─────────────────────────────────────

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
            } else if (prop instanceof Prop.Association assoc && assoc.fkColumn() != null) {
                String propName = prefix.isEmpty() ? assoc.name() : prefix + "." + assoc.name();
                String columnPrefix = prefix.replace('.', '_');
                String columnName = prefix.isEmpty() ? assoc.fkColumn() : columnPrefix + "_" + assoc.fkColumn();
                refs.put(propName, new ColumnRef.ForeignKey(columnName, tableName()));
            } else if (prop instanceof Prop.EntityCollection ec) {
                // EntityCollections in VOs don't contribute to column refs - they're separate tables
            } else if (prop instanceof Prop.ElementCollection ec) {
                // ElementCollections in VOs don't contribute to column refs - they're separate tables
            } else if (prop instanceof Prop.Embedded emb && emb.definition() != null) {
                String newPrefix = prefix.isEmpty() ? emb.name() : prefix + "." + emb.name();
                TypeDefinition target = emb.definition();
                target.addColumnRefsRecursive(refs, newPrefix);
            }
        }
    }

    /**
     * Resolves an entity-rooted dot path to its placement.
     *
     * @throws IllegalArgumentException when the path is unknown, names a write-only back-reference,
     *                                  or would walk a cycle
     */
    default ColumnPlacement resolve(String path) {
        return new PathResolver().resolve(this, path);
    }

    /**
     * Whether this entity-rooted path starts with a to-many relation or element collection.
     */
    default boolean isToManyPath(String path) {
        return new PathResolver().isToManyPath(this, path);
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
            List<Prop.Association> associations,
            List<Prop.EntityCollection> entityCollections,
            List<Prop.ElementCollection> elementCollections
    ) implements TypeDefinition {

        public Entity {
            scalars = List.copyOf(scalars);
            embedded = List.copyOf(embedded);
            associations = List.copyOf(associations);
            entityCollections = List.copyOf(entityCollections);
            elementCollections = List.copyOf(elementCollections);
        }

        @Override
        public List<Prop> properties() {
            return entityProperties(id, version, scalars, embedded, associations, entityCollections, elementCollections);
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
            List<Prop.Association> associations,
            List<Prop.EntityCollection> entityCollections,
            List<Prop.ElementCollection> elementCollections
    ) implements TypeDefinition {

        public Aggregate {
            scalars = List.copyOf(scalars);
            embedded = List.copyOf(embedded);
            associations = List.copyOf(associations);
            entityCollections = List.copyOf(entityCollections);
            elementCollections = List.copyOf(elementCollections);
        }

        @Override
        public List<Prop> properties() {
            return entityProperties(id, version, scalars, embedded, associations, entityCollections, elementCollections);
        }
    }

    // ── Nested ValueObject record ────────────────────────────────────────

    /**
     * A value object without its own table or ID, used for embedding.
     * Value objects can contain scalars, embedded VOs, element collections, AND associations to entities.
     */
    record ValueObject(
            String typeName,
            List<Prop.Scalar> scalars,
            List<Prop.Embedded> embedded,
            List<Prop.Association> associations,
            List<Prop.EntityCollection> entityCollections,
            List<Prop.ElementCollection> elementCollections
    ) implements TypeDefinition {

        public ValueObject {
            scalars = List.copyOf(scalars);
            embedded = List.copyOf(embedded);
            associations = List.copyOf(associations);
            entityCollections = List.copyOf(entityCollections);
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
            all.addAll(associations);
            all.addAll(entityCollections);
            all.addAll(elementCollections);
            return all;
        }
    }

    // ── Shared entity properties helper ───────────────────────────────────

    /**
     * The declaration-order property list of an entity-shaped type (Entity or Aggregate): the id,
     * the optional version, then scalars, embedded VOs, associations, and collections.
     */
    private static List<Prop> entityProperties(Prop.Id id, Prop.Version version,
                                                List<Prop.Scalar> scalars, List<Prop.Embedded> embedded,
                                                List<Prop.Association> associations,
                                                List<Prop.EntityCollection> entityCollections,
                                                List<Prop.ElementCollection> elementCollections) {
        List<Prop> all = new ArrayList<>();
        all.add(id);
        if (version != null) all.add(version);
        all.addAll(scalars);
        all.addAll(embedded);
        all.addAll(associations);
        all.addAll(entityCollections);
        all.addAll(elementCollections);
        return all;
    }

    // ── Nested Prop sealed interface ─────────────────────────────────────

    /**
     * Sealed interface for all property kinds.
     */
    sealed interface Prop permits Prop.Scalar, Prop.Embedded, Prop.Association,
                                  Prop.EntityCollection, Prop.ElementCollection,
                                  Prop.Id, Prop.Version {

        /** The Java property name. */
        String name();

        /** The fully-qualified Java type name. */
        String javaType();

        // ── Scalar property ──────────────────────────────────────────────

        /**
         * A simple scalar property mapped to a column.
         */
        record Scalar(String name, String javaType, String columnName, boolean nullable) implements Prop {
        }

        // ── Embedded property ────────────────────────────────────────────

        /**
         * An embedded value object flattened into the parent table.
         */
        record Embedded(String name, String javaType, ValueObject definition, String prefix) implements Prop {
        }

        // ── Association property ─────────────────────────────────────────

        /**
         * A to-one association (ManyToOne, OneToOne, or Composition when composition=true).
         */
        record Association(String name, String javaType, Entity definition, String fkColumn,
                           boolean backReference, boolean composition, Cascade cascade) implements Prop {

            public enum Cascade {
                ALL, PERSIST, MERGE, REMOVE
            }
        }

        // ── EntityCollection property ────────────────────────────────────

        /**
         * A to-many entity collection stored in the child table (OneToMany or ManyToMany).
         * The definition may be an Entity or an Aggregate (when the target is itself an aggregate root).
         */
        record EntityCollection(String name, String javaType, TypeDefinition definition, String fkColumn, String mappedBy) implements Prop {
        }

        // ── ElementCollection property ───────────────────────────────────

        /**
         * A value object element collection stored in a child table.
         */
        record ElementCollection(String name, String javaType, ValueObject definition, String childTable, String fkColumn) implements Prop {
        }

        // ── Id property ──────────────────────────────────────────────────

        /**
         * The primary key property.
         */
        record Id(String name, String javaType, String columnName, GeneratedType generated) implements Prop {

            public enum GeneratedType {
                NONE, AUTO, IDENTITY, SEQUENCE, UUID
            }
        }

        // ── Version property ─────────────────────────────────────────────

        /**
         * The optimistic locking version property.
         */
        record Version(String name, String javaType, String columnName) implements Prop {
        }
    }
}
