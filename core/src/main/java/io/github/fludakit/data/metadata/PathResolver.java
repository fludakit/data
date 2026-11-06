package io.github.fludakit.data.metadata;

import java.util.ArrayList;
import java.util.List;

/**
 * Resolves entity-rooted attribute paths to their {@link ColumnPlacement}: the terminal column and
 * the {@link JoinEdge}s that must be crossed to reach it.
 *
 * <p>This is the entity-property → column mapping. It is a separate concern from {@link TableSchema}
 * (the physical shape of one table) and from {@link ColumnRef} (a placement), and it is the only one
 * of the three that knows about property names.</p>
 *
 * <p>Stateless: the resolved root type travels as an argument, so the same resolver serves any
 * entity being resolved.</p>
 */
public final class PathResolver {

    /**
     * Resolves an entity-rooted dot path to its placement.
     *
     * @param root the entity (or aggregate root) the path starts from
     * @param path the dot-separated logical path, e.g. {@code "author.name"} or {@code "address.zipcode"}
     * @return the terminal column plus the edges crossed, empty edges for a root-table column
     * @throws IllegalArgumentException when the path is unknown, names a write-only back-reference,
     *                                  or would walk a cycle
     */
    public ColumnPlacement resolve(TypeDefinition root, String path) {
        String[] segments = path.split("\\.");
        List<JoinEdge> edges = new ArrayList<>();
        TypeDefinition current = root;
        StringBuilder columnPrefix = new StringBuilder();
        String elementCollectionTable = null; // Track the child table when navigating through ElementCollection

        // Walk through all segments except the last to build edges and track embedded prefix
        for (int i = 0; i < segments.length - 1; i++) {
            String segment = segments[i];
            TypeDefinition.Prop prop = current.findProperty(segment);

            if (prop == null) {
                throw new UnknownAttributePathException(path,
                        "Cannot navigate through non-relation property: " + segment);
            }

            if (prop instanceof TypeDefinition.Prop.Association assoc) {
                // To-one: owner table has FK pointing to child table's ID
                ColumnRef.ForeignKey ownerFk = new ColumnRef.ForeignKey(
                        assoc.fkColumn(), current.tableName());
                ColumnRef.Simple childId = new ColumnRef.Simple(
                        assoc.definition().id().columnName(), assoc.definition().tableName());
                edges.add(new JoinEdge.ToOne(ownerFk, childId));
                current = assoc.definition();
            } else if (prop instanceof TypeDefinition.Prop.EntityCollection ec) {
                // To-many: child table has FK pointing back to owner table's ID
                ColumnRef.Simple ownerId = new ColumnRef.Simple(
                        current.idColumn(), current.tableName());
                ColumnRef.ForeignKey childFk = new ColumnRef.ForeignKey(
                        ec.fkColumn(), ec.definition().tableName());
                edges.add(new JoinEdge.ToMany(ownerId, childFk));
                current = ec.definition();
            } else if (prop instanceof TypeDefinition.Prop.ElementCollection ec) {
                // Element collection: child table has FK pointing back to owner table's ID
                ColumnRef.Simple ownerId = new ColumnRef.Simple(
                        current.idColumn(), current.tableName());
                ColumnRef.ForeignKey childFk = new ColumnRef.ForeignKey(
                        ec.fkColumn(), ec.childTable());
                edges.add(new JoinEdge.ToMany(ownerId, childFk));
                // Track the element collection's child table for leaf qualification
                elementCollectionTable = ec.childTable();
                // Continue navigation into the element type (ValueObject)
                if (ec.definition() == null) {
                    throw new UnknownAttributePathException(path,
                            "Element collection target type not resolved: " + segment);
                }
                current = ec.definition();
            } else if (prop instanceof TypeDefinition.Prop.Embedded emb) {
                // Embedded relations don't create join edges - they're flattened into the same table
                if (columnPrefix.length() > 0) {
                    columnPrefix.append('_');
                }
                columnPrefix.append(segment);
                current = emb.definition();
            } else {
                // Scalar, Id, Version cannot be navigated through
                throw new UnknownAttributePathException(path,
                        "Cannot navigate through scalar property: " + segment);
            }
        }

        // Resolve the final segment
        String lastSegment = segments[segments.length - 1];
        TypeDefinition.Prop lastProp = current.findProperty(lastSegment);

        if (lastProp == null) {
            throw new UnknownAttributePathException(path,
                    "Property not found: " + lastSegment);
        }

        // Build the column ref
        ColumnRef leaf = leafColumn(lastProp, columnPrefix.toString(), resolveTable(current, elementCollectionTable, root), path, lastSegment);

        return new ColumnPlacement(leaf, edges);
    }

    /**
     * Whether this entity-rooted path starts with a to-many relation or element collection.
     */
    public boolean isToManyPath(TypeDefinition root, String path) {
        int dot = path.indexOf('.');
        String pathHead = dot < 0 ? path : path.substring(0, dot);
        TypeDefinition.Prop prop = root.findProperty(pathHead);
        return prop instanceof TypeDefinition.Prop.EntityCollection
                || prop instanceof TypeDefinition.Prop.ElementCollection;
    }

    // ----------------------------------------------------------------- internals

    /**
     * The table the terminal column lives on: the element-collection child table when the path
     * navigated through one, else the current type's table — falling back to the root type's table
     * for value objects, which own no table of their own.
     */
    private String resolveTable(TypeDefinition current, String elementCollectionTable, TypeDefinition root) {
        if (elementCollectionTable != null) {
            return elementCollectionTable;
        }
        return current.tableName() != null ? current.tableName() : root.tableName();
    }

    /**
     * Builds the terminal column for a leaf property: a plain column for scalars/ids/versions, a
     * foreign key for associations, and refusal for anything that is not a column.
     */
    private ColumnRef leafColumn(TypeDefinition.Prop prop, String columnPrefix, String tableName, String path, String terminalName) {
        String columnName;
        switch (prop) {
            case TypeDefinition.Prop.Scalar s -> columnName = columnPrefix.isEmpty()
                    ? s.columnName()
                    : columnPrefix + "_" + s.columnName();
            case TypeDefinition.Prop.Id id -> columnName = columnPrefix.isEmpty()
                    ? id.columnName()
                    : columnPrefix + "_" + id.columnName();
            case TypeDefinition.Prop.Version v -> columnName = columnPrefix.isEmpty()
                    ? v.columnName()
                    : columnPrefix + "_" + v.columnName();
            case TypeDefinition.Prop.Association assoc -> columnName = columnPrefix.isEmpty()
                    ? assoc.fkColumn()
                    : columnPrefix + "_" + assoc.fkColumn();
            default -> throw new UnknownAttributePathException(path,
                    "Terminal property is not a column: " + terminalName);
        }

        return switch (prop) {
            case TypeDefinition.Prop.Scalar s -> new ColumnRef.Simple(columnName, tableName);
            case TypeDefinition.Prop.Id id -> new ColumnRef.Simple(columnName, tableName);
            case TypeDefinition.Prop.Version v -> new ColumnRef.Simple(columnName, tableName);
            case TypeDefinition.Prop.Association assoc -> new ColumnRef.ForeignKey(columnName, tableName);
            default -> throw new UnknownAttributePathException(path,
                    "Terminal property is not a column: " + terminalName);
        };
    }
}
