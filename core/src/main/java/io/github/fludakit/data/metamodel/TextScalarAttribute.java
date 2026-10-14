package io.github.fludakit.data.metamodel;

import jakarta.data.Sort;
import jakarta.data.metamodel.TextAttribute;

/**
 * A textual scalar attribute that supports case-insensitive ordering.
 *
 * <p>{@link TextAttribute} adds the two case-insensitive terms to the {@link
 * SortableScalarAttribute} pair; like that pair, they are defaults over {@link
 * PathAttribute#path()} for the same reason — {@code TextScalarAttributeRecord} used to carry all
 * four bodies, of which the two ascending/descending ones were copies of two other records'.</p>
 *
 * @param <T> the root entity type
 */
public interface TextScalarAttribute<T> extends SortableScalarAttribute<T>, TextAttribute<T> {

    /** Case-insensitive ascending {@link Sort} over {@link #path()}. */
    @Override
    default Sort<T> ascIgnoreCase() {
        return Sort.ascIgnoreCase(path());
    }

    /** Case-insensitive descending {@link Sort} over {@link #path()}. */
    @Override
    default Sort<T> descIgnoreCase() {
        return Sort.descIgnoreCase(path());
    }
}
