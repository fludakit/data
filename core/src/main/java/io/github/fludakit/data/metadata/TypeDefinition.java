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
        permits TypeDefinition.IdentityAware,
                TypeDefinition.ValueObject {

    /** The fully-qualified Java type name. */
    String typeName();

    /** The physical table name, or {@code null} for value objects without their own table. */
    String tableName();

    /** All properties of this type, in declaration order. */
    List<Prop> properties();

    /** The identifier property, or {@code null} for value objects. */
    Prop.Id id();

    /** Dispatches this type to a {@link Visitor}. */
    <R> R accept(Visitor<R> visitor);

    /** Returns an {@link Entity} view of this type, or {@code null} for value objects. */
    TypeDefinition.Entity asEntity();

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
    boolean hasId();

    /**
     * Returns the ID column name, or {@code null} for value objects.
     */
    String idColumn();

    /**
     * Returns whether this type owns aggregate lifecycle.
     */
    boolean ownsAggregateLifecycle();

    // ── TableSchema implementation ───────────────────────────────────────

    /**
     * Physical columns owned by this type's table.
     */
    default List<ColumnRef> columns() {
        List<ColumnRef> refs = new ArrayList<>();
        ColumnsCollector collector = new ColumnsCollector(refs, "", tableName());
        for (Prop prop : properties()) {
            prop.accept(collector);
        }
        return refs;
    }

    static final class ColumnsCollector implements Prop.Visitor<Void> {
        private final List<ColumnRef> refs;
        private final String prefix;
        private final String table;

        ColumnsCollector(List<ColumnRef> refs, String prefix, String table) {
            this.refs = refs;
            this.prefix = prefix;
            this.table = table;
        }

        @Override
        public Void visitScalar(Prop.Scalar s) {
            refs.add(new ColumnRef.Simple(qualified(s.columnName()), table));
            return null;
        }

        @Override
        public Void visitId(Prop.Id id) {
            refs.add(new ColumnRef.Simple(qualified(id.columnName()), table));
            return null;
        }

        @Override
        public Void visitVersion(Prop.Version v) {
            refs.add(new ColumnRef.Simple(qualified(v.columnName()), table));
            return null;
        }

        @Override
        public Void visitAssociation(Prop.Association assoc) {
            if (assoc.fkColumn() != null) {
                refs.add(new ColumnRef.Simple(qualified(assoc.fkColumn()), table));
            }
            return null;
        }

        @Override
        public Void visitEmbedded(Prop.Embedded emb) {
            if (emb.definition() != null) {
                ColumnsCollector child = new ColumnsCollector(refs, qualified(emb.name()), table);
                for (Prop prop : emb.definition().properties()) {
                    prop.accept(child);
                }
            }
            return null;
        }

        @Override
        public Void visitEntityCollection(Prop.EntityCollection ec) {
            return null;
        }

        @Override
        public Void visitElementCollection(Prop.ElementCollection ec) {
            return null;
        }

        private String qualified(String name) {
            return prefix.isEmpty() ? name : prefix + "_" + name;
        }
    }

    // ── Entity-rooted path resolution ─────────────────────────────────────

    /**
     * Resolves entity-rooted attribute paths to their column placements.
     */
    default Map<String, ColumnRef> columnRefs() {
        Map<String, ColumnRef> refs = new LinkedHashMap<>();
        // Add ID column first (only at root level)
        if (hasId()) {
            Prop.Id id = id();
            if (id != null) {
                refs.put(id.name(), new ColumnRef.Simple(id.columnName(), tableName()));
            }
        }
        ColumnRefCollector collector = new ColumnRefCollector(refs, tableName(), "");
        for (Prop prop : properties()) {
            prop.accept(collector);
        }
        return refs;
    }

    static final class ColumnRefCollector implements Prop.Visitor<Void> {
        private final Map<String, ColumnRef> refs;
        private final String table;
        private final String prefix;

        ColumnRefCollector(Map<String, ColumnRef> refs, String table, String prefix) {
            this.refs = refs;
            this.table = table;
            this.prefix = prefix;
        }

        @Override
        public Void visitScalar(Prop.Scalar s) {
            refs.put(qualified(s.name()), new ColumnRef.Simple(column(s.columnName()), table));
            return null;
        }

        @Override
        public Void visitAssociation(Prop.Association assoc) {
            if (assoc.fkColumn() != null) {
                refs.put(qualified(assoc.name()), new ColumnRef.ForeignKey(column(assoc.fkColumn()), table));
            }
            return null;
        }

        @Override
        public Void visitVersion(Prop.Version v) {
            // The version is a root-level column; key it by property name like the id.
            if (prefix.isEmpty()) {
                refs.put(v.name(), new ColumnRef.Simple(v.columnName(), table));
            }
            return null;
        }

        @Override
        public Void visitEmbedded(Prop.Embedded emb) {
            if (emb.definition() != null) {
                ColumnRefCollector child = new ColumnRefCollector(refs, emb.definition().tableName(), qualified(emb.name()));
                for (Prop prop : emb.definition().properties()) {
                    prop.accept(child);
                }
            }
            return null;
        }

        @Override
        public Void visitId(Prop.Id id) {
            return null;
        }

        @Override
        public Void visitEntityCollection(Prop.EntityCollection ec) {
            return null;
        }

        @Override
        public Void visitElementCollection(Prop.ElementCollection ec) {
            return null;
        }

        private String qualified(String name) {
            return prefix.isEmpty() ? name : prefix + "." + name;
        }

        private String column(String name) {
            String columnPrefix = prefix.replace('.', '_');
            return prefix.isEmpty() ? name : columnPrefix + "_" + name;
        }
    }

    /**
     * Resolves an entity-rooted dot path to its placement.
     *
     * @throws IllegalArgumentException when the path is unknown, names a write-only back-reference,
     *                                  or would walk a cycle
     */
    default ColumnPlacement resolve(String path) {
        String[] segments = path.split("\\.");
        List<JoinEdge> edges = new ArrayList<>();
        StringBuilder columnPrefix = new StringBuilder();
        String elementCollectionTable = null; // Track the child table when navigating through ElementCollection
        TypeDefinition current = this;

        // Walk through all segments except the last to build edges and track embedded prefix
        for (int i = 0; i < segments.length - 1; i++) {
            String segment = segments[i];
            Prop prop = current.findProperty(segment);

            if (prop == null) {
                throw new UnknownAttributePathException(path,
                        "Cannot navigate through non-relation property: " + segment);
            }

            NavigationStep step = prop.accept(new NavigationVisitor(current, edges, columnPrefix, segment, path));
            current = step.next();
            if (step.elementCollectionTable() != null) {
                elementCollectionTable = step.elementCollectionTable();
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
        ColumnRef leaf = lastProp.accept(new LeafColumnVisitor(
                columnPrefix.toString(), resolveTable(current, elementCollectionTable), path, lastSegment));

        return new ColumnPlacement(leaf, edges);
    }

    /**
     * Whether this entity-rooted path starts with a to-many relation or element collection.
     */
    default boolean isToManyPath(String path) {
        int dot = path.indexOf('.');
        String pathHead = dot < 0 ? path : path.substring(0, dot);
        Prop prop = findProperty(pathHead);
        return prop instanceof Prop.EntityCollection
                || prop instanceof Prop.ElementCollection;
    }

    private String resolveTable(TypeDefinition current, String elementCollectionTable) {
        if (elementCollectionTable != null) {
            return elementCollectionTable;
        }
        return current.tableName() != null ? current.tableName() : tableName();
    }

    /** A single navigation step: the next type and, for element collections, the child table. */
    static final class NavigationStep {
        private final TypeDefinition next;
        private final String elementCollectionTable;

        NavigationStep(TypeDefinition next, String elementCollectionTable) {
            this.next = next;
            this.elementCollectionTable = elementCollectionTable;
        }

        TypeDefinition next() {
            return next;
        }

        String elementCollectionTable() {
            return elementCollectionTable;
        }
    }

    /**
     * Dispatches a non-terminal path segment to its navigation outcome: the next type to walk,
     * the join edge to record (associations and collections), and any embedded prefix to append.
     */
    static final class NavigationVisitor implements Prop.Visitor<NavigationStep> {
        private final TypeDefinition current;
        private final List<JoinEdge> edges;
        private final StringBuilder columnPrefix;
        private final String segment;
        private final String path;

        NavigationVisitor(TypeDefinition current, List<JoinEdge> edges, StringBuilder columnPrefix, String segment, String path) {
            this.current = current;
            this.edges = edges;
            this.columnPrefix = columnPrefix;
            this.segment = segment;
            this.path = path;
        }

        @Override
        public NavigationStep visitAssociation(Prop.Association assoc) {
            // To-one: owner table has FK pointing to child table's ID
            ColumnRef.ForeignKey ownerFk = new ColumnRef.ForeignKey(assoc.fkColumn(), current.tableName());
            ColumnRef.Simple childId = new ColumnRef.Simple(assoc.definition().id().columnName(), assoc.definition().tableName());
            edges.add(new JoinEdge.ToOne(ownerFk, childId));
            return new NavigationStep(assoc.definition(), null);
        }

        @Override
        public NavigationStep visitEntityCollection(Prop.EntityCollection ec) {
            // To-many: child table has FK pointing back to owner table's ID
            ColumnRef.Simple ownerId = new ColumnRef.Simple(current.idColumn(), current.tableName());
            ColumnRef.ForeignKey childFk = new ColumnRef.ForeignKey(ec.fkColumn(), ec.definition().tableName());
            edges.add(new JoinEdge.ToMany(ownerId, childFk));
            return new NavigationStep(ec.definition(), null);
        }

        @Override
        public NavigationStep visitElementCollection(Prop.ElementCollection ec) {
            // Element collection: child table has FK pointing back to owner table's ID
            ColumnRef.Simple ownerId = new ColumnRef.Simple(current.idColumn(), current.tableName());
            ColumnRef.ForeignKey childFk = new ColumnRef.ForeignKey(ec.fkColumn(), ec.childTable());
            edges.add(new JoinEdge.ToMany(ownerId, childFk));
            // Continue navigation into the element type (ValueObject)
            if (ec.definition() == null) {
                throw new UnknownAttributePathException(path,
                        "Element collection target type not resolved: " + segment);
            }
            return new NavigationStep(ec.definition(), ec.childTable());
        }

        @Override
        public NavigationStep visitEmbedded(Prop.Embedded emb) {
            // Embedded relations don't create join edges - they're flattened into the same table
            if (columnPrefix.length() > 0) {
                columnPrefix.append('_');
            }
            columnPrefix.append(segment);
            return new NavigationStep(emb.definition(), null);
        }

        @Override
        public NavigationStep visitScalar(Prop.Scalar s) {
            throw new UnknownAttributePathException(path, "Cannot navigate through scalar property: " + segment);
        }

        @Override
        public NavigationStep visitId(Prop.Id id) {
            throw new UnknownAttributePathException(path, "Cannot navigate through scalar property: " + segment);
        }

        @Override
        public NavigationStep visitVersion(Prop.Version v) {
            throw new UnknownAttributePathException(path, "Cannot navigate through scalar property: " + segment);
        }
    }

    static final class LeafColumnVisitor implements Prop.Visitor<ColumnRef> {
        private final String columnPrefix;
        private final String tableName;
        private final String path;
        private final String terminalName;

        LeafColumnVisitor(String columnPrefix, String tableName, String path, String terminalName) {
            this.columnPrefix = columnPrefix;
            this.tableName = tableName;
            this.path = path;
            this.terminalName = terminalName;
        }

        @Override
        public ColumnRef visitScalar(Prop.Scalar s) {
            return new ColumnRef.Simple(qualified(s.columnName()), tableName);
        }

        @Override
        public ColumnRef visitId(Prop.Id id) {
            return new ColumnRef.Simple(qualified(id.columnName()), tableName);
        }

        @Override
        public ColumnRef visitVersion(Prop.Version v) {
            return new ColumnRef.Simple(qualified(v.columnName()), tableName);
        }

        @Override
        public ColumnRef visitAssociation(Prop.Association assoc) {
            return new ColumnRef.ForeignKey(qualified(assoc.fkColumn()), tableName);
        }

        @Override
        public ColumnRef visitEmbedded(Prop.Embedded emb) {
            throw new UnknownAttributePathException(path, "Terminal property is not a column: " + terminalName);
        }

        @Override
        public ColumnRef visitEntityCollection(Prop.EntityCollection ec) {
            throw new UnknownAttributePathException(path, "Terminal property is not a column: " + terminalName);
        }

        @Override
        public ColumnRef visitElementCollection(Prop.ElementCollection ec) {
            throw new UnknownAttributePathException(path, "Terminal property is not a column: " + terminalName);
        }

        private String qualified(String name) {
            return columnPrefix.isEmpty() ? name : columnPrefix + "_" + name;
        }
    }

    // ── Identity-aware types (Entity / Aggregate) ─────────────────────────

    /**
     * The shared shape of entity and aggregate-root types: a table-backed type with an identity,
     * an optional version, and mapped properties. Value objects are not identity-aware.
     */
    sealed interface IdentityAware extends TypeDefinition
            permits TypeDefinition.Entity, TypeDefinition.Aggregate {

        /** The optimistic-locking version property, or {@code null}. */
        Prop.Version version();

        List<Prop.Scalar> scalars();

        List<Prop.Embedded> embedded();

        List<Prop.Association> associations();

        List<Prop.EntityCollection> entityCollections();

        List<Prop.ElementCollection> elementCollections();
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
    ) implements IdentityAware {

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

        @Override
        public boolean hasId() {
            return true;
        }

        @Override
        public String idColumn() {
            return id != null ? id.columnName() : null;
        }

        @Override
        public boolean ownsAggregateLifecycle() {
            return false;
        }

        @Override
        public <R> R accept(Visitor<R> visitor) {
            return visitor.visitEntity(this);
        }

        @Override
        public TypeDefinition.Entity asEntity() {
            return this;
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
    ) implements IdentityAware {

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

        @Override
        public boolean hasId() {
            return true;
        }

        @Override
        public String idColumn() {
            return id != null ? id.columnName() : null;
        }

        @Override
        public boolean ownsAggregateLifecycle() {
            return true;
        }

        @Override
        public <R> R accept(Visitor<R> visitor) {
            return visitor.visitAggregate(this);
        }

        /**
         * Returns an {@link Entity} view of this aggregate: the same table, id, version, scalars,
         * and embedded value objects, but with no associations or collections — the FK-reference
         * shape used when an aggregate root is referenced by another type.
         */
        @Override
        public TypeDefinition.Entity asEntity() {
            return new TypeDefinition.Entity(typeName, tableName, id, version, scalars, embedded,
                    List.of(), List.of(), List.of());
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
        public Prop.Id id() {
            return null; // Value objects have no identity
        }

        @Override
        public boolean hasId() {
            return false;
        }

        @Override
        public String idColumn() {
            return null;
        }

        @Override
        public boolean ownsAggregateLifecycle() {
            return false;
        }

        @Override
        public <R> R accept(Visitor<R> visitor) {
            return visitor.visitValueObject(this);
        }

        @Override
        public TypeDefinition.Entity asEntity() {
            return null;
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

        /** Dispatches this property to a {@link Visitor}. */
        <R> R accept(Visitor<R> visitor);

        // ── Scalar property ──────────────────────────────────────────────

        /**
         * A simple scalar property mapped to a column.
         */
        record Scalar(String name, String javaType, String columnName, boolean nullable) implements Prop {
            @Override
            public <R> R accept(Visitor<R> visitor) {
                return visitor.visitScalar(this);
            }
        }

        // ── Embedded property ────────────────────────────────────────────

        /**
         * An embedded value object flattened into the parent table.
         */
        record Embedded(String name, String javaType, ValueObject definition, String prefix) implements Prop {
            @Override
            public <R> R accept(Visitor<R> visitor) {
                return visitor.visitEmbedded(this);
            }
        }

        // ── Association property ─────────────────────────────────────────

        /**
         * A to-one association (ManyToOne, OneToOne, or Composition when composition=true).
         */
        record Association(String name, String javaType, Entity definition, String fkColumn,
                           boolean backReference, boolean composition, Cascade cascade) implements Prop {

            @Override
            public <R> R accept(Visitor<R> visitor) {
                return visitor.visitAssociation(this);
            }

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
            @Override
            public <R> R accept(Visitor<R> visitor) {
                return visitor.visitEntityCollection(this);
            }
        }

        // ── ElementCollection property ───────────────────────────────────

        /**
         * A value object element collection stored in a child table.
         */
        record ElementCollection(String name, String javaType, ValueObject definition, String childTable, String fkColumn) implements Prop {
            @Override
            public <R> R accept(Visitor<R> visitor) {
                return visitor.visitElementCollection(this);
            }
        }

        // ── Id property ──────────────────────────────────────────────────

        /**
         * The primary key property.
         */
        record Id(String name, String javaType, String columnName, GeneratedType generated) implements Prop {

            @Override
            public <R> R accept(Visitor<R> visitor) {
                return visitor.visitId(this);
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
            public <R> R accept(Visitor<R> visitor) {
                return visitor.visitVersion(this);
            }
        }

        /**
         * Visitor over the {@link Prop} kinds, dispatched via {@link Prop#accept(Visitor)}.
         */
        interface Visitor<R> {
            R visitScalar(Scalar prop);
            R visitId(Id prop);
            R visitVersion(Version prop);
            R visitAssociation(Association prop);
            R visitEmbedded(Embedded prop);
            R visitEntityCollection(EntityCollection prop);
            R visitElementCollection(ElementCollection prop);
        }
    }

    /**
     * Visitor over the {@link TypeDefinition} kinds (entity, aggregate root, value object),
     * dispatched via {@link TypeDefinition#accept(Visitor)}.
     */
    interface Visitor<R> {
        R visitEntity(Entity entity);
        R visitAggregate(Aggregate aggregate);
        R visitValueObject(ValueObject valueObject);
    }
}
