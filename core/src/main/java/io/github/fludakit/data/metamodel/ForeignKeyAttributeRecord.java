package io.github.fludakit.data.metamodel;

/** Record implementation of {@link ForeignKeyAttribute}. */
public record ForeignKeyAttributeRecord<T, U>(String name, String column, Class<U> target)
        implements ForeignKeyAttribute<T, U> { }
