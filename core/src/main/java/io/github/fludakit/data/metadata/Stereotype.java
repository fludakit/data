package io.github.fludakit.data.metadata;

/**
 * Domain role of a mapped type. A {@code VALUE_OBJECT} has no table or identifier of its own; an
 * {@code ENTITY} and an {@code AGGREGATE_ROOT} both map to their own table, the latter owning the
 * write lifecycle of its entity collections.
 */
public enum Stereotype {
    ENTITY, VALUE_OBJECT, AGGREGATE_ROOT
}
