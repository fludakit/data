package io.github.fludakit.data.metadata;

import java.util.Map;

/**
 * Resolves entity-relative attribute paths to their physical column placement, keyed by the logical
 * path string. Compile-time and runtime mappings share this one shape; the query plan and
 * {@code SqlBuilder} both address columns by path.
 */
public interface AttributePathResolver {

    /** Maps each resolvable logical attribute path to its physical table and join placement. */
    default Map<String, ColumnRef> columnRefs() {
        return Map.of();
    }

    /** Resolves an entity-rooted path to the leaf column and its table/join placement. */
    ColumnRef columnRef(String attributePath);

    /** Returns whether this entity-rooted path starts with a to-many relation or collection. */
    boolean isToManyPath(String attributePath);
}
