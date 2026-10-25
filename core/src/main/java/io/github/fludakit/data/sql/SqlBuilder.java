package io.github.fludakit.data.sql;

import io.github.fludakit.data.metadata.ColumnPlacement;
import io.github.fludakit.data.metadata.ColumnRef;
import io.github.fludakit.data.metadata.ColumnResolver;
import io.github.fludakit.data.metadata.JoinEdge;
import io.github.fludakit.data.metadata.NonLocalAttributePathException;
import io.github.fludakit.data.metadata.TableSchema;
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
 * The engine's SQL façade for callers that assemble SQL at RUNTIME — above all the generated
 * repository implementations, which hold one of these as a field and splice dynamic fragments
 * (seek predicates, cursor ORDER BY, limit) onto a pre-baked shell.
 *
 * <pre>
 * new SqlBuilder(schema, resolver, model).dialect(H2Dialect.INSTANCE).select();
 * new SqlBuilder(schema, resolver).insert(false);
 * new SqlBuilder(schema).deleteById();
 * </pre>
 *
 * <p><b>This class renders nothing of its own.</b> Every method delegates to
 * {@link QueryCompiler}, which is the ONE structured-query renderer, or to {@link SqlFragment},
 * which is the ONE ordered statement accumulator, so every statement is rendered by the same
 * code and cannot drift.</p>
 *
 * <p>Physical lifecycle statements use a {@link TableSchema} for one table; structured queries also
 * use an {@link ColumnResolver} for entity-rooted paths. {@link #select()} / {@link #count()} /
 * {@link #delete()} render a whole query method from its {@link QueryDefinition}; the splice
 * primitives ({@link #qualify(ColumnRef)}, {@link #joinClause(List)},
 * {@link #aggregateSortTerm(ColumnRef, List, boolean)}, ...) are the same rendering pieces exposed
 * as instance methods for runtime assembly. Generated SQL syntax is supplied by the
 * {@link SqlDialect}; {@link H2Dialect} is the default and only shipped backend.
 * {@link #paramName(String)} is the one pure (stateless) helper kept here for generated-code
 * parity.</p>
 */
public final class SqlBuilder {

    private final TableSchema schema;
    private final ColumnResolver resolver;
    private final QueryDefinition.Criteria model;
    private SqlDialect dialect;

    /** A schema-only builder for lifecycle statements and splice primitives. */
    public SqlBuilder(TableSchema schema) {
        this(schema, null, null);
    }

    /** A convenience for generated code: a {@link TypeDefinition} is both schema and resolver. */
    public SqlBuilder(TypeDefinition metadata) {
        this(metadata, metadata, null);
    }

    /** A schema + resolver builder for lifecycle statements that bind logical paths. */
    public SqlBuilder(TableSchema schema, ColumnResolver resolver) {
        this(schema, resolver, null);
    }

    /**
     * A full structured-query builder. The model is a {@link QueryDefinition.Criteria} — a read, a
     * count, an exists or a delete-by-criteria — because those are the statements that resolve paths
     * and plan joins. Lifecycle statements need no model: {@link #insert(boolean)}, {@link #update()}
     * and {@link #deleteById()} build theirs on the spot.
     */
    public SqlBuilder(TableSchema schema, ColumnResolver resolver, QueryDefinition.Criteria model) {
        this.schema = Objects.requireNonNull(schema, "schema");
        this.resolver = resolver;
        this.model = model;
        this.dialect = H2Dialect.INSTANCE;
    }

    /** Overrides the dialect used by every subsequent rendering. Returns {@code this}. */
    public SqlBuilder dialect(SqlDialect dialect) {
        this.dialect = Objects.requireNonNull(dialect, "dialect");
        return this;
    }

    // --------------------------------------------------------------- the one terminal

    /**
     * Renders any statement — the single terminal every other one funnels into. The variant decides
     * the handling: a lifecycle statement renders straight from the table shape, a criteria statement
     * is resolved, planned and assembled.
     */
    public String render(QueryDefinition definition) {
        return render(definition, null);
    }

    /** As {@link #render(QueryDefinition)}, carrying a joined full-materialisation projection. */
    public String render(QueryDefinition definition, String extraProjection) {
        return QueryCompiler.render(schema, resolver, dialect, definition, extraProjection);
    }

    /** As {@link #render(QueryDefinition, String)}, additionally seeding the join chain with {@code extraEdges}. */
    public String render(QueryDefinition definition, List<JoinEdge> extraEdges, String extraProjection) {
        return QueryCompiler.render(schema, resolver, dialect, definition, extraEdges, extraProjection);
    }

    // --------------------------------------------------------------- structured terminals

    /** Renders the builder's query model as a SELECT (FIND). */
    public String select() {
        return select(null);
    }

    /** Renders the query model as a SELECT carrying a joined full-materialisation projection. */
    public String select(String extraProjection) {
        return render(requireModel(), extraProjection);
    }

    /** Renders the query model as a COUNT or EXISTS. */
    public String count() {
        return render(requireModel());
    }

    /** Renders the query model as a DELETE by criteria. */
    public String delete() {
        return render(requireModel());
    }

    // --------------------------------------------------------------- lifecycle terminals

    /** Renders a classic no-join SELECT over the builder's schema. */
    public String selectBy(List<Predicate> predicates, List<SortSpec> sorts, int limit, String cursorClause) {
        return QueryCompiler.selectBy(dialect, schema, predicates, sorts, limit, cursorClause);
    }

    /** Renders a classic no-join SELECT over the builder's schema, optionally {@code DISTINCT}. */
    public String selectBy(List<Predicate> predicates, List<SortSpec> sorts, int limit, String cursorClause, boolean distinct) {
        return QueryCompiler.selectBy(dialect, schema, predicates, sorts, limit, cursorClause, distinct);
    }

    /** Renders a classic no-join COUNT over the builder's schema. */
    public String countBy(List<Predicate> predicates) {
        return QueryCompiler.countBy(dialect, schema, predicates, false);
    }

    /** Renders a classic no-join DELETE over the builder's schema. */
    public String deleteBy(List<Predicate> predicates) {
        return QueryCompiler.deleteBy(dialect, schema, predicates);
    }

    /** Renders an INSERT including the identifier column. */
    public String insert() {
        return insert(true);
    }

    /**
     * Renders an INSERT; {@code includeId=false} omits a database-generated identity column.
     *
     * <p>The result kind does not change the SQL — an INSERT is the same statement whether the caller
     * wants nothing, the entity back, or the row count — but it is part of the statement's
     * {@link QueryDefinition}, so it is taken here rather than defaulted silently.</p>
     */
    public String insert(boolean includeId) {
        return insert(includeId, ResultKind.VOID);
    }

    /** As {@link #insert(boolean)}, declaring what the caller gets back. */
    public String insert(boolean includeId, ResultKind resultKind) {
        return render(new QueryDefinition.Insert(resultKind, includeId));
    }

    /**
     * Renders an upsert — insert the row, or update it when the identifier is already there — in the
     * one-statement form this builder's dialect declares.
     *
     * <p>The identifier is the match key, so it rides along; a null identifier has nothing to match on
     * and stays a plain {@link #insert(boolean)}. Throws when the dialect declares
     * {@link UpsertForm#NONE} — such a database has no one-statement spelling, and the caller must
     * issue an update followed by an insert instead.</p>
     */
    public String upsert() {
        return upsert(ResultKind.VOID);
    }

    /** As {@link #upsert()}, declaring what the caller gets back. */
    public String upsert(ResultKind resultKind) {
        return render(new QueryDefinition.Upsert(resultKind));
    }

    /** Renders an UPDATE by identifier. */
    public String update() {
        return update(ResultKind.VOID);
    }

    /** As {@link #update()}, declaring what the caller gets back. */
    public String update(ResultKind resultKind) {
        return render(new QueryDefinition.Update(resultKind));
    }

    /** Renders a DELETE by identifier. */
    public String deleteById() {
        return deleteById(ResultKind.VOID);
    }

    /** As {@link #deleteById()}, declaring what the caller gets back. */
    public String deleteById(ResultKind resultKind) {
        return render(new QueryDefinition.DeleteById(resultKind));
    }

    /**
     * Renders a cascade DELETE of the named child table's rows by the parent's foreign key.
     * The child table travels with the statement; this builder's schema is not used.
     */
    public String deleteByFk(String table, String fkColumn) {
        return render(new QueryDefinition.DeleteByFk(table, fkColumn));
    }

    /** Renders the read of this builder's table foreign-key column for one identifier. */
    public String selectForeignKey(String fkColumn) {
        return render(new QueryDefinition.SelectForeignKey(fkColumn));
    }

    // --------------------------------------------------------------- splice primitives

    /** Qualifies a table-local column by its owner table, or the schema's root table. */
    public String qualify(ColumnRef columnRef) {
        return qualify(columnRef, schema.tableName());
    }

    /** Qualifies a column against an explicit root table. */
    public String qualify(ColumnRef columnRef, String rootTable) {
        return QueryCompiler.qualify(dialect, columnRef, rootTable);
    }

    /** The deduplicated {@code LEFT JOIN} chain these relation edges require (empty when none). */
    public String joinClause(List<JoinEdge> edges) {
        return QueryCompiler.joinClause(dialect, edges);
    }

    /**
     * As {@link #joinClause(List)}, dropping every edge onto a table already named in
     * {@code alreadyJoined}: the generated paged read splices this onto a base constant that, when
     * composing to-one targets are JOIN-projected, already carries its own {@code LEFT JOIN} chain —
     * repeating an alias makes every reference to it ambiguous.
     */
    public String joinClause(List<JoinEdge> edges, Collection<String> alreadyJoined) {
        return QueryCompiler.joinClause(dialect, edges, alreadyJoined);
    }

    /**
     * Resolves an entity-rooted logical attribute path to its table-local physical column. A path
     * that traverses a relationship (its placement carries edges) has no single table-local column
     * and is rejected with {@link NonLocalAttributePathException}, so callers fall back to
     * relation-aware resolution (an aggregate sort term) — a path that does not exist at all fails
     * with {@link UnknownAttributePathException} instead, and the two must stay distinct.
     */
    public String resolveColumn(String path) {
        ColumnPlacement placement = requireResolver().resolve(path);
        if (!placement.local()) {
            throw new NonLocalAttributePathException(path, placement);
        }
        return placement.leaf().column();
    }

    /**
     * Renders the keyset (cursor) seek predicate over PRE-RENDERED sort terms, expanded as
     * {@code (t0 > :cursor0) OR (t0 = :cursor0 AND t1 > :cursor1) OR ...}.
     *
     * <p>Every PRECEDING key must compare with {@code =}, never with the direction operator: using
     * {@code >} there makes each disjunct a subset of the previous one, so the whole predicate
     * degenerates to {@code t0 > :cursor0} and every row sharing the leading key becomes unreachable
     * from page two onward. Preceding keys use NULL-safe equality because a cursor key is
     * legitimately null (an empty or all-null to-many list reduces to a null edge) and
     * {@code t = NULL} is UNKNOWN, not true.</p>
     *
     * <p>Callers pass the same terms {@link #orderBy(List)} renders, so seek and sort agree
     * byte-for-byte; terms are already quoted (and, for a relation-sorted read, table-qualified or an
     * aggregate subquery), so they are spliced verbatim.</p>
     *
     * @param terms     the rendered sort terms, in cursor-key order
     * @param ascending the sort direction of each key
     * @param forward   {@code true} for {@code CURSOR_NEXT}, {@code false} for {@code CURSOR_PREVIOUS}
     * @return the parenthesised predicate, binding {@code :cursor0 .. :cursorN-1}
     */
    public String seekClause(List<String> terms, List<Boolean> ascending, boolean forward) {
        return SeekClause.render(terms, ascending, forward);
    }

    /**
     * Assembles a keyset-seek read statement on a pre-rendered {@code shell}: the shell, the seek
     * predicate joined to it by {@code WHERE}/{@code AND} (whichever the shell still needs), and the
     * shared {@code orderBy}. This is the composition the paged emitter used to do with {@code +} in
     * the processor; it lives here so the clause order and connector are core's decision.
     *
     * <p>The LIMIT is deliberately NOT part of it. The fetch-one-extra row count is a RUNTIME value, so
     * the caller splices {@code limitClause} onto the finished statement; baking the clause here would
     * leave a trailing-space fragment that cannot be a formatted text block. The returned statement ends
     * on {@code orderBy} — no boundary whitespace — so it is one clean, formable constant.</p>
     *
     * @param shell         the pre-rendered base SELECT shell (projection, FROM, any base predicates)
     * @param shellHasWhere whether {@code shell} already carries a WHERE the seek must AND onto
     * @param terms         the rendered sort terms, in cursor-key order
     * @param ascending     the sort direction of each key
     * @param forward       {@code true} for {@code CURSOR_NEXT}, {@code false} for {@code CURSOR_PREVIOUS}
     * @param orderBy       the rendered ORDER BY clause (leading space, no trailing space)
     * @return the seek statement, ending at {@code orderBy} with no boundary whitespace
     */
    public String pagedSeekStatement(String shell, boolean shellHasWhere, List<String> terms,
                                     List<Boolean> ascending, boolean forward, String orderBy) {
        return shell + (shellHasWhere ? " AND " : " WHERE ")
                + seekClause(terms, ascending, forward) + orderBy;
    }

    /** Whether the first segment of an entity-rooted path is a to-many relation or collection. */
    public boolean isToManyPath(String path) {
        return requireResolver().isToManyPath(path);
    }

    /**
     * The correlated {@code MIN}/{@code MAX} scalar subquery a to-many sort orders by.
     *
     * <p>Takes a {@link ColumnPlacement} rather than a leaf and an edge list so the two cannot be
     * handed over mismatched — the edges are the ones the placement was resolved with.</p>
     */
    public String aggregateSortTerm(ColumnPlacement placement, boolean ascending) {
        return QueryCompiler.aggregateSortTerm(dialect, schema, placement, ascending);
    }

    /** The EXISTS conjunct a predicate on an existential (or DELETE-traversed) ref renders. */
    public String existsConjunct(Predicate predicate, ColumnPlacement placement) {
        return QueryCompiler.existsConjunct(dialect, predicate, placement);
    }

    /**
     * Renders ONE predicate with its column qualified by {@code placement}'s leaf.
     *
     * <p>Named for what it renders rather than {@code render}, because the sibling
     * {@link #render(QueryDefinition)} renders a whole STATEMENT from a query model and this renders a
     * fragment of one from a single predicate — same verb, different granularity, and the two were
     * indistinguishable at a call site. It now reads as a pair with {@link #existsConjunct}, the
     * other single-predicate renderer.</p>
     */
    public String renderPredicate(Predicate predicate, ColumnPlacement placement) {
        return QueryCompiler.render(dialect, predicate, placement);
    }

    /** The root-table projection/join shell ({@code SELECT ... FROM ... LEFT JOIN ...}). */
    public String qualifiedRootSelect(List<JoinEdge> traversedToOne, boolean qualified, String extraProjection) {
        return QueryCompiler.qualifiedRootSelect(dialect, schema, traversedToOne, qualified, extraProjection);
    }

    /** Renders the ORDER BY clause for the given sort paths, resolving each through the resolver. */
    public String orderBy(List<SortSpec> sorts) {
        if (sorts.isEmpty()) {
            return "";
        }
        return " ORDER BY " + sorts.stream()
                .map(s -> dialect.quoteIdentifier(resolveColumn(s.property()))
                        + (s.ascending() ? " ASC" : " DESC"))
                .collect(Collectors.joining(", "));
    }

    /**
     * Maps a dot-separated property path to the named-parameter token used in the generated SQL
     * (e.g. {@code "address.street"} becomes {@code "address_street"}). Callers that bind parameters
     * programmatically must use the same mapping so the emitted {@code .param(...)} names line up.
     */
    public static String paramName(String path) {
        return QueryCompiler.paramName(path);
    }

    /**
     * How many values an operator binds: none for the nullary ones, two for {@code BETWEEN}, one
     * otherwise. Callers that allocate method arguments to predicates must use this so the tokens
     * rendered by {@link QueryCompiler#paramNames} and the values bound at runtime stay in step.
     */
    public static int parameterCount(Operator operator) {
        return QueryCompiler.parameterCount(operator);
    }

    /** The named-parameter tokens {@code operator} renders for {@code param}, in binding order. */
    public static List<String> paramNames(Operator operator, String param) {
        return QueryCompiler.paramNames(operator, param);
    }

    // ------------------------------------------------------------------- internals

    private QueryDefinition.Criteria requireModel() {
        if (model == null) {
            throw new IllegalStateException("this SqlBuilder has no query model — construct it with "
                    + "SqlBuilder(TableSchema, ColumnResolver, QueryDefinition)");
        }
        return model;
    }

    private ColumnResolver requireResolver() {
        if (resolver == null) {
            throw new IllegalStateException("this SqlBuilder has no attribute resolver — construct it with "
                    + "SqlBuilder(TableSchema, ColumnResolver[, QueryDefinition])");
        }
        return resolver;
    }
}
