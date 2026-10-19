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
        return prop != null
                && (prop.kind() == TypeDefinition.Prop.PropKind.TO_MANY
                || prop.kind() == TypeDefinition.Prop.PropKind.ELEMENT_COLLECTION);
    }

    /**
     * Returns true if the terminal is an embedded relation.
     */
    public boolean isEmbedded() {
        return prop != null && prop.kind() == TypeDefinition.Prop.PropKind.EMBEDDED;
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
        return switch (prop.kind()) {
            case SCALAR -> ((TypeDefinition.Prop.Scalar) prop).columnName();
            case ID -> ((TypeDefinition.Prop.Id) prop).columnName();
            case VERSION -> ((TypeDefinition.Prop.Version) prop).columnName();
            case TO_ONE -> ((TypeDefinition.Prop.ToOne) prop).fkColumn();
            case TO_MANY -> ((TypeDefinition.Prop.ToMany) prop).fkColumn();
            case ELEMENT_COLLECTION -> ((TypeDefinition.Prop.ElementCollection) prop).fkColumn();
            default -> throw new IllegalStateException("Prop kind " + prop.kind() + " has no column name");
        };
    }

    /**
     * Returns the table name for the terminal property.
     */
    public String tableName() {
        return ownerType != null ? ownerType.tableName() : null;
    }
}
