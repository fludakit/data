package io.github.fludakit.data.metamodel;

import jakarta.data.Sort;

/** Record implementation of {@link TextColumnAttribute}. */
public record TextColumnAttributeRecord<T>(String name)
        implements TextColumnAttribute<T> {

    @Override
    public Sort<T> asc() {
        return Sort.asc(name);
    }

    @Override
    public Sort<T> desc() {
        return Sort.desc(name);
    }

    @Override
    public Sort<T> ascIgnoreCase() {
        return Sort.ascIgnoreCase(name);
    }

    @Override
    public Sort<T> descIgnoreCase() {
        return Sort.descIgnoreCase(name);
    }
}
