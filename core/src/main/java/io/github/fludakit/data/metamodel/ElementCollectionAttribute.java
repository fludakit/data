package io.github.fludakit.data.metamodel;

import io.github.fludakit.data.metadata.EntityDescriptor;
import jakarta.data.metamodel.Attribute;

import java.util.function.Supplier;

/** A {@code List<@ValueObject>} element collection stored in a child table keyed by this entity. */
public interface ElementCollectionAttribute<T, E> extends Attribute<T> {

    Class<E> elementType();

    String childTable();

    String parentFkColumn();

    Supplier<? extends EntityDescriptor<E>> elementDescriptor();
}
