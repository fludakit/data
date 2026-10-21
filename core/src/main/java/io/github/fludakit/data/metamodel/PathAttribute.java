package io.github.fludakit.data.metamodel;

import jakarta.data.metamodel.Attribute;

/**
 * An end-user metamodel attribute identified by its property path from the entity root.
 *
 * @param <T> the root entity type
 */
public interface PathAttribute<T> extends Attribute<T> {

    /**
     * Returns the property path from the entity root.
     *
     * <p>The path is a tree structure that can compute its dot-separated string form
     * via {@link PropertyPath#toDotPath()} when needed for SQL rendering.</p>
     *
     * @return the property path
     */
    default PropertyPath path() {
        return PropertyPath.of(name());
    }
}
