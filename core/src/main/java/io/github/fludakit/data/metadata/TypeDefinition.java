package io.github.fludakit.data.metadata;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Flat metadata record for a mapped type (entity, aggregate root, or value object).
 *
 * <p>Replaces the 5-type TypeMapping hierarchy with a single record structure.
 * Carries everything needed for SQL generation, row mapping, and relation path resolution.</p>
 */
public record TypeDefinition(
        String typeName,
        String tableName,
        String idColumn,
        boolean isAggregate,
        List<ColumnMapping> columnMappings,
        List<RelationMapping> relationMappings
) implements TableSchema, ColumnResolver {

    public TypeDefinition {
        columnMappings = List.copyOf(columnMappings);
        relationMappings = List.copyOf(relationMappings);
    }

    /**
     * Resolves a dot-separated property path through the relation graph.
     *
     * @param dotPath property path like "address.city" or "workHistory.company"
     * @return resolved path with owner type, column/relation info
     * @throws UnknownAttributePathException if path cannot be resolved
     */
    public ResolvedPath resolvePath(String dotPath) {
        String[] segments = dotPath.split("\\.");
        return resolvePath(segments, 0, this);
    }

    private ResolvedPath resolvePath(String[] segments, int index, TypeDefinition current) {
        if (index >= segments.length) {
            return new ResolvedPath(current, null, null);
        }

        String segment = segments[index];
        boolean isLast = (index == segments.length - 1);

        // Check columns first (terminal property)
        for (ColumnMapping col : current.columnMappings) {
            if (col.propertyName().equals(segment)) {
                if (isLast) {
                    return new ResolvedPath(current, col, null);
                }
                throw new UnknownAttributePathException(dotPath(segments, index),
                        "Cannot navigate through scalar: " + segment);
            }
        }

        // Check relations (navigation property)
        for (RelationMapping rel : current.relationMappings) {
            if (rel.propertyName().equals(segment)) {
                if (isLast) {
                    // Terminal relation (e.g., "author" in findByAuthor)
                    return new ResolvedPath(current, null, rel);
                }
                // Navigate deeper (e.g., "workHistory.company")
                if (rel.target() == null) {
                    throw new UnknownAttributePathException(dotPath(segments, index),
                            "Relation has no target: " + segment);
                }
                return resolvePath(segments, index + 1, rel.target());
            }
        }

        throw new UnknownAttributePathException(dotPath(segments, index),
                "Property not found: " + segment);
    }

    private static String dotPath(String[] segments, int endIndex) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i <= endIndex; i++) {
            if (i > 0) sb.append('.');
            sb.append(segments[i]);
        }
        return sb.toString();
    }

    /**
     * Returns whether the named property is a to-many relation or element collection.
     */
    public boolean isToManyPath(String path) {
        int dot = path.indexOf('.');
        String pathHead = dot < 0 ? path : path.substring(0, dot);
        for (RelationMapping rel : relationMappings) {
            if (rel.propertyName().equals(pathHead)) {
                return rel.kind() == RelationKind.TO_MANY
                        || rel.kind() == RelationKind.ELEMENT_COLLECTION;
            }
        }
        return false;
    }

    /**
     * Finds a column mapping by property name.
     */
    public ColumnMapping columnMapping(String propertyName) {
        for (ColumnMapping col : columnMappings) {
            if (col.propertyName().equals(propertyName)) {
                return col;
            }
        }
        return null;
    }

    /**
     * Finds a relation mapping by property name.
     */
    public RelationMapping relationMapping(String propertyName) {
        for (RelationMapping rel : relationMappings) {
            if (rel.propertyName().equals(propertyName)) {
                return rel;
            }
        }
        return null;
    }

    // ── TableSchema implementation ───────────────────────────────────────

    @Override
    public List<ColumnRef> columns() {
        List<ColumnRef> refs = new ArrayList<>();
        addColumnsRecursive(refs, "", "");
        return refs;
    }

    private void addColumnsRecursive(List<ColumnRef> refs, String prefix, String table) {
        // Add direct columns
        for (ColumnMapping col : columnMappings) {
            String columnName = prefix.isEmpty() ? col.columnName() : prefix + "_" + col.columnName();
            refs.add(new ColumnRef.Simple(columnName, table.isEmpty() ? tableName : table));
        }
        // Add FK columns from to-one relations
        for (RelationMapping rel : relationMappings) {
            if (rel.kind() == RelationKind.TO_ONE && rel.fkColumn() != null) {
                String columnName = prefix.isEmpty() ? rel.fkColumn() : prefix + "_" + rel.fkColumn();
                refs.add(new ColumnRef.Simple(columnName, table.isEmpty() ? tableName : table));
            }
            // Recursively add embedded VO columns with flattened prefix
            if (rel.kind() == RelationKind.EMBEDDED && rel.target() != null) {
                String newPrefix = prefix.isEmpty() ? rel.propertyName() : prefix + "_" + rel.propertyName();
                TypeDefinition target = rel.target();
                // Pass the current table name through so nested embeddings use the root table
                target.addColumnsRecursive(refs, newPrefix, table.isEmpty() ? tableName : table);
            }
        }
    }

    // ── ColumnResolver implementation ────────────────────────────────────

    @Override
    public Map<String, ColumnRef> columnRefs() {
        Map<String, ColumnRef> refs = new LinkedHashMap<>();
        addColumnRefsRecursive(refs, "");
        return refs;
    }

    private void addColumnRefsRecursive(Map<String, ColumnRef> refs, String prefix) {
        // Add ID column first (only at root level)
        if (prefix.isEmpty() && idColumn != null) {
            for (ColumnMapping col : columnMappings) {
                if (col.isId()) {
                    refs.put(col.propertyName(), new ColumnRef.Simple(col.columnName(), tableName));
                    break;
                }
            }
        }
        // Add scalar columns with optional prefix
        for (ColumnMapping col : columnMappings) {
            String propName = prefix.isEmpty() ? col.propertyName() : prefix + "." + col.propertyName();
            // Convert dotted prefix to underscore-separated for column names
            String columnPrefix = prefix.replace('.', '_');
            String columnName = prefix.isEmpty() ? col.columnName() : columnPrefix + "_" + col.columnName();
            if (!col.isId()) {
                refs.put(propName, new ColumnRef.Simple(columnName, tableName));
            }
        }
        // Add to-one FK columns
        for (RelationMapping rel : relationMappings) {
            if (rel.kind() == RelationKind.TO_ONE && rel.fkColumn() != null) {
                String propName = prefix.isEmpty() ? rel.propertyName() : prefix + "." + rel.propertyName();
                String columnPrefix = prefix.replace('.', '_');
                String columnName = prefix.isEmpty() ? rel.fkColumn() : columnPrefix + "_" + rel.fkColumn();
                refs.put(propName, new ColumnRef.ForeignKey(columnName, tableName));
            }
            // Recursively add embedded VO columns with flattened prefix
            if (rel.kind() == RelationKind.EMBEDDED && rel.target() != null) {
                String newPrefix = prefix.isEmpty() ? rel.propertyName() : prefix + "." + rel.propertyName();
                TypeDefinition target = rel.target();
                target.addColumnRefsRecursive(refs, newPrefix);
            }
        }
    }

    @Override
    public ColumnPlacement resolve(String path) {
        String[] segments = path.split("\\.");
        List<JoinEdge> edges = new ArrayList<>();
        TypeDefinition current = this;
        StringBuilder columnPrefix = new StringBuilder();
        
        // Walk through all segments except the last to build edges and track embedded prefix
        for (int i = 0; i < segments.length - 1; i++) {
            String segment = segments[i];
            RelationMapping rel = current.relationMapping(segment);
            
            if (rel == null) {
                throw new UnknownAttributePathException(path,
                        "Cannot navigate through non-relation property: " + segment);
            }
            
            if (rel.target() == null) {
                throw new UnknownAttributePathException(path,
                        "Relation has no target: " + segment);
            }
            
            // Build the appropriate JoinEdge based on relation kind
            if (rel.kind() == RelationKind.TO_ONE) {
                // To-one: owner table has FK pointing to child table's ID
                ColumnRef.ForeignKey ownerFk = new ColumnRef.ForeignKey(
                        rel.fkColumn(), current.tableName());
                ColumnRef.Simple childId = new ColumnRef.Simple(
                        rel.target().idColumn(), rel.target().tableName());
                edges.add(new JoinEdge.ToOne(ownerFk, childId));
            } else if (rel.kind() == RelationKind.TO_MANY 
                    || rel.kind() == RelationKind.ELEMENT_COLLECTION) {
                // To-many: child table has FK pointing back to owner table's ID
                ColumnRef.Simple ownerId = new ColumnRef.Simple(
                        current.idColumn(), current.tableName());
                ColumnRef.ForeignKey childFk = new ColumnRef.ForeignKey(
                        rel.fkColumn(), rel.target().tableName());
                edges.add(new JoinEdge.ToMany(ownerId, childFk));
            } else if (rel.kind() == RelationKind.EMBEDDED) {
                // Embedded relations don't create join edges - they're flattened into the same table
                // Track the prefix for column naming
                if (columnPrefix.length() > 0) {
                    columnPrefix.append('_');
                }
                columnPrefix.append(segment);
            }
            
            // For ALL relation types (including embedded), navigate to the target
            current = rel.target();
        }
        
        // Resolve the final segment
        String lastSegment = segments[segments.length - 1];
        ResolvedPath resolved = current.resolvePath(lastSegment);
        
        // Build the full column name with embedded prefix
        String columnName = resolved.columnName();
        if (columnPrefix.length() > 0) {
            columnName = columnPrefix.toString() + "_" + columnName;
        }
        
        // For embedded paths, the table is the root table (this), not the VO's table (which is null)
        String tableName = resolved.tableName() != null ? resolved.tableName() : this.tableName;
        
        ColumnRef leaf = new ColumnRef.Simple(columnName, tableName);
        
        return new ColumnPlacement(leaf, edges);
    }

    // ── Compatibility methods for migration ──────────────────────────────

    /**
     * Returns whether this type owns aggregate lifecycle (same as isAggregate).
     * Compatibility method for TypeMapping.ownsAggregateLifecycle().
     */
    public boolean ownsAggregateLifecycle() {
        return isAggregate;
    }
}
