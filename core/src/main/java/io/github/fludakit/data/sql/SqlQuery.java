package io.github.fludakit.data.sql;

import io.github.fludakit.data.metadata.ColumnResolver;
import io.github.fludakit.data.metadata.ColumnPlacement;
import io.github.fludakit.data.metadata.ColumnRef;
import io.github.fludakit.data.metadata.JoinEdge;
import io.github.fludakit.data.metadata.TableSchema;
import io.github.fludakit.data.query.Connector;
import io.github.fludakit.data.query.LimitSpec;
import io.github.fludakit.data.query.Operation;
import io.github.fludakit.data.query.Operator;
import io.github.fludakit.data.query.PagingMode;
import io.github.fludakit.data.query.Predicate;
import io.github.fludakit.data.query.QueryDefinition;
import io.github.fludakit.data.query.ResultKind;
import io.github.fludakit.data.query.SortSpec;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * The ONE implementation behind every {@link Sql} query stage.
 *
 * <p>A single object walks the chain: each stage interface exposes only the clauses SQL allows at
 * that point, so the compiler — not a run-time check — rejects {@code columns(..)} after
 * {@code where(..)}, {@code and(..)} after {@code orderBy(..)}, and {@code orderBy(..)} after
 * {@code limit(..)}. Nothing is rendered until {@code toSql()}, so a half-built chain has no SQL to
 * be wrong about.</p>
 *
 * <p>Because the object outlives each stage, the methods shared by several stages
 * ({@code dialect}, {@code where}, {@code and}, {@code or}) return this concrete type: it is a
 * subtype of every stage's declared return, which is what lets one implementation satisfy all four
 * interfaces. Callers only ever see the stage type through the {@link Sql} entry points.</p>
 *
 * <p>The chain accumulates LOGICAL state (paths, operators, sort specs, the row window) and
 * delegates every physical decision — path to column, predicate to join or EXISTS, sort to aggregate
 * subquery — to {@link QueryCompiler}. That is the split that keeps one rendering of the truth.</p>
 */
final class SqlQuery implements Sql.QueryDraft, Sql.Predicated, Sql.Ordered, Sql.Limited, Sql.Filtered {

    private final TableSchema schema;
    private final ColumnResolver resolver;
    private final Operation operation;
    private SqlDialect dialect = H2Dialect.INSTANCE;

    private List<String> columns = List.of();
    private boolean distinct;
    private final List<Predicate> predicates = new ArrayList<>();
    private final List<SortSpec> sorts = new ArrayList<>();
    private int limit;
    private int offset;

    private SqlQuery(TableSchema schema, ColumnResolver resolver, Operation operation) {
        this.schema = Objects.requireNonNull(schema, "schema");
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.operation = Objects.requireNonNull(operation, "operation");
    }

    static SqlQuery query(TableSchema schema, ColumnResolver resolver, Operation operation) {
        return new SqlQuery(schema, resolver, operation);
    }

    // ------------------------------------------------------------------- stage one

    @Override
    public SqlQuery dialect(SqlDialect dialect) {
        this.dialect = Objects.requireNonNull(dialect, "dialect");
        return this;
    }

    @Override
    public SqlQuery columns(String... paths) {
        return columns(List.of(paths));
    }

    @Override
    public SqlQuery columns(List<String> paths) {
        this.columns = List.copyOf(Objects.requireNonNull(paths, "paths"));
        return this;
    }

    @Override
    public SqlQuery distinct() {
        this.distinct = true;
        return this;
    }

    // ------------------------------------------------------------------- predicates

    @Override
    public SqlQuery where(Predicate predicate) {
        return add(predicate, true);
    }

    @Override
    public SqlQuery where(String property, Operator operator) {
        return where(property, operator, QueryCompiler.paramName(property));
    }

    @Override
    public SqlQuery where(String property, Operator operator, String param) {
        return where(Predicate.of(property, operator, param));
    }

    @Override
    public SqlQuery and(Predicate predicate) {
        return add(predicate, true);
    }

    @Override
    public SqlQuery and(String property, Operator operator) {
        return and(property, operator, QueryCompiler.paramName(property));
    }

    @Override
    public SqlQuery and(String property, Operator operator, String param) {
        return and(Predicate.of(property, operator, param));
    }

    @Override
    public SqlQuery or(Predicate predicate) {
        return add(predicate, false);
    }

    @Override
    public SqlQuery or(String property, Operator operator) {
        return or(property, operator, QueryCompiler.paramName(property));
    }

    @Override
    public SqlQuery or(String property, Operator operator, String param) {
        return or(Predicate.of(property, operator, param));
    }

    /**
     * Appends one predicate. The connector is derived from position and intent: the first predicate
     * is {@code AND} regardless, because a leading {@code OR} has nothing to disjoin and every
     * downstream renderer ignores entry zero's connector anyway.
     */
    private SqlQuery add(Predicate predicate, boolean andJoined) {
        Objects.requireNonNull(predicate, "predicate");
        Connector connector = predicates.isEmpty() || andJoined ? Connector.AND : Connector.OR;
        predicates.add(new Predicate(predicate.property(), predicate.operator(), predicate.param(),
                connector, predicate.ignoreCase()));
        return this;
    }

    // ------------------------------------------------------------------- ordering

    @Override
    public SqlQuery orderBy(SortSpec... sorts) {
        return orderBy(List.of(sorts));
    }

    @Override
    public SqlQuery orderBy(List<SortSpec> sorts) {
        Objects.requireNonNull(sorts, "sorts");
        this.sorts.addAll(sorts);
        return this;
    }

    @Override
    public SqlQuery asc(String property) {
        return orderBy(SortSpec.of(property, true, false));
    }

    @Override
    public SqlQuery desc(String property) {
        return orderBy(SortSpec.of(property, false, false));
    }

    // ------------------------------------------------------------------- row window

    @Override
    public SqlQuery limit(int limit) {
        this.limit = limit;
        return this;
    }

    @Override
    public SqlQuery offset(int offset) {
        this.offset = offset;
        return this;
    }

    // ------------------------------------------------------------------- rendering

    @Override
    public String toSql() {
        QueryDefinition.Criteria model = definition();
        // Neither a custom projection nor an offset: the engine's own rendering, legacy
        // short-circuit included. Both are features the pre-join classic statement never had, so
        // asking for either bypasses it. DISTINCT rides on the model, so both paths honour it.
        if (columns.isEmpty() && offset <= 0) {
            return QueryCompiler.render(schema, resolver, dialect, model, null);
        }
        // The joins a projection needs must reach the plan BEFORE it decides whether the statement
        // joins: that decision qualifies every leaf, and a WHERE term left bare next to a qualified
        // projection is ambiguous the moment the joined table shares a column name.
        List<ColumnPlacement> refs = columns.stream()
                .map(path -> QueryCompiler.resolveColumn(schema, resolver, path))
                .collect(Collectors.toList());
        List<JoinEdge> projectionEdges = refs.stream()
                .flatMap(ref -> ref.edges().stream())
                .collect(Collectors.toList());
        QueryCompiler.Plan plan = QueryCompiler.plan(schema, resolver, dialect, model, projectionEdges);
        SqlFragment fragment = QueryCompiler.fragment(schema, dialect, model, plan, null);
        if (!refs.isEmpty()) {
            boolean splicesJoins = !fragment.joins().isEmpty();
            fragment.projection(refs.stream()
                    .map(ref -> renderColumn(ref, splicesJoins))
                    .collect(Collectors.joining(", ")));
        }
        if (offset > 0) {
            fragment.offset(offset);
        }
        return fragment.render(dialect);
    }

    private String renderColumn(ColumnPlacement placement, boolean splicesJoins) {
        ColumnRef leaf = placement.leaf();
        return QueryCompiler.isLocalTo(leaf, schema.tableName()) && !splicesJoins
                ? dialect.quoteIdentifier(leaf.column())
                : QueryCompiler.qualify(dialect, leaf, schema.tableName());
    }

    /**
     * The statement this chain describes. Each variant takes only what it can use: a count has no
     * ordering and no row window to carry, and a delete has no result shape to declare.
     */
    private QueryDefinition.Criteria definition() {
        return switch (operation) {
            case COUNT -> new QueryDefinition.Count(predicates, List.of(), distinct);
            case EXISTS -> new QueryDefinition.Exists(predicates, List.of());
            case DELETE -> new QueryDefinition.DeleteWhere(predicates, List.of());
            default -> new QueryDefinition.Read(ResultKind.LIST, predicates, sorts, PagingMode.NONE,
                    new LimitSpec(limit, offset), List.of(), List.of(), distinct);
        };
    }
}
