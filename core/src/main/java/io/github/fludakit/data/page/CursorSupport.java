package io.github.fludakit.data.page;

import jakarta.data.page.PageRequest;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class CursorSupport {

    private CursorSupport() {}

    /** Lexicographic seek WHERE clause over the given snake_case columns/directions. {@code forward} true = CURSOR_NEXT. */
    public static String seekWhere(List<String> columns, List<Boolean> ascending, PageRequest.Cursor cursor, boolean forward) {
        StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) sb.append(" OR ");
            sb.append("(");
            for (int j = 0; j <= i; j++) {
                if (j > 0) sb.append(" AND ");
                String op = (ascending.get(j) == forward) ? ">" : "<";
                sb.append('"').append(columns.get(j)).append("\" ").append(op).append(" :cursor").append(j);
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
