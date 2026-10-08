package io.github.fludakit.data.model;

import io.github.fludakit.data.metadata.ColumnRef;

import java.util.List;

/**
 * A table-backed {@code @Entity} / {@code @AggregateRoot}: the direct successor of the processor's
 * former {@code DescriptorModel}. The flat physical column view is the shared
 * {@link DataModel#flattenColumns} algorithm applied to {@code (id, props)}.
 */
public record EntityDataModel(Stereotype stereotype, String typeName, String tableName,
                              Prop.Id id, List<Prop> props) implements DataModel {

    public EntityDataModel {
        // Defensive copy: the immutability invariant of a descriptor must not rely on
        // call-site discipline.
        props = List.copyOf(props);
    }

    @Override
    public List<ColumnRef> columns() {
        return DataModel.flattenColumns(typeName, id, props);
    }
}
