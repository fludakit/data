package io.github.fludakit.data.metamodel;

/** Record implementation of {@link ScalarAttribute}. */
public record ScalarAttributeRecord<T>(String name, String path) implements ScalarAttribute<T> {

    public ScalarAttributeRecord(String name) {
        this(name, name);
    }
}
