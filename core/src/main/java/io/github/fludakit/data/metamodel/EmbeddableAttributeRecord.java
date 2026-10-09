package io.github.fludakit.data.metamodel;

/** Record implementation of {@link EmbeddableAttribute}. */
public record EmbeddableAttributeRecord<T, V>(String name, String path)
        implements EmbeddableAttribute<T, V> {

    public EmbeddableAttributeRecord(String name) {
        this(name, name);
    }
}
