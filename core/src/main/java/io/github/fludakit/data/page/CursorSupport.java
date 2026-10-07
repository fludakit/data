package io.github.fludakit.data.page;

import io.github.fludakit.data.metadata.EntityDescriptor;
import jakarta.data.page.PageRequest;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class CursorSupport {

    private CursorSupport() {}

    /** Lexicographic seek WHERE clause over the given snake_case columns/directions. {@code forward} true = CURSOR_NEXT. */
    public static String seekWhere(List<String> columns, List<Boolean> ascending, PageRequest.Cursor cursor, boolean forward) {
        return seekWhereTerms(columns.stream().map(c -> "\"" + c + "\"").toList(), ascending, cursor, forward);
    }

    /**
     * Lexicographic seek WHERE clause over PRE-RENDERED terms (each used verbatim, already quoted and —
     * for relation-sorted paged reads — table-qualified, e.g. {@code "profile"."id"}). {@code seekWhere}
     * quotes bare column names itself, which cannot carry a table qualifier without double-quoting;
     * generated paging code uses this variant when its refs may join (Task 11 fix, parked M-1).
     */
    public static String seekWhereTerms(List<String> terms, List<Boolean> ascending, PageRequest.Cursor cursor, boolean forward) {
        StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < terms.size(); i++) {
            if (i > 0) sb.append(" OR ");
            sb.append("(");
            for (int j = 0; j <= i; j++) {
                if (j > 0) sb.append(" AND ");
                String op = (ascending.get(j) == forward) ? ">" : "<";
                sb.append(terms.get(j)).append(" ").append(op).append(" :cursor").append(j);
            }
            sb.append(")");
        }
        return sb.append(")").toString();
    }

    public static Map<String, Object> cursorParams(PageRequest.Cursor cursor) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < cursor.size(); i++) out.put("cursor" + i, cursor.get(i));
        return out;
    }

    /**
     * Builds the cursor key for {@code entity} from its sorted property paths, reading every value
     * through the descriptor's reflection-free {@link EntityDescriptor#value(String, Object)} — so a
     * to-many sort key collapses to the same edge the joined SQL aggregate produces: the minimum for an
     * ascending key, the maximum for a descending one (nulls ignored, as SQL {@code MIN}/{@code MAX}
     * ignore them; an empty or all-null list yields a {@code null} key).
     *
     * @param properties the dot paths, in cursor-key order
     * @param ascending  the sort direction of each key
     * @param md         the entity's generated descriptor
     * @param entity     the row the cursor continues from
     */
    public static PageRequest.Cursor cursorFor(List<String> properties, List<Boolean> ascending,
                                               EntityDescriptor<?> md, Object entity) {
        Object[] key = new Object[properties.size()];
        for (int i = 0; i < properties.size(); i++) {
            Object value = md.value(properties.get(i), entity);
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
            if (edge == null || (ascending ? ((Comparable) edge).compareTo(value) > 0
                    : ((Comparable) edge).compareTo(value) < 0)) {
                edge = value;
            }
        }
        return edge;
    }

    /**
     * Reflection-based cursor key, superseded by the descriptor-driven {@link
     * #cursorFor(List, List, EntityDescriptor, Object)} overload that the generated paging code is
     * migrating to. Kept (with its reflective helpers) until every generated call site moves.
     */
    @Deprecated
    public static PageRequest.Cursor cursorFor(List<String> properties, Object entity) {
        Object[] key = new Object[properties.size()];
        for (int i = 0; i < properties.size(); i++) key[i] = fieldValue(entity, properties.get(i));
        return PageRequest.Cursor.forKey(key);
    }

    private static Object fieldValue(Object entity, String property) {
        Field f = findField(entity.getClass(), property);
        if (f == null) throw new IllegalArgumentException("No field named '" + property + "' on " + entity.getClass().getName());
        try { f.setAccessible(true); return f.get(entity); } catch (IllegalAccessException e) { throw new IllegalStateException(e); }
    }

    private static Field findField(Class<?> c, String name) {
        Class<?> cur = c;
        while (cur != null && cur != Object.class) {
            try { return cur.getDeclaredField(name); } catch (NoSuchFieldException ignored) { }
            cur = cur.getSuperclass();
        }
        return null;
    }
}
