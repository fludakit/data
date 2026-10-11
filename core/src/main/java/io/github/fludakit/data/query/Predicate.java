package io.github.fludakit.data.query;

/**
 * One rendered WHERE conjunct: the logical property path, the operator, the named SQL parameter, the
 * connector joining it to the preceding predicate, and whether the comparison folds case.
 *
 * @param property the logical attribute path
 * @param operator the comparison operator
 * @param param the named-parameter token the operator binds
 * @param connector the logical operator joining this predicate to the one before it; the first
 *                  predicate's connector is ignored, and {@code null} means {@link Connector#AND}
 * @param ignoreCase when true the comparison folds case on both sides ({@code LOWER(col) = LOWER(:p)})
 */
public record Predicate(String property, Operator operator, String param, Connector connector,
                        boolean ignoreCase) {

    /** A case-sensitive predicate joined to its predecessor with {@code AND} — the common case. */
    public static Predicate of(String property, Operator operator, String param) {
        return new Predicate(property, operator, param, Connector.AND, false);
    }

    /** A predicate with an explicit connector; a {@code null} connector means {@code AND}. */
    public static Predicate of(String property, Operator operator, String param, Connector connector) {
        return new Predicate(property, operator, param, connector == null ? Connector.AND : connector, false);
    }

    /** A predicate with an explicit connector and case sensitivity. */
    public static Predicate of(String property, Operator operator, String param, Connector connector,
                               boolean ignoreCase) {
        return new Predicate(property, operator, param, connector == null ? Connector.AND : connector, ignoreCase);
    }
}
