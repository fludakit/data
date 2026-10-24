package io.github.fludakit.data;

public final class Names {
    private Names() {}

    public static String toSnakeCase(String javaName) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < javaName.length(); i++) {
            char c = javaName.charAt(i);
            if (Character.isUpperCase(c)) {
                if (i > 0) sb.append('_');
                sb.append(Character.toLowerCase(c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * The snake-cased BASE a property contributes to a column — not the whole column for a
     * composite path. For a plain scalar the base IS the column ({@code lastName → last_name});
     * for an embedded path it is only the leading segment, and the CALLER completes it
     * ({@code address} + {@code "_"} + {@code city}), which is what the flattening in
     * {@code TypeMapping} does.
     *
     * <p>Named for the base rather than the column because the two are NOT the same and the
     * difference is invisible at a call site — both are {@code Names.columnBase(property)}, so a
     * caller cannot tell from the call which one it got. A dotted path is not a column either:
     * {@code toSnakeCase} leaves the {@code '.'} alone, so {@code address.city} does not become
     * {@code address_city}, and a caller holding a dotted path has to flatten it first.
     */
    public static String columnBase(String property) { return toSnakeCase(property); }
    public static String fkColumn(String property) { return toSnakeCase(property) + "_id"; }
    public static String tableName(Class<?> type) { return tableName(type.getSimpleName()); }
    public static String tableName(String simpleName) { return pluralize(toSnakeCase(simpleName)); }

    public static String pluralize(String singular) {
        if (singular == null || singular.isEmpty()) return singular;
        String lower = singular.toLowerCase();

        // Sibilant endings: -s, -x, -z, -ch, -sh → add "es"
        if (lower.endsWith("s") || lower.endsWith("x") || lower.endsWith("z")
                || lower.endsWith("ch") || lower.endsWith("sh")) {
            return lower + "es";
        }

        // Consonant + y → ies
        if (lower.endsWith("y") && lower.length() > 1) {
            char before = lower.charAt(lower.length() - 2);
            if ("aeiou".indexOf(before) < 0) {
                return lower.substring(0, lower.length() - 1) + "ies";
            }
        }

        // Default: add "s"
        return lower + "s";
    }
    /**
     * Element collection child table: derived from the ELEMENT TYPE name, not the parent.
     * {@code OrderItem} → {@code order_items}.
     */
    public static String childTable(String elementTypeName) {
        return pluralize(toSnakeCase(elementTypeName));
    }

    public static String childTable(String parentTable, String field) { return parentTable + "_" + toSnakeCase(field); }

    /**
     * The canonical many-to-many join table name for two simple type names: the two snake-cased
     * singulars joined by an underscore, in alphabetical order, so both directions agree
     * ({@code Book} + {@code Author} → {@code book_authors}).
     */
    public static String joinTable(String aSimple, String bSimple) {
        String a = toSnakeCase(aSimple);
        String b = toSnakeCase(bSimple);
        return a.compareTo(b) <= 0 ? a + "_" + b : b + "_" + a;
    }
}
