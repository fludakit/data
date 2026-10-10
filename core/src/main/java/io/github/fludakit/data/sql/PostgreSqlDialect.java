package io.github.fludakit.data.sql;

import java.util.Objects;

/**
 * SQL syntax for PostgreSQL. Identifier quoting and {@code LIMIT}/{@code OFFSET} pagination
 * currently match {@link H2Dialect}; the class exists as a shipped dialect so PostgreSQL-specific
 * syntax can diverge here without touching the renderer.
 */
public final class PostgreSqlDialect implements SqlDialect {

    public static final PostgreSqlDialect INSTANCE = new PostgreSqlDialect();

    private PostgreSqlDialect() {
    }

    @Override
    public String quoteIdentifier(String identifier) {
        Objects.requireNonNull(identifier, "identifier");
        if (identifier.isEmpty()) {
            throw new IllegalArgumentException("SQL identifier must not be empty");
        }
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    @Override
    public String limitClause(String limitExpression) {
        return " LIMIT " + Objects.requireNonNull(limitExpression, "limitExpression");
    }

    @Override
    public String offsetClause(String offsetExpression) {
        return " OFFSET " + Objects.requireNonNull(offsetExpression, "offsetExpression");
    }
}
