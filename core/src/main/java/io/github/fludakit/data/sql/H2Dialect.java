package io.github.fludakit.data.sql;

import java.util.List;
import java.util.stream.Collectors;

/**
 * SQL syntax used by the currently supported H2 backend. Identifier quoting and {@code LIMIT}/
 * {@code OFFSET} come from {@link AnsiSqlDialect}; H2 differs only in its single-statement upsert.
 */
public final class H2Dialect extends AnsiSqlDialect {

    public static final H2Dialect INSTANCE = new H2Dialect();

    private H2Dialect() {
    }

    /**
     * H2 has no {@code ON CONFLICT} and no {@code ON DUPLICATE KEY UPDATE}; it has the legacy
     * {@code MERGE INTO <table> (<columns>) KEY (<id>) VALUES (...)}. The {@code USING (SELECT ...)}
     * form of MERGE is rejected whenever a bound parameter has no inferable type
     * ({@code SELECT :id AS "id"} fails with "Unknown data type"), so the single-row {@code KEY}
     * form is the only one the renderer can use with named parameters.
     */
    @Override
    public UpsertForm upsertForm() {
        return UpsertForm.MERGE;
    }

    @Override
    public String upsertStatement(String tableName, String idColumn, List<String> columns,
                                  List<String> params) {
        String names = columns.stream().map(this::quoteIdentifier).collect(Collectors.joining(", "));
        return "MERGE INTO " + quoteIdentifier(tableName) + " (" + names + ") KEY (" + quoteIdentifier(idColumn)
                + ") VALUES (" + String.join(", ", params) + ")";
    }
}
