package io.github.fludakit.data.sql;

import java.util.Objects;

/**
 * The ANSI/standard SQL spellings the shipped dialects share: double-quoted identifiers and
 * {@code LIMIT}/{@code OFFSET} pagination. A third-party dialect can extend this and override only
 * what its database spells differently (typically {@link #upsertForm()} and {@link #upsertStatement}).
 */
public abstract class AnsiSqlDialect implements SqlDialect {

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
