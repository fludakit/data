package io.github.fludakit.data.metadata;

import java.util.List;

/**
 * Value object stored in an element-collection child table.
 *
 * <p>Unlike its embedded counterpart it IS {@link #tableBacked()}: the child table carries the
 * parent FK plus one column per flattened element field. It still has no identity of its own.</p>
 */
public record ElementCollectionValueObjectMapping<T>(String typeName, String tableName,
                                       String parentFkColumn, List<Prop> props)
        implements ValueObjectMapping<T> {

    public ElementCollectionValueObjectMapping {
        props = List.copyOf(props);
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
