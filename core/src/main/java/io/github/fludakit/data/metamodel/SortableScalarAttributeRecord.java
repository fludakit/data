package io.github.fludakit.data.metamodel;

/**
 * Record implementation of {@link SortableScalarAttribute}. The {@code Sort} terms are that
 * interface's defaults.
 */
public record SortableScalarAttributeRecord<T>(String name, PropertyPath path)
        implements SortableScalarAttribute<T> {

    /** A single-segment attribute: its path is its name. */
    public SortableScalarAttributeRecord(String name) {
        this(name, PropertyPath.of(name));
    }
}
