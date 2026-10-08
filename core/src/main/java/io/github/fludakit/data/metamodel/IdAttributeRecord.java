package io.github.fludakit.data.metamodel;

import jakarta.data.Sort;

/** Record implementation of {@link IdAttribute}. */
public record IdAttributeRecord<T>(String name) implements IdAttribute<T> {

    @Override
    public Sort<T> asc() {
        return Sort.asc(name);
    }

    @Override
    public Sort<T> desc() {
        return Sort.desc(name);
    }
}
