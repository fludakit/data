package io.github.fludakit.data.metamodel;

/** Record implementation of {@link EmbeddableAttribute}. */
public record EmbeddableAttributeRecord<T, V>(String name, PropertyPath path)
        implements EmbeddableAttribute<T, V> {

    public EmbeddableAttributeRecord(String name) {
        this(name, PropertyPath.of(name));
    }
}
