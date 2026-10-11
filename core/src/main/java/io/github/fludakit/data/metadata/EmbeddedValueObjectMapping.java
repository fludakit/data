package io.github.fludakit.data.metadata;

import java.util.List;

/** Value object whose properties flatten into its owner's table. */
public record EmbeddedValueObjectMapping<T>(String typeName, List<Prop> props) implements TypeMapping<T> {

    public EmbeddedValueObjectMapping {
        props = List.copyOf(props);
    }

    @Override
    public Kind kind() {
        return Kind.VALUE_OBJECT;
    }

    @Override
    public String tableName() {
        return null;
    }

    @Override
    public Prop.Id id() {
        return null;
    }

    @Override
    public List<ColumnRef> columns() {
        return TypeMapping.flattenColumns(typeName, null, null, props);
    }
}
