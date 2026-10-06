package io.github.fludakit.data.metamodel;

import io.github.fludakit.data.metadata.EntityDescriptor;

import java.util.function.Supplier;

/** Record implementation of {@link ElementCollectionAttribute}. */
public record ElementCollectionAttributeRecord<T, E>(String name, Class<E> elementType,
        String childTable, String parentFkColumn,
        Supplier<? extends EntityDescriptor<E>> elementDescriptor)
        implements ElementCollectionAttribute<T, E> { }
