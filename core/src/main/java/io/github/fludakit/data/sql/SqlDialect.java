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

    /**
     * Renders an INSERT of a row in which every column takes its DEFAULT — the form an entity with
     * nothing left to bind needs, because {@code INSERT INTO t () VALUES ()} is not SQL any
     * database accepts.
     *
     * <p>The usual reason to reach it is an entity whose only column is a database-generated
     * identifier: the identity is excluded from the column list, so the list is empty, yet
     * inserting the row is still how that identity comes into existence. The default is the
     * standard {@code DEFAULT VALUES} spelling, which both shipped dialects accept; a database
     * that spells it differently overrides this rather than the renderer learning a special case.
     */
    default String insertDefaultValues(String table) {
        return "INSERT INTO " + quoteIdentifier(table) + " DEFAULT VALUES";
    }

    /**
     * How this database writes an insert-or-update in one statement, or {@link UpsertForm#NONE} when
     * it has no such form.
     *
     * <p>A dialect answers WHICH syntax its database accepts; {@link QueryCompiler} owns the text.
     * {@link UpsertForm#NONE} is a supported answer, not an error: a caller that must still honour
     * {@code save}'s insert-or-update contract falls back to an {@code UPDATE} followed, when it
     * matched nothing, by an {@code INSERT} carrying the identifier.</p>
     */
    default UpsertForm upsertForm() {
        return UpsertForm.NONE;
    }
}
