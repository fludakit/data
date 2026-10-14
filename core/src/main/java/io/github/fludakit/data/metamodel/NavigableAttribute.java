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
 * @param <T> the owner type
 * @param <U> the navigated target type (value object, associated entity, or element type)
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
}
