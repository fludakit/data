package io.github.fludakit.data.metadata;

/**
 * Maps a relation property (reference, collection, embedded) to its target type.
 *
 * @param propertyName Java property name
 * @param kind relation kind (TO_ONE, TO_MANY, ELEMENT_COLLECTION, EMBEDDED)
 * @param targetTypeName fully-qualified Java type name of the target
 * @param fkColumn foreign key column name (for TO_ONE) or inverse FK column (for TO_MANY/ELEMENT_COLLECTION)
 * @param childTable child table name (for ELEMENT_COLLECTION), or null
 * @param isComposition whether this relation is owned by the parent (cascade lifecycle)
 * @param isEmbedded whether this is an embedded value object (flattened into parent table)
 * @param target the target TypeDefinition, or null if unresolved
 */
public record RelationMapping(
        String propertyName,
        RelationKind kind,
        String targetTypeName,
        String fkColumn,
        String childTable,
        boolean isComposition,
        boolean isEmbedded,
        TypeDefinition target
) {
}
