package io.github.fludakit.data.metamodel;

/** Record implementation of {@link EntityAttribute}. */
public record EntityAttributeRecord<T, U>(String name) implements EntityAttribute<T, U> { }
