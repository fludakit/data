package io.github.fludakit.data.metamodel;

import jakarta.data.Sort;

/** Record implementation of {@link TextScalarAttribute}. */
public record TextScalarAttributeRecord<T>(String name, String path)
        implements TextScalarAttribute<T> {

    public TextScalarAttributeRecord(String name) {
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

    @Override
    public Sort<T> ascIgnoreCase() {
        return Sort.ascIgnoreCase(path);
    }

    @Override
    public Sort<T> descIgnoreCase() {
        return Sort.descIgnoreCase(path);
    }
}
