package io.github.fludakit.data.metamodel;

import jakarta.data.metamodel.Attribute;

/**
 * Base of every column-bearing FluDa metamodel attribute: the Jakarta Data attribute name (a dot
 * path for flattened embedded leaves, e.g. {@code "address.city"}) plus the physical column it
 * maps to (already prefixed, e.g. {@code "address_city"}).
 */
public interface ColumnAttribute<T> extends Attribute<T> {

    String column();
}
