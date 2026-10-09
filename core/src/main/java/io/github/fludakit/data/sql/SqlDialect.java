package io.github.fludakit.data.sql;

/**
 * SQL syntax hooks used by the generated-SQL renderer.
 *
 * <p>This is intentionally limited to syntax that the current repository implementation emits.
 * User-provided native SQL is not parsed or rewritten by a dialect.</p>
 */
public interface SqlDialect {

    /** Quotes one table, column, alias, or other SQL identifier. */
    String quoteIdentifier(String identifier);

    /** Renders a row limit expression such as an integer literal or bind expression. */
    String limitClause(String limitExpression);

    /** Renders an offset expression such as an integer literal or bind expression. */
    String offsetClause(String offsetExpression);

    /** Renders the dialect's limit-and-offset form. */
    default String limitOffsetClause(String limitExpression, String offsetExpression) {
        return limitClause(limitExpression) + offsetClause(offsetExpression);
    }
}
