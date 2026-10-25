package io.github.fludakit.data.sql;

import io.github.fludakit.data.Names;
import io.github.fludakit.data.metadata.ColumnPlacement;
import io.github.fludakit.data.metadata.ColumnRef;
import io.github.fludakit.data.metadata.ColumnResolver;
import io.github.fludakit.data.metadata.JoinEdge;
import io.github.fludakit.data.metadata.NavigationPlan;
import io.github.fludakit.data.metadata.TableSchema;
import io.github.fludakit.data.metadata.TypeDefinition;
import io.github.fludakit.data.metadata.UnknownAttributePathException;
import io.github.fludakit.data.query.Connector;
import io.github.fludakit.data.query.Operation;
import io.github.fludakit.data.query.Operator;
import io.github.fludakit.data.query.PagingMode;
import io.github.fludakit.data.query.Predicate;
import io.github.fludakit.data.query.QueryDefinition;
import io.github.fludakit.data.query.SortSpec;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Compiles a logical {@link QueryDefinition} into physical SQL: one pass resolves and classifies
 * every predicate and sort ref, then assembles the whole statement as a {@link SqlFragment}.
 *
 * <p>This is the engine's only structured-query renderer. {@link SqlBuilder} exposes it as instance
 * methods for callers that splice dynamic SQL at runtime (the generated paged readers); it owns no
 * rendering logic of its own, so there is exactly one place where a logical path becomes a
 * physical column.</p>
 *
 * <h2>The layout rules</h2>
 * <ul>
 * <li>An existential (to-many head) predicate ref renders as an
 *     {@link #existsConjunct EXISTS} conjunct and contributes NO join; in DELETE mode every
 *     traversed predicate ref does, because a joined {@code DELETE ... LEFT JOIN} is invalid SQL on
 *     H2/PostgreSQL/SQL Server. The DELETE-mode suppression inspects predicate refs only: an emitted
 *     delete model always carries empty sorts, so a hand-built model with {@code Operation.DELETE}
 *     and joined sort refs would render an invalid statement — joined sort refs are not supported
 *     for DELETE.</li>
 * <li>An existential sort renders an {@link #aggregateSortTerm} and contributes NO join; only
 *     to-one predicate refs and to-one sort refs splice the join chain.</li>
 * <li>The classic {@link #selectBy}/{@link #countBy}/{@link #deleteBy} builders stay as a separate
 *     raw-predicates API (no {@code QueryDefinition}, no resolver) for the generated paged readers,
 *     which splice a seek predicate and cursor ORDER BY onto a pre-baked shell at runtime.</li>
 * <li>A {@code model.paging()} other than {@link PagingMode#NONE} renders the BASE constant:
 *     ORDER BY and LIMIT are spliced at runtime by the generated paged read, so the declared sorts
 *     and the static limit are dropped from this rendering.</li>
 * <li>The root-id tiebreak is a runtime cursor concern only; a model's {@code sorts()} render
 *     exactly as declared.</li>
 * </ul>
 * {@code extraProjection} is the joined full-materialisation payload and rides FIND shells only —
 * count and delete ignore it.
 */
public final class QueryCompiler {

    private QueryCompiler() {
    }

    /** One WHERE conjunct plus the connector that joins it to the preceding one. */
    public record Conjunct(String sql, boolean orJoined) {
    }

    /**
     * The resolved shape of one query: which edges to join, which conjuncts to assert in which
     * order, which terms to order by, and whether the whole thing degrades to the pre-join classic
     * statement.
     */
    public record Plan(List<JoinEdge> joins, List<Conjunct> where, List<String> orderBy, int limit) {
    }

    // ------------------------------------------------------------------- entry points

    /**
     * Renders one statement. The single entry point every façade funnels into, so the mapping from
     * a statement to its SQL lives here and nowhere else.
     *
     * <p>Lifecycle statements leave immediately: they have no path to resolve, no join to plan and no
     * predicate to classify, so they render straight from the table shape. Only the criteria
     * statements reach the planner.</p>
     *
     * @param schema      the root table shape
     * @param resolver    entity-rooted attribute path resolver; required by a criteria statement and
     *                    by an INSERT/UPDATE, unused by {@code DeleteById}
     * @param dialect     the SQL syntax to render with
     * @param model       the statement
     * @param extraProjection the joined full-materialisation payload, or {@code null}
     */
    public static String render(TableSchema schema, ColumnResolver resolver, SqlDialect dialect,
                                QueryDefinition model, String extraProjection) {
        return render(schema, resolver, dialect, model, List.of(), extraProjection);
    }

    /**
     * As {@link #render}, additionally seeding the join chain with {@code extraEdges}.
     *
     * <p>Edges a projection contributes rather than a predicate: a caller that asks for
     * {@code author.name} joins the author table even with no predicate on it. Planning must see
     * those BEFORE it decides whether the statement joins, because that decision is what qualifies
     * every leaf — a WHERE term left bare while the projection is qualified is ambiguous the moment
     * the joined table shares a column name.</p>
     */
    public static String render(TableSchema schema, ColumnResolver resolver, SqlDialect dialect,
                                QueryDefinition model, List<JoinEdge> extraEdges, String extraProjection) {
        Objects.requireNonNull(schema, "schema");
        Objects.requireNonNull(dialect, "dialect");
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(extraEdges, "extraEdges");
        return switch (model) {
            case QueryDefinition.Insert insert -> insert(dialect, schema, requireResolver(resolver),
                    insert.includeId());
            case QueryDefinition.Upsert ignored -> upsert(dialect, schema, requireResolver(resolver));
            case QueryDefinition.Update ignored -> update(dialect, schema, requireResolver(resolver));
            case QueryDefinition.DeleteById ignored -> deleteById(dialect, schema);
            case QueryDefinition.DeleteByFk byFk -> deleteByFk(dialect, byFk.table(), byFk.fkColumn());
            case QueryDefinition.SelectForeignKey selectFk -> selectForeignKey(dialect, schema, selectFk.fkColumn());
            case QueryDefinition.Native nativeQuery -> nativeQuery.sql();
            case QueryDefinition.Criteria criteria -> {
                Objects.requireNonNull(resolver, "resolver");
                Plan plan = new QueryPlanner(schema, resolver, dialect, criteria, extraEdges).plan();
                yield fragment(schema, dialect, criteria, plan, extraProjection).render(dialect);
            }
        };
    }

    /**
     * As {@link #render(TableSchema, ColumnResolver, SqlDialect, QueryDefinition, String)},
     * using a {@link NavigationPlan} for alias-aware join rendering.
     */
    public static String render(TableSchema schema, ColumnResolver resolver, SqlDialect dialect,
                                QueryDefinition model, NavigationPlan navPlan, String extraProjection) {
        Objects.requireNonNull(navPlan, "navPlan");
        return switch (model) {
            case QueryDefinition.Insert insert -> insert(dialect, schema, requireResolver(resolver),
                    insert.includeId());
            case QueryDefinition.Upsert ignored -> upsert(dialect, schema, requireResolver(resolver));
            case QueryDefinition.Update ignored -> update(dialect, schema, requireResolver(resolver));
            case QueryDefinition.DeleteById ignored -> deleteById(dialect, schema);
            case QueryDefinition.DeleteByFk byFk -> deleteByFk(dialect, byFk.table(), byFk.fkColumn());
            case QueryDefinition.SelectForeignKey selectFk -> selectForeignKey(dialect, schema, selectFk.fkColumn());
            case QueryDefinition.Native nativeQuery -> nativeQuery.sql();
            case QueryDefinition.Criteria criteria -> {
                Objects.requireNonNull(resolver, "resolver");
                Plan plan = new QueryPlanner(schema, resolver, dialect, criteria, List.of(), navPlan).plan();
                yield fragment(schema, dialect, criteria, plan, extraProjection, navPlan).render(dialect);
            }
        };
    }

    /** Resolves and classifies one criteria statement without rendering it. */
    public static Plan plan(TableSchema schema, ColumnResolver resolver, SqlDialect dialect,
                            QueryDefinition.Criteria model) {
        return plan(schema, resolver, dialect, model, List.of());
    }

    /** As {@link #plan}, seeding the join chain with {@code extraEdges}. */
    public static Plan plan(TableSchema schema, ColumnResolver resolver, SqlDialect dialect,
                            QueryDefinition.Criteria model, List<JoinEdge> extraEdges) {
        return new QueryPlanner(schema, resolver, dialect, model, extraEdges).plan();
    }

    /** As {@link #plan}, using a {@link NavigationPlan} for alias-aware planning. */
    public static Plan plan(TableSchema schema, ColumnResolver resolver, SqlDialect dialect,
                            QueryDefinition.Criteria model, NavigationPlan navPlan) {
        return new QueryPlanner(schema, resolver, dialect, model, List.of(), navPlan).plan();
    }

    private static ColumnResolver requireResolver(ColumnResolver resolver) {
        if (resolver == null) {
            throw new IllegalStateException("this statement binds logical attribute paths and needs a"
                    + " column resolver — construct it with SqlBuilder(TableSchema, ColumnResolver)");
        }
        return resolver;
    }

    // ------------------------------------------------------------------- assembly

    /**
     * Fills a {@link SqlFragment} from a resolved plan. Visible so a caller that has already planned
     * a query can reuse the plan, and so tests can assert on the parts instead of the string.
     */
    public static SqlFragment fragment(TableSchema schema, SqlDialect dialect, QueryDefinition.Criteria model,
                                       Plan plan, String extraProjection) {
        boolean spliceJoins = !plan.joins().isEmpty();
        boolean countMode = model instanceof QueryDefinition.Count || model instanceof QueryDefinition.Exists;
        boolean deleteMode = model instanceof QueryDefinition.DeleteWhere;
        SqlFragment fragment = new SqlFragment().table(schema.tableName()).join(plan.joins());
        if (countMode) {
            // COUNT collapses to COUNT(*), or to COUNT(DISTINCT <id>) when the query asks for
            // distinct rows: a root row is identified by its id, and COUNT(DISTINCT *) is not SQL.
            fragment.keyword("SELECT").projection(distinct(model) && schema.idColumn() != null
                    ? "COUNT(DISTINCT " + dialect.quoteIdentifier(schema.idColumn()) + ")"
                    : "COUNT(*)");
        } else if (deleteMode) {
            fragment.keyword("DELETE");
        } else {
            // Joined find: every root column is qualified by the root table. Unqualified bare names
            // become ambiguous the moment a join adds a table sharing a column name (e.g. the definition's
            // own "id"), and a full-materialisation read joins exactly such a table. Qualifying keeps
            // each label = its physical column, so the generated row mapper still reads it by name.
            fragment.keyword("SELECT")
                    .projection(rootProjection(dialect, schema, spliceJoins, extraProjection))
                    .distinct(distinct(model));
        }
        for (Conjunct conjunct : plan.where()) {
            fragment.where(conjunct.sql(), conjunct.orJoined());
        }
        for (String term : plan.orderBy()) {
            fragment.orderBy(term);
        }
        if (plan.limit() > 0) {
            fragment.limit(plan.limit());
        }
        return fragment;
    }

    /**
     * As {@link #fragment(TableSchema, SqlDialect, QueryDefinition.Criteria, Plan, String)},
     * using a {@link NavigationPlan} for alias-aware join rendering.
     */
    public static SqlFragment fragment(TableSchema schema, SqlDialect dialect, QueryDefinition.Criteria model,
                                       Plan plan, String extraProjection, NavigationPlan navPlan) {
        Objects.requireNonNull(navPlan, "navPlan");
        boolean spliceJoins = !plan.joins().isEmpty();
        boolean countMode = model instanceof QueryDefinition.Count || model instanceof QueryDefinition.Exists;
        boolean deleteMode = model instanceof QueryDefinition.DeleteWhere;

        SqlFragment fragment = new SqlFragment()
                .table(schema.tableName())
                .aliases(navPlan.aliasMap());

        // Use alias-aware join method
        for (JoinEdge edge : plan.joins()) {
            String alias = navPlan.aliasFor(edge.childTable());
            if (alias != null) {
                fragment.join(edge, alias);
            } else {
                fragment.join(edge);
            }
        }

        if (countMode) {
            fragment.keyword("SELECT").projection(distinct(model) && schema.idColumn() != null
                    ? "COUNT(DISTINCT " + dialect.quoteIdentifier(schema.idColumn()) + ")"
                    : "COUNT(*)");
        } else if (deleteMode) {
            fragment.keyword("DELETE");
        } else {
            fragment.keyword("SELECT")
                    .projection(rootProjection(dialect, schema, spliceJoins, extraProjection))
                    .distinct(distinct(model));
        }
        for (Conjunct conjunct : plan.where()) {
            fragment.where(conjunct.sql(), conjunct.orJoined());
        }
        for (String term : plan.orderBy()) {
            fragment.orderBy(term);
        }
        if (plan.limit() > 0) {
            fragment.limit(plan.limit());
        }
        return fragment;
    }

    private static String rootProjection(SqlDialect dialect, TableSchema schema, boolean qualified,
                                         String extraProjection) {
        if (!qualified && extraProjection != null && !extraProjection.isEmpty()) {
            throw new IllegalStateException("bare projection cannot carry extraProjection");
        }
        String columns = schema.columns().stream()
                .map(c -> qualified ? SqlFragment.qualified(dialect, schema.tableName(), c.column())
                        : dialect.quoteIdentifier(c.column()))
                .collect(Collectors.joining(", "));
        return extraProjection == null || extraProjection.isEmpty()
                ? columns
                : columns + ", " + extraProjection;
    }

    /** {@code DISTINCT} is a fact only a read and a count carry; every other statement ignores it. */
    private static boolean distinct(QueryDefinition.Criteria model) {
        return switch (model) {
            case QueryDefinition.Read read -> read.distinct();
            case QueryDefinition.Count count -> count.distinct();
            case QueryDefinition.Exists ignored -> false;
            case QueryDefinition.DeleteWhere ignored -> false;
        };
    }

    // ------------------------------------------------------------------- classic statements

    /** Renders a classic no-join SELECT. */
    public static String selectBy(SqlDialect dialect, TableSchema schema, List<Predicate> predicates,
                                  List<SortSpec> sorts, int limit, String cursorClause) {
        return selectBy(dialect, schema, predicates, sorts, limit, cursorClause, false);
    }

    /** Renders a classic no-join SELECT, optionally {@code DISTINCT}. */
    public static String selectBy(SqlDialect dialect, TableSchema schema, List<Predicate> predicates,
                                  List<SortSpec> sorts, int limit, String cursorClause, boolean distinct) {
        String mainAlias = schema.tableName().toLowerCase() + "_0";
        String cols = schema.columns().stream()
                .map(c -> dialect.quoteIdentifier(mainAlias) + "." + dialect.quoteIdentifier(c.column()))
                .collect(Collectors.joining(", "));
        StringBuilder sb = new StringBuilder(distinct ? "SELECT DISTINCT " : "SELECT ").append(cols)
                .append(" FROM ").append(dialect.quoteIdentifier(schema.tableName()))
                .append(" AS ").append(dialect.quoteIdentifier(mainAlias));
        appendWhere(dialect, sb, predicates, cursorClause);
        sb.append(classicOrderBy(dialect, schema, sorts, mainAlias));
        if (limit > 0) {
            sb.append(dialect.limitClause(Integer.toString(limit)));
        }
        return sb.toString();
    }

    /** Renders a classic no-join COUNT; a distinct count collapses to {@code COUNT(DISTINCT id)}. */
    public static String countBy(SqlDialect dialect, TableSchema schema, List<Predicate> predicates, boolean distinct) {
        // Distinct counts the distinct results: a root row is identified by its id, so the count
        // collapses to COUNT(DISTINCT <id>) — COUNT(DISTINCT *) is not valid SQL.
        String projection = distinct && schema.idColumn() != null
                ? "COUNT(DISTINCT " + dialect.quoteIdentifier(schema.idColumn()) + ")"
                : "COUNT(*)";
        StringBuilder sb = new StringBuilder("SELECT ").append(projection).append(" FROM ")
                .append(dialect.quoteIdentifier(schema.tableName()));
        appendWhere(dialect, sb, predicates, null);
        return sb.toString();
    }

    /** Renders a classic no-join DELETE. */
    public static String deleteBy(SqlDialect dialect, TableSchema schema, List<Predicate> predicates) {
        StringBuilder sb = new StringBuilder("DELETE FROM ").append(dialect.quoteIdentifier(schema.tableName()));
        appendWhere(dialect, sb, predicates, null);
        return sb.toString();
    }

    /** Renders an INSERT; {@code includeId=false} omits a database-generated identity column. */
    public static String insert(SqlDialect dialect, TableSchema schema, ColumnResolver attributes,
                                boolean includeId) {
        List<ColumnRef> cols = schema.columns();
        if (!includeId) {
            cols = cols.stream()
                    .filter(c -> schema.idColumn() == null || !c.column().equals(schema.idColumn()))
                    .toList();
        }
        if (cols.isEmpty()) {
            // An entity whose only column is a database-generated identifier binds NOTHING, and
            // `INSERT INTO t () VALUES ()` is not SQL. The row is still a real insert — every
            // column takes its default and the identity is generated by the database — so the
            // dialect's all-defaults form goes out, and the keyholder roundtrip that reads the
            // generated key back is unchanged.
            return dialect.insertDefaultValues(schema.tableName());
        }
        String names = cols.stream().map(c -> dialect.quoteIdentifier(c.column())).collect(Collectors.joining(", "));
        String params = cols.stream().map(c -> ":" + paramName(attributePath(attributes, c)))
                .collect(Collectors.joining(", "));
        return "INSERT INTO " + dialect.quoteIdentifier(schema.tableName()) + " (" + names + ") VALUES (" + params + ")";
    }

    /**
     * Renders an upsert — insert the row, or update it when the identifier already exists — in
     * whichever single-statement form {@code dialect} declares.
     *
     * <p>The identifier always rides along: it is the match key, so an upsert has no
     * {@code includeId} decision to make. The column list and the {@code :param} tokens are identical
     * across every form — only the dialect's spelling of "or update it" differs, so the spelling
     * lives in {@link SqlDialect#upsertStatement} and this method only computes the shared pieces.</p>
     *
     * @throws IllegalStateException when the dialect declares {@link UpsertForm#NONE}; a caller that
     *         must honour the contract there issues an {@link #update} and, if it matched nothing, an
     *         {@link #insert} carrying the identifier
     */
    public static String upsert(SqlDialect dialect, TableSchema schema, ColumnResolver attributes) {
        List<ColumnRef> cols = schema.columns();
        List<String> names = cols.stream().map(ColumnRef::column).toList();
        List<String> params = cols.stream().map(c -> ":" + paramName(attributePath(attributes, c)))
                .toList();
        return dialect.upsertStatement(schema.tableName(), schema.idColumn(), names, params);
    }

    /** Renders an UPDATE by identifier. */
    public static String update(SqlDialect dialect, TableSchema schema, ColumnResolver attributes) {
        String idCol = dialect.quoteIdentifier(schema.idColumn());
        String sets = schema.columns().stream()
                .filter(c -> !c.column().equals(schema.idColumn()))
                .map(c -> dialect.quoteIdentifier(c.column()) + " = :" + paramName(attributePath(attributes, c)))
                .collect(Collectors.joining(", "));
        if (sets.isEmpty()) {
            // Nothing to assign but the row still has to be touched: `UPDATE t SET  WHERE ...` is not
            // SQL. Assigning the identifier to itself leaves the row exactly as it was while still
            // reporting one affected row when it exists and zero when it does not — which is the only
            // thing the caller's `if (updated == 0) insert` decision reads from it.
            return "UPDATE " + dialect.quoteIdentifier(schema.tableName()) + " SET " + idCol + " = " + idCol
                    + " WHERE " + idCol + " = :id";
        }
        return "UPDATE " + dialect.quoteIdentifier(schema.tableName()) + " SET " + sets + " WHERE " + idCol + " = :id";
    }

    /** Renders a DELETE by identifier. */
    public static String deleteById(SqlDialect dialect, TableSchema schema) {
        return "DELETE FROM " + dialect.quoteIdentifier(schema.tableName()) + " WHERE "
                + dialect.quoteIdentifier(schema.idColumn()) + " = :id";
    }

    /** Renders a cascade {@code DELETE} of one child table's rows by the parent's foreign key. */
    private static String deleteByFk(SqlDialect dialect, String table, String fkColumn) {
        return "DELETE FROM " + dialect.quoteIdentifier(table)
                + " WHERE " + dialect.quoteIdentifier(fkColumn) + " = :parentId";
    }

    /** Renders the context table's foreign-key column read for one identifier. */
    private static String selectForeignKey(SqlDialect dialect, TableSchema schema, String fkColumn) {
        return "SELECT " + dialect.quoteIdentifier(fkColumn)
                + " FROM " + dialect.quoteIdentifier(schema.tableName())
                + " WHERE " + dialect.quoteIdentifier(schema.idColumn()) + " = :id";
    }

    // ------------------------------------------------------------------- splice primitives

    /** Qualifies a table-local column by its owner table, or the supplied root. */
    public static String qualify(SqlDialect dialect, ColumnRef columnRef, String rootTable) {
        String qualifier = columnRef.table() != null ? columnRef.table() : rootTable;
        if (qualifier == null) {
            throw new IllegalStateException(
                    "Cannot qualify column '" + columnRef.column() + "': no owning table or root table");
        }
        return SqlFragment.qualified(dialect, qualifier, columnRef.column());
    }

    /** The deduplicated {@code LEFT JOIN} chain these relation edges require (empty when none). */
    public static String joinClause(SqlDialect dialect, List<JoinEdge> edges) {
        return SqlFragment.joins(dialect, edges);
    }

    /**
     * As {@link #joinClause(SqlDialect, List)}, dropping every edge onto a table already named in
     * {@code alreadyJoined} — the tables a pre-rendered shell the caller splices onto has already
     * joined. Repeating such an alias makes every reference to it ambiguous.
     */
    public static String joinClause(SqlDialect dialect, List<JoinEdge> edges,
                                    Collection<String> alreadyJoined) {
        return SqlFragment.joins(dialect, edges, alreadyJoined);
    }

    /**
     * The ONE rendering of the root-table projection/join shell: a SELECT list of the root columns
     * (comma-separated in schema order, optionally followed by a comma and {@code extraProjection}),
     * FROM the quoted table, then the deduped join chain for {@code traversedToOne}.
     * {@code qualified} keeps the deliberate split intact: a statement that splices joins qualifies
     * every leaf ({@code "root"."col"}, so a joined table sharing a column name cannot make the list
     * ambiguous) while an EXISTS-only statement keeps the bare {@code "col"} projection.
     */
    public static String qualifiedRootSelect(SqlDialect dialect, TableSchema schema, List<JoinEdge> traversedToOne,
                                             boolean qualified, String extraProjection) {
        return qualifiedRootSelect(dialect, schema, traversedToOne, qualified, extraProjection, false);
    }

    /** As {@link #qualifiedRootSelect}, honouring {@code DISTINCT}. */
    public static String qualifiedRootSelect(SqlDialect dialect, TableSchema schema, List<JoinEdge> traversedToOne,
                                             boolean qualified, String extraProjection, boolean distinct) {
        SqlFragment fragment = new SqlFragment()
                .keyword("SELECT")
                .projection(rootProjection(dialect, schema, qualified, extraProjection))
                .distinct(distinct)
                .table(schema.tableName())
                .join(traversedToOne);
        return fragment.renderWithoutTail(dialect);
    }

    /**
     * The EXISTS conjunct a predicate on an existential (to-many head) — or on ANY traversed ref in a
     * DELETE — renders instead of a joined WHERE term: the ref's LAST edge supplies the child table
     * and the edge-side column; the correlation closes on the edge's owner-side column — the root id
     * for a to-many head, the owner-side FK for a to-one head. The leaf term is
     * {@link #render(SqlDialect, Predicate, ColumnPlacement)}'s table-qualified rendering, so the
     * named parameter and the caller's binding are unchanged.
     */
    public static String existsConjunct(SqlDialect dialect, Predicate predicate, ColumnPlacement placement) {
        List<JoinEdge> edges = placement.edges();
        JoinEdge join = edges.getLast();
        String owner = join.ownerTable();
        return "EXISTS (SELECT 1 FROM " + dialect.quoteIdentifier(join.childTable()) + " "
                + dialect.quoteIdentifier(join.childTable())
                + " WHERE " + dialect.quoteIdentifier(join.childTable()) + "." + dialect.quoteIdentifier(join.childColumn())
                + " = " + dialect.quoteIdentifier(owner) + "." + dialect.quoteIdentifier(join.ownerColumn())
                + " AND " + render(dialect, predicate, placement) + ")";
    }


    /**
     * The correlated scalar subquery a to-many sort orders by: {@code MIN} of the leaf column for an
     * ascending key, {@code MAX} for a descending one — exactly the edge the descriptor-driven cursor
     * key reduction collapses the value list to, so seek and sort agree. The head edge opens the
     * subquery {@code FROM} (alias WITHOUT {@code AS}, mirroring nothing else on the statement);
     * deeper edges chain inside as the same {@code LEFT JOIN ... AS ... ON ...} fragments the join
     * clause emits, qualified per-path. Correlation is the head's {@code childFk = rootTable.rootId}
     * pair taken from its {@code Collection} edge. Every identifier goes through the dialect; the
     * result is used VERBATIM as an ORDER BY / seek term.
     */
    public static String aggregateSortTerm(SqlDialect dialect, TableSchema schema, ColumnPlacement placement,
                                           boolean ascending) {
        List<JoinEdge> edges = placement.edges();
        if (edges.isEmpty()) {
            throw new IllegalArgumentException("aggregate sort term requires a relation path");
        }
        JoinEdge head = edges.getFirst();
        String owner = head.ownerTable();
        StringBuilder sb = new StringBuilder("(SELECT ")
                .append(ascending ? "MIN(" : "MAX(")
                .append(qualify(dialect, placement.leaf(), schema.tableName()))
                .append(") FROM ").append(dialect.quoteIdentifier(head.childTable()))
                .append(' ').append(dialect.quoteIdentifier(head.childTable()));
        for (int i = 1; i < edges.size(); i++) {
            JoinEdge join = edges.get(i);
            sb.append(" LEFT JOIN ").append(dialect.quoteIdentifier(join.childTable()))
                    .append(" AS ").append(dialect.quoteIdentifier(join.childTable()))
                    .append(" ON ").append(dialect.quoteIdentifier(join.ownerTable())).append('.')
                    .append(dialect.quoteIdentifier(join.ownerColumn()))
                    .append(" = ").append(dialect.quoteIdentifier(join.childTable())).append('.')
                    .append(dialect.quoteIdentifier(join.childColumn()));
        }
        sb.append(" WHERE ").append(dialect.quoteIdentifier(head.childTable())).append('.')
                .append(dialect.quoteIdentifier(head.childColumn()))
                .append(" = ").append(dialect.quoteIdentifier(owner)).append('.')
                .append(dialect.quoteIdentifier(head.ownerColumn()))
                .append(')');
        return sb.toString();
    }

    /** Renders a predicate with its column qualified by the resolved leaf. */
    public static String render(SqlDialect dialect, Predicate predicate, ColumnPlacement placement) {
        ColumnRef leaf = placement.leaf();
        String col = placement.local() && leaf.table() == null
                ? dialect.quoteIdentifier(leaf.column())
                : qualify(dialect, leaf, null);
        return renderOperator(predicate.operator(), col, predicate.param(), predicate.ignoreCase());
    }

    /**
     * Renders a predicate with its column qualified by the resolved leaf: an explicit table for a
     * traversed leaf, and a fully-local ref stays unqualified for legacy parity unless
     * {@code qualifyRoot} (a joined statement) forces it.
     */
    public static String render(SqlDialect dialect, Predicate predicate, ColumnPlacement placement,
                                String rootTable, boolean qualifyRoot) {
        ColumnRef leaf = placement.leaf();
        boolean localRootColumn = isLocalTo(leaf, rootTable);
        String col = localRootColumn && !qualifyRoot
                ? dialect.quoteIdentifier(leaf.column())
                : qualify(dialect, leaf, rootTable);
        return renderOperator(predicate.operator(), col, predicate.param(), predicate.ignoreCase());
    }

    /**
     * One ORDER BY term: the rendered column, {@code LOWER(...)}-wrapped when the sort asks for
     * case-insensitive ordering, then the direction. An existential sort wraps an aggregate subquery
     * whose MIN/MAX already picked a case-sensitive edge — and the runtime cursor key reduces the
     * same value list case-sensitively — so folding there would order by something the seek cannot
     * reproduce; the processor rejects that combination instead.
     */
    public static String orderTerm(String renderedColumn, SortSpec sort, boolean existentialSort) {
        boolean fold = sort.ignoreCase() && !existentialSort;
        return (fold ? "LOWER(" + renderedColumn + ")" : renderedColumn)
                + (sort.ascending() ? " ASC" : " DESC");
    }

    /** The ORDER BY clause of a classic no-join statement, one term per declared sort. */
    public static String classicOrderBy(SqlDialect dialect, TableSchema schema, List<SortSpec> sorts) {
        return classicOrderBy(dialect, schema, sorts, null);
    }

    /** Renders an ORDER BY clause for a simple (no-join) query. */
    public static String classicOrderBy(SqlDialect dialect, TableSchema schema, List<SortSpec> sorts, String mainAlias) {
        if (sorts.isEmpty()) {
            return "";
        }
        return " ORDER BY " + sorts.stream()
                .map(s -> {
                    String column = s.property().replace('.', '_');
                    String qualifiedColumn = mainAlias != null 
                        ? dialect.quoteIdentifier(mainAlias) + "." + dialect.quoteIdentifier(column)
                        : dialect.quoteIdentifier(column);
                    return orderTerm(qualifiedColumn, s, false);
                })
                .collect(Collectors.joining(", "));
    }

    // ------------------------------------------------------------------- helpers

    /**
     * Resolves one logical attribute path, falling back to a root-table column for an unknown path.
     *
     * <p>The fallback is what lets a hand-written {@code @Query} or a method-name predicate on a
     * column the descriptor does not publish still render: a bare path degrades to its own
     * snake_case column name on the root table rather than failing the whole statement.</p>
     *
     * <p>Catches {@link UnknownAttributePathException} and NOT {@link IllegalArgumentException}: the
     * fallback is a decision about ONE class of error, and catching the supertype also degraded every
     * other illegal-argument failure thrown anywhere inside the resolution — an empty SQL identifier,
     * a cursor key with mismatched directions — into a silently wrong column. A resolver that throws
     * anything else is a bug, and now it propagates instead of rendering.</p>
     */
    public static ColumnPlacement resolveColumn(TableSchema schema, ColumnResolver attributes, String path) {
        try {
            return attributes.resolve(path);
        } catch (UnknownAttributePathException unknownPath) {
            return new ColumnPlacement(new ColumnRef.Simple(Names.columnBase(path), schema.tableName()));
        }
    }

    /** Whether {@code leaf} is a column local to {@code table}: owned by no table or by that table. */
    public static boolean isLocalTo(ColumnRef leaf, String table) {
        return leaf.table() == null || leaf.table().equals(table);
    }

    /** Whether the head of {@code path} is a to-many relation or collection. */
    public static boolean isExistential(ColumnResolver attributes, String path) {
        return pathHead(path) != null && attributes.isToManyPath(path);
    }

    /** The first segment of an entity-rooted path, or {@code null} when it has no child segment. */
    public static String pathHead(String path) {
        int dot = path.indexOf('.');
        return dot < 0 ? null : path.substring(0, dot);
    }

    /**
     * Maps a dot-separated property path to the named-parameter token used in the generated SQL
     * (e.g. {@code "address.street"} becomes {@code "address_street"}). Callers that bind parameters
     * programmatically must use the same mapping so the emitted {@code .param(...)} names line up.
     */
    public static String paramName(String path) {
        return path.replace('.', '_');
    }

    /**
     * How many values an operator binds: none for the nullary ones, two for {@code BETWEEN}, one
     * otherwise. Callers that allocate method arguments to predicates must use this so the tokens
     * rendered by {@link #renderOperator} and the values bound at runtime stay in step.
     */
    public static int parameterCount(Operator operator) {
        return switch (operator) {
            case IS_NULL, IS_NOT_NULL, TRUE, FALSE -> 0;
            case BETWEEN, NOT_BETWEEN -> 2;
            default -> 1;
        };
    }

    /** The named-parameter tokens {@code operator} renders for {@code param}, in binding order. */
    public static List<String> paramNames(Operator operator, String param) {
        return switch (operator) {
            case IS_NULL, IS_NOT_NULL, TRUE, FALSE -> List.of();
            // Named for the ROLE of each bound, not for its index: both placeholders are derived from
            // the FIRST parameter name ("low"), so numbering them made the upper bound read as
            // ":low_1" — a reader has to re-derive which bound that is every time.
            case BETWEEN, NOT_BETWEEN -> List.of(param + "_from", param + "_to");
            default -> List.of(param);
        };
    }

    /** The logical attribute path that owns {@code column}, taken from the WRITE view when available. */
    public static String attributePath(ColumnResolver attributes, ColumnRef column) {
        Map<String, ColumnRef> refs = attributes instanceof TypeDefinition td
                ? td.columnRefs() : attributes.columnRefs();
        return refs.entrySet().stream()
                .filter(entry -> entry.getValue().column().equals(column.column()))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "No logical attribute path maps to column '" + column.column() + "'"));
    }

    private static void appendWhere(SqlDialect dialect, StringBuilder sql, List<Predicate> predicates,
                                    String cursorClause) {
        boolean hasOr = predicates.stream().anyMatch(p -> Connector.isOr(p.connector()));
        boolean hasCursor = cursorClause != null && !cursorClause.isBlank();
        StringBuilder where = new StringBuilder();
        if (!predicates.isEmpty()) {
            List<String> conjuncts = new ArrayList<>(predicates.size());
            List<Boolean> ors = new ArrayList<>(predicates.size());
            for (int i = 0; i < predicates.size(); i++) {
                Predicate p = predicates.get(i);
                conjuncts.add(renderOperator(p.operator(),
                        dialect.quoteIdentifier(Names.columnBase(p.property())), p.param(), p.ignoreCase()));
                ors.add(i > 0 && Connector.isOr(p.connector()));
            }
            String userExpression = joinConjuncts(conjuncts, ors);
            // The cursor conjunct is joined with AND and must not bind to the caller's last OR term,
            // so an OR-bearing user expression is grouped before the cursor clause is appended.
            where.append(hasOr && hasCursor ? "(" + userExpression + ")" : userExpression);
        }
        if (hasCursor) {
            if (where.length() > 0) {
                where.append(" AND ");
            }
            where.append(cursorClause);
        }
        if (where.length() > 0) {
            sql.append(" WHERE ").append(where);
        }
    }

    /** Joins rendered conjuncts with each one's connector; entry 0's flag is ignored. */
    private static String joinConjuncts(List<String> conjuncts, List<Boolean> orJoinedFlags) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < conjuncts.size(); i++) {
            if (i > 0) {
                sb.append(Boolean.TRUE.equals(orJoinedFlags.get(i)) ? " OR " : " AND ");
            }
            sb.append(conjuncts.get(i));
        }
        return sb.toString();
    }

    private static String renderOperator(Operator operator, String column, String param, boolean ignoreCase) {
        return ignoreCase ? renderIgnoreCase(operator, column, param) : renderOperator(operator, column, param);
    }

    /**
     * The case-folding form of an operator, wrapping both sides in {@code LOWER(...)}. Only the
     * operators where folding changes the comparison are listed; {@code In}/{@code NotIn} fall
     * through (their argument is a collection, so folding one bound token would be wrong) as do the
     * nullary and range operators, for which case is meaningless.
     */
    private static String renderIgnoreCase(Operator operator, String column, String param) {
        String lowerCol = "LOWER(" + column + ")";
        String lowerParam = "LOWER(:" + param + ")";
        return switch (operator) {
            case EQ, IGNORE_CASE_EQ -> lowerCol + " = " + lowerParam;
            case NOT_EQ -> lowerCol + " <> " + lowerParam;
            case LIKE, CONTAINS, STARTS_WITH, ENDS_WITH -> lowerCol + " LIKE " + lowerParam;
            case NOT_LIKE, NOT_CONTAINS, NOT_STARTS_WITH, NOT_ENDS_WITH -> lowerCol + " NOT LIKE " + lowerParam;
            default -> renderOperator(operator, column, param);
        };
    }

    private static String renderOperator(Operator operator, String column, String param) {
        return switch (operator) {
            case EQ -> column + " = :" + param;
            case NOT_EQ -> column + " <> :" + param;
            case LESS_THAN -> column + " < :" + param;
            case LESS_THAN_EQUAL -> column + " <= :" + param;
            case GREATER_THAN -> column + " > :" + param;
            case GREATER_THAN_EQUAL -> column + " >= :" + param;
            case LIKE, CONTAINS, STARTS_WITH, ENDS_WITH -> column + " LIKE :" + param;
            case NOT_LIKE, NOT_CONTAINS, NOT_STARTS_WITH, NOT_ENDS_WITH -> column + " NOT LIKE :" + param;
            case BETWEEN -> {
                List<String> bounds = paramNames(operator, param);
                yield column + " BETWEEN :" + bounds.getFirst() + " AND :" + bounds.get(1);
            }
            case NOT_BETWEEN -> {
                List<String> bounds = paramNames(operator, param);
                yield column + " NOT BETWEEN :" + bounds.getFirst() + " AND :" + bounds.get(1);
            }
            case IN -> column + " IN (:" + param + ")";
            case NOT_IN -> column + " NOT IN (:" + param + ")";
            case IS_NULL -> column + " IS NULL";
            case IS_NOT_NULL -> column + " IS NOT NULL";
            case TRUE -> column + " = TRUE";
            case FALSE -> column + " = FALSE";
            case IGNORE_CASE_EQ -> "LOWER(" + column + ") = LOWER(:" + param + ")";
        };
    }
}
