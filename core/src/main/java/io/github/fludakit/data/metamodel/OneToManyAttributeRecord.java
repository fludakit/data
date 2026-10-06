package io.github.fludakit.data.metamodel;

import io.github.fludakit.data.metadata.EntityDescriptor;

import java.util.function.Supplier;

/** Record implementation of {@link OneToManyAttribute}. */
public record OneToManyAttributeRecord<T, U>(String name, Class<U> target, String inverseName,
        Supplier<? extends EntityDescriptor<U>> targetDescriptor)
        implements OneToManyAttribute<T, U> { }
