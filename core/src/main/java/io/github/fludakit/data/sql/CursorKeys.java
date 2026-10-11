package io.github.fludakit.data.sql;

import io.github.fludakit.data.metadata.EntityMetadata;
import jakarta.data.page.PageRequest;

import java.util.List;

/**
 * Builds keyset-paging cursor keys from materialized entities.
 *
 * <p>This is the only cursor helper a generated repository needs at runtime. The seek predicate
 * itself is rendered once at compile time by {@link SqlBuilder#seekClause} and baked into a SQL
 * literal, so no cursor SQL is assembled per query.
 */
public final class CursorKeys {

    private CursorKeys() {
    }

    /**
     * Builds the cursor key for {@code entity} from its sorted property paths, reading every value
     * through the metadata's reflection-free {@link EntityMetadata#navigate(String, Object)} — so a
     * to-many sort key collapses to the same edge the joined SQL aggregate produces: the minimum for an
     * ascending key, the maximum for a descending one (nulls ignored, as SQL {@code MIN}/{@code MAX}
     * ignore them; an empty or all-null list yields a {@code null} key).
     *
     * @param properties the dot paths, in cursor-key order
     * @param ascending  the sort direction of each key
     * @param md         the entity's generated metadata
     * @param entity     the root object the cursor continues from
     */
    public static <T> PageRequest.Cursor forEntity(List<String> properties, List<Boolean> ascending,
                                                   EntityMetadata<T> md, T entity) {
        if (properties.size() != ascending.size()) {
            throw new IllegalArgumentException("cursor key requires one direction per property: "
                    + properties.size() + " properties, " + ascending.size() + " directions");
        }
        Object[] key = new Object[properties.size()];
        for (int i = 0; i < properties.size(); i++) {
            Object value = md.navigate(properties.get(i), entity);
            key[i] = value instanceof List<?> values ? edgeOf(values, ascending.get(i)) : value;
        }
        return PageRequest.Cursor.forKey(key);
    }

    /** The {@code MIN} (ascending) or {@code MAX} edge of a to-many value list, nulls skipped. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object edgeOf(List<?> values, boolean ascending) {
        Object edge = null;
        for (Object value : values) {
            if (value == null) {
                continue;
            }
            if (!(value instanceof Comparable)) {
                // A value list whose elements carry no natural order (e.g.
                // value("workHistory.profile") -> List<Profile>) cannot produce a cursor edge — fail
                // fast at cursor-build time instead of casting blind and surfacing a CCE mid-page.
                throw new IllegalArgumentException("cursor sort on non-comparable path element: "
                        + value.getClass().getName());
            }
            if (edge == null || (ascending ? ((Comparable) edge).compareTo(value) > 0
                    : ((Comparable) edge).compareTo(value) < 0)) {
                edge = value;
            }
        }
        return edge;
    }
}
