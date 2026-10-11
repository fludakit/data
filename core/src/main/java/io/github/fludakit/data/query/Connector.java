package io.github.fludakit.data.query;

/**
 * The logical operator joining a predicate to the one before it, as written in the method name.
 *
 * <p>A predicate carries the connector to its PREDECESSOR; the first predicate's connector is ignored.
 * Jakarta Data evaluates {@code And} before {@code Or}, which is also SQL's precedence, so the
 * declared order is rendered as written ({@code a AND b OR c} ≡ {@code (a AND b) OR c}) and no
 * grouping is needed.
 */
public enum Connector {
    AND,
    OR;

    /** Whether a connector is {@code OR}; a {@code null} connector (absent) is treated as AND. */
    public static boolean isOr(Connector connector) {
        return connector == OR;
    }
}
