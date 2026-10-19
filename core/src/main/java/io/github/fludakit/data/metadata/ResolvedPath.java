package io.github.fludakit.data.metadata;

/**
 * Result of resolving a dot-separated property path through the relation graph.
 *
 * @param ownerType the TypeDefinition that owns the terminal column or relation
 * @param prop non-null if the terminal is a scalar column, ID, version, or relation
 */
public record ResolvedPath(
        TypeDefinition ownerType,
        TypeDefinition.Prop prop
) {

    /**
     * Returns true if the terminal is a to-many relation or element collection.
     */
    public boolean isToMany() {
        return prop instanceof TypeDefinition.Prop.EntityCollection
                || prop instanceof TypeDefinition.Prop.ElementCollection;
    }

    /**
     * Returns true if the terminal is an embedded relation.
     */
    public boolean isEmbedded() {
        return prop instanceof TypeDefinition.Prop.Embedded;
    }

    /**
     * Returns the column name for the terminal property.
     * For scalar columns, returns the column name.
     * For relations, returns the FK column or embedded column.
     */
    public String columnName() {
        if (prop == null) {
            throw new IllegalStateException("ResolvedPath has no prop");
        }
        return switch (prop) {
            case TypeDefinition.Prop.Scalar s -> s.columnName();
            case TypeDefinition.Prop.Id id -> id.columnName();
            case TypeDefinition.Prop.Version v -> v.columnName();
            case TypeDefinition.Prop.Association assoc -> assoc.fkColumn();
            case TypeDefinition.Prop.EntityCollection ec -> ec.fkColumn();
            case TypeDefinition.Prop.ElementCollection ec -> ec.elementType();
            default -> throw new IllegalStateException("Prop type " + prop.getClass().getSimpleName() + " has no column name");
        };
    }

    /**
     * Returns the table name for the terminal property.
     */
    public String tableName() {
        return ownerType != null ? ownerType.tableName() : null;
    }
}
