package io.github.fludakit.data.metadata;

/**
 * Maps a scalar property to its database column.
 *
 * @param propertyName Java property name
 * @param columnName database column name
 * @param typeFqn fully-qualified Java type name
 * @param isId whether this is the primary key column
 * @param isVersion whether this is the optimistic locking version column
 */
public record ColumnMapping(
        String propertyName,
        String columnName,
        String typeFqn,
        boolean isId,
        boolean isVersion
) {
}
