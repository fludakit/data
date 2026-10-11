package io.github.fludakit.data.metadata;

import java.util.Map;

/**
 * Resolves entity-relative attribute paths to their physical column placement. The entity's own
 * columns are published by {@link #columnRefs()}; a path that traverses a relation is resolved by
 * {@link #visit(String, AttributePathVisitor)}, which walks the aggregate tree and reports the
 * relation edges and the terminal column to the supplied visitor.
 */
public interface AttributePathResolver {

    /** Maps each of the entity's own resolvable columns by its logical path. */
    default Map<String, ColumnRef> columnRefs() {
        return Map.of();
    }

    /**
     * Walks an entity-rooted dot path through the aggregate tree, reporting each relation edge and
     * finally the terminal column to {@code visitor}. A path that cannot be resolved (unknown
     * property, or a traversal through a write-only back-reference) reports nothing and throws.
     */
    void visit(String path, AttributePathVisitor visitor);

    /** Returns whether this entity-rooted path starts with a to-many relation or collection. */
    boolean isToManyPath(String path);
}
