package io.github.fludakit.data.sql;

import java.util.Objects;

/** SQL syntax used by the currently supported H2 backend. */
public final class H2Dialect implements SqlDialect {

    public static final H2Dialect INSTANCE = new H2Dialect();

    private H2Dialect() {
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
