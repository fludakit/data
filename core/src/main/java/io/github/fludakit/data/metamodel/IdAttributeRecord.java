package io.github.fludakit.data.metamodel;

/**
 * Record implementation of {@link IdAttribute}. The {@code Sort} terms come from {@link
 * SortableScalarAttribute}, which {@link IdAttribute} now extends.
 */
public record IdAttributeRecord<T>(String name, PropertyPath path) implements IdAttribute<T> {

    /** A single-segment identifier: its path is its name. */
    public IdAttributeRecord(String name) {
        this(name, PropertyPath.of(name));
    }
}
