package io.github.fludakit.data.metamodel;

/**
 * Record implementation of {@link TextScalarAttribute}. All four {@code Sort} terms are that
 * interface's defaults and its two supertypes'.
 */
public record TextScalarAttributeRecord<T>(String name, String path)
        implements TextScalarAttribute<T> {

    /** A single-segment attribute: its path is its name. */
    public TextScalarAttributeRecord(String name) {
        this(name, name);
    }
}
