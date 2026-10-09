package io.github.fludakit.data.metamodel;

import jakarta.data.metamodel.Attribute;

/**
 * An end-user metamodel attribute identified by its path from the entity root.
 *
 * @param <T> the root entity type
 */
public interface PathAttribute<T> extends Attribute<T> {

    /** Dot-separated property path from the entity root. */
    default String path() {
        return name();
    }
}
