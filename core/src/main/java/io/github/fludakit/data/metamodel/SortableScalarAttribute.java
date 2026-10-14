package io.github.fludakit.data.metamodel;

import jakarta.data.Sort;
import jakarta.data.metamodel.SortableAttribute;

/**
 * A scalar attribute that supports ordering.
 *
 * <p>The two {@link Sort} terms are DEFAULTS here rather than body-less declarations, because every
 * sortable scalar in this package answers them the same way: a {@code Sort} over {@link
 * PathAttribute#path()}. Three records used to carry the pair as byte-identical two-line bodies.
 *
 * <p>The PATH and not the name is the whole point. {@code Sort.asc(name())} would compile and would
 * sort an embedded leaf by a property that does not resolve: {@code name()} is only the last segment
 * ({@code "city"}) while the attribute is {@code "address.city"}.</p>
 *
 * @param <T> the root entity type
 */
public interface SortableScalarAttribute<T> extends ScalarAttribute<T>, SortableAttribute<T> {

    /** Ascending {@link Sort} over {@link #path()}. */
    @Override
    default Sort<T> asc() {
        return Sort.asc(path());
    }

    /** Descending {@link Sort} over {@link #path()}. */
    @Override
    default Sort<T> desc() {
        return Sort.desc(path());
    }
}
