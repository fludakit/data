package io.github.fludakit.data.sql;

import io.github.fludakit.data.metadata.ColumnResolver;
import io.github.fludakit.data.metadata.TableSchema;
import io.github.fludakit.data.query.QueryDefinition;
import io.github.fludakit.data.query.ResultKind;

import java.util.Objects;

/**
 * The ONE implementation behind the {@link Sql} lifecycle stages: INSERT, UPDATE and DELETE by
 * identifier.
 *
 * <p>These statements are the level-one half of the API — they need a table shape and nothing else,
 * so there is no predicate stage to walk. The one decision a caller makes is whether an INSERT
 * carries the identifier column, which is why {@link Sql.Insert} exists as a distinct stage rather
 * than a flag on {@link Sql.Lifecycle}.</p>
 *
 * <p>Rendering delegates to {@link QueryCompiler}, the same renderer the query chains and
 * {@link SqlBuilder} use.</p>
 */
final class SqlLifecycle implements Sql.Lifecycle, Sql.Insert {

    private final TableSchema schema;
    private final ColumnResolver resolver;
    private final Kind kind;
    private SqlDialect dialect = H2Dialect.INSTANCE;
    private boolean includeId = true;
    private ResultKind resultKind = ResultKind.VOID;

    private enum Kind { INSERT, UPDATE, DELETE_BY_ID }

    private SqlLifecycle(TableSchema schema, ColumnResolver resolver, Kind kind) {
        this.schema = Objects.requireNonNull(schema, "schema");
        this.resolver = resolver;
        this.kind = Objects.requireNonNull(kind, "kind");
    }

    static SqlLifecycle insert(TableSchema schema, ColumnResolver resolver) {
        return new SqlLifecycle(schema, resolver, Kind.INSERT);
    }

    static SqlLifecycle update(TableSchema schema, ColumnResolver resolver) {
        return new SqlLifecycle(schema, resolver, Kind.UPDATE);
    }

    static SqlLifecycle deleteById(TableSchema schema) {
        return new SqlLifecycle(schema, null, Kind.DELETE_BY_ID);
    }

    @Override
    public SqlLifecycle dialect(SqlDialect dialect) {
        this.dialect = Objects.requireNonNull(dialect, "dialect");
        return this;
    }

    @Override
    public SqlLifecycle withIdentityGenerated() {
        this.includeId = false;
        return this;
    }

    @Override
    public SqlLifecycle includingId() {
        this.includeId = true;
        return this;
    }

    @Override
    public SqlLifecycle returning(ResultKind resultKind) {
        this.resultKind = Objects.requireNonNull(resultKind, "resultKind");
        return this;
    }

    /**
     * The statement this chain describes, then one render: the lifecycle variants need no resolver,
     * no planning and no projection, so the whole statement is the table shape plus — for an INSERT —
     * whether the identifier rides along. The result kind rides on the definition without changing the
     * SQL: an INSERT is the same statement whether the caller wants nothing, the entity back, or the
     * number of rows written.
     */
    @Override
    public String toSql() {
        QueryDefinition definition = switch (kind) {
            case INSERT -> new QueryDefinition.Insert(resultKind, includeId);
            case UPDATE -> new QueryDefinition.Update(resultKind);
            case DELETE_BY_ID -> new QueryDefinition.DeleteById(resultKind);
        };
        return QueryCompiler.render(schema, resolver, dialect, definition, null);
    }
}
