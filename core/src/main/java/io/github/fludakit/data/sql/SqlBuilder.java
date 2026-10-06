package io.github.fludakit.data.sql;

import io.github.fludakit.data.Names;
import io.github.fludakit.data.metadata.ColumnRef;
import io.github.fludakit.data.metadata.EntitySchema;
import io.github.fludakit.data.metadata.JoinRef;
import io.github.fludakit.data.query.Operator;
import io.github.fludakit.data.query.Predicate;
import io.github.fludakit.data.query.SortSpec;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public final class SqlBuilder {

    public String selectBy(EntitySchema md, List<Predicate> predicates, List<SortSpec> sorts, int limit, String cursorClause) {
        String cols = md.columns().stream().map(c -> Names.quote(c.column())).collect(Collectors.joining(", "));
        StringBuilder sb = new StringBuilder("SELECT ").append(cols).append(" FROM ").append(Names.quote(md.tableName()));
        appendWhere(sb, predicates, cursorClause);
        sb.append(orderBy(md, sorts));
        if (limit > 0) sb.append(" LIMIT ").append(limit);
        return sb.toString();
    }

    public String countBy(EntitySchema md, List<Predicate> predicates) {
        StringBuilder sb = new StringBuilder("SELECT COUNT(*) FROM ").append(Names.quote(md.tableName()));
        appendWhere(sb, predicates, null);
        return sb.toString();
    }

    public String insert(EntitySchema md) {
        return insert(md, true);
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
     * Builds an INSERT. When {@code includeId} is false the identifier column is omitted so the
     * database can generate it (identity / auto-increment); binding an explicit NULL to an identity
     * column is rejected by most databases, including H2.
     */
    public String insert(EntitySchema md, boolean includeId) {
        List<ColumnRef> cols = md.columns();
        if (!includeId) {
            cols = cols.stream()
                    .filter(c -> md.idColumn() == null || !c.column().equals(md.idColumn()))
                    .collect(Collectors.toList());
        }
        String names = cols.stream().map(c -> Names.quote(c.column())).collect(Collectors.joining(", "));
        String params = cols.stream().map(c -> ":" + paramName(c.path())).collect(Collectors.joining(", "));
        return "INSERT INTO " + Names.quote(md.tableName()) + " (" + names + ") VALUES (" + params + ")";
    }

    public String update(EntitySchema md) {
        List<ColumnRef> cols = md.columns();
        String idCol = Names.quote(md.idColumn());
        String sets = cols.stream()
                .filter(c -> !c.column().equals(md.idColumn()))
                .map(c -> Names.quote(c.column()) + " = :" + paramName(c.path()))
                .collect(Collectors.joining(", "));
        return "UPDATE " + Names.quote(md.tableName()) + " SET " + sets + " WHERE " + idCol + " = :id";
    }

    public String deleteById(EntitySchema md) {
        return "DELETE FROM " + Names.quote(md.tableName()) + " WHERE " + Names.quote(md.idColumn()) + " = :id";
    }

    public String deleteBy(EntitySchema md, List<Predicate> predicates) {
        StringBuilder sb = new StringBuilder("DELETE FROM ").append(Names.quote(md.tableName()));
        appendWhere(sb, predicates, null);
        return sb.toString();
    }

    public String orderBy(EntitySchema md, List<SortSpec> sorts) {
        if (sorts.isEmpty()) return "";
        return " ORDER BY " + sorts.stream()
                .map(s -> Names.quote(resolveColumn(md, s.property())) + (s.ascending() ? " ASC" : " DESC"))
                .collect(Collectors.joining(", "));
    }

    /**
     * Orders by already-resolved column refs, each qualified via {@link #qualify(ColumnRef)}.
     * Refs must carry a join or an explicit table; fully-local refs need the {@code rootTable}
     * overload. Empty input renders {@code ""}.
     */
    public String orderBy(List<ColumnRef> resolvedSortRefs) {
        if (resolvedSortRefs.isEmpty()) return "";
        return " ORDER BY " + resolvedSortRefs.stream()
                .map(SqlBuilder::qualify)
                .collect(Collectors.joining(", "));
    }

    /** As {@link #orderBy(List)} with {@code rootTable} as the qualifier for fully-local refs. */
    public String orderBy(List<ColumnRef> resolvedSortRefs, String rootTable) {
        if (resolvedSortRefs.isEmpty()) return "";
        return " ORDER BY " + resolvedSortRefs.stream()
                .map(ref -> qualify(ref, rootTable))
                .collect(Collectors.joining(", "));
    }

    /**
     * Qualifies a ref's leaf column: the LAST join's alias when the path contributes joins
     * (per the {@link ColumnRef} contract), else the explicit owning table. Fully-local refs
     * ({@code table == null}, no joins) have no root here and are rejected; use
     * {@link #qualify(ColumnRef, String)}.
     */
    public static String qualify(ColumnRef ref) {
        return qualify(ref, null);
    }

    /**
     * Qualifies a ref's leaf column: last-join alias when joins are present, else
     * {@code ref.table()} when non-null, else {@code rootTable}.
     */
    public static String qualify(ColumnRef ref, String rootTable) {
        String qualifier;
        if (!ref.joins().isEmpty()) {
            qualifier = ref.joins().get(ref.joins().size() - 1).alias();
        } else if (ref.table() != null) {
            qualifier = ref.table();
        } else if (rootTable != null) {
            qualifier = rootTable;
        } else {
            throw new IllegalStateException(
                    "Cannot qualify fully-local column ref '" + ref.path() + "': no owning table or root table");
        }
        return Names.quote(qualifier) + "." + Names.quote(ref.column());
    }

    /**
     * The deduplicated {@code LEFT JOIN} chain these refs require, in first-appearance order by
     * alias (first occurrence wins). Each fragment is
     * {@code  LEFT JOIN "<table>" AS "<alias>" ON <qualifier>."<parentColumn>" = "<alias>"."<childColumn>";}
     * the first qualifier is the root table, every later qualifier is the previous fragment's
     * alias — regardless of which ref contributed the join. Returns {@code ""} when no ref
     * carries a join.
     */
    public static String joinClause(EntitySchema md, List<ColumnRef> refs) {
        Map<String, JoinRef> byAlias = new LinkedHashMap<>();
        for (ColumnRef ref : refs) {
            for (JoinRef join : ref.joins()) {
                byAlias.putIfAbsent(join.alias(), join);
            }
        }
        if (byAlias.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        String qualifier = Names.quote(md.tableName());
        for (JoinRef join : byAlias.values()) {
            String alias = Names.quote(join.alias());
            sb.append(" LEFT JOIN ").append(Names.quote(join.table()))
                    .append(" AS ").append(alias)
                    .append(" ON ").append(qualifier).append('.').append(Names.quote(join.parentColumn()))
                    .append(" = ").append(alias).append('.').append(Names.quote(join.childColumn()));
            qualifier = alias;
        }
        return sb.toString();
    }

    /**
     * Resolves a sort property — a bare attribute name or an embedded dot path such as
     * {@code "address.city"} — to its physical column via the descriptor's flattened view.
     */
    public static String resolveColumn(EntitySchema md, String property) {
        for (ColumnRef c : md.columns()) {
            if (c.path().equals(property)) return c.column();
        }
        throw new IllegalArgumentException("Unknown sort property: " + property);
    }

    private void appendWhere(StringBuilder sb, List<Predicate> predicates, String cursorClause) {
        StringBuilder where = new StringBuilder();
        if (!predicates.isEmpty()) {
            for (Predicate p : predicates) {
                if (where.length() > 0) where.append(" AND ");
                where.append(render(p));
            }
        }
        if (cursorClause != null && !cursorClause.isBlank()) {
            if (where.length() > 0) where.append(" AND ");
            where.append(cursorClause);
        }
        if (where.length() > 0) sb.append(" WHERE ").append(where);
    }

    private String render(Predicate p) {
        return renderOperator(p.operator(), Names.quote(Names.columnName(p.property())), p.param());
    }

    /**
     * Renders a predicate with its column qualified by {@code ref}: last-join alias, then
     * explicit table, and fully-local refs stay unqualified for legacy parity.
     */
    public static String render(Predicate p, ColumnRef ref) {
        String col = ref.joins().isEmpty() && ref.table() == null
                ? Names.quote(ref.column())
                : qualify(ref);
        return renderOperator(p.operator(), col, p.param());
    }

    private static String renderOperator(Operator op, String col, String param) {
        return switch (op) {
            case EQ -> col + " = :" + param;
            case NOT_EQ -> col + " <> :" + param;
            case LESS_THAN -> col + " < :" + param;
            case LESS_THAN_EQUAL -> col + " <= :" + param;
            case GREATER_THAN -> col + " > :" + param;
            case GREATER_THAN_EQUAL -> col + " >= :" + param;
            case LIKE, CONTAINS, STARTS_WITH, ENDS_WITH -> col + " LIKE :" + param;
            case NOT_LIKE -> col + " NOT LIKE :" + param;
            case BETWEEN -> col + " BETWEEN :" + param + "_0 AND :" + param + "_1";
            case IN -> col + " IN (:" + param + ")";
            case NOT_IN -> col + " NOT IN (:" + param + ")";
            case IS_NULL -> col + " IS NULL";
            case IS_NOT_NULL -> col + " IS NOT NULL";
            case TRUE -> col + " = TRUE";
            case FALSE -> col + " = FALSE";
            case IGNORE_CASE_EQ -> "LOWER(" + col + ") = LOWER(:" + param + ")";
        };
    }
}
