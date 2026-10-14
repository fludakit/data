package io.github.fludakit.data.sql;

import io.github.fludakit.data.metadata.JoinEdge;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The ONE ordered SQL accumulator: every statement the engine emits is assembled as named parts and
 * rendered in exactly one order by exactly one {@link SqlDialect}.
 *
 * <p>The parts, in the order {@link #render(SqlDialect)} emits them:</p>
 *
 * <pre>
 * &lt;keyword&gt;[ DISTINCT] [&lt;projection&gt;] FROM &lt;table&gt;
 *     &lt;joins&gt;
 *     [WHERE &lt;where&gt;]
 *     [ORDER BY &lt;orderBy&gt;]
 *     &lt;pagination&gt;
 * </pre>
 *
 * <p>Three properties make this the substrate rather than a string being concatenated by hand:</p>
 * <ol>
 * <li><b>Joins are deduplicated by child table, structurally.</b> Two predicates that traverse the
 *     same relationship contribute the same {@link JoinEdge}; the map keeps the first and drops the
 *     rest. Dedup is the accumulator's job, not a caller's bookkeeping — previously it was an
 *     implicit side effect of {@code joinClause(List)} being handed an already-flat edge list.</li>
 * <li><b>Every identifier goes through the dialect at render time.</b> The accumulator stores raw
 *     table and column names; quoting, limit and offset syntax are the dialect's one
 *     responsibility, and no dialect branch appears in any builder.</li>
 * <li><b>A part that was never set is never emitted.</b> An absent WHERE is not {@code WHERE true},
 *     an absent ORDER BY is not {@code ORDER BY null}.</li>
 * </ol>
 *
 * <p>Mutable by design: a builder fills it in whatever order the caller's fluent chain arrives, and
 * {@code render} is the only thing that imposes SQL's order.</p>
 */
public final class SqlFragment {

    /** One WHERE conjunct plus the connector that joins it to the preceding one. */
    private record Conjunct(String sql, boolean orJoined) {
    }

    private String keyword = "SELECT";
    private String projection = "";
    private boolean distinct;
    private String table;
    private final Map<String, JoinEdge> joins = new LinkedHashMap<>();
    private final List<Conjunct> where = new ArrayList<>();
    private final List<String> orderBy = new ArrayList<>();
    private Integer limit;
    private Integer offset;

    // ------------------------------------------------------------------- assembly

    /** Sets the leading SQL keyword: {@code SELECT}, {@code DELETE}, ... */
    public SqlFragment keyword(String keyword) {
        this.keyword = Objects.requireNonNull(keyword, "keyword");
        return this;
    }

    /**
     * Sets the SELECT list verbatim. Callers compose it — bare {@code "col"}, {@code "root"."col"}
     * or an aggregate such as {@code COUNT(*)} — because only they know which shape the statement
     * needs. {@code DISTINCT} is a separate flag so that a projection need never encode it.
     */
    public SqlFragment projection(String projection) {
        this.projection = projection == null ? "" : projection;
        return this;
    }

    /** Marks the projection {@code DISTINCT}. Ignored unless a projection is set. */
    public SqlFragment distinct(boolean distinct) {
        this.distinct = distinct;
        return this;
    }

    /** Sets the root table; quoted by the dialect at render time. */
    public SqlFragment table(String table) {
        this.table = Objects.requireNonNull(table, "table");
        return this;
    }

    /** Adds one relation edge; a second edge reaching the same child table is dropped. */
    public SqlFragment join(JoinEdge edge) {
        Objects.requireNonNull(edge, "edge");
        joins.putIfAbsent(edge.childTable(), edge);
        return this;
    }

    /** Adds every edge in {@code edges}, in order, deduplicated by child table. */
    public SqlFragment join(Collection<JoinEdge> edges) {
        Objects.requireNonNull(edges, "edges");
        for (JoinEdge edge : edges) {
            join(edge);
        }
        return this;
    }

    /** Appends a WHERE conjunct joined to the preceding one with {@code AND}. */
    public SqlFragment where(String sql) {
        return where(sql, false);
    }

    /** Appends a WHERE conjunct; {@code or} joins it with {@code OR} instead of {@code AND}. */
    public SqlFragment where(String sql, boolean orJoined) {
        Objects.requireNonNull(sql, "sql");
        where.add(new Conjunct(sql, orJoined));
        return this;
    }

    /** Appends an ORDER BY term, already rendered (quoting, {@code LOWER}, aggregate subquery). */
    public SqlFragment orderBy(String term) {
        Objects.requireNonNull(term, "term");
        orderBy.add(term);
        return this;
    }

    /** Sets the row limit; {@code <= 0} or {@code null} emits no limit clause. */
    public SqlFragment limit(Integer limit) {
        this.limit = limit;
        return this;
    }

    /** Sets the row offset; {@code null} emits no offset clause. */
    public SqlFragment offset(Integer offset) {
        this.offset = offset;
        return this;
    }

    // ------------------------------------------------------------------- inspection

    /** The deduplicated join edges, in insertion order. */
    public List<JoinEdge> joins() {
        return List.copyOf(joins.values());
    }

    /** The number of WHERE conjuncts accumulated. */
    public int whereCount() {
        return where.size();
    }

    // ------------------------------------------------------------------- rendering

    /**
     * Renders every accumulated part through {@code dialect}, in SQL's order.
     *
     * @throws IllegalStateException when no root table was set — a statement without a FROM target
     *                               is never a valid rendering, and silently emitting one would turn
     *                               a caller's missing step into a database error
     */
    public String render(SqlDialect dialect) {
        Objects.requireNonNull(dialect, "dialect");
        if (table == null) {
            throw new IllegalStateException("SqlFragment has no root table — call table(..) before render(..)");
        }
        StringBuilder sb = new StringBuilder(keyword);
        if (distinct && !projection.isEmpty()) {
            sb.append(" DISTINCT");
        }
        if (!projection.isEmpty()) {
            sb.append(' ').append(projection);
        }
        sb.append(" FROM ").append(dialect.quoteIdentifier(table));
        renderJoins(sb, dialect);
        renderWhere(sb);
        renderOrderBy(sb);
        renderPagination(sb, dialect);
        return sb.toString();
    }

    /**
     * Renders only the join chain — {@code LEFT JOIN ... AS ... ON ...}, one per distinct child
     * table, or {@code ""} when nothing joins. Callers that splice their own join string onto a
     * pre-rendered shell (the generated paged readers) render exactly this.
     */
    public String renderJoins(SqlDialect dialect) {
        Objects.requireNonNull(dialect, "dialect");
        StringBuilder sb = new StringBuilder();
        renderJoins(sb, dialect);
        return sb.toString();
    }

    /** Renders only the join chain for {@code edges}, deduplicated by child table. */
    public static String joins(SqlDialect dialect, Collection<JoinEdge> edges) {
        return joins(dialect, edges, Set.of());
    }

    /**
     * Renders only the join chain for {@code edges}, deduplicated by child table and DROPPING every
     * edge onto a table already named in {@code alreadyJoined}.
     *
     * <p>A caller that splices this onto a pre-rendered shell must suppress the shell's own joins:
     * the shell already emitted {@code LEFT JOIN "author" AS "author"}, so repeating the alias makes
     * every reference to it ambiguous. The caller knows which tables its shell joined; this overload
     * is where that knowledge is applied, so the suppression rule lives once instead of in every
     * generated splice site.</p>
     */
    public static String joins(SqlDialect dialect, Collection<JoinEdge> edges,
                               Collection<String> alreadyJoined) {
        Objects.requireNonNull(dialect, "dialect");
        Objects.requireNonNull(edges, "edges");
        Objects.requireNonNull(alreadyJoined, "alreadyJoined");
        List<JoinEdge> remaining = new ArrayList<>();
        for (JoinEdge edge : edges) {
            if (edge == null) {
                continue;
            }
            if (alreadyJoined.contains(edge.childTable())) {
                continue;
            }
            remaining.add(edge);
        }
        SqlFragment fragment = new SqlFragment();
        fragment.join(remaining);
        return fragment.renderJoins(dialect);
    }

    /**
     * Renders the shell only — keyword, projection, {@code FROM}, join chain — dropping WHERE,
     * ORDER BY and pagination. A caller that appends those itself (a runtime paged read splicing
     * its own seek predicate and limit) starts from this.
     */
    public String renderWithoutTail(SqlDialect dialect) {
        Objects.requireNonNull(dialect, "dialect");
        if (table == null) {
            throw new IllegalStateException("SqlFragment has no root table — call table(..) before render(..)");
        }
        StringBuilder sb = new StringBuilder(keyword);
        if (distinct && !projection.isEmpty()) {
            sb.append(" DISTINCT");
        }
        if (!projection.isEmpty()) {
            sb.append(' ').append(projection);
        }
        sb.append(" FROM ").append(dialect.quoteIdentifier(table));
        renderJoins(sb, dialect);
        return sb.toString();
    }

    private void renderJoins(StringBuilder sql, SqlDialect dialect) {
        for (JoinEdge join : joins.values()) {
            String owner = dialect.quoteIdentifier(join.ownerTable());
            String alias = dialect.quoteIdentifier(join.childTable());
            sql.append(" LEFT JOIN ").append(alias)
                    .append(" AS ").append(alias)
                    .append(" ON ").append(owner).append('.')
                    .append(dialect.quoteIdentifier(join.ownerColumn()))
                    .append(" = ").append(alias).append('.')
                    .append(dialect.quoteIdentifier(join.childColumn()));
        }
    }

    private void renderWhere(StringBuilder sql) {
        if (where.isEmpty()) {
            return;
        }
        sql.append(" WHERE ");
        for (int i = 0; i < where.size(); i++) {
            if (i > 0) {
                sql.append(where.get(i).orJoined() ? " OR " : " AND ");
            }
            sql.append(where.get(i).sql());
        }
    }

    private void renderOrderBy(StringBuilder sql) {
        if (orderBy.isEmpty()) {
            return;
        }
        sql.append(" ORDER BY ").append(String.join(", ", orderBy));
    }

    private void renderPagination(StringBuilder sql, SqlDialect dialect) {
        boolean hasLimit = limit != null && limit > 0;
        boolean hasOffset = offset != null && offset >= 0;
        if (hasLimit && hasOffset) {
            sql.append(dialect.limitOffsetClause(Integer.toString(limit), Integer.toString(offset)));
        } else if (hasLimit) {
            sql.append(dialect.limitClause(Integer.toString(limit)));
        } else if (hasOffset) {
            sql.append(dialect.offsetClause(Integer.toString(offset)));
        }
    }

    /** The table-qualified form of {@code column}, e.g. {@code "root"."name"}. */
    public static String qualified(SqlDialect dialect, String table, String column) {
        return dialect.quoteIdentifier(table) + "." + dialect.quoteIdentifier(column);
    }
}
