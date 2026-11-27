package io.github.fludakit.data.sql;

import io.github.fludakit.data.metadata.ColumnPlacement;
import io.github.fludakit.data.metadata.ColumnRef;
import io.github.fludakit.data.metadata.JoinEdge;
import io.github.fludakit.data.metadata.TypeDefinition;
import io.github.fludakit.data.query.QueryDefinition;
import io.github.fludakit.data.query.ResultKind;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Turns an {@link SqlFragment} into a SQL literal. Compilation belongs to {@link QueryCompiler};
 * this class only renders, optionally formats, and splices call-time clauses (seek, order).
 *
 * <pre>
 * new SqlBuilder(typeModel).dialect(dialect).sql(definition);
 * new SqlBuilder(typeModel).format(SqlFormat.PRETTY).sql(fragment);
 * </pre>
 */
public final class SqlBuilder {

    private final TypeDefinition typeModel;
    private SqlDialect dialect;
    private SqlFormat format;

    /** Type model as both physical table shape and attribute-path model. */
    public SqlBuilder(TypeDefinition typeModel) {
        this.typeModel = typeModel;
        this.dialect = H2Dialect.INSTANCE;
        this.format = SqlFormat.COMPACT;
    }

    public SqlBuilder dialect(SqlDialect dialect) {
        this.dialect = Objects.requireNonNull(dialect, "dialect");
        return this;
    }

    public SqlBuilder format(SqlFormat format) {
        this.format = Objects.requireNonNull(format, "format");
        return this;
    }

    // --------------------------------------------------------------- terminal

    /** Renders {@code fragment}, then formats when {@link SqlFormat#PRETTY}. */
    public String sql(SqlFragment fragment) {
        Objects.requireNonNull(fragment, "fragment");
        String rendered = fragment.render(dialect);
        return format == SqlFormat.PRETTY ? SqlFormatter.format(rendered) : rendered;
    }

    /** Compiles then renders a repository statement. */
    public String sql(QueryDefinition definition) {
        return sql(QueryCompiler.compile(definition, requireTypeModel(), dialect));
    }

    /** Compiles with extra joins/projection, then renders. */
    public String sql(QueryDefinition definition, List<JoinEdge> extraEdges, String extraProjection) {
        return sql(QueryCompiler.compile(definition, requireTypeModel(), dialect, extraEdges, extraProjection));
    }

    // --------------------------------------------------------------- runtime splices

    /**
     * Assembles a cursor-paged seek statement: shell + null-guarded seek + ORDER BY. No LIMIT —
     * the caller splices that at runtime.
     */
    public String pagedSeekStatement(String shell, boolean shellHasWhere, List<String> terms,
                                     List<Boolean> ascending, boolean forward, List<String> orderByTerms,
                                     List<String> aggregateExpressions, String rootTable) {
        if (terms.isEmpty()) {
            return shell + renderOrderBy(orderByTerms, ascending);
        }
        if (aggregateExpressions == null || aggregateExpressions.isEmpty()) {
            return shell + (shellHasWhere ? " AND " : " WHERE ")
                    + "(:cursor0 IS NULL OR " + SeekClause.render(terms, ascending, forward) + ")"
                    + renderOrderBy(orderByTerms, ascending);
        }
        String innerAlias = "inner_";
        List<String> aliasedSeek = new java.util.ArrayList<>(terms.size());
        List<String> aliasedOrderBy = new java.util.ArrayList<>(orderByTerms.size());
        int aggIndex = 0;
        for (int i = 0; i < terms.size(); i++) {
            if (aggregateExpressions.contains(terms.get(i))) {
                String aggAlias = innerAlias + "." + dialect.quoteIdentifier("agg_" + aggIndex);
                aliasedSeek.add(aggAlias);
                aliasedOrderBy.add(aggAlias);
                aggIndex++;
            } else {
                aliasedSeek.add(repoint(terms.get(i), rootTable, innerAlias));
                aliasedOrderBy.add(repoint(orderByTerms.get(i), rootTable, innerAlias));
            }
        }
        return "SELECT " + innerAlias + ".* FROM (" + shell + ") AS " + innerAlias
                + (shellHasWhere ? " AND " : " WHERE ")
                + "(:cursor0 IS NULL OR " + SeekClause.render(aliasedSeek, ascending, forward) + ")"
                + renderOrderBy(aliasedOrderBy, ascending);
    }

    private static String renderOrderBy(List<String> terms, List<Boolean> ascending) {
        if (terms == null || terms.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(" ORDER BY ");
        for (int i = 0; i < terms.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(terms.get(i)).append(Boolean.TRUE.equals(ascending.get(i)) ? " ASC" : " DESC");
        }
        return sb.toString();
    }

    private static String repoint(String term, String rootTable, String innerAlias) {
        String prefix = rootTable + ".";
        return term.startsWith(prefix) ? innerAlias + "." + term.substring(prefix.length()) : term;
    }

    // --------------------------------------------------------------- convenience (type-model-backed)

    public String insert(boolean includeId) {
        return insert(includeId, ResultKind.VOID);
    }

    public String insert(boolean includeId, ResultKind resultKind) {
        return sql(new QueryDefinition.Insert(resultKind, includeId));
    }

    public String upsert() {
        return upsert(ResultKind.VOID);
    }

    public String upsert(ResultKind resultKind) {
        return sql(new QueryDefinition.Upsert(resultKind));
    }

    public String update() {
        return update(ResultKind.VOID);
    }

    public String update(ResultKind resultKind) {
        return sql(new QueryDefinition.Update(resultKind));
    }

    public String deleteById() {
        return deleteById(ResultKind.VOID);
    }

    public String deleteById(ResultKind resultKind) {
        return sql(new QueryDefinition.DeleteById(resultKind));
    }

    public String deleteByFk(String table, String fkColumn) {
        return sql(QueryCompiler.deleteByFk(dialect, table, fkColumn));
    }

    public String selectForeignKey(String fkColumn) {
        return sql(QueryCompiler.selectForeignKey(dialect, requireTypeModel(), fkColumn));
    }

    public String deleteChildByIdAndFk(String table, String idColumn, String fkColumn) {
        return sql(QueryCompiler.deleteChildByIdAndFk(dialect, table, idColumn, fkColumn));
    }

    public String selectChildIds(String table, String idColumn, String fkColumn) {
        return sql(QueryCompiler.selectChildIds(dialect, table, idColumn, fkColumn));
    }

    public String insertColumns(String table, List<String> columns, List<String> paramNames) {
        return sql(QueryCompiler.insertColumns(dialect, table, columns, paramNames));
    }

    /**
     * The rendered shape of a cursor-paged to-many sort/projection: the base SELECT shell
     * ({@code LEFT JOIN} child + {@code GROUP BY} root columns + {@code MIN}/{@code MAX} aggregates),
     * the {@code ORDER BY} aliases, and the keyset seek terms (full aggregate expressions).
     */
    public record CursorProjection(String shell, List<String> orderByTerms, List<String> seekTerms) {
        public CursorProjection {
            Objects.requireNonNull(shell, "shell");
            orderByTerms = List.copyOf(orderByTerms);
            seekTerms = List.copyOf(seekTerms);
        }
    }

    /**
     * Renders a cursor-paged to-many sort/projection. {@code sortColumns} maps child column suffix
     * to sort direction in iteration order; each becomes {@code MIN(alias.col)} (ascending) or
     * {@code MAX(alias.col)} (descending) aliased {@code agg_N}.
     */
    public CursorProjection cursorSortProjection(
            String childTable, String fkColumn, String joinAlias, Map<String, Boolean> sortColumns) {
        TypeDefinition typeModel = requireTypeModel();
        String rootTable = dialect.quoteIdentifier(typeModel.tableName());
        String quotedAlias = dialect.quoteIdentifier(joinAlias);

        SqlFragment.Query.Builder shell = SqlFragment.Query.builder().table(typeModel.tableName());
        List<String> selectColumns = new ArrayList<>();
        for (ColumnRef col : typeModel.columns()) {
            String qualified = rootTable + "." + dialect.quoteIdentifier(col.column());
            selectColumns.add(qualified);
            shell.groupBy(qualified);
        }

        List<String> orderByTerms = new ArrayList<>();
        List<String> seekTerms = new ArrayList<>();
        int aggIndex = 0;
        for (Map.Entry<String, Boolean> sort : sortColumns.entrySet()) {
            String aggFunc = sort.getValue() ? "MIN" : "MAX";
            String expr = aggFunc + "(" + quotedAlias + "." + dialect.quoteIdentifier(sort.getKey()) + ")";
            String alias = dialect.quoteIdentifier("agg_" + aggIndex);
            selectColumns.add(expr + " AS " + alias);
            orderByTerms.add(alias);
            seekTerms.add(expr);
            aggIndex++;
        }

        JoinEdge edge = new JoinEdge.ToMany(
                new ColumnRef.Simple(typeModel.idColumn(), typeModel.tableName()),
                new ColumnRef.ForeignKey(fkColumn, childTable));
        shell.projection(String.join(", ", selectColumns)).join(edge, joinAlias);
        return new CursorProjection(shell.build().render(dialect), orderByTerms, seekTerms);
    }

    public String correlatedSort(String childTable, String leafColumn, String fkColumn,
                                 String ownerTable, String ownerIdColumn, boolean ascending) {
        return sql(QueryCompiler.correlatedSort(dialect, childTable, leafColumn, fkColumn,
                ownerTable, ownerIdColumn, ascending));
    }

    public String selectByFk(String table, String whereColumn, String paramName, String projection) {
        return sql(QueryCompiler.selectByFk(dialect, table, whereColumn, paramName, projection));
    }

    public String rowMapperProjection(TypeDefinition type, boolean associationAliasIsName) {
        return QueryCompiler.rowMapperProjection(dialect, type, associationAliasIsName);
    }

    // --------------------------------------------------------------- splice primitives

    public String aggregateSortTerm(ColumnPlacement placement, boolean ascending) {
        return QueryCompiler.aggregateSortTerm(dialect, requireTypeModel(), placement, ascending);
    }

    public static String paramName(String path) {
        return QueryCompiler.paramName(path);
    }

    private TypeDefinition requireTypeModel() {
        if (typeModel == null) {
            throw new IllegalStateException("this SqlBuilder has no type model — construct it with "
                    + "SqlBuilder(TypeDefinition)");
        }
        return typeModel;
    }
}
