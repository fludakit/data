package io.github.fludakit.data.metamodel;

import io.github.fludakit.data.metadata.EntityDescriptor;

import java.util.function.Supplier;

/** Record implementation of {@link ForeignKeyAttribute}. */
public record ForeignKeyAttributeRecord<T, U>(String name, String column, Class<U> target,
        Supplier<? extends EntityDescriptor<U>> targetDescriptor)
        implements ForeignKeyAttribute<T, U> { }
