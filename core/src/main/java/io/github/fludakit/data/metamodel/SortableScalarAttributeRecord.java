package io.github.fludakit.data.metamodel;

import jakarta.data.Sort;

/** Record implementation of {@link SortableScalarAttribute}. */
public record SortableScalarAttributeRecord<T>(String name, String path)
        implements SortableScalarAttribute<T> {

    public SortableScalarAttributeRecord(String name) {
        this(name, name);
    }

    @Override
    public Sort<T> asc() {
        return Sort.asc(path);
    }

    @Override
    public Sort<T> desc() {
        return Sort.desc(path);
    }
}
