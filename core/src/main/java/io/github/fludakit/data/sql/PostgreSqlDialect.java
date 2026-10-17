package io.github.fludakit.data.sql;

import java.util.List;
import java.util.stream.Collectors;

/**
 * SQL syntax for PostgreSQL. Identifier quoting and {@code LIMIT}/{@code OFFSET} pagination match H2
 * (via {@link AnsiSqlDialect}); the class exists as a shipped dialect so PostgreSQL-specific syntax
 * — its {@code ON CONFLICT} upsert — lives here without touching the renderer.
 */
public final class PostgreSqlDialect extends AnsiSqlDialect {

    public static final PostgreSqlDialect INSTANCE = new PostgreSqlDialect();

    private PostgreSqlDialect() {
    }

    /** PostgreSQL spells an upsert {@code INSERT ... ON CONFLICT (<id>) DO UPDATE SET ...}. */
    @Override
    public UpsertForm upsertForm() {
        return UpsertForm.ON_CONFLICT;
    }

    @Override
    public String upsertStatement(String tableName, String idColumn, List<String> columns,
                                  List<String> params) {
        // EXCLUDED is the row the INSERT tried to write, so the update half rebinds nothing and both
        // halves share the same named parameters. The identifier is the conflict target and is never
        // re-assigned, so it is dropped from the SET list. An entity whose only column is its
        // identifier has nothing to update, so it degrades to DO NOTHING.
        String names = columns.stream().map(this::quoteIdentifier).collect(Collectors.joining(", "));
        String excludedSet = columns.stream()
                .filter(c -> !c.equals(idColumn))
                .map(c -> quoteIdentifier(c) + " = EXCLUDED." + quoteIdentifier(c))
                .collect(Collectors.joining(", "));
        return "INSERT INTO " + quoteIdentifier(tableName) + " (" + names + ") VALUES (" + String.join(", ", params) + ")"
                + " ON CONFLICT (" + quoteIdentifier(idColumn) + ") DO "
                + (excludedSet.isEmpty() ? "NOTHING" : "UPDATE SET " + excludedSet);
    }
}
