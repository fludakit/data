package io.github.fludakit.data.metadata;

import java.util.List;

/**
 * Value object whose properties flatten into its owner's table.
 *
 * <p>It has no table of its own — {@link #tableName()} is {@code null} — so it is the one
 * {@link ValueObjectMapping} that is not {@link #tableBacked()}.</p>
 */
public record EmbeddedValueObjectMapping<T>(String typeName, List<Prop> props)
        implements ValueObjectMapping<T> {

    public EmbeddedValueObjectMapping {
        props = List.copyOf(props);
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
