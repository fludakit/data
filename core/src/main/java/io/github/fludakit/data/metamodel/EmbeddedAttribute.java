package io.github.fludakit.data.metamodel;

import io.github.fludakit.data.metadata.EntityDescriptor;
import jakarta.data.metamodel.Attribute;

/**
 * An {@code @ValueObject} property embedded in an owner entity. Carries the value object's own
 * descriptor for leaf-by-leaf mapping; {@code null} when the embedded target could not be resolved
 * (value-object cycle), in which case reading its flattened columns fails fast.
 */
public interface EmbeddedAttribute<T, V> extends Attribute<T> {

    EntityDescriptor<V> embedded();
}
