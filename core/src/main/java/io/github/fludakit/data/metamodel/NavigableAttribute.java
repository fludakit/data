package io.github.fludakit.data.metamodel;

import jakarta.data.metamodel.Attribute;

/**
 * Marker for a metamodel attribute navigable by a dot path — the ported Jakarta Data 1.1
 * {@code NavigableAttribute} vocabulary (spec §"static metamodel" navigable attributes), defined
 * FluDa-side because the resolved jakarta.data-api is 1.0.1 (external BOM). When 1.1.Final reaches
 * the BOM these interfaces re-point to the spec types; user code keeps compiling unchanged.
 *
 * <p>Navigation-only: an attribute carries its NAME and nothing else. Table/foreign-key placement
 * lives in the compile-time {@code io.github.fludakit.data.model.DataModel} and, at runtime, in the
 * generated descriptor's {@code navigationRefs()} map.</p>
 *
 * @param <T> the owner type
 * @param <U> the navigated target type (value object, associated entity, or element type)
 */
public sealed interface NavigableAttribute<T, U> extends Attribute<T>
        permits EntityAttribute, EmbeddableAttribute, ListAttribute { }
