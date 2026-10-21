package io.github.fludakit.data.metamodel;

/** Record implementation of {@link ListAttribute}. */
public record ListAttributeRecord<T, E>(String name, PropertyPath path) implements ListAttribute<T, E> {

    public ListAttributeRecord(String name) {
        this(name, PropertyPath.of(name));
    }
}
