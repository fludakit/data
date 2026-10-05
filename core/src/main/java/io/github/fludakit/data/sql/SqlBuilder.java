package io.github.fludakit.data.sql;

import io.github.fludakit.data.Names;
import io.github.fludakit.data.metadata.EntityDescriptor;
import io.github.fludakit.data.query.Operator;
import io.github.fludakit.data.query.Predicate;
import io.github.fludakit.data.query.SortSpec;

import java.util.List;
import java.util.stream.Collectors;

public final class SqlBuilder {

    public String selectBy(EntityDescriptor md, List<Predicate> predicates, List<SortSpec> sorts, int limit, String cursorClause) {
        String cols = md.columns().stream().map(c -> Names.quote(c.column())).collect(Collectors.joining(", "));
        StringBuilder sb = new StringBuilder("SELECT ").append(cols).append(" FROM ").append(Names.quote(md.tableName()));
        appendWhere(sb, predicates, cursorClause);
        appendOrderBy(sb, sorts);
        if (limit > 0) sb.append(" LIMIT ").append(limit);
        return sb.toString();
    }

    public String countBy(EntityDescriptor md, List<Predicate> predicates) {
        StringBuilder sb = new StringBuilder("SELECT COUNT(*) FROM ").append(Names.quote(md.tableName()));
        appendWhere(sb, predicates, null);
        return sb.toString();
    }

    public String insert(EntityDescriptor md) {
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
    public String insert(EntityDescriptor md, boolean includeId) {
        List<EntityDescriptor.ColumnRef> cols = md.columns();
        if (!includeId) {
            cols = cols.stream()
                    .filter(c -> md.id() == null || !c.column().equals(md.id().column()))
                    .collect(Collectors.toList());
        }
        String names = cols.stream().map(c -> Names.quote(c.column())).collect(Collectors.joining(", "));
        String params = cols.stream().map(c -> ":" + paramName(c.path())).collect(Collectors.joining(", "));
        return "INSERT INTO " + Names.quote(md.tableName()) + " (" + names + ") VALUES (" + params + ")";
    }

    public String update(EntityDescriptor md) {
        List<EntityDescriptor.ColumnRef> cols = md.columns();
        String idCol = Names.quote(md.id().column());
        String sets = cols.stream()
                .filter(c -> !c.column().equals(md.id().column()))
                .map(c -> Names.quote(c.column()) + " = :" + paramName(c.path()))
                .collect(Collectors.joining(", "));
        return "UPDATE " + Names.quote(md.tableName()) + " SET " + sets + " WHERE " + idCol + " = :id";
    }

    public String deleteById(EntityDescriptor md) {
        return "DELETE FROM " + Names.quote(md.tableName()) + " WHERE " + Names.quote(md.id().column()) + " = :id";
    }

    public String deleteBy(EntityDescriptor md, List<Predicate> predicates) {
        StringBuilder sb = new StringBuilder("DELETE FROM ").append(Names.quote(md.tableName()));
        appendWhere(sb, predicates, null);
        return sb.toString();
    }

    public String orderBy(List<SortSpec> sorts) {
        if (sorts.isEmpty()) return "";
        return " ORDER BY " + sorts.stream()
                .map(s -> Names.quote(Names.columnName(s.property())) + (s.ascending() ? " ASC" : " DESC"))
                .collect(Collectors.joining(", "));
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

    private void appendOrderBy(StringBuilder sb, List<SortSpec> sorts) {
        sb.append(orderBy(sorts));
    }

    private String render(Predicate p) {
        Operator op = p.operator();
        String col = Names.quote(Names.columnName(p.property()));
        return switch (op) {
            case EQ -> col + " = :" + p.param();
            case NOT_EQ -> col + " <> :" + p.param();
            case LESS_THAN -> col + " < :" + p.param();
            case LESS_THAN_EQUAL -> col + " <= :" + p.param();
            case GREATER_THAN -> col + " > :" + p.param();
            case GREATER_THAN_EQUAL -> col + " >= :" + p.param();
            case LIKE, CONTAINS, STARTS_WITH, ENDS_WITH -> col + " LIKE :" + p.param();
            case NOT_LIKE -> col + " NOT LIKE :" + p.param();
            case BETWEEN -> col + " BETWEEN :" + p.param() + "_0 AND :" + p.param() + "_1";
            case IN -> col + " IN (:" + p.param() + ")";
            case NOT_IN -> col + " NOT IN (:" + p.param() + ")";
            case IS_NULL -> col + " IS NULL";
            case IS_NOT_NULL -> col + " IS NOT NULL";
            case TRUE -> col + " = TRUE";
            case FALSE -> col + " = FALSE";
            case IGNORE_CASE_EQ -> "LOWER(" + col + ") = LOWER(:" + p.param() + ")";
        };
    }
}
