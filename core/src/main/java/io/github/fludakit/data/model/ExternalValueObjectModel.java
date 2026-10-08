package io.github.fludakit.data.model;

import io.github.fludakit.data.metadata.ColumnRef;

import java.util.List;

/**
 * A value object WITH a secondary table: the {@code List<@ValueObject>} element-collection child
 * table. Owns the child table name and the FK column back to the parent — the placement data the
 * slimmed metamodel no longer carries anywhere else.
 */
public record ExternalValueObjectModel(String typeName, String tableName,
                                       String parentFkColumn, List<Prop> props) implements DataModel {

    public ExternalValueObjectModel {
        props = List.copyOf(props);
    }

    @Override
    public Stereotype stereotype() {
        return Stereotype.VALUE_OBJECT;
    }

    @Override
    public Prop.Id id() {
        return null;
    }

    @Override
    public List<ColumnRef> columns() {
        return DataModel.flattenColumns(typeName, null, props);
    }
}
