package io.github.fludakit.data.metamodel;

import jakarta.data.Sort;

/** Record implementation of {@link SortableColumnAttribute}. */
public record SortableColumnAttributeRecord<T>(String name, String column)
        implements SortableColumnAttribute<T> {

    @Override
    public Sort<T> asc() {
        return Sort.asc(name);
    }

    @Override
    public Sort<T> desc() {
        return Sort.desc(name);
    }
}
