package io.github.fludakit.data.processor.model;

import io.github.fludakit.data.metadata.ColumnRef;
import io.github.fludakit.data.processor.model.TypeMapping.Prop;

import java.util.List;

/** Value object whose properties flatten into its owner's table. */
public record EmbeddedValueObjectMapping(String typeName, List<Prop> props) implements TypeMapping {

    public EmbeddedValueObjectMapping {
        props = List.copyOf(props);
    }

    @Override
    public Stereotype stereotype() {
        return Stereotype.VALUE_OBJECT;
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
