package io.github.fludakit.data.metadata;

/**
 * Result of resolving a dot-separated property path through the relation graph.
 *
 * @param ownerType the TypeDefinition that owns the terminal column or relation
 * @param column non-null if the terminal is a scalar column
 * @param relation non-null if the terminal is a relation
 */
public record ResolvedPath(
        TypeDefinition ownerType,
        ColumnMapping column,
        RelationMapping relation
) {

    /**
     * Returns true if the terminal is a to-many relation or element collection.
     */
    public boolean isToMany() {
        return relation != null
                && (relation.kind() == RelationKind.TO_MANY
                || relation.kind() == RelationKind.ELEMENT_COLLECTION);
    }

    /**
     * Returns true if the terminal is an embedded relation.
     */
    public boolean isEmbedded() {
        return relation != null && relation.kind() == RelationKind.EMBEDDED;
    }

    /**
     * Returns the column name for the terminal property.
     * For scalar columns, returns the column name.
     * For relations, returns the FK column or embedded column.
     */
    public String columnName() {
        if (column != null) {
            return column.columnName();
        }
        if (relation != null) {
            return relation.fkColumn();
        }
        throw new IllegalStateException("ResolvedPath has neither column nor relation");
    }

    /**
     * Returns the table name for the terminal property.
     */
    public String tableName() {
        return ownerType != null ? ownerType.tableName() : null;
    }
}
