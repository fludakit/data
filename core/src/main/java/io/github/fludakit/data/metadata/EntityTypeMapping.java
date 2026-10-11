package io.github.fludakit.data.metadata;

import java.util.List;

/** Table-backed entity or aggregate root. */
public record EntityTypeMapping<T>(Kind kind, String typeName, String tableName,
                                  Prop.Id id, List<Prop> props) implements TypeMapping<T> {

    public EntityTypeMapping {
        props = List.copyOf(props);
    }

    @Override
    public List<ColumnRef> columns() {
        return TypeMapping.flattenColumns(typeName, tableName, id, props);
    }
}
