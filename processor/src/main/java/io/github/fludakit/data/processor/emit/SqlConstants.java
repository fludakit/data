package io.github.fludakit.data.processor.emit;

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
     * The declared result shape, classified straight into {@link ResultKind} — there is no second
     * vocabulary to translate through, so a shape cannot be flattened on the way to the model.
     *
     * <p>An array is its own shape rather than {@link ResultKind#LIST}: both are "many", but only one
     * of them needs {@code toArray} at the end, and the two must not be told apart by re-checking the
     * return {@code TypeMirror} at every emission site.</p>
     */
}
