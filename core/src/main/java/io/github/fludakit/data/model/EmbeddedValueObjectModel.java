package io.github.fludakit.data.model;

import io.github.fludakit.data.metadata.ColumnRef;

import java.util.List;

/**
 * A value object WITHOUT a secondary table: its leaves flatten into the owner's table (the owner
 * prefixes the physical columns; this model's own view carries the bare leaf columns/paths, exactly
 * like the entity-kind flattening consumes them). No table, no identifier.
 */
public record EmbeddedValueObjectModel(String typeName, List<Prop> props) implements DataModel {

    public EmbeddedValueObjectModel {
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
        return DataModel.flattenColumns(typeName, null, props);
    }
}
