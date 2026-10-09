package io.github.fludakit.data.metamodel;

/** Record implementation of {@link ListAttribute}. */
public record ListAttributeRecord<T, E>(String name, String path) implements ListAttribute<T, E> {

    public ListAttributeRecord(String name) {
        this(name, name);
    }
}
