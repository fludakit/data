package io.github.fludakit.data.metamodel;

/**
 * Marker for a metamodel attribute navigable by a dot path — the ported Jakarta Data 1.1
 * {@code NavigableAttribute} vocabulary (spec §"static metamodel" navigable attributes), defined
 * FluDa-side because the resolved jakarta.data-api is 1.0.1 (external BOM). When 1.1.Final reaches
 * the BOM these interfaces re-point to the spec types; user code keeps compiling unchanged.
 *
 * <p>Navigation-only: an attribute carries its NAME and nothing else. Table/foreign-key placement
 * lives in the processor's compile-time entity model and, at runtime, in the generated
 * {@code TypeMapping.columnRefs()} index.</p>
 *
 * <p>The {@code get()} overloads compose paths without wrapper classes:
 * {@code _Order.customer.get(_Customer.name)} yields a {@code TextScalarAttribute<Order>}
 * whose {@link #path()} is {@code "customer.name"}. Each overload preserves the attribute kind
 * (text, sortable, id, entity, embeddable, list) so the result is usable in sorts, predicates,
 * and further navigation.</p>
 *
 * @param <T> the owner type
 * @param <U> the navigated definition type (value object, associated entity, or element type)
 */
public sealed interface NavigableAttribute<T, U> extends PathAttribute<T>
        permits EntityAttribute, EmbeddableAttribute, ListAttribute {

    /**
     * Re-declared abstract rather than inherited: {@link PathAttribute#path()} defaults to
     * {@code name()}, which is right for a single-segment attribute and WRONG here. A navigable
     * attribute's path is multi-segment ({@code "address.city"}) while its name is the last segment
     * ({@code "city"}), so the inherited default would silently publish a path that does not resolve.
     * Every implementation must therefore supply the real path.
     */
    @Override
    String path();

    default TextScalarAttribute<T> get(TextScalarAttribute<U> target) {
        return new TextScalarAttributeRecord<>(target.name(), path() + "." + target.path());
    }

    default IdAttribute<T> get(IdAttribute<U> target) {
        return new IdAttributeRecord<>(target.name(), path() + "." + target.path());
    }

    default SortableScalarAttribute<T> get(SortableScalarAttribute<U> target) {
        return new SortableScalarAttributeRecord<>(target.name(), path() + "." + target.path());
    }

    default ScalarAttribute<T> get(ScalarAttribute<U> target) {
        return new ScalarAttributeRecord<>(target.name(), path() + "." + target.path());
    }

    default <V> EntityAttribute<T, V> get(EntityAttribute<U, V> target) {
        return new EntityAttributeRecord<>(target.name(), path() + "." + target.path());
    }

    default <V> EmbeddableAttribute<T, V> get(EmbeddableAttribute<U, V> target) {
        return new EmbeddableAttributeRecord<>(target.name(), path() + "." + target.path());
    }

    default <E> ListAttribute<T, E> get(ListAttribute<U, E> target) {
        return new ListAttributeRecord<>(target.name(), path() + "." + target.path());
    }

    default PathAttribute<T> get(PathAttribute<U> target) {
        return new PathAttributeRecord<>(target.name(), path() + "." + target.path());
    }
}
