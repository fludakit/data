package io.github.fludakit.data.metamodel;

import jakarta.data.metamodel.Attribute;

/**
 * Base of every column-bearing FluDa metamodel attribute: the Jakarta Data attribute name (a dot
 * path for flattened embedded leaves, e.g. {@code "address.city"}). The physical column and the
 * cross-table placement of navigated paths are descriptor-owned
 * ({@code EntityDescriptor.columns()/columnRef()}); the attribute itself navigates only.
 */
public interface ColumnAttribute<T> extends Attribute<T> { }
