package io.github.fludakit.data.metadata;

import java.util.List;

/**
 * Table-backed aggregate root: the consistency boundary that owns the lifecycle of everything
 * inside it.
 *
 * <p>Distinct from {@link EntityTypeMapping} because the two differ in relationship handling, not in
 * storage: both have a table and an identifier. Only an aggregate root synchronises the whole
 * aggregate — its to-one references and its entity and element collections are all saved and read
 * together. See {@code docs/AGGREGATE-SEMANTICS.md}.</p>
 */
public record AggregateTypeMapping<T>(String typeName, String tableName,
                                      Prop.Id id, List<Prop> props) implements TypeMapping<T> {

    public AggregateTypeMapping {
        props = List.copyOf(props);
    }

    @Override
    public boolean ownsAggregateLifecycle() {
        return true;
    }

    @Override
    public List<ColumnRef> columns() {
        return TypeMapping.flattenColumns(typeName, tableName, id, props);
    }
}
