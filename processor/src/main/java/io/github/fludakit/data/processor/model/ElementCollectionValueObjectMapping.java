package io.github.fludakit.data.processor.model;

import io.github.fludakit.data.metadata.ColumnRef;
import io.github.fludakit.data.processor.model.TypeMapping.Prop;

import java.util.List;

/** Value object stored in an element-collection child table. */
public record ElementCollectionValueObjectMapping(String typeName, String tableName,
                                       String parentFkColumn, List<Prop> props) implements TypeMapping {

    public ElementCollectionValueObjectMapping {
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
        return TypeMapping.flattenColumns(typeName, tableName, null, props);
    }
}
