package io.github.fludakit.data.metamodel;

import jakarta.data.Sort;

/** Record implementation of {@link IdAttribute}. */
public record IdAttributeRecord<T>(String name, String path) implements IdAttribute<T> {

    public IdAttributeRecord(String name) {
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
