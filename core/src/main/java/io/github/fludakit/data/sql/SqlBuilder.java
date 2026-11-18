package io.github.fludakit.data.sql;

import io.github.fludakit.data.metadata.ColumnPlacement;
import io.github.fludakit.data.metadata.ColumnRef;
import io.github.fludakit.data.metadata.JoinEdge;
import io.github.fludakit.data.metadata.NonLocalAttributePathException;
import io.github.fludakit.data.metadata.TypeDefinition;
import io.github.fludakit.data.query.Operator;
import io.github.fludakit.data.query.Predicate;
import io.github.fludakit.data.query.QueryDefinition;
import io.github.fludakit.data.query.ResultKind;
import io.github.fludakit.data.query.SortSpec;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Turns an {@link SqlFragment} into a SQL literal. Compilation belongs to {@link QueryCompiler};
 * this class only renders, optionally formats, and splices call-time clauses (seek, order, limit).
 *
 * <pre>
 * new SqlBuilder(typeModel).dialect(dialect).sql(definition);
 * new SqlBuilder(dialect, SqlFormat.PRETTY).sql(fragment);
 * </pre>
 */
public final class SqlBuilder {

    private final TypeDefinition typeModel;
    private SqlDialect dialect;
    private SqlFormat format;

    /** Dialect + format only — for rendering fragments and runtime splices. */
    public SqlBuilder(SqlDialect dialect, SqlFormat format) {
        this(null, dialect, format);
    }

    /** Type model as both physical table shape and attribute-path model. */
    public SqlBuilder(TypeDefinition typeModel) {
        this(typeModel, H2Dialect.INSTANCE, SqlFormat.COMPACT);
    }

    private SqlBuilder(TypeDefinition typeModel, SqlDialect dialect, SqlFormat format) {
        this.typeModel = typeModel;
        this.dialect = Objects.requireNonNull(dialect, "dialect");
        this.format = Objects.requireNonNull(format, "format");
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

    /**
     * @deprecated prefer {@link #sql(QueryDefinition)}
     */
    @Deprecated
    public String render(QueryDefinition definition) {
        return sql(definition);
    }

    /**
     * @deprecated prefer {@link #sql(QueryDefinition, List, String)}
     */
    @Deprecated
    public String render(QueryDefinition definition, String extraProjection) {
        return sql(definition, List.of(), extraProjection);
    }

    /**
     * @deprecated prefer {@link #sql(QueryDefinition, List, String)}
     */
    @Deprecated
    public String render(QueryDefinition definition, List<JoinEdge> extraEdges, String extraProjection) {
        return sql(definition, extraEdges, extraProjection);
    }

    // --------------------------------------------------------------- runtime splices

    /**
     * Keyset seek predicate over pre-rendered sort terms.
     *
     * @see SeekClause#render(List, List, boolean)
     */
    public String withSeek(List<String> terms, List<Boolean> ascending, boolean forward) {
        return SeekClause.render(terms, ascending, forward);
    }

    /**
     * Assembles a cursor-paged seek statement: shell + null-guarded seek + ORDER BY. No LIMIT —
     * the caller splices that at runtime.
     */
    public String withSeek(String shell, boolean shellHasWhere, List<String> terms,
                           List<Boolean> ascending, boolean forward, List<String> orderByTerms,
                           List<String> aggregateExpressions, String rootTable) {
        return pagedSeekStatement(shell, shellHasWhere, terms, ascending, forward, orderByTerms,
                aggregateExpressions, rootTable);
    }

    /** Appends {@code ORDER BY} from pre-rendered terms. */
    public String withOrder(String shell, List<String> terms, List<Boolean> ascending) {
        return shell + renderOrderBy(terms, ascending);
    }

    /** Appends the dialect's limit/offset clause. */
    public String withLimit(String sql, String limitExpression, String offsetExpression) {
        if (offsetExpression != null && !offsetExpression.isBlank()) {
            return sql + dialect.limitOffsetClause(limitExpression, offsetExpression);
        }
        return sql + dialect.limitClause(limitExpression);
    }

    /**
     * @deprecated prefer {@link #withSeek(List, List, boolean)}
     */
    @Deprecated
    public String seekClause(List<String> terms, List<Boolean> ascending, boolean forward) {
        return withSeek(terms, ascending, forward);
    }

    /**
     * Full keyset-seek statement. Prefer
     * {@link #withSeek(String, boolean, List, List, boolean, List, List, String)}.
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

    public String insert() {
        return insert(true);
    }

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

    public QueryCompiler.RenderedCursorSortProjection cursorSortProjection(
            QueryCompiler.CursorSortProjection projection) {
        return QueryCompiler.renderCursorSortProjection(dialect, requireTypeModel(), projection);
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

    public String selectBy(List<Predicate> predicates, List<SortSpec> sorts, int limit, String cursorClause) {
        return QueryCompiler.selectBy(dialect, requireTypeModel(), predicates, sorts, limit, cursorClause);
    }

    public String selectBy(List<Predicate> predicates, List<SortSpec> sorts, int limit, String cursorClause,
                           boolean distinct) {
        return QueryCompiler.selectBy(dialect, requireTypeModel(), predicates, sorts, limit, cursorClause, distinct);
    }

    public String countBy(List<Predicate> predicates) {
        return QueryCompiler.countBy(dialect, requireTypeModel(), predicates, false);
    }

    public String deleteBy(List<Predicate> predicates) {
        return QueryCompiler.deleteBy(dialect, requireTypeModel(), predicates);
    }

    // --------------------------------------------------------------- splice primitives

    public String qualify(ColumnRef columnRef) {
        return qualify(columnRef, requireTypeModel().tableName());
    }

    public String qualify(ColumnRef columnRef, String rootTable) {
        return QueryCompiler.qualify(dialect, columnRef, rootTable);
    }

    public String joinClause(List<JoinEdge> edges) {
        return QueryCompiler.joinClause(dialect, edges);
    }

    public String joinClause(List<JoinEdge> edges, Collection<String> alreadyJoined) {
        return QueryCompiler.joinClause(dialect, edges, alreadyJoined);
    }

    public String resolveColumn(String path) {
        ColumnPlacement placement = requireTypeModel().resolve(path);
        if (!placement.local()) {
            throw new NonLocalAttributePathException(path, placement);
        }
        return placement.leaf().column();
    }

    public boolean isToManyPath(String path) {
        return requireTypeModel().isToManyPath(path);
    }

    public String aggregateSortTerm(ColumnPlacement placement, boolean ascending) {
        return QueryCompiler.aggregateSortTerm(dialect, requireTypeModel(), placement, ascending);
    }

    public String existsConjunct(Predicate predicate, ColumnPlacement placement) {
        return QueryCompiler.existsConjunct(dialect, predicate, placement);
    }

    public String renderPredicate(Predicate predicate, ColumnPlacement placement) {
        return QueryCompiler.renderPredicate(dialect, predicate, placement);
    }

    public String qualifiedRootSelect(List<JoinEdge> traversedToOne, boolean qualified, String extraProjection) {
        return QueryCompiler.qualifiedRootSelect(dialect, requireTypeModel(), traversedToOne, qualified, extraProjection);
    }

    public String orderBy(List<SortSpec> sorts) {
        if (sorts.isEmpty()) {
            return "";
        }
        return " ORDER BY " + sorts.stream()
                .map(s -> dialect.quoteIdentifier(resolveColumn(s.property()))
                        + (s.ascending() ? " ASC" : " DESC"))
                .collect(Collectors.joining(", "));
    }

    public static String paramName(String path) {
        return QueryCompiler.paramName(path);
    }

    public static int parameterCount(Operator operator) {
        return QueryCompiler.parameterCount(operator);
    }

    public static List<String> paramNames(Operator operator, String param) {
        return QueryCompiler.paramNames(operator, param);
    }

    private TypeDefinition requireTypeModel() {
        if (typeModel == null) {
            throw new IllegalStateException("this SqlBuilder has no type model — construct it with "
                    + "SqlBuilder(TypeDefinition)");
        }
        return typeModel;
    }
}
