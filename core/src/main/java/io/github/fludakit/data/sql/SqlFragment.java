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
 * Immutable SQL-shaped parts produced by {@link QueryCompiler}. {@link #render(SqlDialect)} is the
 * only place these parts become a literal; formatting is {@link SqlBuilder}'s job.
 */
public sealed interface SqlFragment permits SqlFragment.Query, SqlFragment.Opaque {

    /** Renders this fragment through {@code dialect} as one single-line statement. */
    String render(SqlDialect dialect);

    /** One WHERE conjunct plus whether it joins to the preceding one with {@code OR}. */
    record Conjunct(String sql, boolean orJoined) {
        public Conjunct {
            Objects.requireNonNull(sql, "sql");
        }
    }

    /** One join edge rendered under an explicit alias. */
    record Joined(String alias, JoinEdge edge) {
        public Joined {
            Objects.requireNonNull(alias, "alias");
            Objects.requireNonNull(edge, "edge");
        }
    }

    /**
     * A structured {@code SELECT} or {@code DELETE}: projection, joins, where, order, and optional
     * limit/offset. Cursor seek keys travel with the fragment when the compiler derived them; they
     * are not rendered into the base statement.
     */
    record Query(
            String keyword,
            boolean distinct,
            String projection,
            String table,
            String tableAlias,
            List<Joined> joins,
            List<Conjunct> where,
            List<String> groupBy,
            List<String> orderBy,
            Integer limit,
            Integer offset,
            List<String> seekKeys
    ) implements SqlFragment {

        public Query {
            Objects.requireNonNull(keyword, "keyword");
            projection = projection == null ? "" : projection;
            Objects.requireNonNull(table, "table");
            joins = List.copyOf(joins);
            where = List.copyOf(where);
            groupBy = List.copyOf(groupBy);
            orderBy = List.copyOf(orderBy);
            seekKeys = List.copyOf(seekKeys);
        }

        /** The join edges in insertion order. */
        public List<JoinEdge> joinEdges() {
            return joins.stream().map(Joined::edge).toList();
        }

        /** Starts a mutable builder for compiler assembly. */
        public static Builder builder() {
            return new Builder();
        }

        @Override
        public String render(SqlDialect dialect) {
            Objects.requireNonNull(dialect, "dialect");
            StringBuilder sb = new StringBuilder(keyword);
            if (distinct && !projection.isEmpty()) {
                sb.append(" DISTINCT");
            }
            if (!projection.isEmpty()) {
                sb.append(' ').append(projection);
            }
            appendFrom(sb, dialect);
            appendJoins(sb, dialect, joins);
            appendWhere(sb, where);
            appendGroupBy(sb);
            appendOrderBy(sb, orderBy);
            appendPagination(sb, dialect, limit, offset);
            return sb.toString();
        }

        /**
         * Keyword, projection, {@code FROM}, and joins only — for callers that append seek, order,
         * or limit themselves.
         */
        public String renderWithoutTail(SqlDialect dialect) {
            Objects.requireNonNull(dialect, "dialect");
            StringBuilder sb = new StringBuilder(keyword);
            if (distinct && !projection.isEmpty()) {
                sb.append(" DISTINCT");
            }
            if (!projection.isEmpty()) {
                sb.append(' ').append(projection);
            }
            appendFrom(sb, dialect);
            appendJoins(sb, dialect, joins);
            return sb.toString();
        }

        private void appendFrom(StringBuilder sql, SqlDialect dialect) {
            sql.append(" FROM ").append(dialect.quoteIdentifier(table));
            if (tableAlias != null) {
                sql.append(" AS ").append(dialect.quoteIdentifier(tableAlias));
            }
        }

        private void appendGroupBy(StringBuilder sql) {
            if (groupBy.isEmpty()) {
                return;
            }
            sql.append(" GROUP BY ").append(String.join(", ", groupBy));
        }

        /** Mutable assembly used only inside {@link QueryCompiler}. */
        public static final class Builder {
            private String keyword = "SELECT";
            private boolean distinct;
            private String projection = "";
            private String table;
            private String tableAlias;
            private final Map<String, JoinEdge> joins = new LinkedHashMap<>();
            private final List<Conjunct> where = new ArrayList<>();
            private final List<String> groupBy = new ArrayList<>();
            private final List<String> orderBy = new ArrayList<>();
            private Integer limit;
            private Integer offset;
            private final List<String> seekKeys = new ArrayList<>();

            public Builder keyword(String keyword) {
                this.keyword = Objects.requireNonNull(keyword, "keyword");
                return this;
            }

            public Builder distinct(boolean distinct) {
                this.distinct = distinct;
                return this;
            }

            public Builder projection(String projection) {
                this.projection = projection == null ? "" : projection;
                return this;
            }

            public Builder table(String table) {
                this.table = Objects.requireNonNull(table, "table");
                return this;
            }

            public Builder table(String table, String alias) {
                this.table = Objects.requireNonNull(table, "table");
                this.tableAlias = Objects.requireNonNull(alias, "alias");
                return this;
            }

            public Builder join(JoinEdge edge) {
                return join(edge, edge.childTable());
            }

            public Builder join(JoinEdge edge, String alias) {
                Objects.requireNonNull(edge, "edge");
                Objects.requireNonNull(alias, "alias");
                joins.putIfAbsent(alias, edge);
                return this;
            }

            public Builder join(Collection<JoinEdge> edges) {
                Objects.requireNonNull(edges, "edges");
                for (JoinEdge edge : edges) {
                    if (edge != null) {
                        join(edge);
                    }
                }
                return this;
            }

            public Builder where(String sql) {
                return where(sql, false);
            }

            public Builder where(String sql, boolean orJoined) {
                where.add(new Conjunct(sql, orJoined));
                return this;
            }

            public Builder where(Conjunct conjunct) {
                where.add(Objects.requireNonNull(conjunct, "conjunct"));
                return this;
            }

            public Builder groupBy(String term) {
                groupBy.add(Objects.requireNonNull(term, "term"));
                return this;
            }

            public Builder orderBy(String term) {
                orderBy.add(Objects.requireNonNull(term, "term"));
                return this;
            }

            public Builder limit(Integer limit) {
                this.limit = limit;
                return this;
            }

            public Builder offset(Integer offset) {
                this.offset = offset;
                return this;
            }

            public Builder seekKey(String key) {
                seekKeys.add(Objects.requireNonNull(key, "key"));
                return this;
            }

            public Query build() {
                if (table == null) {
                    throw new IllegalStateException("SqlFragment has no root table — call table(..) before build()");
                }
                List<Joined> joined = joins.entrySet().stream()
                        .map(e -> new Joined(e.getKey(), e.getValue()))
                        .toList();
                return new Query(keyword, distinct, projection, table, tableAlias,
                        joined, List.copyOf(where), List.copyOf(groupBy),
                        List.copyOf(orderBy), limit, offset, List.copyOf(seekKeys));
            }
        }
    }

    /** Fully-formed SQL: native {@code @Query} text, or a lifecycle/physical statement. */
    record Opaque(String sql) implements SqlFragment {
        public Opaque {
            Objects.requireNonNull(sql, "sql");
        }

        @Override
        public String render(SqlDialect dialect) {
            return sql;
        }
    }

    // ------------------------------------------------------------------- shared helpers

    /** The table-qualified form of {@code column}, e.g. {@code "root"."name"}. */
    static String qualified(SqlDialect dialect, String table, String column) {
        return dialect.quoteIdentifier(table) + "." + dialect.quoteIdentifier(column);
    }

    /** Deduplicated {@code LEFT JOIN} chain for {@code edges}. */
    static String joins(SqlDialect dialect, Collection<JoinEdge> edges) {
        return joins(dialect, edges, Set.of());
    }

    /**
     * Join chain for {@code edges}, dropping every edge onto a table already named in
     * {@code alreadyJoined}.
     */
    static String joins(SqlDialect dialect, Collection<JoinEdge> edges,
                        Collection<String> alreadyJoined) {
        Objects.requireNonNull(dialect, "dialect");
        Objects.requireNonNull(edges, "edges");
        Objects.requireNonNull(alreadyJoined, "alreadyJoined");
        Query.Builder builder = Query.builder().table("_");
        for (JoinEdge edge : edges) {
            if (edge == null || alreadyJoined.contains(edge.childTable())) {
                continue;
            }
            builder.join(edge);
        }
        StringBuilder sb = new StringBuilder();
        appendJoins(sb, dialect, builder.build().joins());
        return sb.toString();
    }

    private static void appendJoins(StringBuilder sql, SqlDialect dialect, List<Joined> joins) {
        for (Joined joined : joins) {
            JoinEdge join = joined.edge();
            String owner = dialect.quoteIdentifier(join.ownerTable());
            String quotedAlias = dialect.quoteIdentifier(joined.alias());
            String childTable = dialect.quoteIdentifier(join.childTable());
            sql.append(" LEFT JOIN ").append(childTable)
                    .append(" AS ").append(quotedAlias)
                    .append(" ON ").append(owner).append('.')
                    .append(dialect.quoteIdentifier(join.ownerColumn()))
                    .append(" = ").append(quotedAlias).append('.')
                    .append(dialect.quoteIdentifier(join.childColumn()));
        }
    }

    private static void appendWhere(StringBuilder sql, List<Conjunct> where) {
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

    private static void appendOrderBy(StringBuilder sql, List<String> orderBy) {
        if (orderBy.isEmpty()) {
            return;
        }
        sql.append(" ORDER BY ").append(String.join(", ", orderBy));
    }

    private static void appendPagination(StringBuilder sql, SqlDialect dialect,
                                         Integer limit, Integer offset) {
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
}
