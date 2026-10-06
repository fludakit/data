package io.github.fludakit.data.metamodel;

import jakarta.data.metamodel.Attribute;

/** A {@code List<@ValueObject>} element collection stored in a child table keyed by this entity. */
public interface ElementCollectionAttribute<T, E> extends Attribute<T> {

    Class<E> elementType();
}
