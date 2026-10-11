package io.github.fludakit.data.metadata;

import java.util.List;

/** Value object stored in an element-collection child table. */
public record ElementCollectionValueObjectMapping<T>(String typeName, String tableName,
                                       String parentFkColumn, List<Prop> props) implements TypeMapping<T> {

    public ElementCollectionValueObjectMapping {
        props = List.copyOf(props);
    }

    @Override
    public Kind kind() {
        return Kind.VALUE_OBJECT;
    }

    @Override
    public Prop.Id id() {
        return null;
    }

    @Override
    public List<ColumnRef> columns() {
        return TypeMapping.flattenColumns(typeName, tableName, null, props);
    }
}
