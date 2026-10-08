package io.github.fludakit.data.metamodel;

/** Record implementation of {@link ListAttribute}. */
public record ListAttributeRecord<T, E>(String name) implements ListAttribute<T, E> { }
