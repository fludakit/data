package io.github.fludakit.data.processor.generator;

import java.util.Map;

/**
 * The {@code private static final String SQL_...} constant registry every emitter writes into. One
 * registry per repository implementation: the emitter shares it with the read shapes, which is what
 * lets two methods that render the same statement share one constant instead of emitting a duplicate.
 */
public final class SqlConstants {

    private SqlConstants() {
    }

    /**
     * Registers {@code sql} under {@code base} and RETURNS THE NAME IT WAS REGISTERED UNDER — the
     * caller emits that name, so the query is the point of the call, not the mutation. Two rules,
     * both visible in the returned name:
     * <ul>
     *   <li>identical SQL under a base already present REUSES the existing name, so two methods that
     *       render the same statement share one constant instead of emitting a duplicate;</li>
     *   <li>DIFFERENT SQL under a taken base is suffixed {@code _2}, {@code _3}, … — a silent rename,
     *       so no caller may assume {@code base} came back unchanged.</li>
     * </ul>
     */
    public static String register(Map<String, String> constants, String base, String sql) {
        String name = base;
        int suffix = 2;
        while (constants.containsKey(name) && !constants.get(name).equals(sql)) {
            name = base + "_" + suffix++;
        }
        constants.put(name, sql);
        return name;
    }

    /**
     * Scans SQL text for positional parameters ({@code ?0}, {@code ?1}, …), skipping string literals
     * and comments. A {@code @Query} method uses positional binding when its SQL contains {@code ?N};
     * otherwise it uses named binding ({@code :name}).
     */
    public static boolean hasPositionalParameter(String sql) {
        boolean singleQuoted = false;
        boolean doubleQuoted = false;
        boolean lineComment = false;
        boolean blockComment = false;
        for (int i = 0; i < sql.length(); i++) {
            char current = sql.charAt(i);
            char next = i + 1 < sql.length() ? sql.charAt(i + 1) : '\0';
            if (lineComment) {
                if (current == '\n' || current == '\r') {
                    lineComment = false;
                }
                continue;
            }
            if (blockComment) {
                if (current == '*' && next == '/') {
                    blockComment = false;
                    i++;
                }
                continue;
            }
            if (singleQuoted) {
                if (current == '\'' && next == '\'') {
                    i++;
                } else if (current == '\'') {
                    singleQuoted = false;
                }
                continue;
            }
            if (doubleQuoted) {
                if (current == '"' && next == '"') {
                    i++;
                } else if (current == '"') {
                    doubleQuoted = false;
                }
                continue;
            }
            if (current == '-' && next == '-') {
                lineComment = true;
                i++;
            } else if (current == '/' && next == '*') {
                blockComment = true;
                i++;
            } else if (current == '\'') {
                singleQuoted = true;
            } else if (current == '"') {
                doubleQuoted = true;
            } else if (current == '?' && Character.isDigit(next)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The declared result shape, classified straight into {@link ResultKind} — there is no second
     * vocabulary to translate through, so a shape cannot be flattened on the way to the model.
     *
     * <p>An array is its own shape rather than {@link ResultKind#LIST}: both are "many", but only one
     * of them needs {@code toArray} at the end, and the two must not be told apart by re-checking the
     * return {@code TypeMirror} at every emission site.</p>
     */
}
