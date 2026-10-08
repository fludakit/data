package io.github.fludakit.data.metamodel;

/** Record implementation of {@link EmbeddableAttribute}. */
public record EmbeddableAttributeRecord<T, V>(String name) implements EmbeddableAttribute<T, V> { }
