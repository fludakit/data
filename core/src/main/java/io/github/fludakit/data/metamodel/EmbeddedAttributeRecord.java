package io.github.fludakit.data.metamodel;

import io.github.fludakit.data.metadata.EntityDescriptor;

/** Record implementation of {@link EmbeddedAttribute}. */
public record EmbeddedAttributeRecord<T, V>(String name, EntityDescriptor<V> embedded)
        implements EmbeddedAttribute<T, V> { }
