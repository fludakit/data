package io.github.fludakit.data;

public final class Names {
    private Names() {}

    public static String toSnakeCase(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isUpperCase(c)) {
                if (i > 0) sb.append('_');
                sb.append(Character.toLowerCase(c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    public static String columnName(String property) { return toSnakeCase(property); }
    public static String fkColumn(String property) { return toSnakeCase(property) + "_id"; }
    public static String tableName(Class<?> type) { return tableName(type.getSimpleName()); }
    public static String tableName(String simpleName) { return toSnakeCase(simpleName); }
    public static String childTable(String parentTable, String field) { return parentTable + "_" + toSnakeCase(field); }
    public static String quote(String identifier) { return "\"" + identifier + "\""; }
}
