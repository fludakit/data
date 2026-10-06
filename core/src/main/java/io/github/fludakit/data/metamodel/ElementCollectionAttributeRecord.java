package io.github.fludakit.data.metamodel;

/** Record implementation of {@link ElementCollectionAttribute}. */
public record ElementCollectionAttributeRecord<T, E>(String name, Class<E> elementType)
        implements ElementCollectionAttribute<T, E> { }
