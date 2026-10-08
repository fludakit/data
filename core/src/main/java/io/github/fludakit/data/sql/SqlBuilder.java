package io.github.fludakit.data.sql;

import io.github.fludakit.data.Names;
import io.github.fludakit.data.metadata.ColumnRef;
import io.github.fludakit.data.metadata.EntityDescriptor;
import io.github.fludakit.data.metadata.EntitySchema;
import io.github.fludakit.data.metadata.JoinRef;
import io.github.fludakit.data.metamodel.ElementCollectionAttribute;
import io.github.fludakit.data.metamodel.OneToManyAttribute;
import io.github.fludakit.data.query.Operator;
import io.github.fludakit.data.query.Predicate;
import io.github.fludakit.data.query.SortSpec;
import jakarta.data.metamodel.Attribute;

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
     * The deduplicated {@code LEFT JOIN} chain these refs require. Joins are chained PER PATH:
     * within one ref's joins list, the first join is qualified by the root table and every later
     * join by the previous join's alias IN THAT SAME ref. Across refs, joins are deduplicated by
     * alias (first appearance wins) and emitted in first-appearance order, so a shared head join
     * keeps a single fragment while each ref's divergent tail chains off its own predecessor.
     * Each fragment is
     * {@code  LEFT JOIN "<table>" AS "<alias>" ON <qualifier>."<parentColumn>" = "<alias>"."<childColumn>";}
     * Returns {@code ""} when no ref carries a join.
     */
    public static String joinClause(EntitySchema md, List<ColumnRef> refs) {
        Map<String, JoinRef> byAlias = new LinkedHashMap<>();
        StringBuilder sb = new StringBuilder();
        for (ColumnRef ref : refs) {
            // Task 6 (R-T6): a sort/paging ref whose head is to-many renders as an aggregate
            // subquery (see aggregateSortTerm), NEVER a fanning LEFT JOIN. Only runtime
            // descriptors can answer that; compile-time callers already filter existentials.
            if (md instanceof EntityDescriptor<?> descriptor && isToManyHead(descriptor, ref)) {
                continue;
            }
            String previous = null;
            for (JoinRef join : ref.joins()) {
                if (byAlias.containsKey(join.alias())) {
                    previous = join.alias();
                    continue;
                }
                byAlias.put(join.alias(), join);
                String qualifier = previous == null ? Names.quote(md.tableName()) : Names.quote(previous);
                previous = join.alias();
                String alias = Names.quote(join.alias());
                sb.append(" LEFT JOIN ").append(Names.quote(join.table()))
                        .append(" AS ").append(alias)
                        .append(" ON ").append(qualifier).append('.').append(Names.quote(join.parentColumn()))
                        .append(" = ").append(alias).append('.').append(Names.quote(join.childColumn()));
            }
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
                : qualify(ref, null);
        return renderOperator(p.operator(), col, p.param());
    }

    /**
     * The EXISTS semi-join conjunct for a predicate whose head is TO-MANY (one-to-many or element
     * collection): instead of splicing a LEFT JOIN - which fans root rows out - the statement asks
     * whether the child table holds a row whose alias-side column ({@code aliasColumn}) equals the
     * root-side column ({@code rootColumn}) - the root id for a to-many head, the owner-side FK for
     * a to-one head - and matching the leaf predicate. The leaf term is exactly
     * {@link #render(Predicate, ColumnRef)}'s alias-qualified rendering, so the named parameter (and
     * the caller's binding code) is unchanged. Every identifier is {@link Names#quote}d.
     */
    public static String existsClause(String rootTable, String rootColumn, String childTable,
                                      String alias, String aliasColumn, Predicate p, ColumnRef leafRef) {
        return "EXISTS (SELECT 1 FROM " + Names.quote(childTable) + " " + Names.quote(alias)
                + " WHERE " + Names.quote(alias) + "." + Names.quote(aliasColumn)
                + " = " + Names.quote(rootTable) + "." + Names.quote(rootColumn)
                + " AND " + render(p, leafRef) + ")";
    }

    /**
     * True when {@code ref}'s relation head — the FIRST dot segment of its path, joins required — is a
     * to-many attribute ({@link OneToManyAttribute} or {@link ElementCollectionAttribute}) on the
     * descriptor. Sorts through such heads render {@link #aggregateSortTerm} instead of joining; a
     * head the descriptor does not know is {@code false} (the join clause and callers keep their
     * existing rejection behaviour).
     */
    public static boolean isToManyHead(EntityDescriptor<?> md, ColumnRef ref) {
        if (ref.joins().isEmpty()) {
            return false;
        }
        int dot = ref.path().indexOf('.');
        if (dot < 0) {
            return false;
        }
        String head = ref.path().substring(0, dot);
        try {
            Attribute<?> attribute = md.attribute(head);
            return attribute instanceof OneToManyAttribute || attribute instanceof ElementCollectionAttribute;
        } catch (IllegalArgumentException unknownProperty) {
            return false;
        }
    }

    /**
     * The correlated scalar subquery a to-many sort orders by: {@code MIN} of the leaf column for an
     * ascending key, {@code MAX} for a descending one — exactly the edge the descriptor-driven cursor
     * key reduction (Task 4) collapses the value list to, so seek and sort agree. The head join opens
     * the subquery {@code FROM} (alias WITHOUT {@code AS}, mirroring nothing else on the statement);
     * deeper edges chain inside as the same {@code LEFT JOIN ... AS ... ON ...} fragments
     * {@link #joinClause} emits, qualified per-path. Correlation is the head's
     * {@code childFk = rootTable.rootId} pair taken from its {@link JoinRef}. Every identifier goes
     * through {@link Names#quote}; the result is used VERBATIM as an ORDER BY / seek term.
     */
    public static String aggregateSortTerm(EntitySchema md, ColumnRef ref, boolean ascending) {
        if (ref.joins().isEmpty()) {
            throw new IllegalArgumentException(
                    "aggregate sort term requires a relation path: " + ref.path());
        }
        JoinRef head = ref.joins().get(0);
        StringBuilder sb = new StringBuilder("(SELECT ")
                .append(ascending ? "MIN(" : "MAX(")
                .append(qualify(ref, md.tableName()))
                .append(") FROM ").append(Names.quote(head.table()))
                .append(' ').append(Names.quote(head.alias()));
        String previous = head.alias();
        for (int i = 1; i < ref.joins().size(); i++) {
            JoinRef join = ref.joins().get(i);
            sb.append(" LEFT JOIN ").append(Names.quote(join.table()))
                    .append(" AS ").append(Names.quote(join.alias()))
                    .append(" ON ").append(Names.quote(previous)).append('.').append(Names.quote(join.parentColumn()))
                    .append(" = ").append(Names.quote(join.alias())).append('.').append(Names.quote(join.childColumn()));
            previous = join.alias();
        }
        sb.append(" WHERE ").append(Names.quote(head.alias())).append('.').append(Names.quote(head.childColumn()))
                .append(" = ").append(Names.quote(md.tableName())).append('.').append(Names.quote(head.parentColumn()))
                .append(')');
        return sb.toString();
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
