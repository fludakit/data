package io.github.fludakit.data.metadata;

import java.util.Map;

/**
 * Resolves entity-relative attribute paths independently of any one table's physical schema.
 * The index key type is chosen by the mapping surface: compile-time mappings use path strings,
 * while runtime metadata uses typed metamodel attributes.
 *
 * @param <A> the key type used by the ordered column-reference index
 */
public interface AttributePathResolver<A> {

    /** Maps each resolvable logical attribute to its physical table and join placement. */
    default Map<A, ColumnRef> columnRefs() {
        return Map.of();
    }

    /** Returns the entity-rooted logical path represented by an index key. */
    String attributePath(A attribute);

    /** Resolves an entity-rooted path to the leaf column and its table/join placement. */
    ColumnRef columnRef(String attributePath);

    /** Returns whether this entity-rooted path starts with a to-many relation or collection. */
    boolean isToManyPath(String attributePath);
}
