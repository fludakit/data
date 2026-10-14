package io.github.fludakit.data.metadata;

import java.util.List;

/**
 * Table-backed entity: has its own table and identifier, but owns no lifecycle beyond itself.
 *
 * <p>Its relations are lazy and never cascade. The root-owned counterpart is
 * {@link AggregateTypeMapping} — same storage shape, different relationship contract.</p>
 */
public record EntityTypeMapping<T>(String typeName, String tableName,
                                  Prop.Id id, List<Prop> props) implements TypeMapping<T> {

    public EntityTypeMapping {
        props = List.copyOf(props);
    }

    @Override
    public List<ColumnRef> columns() {
        return TypeMapping.flattenColumns(typeName, tableName, id, props);
    }
}
