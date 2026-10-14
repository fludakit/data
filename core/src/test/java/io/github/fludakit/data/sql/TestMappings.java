package io.github.fludakit.data.sql;

import io.github.fludakit.data.metadata.AggregateTypeMapping;
import io.github.fludakit.data.metadata.ColumnRef;
import io.github.fludakit.data.metadata.EntityTypeMapping;
import io.github.fludakit.data.metadata.TypeMapping;
import io.github.fludakit.data.metadata.TypeMapping.Prop;

import java.util.List;
import java.util.Map;

/**
 * Builds the small mappings {@link SqlBuilderTest} needs, without anonymous {@code TypeMapping}
 * implementations.
 *
 * <p>The mappings the tests need differ from the real records in one respect: several of them
 * publish a {@code columns()} view that is deliberately NOT what {@code props()} would derive —
 * an entity with a hand-picked column subset, or a to-one reference that contributes no column at
 * all. That is exactly the shape a generated descriptor has before its relations are resolved, so
 * it is modelled here with a record that takes the column list explicitly instead of hiding it in
 * an anonymous subclass.</p>
 */
final class TestMappings {

    private TestMappings() {
    }

    /** A plain entity whose {@code columns()} is the given list rather than a derivation of {@code props}. */
    static <T> TypeMapping<T> entity(String typeName, String tableName, Prop.Id id, List<Prop> props,
                                     List<ColumnRef> columns) {
        return new ExplicitColumns<>(new EntityTypeMapping<>(typeName, tableName, id, props), columns, null);
    }

    /** An aggregate root with the same explicit-columns escape hatch. */
    static <T> TypeMapping<T> aggregateRoot(String typeName, String tableName, Prop.Id id,
                                           List<Prop> props, List<ColumnRef> columns) {
        return new ExplicitColumns<>(new AggregateTypeMapping<>(typeName, tableName, id, props), columns, null);
    }

    /**
     * An aggregate root with an explicit {@code columnRefs()} index as well.
     *
     * <p>Needed when a test's embedded target is left unresolved (a {@code null} target), because the
     * real derivation then contributes nothing for it while the test still needs the flattened leaf
     * to resolve.</p>
     */
    static <T> TypeMapping<T> aggregateRoot(String typeName, String tableName, Prop.Id id, List<Prop> props,
                                           List<ColumnRef> columns, Map<String, ColumnRef> columnRefs) {
        return new ExplicitColumns<>(new AggregateTypeMapping<>(typeName, tableName, id, props), columns, columnRefs);
    }

    /** An aggregate root with the standard derived {@code columns()} view. */
    static <T> TypeMapping<T> aggregateRoot(String typeName, String tableName, Prop.Id id, List<Prop> props) {
        return new AggregateTypeMapping<>(typeName, tableName, id, props);
    }

    /**
     * Wraps a real mapping and replaces only {@link #columns()}. Everything else — identity, props,
     * {@code columnRefs()}, the visitor walk — stays the real implementation's, so the tests still
     * exercise production behaviour.
     */
    private record ExplicitColumns<T>(TypeMapping<T> delegate, List<ColumnRef> columns,
                                      Map<String, ColumnRef> columnRefs)
            implements TypeMapping<T> {

        private ExplicitColumns {
            columns = List.copyOf(columns);
            columnRefs = columnRefs == null ? null : Map.copyOf(columnRefs);
        }

        @Override
        public boolean ownsAggregateLifecycle() {
            return delegate.ownsAggregateLifecycle();
        }

        @Override
        public boolean isValueObject() {
            return delegate.isValueObject();
        }

        @Override
        public String typeName() {
            return delegate.typeName();
        }

        @Override
        public String tableName() {
            return delegate.tableName();
        }

        @Override
        public Prop.Id id() {
            return delegate.id();
        }

        @Override
        public List<Prop> props() {
            return delegate.props();
        }

        @Override
        public List<ColumnRef> columns() {
            return columns;
        }

        @Override
        public Map<String, ColumnRef> columnRefs() {
            return columnRefs != null ? columnRefs : delegate.columnRefs();
        }
    }
}
