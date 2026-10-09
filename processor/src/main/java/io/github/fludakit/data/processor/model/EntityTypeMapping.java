package io.github.fludakit.data.processor.model;

import io.github.fludakit.data.metadata.ColumnRef;
import io.github.fludakit.data.processor.model.TypeMapping.Prop;
import io.github.fludakit.data.processor.model.TypeMapping.Stereotype;

import java.util.List;

/** Table-backed entity or aggregate root. */
public record EntityTypeMapping(Stereotype stereotype, String typeName, String tableName,
                              Prop.Id id, List<Prop> props) implements TypeMapping {

    public EntityTypeMapping {
        props = List.copyOf(props);
    }

    @Override
    public List<ColumnRef> columns() {
        return TypeMapping.flattenColumns(typeName, tableName, id, props);
    }
}
